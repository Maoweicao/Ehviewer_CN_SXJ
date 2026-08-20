package com.hippo.ehviewer.task;

import android.util.Log;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 冷启动时重建 ptoken 索引
 * 检查索引表是否为空，如果为空则从所有已下载画廊的 .ehviewer 文件重建
 */
public class PtokenIndexRebuilder {

    private static final String TAG = "PtokenIndexRebuilder";
    private static final AtomicBoolean sRebuilding = new AtomicBoolean(false);

    /**
     * 如果 ptoken 索引为空，从所有已下载画廊重建
     * 应在后台线程调用
     */
    public static void rebuildIfNeeded() {
        if (!sRebuilding.compareAndSet(false, true)) {
            Log.w(TAG, "Rebuild already in progress, skipping");
            return;
        }

        try {
            if (EhDB.getPtokensIndexCount() > 0) {
                Log.i(TAG, "Ptoken index already populated, skipping rebuild");
                return;
            }

            List<DownloadInfo> allDownloads = EhDB.getAllDownloadInfo();
            if (allDownloads == null || allDownloads.isEmpty()) {
                Log.i(TAG, "No downloads found, skipping rebuild");
                return;
            }

            Log.i(TAG, "Starting ptoken index rebuild for " + allDownloads.size() + " galleries");
            long startTime = System.currentTimeMillis();

            int success = 0;
            int failed = 0;
            int skipped = 0;

            for (DownloadInfo info : allDownloads) {
                if (info.state != DownloadInfo.STATE_FINISH) {
                    skipped++;
                    continue;
                }

                boolean ok = PtokenIndexUpdater.rebuildForGallery(info.gid);
                if (ok) {
                    success++;
                } else {
                    failed++;
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            Log.i(TAG, "Ptoken index rebuild complete: " + success + " success, " +
                    failed + " failed, " + skipped + " skipped, took " + elapsed + "ms");
        } catch (Exception e) {
            Log.e(TAG, "Ptoken index rebuild failed", e);
        } finally {
            sRebuilding.set(false);
        }
    }

    /**
     * 强制全量重建索引（清除旧数据）
     */
    public static void forceRebuildAll() {
        if (!sRebuilding.compareAndSet(false, true)) {
            Log.w(TAG, "Rebuild already in progress, skipping");
            return;
        }

        try {
            EhDB.clearPtokensIndex();
            List<DownloadInfo> allDownloads = EhDB.getAllDownloadInfo();
            if (allDownloads == null) return;

            Log.i(TAG, "Force rebuilding ptoken index for " + allDownloads.size() + " galleries");
            int success = 0;
            for (DownloadInfo info : allDownloads) {
                if (info.state == DownloadInfo.STATE_FINISH) {
                    if (PtokenIndexUpdater.rebuildForGallery(info.gid)) {
                        success++;
                    }
                }
            }
            Log.i(TAG, "Force rebuild complete: " + success + " entries");
        } catch (Exception e) {
            Log.e(TAG, "Force rebuild failed", e);
        } finally {
            sRebuilding.set(false);
        }
    }

    public static boolean isRebuilding() {
        return sRebuilding.get();
    }
}
