/*
 * Copyright 2016 Hippo Seven
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

package com.hippo.ehviewer.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.cache.GalleryCacheManager;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadHistory;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.sync.GalleryTagsWriter;
import com.hippo.ehviewer.lab.analyze.AiAnalyzeManager;
import com.hippo.ehviewer.lab.analyze.model.AiGalleryAnalysis;
import com.hippo.ehviewer.limit.RemoteLimitGuard;
import com.hippo.ehviewer.download.sort.DownloadSortEngine;
import com.hippo.ehviewer.local.LocalRatingManager;
import com.hippo.ehviewer.network.NetworkStateManager;
import com.hippo.ehviewer.spider.GalleryIntegrityChecker;
import com.hippo.ehviewer.spider.GalleryIntegrityResult;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.ehviewer.task.BackgroundTask;
import com.hippo.ehviewer.task.DownloadingRecheckTask;
import com.hippo.ehviewer.task.PreDownloadMergeTask;
import com.hippo.ehviewer.task.PreDownloadMergeTask.Outcome;
import com.hippo.ehviewer.task.PtokenIndexUpdater;
import com.hippo.ehviewer.task.SimpleScanCache;
import com.hippo.ehviewer.task.SpiderTokenUtils;
import com.hippo.ehviewer.task.WaitListProgressiveCheckTask;
import com.hippo.lib.image.Image;
//import com.hippo.lib.image.Image1;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.lib.yorozuya.ConcurrentPool;
import com.hippo.lib.yorozuya.MathUtils;
import com.hippo.lib.yorozuya.ObjectUtils;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.lib.yorozuya.collect.SparseIJArray;

import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadManager implements SpiderQueen.OnSpiderListener {

    private static final String TAG = DownloadManager.class.getSimpleName();

    public static final String DOWNLOAD_INFO_FILENAME = ".ehviewer";
    public static final String DOWNLOAD_INFO_HEADER = "gid,token,title,title_jpn,thumb,category,posted,uploader,rating,rated,simple_lang,simple_tags,thumb_width,thumb_height,span_size,span_index,span_group_index,favorite_slot,favorite_name,pages";

    private final Context mContext;

    // All download info list
    //
    // 下载列表会被主线程（UI/下载回调）、下载线程池（完成回调）和后台扫描任务
    // （预下载合并、预碰撞检测）同时读写。LinkedList 在这种场景下会让正在遍历
    // 的一侧抛 ConcurrentModificationException（crash-2026-10-29 19:42 即此问题），
    // 因此这里统一改用写时复制列表：读写都不需要加锁，迭代器拿到的是稳定快照。
    private final List<DownloadInfo> mAllInfoList;
    // All download info map（同样会被多个线程读写，使用并发 Map 避免数组结构被撕裂）
    private final Map<Long, DownloadInfo> mAllInfoMap;
    // label and info list map, without default label info list
    private final Map<String, List<DownloadInfo>> mMap;

    private final Map<String, Long> mLabelCountMap;
    // All labels without default label
    private final List<DownloadLabel> mLabelList;
    // Store download info with default label
    private final List<DownloadInfo> mDefaultInfoList;
    // Store download info wait to start
    private final List<DownloadInfo> mWaitList;

    /**
     * 用户手动点“开始”的画廊（按点击顺序）。
     * 这些画廊始终排在“开始全部/高级排序”的结果之前，实现
     * “独立指定的开始顺序 > 之前设定的顺序”。
     */
    private final List<Long> mManualPriorityGids = new ArrayList<>();

    /**
     * 内部 loop-download 的 stopAll→startAll 周期不应清除用户的手动优先顺序，
     * 只有用户主动“停止全部”才重置。
     */
    private boolean mLoopRestartInProgress = false;

    private void markManualPriority(@NonNull DownloadInfo info) {
        mManualPriorityGids.remove(info.gid);
        mManualPriorityGids.add(info.gid);
        Log.i(TAG, "Manual start priority: gid=" + info.gid + " order=" + mManualPriorityGids);
    }

    /**
     * 把手动开始的画廊按点击顺序移动到等待队列最前。
     * 其余任务之间的相对顺序保持不变。
     */
    private void moveManualToFront(@NonNull List<DownloadInfo> list) {
        if (mManualPriorityGids.isEmpty() || list.size() < 2) return;
        List<DownloadInfo> manual = new ArrayList<>();
        for (Long gid : mManualPriorityGids) {
            for (DownloadInfo info : list) {
                if (info.gid == gid) {
                    manual.add(info);
                    break;
                }
            }
        }
        if (manual.isEmpty() || manual.size() == list.size()) return;
        List<DownloadInfo> rest = new ArrayList<>(list);
        rest.removeAll(manual);
        List<DownloadInfo> ordered = new ArrayList<>(list.size());
        ordered.addAll(manual);
        ordered.addAll(rest);
        list.clear();
        list.addAll(ordered);
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < list.size() && i < 8; i++) {
            if (i > 0) order.append(',');
            order.append(list.get(i).gid);
        }
        Log.i(TAG, "Wait queue manual-first: " + order);
    }

    private void clearManualPriority() {
        mManualPriorityGids.clear();
    }

    // 打开应用后自动开始未完成下载：每个进程只调度一次，延时几秒执行。
    private static final long AUTO_START_ON_OPEN_DELAY_MS = 3000L;
    private static final int AUTO_START_CORE_WAIT_MAX = 20;
    private boolean mAutoStartOnOpenScheduled = false;
    private int mAutoStartCoreWaitCount = 0;

    // “开始全部”的页数预取批次序号：停止全部或再次开始全部时递增，旧批次自动退出。
    private final AtomicInteger mStartAllPrefetchGeneration = new AtomicInteger();

    // 下载进度每完成多少页落一次库，保证重启后“剩余页数”排序仍有依据。
    private static final int PROGRESS_PERSIST_STEP = 20;

    // 专用后台线程：只跑速度统计/进度节拍这类不碰 UI 的周期性任务。
    // 之前这部分挂在 SimpleHandler(主 Looper) 上，主线程被系统限流时进度会整体停摆。
    //
    // 惰性初始化：Handler.getLooper() 在 HandlerThread 尚未 start() 时返回 null，
    // 字段初始化阶段无法保证线程已启动，因此改为首次使用时再建。
    @Nullable
    private volatile Handler mSpeedHandler;

    /**
     * 获取（并在首次调用时创建）速度统计专用后台 Handler。
     * 线程以BACKGROUND 优先级启动，避免与 UI 争抢 CPU。
     */
    @NonNull
    private Handler speedHandler() {
        Handler h = mSpeedHandler;
        if (h != null) {
            return h;
        }
        synchronized (this) {
            if (mSpeedHandler == null) {
                HandlerThread thread = new HandlerThread("Download-Speed",
                        Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                mSpeedHandler = new Handler(thread.getLooper());
            }
            return mSpeedHandler;
        }
    }

    // 预下载合并任务 gid -> taskId，用于停止按钮取消合并
    private final Map<Long, String> mPreMergeTaskIds = new ConcurrentHashMap<>();

    // 等待列表预碰撞检测任务 gid -> taskId，用于停止按钮取消检测。
    // 与 mPreMergeTaskIds 共享并发控制（mPreMergeSemaphore / mPendingPreMerge）。
    private final Map<Long, String> mWaitListCheckTaskIds = new ConcurrentHashMap<>();

    // 下载中重预检任务 id（同一时刻只允许一个）与被停止任务的原始状态，用于完成后恢复。
    @Nullable
    private String mRecheckDownloadingTaskId;
    private final Map<Long, Integer> mRecheckOriginalStates = new ConcurrentHashMap<>();

    // 预下载简单重复画廊扫描并发限制：同时最多运行 MAX_CONCURRENT_PRE_MERGE 个扫描任务，
    // 其余进入 mPendingPreMerge 等待队列，槽位释放后自动接力，避免依次添加大量画廊时拖垮手机性能。
    // 计数同时覆盖 PreDownloadMergeTask 与 WaitListProgressiveCheckTask，两者合计最多 2 个并发。
    private static final int MAX_CONCURRENT_PRE_MERGE = 2;
    private static final String PRE_MERGE_WAIT_TASK_PREFIX = "pre_merge_wait_";
    private final Semaphore mPreMergeSemaphore = new Semaphore(MAX_CONCURRENT_PRE_MERGE);
    private final LinkedList<DownloadInfo> mPendingPreMerge = new LinkedList<>();

    // 预下载扫描阶段跟踪：扫描期内任务按其扫描完成先后进入下载队列，顺序可能与用户期望的
    // 排序不一致。这里统计本批次进行中 + 排队的预下载任务数，等任务队列清零时走一遍
    // 「停止全部 → 开始全部」重新安排下载队列顺序。
    private final Object mPreMergeStateLock = new Object();
    private int mActivePreMergeTasks = 0;
    private boolean mPreMergePhaseActive = false;
    private int mPreMergeBatchSize = 0;

    private final SpeedReminder mSpeedReminder;

    private final List<DownloadListener> mDownloadListeners;
    private final List<DownloadInfoListener> mDownloadInfoListeners;

    @Nullable
    private DownloadInfo mCurrentTask;
    @Nullable
    private SpiderQueen mCurrentSpider;

    private final ConcurrentPool<NotifyTask> mNotifyTaskPool = new ConcurrentPool<>(5);

    // 循环开始下载直至完成：自动重试轮数限制，防止极端情况下无限循环
    private static final int MAX_LOOP_DOWNLOAD_ROUNDS = 5;
    private int mLoopDownloadRetryCount = 0;

    // ===== 批次下载 =====
    // 每批只放行固定数量进入下载队列，整批结束后等待冷却时间再放行下一批。
    private boolean mBatchMode = false;
    private int mBatchRemaining = 0;
    private final Runnable mBatchResumeRunnable = new Runnable() {
        @Override
        public void run() {
            ensureDownload();
        }
    };

    public DownloadManager(Context context) {
        mContext = context;

        // Get all labels
        List<DownloadLabel> labels = EhDB.getAllDownloadLabelList();
        mLabelList = labels;

        // Create list for each label
        Map<String, List<DownloadInfo>> map = new HashMap<>();
        mMap = map;
        for (DownloadLabel label : labels) {
            map.put(label.getLabel(), new CopyOnWriteArrayList<>());
        }

        // Create default for non tag
        mDefaultInfoList = new CopyOnWriteArrayList<>();

        // Get all info
        List<DownloadInfo> allInfoList = EhDB.getAllDownloadInfo();
        mAllInfoList = new CopyOnWriteArrayList<>(allInfoList);

        // Populate transient local rating overrides from LocalRatingManager
        LocalRatingManager localRatingManager = LocalRatingManager.getInstance(mContext);
        for (DownloadInfo info : allInfoList) {
            info.localRating = localRatingManager.getRating(info.gid);
        }

        // Create all info map
        Map<Long, DownloadInfo> allInfoMap = new ConcurrentHashMap<>(allInfoList.size() * 2 + 10);
        mAllInfoMap = allInfoMap;

        for (int i = 0, n = allInfoList.size(); i < n; i++) {
            DownloadInfo info = allInfoList.get(i);

            if (info.archiveUri != null && info.archiveUri.startsWith("content://")) {
                try {
                    Uri uri = Uri.parse(info.archiveUri);
                    mContext.getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    // Permission might already be taken or URI might be invalid
                    android.util.Log.w("DownloadManager", "Failed to restore URI permission for " + info.archiveUri, e);
                }
            }

            // Add to all info map
            allInfoMap.put(info.gid, info);

            // Add to each label list
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list == null) {
                // Can't find the label in label list
                list = new CopyOnWriteArrayList<>();
                map.put(info.label, list);
                if (!containLabel(info.label)) {
                    // Add label to DB and list
                    labels.add(EhDB.addDownloadLabel(info.label));
                }
            }
            list.add(info);
        }

        mLabelCountMap = new ConcurrentHashMap<>();

        for (Map.Entry<String, List<DownloadInfo>> entry : map.entrySet()) {
            mLabelCountMap.put(entry.getKey(), (long) entry.getValue().size());
        }

        mWaitList = new CopyOnWriteArrayList<>();
        mSpeedReminder = new SpeedReminder();
        mDownloadListeners = new CopyOnWriteArrayList<>();
        mDownloadInfoListeners = new ArrayList<>();

        // 清理上一次会话遗留的“预下载扫描排队”占位任务（进程被杀时未及移除）
        try {
            com.hippo.ehviewer.BackgroundTaskManager.getInstance().getTaskStatusManager()
                    .removeTasksWithPrefix(PRE_MERGE_WAIT_TASK_PREFIX);
        } catch (Throwable ignored) {
        }

        // Restore interrupted downloads: re-add STATE_WAIT items to the wait list
        // Also reset any stuck STATE_DOWNLOAD items back to STATE_WAIT
        for (DownloadInfo info : mAllInfoList) {
            if (info.state == DownloadInfo.STATE_WAIT) {
                mWaitList.add(info);
            } else if (info.state == DownloadInfo.STATE_DOWNLOAD) {
                info.state = DownloadInfo.STATE_WAIT;
                mWaitList.add(info);
                EhDB.putDownloadInfo(info);
            }
        }
        if (Settings.getAdvancedDownloadSortEnabled()) {
            applyAdvancedSort(mWaitList);
        }

        // 恢复进程被杀前的批次下载状态；冷却未结束时只安排计时器，不立即发请求。
        mBatchMode = BatchDownloadStore.isActive(mContext);
        mBatchRemaining = BatchDownloadStore.getRemaining(mContext);
        if (mBatchMode) {
            if (mWaitList.isEmpty() && mCurrentTask == null) {
                clearBatchMode();
            } else {
                SimpleHandler.getInstance().post(new Runnable() {
                    @Override
                    public void run() {
                        ensureDownload();
                    }
                });
            }
        }

        // 限流探测：检测到站点限流/封禁后，按局域网策略暂停远端下载。
        // 公网 IP 变化自动解除限流后，立即重新尝试队列。
        RemoteLimitGuard.getInstance(mContext).addListener(new RemoteLimitGuard.Listener() {
            @Override
            public void onLimitDetected(String reason, long untilMs) {
                SimpleHandler.getInstance().post(() -> onRemoteLimitDetected(reason, untilMs));
            }

            @Override
            public void onLimitCleared(String reason) {
                SimpleHandler.getInstance().post(() -> {
                    Log.i(TAG, "Remote limit cleared: " + reason + ", resuming downloads");
                    if (Settings.getAdvancedDownloadSortEnabled() && !mWaitList.isEmpty()) {
                        applyAdvancedSort(mWaitList);
                    }
                    ensureDownload();
                });
            }
        });
    }

    private void onRemoteLimitDetected(String reason, long untilMs) {
        boolean lanDownload = Settings.getLabDownloadFromLanFirst()
                || Settings.getLanOnlyMode();
        Log.w(TAG, "Remote limit detected, lanDownload=" + lanDownload
                + ", reason=" + reason + ", until=" + untilMs);
        if (!lanDownload) {
            stopAllDownload();
        }
        // 开启局域网优先后不停止队列：远端页请求会在 SpiderQueen /
        // GalleryPageFetcher 中被 guard 拦截，局域网页仍可继续。
    }

    public void replaceInfo(DownloadInfo newInfo, DownloadInfo oldInfo) {

        for (int i = 0; i < mAllInfoList.size(); i++) {
            if (oldInfo.gid == mAllInfoList.get(i).gid) {
                mAllInfoList.set(i, newInfo);
                break;
            }
        }
        final List<DownloadInfo> infoList = getInfoListForLabel(oldInfo.label);
        if (infoList != null) {
            for (int i = 0; i < infoList.size(); i++) {
                if (oldInfo.gid == infoList.get(i).gid) {
                    infoList.set(i, newInfo);
                    break;
                }
            }
        }

        mAllInfoMap.remove(oldInfo.gid);
        mAllInfoMap.put(newInfo.gid, newInfo);


        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReplace(newInfo, oldInfo);
        }
    }

    /**
     * 设备间同步（§5.30）应用完一批远端元数据后，通知所有列表刷新。
     *
     * <p>同步器运行在后台线程，批量写入数据库后调用一次，避免逐条触发 UI 重绘。
     * 必须在主线程调用。
     */
    public void notifySyncedExternal() {
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
    }

    @Nullable
    private List<DownloadInfo> getInfoListForLabel(String label) {
        if (label == null) {
            return mDefaultInfoList;
        } else {
            return mMap.get(label);
        }
    }

    public boolean containLabel(String label) {
        if (label == null) {
            return false;
        }

        for (DownloadLabel raw : mLabelList) {
            if (label.equals(raw.getLabel())) {
                return true;
            }
        }

        return false;
    }

    public boolean containDownloadInfo(long gid) {
        return mAllInfoMap.containsKey(gid);
    }

    @NonNull
    public List<DownloadLabel> getLabelList() {
        return mLabelList;
    }

    @Nullable
    public long getLabelCount(String label) {
        if (label == null) {
            // 默认标签的下载项不走 mLabelCountMap（ConcurrentHashMap 不允许 null key）
            return 0;
        }
        Long count = mLabelCountMap.get(label);
        return count == null ? 0 : count;
    }

    private void updateLabelCount(@Nullable String label, long delta) {
        if (label != null && mLabelCountMap.containsKey(label)) {
            long newCount = mLabelCountMap.get(label) + delta;
            if (newCount <= 0) {
                mLabelCountMap.remove(label);
            } else {
                mLabelCountMap.put(label, newCount);
            }
        }
    }

    public List<DownloadInfo> getAllDownloadInfoList() {
        return mAllInfoList;
    }

    @NonNull
    public List<DownloadInfo> getDefaultDownloadInfoList() {
        return mDefaultInfoList;
//        List<DownloadInfo> infoList = new ArrayList<>();
//        int i = 0;
//        while (infoList.size() < 30000) {
//            if (i == mDefaultInfoList.size()) {
//                i = 0;
//            }
//            infoList.add(mDefaultInfoList.get(i));
//            i++;
//        }
//        return infoList;
    }

    @Nullable
    public List<DownloadInfo> getLabelDownloadInfoList(String label) {
        return mMap.get(label);
    }

    public List<GalleryInfo> getDownloadInfoList() {
        return new ArrayList<>(mAllInfoList);
    }

    /**
     * 传输/后台线程安全读取：返回全部下载信息的快照副本。
     *
     * <p>底层列表是写时复制列表，迭代器本身就是稳定快照；这里再显式拷一份，
     * 供调用方在长时间格式化过程中安全持有。
     */
    @NonNull
    public List<DownloadInfo> getAllDownloadInfoSnapshot() {
        return copyDownloadListSafely(mAllInfoList);
    }

    /** 传输/后台线程安全读取：默认标签下载信息的快照副本。 */
    @NonNull
    public List<DownloadInfo> getDefaultDownloadInfoSnapshot() {
        return copyDownloadListSafely(mDefaultInfoList);
    }

    /**
     * 批量导入后整体重载下载列表。
     *
     * <p>「重建下载记录」一次可能写入上万条记录，逐条 {@link #addDownload} 会触发上万次
     * UI 通知与标签落库；这里在后台线程把库里的数据重新组织好，再在主线程一次性替换
     * 内存列表并通知一次 {@code onUpdateAll()}。
     */
    public void reloadAllFromDatabase() {
        final List<DownloadLabel> labels = EhDB.getAllDownloadLabelList();
        final List<DownloadInfo> all = EhDB.getAllDownloadInfo();

        LocalRatingManager localRatingManager = LocalRatingManager.getInstance(mContext);
        for (DownloadInfo info : all) {
            info.localRating = localRatingManager.getRating(info.gid);
        }

        final Map<Long, DownloadInfo> allMap = new ConcurrentHashMap<>(all.size() * 2 + 10);
        final Map<String, List<DownloadInfo>> labelMap = new HashMap<>();
        for (DownloadLabel label : labels) {
            labelMap.put(label.getLabel(), new CopyOnWriteArrayList<>());
        }
        final List<DownloadInfo> defaultList = new CopyOnWriteArrayList<>();
        for (DownloadInfo info : all) {
            allMap.put(info.gid, info);
            if (info.label == null) {
                defaultList.add(info);
            } else {
                List<DownloadInfo> list = labelMap.get(info.label);
                if (list == null) {
                    list = new CopyOnWriteArrayList<>();
                    labelMap.put(info.label, list);
                }
                list.add(info);
            }
        }

        final Map<String, List<DownloadInfo>> newLabelMap = labelMap;
        final List<DownloadInfo> newDefaultList = defaultList;
        SimpleHandler.getInstance().post(() -> {
            try {
                mLabelList.clear();
                mLabelList.addAll(labels);
                mMap.clear();
                mMap.putAll(newLabelMap);
                mAllInfoList.clear();
                mAllInfoList.addAll(all);
                mAllInfoMap.clear();
                mAllInfoMap.putAll(allMap);
                mDefaultInfoList.clear();
                mDefaultInfoList.addAll(newDefaultList);
                mLabelCountMap.clear();
                for (Map.Entry<String, List<DownloadInfo>> entry : newLabelMap.entrySet()) {
                    mLabelCountMap.put(entry.getKey(), (long) entry.getValue().size());
                }
                for (DownloadInfoListener l : new ArrayList<>(mDownloadInfoListeners)) {
                    l.onUpdateAll();
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "reloadAllFromDatabase failed", e);
            }
        });
    }

    /** 传输/后台线程安全读取：指定标签下载信息的快照副本；标签不存在返回 null。 */
    @Nullable
    public List<DownloadInfo> getLabelDownloadInfoSnapshot(String label) {
        List<DownloadInfo> list = mMap.get(label);
        return list == null ? null : copyDownloadListSafely(list);
    }

    private static List<DownloadInfo> copyDownloadListSafely(List<DownloadInfo> src) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return new ArrayList<>(src);
            } catch (RuntimeException e) {
                if (attempt == 0) {
                    // 并发修改，稍后重试
                    continue;
                }
                Log.w(TAG, "copyDownloadListSafely failed, returning empty snapshot", e);
            }
        }
        return new ArrayList<>();
    }

    @NonNull
    public List<DownloadInfo> getWaitList() {
        return mWaitList;
    }

    @Nullable
    public DownloadInfo getDownloadInfo(long gid) {
        return mAllInfoMap.get(gid);
    }

    @Nullable
    public DownloadInfo getNoneDownloadInfo(long gid) {
        if (mCurrentTask != null && mCurrentTask.gid == gid) {
            // Stop current
            stopCurrentDownloadInternal();
        } else {
            // Remove wait
            for (DownloadInfo info : mWaitList) {
                if (info.gid == gid) {
                    info.state = DownloadInfo.STATE_NONE;
                    // Remove from wait list
                    mWaitList.remove(info);
                    break;
                }
            }
        }
        return mAllInfoMap.get(gid);
    }

    public int getDownloadState(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (null != info) {
            return info.state;
        } else {
            return DownloadInfo.STATE_INVALID;
        }
    }

    public void addDownloadInfoListener(@Nullable DownloadInfoListener downloadInfoListener) {
        if (downloadInfoListener != null && !mDownloadInfoListeners.contains(downloadInfoListener)) {
            mDownloadInfoListeners.add(downloadInfoListener);
        }
    }

    public void removeDownloadInfoListener(@Nullable DownloadInfoListener downloadInfoListener) {
        mDownloadInfoListeners.remove(downloadInfoListener);
    }

    public void setDownloadListener(@Nullable DownloadListener listener) {
        if (listener == null) {
            mDownloadListeners.clear();
        } else if (!mDownloadListeners.contains(listener)) {
            mDownloadListeners.add(listener);
        }
    }

    public void addDownloadListener(@NonNull DownloadListener listener) {
        if (!mDownloadListeners.contains(listener)) {
            mDownloadListeners.add(listener);
        }
    }

    public void removeDownloadListener(@NonNull DownloadListener listener) {
        mDownloadListeners.remove(listener);
    }

    public void ensureDownload() {
        if (mCurrentTask != null) {
            // Only one download
            return;
        }

        // 限流冷却期间：没有局域网优先时，不再启动任何远端下载。
        if (RemoteLimitGuard.getInstance(mContext).isBlocked()
                && !Settings.getLabDownloadFromLanFirst()
                && !Settings.getLanOnlyMode()) {
            Log.i(TAG, "Remote limit blocked, skip starting downloads");
            return;
        }

        if (mBatchMode) {
            long now = System.currentTimeMillis();
            long cooldownUntil = BatchDownloadStore.getCooldownUntil(mContext);
            if (cooldownUntil > now) {
                scheduleBatchEnsure(cooldownUntil - now);
                return;
            }
            if (mBatchRemaining <= 0) {
                int size = Math.max(1, Settings.getDownloadBatchSize());
                long cooldownMs = Settings.getDownloadBatchCooldownSeconds() * 1000L;
                if (cooldownMs > 0 && !mWaitList.isEmpty()) {
                    BatchDownloadStore.setCooldownUntil(mContext, now + cooldownMs);
                    mBatchRemaining = size;
                    BatchDownloadStore.setRemaining(mContext, size);
                    Log.i(TAG, "Batch cooldown " + cooldownMs + "ms before next "
                            + size + " galleries");
                    scheduleBatchEnsure(cooldownMs);
                    return;
                }
                mBatchRemaining = size;
                BatchDownloadStore.setRemaining(mContext, size);
            }
        }

        // 手动开始的画廊始终优先于“开始全部”的排序结果。
        moveManualToFront(mWaitList);

        // Get download from wait list
        if (!mWaitList.isEmpty()) {
            DownloadInfo info = mWaitList.remove(0);
            if (mBatchMode) {
                mBatchRemaining = Math.max(0, mBatchRemaining - 1);
                BatchDownloadStore.setRemaining(mContext, mBatchRemaining);
            }

            // Route to SystemDMBackend if user enabled it and not origin image.
            // 局域网优先/仅局域网模式必须走内置 Spider 引擎，系统 DM 无法从对端读图。
            // 自动换 IP 启用时也必须走内置引擎：系统 DownloadManager 不经过应用代理，
            // 无法使用内置 Clash 内核，且会绕过节点切换。
            if (Settings.getUseSystemDownloadManager()
                    && !Settings.getDownloadOriginImage()
                    && !Settings.getLabDownloadFromLanFirst()
                    && !Settings.getLanOnlyMode()
                    && !Settings.getIpSwitchEnabled()) {
                int sysDmResult = tryEnsureSystemDM(info);
                if (sysDmResult == 1) {
                    return;                     // SystemDM started
                } else if (sysDmResult == -1) {
                    // Pages unknown, re-queued with async fetch; don't fall through
                    return;
                }
                // sysDmResult == 0: fall through to SpiderBackend
            }

            startSpiderDownload(info);
        } else if (mBatchMode) {
            clearBatchMode();
        }
    }

    private void scheduleBatchEnsure(long delayMs) {
        SimpleHandler.getInstance().removeCallbacks(mBatchResumeRunnable);
        SimpleHandler.getInstance().postDelayed(mBatchResumeRunnable, Math.max(1000L, delayMs));
    }

    /** 避免同一次切换被 finishSwitch / RemoteLimitGuard 重复触发。 */
    private volatile long mLastIpSwitchResumeAt;

    /**
     * 自动/手动换 IP 成功后的立即恢复。
     *
     * 旧实现只调用 ensureDownload()：当前任务还在时它直接 return，
     * 已失败的页只能等 loop-download 的下一轮（日志里是 6–16 分钟）。
     * 这里把未完成的当前任务重新入队，Spider 会重新扫描磁盘，只补缺失页。
     */
    public void onIpSwitched() {
        long now = System.currentTimeMillis();
        if (now - mLastIpSwitchResumeAt < 1500L) {
            return;
        }
        mLastIpSwitchResumeAt = now;
        // 自动换 IP 期间冷却已自动关闭；这里再清一次残留状态，
        // 保证 ensureDownload 不会因为旧冷却而拒绝启动。
        if (Settings.getIpSwitchEnabled()) {
            try {
                RemoteLimitGuard.getInstance(mContext).clearBlock();
            } catch (Throwable ignored) {
            }
        }
        resetLoopDownloadRetryCount();
        SimpleHandler.getInstance().post(() -> {
            DownloadInfo current = mCurrentTask;
            if (current != null && (current.total <= 0 || current.finished < current.total)) {
                long gid = current.gid;
                try {
                    DownloadInfo info = stopCurrentDownloadInternal();
                    if (info != null) {
                        info.state = DownloadInfo.STATE_NONE;
                        mWaitList.add(0, info);
                        EhDB.putDownloadInfo(info);
                        Log.i(TAG, "IP switched: requeue incomplete gid=" + gid
                                + " (" + info.finished + "/" + info.total + ")");
                        DownloadLogger logger = DownloadLogger.peek();
                        if (logger != null) {
                            logger.log(DownloadLogger.LogLevel.INFO, "DownloadManager",
                                    "IP 切换后立即重新入队 gid=" + gid
                                            + " (" + info.finished + "/" + info.total + ")",
                                    String.valueOf(gid), null);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "IP switched: requeue failed gid=" + gid, t);
                }
            }
            ensureDownload();
        });
    }

    /** 冷却期间且未开启局域网优先时，远端下载应被拒绝。 */
    public boolean isRemoteDownloadBlocked() {
        return RemoteLimitGuard.getInstance(mContext).isBlocked()
                && !Settings.getLabDownloadFromLanFirst()
                && !Settings.getLanOnlyMode();
    }

    private void clearBatchMode() {
        if (!mBatchMode) {
            return;
        }
        mBatchMode = false;
        mBatchRemaining = 0;
        SimpleHandler.getInstance().removeCallbacks(mBatchResumeRunnable);
        BatchDownloadStore.clear(mContext);
    }

    /**
     * 按设置的每批数量开始下载所有 STATE_NONE / STATE_FAILED 画廊。
     *
     * @return 本次纳入批次下载的画廊总数，0 表示没有待下载项
     */
    public int startBatchDownload() {
        if (isRemoteDownloadBlocked()) {
            Toast.makeText(mContext, R.string.limit_blocked_short, Toast.LENGTH_SHORT).show();
            return 0;
        }
        int pending = 0;
        for (DownloadInfo info : mAllInfoList) {
            if (info.archiveUri != null) continue;
            if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                pending++;
            }
        }
        if (pending == 0) {
            return 0;
        }
        resetLoopDownloadRetryCount();
        SimpleHandler.getInstance().removeCallbacks(mBatchResumeRunnable);
        mBatchMode = true;
        mBatchRemaining = Math.max(1, Settings.getDownloadBatchSize());
        BatchDownloadStore.start(mContext, mBatchRemaining);
        // 直接进入内部启动，跳过「一次性预取所有未知页数」，
        // 否则批次下载还没开始就会先向站点发一大批页数请求。
        startAllDownloadInternal();
        if (mWaitList.isEmpty() && mCurrentTask == null) {
            clearBatchMode();
        }
        return pending;
    }

    public boolean isBatchDownloadActive() {
        return mBatchMode;
    }

    /**
     * 尝试把当前任务交给 SystemDMBackend。
     *
     * @return 1 = handled (async completeness pre-check + enqueue); -1 = pages unknown (re-queued, async fetch pending); 0 = fallback to SpiderBackend
     */
    private int tryEnsureSystemDM(DownloadInfo info) {
        int pages = info.pages;

        // 1. 如果 page 数未知，先尝试从磁盘 SpiderInfo 拿
        if (pages <= 0) {
            com.hippo.ehviewer.spider.SpiderInfo si =
                    com.hippo.ehviewer.spider.SpiderInfo.getSpiderInfo(info);
            if (si != null && si.pages > 0) {
                pages = si.pages;
                info.pages = pages;
                info.total = pages;
                EhDB.putDownloadInfo(info);
                // pages 就绪后补跑一次此前因 pages=0 被跳过的预检
                dedupeProgressiveWaitTasks(info);
            }
        }

        // 2. 等不到 pages 就先异步拉一次，现在返回 -1 阻止 fallthrough
        if (pages <= 0) {
            info.state = DownloadInfo.STATE_WAIT;
            mWaitList.add(0, info);
            fetchPagesAndReroute(info);
            return -1;
        }

        // 3. 本地完整性检查会遍历目录（File.list），绝不能放在主线程。
        //    先转到 IO 线程检查，再回主线程决定「直接完成」还是「交给系统 DM」。
        mCurrentTask = info;
        startSystemDMPrecheckAsync(info, pages);
        return 1;
    }

    /**
     * 在 IO 线程判断图库是否已经完整落盘。
     *
     * <p>旧实现直接在 {@link #ensureDownload()} 的主线程调用
     * {@link GalleryIntegrityChecker#check(DownloadInfo, int)}，并在「已完整」时
     * 同步回调 {@link #onSystemDMGalleryFinished(long, GalleryIntegrityResult)}，
     * 后者又调用 {@link #ensureDownload()}，形成 ensureDownload → tryEnsureSystemDM
     * → onSystemDMGalleryFinished → ensureDownload 的递归；配合目录扫描会直接
     * 卡死主线程并触发 ANR。改为异步检查后递归链路被打断。
     */
    private void startSystemDMPrecheckAsync(@NonNull DownloadInfo info, int pages) {
        final long gid = info.gid;
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            GalleryIntegrityResult integrity;
            try {
                integrity = GalleryIntegrityChecker.check(info, pages);
            } catch (Throwable t) {
                Log.w(TAG, "SystemDM pre-check failed for gid=" + gid, t);
                integrity = null;
            }
            final GalleryIntegrityResult result = integrity;
            SimpleHandler.getInstance().post(() -> {
                if (mCurrentTask != info) {
                    // 期间已被停止或替换
                    return;
                }
                if (mAllInfoMap.get(gid) == null) {
                    mCurrentTask = null;
                    ensureDownload();
                    return;
                }
                if (result != null && result.isComplete()) {
                    Log.i(TAG, "SystemDM: 所有页已在磁盘，直接完成 gid=" + gid + " pages=" + pages);
                    onSystemDMGalleryFinished(gid, result);
                } else {
                    beginSystemDMDownload(info, pages);
                }
            });
        });
    }

    /** 完整性预检未通过时，正式把任务交给系统 DownloadManager。 */
    private void beginSystemDMDownload(@NonNull DownloadInfo info, int pages) {
        mCurrentTask = info;
        info.state = DownloadInfo.STATE_DOWNLOAD;
        info.speed = -1;
        info.remaining = -1;
        info.total = pages;
        info.finished = 0;
        info.downloaded = 0;
        info.legacy = -1;
        EhDB.putDownloadInfo(info);
        DownloadLogger.getInstance().logDownloadStart(info);
        RemoteLimitGuard.getInstance(mContext).beginSession(
                info.gid, EhUtils.getSuitableTitle(info));

        // 触发最小通知
        for (DownloadListener l : mDownloadListeners) {
            l.onStart(info);
        }
        List<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list != null) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdate(info, list, mWaitList);
            }
        }

        // 解析图片 URL + enqueue 全是网络/磁盘 I/O：必须放到后台线程。
        // 曾在主线程同步执行，导致 NetworkOnMainThreadException（所有页解析失败）
        // 且主线程卡住数秒。
        startSystemDMAsync(info, pages);
    }

    /**
     * 后台启动系统 DM 后端；完成后回主线程判断是否需要回退到内置引擎。
     */
    private void startSystemDMAsync(DownloadInfo info, int pages) {
        final long gid = info.gid;
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            int enqueued = 0;
            try {
                enqueued = SystemDMBackend.getInstance(mContext).start(info, pages);
            } catch (Throwable t) {
                Log.e(TAG, "ensureSystemDM failed for gid=" + gid, t);
            }
            final int result = enqueued;
            SimpleHandler.getInstance().post(() -> onSystemDMEnqueued(info, result));
        });
    }

    /**
     * SystemDM 入队结果回调（主线程）。
     *
     * 一页都没入队时回退到内置引擎，避免任务永远停在「下载中」。
     */
    private void onSystemDMEnqueued(@NonNull DownloadInfo info, int enqueued) {
        long gid = info.gid;
        if (mAllInfoMap.get(gid) == null) {
            // 已被删除
            return;
        }
        if (mCurrentTask != info) {
            // 已被停止或已切到别的任务
            return;
        }
        boolean hasTasks = SystemDMBackend.getInstance(mContext).hasPendingTasksForGid(gid);
        if (enqueued > 0 || hasTasks) {
            Log.i(TAG, "SystemDM: gid=" + gid + " enqueued " + enqueued + " page(s)");
            return;
        }
        Log.w(TAG, "SystemDM did not enqueue any page for gid=" + gid + ", falling back");
        mCurrentTask = null;
        info.state = DownloadInfo.STATE_NONE;
        EhDB.putDownloadInfo(info);
        startSpiderDownload(info);
    }

    /**
     * 用内置 SpiderQueen 引擎开始下载（系统 DM 未启用或回退时）。
     */
    private void startSpiderDownload(@NonNull DownloadInfo info) {
        SpiderQueen spider = SpiderQueen.obtainSpiderQueen(mContext, info, SpiderQueen.MODE_DOWNLOAD);
        mCurrentTask = info;
        mCurrentSpider = spider;
        RemoteLimitGuard.getInstance(mContext).beginSession(
                info.gid, EhUtils.getSuitableTitle(info));
        spider.addOnSpiderListener(this);
        info.state = DownloadInfo.STATE_DOWNLOAD;
        info.speed = -1;
        info.remaining = -1;
        info.total = -1;
        info.finished = 0;
        info.downloaded = 0;
        info.legacy = -1;
        // Update in DB
        EhDB.putDownloadInfo(info);
        // Log download start with full info
        DownloadLogger.getInstance().logDownloadStart(info);
        // Start speed count
        mSpeedReminder.start();
        // Keep a WorkManager expedited foreground worker alive for the
        // duration of the download. On aggressive OEMs (HyperOS etc.) the
        // SpiderWorker threads default to THREAD_PRIORITY_DEFAULT and can be
        // throttled once the app is backgrounded; the worker periodically
        // raises them to THREAD_PRIORITY_FOREGROUND and holds a second FGS,
        // which also makes the app less likely to be frozen.
        DownloadWorker.Companion.enqueue(mContext);
        // Notify start downloading
        for (DownloadListener l : mDownloadListeners) {
            l.onStart(info);
        }
        // Notify state update
        List<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list != null) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdate(info, list, mWaitList);
            }
        }
    }

    /**
     * 异步拉取 gallery page 数，拉完后重新调用 ensureDownload()。
     */
    private void fetchPagesAndReroute(DownloadInfo info) {
        long gid = info.gid;
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                // 从 E-Hentai 拿 GalleryDetail 获取 pages
                String url = EhUrl.getGalleryDetailUrl(gid, info.token);
                com.hippo.ehviewer.client.data.GalleryDetail detail =
                        com.hippo.ehviewer.client.EhEngine.getGalleryDetail(
                                null, EhApplication.getOkHttpClient(mContext), url);
                if (detail != null && detail.pages > 0) {
                    info.pages = detail.pages;
                    EhDB.putDownloadInfo(info);
                }
            } catch (Throwable t) {
                Log.w(TAG, "fetchPagesAndReroute failed for gid=" + gid, t);
            }
            // 回到主线程：pages 已就绪，补跑一次此前因 pages=0 被跳过的预检，再 ensureDownload
            SimpleHandler.getInstance().post(() -> {
                if (mAllInfoMap.get(gid) == null) {
                    return;
                }
                // 加入下载时 info.pages 还是 0，dedupeProgressiveWaitTasks 会直接跳过；
                // 这里 pages 已知，重新触发预碰撞检测，否则预检永远不会执行。
                dedupeProgressiveWaitTasks(info);
                // 如果 info 已经被 stop / delete 了，跳过
                if (!mWaitList.contains(info) && mCurrentTask != null && mCurrentTask.gid != gid) {
                    return;
                }
                ensureDownload();
            });
        });
    }

    /**
     * 由 SystemDownloadFinalizeWorker 在某 gid 的所有 page 都 SUCCESS 后调用。
     * 先做一次与内置引擎相同的完整性检查，再走完成收尾。
     */
    public void onSystemDMGalleryFinished(long gid) {
        GalleryIntegrityResult integrity = checkSystemDMCompletion(gid);
        if (integrity == null) {
            return;
        }
        onSystemDMGalleryFinished(gid, integrity);
    }

    /**
     * 系统委托下载的完成收尾。
     *
     * [integrity] 来自 {@link #checkSystemDMCompletion(long)} 或
     * {@link GalleryIntegrityChecker#check(DownloadInfo, int)}，与内置引擎共用同一判定。
     * 检查在既有回调线程内同步完成，不新建后台任务。
     */
    public void onSystemDMGalleryFinished(long gid, @NonNull GalleryIntegrityResult integrity) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) return;
        if (mCurrentTask == null || mCurrentTask.gid != gid) return;

        info.finished = integrity.getValidPages();
        info.legacy = integrity.getLegacy();
        boolean complete = integrity.isComplete()
                || (info.removedFromSource && Settings.getDownloadTreatRemovedAsComplete());
        info.state = complete ? DownloadInfo.STATE_FINISH : DownloadInfo.STATE_FAILED;
        RemoteLimitGuard.getInstance(mContext).endSession(
                complete ? "completed" : "failed");
        if (info.state == DownloadInfo.STATE_FINISH) {
            GalleryIntegrityChecker.deleteEmptyFiles(info, integrity);
        }
        EhDB.putDownloadInfo(info);
        // 新画廊下载完成，简单重复画廊扫描缓存已失效
        SimpleScanCache.invalidate();

        if (!complete) {
            Log.w(TAG, "SystemDM integrity check failed for gid=" + gid
                    + ", expected=" + integrity.getExpectedPages()
                    + ", valid=" + integrity.getValidPages()
                    + ", invalid=" + integrity.getInvalidIndices());
            DownloadLogger.getInstance().logDownloadError(
                    String.valueOf(gid), EhUtils.getSuitableTitle(info),
                    "系统下载完整性校验未通过 | 期望:" + integrity.getExpectedPages()
                            + " | 有效:" + integrity.getValidPages()
                            + " | 缺失页:" + integrity.getInvalidIndices(), null);
        }

        // onFinish 会按 info.state 区分完成/失败，与内置引擎路径一致
        for (DownloadListener l : mDownloadListeners) {
            l.onFinish(info);
        }
        if (complete) {
            maybeAutoAnalyzeGallery(info);
        }

        List<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list != null) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdate(info, list, mWaitList);
            }
        }

        mCurrentTask = null;
        ensureDownload();
    }

    /**
     * 若开启了"下载完成后自动分析"，则后台启动 AI 图片分析。
     */
    private void maybeAutoAnalyzeGallery(DownloadInfo info) {
        if (info == null || !Settings.getAiAnalyzeEnabled() || !Settings.getAiAnalyzeAutoOnFinish()) {
            return;
        }
        try {
            Log.i(TAG, "Auto AI analyze on download finish for gid=" + info.gid);
            AiAnalyzeManager.getInstance().analyzeGallery(info,
                    new AiAnalyzeManager.AnalyzeCallback() {
                        @Override
                        public void onProgress(int current, int total, String detail) {
                        }

                        @Override
                        public void onSuccess(AiGalleryAnalysis analysis) {
                            Log.i(TAG, "Auto AI analyze done for gid=" + info.gid);
                        }

                        @Override
                        public void onError(String error) {
                            Log.w(TAG, "Auto AI analyze failed for gid=" + info.gid + ": " + error);
                        }
                    });
        } catch (Throwable e) {
            Log.w(TAG, "maybeAutoAnalyzeGallery failed", e);
        }
    }

    /**
     * 系统委托下载的完成门槛：所有已入队任务均 SUCCESS 后，按入队页数做统一完整性检查。
     *
     * @return null 表示还有任务未成功或页数未知；非 null 表示可以进入完成收尾。
     *         返回结果本身仍可能是「不完整」，由调用方决定置为失败。
     */
    @Nullable
    public GalleryIntegrityResult checkSystemDMCompletion(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) {
            return null;
        }
        int pages = info.pages;
        if (pages <= 0) {
            SpiderInfo spiderInfo = SpiderInfo.getSpiderInfo(info);
            if (spiderInfo != null && spiderInfo.pages > 0) {
                pages = spiderInfo.pages;
            }
        }
        if (pages <= 0) {
            return null;
        }

        // 已入队任务必须全部 SUCCESS
        java.util.List<com.hippo.ehviewer.dao.SystemDownloadTask> tasks =
                EhDB.getSystemDownloadTasksForGid(gid);
        if (tasks != null) {
            for (com.hippo.ehviewer.dao.SystemDownloadTask t : tasks) {
                if (!com.hippo.ehviewer.dao.SystemDownloadTask.STATUS_SUCCESS.equals(t.getStatus())) {
                    return null;
                }
            }
        }

        // 与内置引擎共用同一完整性判定：缺失/0 字节/非文件都会计入 legacy
        return GalleryIntegrityChecker.check(info, pages);
    }

    /**
     * 检查 gid 的 SystemDM 任务是否全部完成且目录完整。
     * 保留给旧调用方；新代码应使用 {@link #checkSystemDMCompletion(long)}。
     */
    public boolean isSystemDMAllDone(long gid) {
        GalleryIntegrityResult integrity = checkSystemDMCompletion(gid);
        return integrity != null && integrity.isComplete();
    }

    /** Marks a system-delegated gallery as retryable when Android DownloadManager cannot finish it. */
    public void onSystemDMGalleryFailed(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) return;
        info.state = DownloadInfo.STATE_FAILED;
        EhDB.putDownloadInfo(info);
        RemoteLimitGuard.getInstance(mContext).endSession("failed");
        if (mCurrentTask != null && mCurrentTask.gid == gid) {
            mCurrentTask = null;
            ensureDownload();
        }
    }

    void startDownload(GalleryInfo galleryInfo, @Nullable String label) {
        if (isRemoteDownloadBlocked()) {
            Toast.makeText(mContext, R.string.limit_blocked_short, Toast.LENGTH_SHORT).show();
            return;
        }
        // 手动发起下载：清空「停止全部 → 开始全部」自动循环的重试计数，
        // 让手动会话获得全新的自动重试额度（自动循环耗尽后用户手动恢复不再被卡死）。
        resetLoopDownloadRetryCount();
        clearBatchMode();
        if (mCurrentTask != null && mCurrentTask.gid == galleryInfo.gid) {
            // It is current task
            return;
        }

        // Do nothing for locally imported archives or albums.
        if (galleryInfo instanceof DownloadInfo downloadInfo) {
            if (downloadInfo.archiveUri != null) {
                return;
            }
        }

        // Check in download list
        DownloadInfo info = mAllInfoMap.get(galleryInfo.gid);

        if (info != null) { // Get it in download list
            if (info.archiveUri != null) {
                return;
            }
            info.state = DownloadInfo.STATE_WAIT;
            if (!mWaitList.contains(info)) {
                mWaitList.add(info);
            }
            // 用户手动点开始：记录优先级并排到队首。
            markManualPriority(info);
            if (Settings.getAdvancedDownloadSortEnabled()) {
                applyAdvancedSort(mWaitList);
            }
            moveManualToFront(mWaitList);
            // Update in DB
            EhDB.putDownloadInfo(info);
            // Notify state update
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
            // Make sure download is running
            ensureDownload();
        } else {
            // It is new download info
            info = new DownloadInfo(galleryInfo);
            info.label = label;
            info.state = DownloadInfo.STATE_WAIT;
            info.time = System.currentTimeMillis();

            // Add to label download list
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list == null) {
                Log.e(TAG, "Can't find download info list with label: " + label);
                return;
            }
            list.add(0, info);

            // Add to all download list and map
            mAllInfoList.add(0, info);
            mAllInfoMap.put(galleryInfo.gid, info);

            // Add to wait list
            mWaitList.add(info);
            markManualPriority(info);
            // Apply advanced sort if enabled
            if (Settings.getAdvancedDownloadSortEnabled()) {
                applyAdvancedSort(mWaitList);
            }
            moveManualToFront(mWaitList);

            // Save to
            EhDB.putDownloadInfo(info);

            // Notify
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onAdd(info, list, list.size() - 1);
            }
            // Make sure download is running
            ensureDownload();

            // Add it to history
            EhDB.putHistoryInfo(info);
        }
    }

    void startRangeDownload(LongList gidList) {
        if (isRemoteDownloadBlocked()) {
            Toast.makeText(mContext, R.string.limit_blocked_short, Toast.LENGTH_SHORT).show();
            return;
        }
        // 手动批量发起下载：清空「停止全部 → 开始全部」自动循环的重试计数
        resetLoopDownloadRetryCount();
        clearBatchMode();

        List<DownloadInfo> pendingList = new ArrayList<>();
        for (int i = 0, n = gidList.size(); i < n; i++) {
            long gid = gidList.get(i);
            DownloadInfo info = mAllInfoMap.get(gid);
            if (null == info) {
                Log.d(TAG, "Can't get download info with gid: " + gid);
                continue;
            }
            if (info.archiveUri != null) {
                continue;
            }
            if (mCurrentTask != null && mCurrentTask.gid == gid) {
                continue;
            }

            // STATE_WAIT 也允许“提升优先级”：用户手动点开始后应排到队首。
            if (info.state == DownloadInfo.STATE_NONE ||
                    info.state == DownloadInfo.STATE_FAILED ||
                    info.state == DownloadInfo.STATE_FINISH ||
                    info.state == DownloadInfo.STATE_WAIT) {
                info.state = DownloadInfo.STATE_WAIT;
                markManualPriority(info);
                pendingList.add(info);
            }
        }
        if (pendingList.isEmpty()) {
            return;
        }

        // 已经在等待队列里的任务不重复添加，只把它们移动到手动优先区。
        List<DownloadInfo> toInsert = new ArrayList<>();
        for (DownloadInfo info : pendingList) {
            if (!mWaitList.contains(info)) {
                toInsert.add(info);
            }
        }
        // 手动选择的顺序就是优先级顺序，不再按 downloadOrder 反转。
        mWaitList.addAll(toInsert);
        if (Settings.getAdvancedDownloadSortEnabled()) {
            applyAdvancedSort(mWaitList);
        }
        moveManualToFront(mWaitList);

        // 状态变更一次性异步写库，避免大量任务主线程逐条写库导致 ANR。
        EhDB.updateDownloadInfoBulkAsync(pendingList);

        // Notify Listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
        // Ensure download
        ensureDownload();
    }

    public void startAllDownload() {
        if (isRemoteDownloadBlocked()) {
            // 冷却期间也把任务放进等待队列：冷却结束后自动开始，
            // 避免用户看到“点了开始全部但失败画廊仍然是失败”的错觉。
            queuePendingDownloadsForCooldown();
            return;
        }
        // 新的开始全部批次：取消上一轮尚未结束的页数预取。
        final int generation = mStartAllPrefetchGeneration.incrementAndGet();

        // 收集页数未知的待启动项；页数已知的项立即开始，不等预取全部完成。
        List<DownloadInfo> unknownPages = new ArrayList<>();
        for (DownloadInfo info : mAllInfoList) {
            if (info.pages <= 0 && info.total <= 0 &&
                    (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED)) {
                unknownPages.add(info);
            }
        }

        if (unknownPages.isEmpty()) {
            boolean needsProgress = DownloadSortEngine.needsProgress(mContext);
            if (needsProgress) {
                List<DownloadInfo> pendingWithPages = collectPendingWithKnownPages();
                if (!pendingWithPages.isEmpty()) {
                    IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                        scanProgressForSort(pendingWithPages);
                        postAndWait(this::startAllDownloadInternal);
                    });
                    return;
                }
            }
            startAllDownloadInternal();
            return;
        }

        startAllDownloadWithPrefetch(
                unknownPages, generation, DownloadSortEngine.needsProgress(mContext));
    }

    /**
     * 限流冷却期间响应“开始全部”：只入队，不发起任何远端请求。
     * 冷却结束或 IP 变化后由 RemoteLimitGuard 的 onLimitCleared 恢复下载。
     */
    private void queuePendingDownloadsForCooldown() {
        List<DownloadInfo> pendingList = new ArrayList<>();
        for (DownloadInfo info : mAllInfoList) {
            if (info.state == DownloadInfo.STATE_NONE
                    || info.state == DownloadInfo.STATE_FAILED) {
                info.state = DownloadInfo.STATE_WAIT;
                pendingList.add(info);
            }
        }
        if (pendingList.isEmpty()) {
            Toast.makeText(mContext, R.string.limit_blocked_short, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!Settings.getDownloadOrder()) {
            Collections.reverse(pendingList);
        }
        mWaitList.addAll(pendingList);
        EhDB.updateDownloadInfoBulkAsync(pendingList);
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
        Toast.makeText(mContext, R.string.limit_start_all_queued, Toast.LENGTH_SHORT).show();
        Log.i(TAG, "Queued " + pendingList.size() + " downloads during rate-limit cooldown");
    }

    /**
     * 分批补齐未知页数。
     *
     * <p>旧实现一次预取全部未知页数，几百个画廊会撞上 fetchPagesBatch 的 5 分钟
     * 全局超时，未轮到的任务全被标成失败，而且在预取结束前一个下载都不会开始。
     * 现在页数已知的任务立即入队，未知页数按小批次预取，每批完成立即启动该批；
     * 一旦中途再次检测到限流就停止后续批次，保持其余任务原状态供下次重试。
     */
    private void startAllDownloadWithPrefetch(
            List<DownloadInfo> unknownPages, int generation, boolean needsProgress) {
        final int concurrency = Settings.getDownloadPrefetchPagesConcurrency();
        final int chunkSize = Math.max(4, concurrency * 2);
        final Set<Long> remaining = new HashSet<>(unknownPages.size());
        for (DownloadInfo info : unknownPages) {
            remaining.add(info.gid);
        }

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            // 剩余页数排序需要真实已下载页数，先补扫页数已知的旧记录。
            if (needsProgress) {
                scanProgressForSort(collectPendingWithKnownPages());
            }
            // 先启动页数已知的任务；未知页数的任务本轮暂时跳过。
            postAndWait(() -> startAllDownloadInternal(new HashSet<>(remaining)));

            for (int start = 0; start < unknownPages.size(); start += chunkSize) {
                if (mStartAllPrefetchGeneration.get() != generation) {
                    Log.i(TAG, "startAll prefetch cancelled");
                    return;
                }
                int end = Math.min(start + chunkSize, unknownPages.size());
                List<DownloadInfo> chunk = new ArrayList<>(unknownPages.subList(start, end));
                List<Long> chunkGids = new ArrayList<>(chunk.size());
                for (DownloadInfo info : chunk) {
                    chunkGids.add(info.gid);
                }

                try {
                    GalleryPageFetcher.fetchPagesBatch(mContext, chunk, concurrency);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to prefetch pages for startAllDownload", e);
                }
                if (mStartAllPrefetchGeneration.get() != generation) {
                    Log.i(TAG, "startAll prefetch cancelled");
                    return;
                }

                if (needsProgress) {
                    List<DownloadInfo> chunkWithPages = new ArrayList<>();
                    for (DownloadInfo info : chunk) {
                        if (info.pages > 0 && info.finished <= 0) {
                            chunkWithPages.add(info);
                        }
                    }
                    scanProgressForSort(chunkWithPages);
                }

                remaining.removeAll(chunkGids);
                postAndWait(() -> {
                    if (mStartAllPrefetchGeneration.get() != generation) {
                        return;
                    }
                    Set<Long> failedGids = handleFetchedResults(chunkGids, new ArrayList<>());
                    // 尚未预取到的任务保持原状态，不要在本批被标成 WAIT。
                    Set<Long> skipGids = new HashSet<>(remaining);
                    skipGids.addAll(failedGids);
                    startAllDownloadInternal(skipGids);
                });

                if (isRemoteDownloadBlocked()) {
                    Log.w(TAG, "startAll prefetch stopped: remote limit detected");
                    return;
                }
            }
        });
    }

    /** 把动作切到主线程执行并等待完成（调用方应在后台线程）。 */
    private void postAndWait(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        SimpleHandler.getInstance().post(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                Log.w(TAG, "postAndWait action failed", t);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<DownloadInfo> collectPendingWithKnownPages() {
        List<DownloadInfo> result = new ArrayList<>();
        for (DownloadInfo info : mAllInfoList) {
            if ((info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED)
                    && info.pages > 0
                    && info.finished <= 0) {
                result.add(info);
            }
        }
        return result;
    }

    /**
     * 在 IO 线程扫描下载目录，补齐“已下载页数”，供剩余页数排序使用。
     * 只在“剩余图片数量”规则启用且持久化进度为 0 时执行，避免每次排序都扫盘。
     */
    private void scanProgressForSort(@NonNull List<DownloadInfo> items) {
        if (items.isEmpty()) {
            return;
        }
        List<DownloadInfo> scanned = new ArrayList<>(items.size());
        for (DownloadInfo info : items) {
            if (info.pages <= 0 || info.finished > 0) {
                continue;
            }
            try {
                GalleryIntegrityResult result =
                        GalleryIntegrityChecker.check(info, info.pages);
                info.finished = result.getValidPages();
                scanned.add(info);
            } catch (Throwable t) {
                Log.w(TAG, "Progress scan failed for gid=" + info.gid, t);
            }
        }
        if (!scanned.isEmpty()) {
            EhDB.updateDownloadInfoBulkAsync(scanned);
        }
    }

    private void startAllDownloadInternal() {
        startAllDownloadInternal(Collections.emptySet());
    }

    private void startAllDownloadInternal(@NonNull Set<Long> skipGids) {
        // Collect all pending items first. 状态切换先只改内存，数据库最后一次性异步批量更新，
        // 避免几千个任务逐条写库把主线程卡死（ANR）。
        List<DownloadInfo> pendingList = new ArrayList<>();
        for (DownloadInfo info : mAllInfoList) {
            if (skipGids.contains(info.gid)) continue;
            if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                info.state = DownloadInfo.STATE_WAIT;
                pendingList.add(info);
            }
        }
        if (pendingList.isEmpty()) {
            return;
        }

        boolean advancedSortEnabled = Settings.getAdvancedDownloadSortEnabled();
        boolean downloadOrder = Settings.getDownloadOrder();

        if (!advancedSortEnabled && !downloadOrder) {
            // 旧行为：倒序插到等待队列最前面（此处只做一次数组复制）。
            Collections.reverse(pendingList);
            mWaitList.addAll(0, pendingList);
        } else {
            // 正序追加；高级排序在追加后统一排序。
            if (advancedSortEnabled && !downloadOrder) {
                Collections.reverse(pendingList);
            }
            mWaitList.addAll(pendingList);
        }

        if (advancedSortEnabled) {
            applyAdvancedSort(mWaitList);
        }
        // 手动开始的画廊优先于本次“开始全部”的排序结果。
        moveManualToFront(mWaitList);

        EhDB.updateDownloadInfoBulkAsync(pendingList);

        // Notify Listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
        // Ensure download
        ensureDownload();
    }

    /**
     * 重置循环开始下载的自动重试轮数。
     * 用户手动开始新一批下载时调用，为新的会话重新分配完整额度。
     */
    public void resetLoopDownloadRetryCount() {
        mLoopDownloadRetryCount = 0;
    }

    /**
     * Check if there are any active downloads (downloading or waiting)
     */
    public boolean hasActiveDownload() {
        for (DownloadInfo info : mAllInfoList) {
            if (info.state == DownloadInfo.STATE_DOWNLOAD || info.state == DownloadInfo.STATE_WAIT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Notify that network is lost - pause active downloads
     */
    public void notifyNetworkLost() {
        Log.w(TAG, "Network lost, stopping active downloads");
        stopCurrentDownloadInternal();
    }

    /**
     * Notify that network is recovered - resume downloads
     */
    public void notifyNetworkRecovered() {
        Log.i(TAG, "Network recovered, resuming downloads");
        RemoteLimitGuard.getInstance(mContext).maybeCheckIpChanged(true);
        ensureDownload();
    }

    /**
     * Repair gallery info by re-fetching from server, fallback to cache
     */
    public boolean repairGalleryInfo(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) {
            Log.w(TAG, "repairGalleryInfo: download info not found for gid=" + gid);
            return false;
        }
        try {
            String url = EhUrl.getGalleryDetailUrl(gid, info.token);
            GalleryDetail galleryDetail = EhEngine.getGalleryDetail(null, EhApplication.getOkHttpClient(mContext), url);
            if (galleryDetail != null) {
                // 更新 DownloadInfo
                info.title = galleryDetail.title;
                info.titleJpn = galleryDetail.titleJpn;
                info.category = galleryDetail.category;
                info.thumb = galleryDetail.thumb;
                info.pages = galleryDetail.pages;
                info.rating = galleryDetail.rating;
                info.simpleLanguage = galleryDetail.simpleLanguage;
                info.simpleTags = galleryDetail.simpleTags;
                info.tgList = galleryDetail.tgList;
                info.posted = galleryDetail.posted;
                info.uploader = galleryDetail.uploader;
                EhDB.putDownloadInfo(info);
                // 保存 .ehviewer.extra.json 缓存文件
                try {
                    GalleryCacheManager cacheManager = GalleryCacheManager.getInstance(mContext);
                    cacheManager.saveGalleryCache(galleryDetail);
                } catch (Throwable cacheErr) {
                    Log.e(TAG, "repairGalleryInfo: failed to save cache for gid=" + gid, cacheErr);
                }
                Log.i(TAG, "repairGalleryInfo: success for gid=" + gid);
                // 修复顺手补齐 Gallery_Tags：让「修复全部已下载画廊」同时成为老库的标签回填手段
                GalleryTagsWriter.saveFromGalleryInfo(galleryDetail);
                return true;
            }
        } catch (Throwable e) {
            Log.e(TAG, "repairGalleryInfo: API fetch failed for gid=" + gid + ", trying cache", e);
            // API 获取失败，尝试从本地缓存恢复
            return repairFromCache(gid);
        }
        return false;
    }

    /**
     * 从 .ehviewer.extra.json 缓存修复画廊信息
     */
    private boolean repairFromCache(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) {
            Log.w(TAG, "repairFromCache: download info not found for gid=" + gid);
            return false;
        }
        try {
            GalleryCacheManager cacheManager = GalleryCacheManager.getInstance(mContext);
            GalleryDetail cachedDetail = cacheManager.readGalleryCache(gid);
            if (cachedDetail != null) {
                // 使用缓存信息更新 DownloadInfo
                if (cachedDetail.title != null) info.title = cachedDetail.title;
                if (cachedDetail.titleJpn != null) info.titleJpn = cachedDetail.titleJpn;
                if (cachedDetail.category != 0) info.category = cachedDetail.category;
                if (cachedDetail.thumb != null) info.thumb = cachedDetail.thumb;
                if (cachedDetail.pages > 0) info.pages = cachedDetail.pages;
                if (cachedDetail.rating > 0) info.rating = cachedDetail.rating;
                if (cachedDetail.simpleLanguage != null) info.simpleLanguage = cachedDetail.simpleLanguage;
                if (cachedDetail.simpleTags != null) info.simpleTags = cachedDetail.simpleTags;
                if (cachedDetail.tgList != null) info.tgList = cachedDetail.tgList;
                if (cachedDetail.posted != null) info.posted = cachedDetail.posted;
                if (cachedDetail.uploader != null) info.uploader = cachedDetail.uploader;
                // 更新数据库
                EhDB.putDownloadInfo(info);
                // 缓存里带着标签时一并补齐 Gallery_Tags（与 repairGalleryInfo 一致）
                GalleryTagsWriter.saveFromGalleryInfo(cachedDetail);
                Log.i(TAG, "repairFromCache: success for gid=" + gid);
                return true;
            } else {
                Log.w(TAG, "repairFromCache: no cache found for gid=" + gid);
            }
        } catch (Throwable e) {
            Log.e(TAG, "repairFromCache: failed for gid=" + gid, e);
        }
        return false;
    }

    /**
     * Called when app comes to foreground
     */
    public void onAppForeground() {
        Log.i(TAG, "App foreground, resuming downloads");
        ensureDownload();
        RemoteLimitGuard.getInstance(mContext).maybeCheckIpChanged();
        scheduleAutoStartOnOpenIfNeeded();
    }

    /**
     * 打开应用时自动开始未完成下载。
     *
     * <p>每个进程只调度一次；延时几秒等界面初始化完成后再拉起下载服务，
     * 效果等同于用户手动按下「开始全部」。
     */
    private void scheduleAutoStartOnOpenIfNeeded() {
        if (mAutoStartOnOpenScheduled) {
            return;
        }
        if (!Settings.getAutoStartDownloadOnOpen()) {
            return;
        }
        mAutoStartOnOpenScheduled = true;
        SimpleHandler.getInstance().postDelayed(this::autoStartOnOpen, AUTO_START_ON_OPEN_DELAY_MS);
    }

    private void autoStartOnOpen() {
        if (!Settings.getAutoStartDownloadOnOpen()) {
            return;
        }
        if (isRemoteDownloadBlocked()) {
            Log.i(TAG, "Skip auto start: remote downloads are blocked");
            return;
        }
        if (!NetworkStateManager.INSTANCE.isOnline()) {
            Log.i(TAG, "Skip auto start: offline");
            return;
        }
        // 自动换 IP 时等待内置内核和节点就绪，避免一启动就用直连导致“未知主机”。
        if (Settings.getIpSwitchEnabled()) {
            com.hippo.ehviewer.lab.ip.IpSwitchController controller =
                    com.hippo.ehviewer.lab.ip.IpSwitchController.getInstance();
            if (controller.isEnabled() && !controller.isProxyReady()) {
                if (mAutoStartCoreWaitCount++ < AUTO_START_CORE_WAIT_MAX) {
                    Log.i(TAG, "Auto start waits for IP switch core");
                    SimpleHandler.getInstance().postDelayed(this::autoStartOnOpen, 2000L);
                    return;
                }
                Log.w(TAG, "Auto start: IP switch core not ready, continue");
            }
        }
        mAutoStartCoreWaitCount = 0;
        boolean pending = false;
        for (DownloadInfo info : mAllInfoList) {
            if (info.state == DownloadInfo.STATE_NONE
                    || info.state == DownloadInfo.STATE_FAILED) {
                pending = true;
                break;
            }
        }
        if (!pending) {
            Log.i(TAG, "Skip auto start: no unfinished downloads");
            return;
        }
        try {
            Intent intent = new Intent(mContext, DownloadService.class);
            intent.setAction(DownloadService.ACTION_START_ALL);
            ContextCompat.startForegroundService(mContext, intent);
            Log.i(TAG, "Auto start downloads on app open");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to auto start downloads", t);
        }
    }

    /**
     * Called when app goes to background
     */
    public void onAppBackground() {
        Log.i(TAG, "App background");
        // 退到后台时把当前下载进度落库，减少进程被系统杀掉后的进度丢失。
        DownloadInfo current = mCurrentTask;
        if (current != null && current.finished > 0) {
            EhDB.updateDownloadInfoBulkAsync(Collections.singletonList(current));
        }
    }

    public void addDownload(List<DownloadInfo> downloadInfoList) {
        for (DownloadInfo info : downloadInfoList) {
            if (containDownloadInfo(info.gid)) {
                // Contain
                continue;
            }

            // Ensure download state
            if (DownloadInfo.STATE_WAIT == info.state ||
                    DownloadInfo.STATE_DOWNLOAD == info.state) {
                info.state = DownloadInfo.STATE_NONE;
            }

            // Add to label download list
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (null == list) {
                // Can't find the label in label list
                list = new CopyOnWriteArrayList<>();
                mMap.put(info.label, list);
                if (!containLabel(info.label)) {
                    // Add label to DB and list
                    mLabelList.add(EhDB.addDownloadLabel(info.label));
                }
            }
            list.add(info);
            // Sort
            Collections.sort(list, DATE_DESC_COMPARATOR);

            // Add to all download list and map
            mAllInfoList.add(info);
            mAllInfoMap.put(info.gid, info);

            // Save to
            EhDB.putDownloadInfo(info);

            // 标签入库（恢复/导入的 DownloadInfo 可能带着内存里的标签）
            GalleryTagsWriter.saveFromGalleryInfo(info);
        }

        // Sort all download list
        Collections.sort(mAllInfoList, DATE_DESC_COMPARATOR);

        // Notify
        new Handler(Looper.getMainLooper()).post(() -> {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onReload();
            }
        });
    }

    public void addDownloadLabel(List<DownloadLabel> downloadLabelList) {
        for (DownloadLabel label : downloadLabelList) {
            String labelString = label.getLabel();
            if (!containLabel(labelString)) {
                mMap.put(labelString, new CopyOnWriteArrayList<>());
                mLabelList.add(EhDB.addDownloadLabel(label));
            }
        }
    }

    public void addDownload(GalleryInfo galleryInfo, @Nullable String label, int state) {
        if (containDownloadInfo(galleryInfo.gid)) {
            // Contain
            return;
        }

        // It is new download info
        DownloadInfo info = new DownloadInfo(galleryInfo);
        info.label = label;
        info.state = state;
        info.time = System.currentTimeMillis();

        // Add to label download list
        List<DownloadInfo> list = getInfoListForLabel(info.label);
        // 默认标签用 null 表示，而 mLabelCountMap 是 ConcurrentHashMap（不允许 null key），
        // 这里必须跳过；否则「添加到下载」会直接抛 NPE。
        if (label != null) {
            Long oldCount = mLabelCountMap.get(label);
            mLabelCountMap.put(label, oldCount == null ? 1L : oldCount + 1L);
        }
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: " + label);
            return;
        }
        list.add(0, info);

        // Add to all download list and map
        mAllInfoList.add(0, info);
        mAllInfoMap.put(galleryInfo.gid, info);

        // Save to
        EhDB.putDownloadInfo(info);
        if (state == DownloadInfo.STATE_FINISH) {
            EhDB.recordDownloadCompleted(info, EhDB.getDownloadDirname(info.gid));
        }

        // 保存 .ehviewer.extra.json 缓存文件（仅当传入的是 GalleryDetail 时，包含完整的上传者和远程状态）
        if (galleryInfo instanceof GalleryDetail) {
            try {
                GalleryCacheManager cacheManager = GalleryCacheManager.getInstance(mContext);
                cacheManager.saveGalleryCache((GalleryDetail) galleryInfo);
            } catch (Throwable cacheErr) {
                Log.e(TAG, "addDownload: failed to save cache for gid=" + galleryInfo.gid, cacheErr);
            }
        }

        // 标签入库：排除规则 / 标签搜索 / 无标签筛选 / 标签联想只认 Gallery_Tags，
        // 而它历史上只在打开详情页时写入。列表/收藏/历史等来源的标签在这里补齐。
        GalleryTagsWriter.saveFromGalleryInfo(galleryInfo);

        // Add to wait list if state is WAIT
        if (state == DownloadInfo.STATE_WAIT) {
            // 手动发起下载（列表/详情批量添加）：清空「停止全部 → 开始全部」自动循环的重试计数
            resetLoopDownloadRetryCount();
            if (shouldPreMergeDownload(info)) {
                // 预下载合并：延迟加入下载队列，先扫描并合并已存在的重复画廊
                info.phase = DownloadInfo.PHASE_MERGE;
                info.total = 100;
                info.finished = 0;
                info.speed = -1;
                startPreMergeDownload(info);
            } else {
                mWaitList.add(info);
                if (Settings.getAdvancedDownloadSortEnabled()) {
                    applyAdvancedSort(mWaitList);
                }
                // 等待列表预碰撞检测：先拉取同名等待项的图片 token 集，再决定是否合并
                // 修复旧实现"按页数差异就直接判定递进"导致的同名不同图错合并问题
                dedupeProgressiveWaitTasks(info);
                if (containDownloadInfo(info.gid)) {
                    SimpleHandler.getInstance().post(this::ensureDownload);
                }
            }
        }

        // Notify (如果任务因递进关系去重被移除，则只刷新列表)
        if (containDownloadInfo(info.gid)) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onAdd(info, list, list.size() - 1);
            }
        } else {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdateAll();
            }
        }
    }

    public void addDownload(GalleryInfo galleryInfo, @Nullable String label) {
        addDownload(galleryInfo, label, DownloadInfo.STATE_NONE);
    }

    /**
     * 是否需要预下载合并：开关开启且非导入压缩包。
     *
     * 系统下载管理器同样允许预合并：预合并产物使用标准 %08d.ext 命名并写入 .ehviewer，
     * 与 SystemDownloadFinalizeWorker 的落盘规则一致，随后 SystemDMBackend.start 只对磁盘缺失的页入队。
     */
    private boolean shouldPreMergeDownload(DownloadInfo info) {
        if (!Settings.getMergeOnDownload()) {
            Log.i(TAG, "预下载合并跳过：'下载时合并相同画廊' 未开启 gid=" + info.gid);
            return false;
        }
        if (info.archiveUri != null) {
            Log.i(TAG, "预下载合并跳过：本地压缩包导入 gid=" + info.gid);
            return false;
        }
        return true;
    }

    /**
     * 提交预下载合并任务并轮询进度，合并完成后再真正加入下载队列。
     */
    /**
     * 提交预下载合并任务并轮询进度，合并完成后再真正加入下载队列。
     * 受信号量限制：同时最多运行 {@link #MAX_CONCURRENT_PRE_MERGE} 个预下载扫描任务，
     * 超出的任务进入 {@link #mPendingPreMerge} 等待队列，槽位释放后自动接力。
     */
    /**
     * 自动换 IP 成功后，重新提交此前因限流失败的预下载扫描任务。
     * 由 {@link com.hippo.ehviewer.task.PreDownloadScanRetryCoordinator} 调用。
     */
    public void retryPreDownloadScan(@Nullable java.util.Collection<Long> gids) {
        if (gids == null || gids.isEmpty()) {
            return;
        }
        SimpleHandler.getInstance().post(() -> {
            for (Long boxed : gids) {
                if (boxed == null) continue;
                long gid = boxed;
                com.hippo.ehviewer.task.PreDownloadScanRetryStore.INSTANCE.clear(mContext, gid);
                DownloadInfo info = mAllInfoMap.get(gid);
                if (info == null || info.archiveUri != null) {
                    continue;
                }
                if (info.state == DownloadInfo.STATE_DOWNLOAD
                        || info.state == DownloadInfo.STATE_RELAY_DOWNLOAD
                        || info.state == DownloadInfo.STATE_FINISH) {
                    continue;
                }
                Log.i(TAG, "Retrying predownload scan after IP switch gid=" + gid);
                info.state = DownloadInfo.STATE_WAIT;
                info.phase = DownloadInfo.PHASE_MERGE;
                info.total = 100;
                info.finished = 0;
                info.speed = -1;
                info.mergeDetail = mContext.getString(R.string.pre_download_merge_retry);
                EhDB.putDownloadInfo(info);
                startPreMergeDownload(info);
                List<DownloadInfo> list = getInfoListForLabel(info.label);
                if (list != null) {
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onUpdate(info, list, mWaitList);
                    }
                }
            }
        });
    }

    private void startPreMergeDownload(DownloadInfo info) {        // 记录预下载扫描阶段的活跃任务数：本批次所有扫描任务清零时用于重新安排下载队列
        synchronized (mPreMergeStateLock) {
            mActivePreMergeTasks++;
            mPreMergeBatchSize++;
            mPreMergePhaseActive = true;
        }
        synchronized (mPendingPreMerge) {
            if (mPreMergeSemaphore.tryAcquire()) {
                try {
                    doStartPreMergeDownload(info);
                } catch (Throwable t) {
                    // 提交预下载扫描任务失败：归还槽位并按正常方式进入下载队列，
                    // 同时确保活跃任务计数被递减，避免预下载阶段永不结束。
                    Log.e(TAG, "提交预下载扫描任务失败，按正常方式进入下载队列 gid=" + info.gid, t);
                    mPreMergeSemaphore.release();
                    final long gid = info.gid;
                    SimpleHandler.getInstance().post(
                            () -> finishPreMerge(gid, PreDownloadMergeTask.Outcome.PROCEED, null));
                }
            } else {
                // 无空闲扫描槽位，先进入等待队列，等前序扫描完成后由 releasePreMergeSlot 接力
                mPendingPreMerge.add(info);
                updatePreMergeWaitingState(info);
                registerPreMergeQueuedTask(info);
            }
        }
    }

    /**
     * 等待队列中的画廊在下载列表中显示“等待扫描槽位”，避免用户误以为卡死。
     */
    private void updatePreMergeWaitingState(DownloadInfo info) {
        info.phase = DownloadInfo.PHASE_MERGE;
        info.total = 100;
        info.finished = 0;
        info.speed = -1;
        info.mergeDetail = mContext.getString(R.string.pre_download_merge_waiting);
        List<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list != null) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdate(info, list, mWaitList);
            }
        }
    }

    /**
     * 为等待队列中的画廊在后台任务列表注册一个占位任务（排队中），
     * 让用户在后台任务列表也能看到它的等待状态；扫描真正开始时移除占位。
     */
    private void registerPreMergeQueuedTask(DownloadInfo info) {
        try {
            String placeholderId = PRE_MERGE_WAIT_TASK_PREFIX + info.gid;
            String taskName = mContext.getString(R.string.pre_download_merge_task_name);
            String taskDesc = mContext.getString(R.string.pre_download_merge_waiting);
            String taskId = com.hippo.ehviewer.BackgroundTaskManager.getInstance()
                    .getTaskStatusManager().addTask(
                            placeholderId, taskName, taskDesc, null,
                            BackgroundTask.TaskType.MERGE, false);
            if (taskId != null) {
                com.hippo.ehviewer.BackgroundTaskManager.getInstance()
                        .getTaskStatusManager().markTaskQueued(taskId, taskDesc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "registerPreMergeQueuedTask failed for gid=" + info.gid, t);
        }
    }

    /**
     * 移除等待画廊的占位任务（扫描真正开始时调用）。
     */
    private void unregisterPreMergeQueuedTask(long gid) {
        try {
            String placeholderId = PRE_MERGE_WAIT_TASK_PREFIX + gid;
            com.hippo.ehviewer.BackgroundTaskManager.getInstance()
                    .getTaskStatusManager().removeTask(placeholderId);
        } catch (Throwable t) {
            Log.w(TAG, "unregisterPreMergeQueuedTask failed for gid=" + gid, t);
        }
    }

    /**
     * 真正创建并提交预下载合并任务。
     */
    private void doStartPreMergeDownload(DownloadInfo info) {
        final long gid = info.gid;
        PreDownloadMergeTask task = new PreDownloadMergeTask(mContext, gid,
                new PreDownloadMergeTask.Callback() {
                    @Override
                    public void onProgress(int percent, String detail) {
                        DownloadInfo cur = mAllInfoMap.get(gid);
                        if (cur == null) {
                            return;
                        }
                        cur.phase = DownloadInfo.PHASE_MERGE;
                        cur.total = 100;
                        cur.finished = percent;
                        cur.speed = -1;
                        cur.mergeDetail = detail;
                        List<DownloadInfo> list = getInfoListForLabel(cur.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(cur, list, mWaitList);
                            }
                        }
                    }

                    @Override
                    public void onFinished(PreDownloadMergeTask.Outcome outcome,
                                           List<Long> removedSourceGids) {
                        finishPreMerge(gid, outcome, removedSourceGids);
                        // 释放扫描槽位并接力等待队列中的下一个画廊
                        releasePreMergeSlot();
                    }
                });
        mPreMergeTaskIds.put(gid, task.getTaskId());
        com.hippo.ehviewer.BackgroundTaskManager.getInstance().submitBackgroundTask(task);
    }

    /**
     * 释放一个预下载扫描槽位，并从等待队列中取出下一个画廊开始扫描。
     * 队列为空时才真正归还信号量许可。
     */
    private void releasePreMergeSlot() {
        DownloadInfo next;
        synchronized (mPendingPreMerge) {
            next = mPendingPreMerge.poll();
        }
        if (next != null) {
            if (containDownloadInfo(next.gid)) {
                unregisterPreMergeQueuedTask(next.gid);
                doStartPreMergeDownload(next);
                return;
            }
            // 等待期间该画廊已被删除，跳过并继续取下一个。
            // 该画廊没有走 finishPreMerge 收尾，需要手动递减活跃任务数，
            // 否则本批次预下载扫描队列永远不会清零、重排也不会触发。
            unregisterPreMergeQueuedTask(next.gid);
            if (onPreMergeTaskFinished()) {
                rearrangeQueueAfterPreMerge();
            }
            releasePreMergeSlot();
        } else {
            mPreMergeSemaphore.release();
        }
    }

    /**
     * 取消某个画廊的预下载合并任务。取消后该画廊按正常方式进入下载队列。
     */
    public void cancelPreMergeDownload(long gid) {
        boolean removedFromQueue = false;
        synchronized (mPendingPreMerge) {
            Iterator<DownloadInfo> it = mPendingPreMerge.iterator();
            while (it.hasNext()) {
                if (it.next().gid == gid) {
                    it.remove();
                    removedFromQueue = true;
                    break;
                }
            }
        }
        if (removedFromQueue) {
            // 未开始扫描即被取消：移除占位任务并按正常方式进入下载队列
            unregisterPreMergeQueuedTask(gid);
            finishPreMerge(gid, PreDownloadMergeTask.Outcome.PROCEED, null);
            return;
        }
        String taskId = mPreMergeTaskIds.get(gid);
        if (taskId != null) {
            com.hippo.ehviewer.BackgroundTaskManager.getInstance().cancelTask(taskId);
        }
    }

    /**
     * 预下载合并结束后的收尾：取消新下载 / 移除被合并的源画廊 / 进入下载队列。
     */
    private void finishPreMerge(long gid, PreDownloadMergeTask.Outcome outcome,
                                List<Long> removedSourceGids) {
        mPreMergeTaskIds.remove(gid);
        try {
            DownloadInfo info = mAllInfoMap.get(gid);
            if (info == null) {
                return;
            }

            if (outcome == PreDownloadMergeTask.Outcome.CANCELLED_NEWER) {
                // 已存在更新更完整的版本，取消本次下载（合并历史已由任务写入）
                removePendingAfterPreMerge(gid);
                return;
            }

            if (outcome == PreDownloadMergeTask.Outcome.FAILED_LIMIT) {
                // 预下载扫描因限流失败：标记为 FAILED，等待自动换 IP 后重试，
                // 不进入正常下载队列，避免扫描未完成就开始下载。
                info.phase = DownloadInfo.PHASE_IDLE;
                info.mergeDetail = mContext.getString(
                        R.string.pre_download_scan_limited_retry);
                info.speed = 0;
                info.state = DownloadInfo.STATE_FAILED;
                for (Iterator<DownloadInfo> it = mWaitList.iterator(); it.hasNext(); ) {
                    if (it.next().gid == gid) {
                        it.remove();
                        break;
                    }
                }
                EhDB.putDownloadInfo(info);
                List<DownloadInfo> limitedList = getInfoListForLabel(info.label);
                if (limitedList != null) {
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onUpdate(info, limitedList, mWaitList);
                    }
                }
                return;
            }

            // 移除已被并入的源画廊（其目录/DB 记录已由任务清理，历史已标注）
            if (removedSourceGids != null) {
                for (long sourceGid : removedSourceGids) {
                    removePendingAfterPreMerge(sourceGid);
                }
            }

            info.phase = DownloadInfo.PHASE_IDLE;
            info.mergeDetail = null;
            // 合并阶段的进度(100%)与真实下载页数无关，重置为待开始状态，避免进度条从虚假的 100% 掉回低值
            info.speed = 0;
            info.finished = 0;
            info.downloaded = 0;
            info.total = 0;
            info.state = DownloadInfo.STATE_WAIT;
            if (!mWaitList.contains(info)) {
                mWaitList.add(info);
                if (Settings.getAdvancedDownloadSortEnabled()) {
                    applyAdvancedSort(mWaitList);
                }
            }
            EhDB.putDownloadInfo(info);
            // 预下载合并完成后进入等待队列，再触发一次预碰撞检测：
            // 可能在合并过程中已经改变了等待列表（例如移除了同名旧版本），
            // 需要重新拉取图片 token 验证新的等待项之间是否为递进关系
            dedupeProgressiveWaitTasks(info);

            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
            ensureDownload();
        } finally {
            // 预下载扫描任务收尾：无论成功/失败/取消都递减活跃任务数。
            // 当本批次所有扫描任务清零时，走一遍「停止全部 → 开始全部」重新安排下载队列。
            if (onPreMergeTaskFinished()) {
                rearrangeQueueAfterPreMerge();
            }
        }
    }

    /**
     * 一个预下载扫描任务结束（成功/失败/取消/被删除后跳过）时调用：
     * 递减活跃任务数，并在本批次扫描任务全部清零时判定是否需要重排下载队列。
     *
     * @return true 表示本批次预下载扫描已全部结束，需要重新安排下载队列
     */
    private boolean onPreMergeTaskFinished() {
        boolean rearrangeQueue = false;
        synchronized (mPreMergeStateLock) {
            if (mActivePreMergeTasks > 0) {
                mActivePreMergeTasks--;
            }
            if (mActivePreMergeTasks == 0 && mPreMergePhaseActive) {
                mPreMergePhaseActive = false;
                // 单个画廊的扫描结束无需重排；只有批量（≥2）才需要恢复期望的队列顺序
                rearrangeQueue = mPreMergeBatchSize >= 2;
                mPreMergeBatchSize = 0;
            }
        }
        return rearrangeQueue;
    }

    /**
     * 预下载扫描阶段任务队列清零后调用：
     * 走一遍「停止全部 → 开始全部」，让下载队列按排序规则重新安排顺序。
     * 扫描期内已过扫盘的任务是按其扫描完成先后进入队列的，顺序可能与用户期望不符，
     * 全部扫盘结束后重排一次即可恢复正确的下载优先级。
     */
    private void rearrangeQueueAfterPreMerge() {
        SimpleHandler.getInstance().post(() -> {
            synchronized (mPreMergeStateLock) {
                if (mPreMergePhaseActive || mActivePreMergeTasks > 0) {
                    // 新一轮预下载扫描已经开始，跳过本次重排
                    return;
                }
            }
            try {
                // 只重排等待队列，不能再用「停止全部 → 开始全部」：
                // 那会把 WAIT 全部改成 NONE，并触发一次全量页数预取；
                // 一旦网络失败/限流，旧逻辑会把页数未知的任务直接删除。
                if (Settings.getAdvancedDownloadSortEnabled()) {
                    applyAdvancedSort(mWaitList);
                } else {
                    doSort(mWaitList);
                }
                notifyWaitListChanged();
                ensureDownload();
                Log.i(TAG, "预下载扫描队列已清零，仅重排等待队列（不中断下载、不删除任务）");
            } catch (Throwable t) {
                Log.e(TAG, "预下载扫描结束后重新安排下载队列失败", t);
            }
        });
    }

    /**
     * 预下载合并后移除一个下载项（不写普通删除历史，合并历史已由任务记录）。
     */
    private void removePendingAfterPreMerge(long gid) {
        stopDownloadInternal(gid);
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) {
            return;
        }
        EhDB.removeDownloadInfo(gid);
        mAllInfoList.remove(info);
        mAllInfoMap.remove(gid);
        List<DownloadInfo> list = getInfoListForLabel(info.label);
        int removedIndex = -1;
        if (list != null) {
            removedIndex = list.indexOf(info);
            if (removedIndex >= 0) {
                list.remove(info);
                updateLabelCount(info.label, -1);
            }
        }
        if (removedIndex >= 0) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onRemove(info, list, removedIndex);
            }
        } else {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdateAll();
            }
        }
        ensureDownload();
    }

    /**
     * 等待列表预碰撞检测入口：当「下载时合并相同画廊」开关开启时，
     * 若等待队列中存在同名（不同 gid）的任务，不直接按页数判定递进关系，
     * 而是触发异步 [WaitListProgressiveCheckTask] 先拉取图片 token 集再做对比：
     *   - cand ⊂ target（cand 是旧版本）→ 移除 cand
     *   - target ⊂ cand（target 是旧版本）→ 移除 target
     *   - token 集相等 → 视为重复，保留页数更大者
     *   - 其余情况 → 保留所有，不视为递进关系
     *
     * 修复旧实现"仅按页数差异就直接判定递进"导致的"同名不同图被错合并"事故。
     *
     * @param trigger 触发本次检查的新加入/刚完成预合并的画廊。
     *                仅用其 gid 作为新的对比 target；为 null 时跳过调度。
     */
    private void dedupeProgressiveWaitTasks(@Nullable DownloadInfo trigger) {
        if (!Settings.getMergeOnDownload()) {
            Log.i(TAG, "预检跳过：'下载时合并相同画廊' 未开启");
            return;
        }
        if (trigger == null) {
            return;
        }
        if (trigger.pages <= 0) {
            Log.i(TAG, "预检跳过：gid=" + trigger.gid + " 页数未知(pages=" + trigger.pages + ")");
            return;
        }
        // 是否存在同名（同作者）的其他画廊（等待/未开始/失败/已完成）作为候选。
        // 不再要求 mWaitList.size() >= 2：单个等待项也可能与已下载完成的画廊构成递进关系。
        boolean hasCandidate = false;
        for (DownloadInfo info : mAllInfoList) {
            if (info.gid == trigger.gid) continue;
            if (info.state == DownloadInfo.STATE_DOWNLOAD
                    || info.state == DownloadInfo.STATE_RELAY_DOWNLOAD) continue;
            if (SpiderTokenUtils.isSameCoreTitleAndAuthor(trigger, info)) {
                hasCandidate = true;
                break;
            }
        }
        if (!hasCandidate) {
            Log.i(TAG, "预检跳过：未发现同名(同作者)的其他画廊 gid=" + trigger.gid);
            return;
        }
        Log.i(TAG, "预检调度：发现同名候选，启动预碰撞检测 gid=" + trigger.gid);
        scheduleWaitListCollisionCheck(trigger);
    }

    /**
     * 提交等待列表预碰撞检测任务并轮询进度；与 [startPreMergeDownload] 复用同一套
     * 信号量与等待队列基础设施，避免预合并 / 预碰撞同时占用过多资源。
     */
    private void startWaitListCollisionCheck(DownloadInfo info) {
        synchronized (mPreMergeStateLock) {
            mActivePreMergeTasks++;
            mPreMergeBatchSize++;
            mPreMergePhaseActive = true;
        }
        synchronized (mPendingPreMerge) {
            if (mPreMergeSemaphore.tryAcquire()) {
                try {
                    doStartWaitListCollisionCheck(info);
                } catch (Throwable t) {
                    Log.e(TAG, "提交预碰撞检测任务失败，按正常方式保留等待项 gid=" + info.gid, t);
                    mPreMergeSemaphore.release();
                    final long gid = info.gid;
                    SimpleHandler.getInstance().post(() -> finishWaitListCollisionCheck(gid, null));
                }
            } else {
                // 借用同一个等待队列；标识为「预碰撞检测」避免预合并回调误判
                mPendingPreMerge.add(info);
                updatePreMergeWaitingState(info);
                registerPreMergeQueuedTask(info);
            }
        }
    }

    /**
     * 给定触发画廊 → 调度一次预碰撞检测任务。
     * 若该 gid 已有进行中的预碰撞检测任务则跳过，避免重复拉取。
     */
    private void scheduleWaitListCollisionCheck(DownloadInfo trigger) {
        synchronized (mPendingPreMerge) {
            if (mWaitListCheckTaskIds.containsKey(trigger.gid)) return;
            for (DownloadInfo queued : mPendingPreMerge) {
                if (queued.gid == trigger.gid) return;
            }
        }
        // UI 状态：进入预碰撞检测阶段（区别于 PHASE_MERGE）
        trigger.phase = DownloadInfo.PHASE_COLLISION_CHECK;
        trigger.total = 100;
        trigger.finished = 0;
        trigger.speed = -1;
        trigger.mergeDetail = mContext.getString(R.string.pre_download_collision_check_fetching,
                EhUtils.getSuitableTitle(trigger));
        List<DownloadInfo> list = getInfoListForLabel(trigger.label);
        if (list != null) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdate(trigger, list, mWaitList);
            }
        }
        startWaitListCollisionCheck(trigger);
    }

    /**
     * 真正创建并提交预碰撞检测任务。
     */
    private void doStartWaitListCollisionCheck(DownloadInfo info) {
        final long gid = info.gid;
        WaitListProgressiveCheckTask task = new WaitListProgressiveCheckTask(mContext, gid,
                new WaitListProgressiveCheckTask.Callback() {
                    @Override
                    public void onProgress(int percent, String detail) {
                        DownloadInfo cur = mAllInfoMap.get(gid);
                        if (cur == null) return;
                        cur.phase = DownloadInfo.PHASE_COLLISION_CHECK;
                        cur.total = 100;
                        cur.finished = percent;
                        cur.speed = -1;
                        cur.mergeDetail = detail;
                        List<DownloadInfo> list = getInfoListForLabel(cur.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(cur, list, mWaitList);
                            }
                        }
                    }

                    @Override
                    public void onFinished(List<Long> removedSourceGids) {
                        finishWaitListCollisionCheck(gid, removedSourceGids);
                        releasePreMergeSlot();
                    }
                });
        mWaitListCheckTaskIds.put(gid, task.getTaskId());
        com.hippo.ehviewer.BackgroundTaskManager.getInstance().submitBackgroundTask(task);
    }

    /**
     * 预碰撞检测完成后的收尾：移除被标记为旧版本的候选，复位 UI 阶段。
     */
    private void finishWaitListCollisionCheck(long gid, @Nullable List<Long> removedSourceGids) {
        mWaitListCheckTaskIds.remove(gid);
        try {
            // 先重置自身阶段，避免后续 ensureDownload 误判
            DownloadInfo info = mAllInfoMap.get(gid);
            if (info != null && info.phase == DownloadInfo.PHASE_COLLISION_CHECK) {
                info.phase = DownloadInfo.PHASE_IDLE;
                info.mergeDetail = null;
                info.speed = 0;
                info.finished = 0;
                info.downloaded = 0;
                info.total = 0;
                List<DownloadInfo> list = getInfoListForLabel(info.label);
                if (list != null) {
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onUpdate(info, list, mWaitList);
                    }
                }
            }

            // 移除被标记的旧任务；若 target 本身被标记（即 target ⊂ 某个候选），则移除 target
            if (removedSourceGids != null && !removedSourceGids.isEmpty()) {
                // 复制一份避免并发修改
                List<Long> copy = new ArrayList<>(removedSourceGids);
                for (Long removedGid : copy) {
                    DownloadInfo removedInfo = mAllInfoMap.get(removedGid);
                    if (removedInfo == null) continue;
                    long keptGid = pickKeptGidFor(removedGid, removedSourceGids);
                    removeOldProgressiveTask(removedInfo, keptGid);
                }
                // 触发一次重排
                ensureDownload();
            }
        } finally {
            if (onPreMergeTaskFinished()) {
                rearrangeQueueAfterPreMerge();
            }
        }
    }

    /**
     * 在发生递进合并移除时，从当前等待列表中找一个"保留者 gid"用于历史标记：
     * 与被移除项同名、未被标记移除的等待项中页数最大的那个；找不到则传 -1（表示被合并到外部）。
     */
    private long pickKeptGidFor(long removedGid, @Nullable List<Long> removedSourceGids) {
        DownloadInfo removedInfo = mAllInfoMap.get(removedGid);
        if (removedInfo == null) return -1L;
        DownloadInfo best = null;
        int bestPages = -1;
        for (DownloadInfo info : mWaitList) {
            if (info.gid == removedGid) continue;
            if (removedSourceGids != null && removedSourceGids.contains(info.gid)) continue;
            if (info.pages <= 0) continue;
            if (!SpiderTokenUtils.isSameCoreTitleAndAuthor(removedInfo, info)) continue;
            if (info.pages > bestPages) {
                bestPages = info.pages;
                best = info;
            }
        }
        return best != null ? best.gid : -1L;
    }

    /**
     * 取消某个画廊的预碰撞检测任务。取消后该画廊按正常方式留在等待队列。
     */
    public void cancelWaitListCollisionCheck(long gid) {
        boolean removedFromQueue = false;
        synchronized (mPendingPreMerge) {
            Iterator<DownloadInfo> it = mPendingPreMerge.iterator();
            while (it.hasNext()) {
                DownloadInfo next = it.next();
                if (next.gid == gid) {
                    it.remove();
                    removedFromQueue = true;
                    break;
                }
            }
        }
        if (removedFromQueue) {
            unregisterPreMergeQueuedTask(gid);
            finishWaitListCollisionCheck(gid, null);
            return;
        }
        String taskId = mWaitListCheckTaskIds.get(gid);
        if (taskId != null) {
            com.hippo.ehviewer.BackgroundTaskManager.getInstance().cancelTask(taskId);
        }
    }

    /**
     * 下载中重预检：先停止正在下载的任务，再重跑同名/递进检查并合并，只保留最完整的一个
     * （图片最多或同名最新）。结果由 {@link DownloadingRecheckTask} 回主线程后重新排队。
     */
    public void recheckDownloadingTasks() {
        if (mRecheckDownloadingTaskId != null) {
            Log.i(TAG, "重预检已在运行，忽略重复触发");
            return;
        }
        // 收集可重预检的任务：闲置/排队/下载中/失败/更新/接力等「非已完成」状态
        // （排除已完成 FINISH 与无效 INVALID；本地压缩包无 .ehviewer 目录，跳过）
        final List<Long> gids = new ArrayList<>();
        for (DownloadInfo info : mAllInfoList) {
            if (info.archiveUri != null) continue;
            int state = info.state;
            if (state == DownloadInfo.STATE_FINISH || state == DownloadInfo.STATE_INVALID) continue;
            gids.add(info.gid);
        }
        if (gids.isEmpty()) {
            Toast.makeText(mContext, R.string.recheck_downloading_none, Toast.LENGTH_SHORT).show();
            Log.i(TAG, "重预检跳过：没有可重预检的任务");
            return;
        }

        // 记录原始状态并停止，避免合并时与正在写入的目录冲突
        mRecheckOriginalStates.clear();
        for (long gid : gids) {
            DownloadInfo info = mAllInfoMap.get(gid);
            if (info == null) continue;
            mRecheckOriginalStates.put(gid, info.state);
            if (info.state == DownloadInfo.STATE_RELAY_DOWNLOAD) {
                cancelRelayDownload(gid);
            } else {
                stopDownloadInternal(gid);
            }
        }

        DownloadingRecheckTask task = new DownloadingRecheckTask(mContext, gids,
                new DownloadingRecheckTask.Callback() {
                    @Override
                    public void onProgress(int percent, String detail) {
                        // 进度在后台任务列表查看，这里无需额外处理
                    }

                    @Override
                    public void onFinished(DownloadingRecheckTask.RecheckResult result) {
                        finishRecheckDownloading(result);
                    }
                });
        mRecheckDownloadingTaskId = task.getTaskId();
        com.hippo.ehviewer.BackgroundTaskManager.getInstance().submitBackgroundTask(task);
    }

    /**
     * 下载中重预检完成后的收尾：移除被判定为旧版本/重复的任务，并把保留下来的任务重新入队。
     */
    private void finishRecheckDownloading(@Nullable DownloadingRecheckTask.RecheckResult result) {
        mRecheckDownloadingTaskId = null;
        if (result == null) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdateAll();
            }
            ensureDownload();
            mRecheckOriginalStates.clear();
            return;
        }

        // 移除被合并/淘汰的任务（任务侧已删除目录并记录合并历史）
        for (long removed : result.getRemovedGids()) {
            removePendingAfterPreMerge(removed);
        }

        // 保留者按原始状态恢复：原本在下载/排队 → 重新排队继续；接力仍为接力；
        // 闲置/失败/更新 → 恢复原状态，不自动排队
        for (long kept : result.getKeptGids()) {
            DownloadInfo info = mAllInfoMap.get(kept);
            if (info == null) continue;
            Integer original = mRecheckOriginalStates.get(kept);
            int originalState = original != null ? original : DownloadInfo.STATE_NONE;
            info.phase = DownloadInfo.PHASE_IDLE;
            info.mergeDetail = null;
            if (originalState == DownloadInfo.STATE_RELAY_DOWNLOAD) {
                info.state = DownloadInfo.STATE_RELAY_DOWNLOAD;
                info.speed = 0;
                info.remaining = 0;
                EhDB.putDownloadInfo(info);
                continue;
            }
            if (originalState == DownloadInfo.STATE_DOWNLOAD
                    || originalState == DownloadInfo.STATE_WAIT) {
                info.speed = 0;
                info.finished = 0;
                info.downloaded = 0;
                info.total = 0;
                info.state = DownloadInfo.STATE_WAIT;
                if (!mWaitList.contains(info)) {
                    mWaitList.add(info);
                }
            } else {
                info.speed = 0;
                info.state = originalState;
            }
            EhDB.putDownloadInfo(info);
        }
        mRecheckOriginalStates.clear();

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
        ensureDownload();
        Toast.makeText(
                mContext,
                mContext.getString(R.string.recheck_downloading_done,
                        result.getGroupsChecked(), result.getRemovedGids().size(), result.getMergedCount()),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 移除旧的递进关系下载任务，并在下载历史中标记为重复画廊。
     */
    private void removeOldProgressiveTask(DownloadInfo info, long keptGid) {
        long gid = info.gid;
        // 从等待队列移除
        for (DownloadInfo waitInfo : mWaitList) {
            if (waitInfo.gid == gid) {
                mWaitList.remove(waitInfo);
                break;
            }
        }

        // 从标签列表移除
        List<DownloadInfo> labelList = getInfoListForLabel(info.label);
        int removedIndex = -1;
        if (labelList != null) {
            removedIndex = labelList.indexOf(info);
            if (removedIndex >= 0) {
                labelList.remove(info);
                updateLabelCount(info.label, -1);
            }
        }

        // 从全部列表与映射移除
        mAllInfoList.remove(info);
        mAllInfoMap.remove(gid);

        // 从数据库移除
        EhDB.removeDownloadInfo(gid);

        // 下载历史标记为重复画廊（递进合并）
        EhDB.recordDownloadAsDuplicate(info, DownloadHistory.DELETION_PROGRESSIVE_MERGED, keptGid);

        Log.i(TAG, "递进关系去重：移除旧任务 gid=" + gid + "（保留 gid=" + keptGid + "）");

        // 通知监听器
        if (labelList != null && removedIndex >= 0) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onRemove(info, labelList, removedIndex);
            }
        }
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
    }

    public void addDownloadInfo(GalleryInfo galleryInfo, @Nullable String label) {
        if (containDownloadInfo(galleryInfo.gid)) {
            // Contain
            return;
        }

        // It is new download info
        DownloadInfo info = new DownloadInfo(galleryInfo);
        info.label = label;
        info.state = DownloadInfo.STATE_NONE;
        if (info.time == 0) {
            info.time = System.currentTimeMillis();
        }

        // Add to label download list
        List<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: " + label);
            return;
        }
        list.add(0, info);

        // Add to all download list
        mAllInfoList.add(0, info);

        // Save to
        EhDB.putDownloadInfo(info);
        mAllInfoMap.put(galleryInfo.gid, info);

        // 保存 .ehviewer.extra.json 缓存文件（仅当传入的是 GalleryDetail 时，包含完整的上传者和远程状态）
        if (galleryInfo instanceof GalleryDetail) {
            try {
                GalleryCacheManager cacheManager = GalleryCacheManager.getInstance(mContext);
                cacheManager.saveGalleryCache((GalleryDetail) galleryInfo);
            } catch (Throwable cacheErr) {
                Log.e(TAG, "addDownloadInfo: failed to save cache for gid=" + galleryInfo.gid, cacheErr);
            }
        }

        // 标签入库（同 addDownload）：让标签规则对这次新加的条目立刻可见
        GalleryTagsWriter.saveFromGalleryInfo(galleryInfo);
    }


    public void stopDownload(long gid) {
        DownloadInfo info = stopDownloadInternal(gid);
        if (info != null) {
            // Update listener
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
            // Ensure download
            ensureDownload();
        }
    }

    void stopCurrentDownload() {
        DownloadInfo info = stopCurrentDownloadInternal();
        if (info != null) {
            // Update listener
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
            // Ensure download
            ensureDownload();
        }
    }

    public void stopRangeDownload(LongList gidList) {
        stopRangeDownloadInternal(gidList);

        // Update listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }

        // Ensure download
        ensureDownload();
    }

    public void stopAllDownload() {
        // 取消仍在进行的“开始全部”页数预取，避免停止后又被后台批次重新入队。
        mStartAllPrefetchGeneration.incrementAndGet();
        if (!mLoopRestartInProgress) {
            clearManualPriority();
        }
        clearBatchMode();
        RemoteLimitGuard.getInstance(mContext).endSession("stopped");
        // Stop all in wait list
        List<DownloadInfo> changed = new ArrayList<>(mWaitList.size());
        for (DownloadInfo info : mWaitList) {
            info.state = DownloadInfo.STATE_NONE;
            changed.add(info);
        }
        mWaitList.clear();
        // 一次性异步写库，避免大量任务在主线程逐条写库造成 ANR
        EhDB.updateDownloadInfoBulkAsync(changed);

        // Stop current (may throw, but we've already cleared wait list)
        try {
            stopCurrentDownloadInternal();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping current download", e);
            // Force cleanup
            if (mCurrentTask != null) {
                mCurrentTask.state = DownloadInfo.STATE_NONE;
                EhDB.putDownloadInfo(mCurrentTask);
            }
            if (mCurrentSpider != null) {
                try {
                    mCurrentSpider.removeOnSpiderListener(DownloadManager.this);
                } catch (Exception ex) {
                    Log.w(TAG, "Failed to remove spider listener", ex);
                }
                try {
                    SpiderQueen.releaseSpiderQueen(mCurrentSpider, SpiderQueen.MODE_DOWNLOAD);
                } catch (Exception ex) {
                    Log.w(TAG, "Failed to release spider", ex);
                }
            }
            mCurrentTask = null;
            mCurrentSpider = null;
        }

        // Notify mDownloadInfoListener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
    }

    /**
     * 将画廊设为接力下载状态。
     * 如果正在下载则先停止，如果在等待队列则移除。
     * 设为接力下载后，下载管理器不再管理该画廊，直到用户主动切回。
     */
    public void setRelayDownload(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) {
            Log.w(TAG, "setRelayDownload: download info not found for gid=" + gid);
            return;
        }
        if (info.state == DownloadInfo.STATE_RELAY_DOWNLOAD) {
            // Already in relay download state
            return;
        }

        // If currently downloading, stop it first
        if (mCurrentTask != null && mCurrentTask.gid == gid) {
            stopCurrentDownloadInternal();
        }

        // If in wait list, remove it
        for (DownloadInfo waitInfo : mWaitList) {
            if (waitInfo.gid == gid) {
                mWaitList.remove(waitInfo);
                break;
            }
        }

        // Set relay download state
        info.state = DownloadInfo.STATE_RELAY_DOWNLOAD;
        info.speed = 0;
        info.remaining = 0;
        EhDB.putDownloadInfo(info);

        // Notify listeners
        List<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list != null) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdate(info, list, mWaitList);
            }
        }

        // Start next download if needed
        ensureDownload();

        Log.i(TAG, "Set relay download for gid=" + gid);
    }

    /**
     * 接力任务释放/租约过期后把画廊置为等待（state=1），但**不自动恢复下载**。
     * 协议 §5.17.6：释放/过期后主机不自动恢复本地下载，由用户或自动化策略决定。
     */
    public void setRelayWaiting(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) {
            return;
        }
        if (info.state == DownloadInfo.STATE_FINISH) {
            return;
        }
        info.state = DownloadInfo.STATE_WAIT;
        info.speed = 0;
        info.remaining = 0;
        EhDB.putDownloadInfo(info);
        notifyDownloadInfoUpdated(info);
        Log.i(TAG, "Set relay waiting for gid=" + gid);
    }

    /**
     * 取消接力下载状态，将画廊恢复为STATE_NONE。
     * 用户可通过此方法主动切回本机下载。
     */
    public void cancelRelayDownload(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) {
            Log.w(TAG, "cancelRelayDownload: download info not found for gid=" + gid);
            return;
        }
        if (info.state != DownloadInfo.STATE_RELAY_DOWNLOAD) {
            Log.w(TAG, "cancelRelayDownload: gid=" + gid + " is not in relay download state");
            return;
        }

        info.state = DownloadInfo.STATE_NONE;
        info.speed = 0;
        info.remaining = 0;
        EhDB.putDownloadInfo(info);

        // Notify listeners
        List<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list != null) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdate(info, list, mWaitList);
            }
        }

        Log.i(TAG, "Cancelled relay download for gid=" + gid);
    }

    /**
     * 检查画廊是否处于接力下载状态
     */
    public boolean isRelayDownload(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        return info != null && info.state == DownloadInfo.STATE_RELAY_DOWNLOAD;
    }

    /**
     * 标记某画廊的指定页（0-indexed）为已下载，更新 finished 计数字段，并通知监听器。
     * 供远程上传页面（POST /api/v1/galleries/{gid}/pages/{page}/upload）等场景使用。
     *
     * @param gid        画廊 GID
     * @param pageIndex  0-indexed 页码
     * @param infoIn     可选：已知的 DownloadInfo（避免重复查询）；传 null 时自动查询
     * @return 更新后的 finished 计数值，-1 表示画廊不存在
     */
    public synchronized int markPageDownloaded(long gid, int pageIndex, DownloadInfo infoIn) {
        DownloadInfo info = infoIn != null ? infoIn : mAllInfoMap.get(gid);
        if (info == null) {
            Log.w(TAG, "markPageDownloaded: download info not found for gid=" + gid);
            return -1;
        }
        if (pageIndex < 0) {
            Log.w(TAG, "markPageDownloaded: invalid pageIndex=" + pageIndex + " for gid=" + gid);
            return info.finished;
        }
        int newFinished = Math.max(info.finished, pageIndex + 1);
        if (newFinished > info.finished) {
            info.finished = newFinished;
            if (info.total <= 0 && info.pages > 0) {
                info.total = info.pages;
            }
            EhDB.putDownloadInfo(info);
        }
        notifyDownloadInfoUpdated(info);
        return info.finished;
    }

    /**
     * 触发画廊信息的更新通知（内部使用）
     */
    private void notifyDownloadInfoUpdated(DownloadInfo info) {
        if (info == null) return;
        List<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list != null) {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdate(info, list, mWaitList);
            }
        }
    }

    public void deleteDownload(long gid) {
        stopDownloadInternal(gid);
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info != null) {
            // Remove from DB
            EhDB.removeDownloadInfo(info.gid);

            // Remove all list and map
            mAllInfoList.remove(info);
            mAllInfoMap.remove(info.gid);

            // Remove label list
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                int index = list.indexOf(info);
                if (index >= 0) {
                    list.remove(info);
                    updateLabelCount(info.label, -1);
                    // Update listener
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onRemove(info, list, index);
                    }
                }
            }

            // Ensure download
            ensureDownload();
        }
    }

    public void deleteRangeDownload(LongList gidList) {
        stopRangeDownloadInternal(gidList);

        for (int i = 0, n = gidList.size(); i < n; i++) {
            long gid = gidList.get(i);
            DownloadInfo info = mAllInfoMap.get(gid);
            if (null == info) {
                Log.d(TAG, "Can't get download info with gid: " + gid);
                continue;
            }

            // Remove from DB
            EhDB.removeDownloadInfo(info.gid);

            // Remove from all info map
            mAllInfoList.remove(info);
            mAllInfoMap.remove(info.gid);

            // Remove from label list
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                list.remove(info);
            }
            updateLabelCount(info.label, -1);
        }

        // Update listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReload();
        }

        // Ensure download
        ensureDownload();
    }

    /**
     * 从内存列表中移除下载记录（不停止下载、不删除数据库记录）。
     * 用于后台删除任务：先从 UI 列表中移除让用户即时看到效果，
     * 再由后台任务按 步骤处理 数据库记录和本地文件。
     */
    public void removeFromMemoryRange(LongList gidList) {
        // 先停止正在进行的下载
        stopRangeDownloadInternal(gidList);

        for (int i = 0, n = gidList.size(); i < n; i++) {
            long gid = gidList.get(i);
            DownloadInfo info = mAllInfoMap.get(gid);
            if (null == info) {
                Log.d(TAG, "removeFromMemoryRange: can't get info with gid: " + gid);
                continue;
            }

            // Remove from all info map
            mAllInfoList.remove(info);
            mAllInfoMap.remove(info.gid);

            // Remove from label list
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                list.remove(info);
            }
            updateLabelCount(info.label, -1);
        }

        // Update listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReload();
        }

        // 继续下一个下载
        ensureDownload();
    }

    @SuppressLint("StaticFieldLeak")
    public void resetAllReadingProgress() {
        resetAllReadingProgress(null);
    }

    @SuppressLint("StaticFieldLeak")
    public void resetAllReadingProgress(@Nullable Runnable onComplete) {
        List<DownloadInfo> list = new ArrayList<>(mAllInfoList);

        // Keep an already running reader in sync with the persisted reset.
        for (DownloadInfo downloadInfo : list) {
            SpiderQueen.resetReadingProgress(downloadInfo.gid);
        }

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                GalleryInfo galleryInfo = new GalleryInfo();
                for (DownloadInfo downloadInfo : list) {
                    galleryInfo.gid = downloadInfo.gid;
                    galleryInfo.token = downloadInfo.token;
                    galleryInfo.title = downloadInfo.title;
                    galleryInfo.thumb = downloadInfo.thumb;
                    galleryInfo.category = downloadInfo.category;
                    galleryInfo.posted = downloadInfo.posted;
                    galleryInfo.uploader = downloadInfo.uploader;
                    galleryInfo.rating = downloadInfo.rating;

                    UniFile downloadDir = SpiderDen.getGalleryDownloadDir(galleryInfo);
                    if (downloadDir == null) {
                        continue;
                    }
                    UniFile file = downloadDir.findFile(".ehviewer");
                    if (file == null) {
                        continue;
                    }
                    SpiderInfo spiderInfo = SpiderInfo.read(file);
                    if (spiderInfo == null) {
                        continue;
                    }
                    spiderInfo.startPage = 0;

                    // Keep the download file and the spider-info cache in sync.
                    spiderInfo.writeNewSpiderInfoToLocal(new SpiderDen(galleryInfo), mContext);
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void unused) {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }.executeOnExecutor(IoThreadPoolExecutor.Companion.getInstance());
    }

    // Update in DB
    // Update listener
    // No ensureDownload
    private DownloadInfo stopDownloadInternal(long gid) {
        mManualPriorityGids.remove(gid);
        // Check current task
        if (mCurrentTask != null && mCurrentTask.gid == gid) {
            // Stop current
            return stopCurrentDownloadInternal();
        }

        for (DownloadInfo info : mWaitList) {
            if (info.gid == gid) {
                // Remove from wait list
                mWaitList.remove(info);
                // Update state
                info.state = DownloadInfo.STATE_NONE;
                // Update in DB
                EhDB.putDownloadInfo(info);
                return info;
            }
        }
        return null;
    }

    // Update in DB
    // Notify DownloadListeners
    private DownloadInfo stopCurrentDownloadInternal() {
        DownloadInfo info = mCurrentTask;
        SpiderQueen spider = mCurrentSpider;
        RemoteLimitGuard.getInstance(mContext).endSession("stopped");
        mCurrentTask = null;
        mCurrentSpider = null;
        // Stop speed reminder
        mSpeedReminder.stop();
        // The WorkManager keep-alive worker exits on its own once there is no
        // active download, but cancel explicitly so a stopped download does not
        // keep a foreground notification around.
        if (mContext != null) {
            DownloadWorker.Companion.cancel(mContext);
        }
        // Release spider (wrap in try-catch to ensure cleanup even if spider throws)
        if (spider != null) {
            try {
                spider.removeOnSpiderListener(DownloadManager.this);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove spider listener", e);
            }
            try {
                SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD);
            } catch (Exception e) {
                Log.w(TAG, "Failed to release spider", e);
            }
        } else if (info != null) {
            // SystemDM-managed gallery：调用 SystemDMBackend 清理
            try {
                SystemDMBackend.getInstance(mContext).cleanupForGid(info.gid);
            } catch (Exception e) {
                Log.w(TAG, "SystemDMBackend.cleanupForGid failed for " + info.gid, e);
            }
        }
        if (info == null) {
            return null;
        }

        // Update state
        info.state = DownloadInfo.STATE_NONE;
        // Update in DB
        EhDB.putDownloadInfo(info);
        // Listener
        for (DownloadListener l : mDownloadListeners) {
            l.onCancel(info);
        }
        return info;
    }

    // Update in DB
    // Update DownloadListeners
    private void stopRangeDownloadInternal(LongList gidList) {
        if (gidList == null || gidList.size() == 0) {
            return;
        }
        Set<Long> gidSet = new HashSet<>(gidList.size());
        for (int i = 0, n = gidList.size(); i < n; i++) {
            gidSet.add(gidList.get(i));
        }

        // Check current task
        if (mCurrentTask != null && gidSet.contains(mCurrentTask.gid)) {
            // Stop current
            stopCurrentDownloadInternal();
        }

        // Check all in wait list
        List<DownloadInfo> toRemove = new ArrayList<>();
        for (DownloadInfo info : mWaitList) {
            if (gidSet.contains(info.gid)) {
                // Update state
                info.state = DownloadInfo.STATE_NONE;
                toRemove.add(info);
            }
        }
        if (!toRemove.isEmpty()) {
            // 一次性移除，避免 CopyOnWriteArrayList 逐条 remove 造成 O(n²) 复制
            mWaitList.removeAll(toRemove);
            // 状态变更一次性异步写库
            EhDB.updateDownloadInfoBulkAsync(toRemove);
        }
    }

    /**
     * @param label Not allow new label
     */
    public void changeLabel(List<DownloadInfo> list, String label) {
        if (null != label && !containLabel(label)) {
            Log.e(TAG, "Not exits label: " + label);
            return;
        }

        List<DownloadInfo> dstList = getInfoListForLabel(label);
        if (dstList == null) {
            Log.e(TAG, "Can't find label with label: " + label);
            return;
        }

        boolean moved = false;
        for (DownloadInfo info : list) {
            if (ObjectUtils.equal(info.label, label)) {
                continue;
            }

            List<DownloadInfo> srcList = getInfoListForLabel(info.label);
            if (srcList == null) {
                Log.e(TAG, "Can't find label with label: " + info.label);
                continue;
            }

            srcList.remove(info);
            dstList.add(info);
            updateLabelCount(info.label, -1);
            info.label = label;
            updateLabelCount(label, 1);

            // Save to DB
            EhDB.putDownloadInfo(info);
            moved = true;
        }
        if (moved) {
            Collections.sort(dstList, DATE_DESC_COMPARATOR);
        }

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReload();
        }
    }

    public void moveDownloadInfo(long gid, int deltaPosition) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) return;
        int index = mAllInfoList.indexOf(info);
        if (index < 0) return;
        int newIndex = index + deltaPosition;
        if (newIndex < 0 || newIndex >= mAllInfoList.size()) return;
        mAllInfoList.remove(index);
        mAllInfoList.add(newIndex, info);
        Collections.sort(mAllInfoList, DATE_DESC_COMPARATOR);
    }

    public void addLabel(String label) {
        if (label == null || containLabel(label)) {
            return;
        }

        mLabelList.add(EhDB.addDownloadLabel(label));
        mMap.put(label, new CopyOnWriteArrayList<>());

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateLabels();
        }
    }

    public void addLabelInSyncThread(String label) {
        if (label == null || containLabel(label)) {
            return;
        }

        mLabelList.add(EhDB.addDownloadLabel(label));
        mMap.put(label, new CopyOnWriteArrayList<>());
    }

    public void moveLabel(int fromPosition, int toPosition) {
        final DownloadLabel item = mLabelList.remove(fromPosition);
        mLabelList.add(toPosition, item);
        EhDB.moveDownloadLabel(fromPosition, toPosition);

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateLabels();
        }
    }

    public void renameLabel(@NonNull String from, @NonNull String to) {
        // Find in label list
        boolean found = false;
        for (DownloadLabel raw : mLabelList) {
            if (from.equals(raw.getLabel())) {
                found = true;
                raw.setLabel(to);
                // Update in DB
                EhDB.updateDownloadLabel(raw);
                break;
            }
        }
        if (!found) {
            return;
        }

        List<DownloadInfo> list = mMap.remove(from);
        if (list == null) {
            return;
        }

        // Update info label
        for (DownloadInfo info : list) {
            info.label = to;
            // Update in DB
            EhDB.putDownloadInfo(info);
        }
        // Put list back with new label
        mMap.put(to, list);

        // Notify listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onRenameLabel(from, to);
        }
    }

    public void deleteLabel(@NonNull String label) {
        // Find in label list and remove
        boolean found = false;
        for (Iterator<DownloadLabel> iterator = mLabelList.iterator(); iterator.hasNext(); ) {
            DownloadLabel raw = iterator.next();
            if (label.equals(raw.getLabel())) {
                found = true;
                iterator.remove();
                EhDB.removeDownloadLabel(raw);
                break;
            }
        }
        if (!found) {
            return;
        }

        List<DownloadInfo> list = mMap.remove(label);
        if (list == null) {
            return;
        }

        // Update info label
        for (DownloadInfo info : list) {
            info.label = null;
            // Update in DB
            EhDB.putDownloadInfo(info);
            mDefaultInfoList.add(info);
        }

        // Sort
        Collections.sort(mDefaultInfoList, DATE_DESC_COMPARATOR);

        // Notify listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onChange();
        }
    }

    boolean isIdle() {
        return mCurrentTask == null && mWaitList.isEmpty();
    }

    public int getDownloadingCount() {
        return mCurrentTask != null ? 1 : 0;
    }

    public int getWaitingCount() {
        return mWaitList.size();
    }

    @Override
    public void onGetPages(int pages) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnGetPagesData(pages);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onGet509(int index) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnGet509Data(index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnPageDownloadData(index, contentLength, receivedSize, bytesRead);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onPageSuccess(int index, int finished, int downloaded, int total) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnPageSuccessData(index, finished, downloaded, total);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnPageFailureDate(index, error, finished, downloaded, total);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onFinish(int finished, int downloaded, int total) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnFinishDate(finished, downloaded, total);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onGetImageSuccess(int index, Image image) {
        // Ignore
    }

    @Override
    public void onGetImageFailure(int index, String error) {
        // Ignore
    }

    private class NotifyTask implements Runnable {

        public static final int TYPE_ON_GET_PAGES = 0;
        public static final int TYPE_ON_GET_509 = 1;
        public static final int TYPE_ON_PAGE_DOWNLOAD = 2;
        public static final int TYPE_ON_PAGE_SUCCESS = 3;
        public static final int TYPE_ON_PAGE_FAILURE = 4;
        public static final int TYPE_ON_FINISH = 5;

        private int mType;
        private int mPages;
        private int mIndex;
        private long mContentLength;
        private long mReceivedSize;
        private int mBytesRead;
        @SuppressWarnings("unused")
        private String mError;
        private int mFinished;
        private int mDownloaded;
        private int mTotal;

        public void setOnGetPagesData(int pages) {
            mType = TYPE_ON_GET_PAGES;
            mPages = pages;
        }

        public void setOnGet509Data(int index) {
            mType = TYPE_ON_GET_509;
            mIndex = index;
        }

        public void setOnPageDownloadData(int index, long contentLength, long receivedSize, int bytesRead) {
            mType = TYPE_ON_PAGE_DOWNLOAD;
            mIndex = index;
            mContentLength = contentLength;
            mReceivedSize = receivedSize;
            mBytesRead = bytesRead;
        }

        public void setOnPageSuccessData(int index, int finished, int downloaded, int total) {
            mType = TYPE_ON_PAGE_SUCCESS;
            mIndex = index;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        public void setOnPageFailureDate(int index, String error, int finished, int downloaded, int total) {
            mType = TYPE_ON_PAGE_FAILURE;
            mIndex = index;
            mError = error;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        public void setOnFinishDate(int finished, int downloaded, int total) {
            mType = TYPE_ON_FINISH;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        @Override
        public void run() {
            switch (mType) {
                case TYPE_ON_GET_PAGES: {
                    DownloadInfo info = mCurrentTask;
                    if (info == null) {
                        Log.e(TAG, "Current task is null, but it should not be");
                    } else {
                        if (mPages > 0) {
                            info.pages = mPages;
                        }
                        info.total = mPages;
                        // 页数持久化，避免重启后“剩余页数”排序再次失去依据。
                        EhDB.updateDownloadInfoBulkAsync(Collections.singletonList(info));
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_GET_509: {
                    DownloadInfo info = mCurrentTask;
                    if (info != null) {
                        DownloadLogger.getInstance().logDownloadError(
                                String.valueOf(info.gid), EhUtils.getSuitableTitle(info),
                                "509 限流，页码: " + mIndex, null);
                    }
                    RemoteLimitGuard.getInstance(mContext).report509("download_509");
                    for (DownloadListener l : mDownloadListeners) {
                        l.onGet509();
                    }
                    break;
                }
                case TYPE_ON_PAGE_DOWNLOAD: {
                    RemoteLimitGuard.getInstance(mContext).onBytes(mBytesRead);
                    mSpeedReminder.onDownload(mIndex, mContentLength, mReceivedSize, mBytesRead);
                    break;
                }
                case TYPE_ON_PAGE_SUCCESS: {
                    RemoteLimitGuard.getInstance(mContext).onPageSuccess();
                    mSpeedReminder.onDone(mIndex);
                    DownloadInfo info = mCurrentTask;
                    if (info == null) {
                        Log.e(TAG, "Current task is null, but it should not be");
                    } else {
                        info.finished = mFinished;
                        info.downloaded = mDownloaded;
                        info.total = mTotal;
                        if (mTotal > 0 && info.pages <= 0) {
                            info.pages = mTotal;
                        }
                        if (info.finished > 0 && info.finished % PROGRESS_PERSIST_STEP == 0) {
                            EhDB.updateDownloadInfoBulkAsync(Collections.singletonList(info));
                        }
                        DownloadLogger.getInstance().log(
                                DownloadLogger.LogLevel.INFO,
                                TAG,
                                "内置下载页面完成 | 页码:" + mIndex + " | 已完成:" + mFinished + "/" + mTotal,
                                String.valueOf(info.gid), EhUtils.getSuitableTitle(info));
                        for (DownloadListener l : mDownloadListeners) {
                            l.onGetPage(info);
                        }
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_PAGE_FAILURE: {
                    mSpeedReminder.onDone(mIndex);
                    DownloadInfo info = mCurrentTask;
                    if (info == null) {
                        Log.e(TAG, "Current task is null, but it should not be");
                    } else {
                        info.finished = mFinished;
                        info.downloaded = mDownloaded;
                        info.total = mTotal;
                        if (isRemovedFromSourceError(mError)) {
                            info.removedFromSource = true;
                        }
                        RemoteLimitGuard.getInstance(mContext)
                                .inspectHttp(0, null, mError, null, "page_failure");
                        DownloadLogger.getInstance().logDownloadError(
                                String.valueOf(info.gid), EhUtils.getSuitableTitle(info),
                                "内置下载页面失败 | 页码:" + mIndex + " | 已完成:" + mFinished + "/" + mTotal + " | 原因:" + mError,
                                null);
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_FINISH: {
                    mSpeedReminder.onFinish();
                    // Download done
                    DownloadInfo info = mCurrentTask;
                    mCurrentTask = null;
                    SpiderQueen spider = mCurrentSpider;
                    mCurrentSpider = null;
                    // Release spider
                    if (spider != null) {
                        spider.removeOnSpiderListener(DownloadManager.this);
                        SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD);
                    }
                    // Check null
                    if (info == null || spider == null) {
                        Log.e(TAG, "Current stuff is null, but it should not be");
                        RemoteLimitGuard.getInstance(mContext).endSession("failed");
                        mSpeedReminder.stop();
                        ensureDownload();
                        break;
                    }
                    // Stop speed count
                    mSpeedReminder.stop();
                    // Update state
                    info.downloaded = mDownloaded;
                    info.total = mTotal;
                    // Verify actual file integrity before marking as complete.
                    // 与系统委托下载、手动校验任务共用同一实现，避免判定语义漂移。
                    GalleryIntegrityResult integrity = GalleryIntegrityChecker.check(info);
                    info.finished = integrity.getValidPages();
                    info.legacy = integrity.getLegacy();
                    // 内置引擎要求 .ehviewer 可用（与手动校验任务一致）；
                    // 缺元数据时置失败，后续重下会由 SpiderQueen 重新写入。
                    // 只有「已确认画廊被源站移除」时才允许按设置视为完成，
                    // 普通网络失败/限流不能把未下载完的画廊标成完成。
                    boolean complete = integrity.isComplete() && !integrity.getSpiderInfoMissing();
                    if (!complete && info.removedFromSource
                            && Settings.getDownloadTreatRemovedAsComplete()) {
                        complete = true;
                    }
                    info.state = complete
                            ? DownloadInfo.STATE_FINISH : DownloadInfo.STATE_FAILED;
                    RemoteLimitGuard.getInstance(mContext).endSession(
                            info.state == DownloadInfo.STATE_FINISH ? "completed" : "failed");
                    // Cleanup empty files if download completed successfully
                    if (info.state == DownloadInfo.STATE_FINISH) {
                        GalleryIntegrityChecker.deleteEmptyFiles(info, integrity);
                    }
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                    // 新画廊下载完成，简单重复画廊扫描缓存已失效
                    SimpleScanCache.invalidate();
                    DownloadLogger.getInstance().logDownloadComplete(
                            String.valueOf(info.gid), EhUtils.getSuitableTitle(info), 0,
                            integrity.getValidPages(), integrity.getLegacy());
                    // Update ptoken index for progressive detection (async)
                    if (info.state == DownloadInfo.STATE_FINISH) {
                        final long gid = info.gid;
                        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                            PtokenIndexUpdater.updateOnFinish(gid);
                        });
                    }
                    // Notify
                    for (DownloadListener l : mDownloadListeners) {
                        l.onFinish(info);
                    }

                    maybeAutoAnalyzeGallery(info);

                    List<DownloadInfo> list = getInfoListForLabel(info.label);
                    if (list != null) {
                        for (DownloadInfoListener l : mDownloadInfoListeners) {
                            l.onUpdate(info, list, mWaitList);
                        }
                    }
                    // Start next download
                    ensureDownload();
                    // Loop start download until complete: if enabled and idle but still have
                    // incomplete tasks, stop all and restart all, capped at a maximum number of rounds.
                    if (Settings.getLoopDownloadUntilComplete() && isIdle()) {
                        boolean hasIncomplete = false;
                        StringBuilder pending = new StringBuilder();
                        for (DownloadInfo i : mAllInfoList) {
                            if (i.state == DownloadInfo.STATE_NONE || i.state == DownloadInfo.STATE_FAILED) {
                                hasIncomplete = true;
                                if (pending.length() < 400) {
                                    pending.append(i.gid).append('(')
                                            .append(i.finished).append('/').append(i.pages)
                                            .append(") ");
                                }
                            }
                        }
                        if (hasIncomplete) {
                            if (mLoopDownloadRetryCount >= MAX_LOOP_DOWNLOAD_ROUNDS) {
                                Log.w(TAG, "Loop-download: reached max " + MAX_LOOP_DOWNLOAD_ROUNDS
                                        + " auto-retry rounds, giving up on incomplete tasks");
                                DownloadLogger logger = DownloadLogger.peek();
                                if (logger != null) {
                                    logger.log(DownloadLogger.LogLevel.WARN, "LoopDownload",
                                            "重试达到上限，放弃未完成任务: " + pending, null, null);
                                }
                            } else {
                                mLoopDownloadRetryCount++;
                                Log.i(TAG, "Loop-download enabled: incomplete tasks detected, scheduling retry round "
                                        + mLoopDownloadRetryCount + "/" + MAX_LOOP_DOWNLOAD_ROUNDS);
                                DownloadLogger logger = DownloadLogger.peek();
                                if (logger != null) {
                                    logger.log(DownloadLogger.LogLevel.INFO, "LoopDownload",
                                            "第 " + mLoopDownloadRetryCount + "/"
                                                    + MAX_LOOP_DOWNLOAD_ROUNDS + " 轮重试，未完成: "
                                                    + pending, null, null);
                                }
                                SimpleHandler.getInstance().postDelayed(() -> {
                                    if (!isIdle()) {
                                        return;
                                    }
                                    Log.i(TAG, "Loop-download: executing stopAll → startAll cycle");
                                    DownloadLogger cycleLogger = DownloadLogger.peek();
                                    if (cycleLogger != null) {
                                        cycleLogger.log(DownloadLogger.LogLevel.INFO, "LoopDownload",
                                                "执行 stopAll → startAll 重试周期", null, null);
                                    }
                                    mLoopRestartInProgress = true;
                                    try {
                                        stopAllDownload();
                                        startAllDownload();
                                    } finally {
                                        mLoopRestartInProgress = false;
                                    }
                                }, 3000);
                            }
                        }
                    }
                    break;
                }
            }

            mNotifyTaskPool.push(this);
        }
    }

    /**
     * 速度统计与进度上报。
     *
     * 速度/剩余时间的计算不依赖任何UI，跑在专用后台线程
     * ({@link #speedHandler()}) 上，避免占用主Looper：
     * 之前它每 2s 跑一次 SimpleHandler(主线程)，一旦主线程被系统限流或
     * 冻结，进度就彻底停摆。现在计算与定时调度都在后台线程，只把最终的
     * UI 回调切回主线程派发。
     */
    class SpeedReminder implements Runnable {

        private boolean mStop = true;

        private long mBytesRead;
        private long oldSpeed = -1;

        private final SparseIJArray mContentLengthMap = new SparseIJArray();
        private final SparseIJArray mReceivedSizeMap = new SparseIJArray();

        public void start() {
            if (mStop) {
                mStop = false;
                speedHandler().post(this);
            }
        }

        public void stop() {
            if (!mStop) {
                mStop = true;
                mBytesRead = 0;
                oldSpeed = -1;
                mContentLengthMap.clear();
                mReceivedSizeMap.clear();
                speedHandler().removeCallbacks(this);
            }
        }

        // Called from SpiderWorker threads while the background handler reads the
        // maps in run(); both sides must be synchronised.
        public synchronized void onDownload(int index, long contentLength, long receivedSize, int bytesRead) {
            mContentLengthMap.put(index, contentLength);
            mReceivedSizeMap.put(index, receivedSize);
            mBytesRead += bytesRead;
        }

        public synchronized void onDone(int index) {
            mContentLengthMap.delete(index);
            mReceivedSizeMap.delete(index);
        }

        public synchronized void onFinish() {
            mContentLengthMap.clear();
            mReceivedSizeMap.clear();
        }

        @Override
        public void run() {
            DownloadInfo info = mCurrentTask;
            if (info == null) {
                mBytesRead = 0;
                return;
            }
            synchronized (this) {
                long newSpeed = mBytesRead / 2;
                if (oldSpeed != -1) {
                    newSpeed = (long) MathUtils.lerp(oldSpeed, newSpeed, 0.75f);
                }
                oldSpeed = newSpeed;
                info.speed = newSpeed;
                RemoteLimitGuard.getInstance(mContext).onSpeedSample(newSpeed);

                // Calculate remaining
                if (info.total <= 0) {
                    info.remaining = -1;
                } else if (newSpeed == 0) {
                    info.remaining = 300L * 24L * 60L * 60L * 1000L; // 300 days
                } else {
                    int downloadingCount = 0;
                    long downloadingContentLengthSum = 0;
                    long totalSize = 0;
                    for (int i = 0, n = Math.max(mContentLengthMap.size(), mReceivedSizeMap.size()); i < n; i++) {
                        long contentLength = mContentLengthMap.valueAt(i);
                        long receivedSize = mReceivedSizeMap.valueAt(i);
                        downloadingCount++;
                        downloadingContentLengthSum += contentLength;
                        totalSize += contentLength - receivedSize;
                    }
                    if (downloadingCount != 0) {
                        totalSize += downloadingContentLengthSum * (info.total - info.downloaded - downloadingCount) / downloadingCount;
                        info.remaining = totalSize * 1000 / newSpeed;
                    }
                }
                // Listener callbacks touch the UI (notification + DownloadsScene), so they
                // must be dispatched on the main thread even though the speed
                // arithmetic above ran on the background handler.
                final DownloadInfo cur = info;
                SimpleHandler.getInstance().post(() -> {
                    for (DownloadListener l : mDownloadListeners) {
                        l.onDownload(cur);
                    }
                    List<DownloadInfo> list = getInfoListForLabel(cur.label);
                    if (list != null) {
                        for (DownloadInfoListener l : mDownloadInfoListeners) {
                            l.onUpdate(cur, list, mWaitList);
                        }
                    }
                });
                mBytesRead = 0;
            }

            if (!mStop) {
                speedHandler().postDelayed(this, 2000);
            }
        }
    }

    public static final Comparator<DownloadInfo> DATE_DESC_COMPARATOR = new Comparator<>() {
        @Override
        public int compare(DownloadInfo lhs, DownloadInfo rhs) {
            long dif = lhs.time - rhs.time;
            if (dif > 0) {
                return -1;
            } else if (dif < 0) {
                return 1;
            } else {
                return 0;
            }
//            return  > 0 ? -1 : 1;
        }
    };

    private void applyAdvancedSort(List<DownloadInfo> list) {
        if (list.isEmpty() || !Settings.getAdvancedDownloadSortEnabled()) return;

        // 只有“剩余/总页数”规则需要页数；页数未知时先预取，再按新引擎排序。
        if (DownloadSortEngine.needsPageCount(mContext)) {
            List<DownloadInfo> needFetch = new ArrayList<>();
            for (DownloadInfo info : list) {
                if (DownloadSortEngine.getTotalPages(info) <= 0) {
                    needFetch.add(info);
                }
            }
            if (!needFetch.isEmpty()) {
                fetchPagesAndReSort(needFetch, list);
                return;
            }
        }
        DownloadSortEngine.sort(list, mContext);
        moveManualToFront(list);
    }

    private void fetchPagesAndReSort(List<DownloadInfo> needFetch, List<DownloadInfo> fullList) {
        List<Long> fetchGids = new ArrayList<>();
        for (DownloadInfo info : needFetch) {
            fetchGids.add(info.gid);
        }

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                GalleryPageFetcher.fetchPagesBatch(mContext, needFetch,
                        Settings.getDownloadPrefetchPagesConcurrency());
            } catch (Exception e) {
                Log.w(TAG, "Failed to fetch pages for sorting", e);
            }
            SimpleHandler.getInstance().post(() -> {
                handleFetchedResults(fetchGids, fullList);
                doSort(fullList);
                notifyWaitListChanged();
            });
        });
    }

    /**
     * 处理预取页数后的状态。
     *
     * <p>关键原则：**网络失败/限流导致页数未知时绝不删除下载任务**。
     * 旧实现会调用 deleteDownload，导致用户刚添加的任务直接消失。
     * 现在只标记为失败并保留，下一次手动开始全部时再重试。
     *
     * @return 本次页数获取失败、应从当前启动批次中跳过的 gid
     */
    private Set<Long> handleFetchedResults(List<Long> fetchGids, List<DownloadInfo> fullList) {
        Set<Long> failedGids = new HashSet<>();
        List<DownloadInfo> changed = new ArrayList<>();
        for (long gid : fetchGids) {
            DownloadInfo current = mAllInfoMap.get(gid);
            if (current == null) continue;

            if (current.state == DownloadInfo.STATE_FINISH && current.pages <= 0) {
                // 只有确认被源站移除才走这里；普通已完成但 pages 丢失的记录不应被移除
                if (current.removedFromSource) {
                    Log.i(TAG, "Gallery GID=" + gid + " was deleted from remote, finishing download");
                    if (mCurrentTask != null && mCurrentTask.gid == gid) {
                        stopCurrentDownload();
                        current.state = DownloadInfo.STATE_FINISH;
                        changed.add(current);
                    }
                    fullList.remove(current);
                    mWaitList.remove(current);
                }
            } else if (current.pages <= 0 && current.total <= 0 &&
                    (current.state == DownloadInfo.STATE_NONE ||
                     current.state == DownloadInfo.STATE_WAIT ||
                     current.state == DownloadInfo.STATE_FAILED)) {
                // 页数获取失败（网络断开、429/509 限流、超时等）：保留任务，不要删除。
                Log.w(TAG, "Cannot determine page count for GID=" + gid
                        + ", keep task as FAILED for retry");
                current.state = DownloadInfo.STATE_FAILED;
                current.pages = 0;
                current.total = 0;
                current.finished = 0;
                current.downloaded = 0;
                current.speed = 0;
                current.mergeDetail = mContext.getString(
                        R.string.pre_download_page_fetch_failed_keep);
                changed.add(current);
                failedGids.add(gid);
            }
        }
        // 批量失败时一次性异步写库，避免主线程逐条写导致 ANR。
        EhDB.updateDownloadInfoBulkAsync(changed);
        return failedGids;
    }

    private static boolean isRemovedFromSourceError(@Nullable String error) {
        if (error == null) return false;
        return error.contains("404")
                || error.contains("410")
                || error.contains("Pining")
                || error.contains("pining for the fjords")
                || error.contains("Gallery Not Found")
                || error.contains("This gallery is unavailable");
    }

    private void doSort(List<DownloadInfo> list) {
        if (list.isEmpty()) return;
        DownloadSortEngine.sort(list, mContext);
        moveManualToFront(list);
    }

    private void notifyWaitListChanged() {
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
    }

    public interface DownloadInfoListener {

        /**
         * Add the special info to the special position
         */
        void onAdd(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position);

        /**
         * delete Old replace new
         */
        void onReplace(@NonNull DownloadInfo newInfo, @NonNull DownloadInfo oldInfo);

        /**
         * The special info is changed
         */
        void onUpdate(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, List<DownloadInfo> mWaitList);

        /**
         * Maybe all data is changed, but size is the same
         */
        void onUpdateAll();

        /**
         * Maybe all data is changed, maybe list is changed
         */
        void onReload();

        /**
         * The list is gone, use default list please
         */
        void onChange();

        /**
         * Rename label
         */
        void onRenameLabel(String from, String to);

        /**
         * Remove the special info from the special position
         */
        void onRemove(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position);

        void onUpdateLabels();
    }

    public interface DownloadListener {

        /**
         * Get 509 error
         */
        void onGet509();

        /**
         * Start download
         */
        void onStart(DownloadInfo info);

        /**
         * Update download speed
         */
        void onDownload(DownloadInfo info);

        /**
         * Update page downloaded
         */
        void onGetPage(DownloadInfo info);

        /**
         * Download done
         */
        void onFinish(DownloadInfo info);

        /**
         * Download done
         */
        void onCancel(DownloadInfo info);

        /**
         * Phase changed (copy/download)
         */
        default void onPhaseChanged(DownloadInfo info, int phase) {}
    }

}
