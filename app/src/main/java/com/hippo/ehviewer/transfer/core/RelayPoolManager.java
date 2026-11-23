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

package com.hippo.ehviewer.transfer.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.ehviewer.transfer.data.RelayTask;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.unifile.UniFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 接力下载 v2 控制面 + 数据面（协议 §5.17）。
 *
 * <p>候选池是派生视图：每次读取时按 {@link DownloadInfo} 与磁盘实际页集合实时推导，
 * 不单独持久化，避免池与真实进度不一致。租约、任务由 {@link RelayTaskManager} 持久化。
 */
public final class RelayPoolManager {

    private static final String TAG = "RelayPoolManager";
    private static final String PREFS_NAME = "relay_pool_v2";
    private static final String KEY_POOL_VERSION = "pool_version";

    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_FORBIDDEN = 403;
    public static final int CODE_NOT_FOUND = 404;
    public static final int CODE_CONFLICT = 409;
    public static final int CODE_PRECONDITION = 412;
    public static final int CODE_LEASE_INVALID = 428;
    public static final int CODE_OVERLOAD = 503;

    private static volatile RelayPoolManager sInstance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final RelayTaskManager taskManager;
    private final DownloadManager downloadManager;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "RelayLeaseScanner");
                t.setDaemon(true);
                return t;
            });

    private final Object claimLock = new Object();
    private final Object versionLock = new Object();
    private final Set<String> finalizingGids = ConcurrentHashMap.newKeySet();

    /** requestId -> 首次响应 JSON（LRU 256）。 */
    private final Map<String, String> idempotency = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 256;
                }
            });

    /** gid:page -> 单写者锁。 */
    private static final Map<String, Object> PAGE_LOCKS = new HashMap<>();
    private static final int MAX_PAGE_LOCKS = 4096;

    private volatile long poolVersion;

    private RelayPoolManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.poolVersion = prefs.getLong(KEY_POOL_VERSION, 0L);
        this.taskManager = RelayTaskManager.getInstance(appContext);
        this.downloadManager = EhApplication.getDownloadManager(appContext);
        scheduler.scheduleWithFixedDelay(this::scanExpiredLeasesSafely,
                30L, 30L, TimeUnit.SECONDS);
    }

    public static RelayPoolManager getInstance(Context context) {
        RelayPoolManager instance = sInstance;
        if (instance == null) {
            synchronized (RelayPoolManager.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new RelayPoolManager(context);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    // ==================== 池版本与事件 ====================

    public long getPoolVersion() {
        synchronized (versionLock) {
            return poolVersion;
        }
    }

    /**
     * 池/任务/页进度变化：poolVersion +1，并广播 relay_pool_changed。
     */
    public long bumpPoolVersion(@Nullable Long gid, @Nullable String reason) {
        long version;
        synchronized (versionLock) {
            version = ++poolVersion;
            prefs.edit().putLong(KEY_POOL_VERSION, version).apply();
        }
        StringBuilder fields = new StringBuilder(96);
        fields.append("\"poolVersion\":").append(version);
        if (gid != null && gid > 0) {
            fields.append(",\"gid\":").append(gid);
        }
        if (reason != null) {
            fields.append(",\"reason\":").append(DataChangeBus.quote(reason));
        }
        DataChangeBus.publishFields(DataChangeBus.TYPE_RELAY_POOL_CHANGED, fields.toString());
        return version;
    }

    public static int clampLeaseSeconds(int requested) {
        int value = requested > 0 ? requested : Settings.getRelayLeaseSeconds();
        if (value < 60) value = 60;
        if (value > 3600) value = 3600;
        return value;
    }

    // ==================== 页级事实 ====================

    public static final class PageFacts {
        public int totalPages;
        public int downloadedCount;
        public boolean[] downloaded;
        public List<Integer> missingPages = new ArrayList<>();
        public UniFile dir;
        public SpiderInfo spiderInfo;
        public long totalSize;
    }

    @NonNull
    public PageFacts scan(@Nullable DownloadInfo info) {
        PageFacts facts = new PageFacts();
        if (info == null) {
            return facts;
        }
        facts.dir = SpiderDen.getExistingGalleryDownloadDir(info);
        facts.spiderInfo = readSpiderInfo(facts.dir);
        int total = info.pages > 0 ? info.pages : info.total;
        if (total <= 0 && facts.spiderInfo != null && facts.spiderInfo.pages > 0) {
            total = facts.spiderInfo.pages;
        }
        if (total <= 0) {
            return facts;
        }
        facts.totalPages = total;
        facts.downloaded = new boolean[total];
        if (facts.dir != null && facts.dir.isDirectory()) {
            for (int i = 0; i < total; i++) {
                UniFile file = SpiderDen.findImageFile(facts.dir, i);
                if (file != null) {
                    facts.downloaded[i] = true;
                    facts.downloadedCount++;
                    long len = file.length();
                    if (len > 0) {
                        facts.totalSize += len;
                    }
                } else {
                    facts.missingPages.add(i + 1);
                }
            }
        } else {
            for (int i = 0; i < total; i++) {
                facts.missingPages.add(i + 1);
            }
        }
        return facts;
    }

    @Nullable
    private SpiderInfo readSpiderInfo(@Nullable UniFile dir) {
        if (dir == null) {
            return null;
        }
        try {
            UniFile file = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME);
            return file != null ? SpiderInfo.read(file) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 以磁盘为准回写 DownloadInfo 计数（§5.17.15 进度口径）。 */
    public void reconcile(@Nullable DownloadInfo info, @NonNull PageFacts facts) {
        if (info == null || facts.totalPages <= 0) {
            return;
        }
        boolean changed = false;
        if (info.total != facts.totalPages) {
            info.total = facts.totalPages;
            changed = true;
        }
        if (info.pages <= 0) {
            info.pages = facts.totalPages;
            changed = true;
        }
        if (info.finished != facts.downloadedCount) {
            info.finished = facts.downloadedCount;
            changed = true;
        }
        info.downloaded = facts.downloadedCount;
        if (facts.totalSize > 0) {
            info.fileSize = facts.totalSize;
        }
        if (changed) {
            try {
                EhDB.putDownloadInfo(info);
            } catch (Throwable t) {
                Log.w(TAG, "reconcile: persist failed gid=" + info.gid, t);
            }
        }
    }

    public static String stateName(int state) {
        switch (state) {
            case DownloadInfo.STATE_NONE: return "none";
            case DownloadInfo.STATE_WAIT: return "waiting";
            case DownloadInfo.STATE_DOWNLOAD: return "downloading";
            case DownloadInfo.STATE_FINISH: return "finished";
            case DownloadInfo.STATE_FAILED: return "failed";
            case DownloadInfo.STATE_UPDATE: return "updating";
            case DownloadInfo.STATE_RELAY_DOWNLOAD: return "relay_download";
            default: return "unknown";
        }
    }

    // ==================== 池查询 ====================

    public static final class PoolQuery {
        public Set<Integer> states = new LinkedHashSet<>();
        public String stateRaw;
        public int limit = 50;
        public String cursor;
        public boolean includeComplete;
        public String includePages = "bitmap";
        public boolean includeMetaPack;
        public long updatedSince;
        public String compress = "none";
        public String sort = "updatedAt:desc";
    }

    @NonNull
    public static PoolQuery parseQuery(@Nullable String state, int limit, @Nullable String cursor,
                                       boolean includeComplete, @Nullable String includePages,
                                       boolean includeMetaPack, long updatedSince,
                                       @Nullable String compress, @Nullable String sort) {
        PoolQuery q = new PoolQuery();
        q.stateRaw = state;
        q.limit = limit <= 0 ? 50 : Math.min(limit, Settings.getRelayPoolMaxItems());
        q.cursor = cursor;
        q.includeComplete = includeComplete;
        q.includePages = includePages == null ? "bitmap" : includePages;
        q.includeMetaPack = includeMetaPack;
        q.updatedSince = updatedSince;
        q.compress = compress == null ? "none" : compress;
        q.sort = sort == null ? "updatedAt:desc" : sort;
        parseStates(q);
        return q;
    }

    private static void parseStates(PoolQuery q) {
        String raw = q.stateRaw;
        if (raw == null || raw.trim().isEmpty()) {
            raw = "downloading,waiting,failed";
        }
        for (String part : raw.split(",")) {
            String s = part.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            switch (s) {
                case "none": q.states.add(DownloadInfo.STATE_NONE); break;
                case "waiting": case "paused": case "wait": q.states.add(DownloadInfo.STATE_WAIT); break;
                case "downloading": case "download": q.states.add(DownloadInfo.STATE_DOWNLOAD); break;
                case "finished": case "complete": q.states.add(DownloadInfo.STATE_FINISH); break;
                case "failed": q.states.add(DownloadInfo.STATE_FAILED); break;
                case "updating": q.states.add(DownloadInfo.STATE_UPDATE); break;
                case "relay_download": case "relay": q.states.add(DownloadInfo.STATE_RELAY_DOWNLOAD); break;
                default:
                    try {
                        q.states.add(Integer.parseInt(s));
                    } catch (NumberFormatException ignored) {
                    }
            }
        }
        if (q.includeComplete) {
            q.states.add(DownloadInfo.STATE_FINISH);
        }
    }

    public static final class PoolList {
        public final List<DownloadInfo> items = new ArrayList<>();
        public final List<PageFacts> facts = new ArrayList<>();
        public int total;
        public boolean hasMore;
        public String nextCursor;
        public long poolVersion;
        public long generatedAt;
    }

    @NonNull
    public PoolList list(PoolQuery q) {
        PoolList result = new PoolList();
        result.poolVersion = getPoolVersion();
        result.generatedAt = System.currentTimeMillis();
        List<DownloadInfo> all = downloadManager.getAllDownloadInfoList();
        List<DownloadInfo> filtered = new ArrayList<>();
        if (all != null) {
            for (DownloadInfo info : all) {
                if (info == null || info.archiveUri != null) {
                    continue;
                }
                if (!q.includeComplete && info.state == DownloadInfo.STATE_FINISH) {
                    continue;
                }
                if (!q.states.isEmpty() && !q.states.contains(info.state)) {
                    continue;
                }
                if (q.updatedSince > 0 && info.time <= q.updatedSince) {
                    continue;
                }
                if (info.token == null || info.token.isEmpty()) {
                    continue;
                }
                filtered.add(info);
            }
        }
        Comparator<DownloadInfo> comparator = comparatorFor(q.sort);
        filtered.sort(comparator);
        result.total = filtered.size();

        int startIndex = 0;
        if (q.cursor != null && !q.cursor.isEmpty()) {
            Cursor cursor = Cursor.parse(q.cursor);
            if (cursor != null) {
                for (int i = 0; i < filtered.size(); i++) {
                    DownloadInfo info = filtered.get(i);
                    if (info.gid == cursor.gid) {
                        startIndex = i + 1;
                        break;
                    }
                }
            }
        }
        int end = Math.min(filtered.size(), startIndex + q.limit);
        for (int i = startIndex; i < end; i++) {
            DownloadInfo info = filtered.get(i);
            PageFacts facts = scan(info);
            reconcile(info, facts);
            result.items.add(info);
            result.facts.add(facts);
        }
        result.hasMore = end < filtered.size();
        if (result.hasMore && !result.items.isEmpty()) {
            DownloadInfo last = result.items.get(result.items.size() - 1);
            result.nextCursor = last.gid + ":" + last.time;
        }
        return result;
    }

    private static Comparator<DownloadInfo> comparatorFor(String sort) {
        String s = sort == null ? "updatedAt:desc" : sort;
        if ("gid:asc".equals(s)) {
            return (a, b) -> Long.compare(a.gid, b.gid);
        }
        if ("missing:desc".equals(s)) {
            return (a, b) -> {
                int ma = Math.max(0, (a.pages > 0 ? a.pages : a.total) - a.finished);
                int mb = Math.max(0, (b.pages > 0 ? b.pages : b.total) - b.finished);
                int cmp = Integer.compare(mb, ma);
                return cmp != 0 ? cmp : Long.compare(b.time, a.time);
            };
        }
        // updatedAt:desc
        return (a, b) -> Long.compare(b.time, a.time);
    }

    public static final class Cursor {
        public final long gid;
        public final long updatedAt;

        Cursor(long gid, long updatedAt) {
            this.gid = gid;
            this.updatedAt = updatedAt;
        }

        @Nullable
        public static Cursor parse(@Nullable String raw) {
            if (raw == null) return null;
            int idx = raw.indexOf(':');
            if (idx <= 0) return null;
            try {
                return new Cursor(Long.parseLong(raw.substring(0, idx)),
                        Long.parseLong(raw.substring(idx + 1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    @NonNull
    public JSONObject poolItemJson(@NonNull DownloadInfo info, @NonNull PageFacts facts,
                                   @NonNull PoolQuery q) {
        JSONObject g = new JSONObject();
        int total = facts.totalPages > 0 ? facts.totalPages
                : Math.max(info.pages, info.total);
        g.put("gid", info.gid);
        g.put("token", info.token);
        g.put("title", info.title);
        g.put("titleJpn", info.titleJpn);
        g.put("category", info.category);
        g.put("posted", info.posted);
        g.put("uploader", info.uploader);
        g.put("rating", info.rating);
        g.put("pages", total);
        g.put("totalPages", total);
        g.put("downloadedPages", facts.downloadedCount);
        g.put("missingCount", Math.max(0, total - facts.downloadedCount));
        String includePages = q.includePages == null ? "bitmap" : q.includePages;
        if (!"none".equals(includePages)) {
            g.put("pagesBitmap", RelayPageBitmap.encode(facts.downloaded, total));
        }
        if ("list".equals(includePages) || "all".equals(includePages)
                || ("bitmap".equals(includePages) && facts.missingPages.size() <= 64)) {
            JSONArray missing = new JSONArray();
            for (Integer page : facts.missingPages) missing.add(page);
            g.put("missingPages", missing);
        }
        g.put("state", info.state);
        g.put("stateName", stateName(info.state));
        g.put("size", facts.totalSize);
        g.put("totalSize", info.fileSize > 0 ? info.fileSize : facts.totalSize);
        g.put("galleryUrl", EhUrl.getGalleryDetailUrl(info.gid, info.token));
        g.put("site", Settings.getGallerySite() == EhUrl.SITE_EX ? "ex" : "e");
        int missingCount = Math.max(0, total - facts.downloadedCount);
        g.put("priorityHint", missingCount > 0 && missingCount <= 3 ? "high" : "normal");
        RelayTask leaseTask = taskManager.getActiveLeaseTaskByGid(info.gid);
        g.put("lease", leaseTask != null ? leaseJson(leaseTask) : null);
        g.put("updatedAt", info.time);
        return g;
    }

    @Nullable
    public static JSONObject leaseJson(@NonNull RelayTask task) {
        if (!task.hasActiveLease()) {
            return null;
        }
        JSONObject lease = new JSONObject();
        lease.put("deviceId", task.getLeaseDeviceId() != null
                ? task.getLeaseDeviceId() : task.getAcceptedDeviceId());
        lease.put("deviceName", task.getLeaseDeviceName() != null
                ? task.getLeaseDeviceName() : task.getAcceptedDevice());
        lease.put("taskId", task.getTaskId());
        lease.put("expiresAt", task.getLeaseExpiresAt());
        return lease;
    }

    // ==================== 池渲染（流式 / gzip） ====================

    public interface ChunkSink {
        void write(String chunk) throws IOException;
    }

    /**
     * 流式渲染池 JSON。调用方决定把 chunk 写入 StringBuilder 还是 GZIPOutputStream。
     */
    public void renderPool(@NonNull PoolQuery q, @NonNull ChunkSink sink) throws IOException {
        PoolList list = list(q);
        sink.write("{\"success\":true");
        sink.write(",\"poolVersion\":" + list.poolVersion);
        sink.write(",\"etag\":" + JSON.toJSONString(etag(q)));
        sink.write(",\"generatedAt\":" + list.generatedAt);
        sink.write(",\"compress\":" + JSON.toJSONString(q.compress));
        sink.write(",\"total\":" + list.total);
        sink.write(",\"hasMore\":" + list.hasMore);
        sink.write(",\"nextCursor\":"
                + (list.nextCursor == null ? "null" : JSON.toJSONString(list.nextCursor)));
        sink.write(",\"galleries\":[");
        for (int i = 0; i < list.items.size(); i++) {
            if (i > 0) sink.write(",");
            sink.write(poolItemJson(list.items.get(i), list.facts.get(i), q).toJSONString());
        }
        sink.write("]");
        if (q.includeMetaPack) {
            appendMetaPack(list, sink);
        }
        sink.write("}");
    }

    private void appendMetaPack(PoolList list, ChunkSink sink) throws IOException {
        JSONArray array = new JSONArray();
        for (int i = 0; i < list.items.size(); i++) {
            DownloadInfo info = list.items.get(i);
            PageFacts facts = list.facts.get(i);
            JSONObject meta = new JSONObject();
            meta.put("gid", info.gid);
            meta.put("token", info.token);
            meta.put("title", info.title);
            meta.put("titleJpn", info.titleJpn);
            meta.put("category", info.category);
            meta.put("posted", info.posted);
            meta.put("uploader", info.uploader);
            meta.put("rating", info.rating);
            meta.put("totalPages", facts.totalPages);
            meta.put("updatedAt", info.time);
            array.add(meta);
        }
        byte[] raw = array.toJSONString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(256, raw.length / 2));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        }
        byte[] gzBytes = bos.toByteArray();
        sink.write(",\"metaPackEncoding\":\"gzip\"");
        sink.write(",\"metaPackSha256\":" + JSON.toJSONString(sha256Hex(raw)));
        sink.write(",\"metaPackSize\":" + raw.length);
        sink.write(",\"metaPack\":" + JSON.toJSONString(base64(gzBytes)));
    }

    /** 池快照指纹：ETag = "pool-{poolVersion}-{paramsHash}"。 */
    @NonNull
    public String etag(@NonNull PoolQuery q) {
        String params = String.valueOf(q.states) + '|' + q.limit + '|' + q.cursor + '|'
                + q.includeComplete + '|' + q.includePages + '|' + q.includeMetaPack + '|'
                + q.updatedSince + '|' + q.compress + '|' + q.sort;
        return "\"pool-" + getPoolVersion() + '-' + shortHash(params) + '"';
    }

    // ==================== 摘要 ====================

    @NonNull
    public JSONObject summary() {
        long version = getPoolVersion();
        List<DownloadInfo> all = downloadManager.getAllDownloadInfoList();
        int available = 0;
        int claimed = 0;
        int totalMissingPages = 0;
        long totalMissingSize = 0L;
        if (all != null) {
            for (DownloadInfo info : all) {
                if (info == null || info.archiveUri != null
                        || info.state == DownloadInfo.STATE_FINISH
                        || info.token == null || info.token.isEmpty()) {
                    continue;
                }
                int totalPages = info.pages > 0 ? info.pages : info.total;
                if (totalPages <= 0) {
                    continue;
                }
                int downloaded = Math.max(0, Math.min(info.finished, totalPages));
                int missing = Math.max(0, totalPages - downloaded);
                if (missing <= 0) {
                    continue;
                }
                // 摘要只读数据库计数，避免为 1 万+ 未完成记录逐个扫盘拖慢 UI 角标
                RelayTask leaseTask = taskManager.getActiveLeaseTaskByGid(info.gid);
                if (leaseTask != null) {
                    claimed++;
                } else {
                    available++;
                }
                totalMissingPages += missing;
                if (info.fileSize > 0) {
                    totalMissingSize += (long) missing * (info.fileSize / totalPages);
                }
            }
        }
        int expired = 0;
        for (RelayTask task : taskManager.getAllTasks()) {
            if (task != null && task.isExpired()) {
                expired++;
            }
        }
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("poolVersion", version);
        resp.put("available", available);
        resp.put("claimed", claimed);
        resp.put("expired", expired);
        resp.put("totalMissingPages", totalMissingPages);
        resp.put("totalMissingSize", totalMissingSize);
        resp.put("updatedAt", System.currentTimeMillis());
        return resp;
    }

    // ==================== 认领 ====================

    public static final class ClaimItem {
        public long gid;
        public RelayTask task;
        public String error;
        public int code;
        public JSONObject currentLease;
    }

    public static final class ClaimResult {
        public final List<ClaimItem> tasks = new ArrayList<>();
        public final List<ClaimItem> errors = new ArrayList<>();
        public long poolVersion;
    }

    @NonNull
    public ClaimResult claim(@NonNull List<Long> gids, @Nullable String deviceId,
                             @Nullable String deviceName, int leaseSeconds, boolean missingOnly,
                             @Nullable String priority, @Nullable String basePagesBitmap,
                             @Nullable String remoteHost, int remotePort) {
        ClaimResult result = new ClaimResult();
        int lease = clampLeaseSeconds(leaseSeconds);
        long expiresAt = System.currentTimeMillis() + lease * 1000L;
        String resolvedDeviceId = deviceId;
        if (resolvedDeviceId == null || resolvedDeviceId.isEmpty()) {
            resolvedDeviceId = remoteHost != null && !remoteHost.isEmpty()
                    ? "lan-" + remoteHost : "unknown-device";
        }
        String resolvedDeviceName = deviceName != null && !deviceName.isEmpty()
                ? deviceName : resolvedDeviceId;
        final String finalDeviceId = resolvedDeviceId;
        final String finalDeviceName = resolvedDeviceName;
        synchronized (claimLock) {
            for (Long gidBox : gids) {
                if (gidBox == null) continue;
                long gid = gidBox;
                ClaimItem item = new ClaimItem();
                item.gid = gid;
                DownloadInfo info = downloadManager.getDownloadInfo(gid);
                if (info == null) {
                    item.error = "Gallery not found in download list";
                    item.code = CODE_NOT_FOUND;
                    result.errors.add(item);
                    continue;
                }
                if (info.state == DownloadInfo.STATE_FINISH) {
                    item.error = "Gallery is already completed";
                    item.code = CODE_CONFLICT;
                    result.errors.add(item);
                    continue;
                }
                RelayTask active = taskManager.getActiveLeaseTaskByGid(gid);
                if (active != null) {
                    item.error = "already_claimed";
                    item.code = CODE_CONFLICT;
                    item.currentLease = leaseJson(active);
                    result.errors.add(item);
                    continue;
                }
                PageFacts facts = scan(info);
                if (facts.totalPages <= 0) {
                    item.error = "Unknown page count";
                    item.code = CODE_PRECONDITION;
                    result.errors.add(item);
                    continue;
                }
                String currentBitmap = RelayPageBitmap.encode(facts.downloaded, facts.totalPages);
                String expectedBase = basePagesBitmap != null ? basePagesBitmap : null;
                if (expectedBase != null && !expectedBase.isEmpty()
                        && !bitmapEquals(expectedBase, currentBitmap)) {
                    item.error = "bitmap_mismatch";
                    item.code = CODE_PRECONDITION;
                    result.errors.add(item);
                    continue;
                }
                reconcile(info, facts);
                RelayTask task = taskManager.createClaimedTask(
                        gid, info.token, info.title, info.titleJpn, info.thumb, info.category,
                        info.posted, info.uploader, info.rating, facts.totalPages,
                        android.os.Build.MODEL, null, remoteHost, remotePort,
                        finalDeviceName, finalDeviceId,
                        priority != null ? priority : "normal", true, expiresAt,
                        facts.missingPages.size(), currentBitmap, missingOnly, expectedBase);
                task.setFinished(facts.downloadedCount);
                task.setTotal(facts.totalPages);
                taskManager.persist();
                // 主机立即暂停本地下载，避免双写者
                try {
                    downloadManager.setRelayDownload(gid);
                } catch (Throwable t) {
                    Log.w(TAG, "claim: pause local download failed gid=" + gid, t);
                }
                result.tasks.add(item);
                item.task = task;
                publishTaskChanged(task);
                bumpPoolVersion(gid, "claimed");
            }
        }
        result.poolVersion = getPoolVersion();
        return result;
    }

    // ==================== 租约 ====================

    /** @return null 表示成功；否则为错误 JSON。 */
    @Nullable
    public JSONObject renewLease(@NonNull String taskId, @Nullable String deviceId, int leaseSeconds) {
        RelayTask task = taskManager.getTask(taskId);
        if (task == null) {
            return error("Task not found", CODE_NOT_FOUND);
        }
        if (!task.isReturnWritable()) {
            return error("Task is in state " + task.getStatus(), CODE_CONFLICT);
        }
        if (!leaseMatches(task, deviceId)) {
            return error("Lease is held by another device", CODE_FORBIDDEN);
        }
        int lease = clampLeaseSeconds(leaseSeconds);
        long expiresAt = System.currentTimeMillis() + lease * 1000L;
        taskManager.renewLease(taskId, deviceId, expiresAt);
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("taskId", taskId);
        resp.put("leaseExpiresAt", expiresAt);
        return null;
    }

    @Nullable
    public JSONObject release(@NonNull String taskId, @Nullable String deviceId,
                              @Nullable String reason) {
        RelayTask task = taskManager.getTask(taskId);
        if (task == null) {
            return error("Task not found", CODE_NOT_FOUND);
        }
        if (!leaseMatches(task, deviceId) && deviceId != null) {
            return error("Lease is held by another device", CODE_FORBIDDEN);
        }
        if (task.isTerminal()) {
            return error("Task is in state " + task.getStatus(), CODE_CONFLICT);
        }
        taskManager.clearLease(taskId, RelayTask.STATUS_OFFERED);
        try {
            downloadManager.setRelayWaiting(task.getGid());
        } catch (Throwable ignored) {
        }
        publishTaskChanged(task);
        bumpPoolVersion(task.getGid(), reason != null ? reason : "released");
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("taskId", taskId);
        resp.put("status", RelayTask.STATUS_OFFERED);
        return resp;
    }

    private boolean leaseMatches(@Nullable RelayTask task, @Nullable String deviceId) {
        if (task == null) return false;
        if (!task.hasActiveLease()) return true; // 无租约时允许旧流程写入
        String holder = task.getLeaseDeviceId() != null
                ? task.getLeaseDeviceId() : task.getAcceptedDeviceId();
        if (holder == null || holder.isEmpty()) return true;
        return deviceId != null && (holder.equals(deviceId)
                || (task.getAcceptedDeviceId() != null && task.getAcceptedDeviceId().equals(deviceId)));
    }

    private void scanExpiredLeasesSafely() {
        try {
            if (!Settings.isRelayAnyEnabled()) {
                return;
            }
            scanExpiredLeases();
        } catch (Throwable t) {
            Log.w(TAG, "Lease scan failed", t);
        }
    }

    public void scanExpiredLeases() {
        long now = System.currentTimeMillis();
        for (RelayTask task : taskManager.getAllTasks()) {
            if (task == null || task.isTerminal() || !task.isReturnWritable()) {
                continue;
            }
            long expiresAt = task.getLeaseExpiresAt();
            if (expiresAt <= 0 || expiresAt > now) {
                continue;
            }
            String expiredDeviceId = task.getLeaseDeviceId() != null
                    ? task.getLeaseDeviceId() : task.getAcceptedDeviceId();
            taskManager.markExpired(task.getTaskId());
            try {
                downloadManager.setRelayWaiting(task.getGid());
            } catch (Throwable ignored) {
            }
            StringBuilder fields = new StringBuilder(128);
            fields.append("\"taskId\":").append(DataChangeBus.quote(task.getTaskId()));
            fields.append(",\"gid\":").append(task.getGid());
            fields.append(",\"deviceId\":").append(DataChangeBus.quote(expiredDeviceId));
            fields.append(",\"leaseExpiresAt\":").append(expiresAt);
            DataChangeBus.publishFields(DataChangeBus.TYPE_RELAY_LEASE_EXPIRED, fields.toString());
            publishTaskChanged(task);
            bumpPoolVersion(task.getGid(), "lease_expired");
        }
    }

    // ==================== 计划 ====================

    @Nullable
    public JSONObject plan(@Nullable String taskId, @Nullable Long gidFallback,
                           @Nullable String only, int limit, @Nullable String cursor) {
        RelayTask task = taskId != null ? taskManager.getTask(taskId) : null;
        long gid = task != null ? task.getGid() : (gidFallback != null ? gidFallback : 0L);
        if (gid <= 0) {
            return error("Task or gid required", CODE_BAD_REQUEST);
        }
        DownloadInfo info = downloadManager.getDownloadInfo(gid);
        if (info == null) {
            return error("Gallery not found in download list", CODE_NOT_FOUND);
        }
        PageFacts facts = scan(info);
        if (facts.totalPages <= 0) {
            return error("Unknown page count", CODE_PRECONDITION);
        }
        reconcile(info, facts);
        if (task != null) {
            task.setFinished(facts.downloadedCount);
            task.setTotal(facts.totalPages);
            task.setMissingCount(facts.missingPages.size());
            task.setPagesBitmap(RelayPageBitmap.encode(facts.downloaded, facts.totalPages));
        }
        boolean missingOnly = !"all".equals(only);
        int max = limit <= 0 ? 200 : Math.min(limit, 500);
        int startPage = 1;
        if (cursor != null) {
            try {
                startPage = Math.max(1, Integer.parseInt(cursor));
            } catch (NumberFormatException ignored) {
            }
        }
        JSONArray pages = new JSONArray();
        int lastPage = 0;
        boolean hasMore = false;
        for (int page = startPage; page <= facts.totalPages; page++) {
            boolean downloaded = facts.downloaded[page - 1];
            if (missingOnly && downloaded) {
                continue;
            }
            if (pages.size() >= max) {
                hasMore = true;
                break;
            }
            JSONObject p = new JSONObject();
            p.put("page", page);
            p.put("downloaded", downloaded);
            String pToken = "";
            if (facts.spiderInfo != null && facts.spiderInfo.pTokenMap != null) {
                String token = facts.spiderInfo.pTokenMap.get(page - 1);
                if (token != null && !SpiderInfo.TOKEN_FAILED.equals(token)) {
                    pToken = token;
                }
            }
            p.put("pToken", pToken);
            p.put("pageUrl", pToken.isEmpty() ? "" : EhUrl.getPageUrl(gid, page - 1, pToken));
            String ext = "jpg";
            long size = 0L;
            String localFile = null;
            if (downloaded && facts.dir != null) {
                UniFile file = SpiderDen.findImageFile(facts.dir, page - 1);
                if (file != null) {
                    localFile = file.getName();
                    size = file.length();
                    int dot = localFile.lastIndexOf('.');
                    if (dot >= 0 && dot + 1 < localFile.length()) {
                        ext = localFile.substring(dot + 1).toLowerCase(Locale.ROOT);
                    }
                }
            }
            p.put("extHint", ext);
            p.put("expectedSize", size);
            p.put("status", downloaded ? "downloaded" : "pending");
            if (localFile != null) {
                p.put("localFile", localFile);
                p.put("size", size);
            }
            pages.add(p);
            lastPage = page;
        }
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("taskId", task != null ? task.getTaskId() : "relay-temp-" + gid);
        resp.put("gid", gid);
        resp.put("token", info.token);
        resp.put("title", info.title);
        resp.put("totalPages", facts.totalPages);
        resp.put("downloadedPages", facts.downloadedCount);
        resp.put("missingCount", facts.missingPages.size());
        JSONArray missing = new JSONArray();
        for (Integer page : facts.missingPages) missing.add(page);
        resp.put("missingPages", missing);
        resp.put("pagesBitmap", RelayPageBitmap.encode(facts.downloaded, facts.totalPages));
        resp.put("site", Settings.getGallerySite() == EhUrl.SITE_EX ? "ex" : "e");
        resp.put("galleryUrl", EhUrl.getGalleryDetailUrl(gid, info.token));
        resp.put("only", missingOnly ? "missing" : "all");
        resp.put("hasMore", hasMore);
        resp.put("nextCursor", hasMore && lastPage > 0 ? String.valueOf(lastPage + 1) : null);
        resp.put("pages", pages);
        return resp;
    }

    // ==================== 逐页回传 ====================

    public static final class PartialResult {
        public JSONObject json;
        public int httpStatus = 200;
    }

    /**
     * 处理逐页回传。manifest 中 pages{page:{ext,size,hash,partName}}，files 为 multipart 临时文件路径。
     */
    @NonNull
    public PartialResult returnPartial(@NonNull JSONObject manifest, @NonNull Map<String, String> files,
                                       @Nullable String remoteIp) throws IOException {
        PartialResult result = new PartialResult();
        long gid = manifest.getLongValue("gid");
        String token = manifest.getString("token");
        String taskId = manifest.getString("taskId");
        int fromPage = manifest.getIntValue("fromPage");
        int toPage = manifest.getIntValue("toPage");
        String algorithm = manifest.getString("algorithm");
        if (algorithm == null || algorithm.isEmpty()) algorithm = "sha256";
        String compress = manifest.getString("compress");
        if (compress == null || compress.isEmpty()) compress = "deflate";
        boolean overwrite = manifest.getBooleanValue("overwrite");
        String requestId = manifest.getString("requestId");
        String deviceId = manifest.getString("deviceId");
        String baseBitmap = manifest.getString("basePagesBitmap");
        JSONObject pages = manifest.getJSONObject("pages");

        if (gid <= 0 || token == null || fromPage <= 0 || toPage < fromPage
                || pages == null || pages.isEmpty()) {
            result.httpStatus = CODE_BAD_REQUEST;
            result.json = error("Invalid manifest: gid/token/fromPage/toPage/pages required",
                    CODE_BAD_REQUEST);
            return result;
        }
        if (requestId != null && !requestId.isEmpty()) {
            String cached = idempotency.get(requestId);
            if (cached != null) {
                try {
                    result.json = JSONObject.parseObject(cached);
                    return result;
                } catch (Throwable ignored) {
                }
            }
        }

        DownloadInfo info = downloadManager.getDownloadInfo(gid);
        if (info == null) {
            result.httpStatus = CODE_NOT_FOUND;
            result.json = error("Gallery not found: " + gid, CODE_NOT_FOUND);
            return result;
        }
        if (token != null && info.token != null && !info.token.equals(token)) {
            result.httpStatus = CODE_PRECONDITION;
            result.json = error("token_mismatch", CODE_PRECONDITION);
            return result;
        }

        RelayTask task = taskId != null ? taskManager.getTask(taskId) : taskManager.getLiveTaskByGid(gid);
        if (task != null && task.getGid() != gid) {
            result.httpStatus = CODE_PRECONDITION;
            result.json = error("task_gid_mismatch", CODE_PRECONDITION);
            return result;
        }
        if (task == null) {
            // 无 taskId 的旧客户端/别名路径：按 gid 自动建立任务
            int lease = clampLeaseSeconds(0);
            long expiresAt = System.currentTimeMillis() + lease * 1000L;
            PageFacts initial = scan(info);
            if (initial.totalPages <= 0) {
                result.httpStatus = CODE_PRECONDITION;
                result.json = error("Unknown page count", CODE_PRECONDITION);
                return result;
            }
            task = taskManager.createClaimedTask(gid, info.token, info.title, info.titleJpn,
                    info.thumb, info.category, info.posted, info.uploader, info.rating,
                    initial.totalPages, "legacy-client", deviceId, remoteIp, 0,
                    "legacy-client", deviceId != null ? deviceId : "legacy-client",
                    "normal", false, expiresAt, initial.missingPages.size(),
                    RelayPageBitmap.encode(initial.downloaded, initial.totalPages), true, null);
            try {
                downloadManager.setRelayDownload(gid);
            } catch (Throwable ignored) {
            }
            publishTaskChanged(task);
            bumpPoolVersion(gid, "legacy_claim");
        } else if (!task.isReturnWritable()) {
            // completed 任务允许幂等回传，但不再写盘
            if (task.isCompleted()) {
                result.json = buildPartialResponse(task, info, new JSONArray(), 0, 0, 0, null);
                return result;
            }
            result.httpStatus = CODE_CONFLICT;
            result.json = error("Task is in state " + task.getStatus(), CODE_CONFLICT);
            return result;
        }

        if (!leaseMatches(task, deviceId)) {
            result.httpStatus = CODE_LEASE_INVALID;
            result.json = error("lease_invalid", CODE_LEASE_INVALID);
            result.json.put("leaseExpiresAt", task.getLeaseExpiresAt() > 0
                    ? Long.valueOf(task.getLeaseExpiresAt()) : null);
            return result;
        }

        PageFacts before = scan(info);
        if (before.totalPages <= 0) {
            result.httpStatus = CODE_PRECONDITION;
            result.json = error("Unknown page count", CODE_PRECONDITION);
            return result;
        }
        String currentBitmap = RelayPageBitmap.encode(before.downloaded, before.totalPages);
        if (baseBitmap != null && !baseBitmap.isEmpty() && !bitmapEquals(baseBitmap, currentBitmap)) {
            result.httpStatus = CODE_PRECONDITION;
            result.json = error("bitmap_mismatch", CODE_PRECONDITION);
            result.json.put("expectedBitmap", currentBitmap);
            return result;
        }

        UniFile dir = SpiderDen.getGalleryDownloadDir(info);
        if (dir == null) {
            result.httpStatus = 500;
            result.json = error("Failed to resolve download directory", 500);
            return result;
        }
        if (!dir.isDirectory() && !dir.ensureDir()) {
            result.httpStatus = 500;
            result.json = error("Failed to create download directory", 500);
            return result;
        }
        int totalPages = before.totalPages;

        JSONArray results = new JSONArray();
        int succeeded = 0;
        int skipped = 0;
        int failed = 0;

        List<String> pageKeys = new ArrayList<>(pages.keySet());
        try {
            pageKeys.sort((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
        } catch (Throwable ignored) {
            Collections.sort(pageKeys);
        }
        for (String pageKey : pageKeys) {
            int page;
            try {
                page = Integer.parseInt(pageKey);
            } catch (NumberFormatException e) {
                continue;
            }
            JSONObject meta = pages.getJSONObject(pageKey);
            if (meta == null) continue;
            String partName = meta.getString("partName");
            if (partName == null || partName.isEmpty()) partName = "file_" + page;
            JSONObject r = new JSONObject();
            r.put("page", page);

            if (page < 1 || page > totalPages) {
                r.put("status", "page_out_of_range");
                results.add(r);
                failed++;
                continue;
            }
            String partPath = files.get(partName);
            if (partPath == null || !new File(partPath).isFile()) {
                r.put("status", "missing_part");
                results.add(r);
                failed++;
                continue;
            }
            String expectedHash = meta.getString("hash");
            long expectedSize = meta.getLongValue("size");
            String ext = sanitizeExt(meta.getString("ext"));

            File decoded = File.createTempFile("relay_page_", ".part", appContext.getCacheDir());
            String actualHash;
            long actualSize;
            try {
                MessageDigest digest = MessageDigest.getInstance(normalizeAlgorithm(algorithm));
                actualSize = decodeToFile(new File(partPath), compress, decoded, digest);
                actualHash = hex(digest.digest());
            } catch (Throwable t) {
                decoded.delete();
                r.put("status", "bad_compress");
                r.put("error", String.valueOf(t.getMessage()));
                results.add(r);
                failed++;
                continue;
            }

            if (expectedSize > 0 && actualSize != expectedSize) {
                decoded.delete();
                r.put("status", "size_mismatch");
                r.put("expectedSize", expectedSize);
                r.put("actualSize", actualSize);
                results.add(r);
                failed++;
                continue;
            }
            if (expectedHash != null && !expectedHash.isEmpty()
                    && !expectedHash.equalsIgnoreCase(actualHash)) {
                decoded.delete();
                r.put("status", "hash_mismatch");
                r.put("expected", expectedHash);
                r.put("actual", actualHash);
                results.add(r);
                failed++;
                continue;
            }

            String filename = SpiderDen.generateImageFilename(page - 1, "." + ext);
            synchronized (pageLock(gid, page)) {
                UniFile existing = dir.findFile(filename);
                boolean existed = existing != null;
                if (existed) {
                    String oldHash = hashFile(existing, normalizeAlgorithm(algorithm));
                    if (oldHash != null && oldHash.equalsIgnoreCase(expectedHash)) {
                        decoded.delete();
                        r.put("status", "skipped");
                        r.put("filename", filename);
                        results.add(r);
                        skipped++;
                        continue;
                    }
                    if (!overwrite) {
                        decoded.delete();
                        r.put("status", "exists_conflict");
                        r.put("filename", filename);
                        results.add(r);
                        failed++;
                        continue;
                    }
                }
                boolean written = writeAtomically(dir, filename, decoded);
                decoded.delete();
                if (!written) {
                    r.put("status", "internal_error");
                    results.add(r);
                    failed++;
                    continue;
                }
                r.put("status", existed ? "overwritten" : "ok");
                r.put("filename", filename);
                r.put("size", actualSize);
                results.add(r);
                succeeded++;
                try {
                    downloadManager.markPageDownloaded(gid, page - 1, info);
                } catch (Throwable t) {
                    Log.w(TAG, "markPageDownloaded failed gid=" + gid + " page=" + page, t);
                }
                ResponseCache.getInstance().invalidateGalleries();
                publishPageReceived(task.getTaskId(), gid, page, existed ? "overwritten" : "ok",
                        actualSize, task.getLeaseDeviceId());
                publishProgress(gid, Math.min(totalPages, before.downloadedCount + succeeded),
                        totalPages, DownloadInfo.STATE_RELAY_DOWNLOAD, task.getLeaseDeviceId());
            }
        }

        PageFacts after = scan(info);
        reconcile(info, after);
        String afterBitmap = RelayPageBitmap.encode(after.downloaded, after.totalPages);
        String taskStatus;
        if (after.missingPages.isEmpty()) {
            taskStatus = RelayTask.STATUS_COMPLETED;
        } else if (after.downloadedCount > before.downloadedCount) {
            taskStatus = RelayTask.STATUS_PARTIAL;
        } else {
            taskStatus = task.getStatus();
            if (taskStatus == null || RelayTask.STATUS_ACCEPTED.equals(taskStatus)) {
                taskStatus = RelayTask.STATUS_DOWNLOADING;
            }
        }
        taskManager.updateTaskPages(task.getTaskId(), after.downloadedCount, after.totalPages,
                after.missingPages.size(), afterBitmap, succeeded + skipped, failed, taskStatus);

        JSONObject finish = null;
        if (after.missingPages.isEmpty()) {
            finish = finalizeGallery(gid, task.getTaskId(), after);
        } else {
            publishProgress(gid, after.downloadedCount, after.totalPages, info.state,
                    task.getLeaseDeviceId());
            bumpPoolVersion(gid, "page_received");
        }
        result.json = buildPartialResponse(task, info, results, succeeded, skipped, failed, finish);
        if (requestId != null && !requestId.isEmpty()) {
            idempotency.put(requestId, result.json.toJSONString());
        }
        return result;
    }

    private JSONObject buildPartialResponse(RelayTask task, DownloadInfo info, JSONArray results,
                                            int succeeded, int skipped, int failed,
                                            @Nullable JSONObject finish) {
        PageFacts facts = scan(info);
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("taskId", task.getTaskId());
        resp.put("gid", info.gid);
        resp.put("results", results);
        resp.put("succeeded", succeeded);
        resp.put("skipped", skipped);
        resp.put("failed", failed);
        resp.put("downloadedPages", facts.downloadedCount);
        resp.put("totalPages", facts.totalPages);
        resp.put("missingCount", facts.missingPages.size());
        JSONArray missing = new JSONArray();
        for (Integer page : facts.missingPages) missing.add(page);
        resp.put("missingPages", missing);
        resp.put("pagesBitmap", RelayPageBitmap.encode(facts.downloaded, facts.totalPages));
        resp.put("taskStatus", task.getStatus());
        resp.put("leaseExpiresAt", task.getLeaseExpiresAt() > 0
                ? Long.valueOf(task.getLeaseExpiresAt()) : null);
        resp.put("finish", finish);
        return resp;
    }

    // ==================== 收尾流水线 ====================

    @Nullable
    public JSONObject finalizeGallery(long gid, @Nullable String taskId, @Nullable PageFacts knownFacts) {
        String key = String.valueOf(gid);
        if (!finalizingGids.add(key)) {
            return null;
        }
        try {
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (info == null) {
                return null;
            }
            PageFacts facts = knownFacts != null ? knownFacts : scan(info);
            if (facts.totalPages <= 0) {
                return null;
            }
            // 1. 全量核对页文件齐全
            for (int i = 0; i < facts.totalPages; i++) {
                if (!facts.downloaded[i]) {
                    // 还有缺页，不执行收尾
                    return null;
                }
            }
            // 2. 更新下载记录
            info.finished = facts.totalPages;
            info.total = facts.totalPages;
            if (info.pages <= 0) {
                info.pages = facts.totalPages;
            }
            info.downloaded = facts.totalPages;
            info.state = DownloadInfo.STATE_FINISH;
            info.speed = 0;
            info.remaining = 0;
            if (facts.totalSize > 0) {
                info.fileSize = facts.totalSize;
            }
            EhDB.putDownloadInfo(info);
            // 3. 写入/刷新 .ehviewer 元数据（仅在缺失时创建，避免覆盖已有 pToken）
            boolean metadataWritten = ensureSpiderInfo(info, facts);
            // 4. 清理 .tmp / 补缩略图由既有缓存机制按需处理
            cleanupTempFiles(facts.dir);
            // 5. 任务状态 completed
            RelayTask task = taskId != null ? taskManager.getTask(taskId)
                    : taskManager.getLiveTaskByGid(gid);
            long completedAt = System.currentTimeMillis();
            if (task != null) {
                task.setFinished(facts.totalPages);
                task.setTotal(facts.totalPages);
                task.setMissingCount(0);
                task.setPagesBitmap(RelayPageBitmap.encode(facts.downloaded, facts.totalPages));
                task.setStatus(RelayTask.STATUS_COMPLETED);
                task.setCompletedTime(completedAt);
                taskManager.persist();
                publishTaskChanged(task);
            }
            // 6. 池移除 + 事件
            bumpPoolVersion(gid, "gallery_completed");
            StringBuilder fields = new StringBuilder(96);
            fields.append("\"gid\":").append(gid);
            fields.append(",\"completedAt\":").append(completedAt);
            fields.append(",\"sourceDeviceId\":")
                    .append(DataChangeBus.quote(task != null ? task.getLeaseDeviceId() : null));
            DataChangeBus.publishFields(DataChangeBus.TYPE_GALLERY_COMPLETED, fields.toString());

            JSONObject finish = new JSONObject();
            finish.put("state", DownloadInfo.STATE_FINISH);
            finish.put("stateName", "finished");
            finish.put("fileCount", facts.totalPages);
            finish.put("metadataWritten", metadataWritten);
            finish.put("completedAt", completedAt);
            TransferLogger.getInstance().i(TAG,
                    "Relay finish complete: gid=" + gid + " pages=" + facts.totalPages);
            return finish;
        } catch (Throwable t) {
            Log.w(TAG, "finalizeGallery failed gid=" + gid, t);
            return null;
        } finally {
            finalizingGids.remove(key);
        }
    }

    private boolean ensureSpiderInfo(DownloadInfo info, PageFacts facts) {
        if (facts.dir == null) return false;
        try {
            UniFile existing = facts.dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME);
            SpiderInfo spi = facts.spiderInfo;
            if (existing != null) {
                if (spi == null) {
                    spi = SpiderInfo.read(existing);
                }
                if (spi == null) {
                    return false;
                }
                // 页数变化时刷新；pToken 集合保持不变
                if (spi.pages == facts.totalPages && spi.pTokenMap != null) {
                    return true;
                }
                spi.pages = facts.totalPages;
                if (spi.pTokenMap == null) {
                    spi.pTokenMap = new android.util.SparseArray<>(0);
                }
                try (OutputStream os = existing.openOutputStream()) {
                    spi.write(os);
                }
                return true;
            }
            if (spi == null) {
                spi = new SpiderInfo();
                spi.gid = info.gid;
                spi.token = info.token;
                spi.pages = facts.totalPages;
                spi.previewPages = 1;
                spi.previewPerPage = 0;
                spi.pTokenMap = new android.util.SparseArray<>(0);
            } else if (spi.pTokenMap == null) {
                spi.pTokenMap = new android.util.SparseArray<>(0);
            }
            if (spi.pages <= 0) {
                spi.pages = facts.totalPages;
            }
            UniFile target = facts.dir.createFile(SpiderQueen.SPIDER_INFO_FILENAME);
            if (target == null) return false;
            try (OutputStream os = target.openOutputStream()) {
                spi.write(os);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "ensureSpiderInfo failed gid=" + info.gid, t);
            return false;
        }
    }

    private void cleanupTempFiles(@Nullable UniFile dir) {
        if (dir == null || !dir.isDirectory()) return;
        try {
            UniFile[] files = dir.listFiles();
            if (files == null) return;
            for (UniFile file : files) {
                String name = file.getName();
                if (name != null && (name.endsWith(".tmp") || name.endsWith(".part"))) {
                    file.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 事件 ====================

    public void publishTaskChanged(@Nullable RelayTask task) {
        if (task == null) return;
        StringBuilder fields = new StringBuilder(160);
        fields.append("\"taskId\":").append(DataChangeBus.quote(task.getTaskId()));
        fields.append(",\"gid\":").append(task.getGid());
        fields.append(",\"status\":").append(DataChangeBus.quote(task.getStatus()));
        fields.append(",\"missingCount\":").append(task.getMissingCount());
        if (task.getLeaseDeviceId() != null) {
            fields.append(",\"deviceId\":").append(DataChangeBus.quote(task.getLeaseDeviceId()));
        }
        DataChangeBus.publishFields(DataChangeBus.TYPE_RELAY_TASK_CHANGED, fields.toString());
    }

    private void publishPageReceived(String taskId, long gid, int page, String status,
                                     long size, @Nullable String deviceId) {
        StringBuilder fields = new StringBuilder(160);
        fields.append("\"taskId\":").append(DataChangeBus.quote(taskId));
        fields.append(",\"gid\":").append(gid);
        fields.append(",\"page\":").append(page);
        fields.append(",\"status\":").append(DataChangeBus.quote(status));
        fields.append(",\"size\":").append(size);
        if (deviceId != null) {
            fields.append(",\"deviceId\":").append(DataChangeBus.quote(deviceId));
        }
        DataChangeBus.publishFields(DataChangeBus.TYPE_PAGE_RECEIVED, fields.toString());
    }

    private void publishProgress(long gid, int finished, int total, int state,
                                 @Nullable String deviceId) {
        StringBuilder fields = new StringBuilder(128);
        fields.append("\"gid\":").append(gid);
        fields.append(",\"finished\":").append(finished);
        fields.append(",\"total\":").append(total);
        fields.append(",\"state\":").append(state);
        if (deviceId != null) {
            fields.append(",\"fromDeviceId\":").append(DataChangeBus.quote(deviceId));
        }
        DataChangeBus.publishFields(DataChangeBus.TYPE_GALLERY_PROGRESS, fields.toString());
    }

    // ==================== 工具 ====================

    public static JSONObject error(String message, int code) {
        JSONObject resp = new JSONObject();
        resp.put("success", false);
        resp.put("error", message);
        resp.put("code", code);
        return resp;
    }

    private static Object pageLock(long gid, int page) {
        String key = gid + ":" + page;
        synchronized (PAGE_LOCKS) {
            Object lock = PAGE_LOCKS.get(key);
            if (lock == null) {
                if (PAGE_LOCKS.size() > MAX_PAGE_LOCKS) {
                    PAGE_LOCKS.clear();
                }
                lock = new Object();
                PAGE_LOCKS.put(key, lock);
            }
            return lock;
        }
    }

    public static boolean bitmapEquals(@Nullable String a, @Nullable String b) {
        String na = normalizeBitmap(a);
        String nb = normalizeBitmap(b);
        return na.equals(nb);
    }

    private static String normalizeBitmap(@Nullable String value) {
        if (value == null) return "";
        return value.replace("=", "").trim();
    }

    private static String sanitizeExt(@Nullable String ext) {
        if (ext == null) return "jpg";
        String e = ext.trim().toLowerCase(Locale.ROOT);
        if (e.startsWith(".")) e = e.substring(1);
        if (!e.matches("[a-z0-9]+")) return "jpg";
        for (String supported : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            if (supported.substring(1).equals(e)) return e;
        }
        return "jpg";
    }

    private static String normalizeAlgorithm(@Nullable String algorithm) {
        String a = algorithm == null ? "sha256" : algorithm.toLowerCase(Locale.ROOT);
        switch (a) {
            case "md5": return "MD5";
            case "sha1": return "SHA-1";
            default: return "SHA-256";
        }
    }

    private static long decodeToFile(File src, String compress, File dst, MessageDigest digest)
            throws IOException {
        InputStream in;
        switch (compress == null ? "deflate" : compress.toLowerCase(Locale.ROOT)) {
            case "none":
                in = new FileInputStream(src);
                break;
            case "zip": {
                ZipInputStream zin = new ZipInputStream(new FileInputStream(src));
                ZipEntry entry = zin.getNextEntry();
                if (entry == null) {
                    zin.close();
                    throw new IOException("Empty zip");
                }
                in = zin;
                break;
            }
            case "deflate":
            default:
                in = new InflaterInputStream(new FileInputStream(src));
                break;
        }
        long total = 0L;
        try (InputStream input = in; OutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[16 * 1024];
            int len;
            while ((len = input.read(buffer)) > 0) {
                out.write(buffer, 0, len);
                if (digest != null) digest.update(buffer, 0, len);
                total += len;
            }
        }
        return total;
    }

    private boolean writeAtomically(UniFile dir, String filename, File decoded) {
        String tmpName = filename + ".tmp";
        UniFile tmp = dir.findFile(tmpName);
        if (tmp != null) tmp.delete();
        tmp = dir.createFile(tmpName);
        if (tmp == null) {
            return false;
        }
        try (InputStream is = new FileInputStream(decoded);
             OutputStream os = tmp.openOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
            os.flush();
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        UniFile existing = dir.findFile(filename);
        if (existing != null) {
            existing.delete();
        }
        if (tmp.renameTo(filename)) {
            return true;
        }
        // rename 失败时兜底复制
        try {
            UniFile target = dir.createFile(filename);
            if (target == null) {
                tmp.delete();
                return false;
            }
            try (InputStream is = tmp.openInputStream();
                 OutputStream os = target.openOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
            }
            tmp.delete();
            return true;
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
    }

    @Nullable
    private String hashFile(UniFile file, String algorithm) {
        try (InputStream is = file.openInputStream()) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[16 * 1024];
            int len;
            while ((len = is.read(buffer)) > 0) {
                digest.update(buffer, 0, len);
            }
            return hex(digest.digest());
        } catch (Throwable t) {
            return null;
        }
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Throwable t) {
            return "";
        }
    }

    public static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public static String base64(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        final char[] alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder sb = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(alphabet[(n >> 18) & 63]);
            sb.append(alphabet[(n >> 12) & 63]);
            sb.append(alphabet[(n >> 6) & 63]);
            sb.append(alphabet[n & 63]);
            i += 3;
        }
        int remaining = data.length - i;
        if (remaining == 1) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(alphabet[(n >> 18) & 63]);
            sb.append(alphabet[(n >> 12) & 63]);
            sb.append("==");
        } else if (remaining == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(alphabet[(n >> 18) & 63]);
            sb.append(alphabet[(n >> 12) & 63]);
            sb.append(alphabet[(n >> 6) & 63]);
            sb.append('=');
        }
        return sb.toString();
    }

    /** 供统计/调试。 */
    public int getLiveTaskCount() {
        int count = 0;
        for (RelayTask task : taskManager.getAllTasks()) {
            if (task != null && !task.isTerminal()) count++;
        }
        return count;
    }
}
