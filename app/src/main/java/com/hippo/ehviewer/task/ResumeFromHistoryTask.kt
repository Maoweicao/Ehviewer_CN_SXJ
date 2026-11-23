package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.cache.GalleryCacheManager
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadHistory
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import kotlin.coroutines.coroutineContext

/**
 * 从下载历史恢复下载任务。
 *
 * 只恢复“当前有效”（[DownloadHistory.DELETION_NONE]）的历史，且与 [RestoreDownloadTask]
 * 共用同一套四步过滤（[RestoreCandidateValidator]），避免把已被下载列表、目录映射、
 * 重复目录或递进包含的画廊重新恢复成副本。
 *
 * 本地目录仍在的候选走“只补缺失文件”的恢复；目录已消失的候选走完整下载。
 */
class ResumeFromHistoryTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "ResumeFromHistoryTask"
    }

    @Volatile
    private var isCancelled = false

    private val downloadManager: DownloadManager = EhApplication.getDownloadManager(context)
    private val httpClient: OkHttpClient = EhApplication.getOkHttpClient(context)
    private val downloadRoot by lazy { Settings.getDownloadLocation() }

    private var totalAdded = 0
    private var totalFailed = 0
    private var onDiskCount = 0
    private var fullDownloadCount = 0

    override fun getTaskId(): String = "resume_from_history"

    override fun getTaskName(): String = context.getString(R.string.settings_download_resume_from_history)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.IMPORT

    override fun getTaskDescription(): String? =
        context.getString(R.string.settings_download_resume_from_history_summary)

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            appendTaskLog(context.getString(R.string.resume_from_history_scanning))

            coroutineContext[Job]?.ensureActive()

            // ── 阶段一：读取有效历史并构建候选 ──
            val allHistory = EhDB.getAllDownloadHistory()
            appendTaskLog("下载历史中共 ${allHistory.size} 条记录")

            val histories = allHistory.filter { it.deletionType == DownloadHistory.DELETION_NONE }
            appendTaskLog("其中有效记录 ${histories.size} 条")
            if (histories.isEmpty()) {
                appendTaskLog("没有需要恢复的下载历史")
                notifyCompleted()
                return Result.success(Unit)
            }

            val candidates = ArrayList<RestoreCandidate>(histories.size)
            onDiskCount = 0
            fullDownloadCount = 0
            for ((index, history) in histories.withIndex()) {
                if (isCancelled) throw CancellationException("任务已取消")
                val candidate = buildCandidate(history)
                if (candidate != null) {
                    candidates.add(candidate)
                    if (candidate.dir != null) onDiskCount++ else fullDownloadCount++
                }
                if ((index + 1) % 50 == 0) {
                    updateProgress(index + 1, histories.size, "已分析 ${index + 1}/${histories.size}")
                }
            }
            appendTaskLog("分析完成: $onDiskCount 个在磁盘上, $fullDownloadCount 个需要完整下载")

            if (candidates.isEmpty()) {
                appendTaskLog("没有可恢复的画廊")
                notifyCompleted()
                return Result.success(Unit)
            }

            // ── 阶段二：四步过滤 ──
            updateProgress(-1, "校验 ${candidates.size} 个候选…")
            appendTaskLog("开始校验 ${candidates.size} 个候选…")
            val validator = RestoreCandidateValidator(
                context,
                downloadManager,
                httpClient,
                log = { appendTaskLog(it) },
                progress = { current, total, detail -> updateProgress(current, total, detail) }
            )
            val validation = validator.validate(candidates)
            logRejections(validation.rejected)

            val accepted = validation.accepted
            appendTaskLog("校验完成：可恢复 ${accepted.size} 个，跳过 ${validation.rejected.size} 个")
            if (accepted.isEmpty()) {
                appendTaskLog("没有可恢复的画廊")
                notifyCompleted()
                return Result.success(Unit)
            }

            // ── 阶段三：构建 GalleryInfo（磁盘项优先用本地缓存） ──
            val items = ArrayList<GalleryInfo>(accepted.size)
            val needApi = ArrayList<GalleryInfo>()
            for (candidate in accepted) {
                val history = candidate.payload as? DownloadHistory
                val info = buildGalleryInfo(candidate, history)
                items.add(info)
                // 只有缺标题或 token 时才联网：缩略图缺了可以稍后按需加载，
                // 不能因为 thumb 为空就给上万个画廊逐个发详情请求。
                if (info.title.isNullOrEmpty() || info.token.isNullOrEmpty()) {
                    needApi.add(info)
                }
            }

            if (needApi.isNotEmpty()) {
                appendTaskLog("正在从 API 补全 ${needApi.size} 个画廊的信息…")
                var apiDone = 0
                for (chunk in needApi.chunked(25)) {
                    coroutineContext[Job]?.ensureActive()
                    try {
                        EhEngine.fillGalleryListByApiBestEffort(
                            null,
                            httpClient,
                            chunk,
                            EhUrl.getReferer()
                        )
                    } catch (e: Throwable) {
                        com.hippo.util.ExceptionUtils.throwIfFatal(e)
                        Log.e(TAG, "Failed to fill gallery list from API", e)
                        appendTaskLog("API 获取失败: ${e.message}")
                    }
                    apiDone += chunk.size
                    updateProgress(apiDone, needApi.size, "补全画廊信息 $apiDone/${needApi.size}")
                    // 逐批节流，避免 1.5 万条时连续几百个请求触发限流
                    kotlinx.coroutines.delay(800)
                }
            }

            // ── 阶段四：写入下载队列 ──
            appendTaskLog("开始添加 ${items.size} 个画廊到下载队列…")
            var successCount = 0
            var failCount = 0
            for ((index, info) in items.withIndex()) {
                if (isCancelled) throw CancellationException("任务已取消")
                val candidate = accepted[index]
                if (info.title.isNullOrEmpty()) {
                    info.title = candidate.title?.takeIf { it.isNotEmpty() }
                        ?: candidate.dirname.takeIf { it.isNotEmpty() }
                        ?: info.gid.toString()
                }
                if (RestoreSupport.commit(downloadManager, info, candidate) { appendTaskLog(it) }) {
                    successCount++
                } else {
                    failCount++
                }
                updateProgress(index + 1, items.size, "已处理 ${index + 1}/${items.size}")
            }

            totalAdded = successCount
            totalFailed = failCount

            val summary = "恢复完成: 成功 $successCount，失败 $failCount " +
                "(磁盘: $onDiskCount，完整下载: $fullDownloadCount)"
            Log.d(TAG, summary)
            appendTaskLog(summary)
            notifyCompleted()
            Result.success(Unit)
        } catch (e: CancellationException) {
            Log.d(TAG, "Resume from history cancelled")
            appendTaskLog("任务已取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Throwable) {
            com.hippo.util.ExceptionUtils.throwIfFatal(e)
            Log.e(TAG, "Resume from history failed", e)
            appendTaskLog("任务失败: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }

    override fun isPausable(): Boolean = false

    override suspend fun cancel() {
        isCancelled = true
        notifyCancelled()
    }

    /**
     * 把一条历史记录转换成恢复候选：目录仍在时补上 pages / pToken / 文件数，
     * 目录已消失时只保留 gid / token，走完整下载。
     *
     * 目录里的 `.ehviewer` 属于别的 gid 时，以磁盘内容为准（历史记录是陈旧的）：
     * 否则会拿这条历史去补元数据，把别的画廊的 `.ehviewer` 覆盖掉。
     */
    private fun buildCandidate(history: DownloadHistory): RestoreCandidate? {
        val dirname = history.filePath?.takeIf { it.isNotEmpty() } ?: ""
        val dir = findLocalDir(dirname)

        var gid = history.gid
        var token = history.token?.takeIf { it.isNotEmpty() }
        var pages = 0
        var hashes: Set<String> = emptySet()
        var fileCount = 0
        var modifiedAt = 0L
        // 磁盘上的 gid 与历史不一致时，这条历史不再适用于该目录
        var historyMatchesDir = true

        if (dir != null) {
            fileCount = RestoreSupport.countImageFiles(dir)
            modifiedAt = dir.lastModified()

            val meta = EhviewerMetaParser.parse(dir)
            if (meta != null) {
                if (meta.gid != history.gid) {
                    historyMatchesDir = false
                    gid = meta.gid
                    appendTaskLog(
                        "目录 $dirname 的 .ehviewer 属于 GID ${meta.gid}，" +
                            "与历史记录的 GID ${history.gid} 不符，按磁盘内容恢复"
                    )
                }
                if (token.isNullOrEmpty()) token = meta.token.takeIf { it.isNotEmpty() }
                pages = meta.pages
                hashes = meta.hashes.toHashSet()
            }

            if (token.isNullOrEmpty() || pages <= 0) {
                try {
                    val cached = GalleryCacheManager.getInstance(context)
                        .readGalleryCache(gid, dir)
                    if (cached != null) {
                        if (token.isNullOrEmpty()) token = cached.token?.takeIf { it.isNotEmpty() }
                        if (pages <= 0) pages = cached.pages
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "读取画廊缓存失败: GID=$gid", e)
                }
            }
        }

        return RestoreCandidate(
            gid = gid,
            dirname = dirname,
            dir = dir,
            token = token,
            title = if (historyMatchesDir) history.title else null,
            pages = pages,
            hashes = hashes,
            fileCount = fileCount,
            modifiedAt = modifiedAt
        ).apply {
            payload = if (historyMatchesDir) history else null
        }
    }

    /** 目录存在且有非元数据文件才算“本地已有” */
    private fun findLocalDir(dirname: String): UniFile? {
        if (dirname.isEmpty()) return null
        val root = downloadRoot ?: return null
        val dir = root.findFile(dirname) ?: return null
        if (!dir.isDirectory) return null
        val files = dir.listFiles() ?: return null
        if (files.isEmpty()) return null
        for (file in files) {
            val name = file.name
            if (file.isFile && name != null && !name.startsWith(".")) {
                return dir
            }
        }
        return null
    }

    /**
     * 构建下载项基础信息：历史字段 + 本地缓存补充（缩略图、分类、页数等）。
     */
    private fun buildGalleryInfo(candidate: RestoreCandidate, history: DownloadHistory?): GalleryInfo {
        val info = GalleryInfo()
        info.gid = candidate.gid
        info.token = candidate.token ?: history?.token ?: ""
        info.title = history?.title
        info.titleJpn = history?.titleJpn
        info.pages = candidate.pages

        val dir = candidate.dir ?: return info
        try {
            val cached = GalleryCacheManager.getInstance(context)
                .readGalleryCache(candidate.gid, dir) ?: return info
            if (info.thumb.isNullOrEmpty()) info.thumb = cached.thumb
            if (info.category == 0 && cached.category != 0) info.category = cached.category
            if (info.pages <= 0 && cached.pages > 0) info.pages = cached.pages
            if (info.title.isNullOrEmpty()) info.title = cached.title
            if (info.titleJpn.isNullOrEmpty()) info.titleJpn = cached.titleJpn
            if (info.posted.isNullOrEmpty()) info.posted = cached.posted
            if (info.uploader.isNullOrEmpty()) info.uploader = cached.uploader
            if (info.rating == 0f && cached.rating > 0) info.rating = cached.rating
            if (info.simpleLanguage.isNullOrEmpty()) info.simpleLanguage = cached.simpleLanguage
            if (info.simpleTags == null) info.simpleTags = cached.simpleTags
            if (info.tgList == null) info.tgList = cached.tgList
        } catch (e: Exception) {
            Log.w(TAG, "读取画廊缓存失败: GID=${candidate.gid}", e)
        }
        return info
    }

    private fun logRejections(rejected: List<RestoreRejection>) {
        if (rejected.isEmpty()) return
        val byReason = rejected.groupBy { it.reason }
        for ((reason, list) in byReason) {
            appendTaskLog("跳过 ${list.size} 项：${describeReason(reason)}")
            list.take(5).forEach { rejection ->
                appendTaskLog(
                    "  · ${rejection.candidate.dirname.ifEmpty { rejection.candidate.gid.toString() }}" +
                        "（GID: ${rejection.candidate.gid}）" +
                        (rejection.detail?.let { " — $it" } ?: "")
                )
            }
            if (list.size > 5) {
                appendTaskLog("  · …另有 ${list.size - 5} 项，详见任务日志")
            }
        }
    }

    private fun describeReason(reason: RestoreRejectReason): String = when (reason) {
        RestoreRejectReason.DIR_CLAIMED_BY_OTHER -> "目录已被其他画廊占用"
        RestoreRejectReason.GID_MAPPED_ELSEWHERE -> "该画廊已有其他目录"
        RestoreRejectReason.ALREADY_DOWNLOADING -> "已在下载队列"
        RestoreRejectReason.DUPLICATE_OF_OTHER -> "同一画廊的重复目录"
        RestoreRejectReason.INCLUDED_BY_OTHER -> "被更完整的画廊递进包含"
        RestoreRejectReason.NO_TOKEN -> "缺少 token，无法联网修复元数据"
        RestoreRejectReason.METADATA_REPAIR_FAILED -> "联网修复元数据失败"
        RestoreRejectReason.METADATA_INCOMPLETE -> "元数据仍不齐全"
    }

    /** 获取成功添加的数量 */
    fun getTotalAdded(): Int = totalAdded

    /** 获取失败的数量 */
    fun getTotalFailed(): Int = totalFailed
}
