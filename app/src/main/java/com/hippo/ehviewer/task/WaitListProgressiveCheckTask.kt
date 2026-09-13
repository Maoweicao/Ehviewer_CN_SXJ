package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.lib.yorozuya.SimpleHandler
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 等待列表预碰撞检测任务。
 *
 * 当下载列表中同时存在两个「名字相同、页码不同、gid 不同」的等待项时，
 * 旧实现 [com.hippo.ehviewer.download.DownloadManager.dedupeProgressiveWaitTasks]
 * 仅按 (uploader, title) + 页数差异就直接判定为递进关系并移除较小者，
 * 出现过"同名不同图被错合并"的事故。
 *
 * 本任务先为每个候选画廊拉取真实的图片 token（pToken）集合，再比对 token
 * 集合的子集/超集/相等关系：
 *   - cand ⊂ target    → cand 是旧版本，标记为递进合并（保留 target）
 *   - target ⊂ cand    → target 是旧版本，标记为递进合并（保留 cand）
 *   - token 集合相等    → 视为重复，保留页数更大的那个
 *   - 不构成上述关系    → 不动，不视为递进关系
 *
 * 任一画廊拉取失败 → 保守地保留原样，宁可漏判不错判。
 */
class WaitListProgressiveCheckTask(
    context: Context,
    private val targetGid: Long,
    private val callback: Callback?
) : BaseBackgroundTask(context) {

    interface Callback {
        fun onProgress(percent: Int, detail: String?)
        /**
         * 检查结束，返回需要被标记为递进合并移除的 gid 列表。
         * 注意：targetGid 本身也可能出现在该列表中（target ⊂ 候选 的情况）。
         */
        fun onFinished(removedSourceGids: List<Long>)
    }

    private val removed = mutableListOf<Long>()
    @Volatile
    private var finishPosted = false

    override fun getTaskId(): String = "wait_list_progressive_check_${targetGid}_${System.currentTimeMillis()}"

    override fun getTaskName(): String = context.getString(R.string.pre_download_collision_check_task_name)

    override fun getTaskDescription(): String = context.getString(R.string.pre_download_collision_check_task_desc)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.MERGE

    override fun isUniqueTask(): Boolean = false

    override fun isPausable(): Boolean = true

    override fun isPersistable(): Boolean = false

    override suspend fun execute(): Result<Unit> {
        return try {
            runCheck()
            notifyCompleted()
            Result.success(Unit)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled()
            } else {
                Log.w(TAG, "Pre-collision check failed", e)
                notifyError(e)
            }
            Result.failure(e)
        } finally {
            finish()
        }
    }

    private suspend fun runCheck() {
        val target = EhDB.getDownloadInfo(targetGid)
        if (target == null) {
            logAndProgress(100, "未找到目标画廊 gid=$targetGid，跳过预碰撞检测")
            return
        }
        val title = com.hippo.ehviewer.client.EhUtils.getSuitableTitle(target) ?: ""
        logAndProgress(0, "开始预碰撞检测: ${target.gid} - $title")

        // 1. 收集等待/未开始候选：数据库中所有 STATE_WAIT/STATE_NONE/STATE_FINISH 同名候选。
        //    注意：已完成的画廊本就该走 PreDownloadMergeTask 处理，这里也允许比较，
        //    用于判断等待项是否为已完成画廊的旧版本（target ⊂ cand）。
        val candidates = collectCandidates(target)
        if (candidates.isEmpty()) {
            logAndProgress(100, "等待列表中无同名画廊，无需预碰撞")
            return
        }
        logAndProgress(
            5,
            "发现 ${candidates.size} 个等待列表候选画廊（gid 列表: ${candidates.joinToString { it.gid.toString() }}）"
        )

        // 2. 拉取 target 的 token 集；拉取失败则放弃整个检查（保守策略）
        val targetSet = fetchHashSetResilient(target)
        if (targetSet == null) {
            logAndProgress(100, "目标画廊 token 拉取失败，跳过本次预碰撞检测（保守保留所有候选）")
            return
        }
        if (targetSet.isEmpty()) {
            logAndProgress(100, "目标画廊 token 为空，跳过本次预碰撞检测")
            return
        }
        logAndProgress(20, "目标画廊 token 集大小 = ${targetSet.size}")

        // 3. 逐个候选拉取并比对
        val total = candidates.size
        candidates.forEachIndexed { index, candidate ->
            ensureNotCancelled()
            val candSet = fetchHashSetResilient(candidate)
            if (candSet == null) {
                logAndProgress(
                    20 + (index + 1) * 70 / maxOf(total, 1),
                    "候选 ${candidate.gid} token 拉取失败，跳过该候选"
                )
                return@forEachIndexed
            }
            if (candSet.isEmpty()) {
                logAndProgress(
                    20 + (index + 1) * 70 / maxOf(total, 1),
                    "候选 ${candidate.gid} token 为空，跳过该候选"
                )
                return@forEachIndexed
            }
            val decision = SpiderTokenUtils.decideProgressiveRelation(
                targetSet, target.pages,
                candSet, candidate.pages
            )
            if (decision != SpiderTokenUtils.ProgressiveDecision.NONE) {
                val removedGid = when (decision) {
                    SpiderTokenUtils.ProgressiveDecision.REMOVE_CANDIDATE,
                    SpiderTokenUtils.ProgressiveDecision.REMOVE_EQUAL_CANDIDATE -> candidate.gid
                    else -> targetGid
                }
                if (!removed.contains(removedGid)) {
                    removed.add(removedGid)
                }
                logAndProgress(
                    20 + (index + 1) * 70 / maxOf(total, 1),
                    "候选 ${candidate.gid}：$decision，标记 ${removedGid} 为递进合并"
                )
            } else {
                logAndProgress(
                    20 + (index + 1) * 70 / maxOf(total, 1),
                    "候选 ${candidate.gid}：token 集与目标无递进关系，保留"
                )
            }
        }

        logAndProgress(
            95,
            "预碰撞检测完成：标记 ${removed.size} 个旧版本（${removed.joinToString { it.toString() }}）"
        )
    }

    private fun collectCandidates(target: DownloadInfo): List<DownloadInfo> {
        val all = EhDB.getAllDownloadInfo() ?: return emptyList()
        val result = mutableListOf<DownloadInfo>()
        for (info in all) {
            // 跳过自身
            if (info.gid == target.gid) continue
            // 仅考虑尚未实质下载的等待项，避免与 PreDownloadMergeTask 重复或冲突
            when (info.state) {
                DownloadInfo.STATE_WAIT,
                DownloadInfo.STATE_NONE,
                DownloadInfo.STATE_FAILED -> Unit
                else -> continue
            }
            // 排除正在下载中的任务
            if (info.state == DownloadInfo.STATE_DOWNLOAD ||
                info.state == DownloadInfo.STATE_RELAY_DOWNLOAD) continue
            if (info.pages <= 0) continue
            if (SpiderTokenUtils.isSameCoreTitleAndAuthor(target, info)) {
                result.add(info)
            }
        }
        return result
    }

    /**
     * 拉取指定画廊的 token 集，按以下顺序：
     *   1. SimpleScanCache
     *   2. PtokenIndex（已下载完成或之前缓存过的画廊）
     *   3. 本地 .ehviewer（已有目录）
     *   4. 网络拉取（兜底）；拉取后写入 PtokenIndex 与 SimpleScanCache
     *
     * 拉取失败时返回 null，由调用方决定是否保守放弃。
     */
    private suspend fun fetchHashSetResilient(info: DownloadInfo): HashSet<String>? {
        // 1) SimpleScanCache
        SimpleScanCache.getHashSet(info.gid)?.let { return it }

        // 2) PtokenIndex
        EhDB.getPtokensIndex(info.gid)?.let { pi ->
            val set = SpiderTokenUtils.parsePtokenString(pi.getPtokens())
            if (set.isNotEmpty()) {
                SimpleScanCache.putHashSet(info.gid, set)
                return set
            }
        }

        // 3) 本地 .ehviewer
        val localSet = readLocalHashSet(info)
        if (localSet != null && localSet.isNotEmpty()) {
            // 本地不完整时尝试网络补齐
            val remoteSet = tryFetchRemoteAndMerge(info, localSet)
            if (remoteSet != null) {
                SimpleScanCache.putHashSet(info.gid, remoteSet)
                return remoteSet
            }
            SimpleScanCache.putHashSet(info.gid, localSet)
            return localSet
        }

        // 4) 网络拉取（兜底）
        val spi = SpiderTokenUtils.fetchSpiderInfoFromRemote(context, info)
            ?: return null
        val set = SpiderTokenUtils.toHashSet(spi)
        if (set.isEmpty()) return null
        try {
            EhDB.putPtokensIndex(info.gid, SpiderTokenUtils.serializePtokens(set), spi.pages)
        } catch (_: Exception) {
        }
        SimpleScanCache.putHashSet(info.gid, set)
        return set
    }

    /** 读本地 .ehviewer 拿到 token 集（不完整也算）。 */
    private fun readLocalHashSet(info: DownloadInfo): HashSet<String>? {
        val dir = SpiderDen.getExistingGalleryDownloadDir(info) ?: return null
        val f = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return null
        val spi = SpiderInfo.read(f) ?: return null
        return SpiderTokenUtils.toHashSet(spi)
    }

    /**
     * 本地有 .ehviewer 但可能不完整（页数 < 远程）时，
     * 拉一遍远程 preview 列表并补齐缺失的 token，再合并写入。
     * 拉取失败时返回 null（回退使用本地集）。
     */
    private suspend fun tryFetchRemoteAndMerge(
        info: DownloadInfo,
        localSet: HashSet<String>
    ): HashSet<String>? {
        val dir = SpiderDen.getExistingGalleryDownloadDir(info) ?: return null
        val localSpi = SpiderInfo.read(dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return null)
            ?: return null
        val token = info.token ?: return null
        // 远程列表不完整时 (local.pages 缺失) 才补齐
        val remoteSpi = SpiderTokenUtils.fetchSpiderInfoFromRemote(context, info) ?: return null
        if (remoteSpi.pages <= localSpi.pages) {
            // 远程不比本地完整，无需补齐；尝试写回最新 PtokenIndex 即可
            try {
                EhDB.putPtokensIndex(info.gid, SpiderTokenUtils.serializePtokens(localSet), localSpi.pages)
            } catch (_: Exception) {
            }
            return localSet
        }
        // 合并：取两者并集
        val merged = HashSet<String>(localSet)
        merged.addAll(SpiderTokenUtils.toHashSet(remoteSpi))
        // 写回 .ehviewer，使后续 SpiderQueen 复用完整 token 集
        try {
            val target = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return merged
            target.openOutputStream().use { os -> remoteSpi.write(os) }
        } catch (_: Exception) {
        }
        try {
            EhDB.putPtokensIndex(info.gid, SpiderTokenUtils.serializePtokens(merged), remoteSpi.pages)
        } catch (_: Exception) {
        }
        return merged
    }

    private suspend fun ensureNotCancelled() {
        coroutineContext.ensureActive()
        if (Thread.currentThread().isInterrupted) {
            throw kotlinx.coroutines.CancellationException("预碰撞检测已取消")
        }
    }

    private fun postProgress(percent: Int, detail: String?) {
        updateProgress(percent, detail)
        SimpleHandler.getInstance().post {
            callback?.onProgress(percent, detail)
        }
    }

    private fun logAndProgress(percent: Int, detail: String) {
        updateProgress(percent, detail)
        appendTaskLog(detail)
        SimpleHandler.getInstance().post {
            callback?.onProgress(percent, detail)
        }
    }

    private fun finish() {
        if (finishPosted) return
        finishPosted = true
        SimpleHandler.getInstance().post {
            callback?.onFinished(ArrayList(removed))
        }
    }

    companion object {
        private const val TAG = "WaitListProgressiveCheckTask"
    }
}