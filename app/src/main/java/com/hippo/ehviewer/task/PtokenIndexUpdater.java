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

    /**
     * 每批载入的索引条数。
     *
     * <p>单行的 PTOKENS 是逗号分隔的整本画廊 hash（上千页时可达几十 KB），
     * 旧实现的 500 条一批会在扫描时一次性构造出几十万个 String 和一个巨大的
     * HashSet，堆紧张时直接 OOM（crash-2026-10-31 15:05 / 2026-08-06 13:13）。
     * 这里把批次压到很小的值，并在每批结束后立即丢弃引用。
     */
    private static final int BATCH_SIZE = 16;

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
        } catch (OutOfMemoryError oom) {
            // 索引只是递进检测的加速缓存，缺一条不影响下载正确性；
            // 扫描过程中堆紧张时放弃本次索引更新，而不是让整个进程崩溃。
            Log.e(TAG, "OOM while updating ptoken index for gid=" + gid + ", skipped", oom);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update ptoken index for gid=" + gid, e);
        }
    }

    /**
     * 检查新下载的画廊是否是其他已下载画廊的超集
     * 如果是，可以通过回调通知 UI 层
     *
     * 采用按键集分批查询，避免一次将整张 PTOKENS_INDEX 表载入内存导致 OOM。
     */
    private static void checkAsSuperset(long newGid, List<String> newHashes) {
        Set<String> newPtokenSet = new HashSet<>(newHashes);
        List<Long> subsetGids = new ArrayList<>();

        long lastGid = Long.MIN_VALUE;
        while (true) {
            List<PtokensIndex> batch = EhDB.getPtokensIndexBatchAfter(lastGid, newGid, BATCH_SIZE);
            if (batch == null || batch.isEmpty()) {
                break;
            }

            for (PtokensIndex idx : batch) {
                lastGid = idx.getGid();
                if (idx.getGid() == newGid) {
                    continue;
                }
                String ptokens = idx.getPtokens();
                if (ptokens == null || ptokens.isEmpty()) {
                    continue;
                }

                Set<String> existingPtokens = new HashSet<>(Arrays.asList(ptokens.split(",")));
                if (newPtokenSet.containsAll(existingPtokens)) {
                    subsetGids.add(idx.getGid());
                }
            }

            // 本批未读满说明已经到表尾
            if (batch.size() < BATCH_SIZE) {
                break;
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
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "OOM while rebuilding index for gid=" + gid + ", skipped", oom);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to rebuild index for gid=" + gid, e);
            return false;
        }
    }
}
