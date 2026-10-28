/*
 * Copyright 2026 EhViewer Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.transfer.index;

import android.content.Context;
import android.content.SharedPreferences;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.transfer.core.DataChangeBus;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 画廊索引的持久化状态（协议 §5.29.7）。
 *
 * <p>保存 {@code epoch}、每个 scope 的桶范围与 {@code (bucket → version, fp)}。
 * 版本号来自 {@link DataChangeBus}，与 §5.28 共用同一个单调计数器。
 *
 * <p><b>变更发现方式</b>：构建清单时先按当前数据算出每个桶的内部指纹，与上次保存的
 * 指纹比较；不一致的桶才推进版本。这样无论画廊是被传输 API、手机界面还是一次性
 * 批量导入修改的，都不会漏报，也不需要在每条写路径上手工打点。
 */
public final class GalleryIndexStore {

    private static final String TAG = "GalleryIndexStore";

    private static final String PREFS_NAME = "gallery_index";
    private static final String KEY_INSTALL_ID = "install_id";
    private static final String KEY_EPOCH_SALT = "epoch_salt";
    private static final String STATE_FILE = "gallery_index.json";

    /** 单个 scope 位图允许覆盖的最大桶数，防止异常 GID 把位图撑爆。 */
    private static final int MAX_BIT_COUNT = 1 << 22;

    private static volatile GalleryIndexStore sInstance;

    public static GalleryIndexStore get(Context context) {
        GalleryIndexStore instance = sInstance;
        if (instance == null) {
            synchronized (GalleryIndexStore.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new GalleryIndexStore(context.getApplicationContext());
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    /** 单个桶的状态；{@code fp} 为空串表示"曾经存在、现在为空"的墓碑。 */
    private static final class BucketState {
        long version;
        String fp;

        BucketState(long version, String fp) {
            this.version = version;
            this.fp = fp == null ? "" : fp;
        }
    }

    private static final class ScopeState {
        boolean initialized;
        long version;
        int baseBucket = -1;
        int bitCount;
        final TreeMap<Integer, BucketState> buckets = new TreeMap<>();
    }

    /** {@link #updateScope} 的结果。 */
    public static final class ScopeUpdate {
        public final long globalVersion;
        public final long startVersion;
        public final long scopeVersion;
        public final int baseBucket;
        public final int bitCount;
        public final boolean snapshotConsistent;
        public final TreeMap<Integer, Long> bucketVersions;

        ScopeUpdate(long globalVersion, long startVersion, long scopeVersion,
                    int baseBucket, int bitCount, boolean snapshotConsistent,
                    TreeMap<Integer, Long> bucketVersions) {
            this.globalVersion = globalVersion;
            this.startVersion = startVersion;
            this.scopeVersion = scopeVersion;
            this.baseBucket = baseBucket;
            this.bitCount = bitCount;
            this.snapshotConsistent = snapshotConsistent;
            this.bucketVersions = bucketVersions;
        }

        /** 自 {@code since} 之后发生变化的桶（升序）。 */
        public TreeSet<Integer> changedBuckets(long since) {
            TreeSet<Integer> changed = new TreeSet<>();
            for (Map.Entry<Integer, Long> entry : bucketVersions.entrySet()) {
                Long version = entry.getValue();
                if (version != null && version > since) {
                    changed.add(entry.getKey());
                }
            }
            return changed;
        }
    }

    private final File stateFile;
    private final SharedPreferences prefs;
    private final LinkedHashMap<String, ScopeState> scopes = new LinkedHashMap<>();

    private String epoch;
    private boolean loaded;

    private GalleryIndexStore(Context appContext) {
        this.stateFile = new File(appContext.getFilesDir(), STATE_FILE);
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        DataChangeBus.init(appContext);
    }

    public int getBucketSize() {
        return BucketLayout.DEFAULT_BUCKET_SIZE;
    }

    public synchronized long getVersion() {
        return DataChangeBus.getVersion();
    }

    /** 当前数据集身份；首次调用时生成并落盘。 */
    public synchronized String getEpoch() {
        ensureLoadedLocked();
        return epoch;
    }

    /** 某 scope 最近一次内容变化对应的全局版本；从未构建过清单时为 0。 */
    public synchronized long getScopeVersion(String scopeKey) {
        ensureLoadedLocked();
        ScopeState state = scopes.get(scopeKey);
        return state == null ? 0L : state.version;
    }

    /** 某桶最近一次变化对应的版本；未知桶为 0。 */
    public synchronized long getBucketVersion(String scopeKey, int bucket) {
        ensureLoadedLocked();
        ScopeState state = scopes.get(scopeKey);
        if (state == null) {
            return 0L;
        }
        BucketState bucketState = state.buckets.get(bucket);
        return bucketState == null ? 0L : bucketState.version;
    }

    /** 所有已知桶的版本快照（升序）。 */
    public synchronized TreeMap<Integer, Long> getBucketVersions(String scopeKey) {
        ensureLoadedLocked();
        TreeMap<Integer, Long> result = new TreeMap<>();
        ScopeState state = scopes.get(scopeKey);
        if (state != null) {
            for (Map.Entry<Integer, BucketState> entry : state.buckets.entrySet()) {
                result.put(entry.getKey(), entry.getValue().version);
            }
        }
        return result;
    }

    /**
     * 刷新单个桶的内容指纹；桶在两次分页请求之间发生变化时推进版本，
     * 使 {@code expectedVersion} 校验能够发现跨页改动（协议 §5.29.5）。
     *
     * @return 该桶当前版本
     */
    public synchronized long refreshBucket(String scopeKey, int bucket, String currentFp) {
        ensureLoadedLocked();
        ScopeState state = scopes.get(scopeKey);
        if (state == null) {
            state = new ScopeState();
            state.initialized = true;
            scopes.put(scopeKey, state);
        }
        BucketState old = state.buckets.get(bucket);
        String oldFp = old == null ? null : normalizeFp(old.fp);
        String newFp = normalizeFp(currentFp);
        if (!Objects.equals(oldFp, newFp)) {
            long version = DataChangeBus.touch();
            state.buckets.put(bucket, new BucketState(version, currentFp));
            state.version = version;
            // 位图范围只由 manifest 构建维护，单桶刷新不扩大范围，避免越界查询把位图撑大。
            saveLocked();
        }
        BucketState current = state.buckets.get(bucket);
        return current == null ? 0L : current.version;
    }

    /**
     * 用本次扫描结果更新 scope 的桶状态，必要时推进全局版本。
     *
     * @param scopeKey    scope 唯一键（{@code downloads} / {@code favorites} / {@code label:名称}）
     * @param currentFps  当前每个非空桶的内部指纹（桶号升序）
     * @param startVersion 扫描开始前的全局版本，用于 {@code snapshotConsistent}
     */
    public synchronized ScopeUpdate updateScope(String scopeKey,
                                                TreeMap<Integer, String> currentFps,
                                                long startVersion) {
        ensureLoadedLocked();

        ScopeState state = scopes.get(scopeKey);
        if (state == null) {
            state = new ScopeState();
            scopes.put(scopeKey, state);
        }

        TreeSet<Integer> allBuckets = new TreeSet<>(state.buckets.keySet());
        allBuckets.addAll(currentFps.keySet());

        TreeSet<Integer> dirtyBuckets = new TreeSet<>();
        for (int bucket : allBuckets) {
            BucketState old = state.buckets.get(bucket);
            String oldFp = old == null ? null : normalizeFp(old.fp);
            String newFp = normalizeFp(currentFps.get(bucket));
            if (!Objects.equals(oldFp, newFp)) {
                dirtyBuckets.add(bucket);
            }
        }

        boolean rangeChanged = false;
        if (!currentFps.isEmpty()) {
            int minBucket = currentFps.firstKey();
            int maxBucket = currentFps.lastKey();
            if (state.baseBucket < 0) {
                state.baseBucket = minBucket;
                state.bitCount = maxBucket - minBucket + 1;
                rangeChanged = true;
            } else {
                int newBase = Math.min(state.baseBucket, minBucket);
                int newBits = Math.max(state.bitCount, maxBucket - newBase + 1);
                if (newBase != state.baseBucket || newBits != state.bitCount) {
                    state.baseBucket = newBase;
                    state.bitCount = newBits;
                    rangeChanged = true;
                }
            }
            if (state.bitCount > MAX_BIT_COUNT) {
                // 病态 GID（极小与极大值同时存在）时收缩范围，优先保证可用性。
                TransferLogger.getInstance().w(TAG, "位图范围过大(" + state.bitCount + ")，收缩到 " + MAX_BIT_COUNT);
                state.baseBucket = maxBucket - MAX_BIT_COUNT + 1;
                state.bitCount = MAX_BIT_COUNT;
                rangeChanged = true;
            }
        }

        boolean dirty = !state.initialized || !dirtyBuckets.isEmpty() || rangeChanged;
        if (!state.initialized) {
            // 首次构建：把所有非空桶视为已变化，客户端本来就没有旧状态。
            dirtyBuckets.addAll(currentFps.keySet());
            state.initialized = true;
        }

        // 扫描期间是否有其它写操作推进了版本：只把"外部变化"算作快照不一致，
        // 本方法自己 touch 造成的版本前进不算。
        boolean snapshotConsistent = DataChangeBus.getVersion() == startVersion;

        if (!dirtyBuckets.isEmpty() || state.version == 0L) {
            long version = DataChangeBus.touch();
            for (int bucket : dirtyBuckets) {
                String fp = currentFps.get(bucket);
                state.buckets.put(bucket, new BucketState(version, fp));
            }
            state.version = version;
            dirty = true;
        }

        if (dirty) {
            saveLocked();
        }

        TreeMap<Integer, Long> versions = new TreeMap<>();
        for (Map.Entry<Integer, BucketState> entry : state.buckets.entrySet()) {
            versions.put(entry.getKey(), entry.getValue().version);
        }
        return new ScopeUpdate(DataChangeBus.getVersion(), startVersion, state.version,
                state.baseBucket, state.bitCount, snapshotConsistent, versions);
    }

    /**
     * 更换 epoch（数据库导入/恢复、索引重建后调用）。
     *
     * <p>客户端看到 epoch 变化会清空本地桶状态并全量重同步。
     */
    public synchronized String rotateEpoch() {
        ensureLoadedLocked();
        long salt = prefs.getLong(KEY_EPOCH_SALT, 0L);
        long nextSalt = salt * 31L + System.currentTimeMillis() + 1L;
        prefs.edit().putLong(KEY_EPOCH_SALT, nextSalt).apply();
        epoch = buildEpoch(nextSalt);
        scopes.clear();
        saveLocked();
        DataChangeBus.touch();
        TransferLogger.getInstance().i(TAG, "画廊索引 epoch 已更换: " + epoch);
        return epoch;
    }

    // ==================== 持久化 ====================

    private void ensureLoadedLocked() {
        if (loaded) {
            return;
        }
        loaded = true;
        boolean ok = false;
        boolean hadState = stateFile.isFile();
        if (hadState) {
            try {
                byte[] data = readFile(stateFile);
                JSONObject root = JSON.parseObject(new String(data, StandardCharsets.UTF_8));
                if (root != null) {
                    int bucketSize = root.getIntValue("bucketSize");
                    String storedEpoch = root.getString("epoch");
                    if (bucketSize == BucketLayout.DEFAULT_BUCKET_SIZE
                            && storedEpoch != null && !storedEpoch.isEmpty()) {
                        epoch = storedEpoch;
                        JSONObject scopesJson = root.getJSONObject("scopes");
                        if (scopesJson != null) {
                            for (String key : scopesJson.keySet()) {
                                scopes.put(key, parseScope(scopesJson.getJSONObject(key)));
                            }
                        }
                        ok = true;
                    }
                }
            } catch (Throwable t) {
                TransferLogger.getInstance().w(TAG, "画廊索引状态读取失败，将重新生成: " + t.getMessage());
            }
        }
        if (!ok) {
            // 状态文件存在但不可解析：视为索引重建，更换 epoch 让客户端全量重同步。
            if (hadState) {
                long nextSalt = prefs.getLong(KEY_EPOCH_SALT, 0L) * 31L
                        + System.currentTimeMillis() + 1L;
                prefs.edit().putLong(KEY_EPOCH_SALT, nextSalt).apply();
                epoch = buildEpoch(nextSalt);
            } else {
                epoch = buildEpoch(prefs.getLong(KEY_EPOCH_SALT, 0L));
            }
            scopes.clear();
            saveLocked();
        }
    }

    private static ScopeState parseScope(JSONObject json) {
        ScopeState state = new ScopeState();
        if (json == null) {
            return state;
        }
        state.initialized = json.getBooleanValue("initialized");
        state.version = json.getLongValue("version");
        state.baseBucket = json.containsKey("base") ? json.getIntValue("base") : -1;
        state.bitCount = json.getIntValue("bits");
        JSONObject buckets = json.getJSONObject("buckets");
        if (buckets != null) {
            for (String key : buckets.keySet()) {
                try {
                    int bucket = Integer.parseInt(key);
                    JSONObject item = buckets.getJSONObject(key);
                    if (item != null) {
                        state.buckets.put(bucket,
                                new BucketState(item.getLongValue("v"), item.getString("fp")));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return state;
    }

    private void saveLocked() {
        try {
            JSONObject root = new JSONObject(true);
            root.put("epoch", epoch);
            root.put("bucketSize", BucketLayout.DEFAULT_BUCKET_SIZE);
            JSONObject scopesJson = new JSONObject(true);
            for (Map.Entry<String, ScopeState> entry : scopes.entrySet()) {
                ScopeState state = entry.getValue();
                JSONObject scopeJson = new JSONObject(true);
                scopeJson.put("initialized", state.initialized);
                scopeJson.put("version", state.version);
                scopeJson.put("base", state.baseBucket);
                scopeJson.put("bits", state.bitCount);
                JSONObject bucketsJson = new JSONObject(true);
                for (Map.Entry<Integer, BucketState> bucketEntry : state.buckets.entrySet()) {
                    JSONObject item = new JSONObject(true);
                    item.put("v", bucketEntry.getValue().version);
                    item.put("fp", bucketEntry.getValue().fp);
                    bucketsJson.put(String.valueOf(bucketEntry.getKey()), item);
                }
                scopeJson.put("buckets", bucketsJson);
                scopesJson.put(entry.getKey(), scopeJson);
            }
            root.put("scopes", scopesJson);

            byte[] data = root.toJSONString().getBytes(StandardCharsets.UTF_8);
            File parent = stateFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                TransferLogger.getInstance().w(TAG, "无法创建索引状态目录: " + parent);
            }
            File tmp = new File(stateFile.getPath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
                fos.flush();
                fos.getFD().sync();
            }
            if (!tmp.renameTo(stateFile)) {
                // rename 在部分文件系统上对已存在目标可能失败，退化为覆盖写
                try (FileOutputStream fos = new FileOutputStream(stateFile)) {
                    fos.write(data);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable t) {
            TransferLogger.getInstance().w(TAG, "画廊索引状态保存失败: " + t.getMessage());
        }
    }

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, file.length()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }

    private String buildEpoch(long salt) {
        String installId = prefs.getString(KEY_INSTALL_ID, null);
        if (installId == null || installId.isEmpty()) {
            installId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_INSTALL_ID, installId).apply();
        }
        String seed = installId + "|eh.db|" + BucketLayout.DEFAULT_BUCKET_SIZE
                + "|" + BucketLayout.FORMAT_VERSION + "|" + salt;
        return BucketLayout.fnv1a32(seed);
    }

    private static String normalizeFp(String fp) {
        return fp == null || fp.isEmpty() ? null : fp;
    }

    /** 应用包名相同、进程重启后仍保持的状态目录；供诊断端点使用。 */
    public File getStateFile() {
        return stateFile;
    }
}
