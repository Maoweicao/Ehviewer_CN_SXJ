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
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.cache.GalleryCacheManager;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.ehviewer.task.PtokenIndexUpdater;
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
import com.hippo.lib.yorozuya.collect.SparseJLArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class DownloadManager implements SpiderQueen.OnSpiderListener {

    private static final String TAG = DownloadManager.class.getSimpleName();

    public static final String DOWNLOAD_INFO_FILENAME = ".ehviewer";
    public static final String DOWNLOAD_INFO_HEADER = "gid,token,title,title_jpn,thumb,category,posted,uploader,rating,rated,simple_lang,simple_tags,thumb_width,thumb_height,span_size,span_index,span_group_index,favorite_slot,favorite_name,pages";

    private final Context mContext;

    // All download info list
    private final LinkedList<DownloadInfo> mAllInfoList;
    // All download info map
    private final SparseJLArray<DownloadInfo> mAllInfoMap;
    // label and info list map, without default label info list
    private final Map<String, LinkedList<DownloadInfo>> mMap;

    private final Map<String, Long> mLabelCountMap;
    // All labels without default label
    private final List<DownloadLabel> mLabelList;
    // Store download info with default label
    private final LinkedList<DownloadInfo> mDefaultInfoList;
    // Store download info wait to start
    private final LinkedList<DownloadInfo> mWaitList;

    private final SpeedReminder mSpeedReminder;

    private final List<DownloadListener> mDownloadListeners;
    private final List<DownloadInfoListener> mDownloadInfoListeners;

    @Nullable
    private DownloadInfo mCurrentTask;
    @Nullable
    private SpiderQueen mCurrentSpider;

    private final ConcurrentPool<NotifyTask> mNotifyTaskPool = new ConcurrentPool<>(5);

    public DownloadManager(Context context) {
        mContext = context;

        // Get all labels
        List<DownloadLabel> labels = EhDB.getAllDownloadLabelList();
        mLabelList = labels;

        // Create list for each label
        HashMap<String, LinkedList<DownloadInfo>> map = new HashMap<>();
        mMap = map;
        for (DownloadLabel label : labels) {
            map.put(label.getLabel(), new LinkedList<>());
        }

        // Create default for non tag
        mDefaultInfoList = new LinkedList<>();

        // Get all info
        List<DownloadInfo> allInfoList = EhDB.getAllDownloadInfo();
        mAllInfoList = new LinkedList<>(allInfoList);

        // Create all info map
        SparseJLArray<DownloadInfo> allInfoMap = new SparseJLArray<>(allInfoList.size() + 10);
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
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list == null) {
                // Can't find the label in label list
                list = new LinkedList<>();
                map.put(info.label, list);
                if (!containLabel(info.label)) {
                    // Add label to DB and list
                    labels.add(EhDB.addDownloadLabel(info.label));
                }
            }
            list.add(info);
        }

        mLabelCountMap = new HashMap<>();

        for (Map.Entry<String, LinkedList<DownloadInfo>> entry : map.entrySet()) {
            mLabelCountMap.put(entry.getKey(), (long) entry.getValue().size());
        }

        mWaitList = new LinkedList<>();
        mSpeedReminder = new SpeedReminder();
        mDownloadListeners = new CopyOnWriteArrayList<>();
        mDownloadInfoListeners = new ArrayList<>();

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

    @Nullable
    private LinkedList<DownloadInfo> getInfoListForLabel(String label) {
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
        return mAllInfoMap.indexOfKey(gid) >= 0;
    }

    @NonNull
    public List<DownloadLabel> getLabelList() {
        return mLabelList;
    }

    @Nullable
    public long getLabelCount(String label) {
        try {
            if (mLabelCountMap.containsKey(label)) {
                return mLabelCountMap.get(label);
            } else {
                return 0;
            }
        } catch (NullPointerException e) {
            Analytics.recordException(e);
            return 0;
        }
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

    @NonNull
    public LinkedList<DownloadInfo> getWaitList() {
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
            for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
                DownloadInfo info = iterator.next();
                if (info.gid == gid) {
                    info.state = DownloadInfo.STATE_NONE;
                    // Remove from wait list
                    iterator.remove();
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

        // Get download from wait list
        if (!mWaitList.isEmpty()) {
            DownloadInfo info = mWaitList.removeFirst();

            // Route to SystemDMBackend if user enabled it and not origin image
            if (Settings.getUseSystemDownloadManager()
                    && !Settings.getDownloadOriginImage()) {
                int sysDmResult = tryEnsureSystemDM(info);
                if (sysDmResult == 1) {
                    return;                     // SystemDM started
                } else if (sysDmResult == -1) {
                    // Pages unknown, re-queued with async fetch; don't fall through
                    return;
                }
                // sysDmResult == 0: fall through to SpiderBackend
            }

            SpiderQueen spider = SpiderQueen.obtainSpiderQueen(mContext, info, SpiderQueen.MODE_DOWNLOAD);
            mCurrentTask = info;
            mCurrentSpider = spider;
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
    }

    /**
     * 尝试把当前任务交给 SystemDMBackend。
     *
     * @return 1 = SystemDM started; -1 = pages unknown (re-queued, async fetch pending); 0 = fallback to SpiderBackend
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
            }
        }

        // 2. 等不到 pages 就先异步拉一次，现在返回 -1 阻止 fallthrough
        if (pages <= 0) {
            info.state = DownloadInfo.STATE_WAIT;
            mWaitList.addFirst(info);
            fetchPagesAndReroute(info);
            return -1;
        }

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

        // 启动系统 DM 后端（解析 URL + enqueue）
        try {
            int enqueued = SystemDMBackend.getInstance(mContext).start(info, pages);
            if (enqueued == 0 && !SystemDMBackend.getInstance(mContext).hasTasksForGid(info.gid)) {
                mCurrentTask = null;
                info.state = DownloadInfo.STATE_NONE;
                EhDB.putDownloadInfo(info);
                Log.w(TAG, "SystemDM did not enqueue any page for gid=" + info.gid + ", falling back");
                return 0;
            }
        } catch (Throwable t) {
            Log.e(TAG, "ensureSystemDM failed for gid=" + info.gid, t);
        }

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

        return 1;
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
            // 回到主线程触发 ensureDownload
            SimpleHandler.getInstance().post(() -> {
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
     * 走与 SpiderQueen.onFinish() 等价的路径：刷 state、触发 onFinish 通知、清除 mCurrentTask、
     * 调度下一项。
     */
    public void onSystemDMGalleryFinished(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) return;
        if (mCurrentTask == null || mCurrentTask.gid != gid) return;

        info.finished = info.pages;
        info.state = DownloadInfo.STATE_FINISH;
        EhDB.putDownloadInfo(info);

        for (DownloadListener l : mDownloadListeners) {
            l.onFinish(info);
        }

        mCurrentTask = null;
        ensureDownload();
    }

    /**
     * 检查 gid 是否还有未完成的 SystemDM 任务。
     * 用于 FinalizeWorker 完成单张图后判断是否需要调用 onSystemDMGalleryFinished。
     */
    public boolean isSystemDMAllDone(long gid) {
        java.util.List<com.hippo.ehviewer.dao.SystemDownloadTask> tasks =
                EhDB.getSystemDownloadTasksForGid(gid);
        if (tasks == null || tasks.isEmpty()) return false;
        for (com.hippo.ehviewer.dao.SystemDownloadTask t : tasks) {
            String s = t.getStatus();
            if (!com.hippo.ehviewer.dao.SystemDownloadTask.STATUS_SUCCESS.equals(s)) {
                return false;
            }
        }
        return true;
    }

    /** Marks a system-delegated gallery as retryable when Android DownloadManager cannot finish it. */
    public void onSystemDMGalleryFailed(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info == null) return;
        info.state = DownloadInfo.STATE_FAILED;
        EhDB.putDownloadInfo(info);
        if (mCurrentTask != null && mCurrentTask.gid == gid) {
            mCurrentTask = null;
            ensureDownload();
        }
    }

    void startDownload(GalleryInfo galleryInfo, @Nullable String label) {
        if (mCurrentTask != null && mCurrentTask.gid == galleryInfo.gid) {
            // It is current task
            return;
        }

        // Do nothing in the case of a local compressed file.
        if (galleryInfo instanceof DownloadInfo downloadInfo) {
            if (downloadInfo.archiveUri != null && downloadInfo.archiveUri.startsWith("content://")){
                return;
            }
        }

        // Check in download list
        DownloadInfo info = mAllInfoMap.get(galleryInfo.gid);

        if (info != null) { // Get it in download list
            if (info.state != DownloadInfo.STATE_WAIT) {
                // Set state DownloadInfo.STATE_WAIT
                info.state = DownloadInfo.STATE_WAIT;
                // Add to wait list
                mWaitList.add(info);
                // Apply advanced sort if enabled
                if (Settings.getAdvancedDownloadSortEnabled()) {
                    applyAdvancedSort(mWaitList);
                }
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
            }
        } else {
            // It is new download info
            info = new DownloadInfo(galleryInfo);
            info.label = label;
            info.state = DownloadInfo.STATE_WAIT;
            info.time = System.currentTimeMillis();

            // Add to label download list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list == null) {
                Log.e(TAG, "Can't find download info list with label: " + label);
                return;
            }
            list.addFirst(info);

            // Add to all download list and map
            mAllInfoList.addFirst(info);
            mAllInfoMap.put(galleryInfo.gid, info);

            // Add to wait list
            mWaitList.add(info);
            // Apply advanced sort if enabled
            if (Settings.getAdvancedDownloadSortEnabled()) {
                applyAdvancedSort(mWaitList);
            }

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
        boolean update = false;
        boolean advancedSortEnabled = Settings.getAdvancedDownloadSortEnabled();

        if (advancedSortEnabled) {
            // Collect all selected pending items, sort them, then add to wait list
            List<DownloadInfo> pendingList = new ArrayList<>();
            for (int i = 0, n = gidList.size(); i < n; i++) {
                long gid = gidList.get(i);
                DownloadInfo info = mAllInfoMap.get(gid);
                if (null == info) {
                    Log.d(TAG, "Can't get download info with gid: " + gid);
                    continue;
                }
                if (info.state == DownloadInfo.STATE_NONE ||
                        info.state == DownloadInfo.STATE_FAILED ||
                        info.state == DownloadInfo.STATE_FINISH) {
                    update = true;
                    info.state = DownloadInfo.STATE_WAIT;
                    pendingList.add(info);
                    EhDB.putDownloadInfo(info);
                }
            }
            if (!pendingList.isEmpty()) {
                boolean downloadOrder = Settings.getDownloadOrder();
                if (downloadOrder) {
                    for (DownloadInfo info : pendingList) {
                        mWaitList.add(info);
                    }
                } else {
                    for (int i = pendingList.size() - 1; i >= 0; i--) {
                        mWaitList.add(pendingList.get(i));
                    }
                }
                applyAdvancedSort(mWaitList);
            }
        } else {
            boolean downloadOrder = Settings.getDownloadOrder();
            if (downloadOrder) {
                for (int i = 0, n = gidList.size(); i < n; i++) {
                    long gid = gidList.get(i);
                    DownloadInfo info = mAllInfoMap.get(gid);
                    if (null == info) {
                        Log.d(TAG, "Can't get download info with gid: " + gid);
                        continue;
                    }

                    if (info.state == DownloadInfo.STATE_NONE ||
                            info.state == DownloadInfo.STATE_FAILED ||
                            info.state == DownloadInfo.STATE_FINISH) {
                        update = true;
                        // Set state DownloadInfo.STATE_WAIT
                        info.state = DownloadInfo.STATE_WAIT;
                        // Add to wait list
                        mWaitList.add(info);
                        // Update in DB
                        EhDB.putDownloadInfo(info);
                    }
                }
            } else {
                for (int i = gidList.size(), n = 0; i > n; i--) {
                    long gid = gidList.get(i - 1);
                    DownloadInfo info = mAllInfoMap.get(gid);
                    if (null == info) {
                        Log.d(TAG, "Can't get download info with gid: " + gid);
                        continue;
                    }

                    if (info.state == DownloadInfo.STATE_NONE ||
                            info.state == DownloadInfo.STATE_FAILED ||
                            info.state == DownloadInfo.STATE_FINISH) {
                        update = true;
                        // Set state DownloadInfo.STATE_WAIT
                        info.state = DownloadInfo.STATE_WAIT;
                        // Add to wait list
                        mWaitList.add(info);
                        // Update in DB
                        EhDB.putDownloadInfo(info);
                    }
                }
            }
        }


        if (update) {
            // Notify Listener
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdateAll();
            }
            // Ensure download
            ensureDownload();
        }
    }

    public void startAllDownload() {
        // Check for items with unknown page count
        List<DownloadInfo> unknownPages = new ArrayList<>();
        for (DownloadInfo info : mAllInfoList) {
            if (info.pages <= 0 && info.total <= 0 &&
                    (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED)) {
                unknownPages.add(info);
            }
        }

        if (!unknownPages.isEmpty()) {
            startAllDownloadWithPrefetch(unknownPages);
            return;
        }

        startAllDownloadInternal();
    }

    private void startAllDownloadWithPrefetch(List<DownloadInfo> unknownPages) {
        List<Long> fetchGids = new ArrayList<>();
        for (DownloadInfo info : unknownPages) {
            fetchGids.add(info.gid);
        }

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                GalleryPageFetcher.fetchPagesBatch(mContext, unknownPages,
                        Settings.getDownloadPrefetchPagesConcurrency());
            } catch (Exception e) {
                Log.w(TAG, "Failed to prefetch pages for startAllDownload", e);
            }
            SimpleHandler.getInstance().post(() -> {
                handleFetchedResults(fetchGids, new ArrayList<>());
                startAllDownloadInternal();
            });
        });
    }

    private void startAllDownloadInternal() {
        boolean update = false;
        // Start all STATE_NONE and STATE_FAILED item
        LinkedList<DownloadInfo> allInfoList = mAllInfoList;
        LinkedList<DownloadInfo> waitList = mWaitList;
        boolean advancedSortEnabled = Settings.getAdvancedDownloadSortEnabled();

        if (advancedSortEnabled) {
            // Collect all pending items, sort them, then add to wait list
            List<DownloadInfo> pendingList = new ArrayList<>();
            for (DownloadInfo info : allInfoList) {
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                    update = true;
                    info.state = DownloadInfo.STATE_WAIT;
                    pendingList.add(info);
                    EhDB.putDownloadInfo(info);
                }
            }
            if (!pendingList.isEmpty()) {
                boolean downloadOrder = Settings.getDownloadOrder();
                if (downloadOrder) {
                    for (DownloadInfo info : pendingList) {
                        waitList.add(info);
                    }
                } else {
                    for (int i = pendingList.size() - 1; i >= 0; i--) {
                        waitList.add(pendingList.get(i));
                    }
                }
                applyAdvancedSort(waitList);
            }
        } else {
            boolean downloadOrder = Settings.getDownloadOrder();
            if (downloadOrder) {
                for (DownloadInfo info : allInfoList) {
                    if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                        update = true;
                        // Set state DownloadInfo.STATE_WAIT
                        info.state = DownloadInfo.STATE_WAIT;
                        // Add to wait list
                        waitList.add(info);
                        // Update in DB
                        EhDB.putDownloadInfo(info);
                    }
                }
            } else {
                for (DownloadInfo info : allInfoList) {
                    if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                        update = true;
                        // Set state DownloadInfo.STATE_WAIT
                        info.state = DownloadInfo.STATE_WAIT;
                        // Add to wait list
                        waitList.addFirst(info);
                        // Update in DB
                        EhDB.putDownloadInfo(info);
                    }
                }
            }
        }


        if (update) {
            // Notify Listener
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdateAll();
            }
            // Ensure download
            ensureDownload();
        }
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
        ensureDownload();
    }

    /**
     * Repair gallery info by re-fetching from server
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
                return true;
            }
        } catch (Throwable e) {
            Log.e(TAG, "repairGalleryInfo: failed for gid=" + gid, e);
        }
        return false;
    }

    /**
     * Called when app comes to foreground
     */
    public void onAppForeground() {
        Log.i(TAG, "App foreground, resuming downloads");
        ensureDownload();
    }

    /**
     * Called when app goes to background
     */
    public void onAppBackground() {
        Log.i(TAG, "App background");
        // No-op for now, downloads continue in background
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
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (null == list) {
                // Can't find the label in label list
                list = new LinkedList<>();
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
                mMap.put(labelString, new LinkedList<>());
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
        LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
        if (!mLabelCountMap.containsKey(label)) {
            mLabelCountMap.put(label, 1L);
        } else {
            long value = mLabelCountMap.get(label) + 1L;
            mLabelCountMap.put(label, value);
        }
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: " + label);
            return;
        }
        list.addFirst(info);

        // Add to all download list and map
        mAllInfoList.addFirst(info);
        mAllInfoMap.put(galleryInfo.gid, info);

        // Save to
        EhDB.putDownloadInfo(info);

        // 保存 .ehviewer.extra.json 缓存文件（仅当传入的是 GalleryDetail 时，包含完整的上传者和远程状态）
        if (galleryInfo instanceof GalleryDetail) {
            try {
                GalleryCacheManager cacheManager = GalleryCacheManager.getInstance(mContext);
                cacheManager.saveGalleryCache((GalleryDetail) galleryInfo);
            } catch (Throwable cacheErr) {
                Log.e(TAG, "addDownload: failed to save cache for gid=" + galleryInfo.gid, cacheErr);
            }
        }

        // Add to wait list if state is WAIT
        if (state == DownloadInfo.STATE_WAIT) {
            mWaitList.add(info);
            if (Settings.getAdvancedDownloadSortEnabled()) {
                applyAdvancedSort(mWaitList);
            }
            SimpleHandler.getInstance().post(this::ensureDownload);
        }

        // Notify
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onAdd(info, list, list.size() - 1);
        }
    }

    public void addDownload(GalleryInfo galleryInfo, @Nullable String label) {
        addDownload(galleryInfo, label, DownloadInfo.STATE_NONE);
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
        LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: " + label);
            return;
        }
        list.addFirst(info);

        // Add to all download list
        mAllInfoList.addFirst(info);

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
        // Stop all in wait list
        for (DownloadInfo info : mWaitList) {
            info.state = DownloadInfo.STATE_NONE;
            // Update in DB
            EhDB.putDownloadInfo(info);
        }
        mWaitList.clear();

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
        for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
            DownloadInfo waitInfo = iterator.next();
            if (waitInfo.gid == gid) {
                iterator.remove();
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
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
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
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
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

    @SuppressLint("StaticFieldLeak")
    public void resetAllReadingProgress() {
        LinkedList<DownloadInfo> list = new LinkedList<>(mAllInfoList);

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

                    try {
                        spiderInfo.write(file.openOutputStream());
                    } catch (IOException e) {
                        Log.e(TAG, "Can't write SpiderInfo", e);
                    }
                }
                return null;
            }
        }.executeOnExecutor(IoThreadPoolExecutor.Companion.getInstance());
    }

    // Update in DB
    // Update listener
    // No ensureDownload
    private DownloadInfo stopDownloadInternal(long gid) {
        // Check current task
        if (mCurrentTask != null && mCurrentTask.gid == gid) {
            // Stop current
            return stopCurrentDownloadInternal();
        }

        for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
            DownloadInfo info = iterator.next();
            if (info.gid == gid) {
                // Remove from wait list
                iterator.remove();
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
        mCurrentTask = null;
        mCurrentSpider = null;
        // Stop speed reminder
        mSpeedReminder.stop();
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
        // Two way
        if (gidList.size() < mWaitList.size()) {
            for (int i = 0, n = gidList.size(); i < n; i++) {
                stopDownloadInternal(gidList.get(i));
            }
        } else {
            // Check current task
            if (mCurrentTask != null && gidList.contains(mCurrentTask.gid)) {
                // Stop current
                stopCurrentDownloadInternal();
            }

            // Check all in wait list
            for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
                DownloadInfo info = iterator.next();
                if (gidList.contains(info.gid)) {
                    // Remove from wait list
                    iterator.remove();
                    // Update state
                    info.state = DownloadInfo.STATE_NONE;
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
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
        mMap.put(label, new LinkedList<>());

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateLabels();
        }
    }

    public void addLabelInSyncThread(String label) {
        if (label == null || containLabel(label)) {
            return;
        }

        mLabelList.add(EhDB.addDownloadLabel(label));
        mMap.put(label, new LinkedList<>());
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

        LinkedList<DownloadInfo> list = mMap.remove(from);
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

        LinkedList<DownloadInfo> list = mMap.remove(label);
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
                        info.total = mPages;
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
                    for (DownloadListener l : mDownloadListeners) {
                        l.onGet509();
                    }
                    break;
                }
                case TYPE_ON_PAGE_DOWNLOAD: {
                    mSpeedReminder.onDownload(mIndex, mContentLength, mReceivedSize, mBytesRead);
                    break;
                }
                case TYPE_ON_PAGE_SUCCESS: {
                    mSpeedReminder.onDone(mIndex);
                    DownloadInfo info = mCurrentTask;
                    if (info == null) {
                        Log.e(TAG, "Current task is null, but it should not be");
                    } else {
                        info.finished = mFinished;
                        info.downloaded = mDownloaded;
                        info.total = mTotal;
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
                        mSpeedReminder.stop();
                        ensureDownload();
                        break;
                    }
                    // Stop speed count
                    mSpeedReminder.stop();
                    // Update state
                    info.finished = mFinished;
                    info.downloaded = mDownloaded;
                    info.total = mTotal;
                    // Verify actual file integrity before marking as complete
                    int verifiedFinished = verifyDownloadedFiles(info);
                    info.legacy = mTotal - verifiedFinished;
                    if (info.legacy == 0) {
                        info.state = DownloadInfo.STATE_FINISH;
                    } else if (Settings.getDownloadTreatRemovedAsComplete()) {
                        info.state = DownloadInfo.STATE_FINISH;
                    } else {
                        info.state = DownloadInfo.STATE_FAILED;
                    }
                    // Cleanup empty files if download completed successfully
                    if (info.state == DownloadInfo.STATE_FINISH) {
                        cleanupEmptyFiles(info);
                    }
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                    DownloadLogger.getInstance().logDownloadComplete(
                            String.valueOf(info.gid), EhUtils.getSuitableTitle(info), 0,
                            verifiedFinished, mTotal - verifiedFinished);
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
                    List<DownloadInfo> list = getInfoListForLabel(info.label);
                    if (list != null) {
                        for (DownloadInfoListener l : mDownloadInfoListeners) {
                            l.onUpdate(info, list, mWaitList);
                        }
                    }
                    // Start next download
                    ensureDownload();
                    // Auto-retry: if enabled and idle but still have incomplete tasks, stop all and restart all
                    if (Settings.getDownloadAlwaysComplete() && isIdle()) {
                        boolean hasIncomplete = false;
                        for (DownloadInfo i : mAllInfoList) {
                            if (i.state == DownloadInfo.STATE_NONE || i.state == DownloadInfo.STATE_FAILED) {
                                hasIncomplete = true;
                                break;
                            }
                        }
                        if (hasIncomplete) {
                            Log.i(TAG, "Always-complete enabled: incomplete tasks detected, scheduling retry");
                            SimpleHandler.getInstance().postDelayed(() -> {
                                if (!isIdle()) {
                                    return;
                                }
                                Log.i(TAG, "Always-complete: executing stopAll → startAll cycle");
                                stopAllDownload();
                                startAllDownload();
                            }, 3000);
                        }
                    }
                    break;
                }
            }

            mNotifyTaskPool.push(this);
        }
    }

    /**
     * Verify downloaded files integrity by checking actual file existence and size.
     * Returns the count of valid (non-empty) files.
     */
    private int verifyDownloadedFiles(DownloadInfo info) {
        int verifiedCount = 0;
        try {
            UniFile downloadDir = SpiderDen.getExistingGalleryDownloadDir(info);
            if (downloadDir == null) {
                Log.w(TAG, "verifyDownloadedFiles: download directory not found for gid=" + info.gid);
                return 0;
            }

            SpiderInfo spiderInfo = SpiderInfo.read(downloadDir);
            if (spiderInfo == null || spiderInfo.pages <= 0) {
                Log.w(TAG, "verifyDownloadedFiles: SpiderInfo invalid for gid=" + info.gid);
                return 0;
            }

            int totalPages = spiderInfo.pages;
            for (int i = 0; i < totalPages; i++) {
                UniFile imageFile = SpiderDen.findImageFile(downloadDir, i);
                if (imageFile != null && imageFile.isFile()) {
                    long fileSize = imageFile.length();
                    if (fileSize > 0) {
                        verifiedCount++;
                    } else {
                        Log.w(TAG, "verifyDownloadedFiles: empty file at index " + i + " for gid=" + info.gid);
                    }
                } else {
                    Log.w(TAG, "verifyDownloadedFiles: missing file at index " + i + " for gid=" + info.gid);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "verifyDownloadedFiles: error verifying files for gid=" + info.gid, e);
            // Fall back to using the original finished count
            return info.finished;
        }
        return verifiedCount;
    }

    /**
     * Cleanup empty (0-byte) files from download directory.
     * Called after download completes to remove any invalid files.
     */
    private void cleanupEmptyFiles(DownloadInfo info) {
        try {
            UniFile downloadDir = SpiderDen.getExistingGalleryDownloadDir(info);
            if (downloadDir == null) {
                return;
            }

            SpiderInfo spiderInfo = SpiderInfo.read(downloadDir);
            if (spiderInfo == null || spiderInfo.pages <= 0) {
                return;
            }

            int deletedCount = 0;
            int totalPages = spiderInfo.pages;
            for (int i = 0; i < totalPages; i++) {
                UniFile imageFile = SpiderDen.findImageFile(downloadDir, i);
                if (imageFile != null && imageFile.isFile()) {
                    long fileSize = imageFile.length();
                    if (fileSize == 0) {
                        if (imageFile.delete()) {
                            deletedCount++;
                            Log.w(TAG, "cleanupEmptyFiles: deleted empty file at index " + i + " for gid=" + info.gid);
                        }
                    }
                }
            }
            if (deletedCount > 0) {
                Log.i(TAG, "cleanupEmptyFiles: deleted " + deletedCount + " empty files for gid=" + info.gid);
            }
        } catch (Exception e) {
            Log.e(TAG, "cleanupEmptyFiles: error cleaning up files for gid=" + info.gid, e);
        }
    }


    class SpeedReminder implements Runnable {

        private boolean mStop = true;

        private long mBytesRead;
        private long oldSpeed = -1;

        private final SparseIJArray mContentLengthMap = new SparseIJArray();
        private final SparseIJArray mReceivedSizeMap = new SparseIJArray();

        public void start() {
            if (mStop) {
                mStop = false;
                SimpleHandler.getInstance().post(this);
            }
        }

        public void stop() {
            if (!mStop) {
                mStop = true;
                mBytesRead = 0;
                oldSpeed = -1;
                mContentLengthMap.clear();
                mReceivedSizeMap.clear();
                SimpleHandler.getInstance().removeCallbacks(this);
            }
        }

        public void onDownload(int index, long contentLength, long receivedSize, int bytesRead) {
            mContentLengthMap.put(index, contentLength);
            mReceivedSizeMap.put(index, receivedSize);
            mBytesRead += bytesRead;
        }

        public void onDone(int index) {
            mContentLengthMap.delete(index);
            mReceivedSizeMap.delete(index);
        }

        public void onFinish() {
            mContentLengthMap.clear();
            mReceivedSizeMap.clear();
        }

        @Override
        public void run() {
            DownloadInfo info = mCurrentTask;
            if (info != null) {
                long newSpeed = mBytesRead / 2;
                if (oldSpeed != -1) {
                    newSpeed = (long) MathUtils.lerp(oldSpeed, newSpeed, 0.75f);
                }
                oldSpeed = newSpeed;
                info.speed = newSpeed;

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
                for (DownloadListener l : mDownloadListeners) {
                    l.onDownload(info);
                }
                List<DownloadInfo> list = getInfoListForLabel(info.label);
                if (list != null) {
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onUpdate(info, list, mWaitList);
                    }
                }
            }

            mBytesRead = 0;

            if (!mStop) {
                SimpleHandler.getInstance().postDelayed(this, 2000);
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

    private static final Comparator<DownloadInfo> PAGES_ASC_COMPARATOR = new Comparator<>() {
        @Override
        public int compare(DownloadInfo lhs, DownloadInfo rhs) {
            int lhsPages = lhs.pages > 0 ? lhs.pages : Integer.MAX_VALUE;
            int rhsPages = rhs.pages > 0 ? rhs.pages : Integer.MAX_VALUE;
            return Integer.compare(lhsPages, rhsPages);
        }
    };

    private static final Comparator<DownloadInfo> PAGES_DESC_COMPARATOR = new Comparator<>() {
        @Override
        public int compare(DownloadInfo lhs, DownloadInfo rhs) {
            int lhsPages = lhs.pages > 0 ? lhs.pages : Integer.MIN_VALUE;
            int rhsPages = rhs.pages > 0 ? rhs.pages : Integer.MIN_VALUE;
            return Integer.compare(rhsPages, lhsPages);
        }
    };

    private Comparator<DownloadInfo> createCompositeComparator(boolean pagesAsc) {
        int[] priorityMap = getCategoryPriorityMap();
        return (lhs, rhs) -> {
            int lhsPages = lhs.pages > 0 ? lhs.pages : (pagesAsc ? Integer.MAX_VALUE : Integer.MIN_VALUE);
            int rhsPages = rhs.pages > 0 ? rhs.pages : (pagesAsc ? Integer.MAX_VALUE : Integer.MIN_VALUE);
            int pageCmp = pagesAsc
                    ? Integer.compare(lhsPages, rhsPages)
                    : Integer.compare(rhsPages, lhsPages);
            if (pageCmp != 0) return pageCmp;
            int lhsP = priorityMap != null ? getCategoryPriority(lhs.category, priorityMap) : 0;
            int rhsP = priorityMap != null ? getCategoryPriority(rhs.category, priorityMap) : 0;
            return Integer.compare(lhsP, rhsP);
        };
    }

    private void applyAdvancedSort(List<DownloadInfo> list) {
        if (list.isEmpty()) return;

        // Step 1: Check if any items need page count fetching
        List<DownloadInfo> needFetch = new ArrayList<>();
        for (DownloadInfo info : list) {
            if (info.pages <= 0 && info.total <= 0) {
                needFetch.add(info);
            }
        }

        if (!needFetch.isEmpty() && Settings.getAdvancedDownloadSortEnabled()) {
            // Fetch pages in background, then re-sort
            fetchPagesAndReSort(needFetch, list);
            return;
        }

        int queueOrder = Settings.getDownloadQueueOrder();
        int secondary = Settings.getDownloadQueueOrderSecondary();

        if (queueOrder == Settings.DOWNLOAD_QUEUE_ORDER_DEFAULT) return;

        if (queueOrder == Settings.DOWNLOAD_QUEUE_ORDER_CATEGORY_PRIORITY) {
            if (secondary == Settings.DOWNLOAD_QUEUE_SECONDARY_FEWEST_FIRST) {
                Collections.sort(list, PAGES_ASC_COMPARATOR);
                sortByCategoryPriority(list);
            } else if (secondary == Settings.DOWNLOAD_QUEUE_SECONDARY_MOST_FIRST) {
                Collections.sort(list, PAGES_DESC_COMPARATOR);
                sortByCategoryPriority(list);
            } else {
                sortByCategoryPriority(list);
            }
            return;
        }

        if (queueOrder == Settings.DOWNLOAD_QUEUE_ORDER_FEWEST_FIRST) {
            if (secondary == Settings.DOWNLOAD_QUEUE_SECONDARY_CATEGORY_PRIORITY) {
                Collections.sort(list, createCompositeComparator(true));
            } else {
                Collections.sort(list, PAGES_ASC_COMPARATOR);
            }
            return;
        }

        if (queueOrder == Settings.DOWNLOAD_QUEUE_ORDER_MOST_FIRST) {
            if (secondary == Settings.DOWNLOAD_QUEUE_SECONDARY_CATEGORY_PRIORITY) {
                Collections.sort(list, createCompositeComparator(false));
            } else {
                Collections.sort(list, PAGES_DESC_COMPARATOR);
            }
            return;
        }
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

    private void handleFetchedResults(List<Long> fetchGids, List<DownloadInfo> fullList) {
        for (long gid : fetchGids) {
            DownloadInfo current = mAllInfoMap.get(gid);
            if (current == null) continue;

            if (current.state == DownloadInfo.STATE_FINISH && current.pages <= 0) {
                // Gallery confirmed deleted from remote (returned -2)
                Log.i(TAG, "Gallery GID=" + gid + " was deleted from remote, finishing download");
                if (mCurrentTask != null && mCurrentTask.gid == gid) {
                    stopCurrentDownload();
                    current.state = DownloadInfo.STATE_FINISH;
                    EhDB.putDownloadInfo(current);
                }
                fullList.remove(current);
                mWaitList.remove(current);
            } else if (current.pages <= 0 && current.total <= 0 &&
                    (current.state == DownloadInfo.STATE_NONE ||
                     current.state == DownloadInfo.STATE_WAIT ||
                     current.state == DownloadInfo.STATE_FAILED)) {
                // Couldn't determine page count after fetch, delete from local
                Log.w(TAG, "Deleting download GID=" + gid + " (cannot determine page count)");
                deleteDownload(gid);
            }
        }
    }

    private void doSort(List<DownloadInfo> list) {
        if (list.isEmpty()) return;

        int queueOrder = Settings.getDownloadQueueOrder();
        int secondary = Settings.getDownloadQueueOrderSecondary();

        if (queueOrder == Settings.DOWNLOAD_QUEUE_ORDER_DEFAULT) return;

        if (queueOrder == Settings.DOWNLOAD_QUEUE_ORDER_CATEGORY_PRIORITY) {
            if (secondary == Settings.DOWNLOAD_QUEUE_SECONDARY_FEWEST_FIRST) {
                Collections.sort(list, PAGES_ASC_COMPARATOR);
                sortByCategoryPriority(list);
            } else if (secondary == Settings.DOWNLOAD_QUEUE_SECONDARY_MOST_FIRST) {
                Collections.sort(list, PAGES_DESC_COMPARATOR);
                sortByCategoryPriority(list);
            } else {
                sortByCategoryPriority(list);
            }
            return;
        }

        if (queueOrder == Settings.DOWNLOAD_QUEUE_ORDER_FEWEST_FIRST) {
            if (secondary == Settings.DOWNLOAD_QUEUE_SECONDARY_CATEGORY_PRIORITY) {
                Collections.sort(list, createCompositeComparator(true));
            } else {
                Collections.sort(list, PAGES_ASC_COMPARATOR);
            }
            return;
        }

        if (queueOrder == Settings.DOWNLOAD_QUEUE_ORDER_MOST_FIRST) {
            if (secondary == Settings.DOWNLOAD_QUEUE_SECONDARY_CATEGORY_PRIORITY) {
                Collections.sort(list, createCompositeComparator(false));
            } else {
                Collections.sort(list, PAGES_DESC_COMPARATOR);
            }
            return;
        }
    }

    private void notifyWaitListChanged() {
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
    }

    private void sortByCategoryPriority(List<DownloadInfo> list) {
        int[] priorityMap = getCategoryPriorityMap();
        if (priorityMap == null || priorityMap.length == 0) return;

        Collections.sort(list, (lhs, rhs) -> {
            int lhsP = getCategoryPriority(lhs.category, priorityMap);
            int rhsP = getCategoryPriority(rhs.category, priorityMap);
            return Integer.compare(lhsP, rhsP);
        });
    }

    private int[] getCategoryPriorityMap() {
        String saved = Settings.getDownloadCategoryPriorityOrder();
        if (saved == null || saved.isEmpty()) return null;

        String[] parts = saved.split(",");
        int[] map = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                map[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return map;
    }

    private static int getCategoryPriority(int category, int[] priorityMap) {
        for (int i = 0; i < priorityMap.length; i++) {
            if (priorityMap[i] == category) return i;
        }
        return Integer.MAX_VALUE;
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
        void onUpdate(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, LinkedList<DownloadInfo> mWaitList);

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
