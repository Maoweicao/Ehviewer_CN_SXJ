package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.sync.GalleryTagsWriter
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 补全画廊标签缓存。
 *
 * 只处理 `Gallery_Tags` 里还没有标签的条目：
 *  - 已下载画廊：复用 [DownloadManager.repairGalleryInfo]（顺带补齐元数据）；
 *  - 本地收藏：直接用 token 拉一次画廊详情，只写标签。
 *
 * 不做云端收藏的全量扫描：那需要分页拉取整个收藏夹，代价远大于本任务，
 * 收藏夹的标签计数会退化为“基于已加载项”，界面上会标注。
 */
class BackfillGalleryTagsTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "BackfillTagsTask"

        /** 并发请求数；窗口不大，避免触发站点限流 */
        private const val CONCURRENT_REQUESTS = 4
    }

    private data class Target(
        val gid: Long,
        val token: String?,
        val isDownload: Boolean,
    )

    @Volatile
    private var cancelled = false

    private val downloadManager: DownloadManager = EhApplication.getDownloadManager(context)

    private var successCount = 0
    private var failedCount = 0
    private var skippedCount = 0

    override fun getTaskId(): String = "backfill_gallery_tags"

    override fun getTaskName(): String = context.getString(R.string.backfill_gallery_tags_title)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.UPDATE

    override fun getTaskDescription(): String? =
        context.getString(R.string.settings_advanced_backfill_gallery_tags_summary)

    /** 只读写数据库与网络，不碰下载目录，不需要和下载任务互斥。 */
    override fun getMutexGroup(): String? = null

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            appendTaskLog("开始补全画廊标签")

            val cached = try {
                EhDB.getAllGalleryTagGids()
            } catch (t: Throwable) {
                Log.w(TAG, "读取 Gallery_Tags 失败", t)
                emptySet<Long>()
            }

            val targets = ArrayList<Target>()
            val downloadGids = HashSet<Long>()

            val downloads: List<com.hippo.ehviewer.dao.DownloadInfo> =
                downloadManager.allDownloadInfoList?.toList().orEmpty()
            for (info in downloads) {
                downloadGids.add(info.gid)
                if (info.gid in cached) continue
                targets.add(Target(info.gid, info.token, true))
            }

            val localFavorites = try {
                EhDB.getAllLocalFavorites()
            } catch (t: Throwable) {
                Log.w(TAG, "读取本地收藏失败", t)
                emptyList()
            }
            for (fav in localFavorites) {
                if (fav.gid in downloadGids || fav.gid in cached) continue
                targets.add(Target(fav.gid, fav.token, false))
            }

            if (targets.isEmpty()) {
                appendTaskLog("没有缺少标签的画廊，无需补全")
                notifyCompleted()
                return Result.success(Unit)
            }

            appendTaskLog("需要补全 ${targets.size} 个画廊（下载 ${downloads.size} 个，本地收藏 ${localFavorites.size} 个）")
            val batches = targets.chunked(CONCURRENT_REQUESTS)
            var processed = 0

            for ((batchIndex, batch) in batches.withIndex()) {
                if (cancelled) throw CancellationException("任务已取消")
                coroutineContext[Job]?.ensureActive()

                val results = coroutineScope {
                    batch.map { target -> async { process(target) } }.awaitAll()
                }
                for (ok in results) {
                    processed++
                    if (ok) successCount++ else failedCount++
                }
                updateProgress(processed, targets.size,
                    "补全中 $processed/${targets.size} (成功: $successCount, 失败: $failedCount)")

                if (batchIndex < batches.size - 1) {
                    Thread.sleep(300)
                }
            }

            val summary = "补全完成: 成功 $successCount, 失败 $failedCount, 跳过 $skippedCount"
            appendTaskLog(summary)
            notifyCompleted()
            if (failedCount == 0) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(summary))
            }
        } catch (e: CancellationException) {
            appendTaskLog("补全任务已取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "补全标签任务出错", e)
            appendTaskLog("补全任务出错: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }

    private fun process(target: Target): Boolean {
        if (cancelled) return false
        return try {
            if (target.isDownload) {
                downloadManager.repairGalleryInfo(target.gid)
            } else {
                val url = EhUrl.getGalleryDetailUrl(target.gid, target.token)
                val detail = EhEngine.getGalleryDetail(
                    null, EhApplication.getOkHttpClient(context), url)
                if (detail != null) {
                    GalleryTagsWriter.saveFromGalleryInfo(detail)
                    true
                } else {
                    false
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "补全失败: gid=${target.gid}", t)
            false
        }
    }

    override fun isPausable(): Boolean = true

    override suspend fun pause() {
        // 本任务批次很短，暂停实现为取消当前轮次即可；保持与 RepairAll… 一致的状态切换
        updateState(TaskState.PAUSED)
        appendTaskLog("任务已暂停")
    }

    override suspend fun resume() {
        updateState(TaskState.RUNNING)
        appendTaskLog("任务已恢复")
    }

    override suspend fun cancel() {
        cancelled = true
        notifyCancelled()
    }
}
