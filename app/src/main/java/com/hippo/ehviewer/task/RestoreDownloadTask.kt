package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.parser.ComicInfoParser
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.unifile.UniFile
import com.hippo.util.ExceptionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import kotlin.coroutines.coroutineContext

/**
 * 扫描下载目录，把本地已有文件但不在下载列表里的画廊恢复成下载任务。
 *
 * 恢复前统一走 [RestoreCandidateValidator] 的四步过滤：
 * ① 目录映射占用 → ④ 下载管理器已有 → ② 递进包含 → ③ 元数据齐全（必要时联网重建）。
 * 只有全部通过的候选才会进入下载队列，避免恢复出重复/被包含的副本。
 */
class RestoreDownloadTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "RestoreDownloadTask"
    }

    @Volatile private var isCancelled = false

    private val downloadManager: DownloadManager = EhApplication.getDownloadManager(context)
    private val httpClient: OkHttpClient = EhApplication.getOkHttpClient(context)

    override fun getTaskId(): String = "restore_download"

    override fun getTaskName(): String = context.getString(R.string.settings_download_restore_download_items)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.IMPORT

    override fun getTaskDescription(): String? = context.getString(R.string.settings_download_restore_download_items_summary)

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            appendTaskLog(context.getString(R.string.settings_download_restore_started))

            coroutineContext[Job]?.ensureActive()

            val dir = Settings.getDownloadLocation()
            if (dir == null || !dir.isDirectory) {
                appendTaskLog("下载目录不存在或无效")
                notifyCompleted()
                return Result.success(Unit)
            }

            val files = dir.listFiles()
            if (files.isNullOrEmpty()) {
                appendTaskLog("下载目录为空")
                notifyCompleted()
                return Result.success(Unit)
            }

            val total = files.size
            appendTaskLog("扫描到 $total 个文件夹")

            // ── 阶段一：解析目录，构建候选 ──
            val candidates = ArrayList<RestoreCandidate>()
            var scanned = 0
            for (file in files) {
                if (isCancelled) throw CancellationException("任务已取消")
                val candidate = buildCandidate(file)
                if (candidate != null) candidates.add(candidate)
                scanned++
                if (scanned % 50 == 0) {
                    updateProgress(scanned, total * 3, "已扫描 $scanned/$total")
                    appendTaskLog("已扫描 $scanned/$total，识别出 ${candidates.size} 个画廊目录")
                }
            }
            appendTaskLog("扫描完成：$total 个文件夹中识别出 ${candidates.size} 个画廊目录")

            if (candidates.isEmpty()) {
                notifyCompleted()
                return Result.success(Unit)
            }

            // ── 阶段二：四步过滤 ──
            updateProgress(total, total * 3, "校验 ${candidates.size} 个候选…")
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
                notifyCompleted()
                return Result.success(Unit)
            }

            // ── 阶段三：补全画廊信息（只处理通过的候选） ──
            updateProgress(total * 2, total * 3, "正在获取画廊信息…")
            appendTaskLog("正在从 API 补全 ${accepted.size} 个画廊的信息…")
            coroutineContext[Job]?.ensureActive()

            val items = ArrayList<RestoreItem>(accepted.size)
            for (candidate in accepted) {
                val item = candidate.payload as? RestoreItem ?: RestoreItem().apply {
                    gid = candidate.gid
                    token = candidate.token
                    dirname = candidate.dirname
                }
                item.gid = candidate.gid
                item.token = candidate.token ?: item.token
                item.pages = candidate.pages
                item.candidate = candidate
                items.add(item)
            }

            try {
                EhEngine.fillGalleryListByApiBestEffort(
                    null,
                    httpClient,
                    ArrayList<GalleryInfo>(items),
                    EhUrl.getReferer()
                )
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
                Log.e(TAG, "Failed to fill gallery list", e)
                appendTaskLog("获取画廊信息失败: ${e.message}")
            }

            // ── 阶段四：写入下载队列 ──
            var successCount = 0
            var failCount = 0
            for ((index, item) in items.withIndex()) {
                if (isCancelled) throw CancellationException("任务已取消")
                val candidate = item.candidate
                if (candidate == null) {
                    failCount++
                    continue
                }
                if (item.title.isNullOrEmpty()) {
                    item.title = candidate.title?.takeIf { it.isNotEmpty() }
                        ?: candidate.dirname.takeIf { it.isNotEmpty() }
                        ?: item.gid.toString()
                }

                if (!RestoreSupport.commit(downloadManager, item, candidate) { appendTaskLog(it) }) {
                    failCount++
                } else {
                    successCount++
                }

                updateProgress(
                    total * 2 + (index + 1), total * 3,
                    "已处理 ${index + 1}/${items.size}"
                )
            }

            appendTaskLog("恢复完成: 成功 $successCount，失败 $failCount")
            notifyCompleted()
            Result.success(Unit)
        } catch (e: CancellationException) {
            Log.d(TAG, "Restore cancelled")
            appendTaskLog("任务已取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            Log.e(TAG, "Restore failed", e)
            appendTaskLog("任务失败: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }

    private fun logRejections(rejected: List<RestoreRejection>) {
        if (rejected.isEmpty()) return
        val byReason = rejected.groupBy { it.reason }
        for ((reason, list) in byReason) {
            appendTaskLog("跳过 ${list.size} 项：${describeReason(reason)}")
            list.take(5).forEach { rejection ->
                appendTaskLog("  · ${rejection.candidate.dirname}（GID: ${rejection.candidate.gid}）" +
                    (rejection.detail?.let { " — $it" } ?: ""))
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

    override fun isPausable(): Boolean = false

    override suspend fun cancel() {
        isCancelled = true
        notifyCancelled()
    }

    /**
     * 解析一个下载子目录，得到 gid / token / pToken 集。
     * 依次尝试 .ehviewer（文本 + CBOR）、ComicInfo.xml，最后从目录名前缀取 gid。
     */
    private fun buildCandidate(file: UniFile): RestoreCandidate? {
        if (!file.isDirectory) return null
        val dirname = file.name ?: return null
        if (dirname.startsWith(".") || dirname == "thumb") return null

        var gid = -1L
        var token: String? = null
        var pages = 0
        var hashes: Set<String> = emptySet()

        val meta = EhviewerMetaParser.parse(file)
        if (meta != null) {
            gid = meta.gid
            token = meta.token
            pages = meta.pages
            hashes = meta.hashes.toHashSet()
        } else {
            val siFile = file.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
            if (siFile != null) {
                val spiderInfo = SpiderInfo.read(siFile) ?: SpiderInfo.readCborHeader(siFile)
                if (spiderInfo != null && spiderInfo.gid >= 0 && !spiderInfo.token.isNullOrEmpty()) {
                    gid = spiderInfo.gid
                    token = spiderInfo.token
                    pages = spiderInfo.pages
                    hashes = SpiderTokenUtils.toHashSet(spiderInfo)
                }
            }
        }

        if (gid < 0L || token.isNullOrEmpty()) {
            val comicInfo = readComicInfo(file)
            if (comicInfo != null && comicInfo.gid >= 0 && !comicInfo.token.isNullOrEmpty()) {
                gid = comicInfo.gid
                token = comicInfo.token
            }
        }

        if (gid < 0L) {
            gid = RestoreSupport.parseGidFromDirName(dirname) ?: return null
        }

        val candidate = RestoreCandidate(
            gid = gid,
            dirname = dirname,
            dir = file,
            token = token,
            pages = pages,
            hashes = hashes,
            fileCount = RestoreSupport.countImageFiles(file),
            modifiedAt = file.lastModified()
        )
        candidate.payload = RestoreItem().apply {
            this.gid = gid
            this.token = token
            this.dirname = dirname
        }
        return candidate
    }

    /**
     * 读取目录中的 ComicInfo.xml，并从 Web 地址解析 gid 和 token。
     */
    private fun readComicInfo(dir: UniFile): GalleryDetailUrlParser.Result? {
        val comicFile = dir.findFile(ComicInfoParser.FILENAME) ?: return null
        return try {
            comicFile.openInputStream().use { ComicInfoParser.parse(it) }
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            null
        }
    }

    private class RestoreItem : GalleryInfo() {
        var dirname: String? = null
        var candidate: RestoreCandidate? = null
    }
}
