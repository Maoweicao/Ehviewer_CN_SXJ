package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadHistory
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.task.TaskState
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.lib.yorozuya.SimpleHandler
import com.hippo.unifile.UniFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 预下载合并任务：在正式下载开始前扫描数据库中已下载的画廊记录（含已完成和未完成），
 * 按「作者 + 去日期标题」找到疑似递进/重复的画廊（始终从最新的找起），
 * 再用 .ehviewer 的 hash 集做确认：
 *  - 新画廊 ⊇ 旧画廊 → 把旧画廊中的同名文件剪切到目标目录，下载仅补齐缺失部分；
 *  - 旧画廊 ⊇ 新画廊（已存在更新更完整的版本）→ 取消本次下载并在 DOWNLOAD_HISTORY 标注合并去向。
 *
 * 支持合并未完成画廊（STATE_NONE / STATE_FAILED / STATE_WAIT），使其已下载的文件能被复用，
 * 减少重复下载。正在下载中的画廊（STATE_DOWNLOAD / STATE_RELAY_DOWNLOAD）会被跳过以避免冲突。
 *
 * 结果通过 [Callback] 回主线程通知 [com.hippo.ehviewer.download.DownloadManager]。
 */
class PreDownloadMergeTask(
    context: Context,
    private val gid: Long,
    private val callback: Callback?
) : BaseBackgroundTask(context) {

    enum class Outcome {
        /** 正常进入下载队列（可能已预合并文件） */
        PROCEED,
        /** 已存在更新更完整的版本，取消本次下载 */
        CANCELLED_NEWER,
        /** 限流导致扫描失败：等待自动换 IP 后由重试入口重新执行 */
        FAILED_LIMIT
    }

    interface Callback {
        fun onProgress(percent: Int, detail: String?)
        fun onFinished(outcome: Outcome, removedSourceGids: List<Long>)
    }

    private var outcome = Outcome.PROCEED
    private val removedSourceGids = mutableListOf<Long>()
    private var targetDir: UniFile? = null
    @Volatile
    private var finishPosted = false

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false

    // 任务 id 必须稳定：注册与 appendTaskLog 都调用 getTaskId()，不能每次返回新时间戳。
    private val taskId = "pre_download_merge_${gid}_${System.currentTimeMillis()}"

    override fun getTaskId(): String = taskId

    override fun getTaskName(): String = context.getString(R.string.pre_download_merge_task_name)

    override fun getTaskDescription(): String = context.getString(R.string.pre_download_merge_task_desc)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.MERGE

    override fun isUniqueTask(): Boolean = false

    override fun isPausable(): Boolean = true

    override fun isPersistable(): Boolean = false

    override suspend fun execute(): Result<Unit> {
        return try {
            runMerge()
            PreDownloadScanRetryStore.clear(context, gid)
            try {
                com.hippo.ehviewer.BackgroundTaskManager.getInstance()
                    .getTaskStatusManager().markTaskRetryFinished(getTaskId())
            } catch (_: Throwable) {
            }
            notifyCompleted()
            Result.success(Unit)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled()
            } else if (e is PreDownloadLimitException) {
                outcome = Outcome.FAILED_LIMIT
                Log.w(TAG, "Pre-download scan paused by rate limit", e)
                notifyError(e)
            } else {
                Log.w(TAG, "Pre-download merge failed", e)
                notifyError(e)
            }
            Result.failure(e)
        } finally {
            // 无论成败都必须通知下载管理器收尾，避免任务卡死在合并阶段
            finish(outcome)
        }
    }

    private suspend fun runMerge() {
        val newInfo = EhDB.getDownloadInfo(gid)
        if (newInfo == null) {
            logAndProgress(5, "未找到下载记录 gid=$gid，跳过预合并")
            finish(Outcome.PROCEED)
            return
        }
        val newTitle = EhUtils.getSuitableTitle(newInfo) ?: ""
        logAndProgress(0, "开始下载前递进关系画廊扫描: ${newInfo.gid} - $newTitle")

        // 1. 数据库扫描候选（按作者 + 去日期标题），包含已完成和未完成的画廊
        logAndProgress(5, "扫描下载记录，查找同名递进关系画廊（含已完成和未完成）...")
        val candidates = findCandidates(newInfo)
        if (candidates.isEmpty()) {
            logAndProgress(100, "扫描未发现需要合并的递进关系画廊，直接下载")
            finish(Outcome.PROCEED)
            return
        }
        val finishedCount = candidates.count { it.state == DownloadInfo.STATE_FINISH }
        val unfinishedCount = candidates.size - finishedCount
        logAndProgress(10, "发现 ${candidates.size} 个候选画廊（已完成 $finishedCount，未完成 $unfinishedCount）")
        Log.i(TAG, "预下载扫描: gid=$gid 找到 ${candidates.size} 个候选（完成=$finishedCount 未完成=$unfinishedCount）")

        // 2. 预拉取新画廊的完整 hash（仅对存在候选时付出网络成本）
        val newSpi = fetchNewSpiderInfo(newInfo)
        if (newSpi == null) {
            logAndProgress(100, "拉取画廊 hash 失败，回退正常下载")
            Log.w(TAG, "预下载: 拉取新画廊 hash 失败，回退正常下载 gid=$gid")
            finish(Outcome.PROCEED)
            return
        }
        val newSet = SpiderTokenUtils.toHashSet(newSpi)
        if (newSet.isEmpty()) {
            logAndProgress(100, "画廊 hash 为空，回退正常下载")
            finish(Outcome.PROCEED)
            return
        }
        logAndProgress(40, "已拉取 ${newSpi.pages} 页 / ${newSet.size} 个 hash")
        val hashToNewIndex = SpiderTokenUtils.buildHashToNewIndex(newSpi)
        // 同步 ptoken 索引，便于后续递进检测复用
        try {
            EhDB.putPtokensIndex(gid, newSet.joinToString(","), newSpi.pages)
        } catch (_: Exception) {
        }

        // 3. 从最新候选开始确认递进关系
        var first = true
        var mergedAny = false
        var candidateIndex = 0
        val totalCandidates = candidates.size
        for (candidate in candidates) {
            ensureNotCancelled()
            val candidateSet = candidateHashSet(candidate)
            if (candidateSet == null || candidateSet.isEmpty()) {
                continue
            }
            if (first && candidateSet.containsAll(newSet)) {
                // 已存在更新更完整的版本：仅当候选画廊已完成下载时才取消本次下载
                // 若候选画廊本身未完成（STATE_FAILED / STATE_NONE），则继续正常合并流程，
                // 将候选画廊的已有文件转移到新画廊目录，避免两者都不完整
                if (candidate.state == DownloadInfo.STATE_FINISH) {
                    EhDB.recordDownloadAsDuplicate(
                        newInfo, DownloadHistory.DELETION_PROGRESSIVE_MERGED, candidate.gid
                    )
                    logAndProgress(
                        100,
                        "已存在更新版本 ${candidate.gid}（⊇ 本画廊），取消本次下载并记录合并去向"
                    )
                    Log.i(TAG, "预下载: 已存在更新版本 gid=${candidate.gid} ⊇ gid=$gid，取消下载")
                    outcome = Outcome.CANCELLED_NEWER
                    finish(outcome)
                    return
                } else {
                    logAndProgress(
                        40,
                        "候选画廊 ${candidate.gid}（state=${
                            stateName(candidate.state)
                        }）⊆ 本画廊但未完成，继续合并已有文件"
                    )
                }
            }
            first = false
            if (newSet.containsAll(candidateSet)) {
                // 新画廊更完整 → 剪切旧画廊文件到目标目录
                val base = 40 + candidateIndex * 50 / maxOf(totalCandidates, 1)
                val span = 50 / maxOf(totalCandidates, 1)
                mergeCandidate(candidate, newInfo, newSpi, hashToNewIndex, base, span)
                mergedAny = true
                candidateIndex++
                ensureNotCancelled()
            }
        }

        if (mergedAny) {
            logAndProgress(95, "预下载合并完成，准备下载缺失部分")
        }
        postProgress(100, context.getString(R.string.pre_download_merge_done))
        finish(Outcome.PROCEED)
    }

    /** 数据库扫描候选：目录存在、作者 + 去日期标题相同的画廊（含已完成和未完成）。 */
    private fun findCandidates(newInfo: DownloadInfo): List<DownloadInfo> {
        val newCore = SpiderTokenUtils.normalizeCoreTitle(EhUtils.getSuitableTitle(newInfo))
        if (newCore.isEmpty()) {
            return emptyList()
        }
        val result = mutableListOf<DownloadInfo>()
        for (info in getMergeableCandidates()) {
            if (SpiderTokenUtils.isSameCoreTitleAndAuthor(newInfo, info)) {
                result.add(info)
            }
        }
        return result
    }

    /**
     * 获取可合并的候选画廊索引：目录存在、含 .ehviewer 元数据的画廊。
     *
     * 包含以下状态的画廊：
     * - [DownloadInfo.STATE_FINISH]：已完成下载，安全合并
     * - [DownloadInfo.STATE_NONE]：空闲/未开始，安全合并
     * - [DownloadInfo.STATE_FAILED]：下载失败，安全合并
     * - [DownloadInfo.STATE_WAIT]：等待队列中（尚未下载），安全合并
     *
     * 排除以下状态：
     * - [DownloadInfo.STATE_DOWNLOAD] / [DownloadInfo.STATE_RELAY_DOWNLOAD]：正在下载中，跳过避免冲突
     * - [DownloadInfo.STATE_UPDATE]：更新中，跳过
     * - [DownloadInfo.STATE_INVALID]：无效记录，跳过
     *
     * 60 秒内复用缓存，避免依次添加大量画廊时重复全量扫库与文件元数据解析。
     */
    private fun getMergeableCandidates(): List<DownloadInfo> {
        SimpleScanCache.getCandidates()?.let { return it }
        val all = EhDB.getAllDownloadInfo() ?: return emptyList()
        val result = mutableListOf<DownloadInfo>()
        for (info in all) {
            if (!isMergeableState(info.state)) continue
            if (info.archiveUri != null) continue
            val dir = SpiderDen.getExistingGalleryDownloadDir(info) ?: continue
            if (!dir.isDirectory) continue
            // 必须含 .ehviewer 元数据文件（用于 hash 比对确定递进关系）
            if (dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) == null) continue
            result.add(info)
        }
        SimpleScanCache.putCandidates(result)
        return result
    }

    /**
     * 判断画廊状态是否适合预下载合并。
     * 仅排除正在下载、接力下载、更新中和无效状态。
     */
    private fun isMergeableState(state: Int): Boolean {
        return when (state) {
            DownloadInfo.STATE_FINISH,
            DownloadInfo.STATE_NONE,
            DownloadInfo.STATE_FAILED,
            DownloadInfo.STATE_WAIT -> true
            else -> false
        }
    }

    /** 将 DownloadInfo 状态码转为可读名称，用于日志。 */
    private fun stateName(state: Int): String = when (state) {
        DownloadInfo.STATE_INVALID -> "INVALID"
        DownloadInfo.STATE_NONE -> "NONE"
        DownloadInfo.STATE_WAIT -> "WAIT"
        DownloadInfo.STATE_DOWNLOAD -> "DOWNLOAD"
        DownloadInfo.STATE_FINISH -> "FINISH"
        DownloadInfo.STATE_FAILED -> "FAILED"
        DownloadInfo.STATE_UPDATE -> "UPDATE"
        DownloadInfo.STATE_RELAY_DOWNLOAD -> "RELAY"
        else -> "UNKNOWN($state)"
    }

    /** 预拉取新画廊完整 pTokenMap（循环 preview 页）。非限流失败返回 null，限流抛出。 */
    private suspend fun fetchNewSpiderInfo(info: DownloadInfo): SpiderInfo? {
        var attempt = 0
        while (true) {
            try {
                return SpiderTokenUtils.fetchSpiderInfoFromRemoteStrict(context, info) { i, previewPages ->
                    ensureNotCancelled()
                    val pct = 10 + (i * 30) / maxOf(previewPages, 1)
                    postProgress(
                        pct,
                        context.getString(R.string.pre_download_merge_fetching) + " ${i + 1}/$previewPages"
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (!PreDownloadScanRetryCoordinator.isLimitFailure(context, t)) {
                    Log.w(TAG, "预下载: 拉取 hash 失败（非限流），回退正常下载 gid=${info.gid}", t)
                    return null
                }
                appendTaskLog("检测到限流，预下载扫描等待自动切换 IP 后重试（第 ${attempt + 1} 次）")
                try {
                    com.hippo.ehviewer.BackgroundTaskManager.getInstance()
                        .getTaskStatusManager()
                        .markTaskRetrying(getTaskId(), attempt + 1, 2, "等待 IP 切换后重试")
                } catch (_: Throwable) {
                }
                val switched = PreDownloadScanRetryCoordinator.awaitIpSwitch(context)
                if (switched && attempt < 2) {
                    attempt++
                    appendTaskLog("IP 已切换，重新拉取画廊 hash")
                    try {
                        com.hippo.ehviewer.BackgroundTaskManager.getInstance()
                            .getTaskStatusManager().markTaskRetryFinished(getTaskId())
                    } catch (_: Throwable) {
                    }
                    continue
                }
                PreDownloadScanRetryStore.markFailed(context, info.gid, t.message)
                throw PreDownloadLimitException(
                    "预下载扫描遇到限流，已登记等待 IP 切换后重试", t
                )
            }
        }
    }

    /** 候选画廊的 hash 全集：优先复用缓存，其次 PtokensIndex，最后读 .ehviewer。 */
    private fun candidateHashSet(info: DownloadInfo): HashSet<String>? {
        SimpleScanCache.getHashSet(info.gid)?.let { return it }
        val set = readCandidateHashSet(info) ?: return null
        SimpleScanCache.putHashSet(info.gid, set)
        return set
    }

    private fun readCandidateHashSet(info: DownloadInfo): HashSet<String>? {
        val pi = EhDB.getPtokensIndex(info.gid)
        if (pi != null) {
            val set = SpiderTokenUtils.parsePtokenString(pi.getPtokens())
            if (set.isNotEmpty()) return set
        }
        val dir = SpiderDen.getExistingGalleryDownloadDir(info) ?: return null
        val f = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return null
        val spi = SpiderInfo.read(f) ?: return null
        return SpiderTokenUtils.toHashSet(spi).takeIf { it.isNotEmpty() }
    }

    /** 剪切候选画廊中与新画廊 hash 相同的文件到目标目录，并清理候选记录。 */
    private suspend fun mergeCandidate(
        candidate: DownloadInfo,
        newInfo: DownloadInfo,
        newSpi: SpiderInfo,
        hashToNewIndex: Map<String, Int>,
        base: Int,
        span: Int
    ) {
        // 安全检查：候选画廊在扫描后可能已开始下载，此时不应合并
        val currentInfo = EhDB.getDownloadInfo(candidate.gid)
        if (currentInfo == null || !isMergeableState(currentInfo.state)) {
            logAndProgress(base, "候选画廊 ${candidate.gid} 状态已变更（state=${
                currentInfo?.state ?: -1
            }），跳过合并")
            return
        }
        val candidateDir = SpiderDen.getExistingGalleryDownloadDir(candidate) ?: return
        val candidateSpi = PreDownloadMergeEngine.readSpiderInfo(candidateDir) ?: return
        val target = getOrCreateTargetDir(newInfo, newSpi) ?: return
        logAndProgress(
            base,
            "合并候选画廊 ${candidate.gid}（${candidateSpi.pTokenMap.size()} 页），剪切重复文件..."
        )

        val stats = PreDownloadMergeEngine.copyMatchingFiles(
            candidateDir = candidateDir,
            candidateSpi = candidateSpi,
            target = target,
            hashToNewIndex = hashToNewIndex,
            onProgress = { processed, total, moved ->
                postProgress(
                    base + span * processed / total,
                    "合并候选画廊 ${candidate.gid}：已剪切 $moved 个文件"
                )
            },
            checkCancelled = { ensureNotCancelled() }
        )

        if (PreDownloadMergeEngine.deleteRecursively(candidateDir)) {
            EhDB.recordDownloadAsDuplicate(
                candidate, DownloadHistory.DELETION_PROGRESSIVE_MERGED, newInfo.gid
            )
            EhDB.removeDownloadDirname(candidate.gid)
            EhDB.removeDownloadInfo(candidate.gid)
            removedSourceGids.add(candidate.gid)
            // 扫描缓存中的被合并画廊已失效，清空以便后续任务重新扫描
            SimpleScanCache.invalidate()
        }
        Log.i(
            TAG,
            "预下载合并: gid=${candidate.gid} -> ${newInfo.gid}，剪切 ${stats.moved} 个文件，删除 ${stats.invalid} 个损坏文件"
        )
        logAndProgress(
            base + span,
            "候选画廊 ${candidate.gid} 合并完成：剪切 ${stats.moved} 个，删除损坏 ${stats.invalid} 个"
        )
    }

    private fun getOrCreateTargetDir(info: DownloadInfo, newSpi: SpiderInfo): UniFile? {
        targetDir?.let { return it }
        val dir = PreDownloadMergeEngine.ensureTargetDir(info, newSpi, writeInfo = true) ?: return null
        targetDir = dir
        return dir
    }

    private suspend fun ensureNotCancelled() {
        // 暂停：在这里等，恢复后继续
        while (isPaused && !isCancelled) {
            delay(200)
        }
        if (isCancelled) {
            throw kotlinx.coroutines.CancellationException("预下载合并已取消")
        }
        coroutineContext.ensureActive()
        if (Thread.currentThread().isInterrupted) {
            throw kotlinx.coroutines.CancellationException("预下载合并已取消")
        }
    }

    // 之前这个任务宣称 isPausable()=true 却没实现 pause/resume/cancel，
    // BackgroundTaskController 会吞掉 UnsupportedOperationException 返回 false，
    // 用户在任务列表点暂停/取消都毫无反应。
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

    /** 更新进度并写入任务日志（阶段节点使用）。 */
    private fun logAndProgress(percent: Int, detail: String) {
        updateProgress(percent, detail)
        appendTaskLog(detail)
        SimpleHandler.getInstance().post {
            callback?.onProgress(percent, detail)
        }
    }

    private fun finish(outcome: Outcome) {
        if (finishPosted) return
        finishPosted = true
        SimpleHandler.getInstance().post {
            callback?.onFinished(outcome, ArrayList(removedSourceGids))
        }
    }

    companion object {
        private const val TAG = "PreDownloadMergeTask"
    }
}

/**
 * 简单重复画廊扫描结果缓存。
 * 依次添加大量画廊时，每条下载记录的候选扫描（EhDB.getAllDownloadInfo + 标题归一化 + 目录存在性检查）
 * 代价较高且彼此高度重复。这里按 TTL 缓存已下载完成画廊的候选索引，
 * 以及每个候选画廊的 hash 集合，尽量复用扫描结果，避免重复解析文件元数据。
 *
 * <p>容量必须有上限：每个 hash 集合都是一整本画廊的 token（上千个 40 字符字符串），
 * 依次扫描大量画廊时无上限累积会占用数百 MB 堆内存，最终在下载或索引线程上 OOM
 * （crash-2026-10-31 11:55 / 15:05）。这里用访问序 LRU + 总 hash 数上限控制内存，
 * 超出后按最久未使用顺序淘汰，失效时仍可重新从数据库/文件读取。
 */
object SimpleScanCache {
    private const val TTL_MS = 60_000L

    /** 最多缓存多少个画廊的 hash 集合。 */
    private const val MAX_CACHED_HASH_SETS = 32

    /** 所有缓存 hash 集合的 hash 条目总数上限（约 150k 个 40 字符串，十几 MB 量级）。 */
    private const val MAX_CACHED_HASH_TOTAL = 150_000

    private val lock = Any()

    private var cacheTime: Long = 0L
    private var cachedCandidates: List<DownloadInfo>? = null

    private val hashSets = LinkedHashMap<Long, HashSet<String>>(16, 0.75f, true)

    private var cachedHashTotal = 0

    fun getCandidates(): List<DownloadInfo>? = synchronized(lock) {
        if (System.currentTimeMillis() - cacheTime < TTL_MS) {
            cachedCandidates
        } else {
            // TTL 到期后立即释放引用，避免一份全量 DownloadInfo 列表长期占着内存
            cachedCandidates = null
            cacheTime = 0L
            null
        }
    }

    fun putCandidates(candidates: List<DownloadInfo>) = synchronized(lock) {
        cacheTime = System.currentTimeMillis()
        cachedCandidates = candidates
    }

    fun getHashSet(gid: Long): HashSet<String>? = synchronized(lock) { hashSets[gid] }

    fun putHashSet(gid: Long, set: HashSet<String>) = synchronized(lock) {
        val previous = hashSets.put(gid, set)
        cachedHashTotal += set.size - (previous?.size ?: 0)
        evictIfNeeded(gid)
    }

    private fun evictIfNeeded(keepGid: Long) {
        val iterator = hashSets.entries.iterator()
        while (iterator.hasNext() &&
            (hashSets.size > MAX_CACHED_HASH_SETS || cachedHashTotal > MAX_CACHED_HASH_TOTAL)
        ) {
            val entry = iterator.next()
            if (entry.key == keepGid) continue
            cachedHashTotal -= entry.value.size
            iterator.remove()
        }
    }

    /** 数据发生变化（下载完成、删除、合并）时清空缓存。 */
    @JvmStatic
    fun invalidate() {
        synchronized(lock) {
            cacheTime = 0L
            cachedCandidates = null
            hashSets.clear()
            cachedHashTotal = 0
        }
    }
}
