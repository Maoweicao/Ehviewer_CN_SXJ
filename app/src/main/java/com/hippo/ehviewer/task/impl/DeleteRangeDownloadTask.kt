package com.hippo.ehviewer.task.impl

import android.content.Context
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.GalleryFileHelper
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.task.BackgroundTask
import com.hippo.ehviewer.task.RedundancyCleaner
import com.hippo.ehviewer.task.TaskState
import com.hippo.ehviewer.transfer.core.ResponseCache
import com.hippo.lib.yorozuya.collect.LongList
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 批量删除下载后台任务。
 *
 * 两步流水线（画廊记录已由前台立即移除）：
 *  1. 写入 Download_history 表（recordDownloadDeleted，DELETION_NORMAL）
 *  2. 清理本地文件：
 *     - deleteFiles=true：复用清理冗余逻辑，扫描下载目录永久删除孤儿目录
 *     - deleteFiles=false：把本批次画廊目录移入回收站（.recycle_bin）
 */
class DeleteRangeDownloadTask(
    context: Context,
    private val downloadManager: DownloadManager,
    private val gidList: LongList,
    private val deleteFiles: Boolean,
    private val onCompletedCallback: Runnable? = null
) : BaseBackgroundTask(context) {

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false

    override fun getTaskId(): String {
        // 时间戳 + 自增序号，避免同一毫秒内多次提交导致 taskId 冲突
        val seq = sTaskSeq.incrementAndGet()
        return "delete_range_download_${System.currentTimeMillis()}_$seq"
    }

    private companion object {
        val sTaskSeq = java.util.concurrent.atomic.AtomicLong(0)
    }

    override fun getTaskName(): String = context.getString(R.string.download_remove_dialog_title)

    override fun getTaskDescription(): String? = context.getString(R.string.download_remove_dialog_title)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.CLEANUP

    override fun isPausable(): Boolean = true

    override suspend fun execute(): Result<Unit> {
        val total = gidList.size()
        if (total <= 0) {
            notifyCompleted()
            onCompletedCallback?.run()
            return Result.success(Unit)
        }

        return try {
            updateState(TaskState.RUNNING)
            appendTaskLog("开始删除 $total 个下载记录")

            // 第一步：写入 Download_history + 解析下载目录（须在删除记录前完成）
            // 注意：画廊记录已由前台立即移除（DownloadDeleteHelper.submitDeleteTask）
            val dirs = ArrayList<UniFile?>(total)
            for (i in 0 until total) {
                checkCancelledAndPaused()
                val gid = gidList.get(i)
                val info = downloadManager.getDownloadInfo(gid)
                if (info != null) {
                    try {
                        EhDB.recordDownloadDeleted(info, 0L)
                    } catch (e: Exception) {
                        appendTaskLog("写入下载历史失败 gid=$gid: ${e.message}")
                    }
                }
                dirs.add(resolveDir(info))
                updateProgress(i + 1, total, "写入下载历史 ${i + 1}/$total")
            }

            // 第二步：清理本地文件
            if (deleteFiles) {
                appendTaskLog("开始清理本地文件")
                RedundancyCleaner.cleanOrphanedGalleries(
                    context = context,
                    // 记录已全部删除，进度保持 100%，通过进度文本展示清理阶段
                    onProgress = { scanned, t, cleaned, _, _ ->
                        updateProgress(100, "清理本地文件 $scanned/$t（已清理 $cleaned）")
                    },
                    onLog = { appendTaskLog(it) },
                    isCancelled = { isCancelled },
                    isPaused = { isPaused }
                )
            } else {
                // 仅把本批次画廊目录移入回收站
                for (i in 0 until total) {
                    checkCancelledAndPaused()
                    val dir = dirs[i]
                    if (dir == null || !dir.isDirectory) {
                        continue
                    }
                    if (!GalleryFileHelper.moveToRecycleBin(dir)) {
                        appendTaskLog("移入回收站失败: ${dir.name}")
                    }
                    updateProgress(100, "移入回收站 ${i + 1}/$total")
                }
            }

            appendTaskLog("删除任务完成")
            // 使 Web API 的画廊/收藏列表缓存失效
            try {
                ResponseCache.getInstance().invalidateGalleries()
            } catch (e: Exception) {
                appendTaskLog("刷新缓存失败: ${e.message}")
            }
            notifyCompleted()
            onCompletedCallback?.run()
            Result.success(Unit)
        } catch (e: CancellationException) {
            appendTaskLog("删除任务已取消")
            notifyCancelled()
            onCompletedCallback?.run()
            Result.failure(e)
        } catch (e: Exception) {
            appendTaskLog("删除任务出错: ${e.message}")
            notifyError(e)
            onCompletedCallback?.run()
            Result.failure(e)
        }
    }

    private suspend fun checkCancelledAndPaused() {
        if (isCancelled) {
            throw CancellationException("任务已取消")
        }
        coroutineContext[Job]?.ensureActive()
        while (isPaused) {
            delay(100)
        }
    }

    private fun resolveDir(info: DownloadInfo?): UniFile? {
        if (info == null) {
            return null
        }
        return try {
            SpiderDen.getExistingGalleryDownloadDir(info)
        } catch (e: Exception) {
            appendTaskLog("解析下载目录失败 gid=${info.gid}: ${e.message}")
            null
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
        notifyCancelled()
    }

    override fun getProgressDetail(): String? = _progressDetail
}
