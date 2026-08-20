package com.hippo.ehviewer.task;

import android.util.Log;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.dao.PtokensIndex;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 画廊下载完成后更新 ptoken 索引，并检测超集关系
 */
public class PtokenIndexUpdater {

    private static final String TAG = "PtokenIndexUpdater";

    public interface SupersetCallback {
        void onSupersetDetected(long newGid, List<Long> subsetGids);
    }

    private static SupersetCallback sCallback;

    public static void setSupersetCallback(SupersetCallback callback) {
        sCallback = callback;
    }

    /**
     * 画廊下载完成后调用：解析 .ehviewer 文件，更新 ptoken 索引，
     * 并检测该画廊是否是其他已下载画廊的超集
     */
    public static void updateOnFinish(long gid) {
        try {
            DownloadInfo info = EhDB.getDownloadInfo(gid);
            if (info == null) {
                Log.w(TAG, "DownloadInfo not found for gid=" + gid);
                return;
            }

            UniFile dir = SpiderDen.getExistingGalleryDownloadDir(info);
            if (dir == null || !dir.exists() || !dir.isDirectory()) {
                Log.w(TAG, "Download dir not found for gid=" + gid);
                return;
            }

            EhviewerMetaParser.Result meta = EhviewerMetaParser.INSTANCE.parse(dir);
            if (meta == null || meta.getHashes().isEmpty()) {
                Log.w(TAG, "No .ehviewer metadata for gid=" + gid);
                return;
            }

            String ptokens = String.join(",", meta.getHashes());
            EhDB.putPtokensIndex(gid, ptokens, meta.getHashes().size());
            Log.i(TAG, "Updated ptoken index for gid=" + gid + ", pages=" + meta.getHashes().size());

            // 检查是否是其他画廊的超集
            checkAsSuperset(gid, meta.getHashes());
        } catch (Exception e) {
            Log.e(TAG, "Failed to update ptoken index for gid=" + gid, e);
        }
    }

    /**
     * 检查新下载的画廊是否是其他已下载画廊的超集
     * 如果是，可以通过回调通知 UI 层
     *
     * 采用分批查询，避免一次将整张 PTOKENS_INDEX 表载入内存导致 OOM。
     */
    private static void checkAsSuperset(long newGid, List<String> newHashes) {
        Set<String> newPtokenSet = new HashSet<>(newHashes);
        List<Long> subsetGids = new ArrayList<>();

        // 分批迭代索引表，控制峰值内存占用
        final int BATCH_SIZE = 500;
        long total = EhDB.getPtokensIndexCount();
        for (long offset = 0; offset < total; offset += BATCH_SIZE) {
            List<PtokensIndex> batch = EhDB.getPtokensIndexBatch((int) offset, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (PtokensIndex idx : batch) {
                if (idx.getGid() == newGid) continue;
                if (idx.getPtokens() == null || idx.getPtokens().isEmpty()) continue;

                Set<String> existingPtokens = new HashSet<>(
                        Arrays.asList(idx.getPtokens().split(",")));
                if (newPtokenSet.containsAll(existingPtokens)) {
                    subsetGids.add(idx.getGid());
                }
            }
        }

        if (!subsetGids.isEmpty() && sCallback != null) {
            Log.i(TAG, "gid=" + newGid + " is superset of " + subsetGids.size() + " galleries");
            sCallback.onSupersetDetected(newGid, subsetGids);
        }
    }

    /**
     * 为单个画廊重建索引条目（用于冷启动增量更新）
     */
    public static boolean rebuildForGallery(long gid) {
        try {
            DownloadInfo info = EhDB.getDownloadInfo(gid);
            if (info == null) return false;

            UniFile dir = SpiderDen.getExistingGalleryDownloadDir(info);
            if (dir == null || !dir.exists()) return false;

            EhviewerMetaParser.Result meta = EhviewerMetaParser.INSTANCE.parse(dir);
            if (meta == null || meta.getHashes().isEmpty()) return false;

            String ptokens = String.join(",", meta.getHashes());
            EhDB.putPtokensIndex(gid, ptokens, meta.getHashes().size());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to rebuild index for gid=" + gid, e);
            return false;
        }
    }
}
