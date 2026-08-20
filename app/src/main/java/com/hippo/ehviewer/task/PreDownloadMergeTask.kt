package com.hippo.ehviewer.task

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.util.SparseArray
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.PreviewSet
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser
import com.hippo.ehviewer.dao.DownloadHistory
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.lib.yorozuya.SimpleHandler
import com.hippo.unifile.UniFile
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
        CANCELLED_NEWER
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

    override fun getTaskId(): String = "pre_download_merge_${gid}_${System.currentTimeMillis()}"

    override fun getTaskName(): String = context.getString(R.string.pre_download_merge_task_name)

    override fun getTaskDescription(): String = context.getString(R.string.pre_download_merge_task_desc)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.MERGE

    override fun isUniqueTask(): Boolean = false

    override fun isPausable(): Boolean = true

    override fun isPersistable(): Boolean = false

    override suspend fun execute(): Result<Unit> {
        return try {
            runMerge()
            notifyCompleted()
            Result.success(Unit)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled()
            } else {
                Log.w(TAG, "Pre-download merge failed", e)
                notifyError(e)
            }
            Result.failure(e)
        } finally {
            // 无论成败都必须通知下载管理器收尾，避免任务卡死在合并阶段
            finish(Outcome.PROCEED)
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
        val newSet = toHashSet(newSpi)
        if (newSet.isEmpty()) {
            logAndProgress(100, "画廊 hash 为空，回退正常下载")
            finish(Outcome.PROCEED)
            return
        }
        logAndProgress(40, "已拉取 ${newSpi.pages} 页 / ${newSet.size} 个 hash")
        val hashToNewIndex = buildHashToNewIndex(newSpi)
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
        val newUploader = newInfo.uploader?.trim()?.lowercase(Locale.ROOT) ?: ""
        val newCore = normalizeCoreTitle(EhUtils.getSuitableTitle(newInfo))
        if (newCore.isEmpty()) {
            return emptyList()
        }
        val result = mutableListOf<DownloadInfo>()
        for (info in getMergeableCandidates()) {
            if (info.gid == newInfo.gid) continue
            val candUploader = info.uploader?.trim()?.lowercase(Locale.ROOT) ?: ""
            if (newUploader.isNotEmpty() && candUploader.isNotEmpty() && newUploader != candUploader) {
                continue
            }
            if (normalizeCoreTitle(EhUtils.getSuitableTitle(info)) != newCore) {
                continue
            }
            result.add(info)
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

    /** 标题归一化：剥离日期标签（如 2026.08.02 / 2026-05-21），NFKC 后小写。 */
    private fun normalizeCoreTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        var t = title.replace(DATE_REGEX, " ")
        t = t.replace("🔄", " ")
        t = Normalizer.normalize(t, Normalizer.Form.NFKC)
        return t.trim().lowercase(Locale.ROOT)
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

    /** 预拉取新画廊完整 pTokenMap（循环 preview 页）。失败返回 null。 */
    private suspend fun fetchNewSpiderInfo(info: DownloadInfo): SpiderInfo? {
        val token = info.token
        if (token.isNullOrEmpty()) return null
        val client = EhApplication.getOkHttpClient(context)
        val gid = info.gid
        val gd = try {
            EhEngine.getGalleryDetail(null, client, EhUrl.getGalleryDetailUrl(gid, token, 0, false))
        } catch (_: Throwable) {
            null
        } ?: return null
        val pages = if (gd.SpiderInfoPages > 0) gd.SpiderInfoPages else info.pages
        val previewPages = gd.SpiderInfoPreviewPages
        if (pages <= 0 || previewPages <= 0) return null

        val spi = SpiderInfo()
        spi.gid = gid
        spi.token = token
        spi.pages = pages
        spi.previewPages = previewPages
        spi.pTokenMap = SparseArray(pages)
        val firstSet = gd.SpiderInfoPreviewSet
        if (firstSet != null && firstSet.size() > 0) {
            spi.previewPerPage = firstSet.size()
            accumulatePreviews(spi, firstSet)
        }
        for (i in 1 until previewPages) {
            ensureNotCancelled()
            try {
                val pair = EhEngine.getPreviewSet(
                    null, client, EhUrl.getGalleryDetailUrl(gid, token, i, false)
                )
                accumulatePreviews(spi, pair.first)
            } catch (_: Throwable) {
                return null
            }
            val pct = 10 + (i * 30) / maxOf(previewPages, 1)
            postProgress(
                pct,
                context.getString(R.string.pre_download_merge_fetching) + " ${i + 1}/$previewPages"
            )
        }
        if (spi.pTokenMap.size() == 0) return null
        return spi
    }

    private fun accumulatePreviews(spi: SpiderInfo, set: PreviewSet) {
        for (i in 0 until set.size()) {
            val r = GalleryPageUrlParser.parse(set.getPageUrlAt(i)) ?: continue
            val pToken = r.pToken
            if (!pToken.isNullOrEmpty() && pToken != SpiderInfo.TOKEN_FAILED) {
                spi.pTokenMap.put(r.page, pToken)
            }
        }
    }

    private fun toHashSet(spi: SpiderInfo): HashSet<String> {
        val set = HashSet<String>()
        for (i in 0 until spi.pTokenMap.size()) {
            val t = spi.pTokenMap.valueAt(i)
            if (!t.isNullOrEmpty() && t != SpiderInfo.TOKEN_FAILED) {
                set.add(t)
            }
        }
        return set
    }

    private fun buildHashToNewIndex(spi: SpiderInfo): HashMap<String, Int> {
        val map = HashMap<String, Int>()
        for (i in 0 until spi.pTokenMap.size()) {
            val t = spi.pTokenMap.valueAt(i)
            if (!t.isNullOrEmpty()) {
                map[t] = spi.pTokenMap.keyAt(i)
            }
        }
        return map
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
            val s = pi.getPtokens()
            if (!s.isNullOrBlank()) {
                val set = HashSet<String>()
                for (part in s.split(",")) {
                    val t = part.trim()
                    if (t.isNotEmpty() && t != SpiderInfo.TOKEN_FAILED) set.add(t)
                }
                if (set.isNotEmpty()) return set
            }
        }
        val dir = SpiderDen.getExistingGalleryDownloadDir(info) ?: return null
        val f = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return null
        val spi = SpiderInfo.read(f) ?: return null
        val set = HashSet<String>()
        for (i in 0 until spi.pTokenMap.size()) {
            val t = spi.pTokenMap.valueAt(i)
            if (!t.isNullOrEmpty() && t != SpiderInfo.TOKEN_FAILED) set.add(t)
        }
        return if (set.isEmpty()) null else set
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
        val candidateSpi = readCandidateSpiderInfo(candidateDir) ?: return
        val target = getOrCreateTargetDir(newInfo, newSpi) ?: return
        logAndProgress(
            base,
            "合并候选画廊 ${candidate.gid}（${candidateSpi.pTokenMap.size()} 页），剪切重复文件..."
        )

        val totalFiles = maxOf(candidateSpi.pTokenMap.size(), 1)
        var moved = 0
        var invalid = 0
        var processed = 0
        for (i in 0 until candidateSpi.pTokenMap.size()) {
            ensureNotCancelled()
            val srcIndex = candidateSpi.pTokenMap.keyAt(i)
            val hash = candidateSpi.pTokenMap.valueAt(i)
            if (hash.isNullOrEmpty() || hash == SpiderInfo.TOKEN_FAILED) continue
            val newIndex = hashToNewIndex[hash] ?: continue
            val srcFile = SpiderDen.findImageFile(candidateDir, srcIndex) ?: continue
            val ext = extensionOf(srcFile.name)
            val targetName = String.format(Locale.US, "%08d%s", newIndex + 1, ext)
            if (target.findFile(targetName) != null) {
                continue
            }
            val targetFile = target.createFile(targetName) ?: continue
            if (copyFile(srcFile, targetFile)) {
                if (isValidImage(targetFile)) {
                    srcFile.delete()
                    moved++
                } else {
                    targetFile.delete()
                    invalid++
                }
            } else {
                targetFile.delete()
            }
            processed++
            postProgress(
                base + span * processed / totalFiles,
                "合并候选画廊 ${candidate.gid}：已剪切 $moved 个文件"
            )
        }

        if (deleteRecursively(candidateDir)) {
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
            "预下载合并: gid=${candidate.gid} -> ${newInfo.gid}，剪切 $moved 个文件，删除 $invalid 个损坏文件"
        )
        logAndProgress(
            base + span,
            "候选画廊 ${candidate.gid} 合并完成：剪切 $moved 个，删除损坏 $invalid 个"
        )
    }

    private fun readCandidateSpiderInfo(dir: UniFile): SpiderInfo? {
        val f = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return null
        return SpiderInfo.read(f)
    }

    private fun getOrCreateTargetDir(info: DownloadInfo, newSpi: SpiderInfo): UniFile? {
        targetDir?.let { return it }
        val dir = SpiderDen.getGalleryDownloadDir(info) ?: return null
        if (!dir.ensureDir()) return null
        // 预写 .ehviewer，下载时 SpiderQueen 复用并按此跳过已存在文件
        writeSpiderInfo(dir, newSpi)
        targetDir = dir
        return dir
    }

    private fun writeSpiderInfo(dir: UniFile, spi: SpiderInfo) {
        try {
            val f = dir.createFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return
            f.openOutputStream().use { os -> spi.write(os) }
        } catch (_: Throwable) {
        }
    }

    private fun copyFile(source: UniFile, target: UniFile): Boolean {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        return try {
            inputStream = BufferedInputStream(source.openInputStream())
            outputStream = target.openOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = inputStream.read(buffer)
                if (count == -1) break
                outputStream?.write(buffer, 0, count)
            }
            outputStream?.flush()
            true
        } catch (_: Throwable) {
            false
        } finally {
            closeQuietly(inputStream)
            closeQuietly(outputStream)
        }
    }

    /** 校验图片完整性：解码头部 + 非空。 */
    private fun isValidImage(file: UniFile): Boolean {
        var inputStream: InputStream? = null
        return try {
            inputStream = file.openInputStream()
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeStream(inputStream, null, opts)
            opts.outWidth > 0 && opts.outHeight > 0 && opts.outMimeType != null
        } catch (_: Throwable) {
            false
        } finally {
            closeQuietly(inputStream)
        }
    }

    private fun deleteRecursively(file: UniFile?): Boolean {
        if (file == null || !file.exists()) return true
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursively(child)) return false
                }
            }
        }
        return file.delete()
    }

    private fun extensionOf(name: String?): String {
        if (name.isNullOrEmpty()) return ".jpg"
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot) else ".jpg"
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        if (closeable == null) return
        try {
            closeable.close()
        } catch (_: Exception) {
        }
    }

    private suspend fun ensureNotCancelled() {
        coroutineContext.ensureActive()
        if (Thread.currentThread().isInterrupted) {
            throw kotlinx.coroutines.CancellationException("预下载合并已取消")
        }
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
        private val DATE_REGEX = Regex(
            "(\\d{4}[-./年]\\d{1,2}[-./月]\\d{1,2})|(\\d{1,2}[-./月]\\d{1,2}[-./日]\\d{2,4})"
        )
    }
}

/**
 * 简单重复画廊扫描结果缓存。
 * 依次添加大量画廊时，每条下载记录的候选扫描（EhDB.getAllDownloadInfo + 标题归一化 + 目录存在性检查）
 * 代价较高且彼此高度重复。这里按 TTL 缓存已下载完成画廊的候选索引，
 * 以及每个候选画廊的 hash 集合，尽量复用扫描结果，避免重复解析文件元数据。
 */
object SimpleScanCache {
    private const val TTL_MS = 60_000L

    @Volatile
    private var cacheTime: Long = 0L
    @Volatile
    private var cachedCandidates: List<DownloadInfo>? = null

    private val hashSets = ConcurrentHashMap<Long, HashSet<String>>()

    @Synchronized
    fun getCandidates(): List<DownloadInfo>? {
        return if (System.currentTimeMillis() - cacheTime < TTL_MS) cachedCandidates else null
    }

    @Synchronized
    fun putCandidates(candidates: List<DownloadInfo>) {
        cacheTime = System.currentTimeMillis()
        cachedCandidates = candidates
    }

    fun getHashSet(gid: Long): HashSet<String>? = hashSets[gid]

    fun putHashSet(gid: Long, set: HashSet<String>) {
        hashSets[gid] = set
    }

    /** 数据发生变化（下载完成、删除、合并）时清空缓存。 */
    @JvmStatic
    @Synchronized
    fun invalidate() {
        cacheTime = 0L
        cachedCandidates = null
        hashSets.clear()
    }
}
