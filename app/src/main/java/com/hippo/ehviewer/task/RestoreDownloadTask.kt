package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.parser.ComicInfoParser
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.ehviewer.Settings
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.unifile.UniFile
import com.hippo.util.ExceptionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.coroutines.coroutineContext

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

            val restoreItemList = mutableListOf<RestoreItem>()
            var scannedCount = 0

            for (file in files) {
                if (isCancelled) {
                    throw CancellationException("任务已取消")
                }

                val restoreItem = getRestoreItem(file)
                if (restoreItem != null) {
                    restoreItemList.add(restoreItem)
                }
                scannedCount++
                if (scannedCount % 50 == 0) {
                    updateProgress(scannedCount, total, "已扫描 $scannedCount/$total")
                    appendTaskLog("已扫描 $scannedCount/$total，发现 ${restoreItemList.size} 个可恢复项")
                }
            }

            appendTaskLog("扫描完成，发现 ${restoreItemList.size} 个可恢复的下载项")

            if (restoreItemList.isEmpty()) {
                notifyCompleted()
                return Result.success(Unit)
            }

            updateProgress(-1, "正在获取画廊信息...")
            appendTaskLog("正在从API获取 ${restoreItemList.size} 个画廊的详细信息...")

            coroutineContext[Job]?.ensureActive()

            val galleryInfoList = mutableListOf<GalleryInfo>()
            galleryInfoList.addAll(restoreItemList)

            try {
                // 分批容错：单批失败只记日志，不丢掉已经补上的信息
                EhEngine.fillGalleryListByApiBestEffort(
                    null,
                    httpClient,
                    ArrayList(galleryInfoList),
                    EhUrl.getReferer()
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to fill gallery list", e)
                appendTaskLog("获取画廊信息失败: ${e.message}")
                notifyError(e)
                return Result.failure(e)
            }

            var successCount = 0
            var failCount = 0
            val resultTotal = restoreItemList.size

            for ((index, item) in restoreItemList.withIndex()) {
                if (isCancelled) {
                    throw CancellationException("任务已取消")
                }

                if (item.title.isNullOrEmpty()) {
                    // 画廊已下架或接口没返回标题时，用目录名兜底，避免整个项目被丢弃
                    item.title = item.dirname?.takeIf { it.isNotEmpty() }
                        ?: item.gid.toString()
                }
                try {
                    downloadManager.addDownload(item, null)
                    EhDB.putDownloadDirname(item.gid, item.dirname)
                    successCount++
                } catch (e: Exception) {
                    failCount++
                    appendTaskLog("添加下载失败: ${item.title} (GID: ${item.gid}): ${e.message}")
                }
                updateProgress(index + 1, resultTotal, "已处理 ${index + 1}/$resultTotal")
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
            Log.e(TAG, "Restore failed", e)
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
     * 从下载子目录取出 gid 和 token。
     * 先读文本 .ehviewer，失败再读同一文件的 CBOR，仍失败则读 ComicInfo.xml。
     * 目录无效、三种来源都没有 gid/token，或该画廊已在下载列表中时返回 null。
     */
    private fun getRestoreItem(file: UniFile): RestoreItem? {
        if (!file.isDirectory) return null

        var gid = -1L
        var token: String? = null
        val siFile = file.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
        if (siFile != null) {
            val spiderInfo = SpiderInfo.readHeader(siFile) ?: SpiderInfo.readCborHeader(siFile)
            if (spiderInfo != null && spiderInfo.gid >= 0 && !spiderInfo.token.isNullOrEmpty()) {
                gid = spiderInfo.gid
                token = spiderInfo.token
            }
        }
        if (gid < 0L || token.isNullOrEmpty()) {
            val comicInfo = readComicInfo(file)
            if (comicInfo != null && comicInfo.gid >= 0 && !comicInfo.token.isNullOrEmpty()) {
                gid = comicInfo.gid
                token = comicInfo.token
            }
        }
        if (gid < 0L || token.isNullOrEmpty()) {
            return null
        }
        if (downloadManager.containDownloadInfo(gid)) {
            return null
        }
        return RestoreItem().apply {
            this.gid = gid
            this.token = token
            this.dirname = file.name
        }
    }

    /**
     * 读取目录中的 ComicInfo.xml，并从 Web 地址解析 gid 和 token。
     * 文件不存在、超过大小上限或地址无法解析时返回 null。
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
    }
}
