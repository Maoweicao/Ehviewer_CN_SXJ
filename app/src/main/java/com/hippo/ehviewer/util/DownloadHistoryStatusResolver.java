package com.hippo.ehviewer.util;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.dao.DownloadHistory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 下载历史标记（已删除 / 已合并）的异步解析器。
 *
 * <p>画廊列表每个 item 的 bind 都需要判断"已删除 / 已合并"标记。旧实现直接在
 * bind 里调用 {@link EhDB#getDownloadHistory(long)}：EhDB 的全局 synchronized
 * 会和后台批量任务（扫描、合并、历史维护）抢锁，滑动列表时立刻表现为掉帧，
 * 严重时就是 ANR。
 *
 * <p>这里改为缓存优先：
 * - 命中内存缓存直接返回（主线程零 I/O）；
 * - 未命中先隐藏标记，在单线程低优先级线程池里查一次，结果回主线程刷新；
 * - 历史写入路径通过 {@link #invalidate(long)} / {@link #invalidateAll()} 失效。
 */
public final class DownloadHistoryStatusResolver {

    /** 尚未解析。 */
    public static final int UNKNOWN = -1;
    /** 没有下载历史记录。 */
    public static final int NONE = 0;
    /** 有历史记录且未被合并（是否算"已删除"还要看下载列表是否仍包含该 gid）。 */
    public static final int HISTORY_NORMAL = 1;
    /** 历史记录为重复/递进合并。 */
    public static final int MERGED = 2;

    public interface Callback {
        void onResolved(long gid, int status);
    }

    private static final ConcurrentHashMap<Long, Integer> CACHE = new ConcurrentHashMap<>();
    private static final Set<Long> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HistoryStatus");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DownloadHistoryStatusResolver() {
    }

    public static int getCached(long gid) {
        Integer status = CACHE.get(gid);
        return status != null ? status : UNKNOWN;
    }

    /**
     * 异步解析状态。
     *
     * <p>已缓存时会同步回调；否则并入后台查询，同一个 gid 只查一次，
     * 结果通过主线程回调。相同 gid 的重复请求在查询期间会被合并（仅首个请求
     * 收到回调），调用方应保证未命中时先隐藏标记。
     */
    public static void resolve(long gid, @Nullable Callback callback) {
        Integer cached = CACHE.get(gid);
        if (cached != null) {
            if (callback != null) {
                callback.onResolved(gid, cached);
            }
            return;
        }
        if (!IN_FLIGHT.add(gid)) {
            return;
        }
        EXECUTOR.execute(() -> {
            int computedStatus;
            try {
                computedStatus = compute(gid);
            } catch (Throwable t) {
                computedStatus = NONE;
            }
            final int status = computedStatus;
            CACHE.put(gid, status);
            IN_FLIGHT.remove(gid);
            final Callback target = callback;
            if (target != null) {
                MAIN.post(() -> target.onResolved(gid, status));
            }
        });
    }

    private static int compute(long gid) {
        DownloadHistory history = EhDB.getDownloadHistory(gid);
        if (history == null) {
            return NONE;
        }
        int type = history.getDeletionType();
        if (type == DownloadHistory.DELETION_DUPLICATE_MERGED
                || type == DownloadHistory.DELETION_PROGRESSIVE_MERGED) {
            return MERGED;
        }
        return HISTORY_NORMAL;
    }

    /** 单条历史写入后调用（主线程/后台线程均可）。 */
    public static void invalidate(long gid) {
        CACHE.remove(gid);
    }

    /** 批量历史变更后调用。 */
    public static void invalidateAll() {
        CACHE.clear();
    }

    @NonNull
    public static String describe(int status) {
        switch (status) {
            case NONE:
                return "none";
            case HISTORY_NORMAL:
                return "normal";
            case MERGED:
                return "merged";
            default:
                return "unknown";
        }
    }
}
