package com.hippo.ehviewer.lab.semantic.index;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.GalleryTags;
import com.hippo.ehviewer.lab.semantic.SemanticConfig;
import com.hippo.ehviewer.lab.semantic.SemanticSearchManager;
import com.hippo.ehviewer.lab.semantic.backend.EmbeddingException;
import com.hippo.ehviewer.lab.semantic.model.ModelManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地库向量索引管线。
 *
 * <p>调度策略（针对手机热与电的限制）：
 * <ul>
 *   <li>单线程后台执行，线程优先级设为最低，不与前台交互抢资源</li>
 *   <li>默认仅在<b>充电中</b>才执行（{@link Settings#getSemanticIndexOnCharge()}）</li>
 *   <li>按批处理（{@link SemanticConfig#BATCH_SIZE}），批间可中断，进度落盘支持续跑</li>
 *   <li>只索引尚未入库的条目（按模型 id 隔离），因此换模型会自动触发全量重建</li>
 * </ul>
 */
public class IndexingPipeline {

    private static final String TAG = "SemanticIndexing";

    private static final String META_INDEXED_MODEL = "indexed_model";
    private static final String META_LAST_GID = "last_indexed_gid";

    /** 索引进度回调。 */
    public interface ProgressListener {
        void onStart(int total);

        void onProgress(int done, int total, String currentTitle);

        void onFinish(int indexed, boolean aborted);

        void onError(String error);
    }

    private static volatile IndexingPipeline sInstance;

    private static volatile Context sAppContext;

    private final Context appContext;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private ExecutorService executor;

    private IndexingPipeline(Context context) {
        this.appContext = context.getApplicationContext();
        sAppContext = this.appContext;
    }

    public static IndexingPipeline getInstance(Context context) {
        if (sInstance == null) {
            synchronized (IndexingPipeline.class) {
                if (sInstance == null) {
                    sInstance = new IndexingPipeline(context);
                }
            }
        }
        return sInstance;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 提交一次索引任务。充电策略不满足时直接拒绝并说明原因。
     */
    public boolean submit(final ProgressListener listener) {
        if (running.get()) {
            if (listener != null) {
                listener.onError("索引正在进行中");
            }
            return false;
        }
        if (Settings.getSemanticIndexOnCharge() && !isCharging()) {
            if (listener != null) {
                listener.onError("已设置为仅在充电时索引，请连接充电器后重试");
            }
            return false;
        }
        running.set(true);
        aborted.set(false);
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SemanticIndexer");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        executor.execute(() -> run(listener));
        return true;
    }

    /** 请求中止，进度已落盘，下次可续跑。 */
    public void abort() {
        aborted.set(true);
    }

    private void run(ProgressListener listener) {
        SemanticSearchManager manager = SemanticSearchManager.getInstance(appContext);
        SemanticVectorStore store = manager.getStore();
        String modelId = manager.getBackend().modelId();

        try {
            List<GalleryInfo> all = collectIndexable();
            if (all.isEmpty()) {
                if (listener != null) {
                    listener.onFinish(0, false);
                }
                return;
            }

            // 换模型时清空旧索引，避免新旧向量混在一张表里
            String indexedModel = store.getMeta(META_INDEXED_MODEL);
            if (indexedModel != null && !indexedModel.equals(modelId)) {
                Log.i(TAG, "model changed " + indexedModel + " -> " + modelId + ", rebuild index");
                store.clear(SemanticConfig.KIND_GALLERY);
            }
            store.putMeta(META_INDEXED_MODEL, modelId);

            List<String> existing = store.existingKeys(SemanticConfig.KIND_GALLERY, modelId);
            List<GalleryInfo> pending = new ArrayList<>();
            for (GalleryInfo info : all) {
                if (info != null && !existing.contains(String.valueOf(info.gid))) {
                    pending.add(info);
                }
            }

            if (pending.isEmpty()) {
                if (listener != null) {
                    listener.onFinish(0, false);
                }
                return;
            }

            if (listener != null) {
                listener.onStart(pending.size());
            }

            int done = 0;
            for (int start = 0; start < pending.size(); start += SemanticConfig.BATCH_SIZE) {
                if (aborted.get()) {
                    store.putMeta(META_LAST_GID, String.valueOf(pending.get(start).gid));
                    if (listener != null) {
                        listener.onFinish(done, true);
                    }
                    return;
                }
                int end = Math.min(pending.size(), start + SemanticConfig.BATCH_SIZE);
                List<GalleryInfo> batch = pending.subList(start, end);

                List<String> keys = new ArrayList<>(batch.size());
                List<String> docs = new ArrayList<>(batch.size());
                for (GalleryInfo info : batch) {
                    String doc = DocumentComposer.compose(info, loadTags(info.gid));
                    if (doc == null) {
                        continue;
                    }
                    keys.add(String.valueOf(info.gid));
                    docs.add(doc);
                }

                if (!docs.isEmpty()) {
                    try {
                        List<float[]> vectors = manager.embedDocuments(docs);
                        store.upsertBatch(keys, SemanticConfig.KIND_GALLERY, modelId, vectors);
                    } catch (EmbeddingException e) {
                        Log.w(TAG, "batch embed failed: " + e.getMessage());
                        if (listener != null) {
                            listener.onError("编码失败: " + e.getMessage());
                            listener.onFinish(done, true);
                        }
                        return;
                    }
                }

                done += batch.size();
                if (listener != null) {
                    listener.onProgress(done, pending.size(), batch.get(batch.size() - 1).title);
                }
                // 批间让出 CPU，避免抢占前台交互
                Thread.sleep(60L);
            }

            if (listener != null) {
                listener.onFinish(done, false);
            }
        } catch (Throwable t) {
            Log.e(TAG, "index crash", t);
            if (listener != null) {
                listener.onError(String.valueOf(t.getMessage()));
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * 汇总需要索引的条目：下载列表 + 本地收藏，按 gid 去重。
     *
     * <p>两者合并索引的理由：语义搜索要在这两个列表里都能命中，
     * 而绝大多数条目只会出现在其中一处，用 gid 作主键即可合并存储。
     */
    private List<GalleryInfo> collectIndexable() {
        Map<Long, GalleryInfo> merged = new LinkedHashMap<>();
        try {
            List<DownloadInfo> downloads = EhDB.getAllDownloadInfo();
            if (downloads != null) {
                for (DownloadInfo info : downloads) {
                    if (info != null) {
                        merged.put(info.gid, info);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "load downloads failed: " + t);
        }
        try {
            List<GalleryInfo> favorites = EhDB.getAllLocalFavorites();
            if (favorites != null) {
                for (GalleryInfo info : favorites) {
                    if (info != null && !merged.containsKey(info.gid)) {
                        merged.put(info.gid, info);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "load favorites failed: " + t);
        }
        return new ArrayList<>(merged.values());
    }

    private GalleryTags loadTags(long gid) {
        try {
            return EhDB.getDaoSession().getGalleryTagsDao().load(gid);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否正在充电。 */
    public static boolean isCharging() {
        Context context = sAppContext;
        if (context == null) {
            return false;
        }
        try {
            Intent status = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (status == null) {
                return false;
            }
            int plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            return plugged != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 索引统计，供设置页展示。 */
    public String stats(SemanticVectorStore store, String modelId) {
        long total = store.count(SemanticConfig.KIND_GALLERY);
        long size = estimateStoreBytes(store);
        return "已索引 " + total + " 条 · 约 " + ModelManager.formatSize(size)
                + " · 模型 " + (modelId == null ? "未选择" : modelId);
    }

    private long estimateStoreBytes(SemanticVectorStore store) {
        // int8 + scale ≈ dim + 4 字节/条
        return store.countAll() * (SemanticConfig.DIM_256 + 4);
    }

    /** 清空索引（换模型或排查问题时使用）。 */
    public void clearIndex() {
        SemanticSearchManager.getInstance(appContext).getStore()
                .clear(SemanticConfig.KIND_GALLERY);
    }
}