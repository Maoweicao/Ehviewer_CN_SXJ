package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadHistory
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.lib.yorozuya.SimpleHandler
import com.hippo.unifile.UniFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 下载中重预检任务：由用户在下载管理工具栏菜单手动触发。
 *
 * 调用前 [com.hippo.ehviewer.download.DownloadManager] 已收集「非已完成」状态的下载项
 * （闲置/排队/下载中/失败/更新/接力），并停止其中正在写入的任务，把它们的 gid 传入。
 * 流程：
 *  1. 在传入的候选任务之间按「作者 + 去日期标题」分组，只保留最完整者，其余合并文件后移除；
 *  2. 对保留者与已下载完成的同名画廊比对，存在递进/包含关系时执行简单画廊合并，只保留最全的画廊；
 *  3. 结果通过 [Callback] 回主线程，由 DownloadManager 按原状态恢复/重新排队。
 *
 * 与 [PreDownloadMergeTask] 的区别：后者只在下载开始前、面向单个新画廊运行；
 * 本任务面向一批「尚未完成」的画廊，且由用户手动触发。
 */
class DownloadingRecheckTask(
    context: Context,
    private val targetGids: List<Long>,
    private val callback: Callback?
) : BaseBackgroundTask(context) {

    interface Callback {
        fun onProgress(percent: Int, detail: String?)
        fun onFinished(result: RecheckResult)
    }

    data class RecheckResult(
        val groupsChecked: Int,
        val removedGids: List<Long>,
        val keptGids: List<Long>,
        val mergedCount: Int
    )

    /** 保留者选择用的纯数据载体，便于单测。 */
    data class Candidate(val gid: Long, val pages: Int, val tokenCount: Int)

    private val removedGids = mutableListOf<Long>()
    private val keptGids = mutableListOf<Long>()
    private var groupsChecked = 0
    private var mergedCount = 0

    // 任务 id 必须稳定：BackgroundTaskManager 注册与 appendTaskLog 都调用 getTaskId()，
    // 若每次返回新的时间戳会导致日志找不到活跃任务而被丢弃。
    private val taskId = "recheck_downloading_${System.currentTimeMillis()}"

    @Volatile
    private var finishPosted = false
    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false

    override fun getTaskId(): String = taskId

    override fun getTaskName(): String = context.getString(R.string.recheck_downloading_task_name)

    override fun getTaskDescription(): String = context.getString(R.string.recheck_downloading_task_desc)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.MERGE

    override fun isUniqueTask(): Boolean = false

    override fun isPausable(): Boolean = true

    override fun isPersistable(): Boolean = false

    override suspend fun execute(): Result<Unit> {
        return try {
            runRecheck()
            notifyCompleted()
            Result.success(Unit)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled()
            } else {
                Log.w(TAG, "Downloading recheck failed", e)
                notifyError(e)
            }
            Result.failure(e)
        } finally {
            // 无论成败都通知下载管理器收尾，避免任务卡死
            finish()
        }
    }

    private suspend fun runRecheck() {
        val all = EhDB.getAllDownloadInfo() ?: emptyList()
        val byGid = HashMap<Long, DownloadInfo>(all.size)
        for (info in all) byGid[info.gid] = info

        val candidates = ArrayList<DownloadInfo>()
        for (gid in targetGids) {
            val info = byGid[gid] ?: continue
            candidates.add(info)
        }
        if (candidates.isEmpty()) {
            logAndProgress(100, context.getString(R.string.recheck_downloading_none))
            return
        }

        // 已下载完成且有 .ehviewer 元数据的同名候选
        val finished = ArrayList<DownloadInfo>()
        for (info in all) {
            if (info.state != DownloadInfo.STATE_FINISH) continue
            if (info.archiveUri != null) continue
            val dir = SpiderDen.getExistingGalleryDownloadDir(info) ?: continue
            if (!dir.isDirectory) continue
            if (dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) == null) continue
            finished.add(info)
        }

        logAndProgress(0, "开始下载中重预检：候选 ${candidates.size} 个，已完成 ${finished.size} 个")

        val processed = HashSet<Long>()
        val total = maxOf(candidates.size, 1)
        var index = 0
        for (info in candidates) {
            ensureNotCancelled()
            if (!processed.add(info.gid)) {
                index++
                continue
            }

            // 同组候选任务
            val peers = ArrayList<DownloadInfo>()
            peers.add(info)
            for (other in candidates) {
                if (other.gid == info.gid) continue
                if (processed.contains(other.gid)) continue
                if (SpiderTokenUtils.isSameCoreTitleAndAuthor(info, other)) {
                    peers.add(other)
                    processed.add(other.gid)
                }
            }
            // 同名已完成画廊
            val finishedMatches = ArrayList<DownloadInfo>()
            for (f in finished) {
                if (SpiderTokenUtils.isSameCoreTitleAndAuthor(info, f)) finishedMatches.add(f)
            }

            val progress = index * 100 / total
            index++
            if (peers.size < 2 && finishedMatches.isEmpty()) {
                keptGids.add(info.gid)
                logAndProgress(progress, "gid=${info.gid} 无同名/递进对象，跳过")
                continue
            }

            groupsChecked++
            processGroup(peers, finishedMatches, progress)
        }

        logAndProgress(
            100,
            context.getString(
                R.string.recheck_downloading_done,
                groupsChecked, removedGids.size, mergedCount
            )
        )
    }

    private suspend fun processGroup(
        peers: List<DownloadInfo>,
        finishedMatches: List<DownloadInfo>,
        progress: Int
    ) {
        // 1. 拉取候选任务的 token 集（用于选最完整者）
        val tokenSets = HashMap<Long, HashSet<String>>()
        for (p in peers) {
            val set = SpiderTokenUtils.fetchHashSetResilient(context, p)
            if (set != null && set.isNotEmpty()) tokenSets[p.gid] = set
        }

        val keeperInfo = pickKeeperInfo(peers, tokenSets)
        if (keeperInfo == null) {
            for (p in peers) keptGids.add(p.gid)
            logAndProgress(progress, "无法判定保留者，保守保留 ${peers.size} 个候选任务")
            return
        }
        val keeperSet = tokenSets[keeperInfo.gid]
        if (keeperSet == null) {
            for (p in peers) keptGids.add(p.gid)
            logAndProgress(progress, "保留者 token 为空，保守保留全部")
            return
        }

        postProgress(
            progress,
            context.getString(R.string.recheck_downloading_comparing, EhUtils.getSuitableTitle(keeperInfo) ?: keeperInfo.gid.toString())
        )

        // 保留者目录 + hash→页索引映射（用于把源画廊文件按保留者索引落位）
        val target = PreDownloadMergeEngine.ensureTargetDir(keeperInfo, null, writeInfo = false)
        val hashToIndex = keeperHashToIndex(keeperInfo)

        // 2. 同组其他候选任务：保留者更完整/相等 → 合并其文件并移除；否则保留
        for (p in peers) {
            if (p.gid == keeperInfo.gid) continue
            val pSet = tokenSets[p.gid]
            if (pSet == null || !keeperSet.containsAll(pSet)) {
                keptGids.add(p.gid)
                logAndProgress(progress, "gid=${p.gid} 与保留者无包含关系，保留")
                continue
            }
            if (target == null || hashToIndex.isEmpty()) {
                // 无法把文件并入保留者目录时不删除源，避免丢失本地已有文件
                keptGids.add(p.gid)
                logAndProgress(progress, "无法定位保留者目录，保守保留 gid=${p.gid}")
                continue
            }
            mergeSourceIntoKeeper(p, target, hashToIndex)
            removeSource(p, keeperInfo.gid)
        }

        // 3. 已完成画廊：与保留者存在包含关系（任一方向）时执行简单合并，只保留最全的画廊
        for (f in finishedMatches) {
            val fSet = SpiderTokenUtils.fetchHashSetResilient(context, f)
            if (fSet == null || fSet.isEmpty()) continue
            if (!keeperSet.containsAll(fSet) && !fSet.containsAll(keeperSet)) continue
            if (target == null || hashToIndex.isEmpty()) {
                // 无法合并时保守保留已下载画廊，避免误删更完整的副本
                logAndProgress(progress, "无法定位保留者目录，保留已下载 gid=${f.gid}")
                continue
            }
            mergeSourceIntoKeeper(f, target, hashToIndex)
            removeSource(f, keeperInfo.gid)
        }

        keptGids.add(keeperInfo.gid)
    }

    /** 把源画廊中与保留者索引匹配的文件并入保留者目录。 */
    private suspend fun mergeSourceIntoKeeper(
        source: DownloadInfo,
        target: UniFile,
        hashToIndex: Map<String, Int>
    ) {
        val srcDir = SpiderDen.getExistingGalleryDownloadDir(source) ?: return
        val srcSpi = PreDownloadMergeEngine.readSpiderInfo(srcDir) ?: return
        val stats = PreDownloadMergeEngine.copyMatchingFiles(
            candidateDir = srcDir,
            candidateSpi = srcSpi,
            target = target,
            hashToNewIndex = hashToIndex,
            checkCancelled = { ensureNotCancelled() }
        )
        Log.i(
            TAG,
            "重预检合并: gid=${source.gid} -> 保留者，剪切 ${stats.moved} 个文件，删除损坏 ${stats.invalid} 个"
        )
    }

    /** 移除被判定为旧版本/重复的源画廊：删除目录、记录合并历史并上报 gid。 */
    private fun removeSource(info: DownloadInfo, keeperGid: Long) {
        val dir = SpiderDen.getExistingGalleryDownloadDir(info)
        if (dir != null) {
            PreDownloadMergeEngine.deleteRecursively(dir)
        }
        EhDB.recordDownloadAsDuplicate(info, DownloadHistory.DELETION_PROGRESSIVE_MERGED, keeperGid)
        EhDB.removeDownloadDirname(info.gid)
        if (!removedGids.contains(info.gid)) {
            removedGids.add(info.gid)
            mergedCount++
        }
        SimpleScanCache.invalidate()
        Log.i(TAG, "重预检移除: gid=${info.gid} -> 保留 ${keeperGid}")
    }

    /** 读取/拉取保留者的 hash→页索引映射；本地 .ehviewer 优先，其次远程。 */
    private suspend fun keeperHashToIndex(info: DownloadInfo): HashMap<String, Int> {
        val dir = SpiderDen.getExistingGalleryDownloadDir(info)
        if (dir != null) {
            val local = PreDownloadMergeEngine.readSpiderInfo(dir)
            if (local != null && local.pTokenMap.size() > 0) {
                return SpiderTokenUtils.buildHashToNewIndex(local)
            }
        }
        val remote = SpiderTokenUtils.fetchSpiderInfoFromRemote(context, info) ?: return HashMap()
        return SpiderTokenUtils.buildHashToNewIndex(remote)
    }

    private fun pickKeeperInfo(
        peers: List<DownloadInfo>,
        tokenSets: Map<Long, HashSet<String>>
    ): DownloadInfo? {
        val candidates = peers.map {
            Candidate(it.gid, it.pages, tokenSets[it.gid]?.size ?: -1)
        }
        val picked = pickKeeper(candidates) ?: return null
        return peers.firstOrNull { it.gid == picked.gid }
    }

    private suspend fun ensureNotCancelled() {
        while (isPaused && !isCancelled) {
            delay(200)
        }
        if (isCancelled) {
            throw kotlinx.coroutines.CancellationException("下载中重预检已取消")
        }
        coroutineContext.ensureActive()
        if (Thread.currentThread().isInterrupted) {
            throw kotlinx.coroutines.CancellationException("下载中重预检已取消")
        }
    }

    override suspend fun pause() {
        isPaused = true
        updateState(TaskState.PAUSED)
        appendTaskLog("任务已暂停")
    }

    override suspend fun resume() {
        isPaused = false
        updateState(TaskState.RUNNING)
        appendTaskLog("任务已恢复")
    }

    override suspend fun cancel() {
        isCancelled = true
        isPaused = false
        appendTaskLog("任务已取消")
        notifyCancelled()
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
        val result = RecheckResult(
            groupsChecked,
            ArrayList(removedGids),
            ArrayList(keptGids.distinct()),
            mergedCount
        )
        SimpleHandler.getInstance().post {
            callback?.onFinished(result)
        }
    }

    companion object {
        private const val TAG = "DownloadingRecheckTask"

        /**
         * 选择最完整的保留者：token（图片）数量最多 → 页数最多 → gid 最大（同名最新）。
         * 纯函数，便于单测。
         */
        @JvmStatic
        fun pickKeeper(candidates: List<Candidate>): Candidate? {
            var best: Candidate? = null
            for (c in candidates) {
                if (best == null) {
                    best = c
                    continue
                }
                val b = best
                if (c.tokenCount > b.tokenCount ||
                    (c.tokenCount == b.tokenCount && c.pages > b.pages) ||
                    (c.tokenCount == b.tokenCount && c.pages == b.pages && c.gid > b.gid)
                ) {
                    best = c
                }
            }
            return best
        }
    }
}
