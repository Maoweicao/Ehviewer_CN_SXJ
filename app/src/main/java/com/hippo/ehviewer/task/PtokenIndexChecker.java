package com.hippo.ehviewer.task;

import android.util.Log;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.dao.PtokensIndex;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 添加下载时检测新画廊是否是已下载画廊的子集
 * 通过内存索引实现秒级检测
 */
public class PtokenIndexChecker {

    private static final String TAG = "PtokenIndexChecker";

    public interface SubsetCheckCallback {
        void onDetectedAsSubset(long parentGid, int overlapCount, int totalCount);
        void onNoRelation();
    }

    /** 分批查询条数，避免一次将整张 PTOKENS_INDEX 表载入内存导致 OOM */
    private static final int BATCH_SIZE = 500;

    /**
     * 检查一组 ptoken 是否是某个已下载画廊的子集
     *
     * @param newPtokens 新画廊的 ptoken 列表
     * @param callback   检测结果回调（在调用线程同步执行）
     */
    public static void checkAsSubset(List<String> newPtokens, SubsetCheckCallback callback) {
        if (newPtokens == null || newPtokens.isEmpty()) {
            callback.onNoRelation();
            return;
        }

        Set<String> newPtokenSet = new HashSet<>(newPtokens);

        long bestGid = -1;
        int bestOverlap = 0;
        boolean hasAny = false;

        // 分批迭代索引表，控制峰值内存占用
        long total = EhDB.getPtokensIndexCount();
        for (long offset = 0; offset < total; offset += BATCH_SIZE) {
            List<PtokensIndex> batch = EhDB.getPtokensIndexBatch((int) offset, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            hasAny = true;

            for (PtokensIndex idx : batch) {
                if (idx.getPtokens() == null || idx.getPtokens().isEmpty()) continue;

                Set<String> existingPtokens = new HashSet<>(
                        Arrays.asList(idx.getPtokens().split(",")));

                int overlap = 0;
                for (String pt : newPtokenSet) {
                    if (existingPtokens.contains(pt)) {
                        overlap++;
                    }
                }

                if (overlap == newPtokenSet.size()) {
                    // 完全子集
                    callback.onDetectedAsSubset(idx.getGid(), overlap, newPtokenSet.size());
                    return;
                }

                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    bestGid = idx.getGid();
                }
            }
        }

        if (!hasAny) {
            callback.onNoRelation();
            return;
        }

        // 如果重叠率超过 80%，也视为疑似递进关系
        if (bestGid > 0 && bestOverlap > newPtokenSet.size() * 0.8f) {
            callback.onDetectedAsSubset(bestGid, bestOverlap, newPtokenSet.size());
        } else {
            callback.onNoRelation();
        }
    }

    /**
     * 快速检查：只判断是否完全子集，不做模糊匹配
     *
     * @return 如果是某个已下载画廊的子集，返回该画廊的 gid；否则返回 -1
     */
    public static long findParentGallery(List<String> newPtokens) {
        if (newPtokens == null || newPtokens.isEmpty()) return -1;

        Set<String> newPtokenSet = new HashSet<>(newPtokens);

        // 分批迭代索引表，控制峰值内存占用
        long total = EhDB.getPtokensIndexCount();
        for (long offset = 0; offset < total; offset += BATCH_SIZE) {
            List<PtokensIndex> batch = EhDB.getPtokensIndexBatch((int) offset, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }

            for (PtokensIndex idx : batch) {
                if (idx.getPtokens() == null || idx.getPtokens().isEmpty()) continue;
                if (idx.getPages() < newPtokens.size()) continue; // 跳过页数更少的画廊

                Set<String> existingPtokens = new HashSet<>(
                        Arrays.asList(idx.getPtokens().split(",")));

                if (existingPtokens.containsAll(newPtokenSet)) {
                    return idx.getGid();
                }
            }
        }

        return -1;
    }
}
