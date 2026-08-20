package com.hippo.ehviewer.task

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.DownloadLogger
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * 清理冗余文件任务
 * 扫描下载目录，删除不在下载记录中的文件。
 * 参考校验画廊完整性任务的思路：先以内存中的下载记录构建已知 gid 集合，
 * 再遍历磁盘条目做 O(1) 判断；进度与日志节流写入，避免每个条目都触发一次全量 JSON 持久化。
 */
class CleanRedundancyTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "CleanRedundancyTask"
        // 每隔多少个条目输出一条周期进度日志
        private const val LOG_INTERVAL = 50
    }

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false
    private var statusListener: StatusListener? = null

    private var totalProgress = 0
    private var scannedCount = 0
    private var cleanedCount = 0
    private var keptCount = 0
    private var ignoredCount = 0

    private fun notifyStatus() {
        statusListener?.onStatus(scannedCount, totalProgress, cleanedCount, keptCount, ignoredCount)
    }

    override fun getTaskId(): String = "clean_redundancy"

    override fun getTaskName(): String = context.getString(R.string.settings_download_clean_redundancy)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.CLEANUP

    override fun getTaskDescription(): String? = context.getString(R.string.settings_download_clean_redundancy_summary)

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "开始清理冗余文件")
            appendTaskLog("开始清理冗余文件")
            logBackgroundStart()

            val downloadManager = EhApplication.getDownloadManager(context)
            val downloadDir = Settings.getDownloadLocation()

            if (downloadDir == null || !downloadDir.isDirectory) {
                Log.w(TAG, "下载目录不存在")
                appendTaskLog("下载目录不存在")
                notifyError(Exception("下载目录不存在"))
                return Result.failure(Exception("下载目录不存在"))
            }

            totalProgress = 0
            scannedCount = 0
            cleanedCount = 0
            keptCount = 0
            ignoredCount = 0
            notifyStatus()

            // 复用共享的孤儿清理逻辑（永久删除不在下载记录中的本地画廊文件）
            val summary = RedundancyCleaner.cleanOrphanedGalleries(
                context = context,
                onProgress = { scanned, total, cleaned, kept, ignored ->
                    scannedCount = scanned
                    totalProgress = total
                    cleanedCount = cleaned
                    keptCount = kept
                    ignoredCount = ignored
                    updateProgress(scanned, total, "$scanned/$total")
                    notifyStatus()
                },
                onLog = { msg ->
                    Log.d(TAG, msg)
                    appendTaskLog(msg)
                },
                isCancelled = { isCancelled },
                isPaused = { isPaused }
            )
            scannedCount = summary.scanned
            cleanedCount = summary.cleaned
            keptCount = summary.kept
            ignoredCount = summary.ignored

            // 周期进度日志
            if (scannedCount >= LOG_INTERVAL) {
                appendTaskLog("已扫描 $scannedCount/$totalProgress，删除冗余 $cleanedCount 个")
            }

            val elapsed = System.currentTimeMillis() - startTime
            val summaryText = "清理完成: 扫描 ${summary.scanned} 个, 删除冗余 ${summary.cleaned} 个, 保留 ${summary.kept} 个, 忽略 ${summary.ignored} 个, 耗时 ${elapsed}ms"
            Log.d(TAG, summaryText)
            appendTaskLog(summaryText)
            logBackgroundComplete(elapsed, true, summaryText)
            notifyCompleted()
            notifyStatus()

            // 完成 Toast（沿用旧版本行为）
            val resultMessage = if (cleanedCount == 0) {
                context.getString(R.string.settings_download_clean_redundancy_no_redundancy)
            } else {
                context.getString(R.string.settings_download_clean_redundancy_done, cleanedCount)
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, resultMessage, Toast.LENGTH_SHORT).show()
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            Log.d(TAG, "清理任务被取消")
            appendTaskLog("清理任务被取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "清理任务出错", e)
            appendTaskLog("清理任务出错: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }

    private fun logBackgroundStart() {
        try {
            DownloadLogger.getInstance()
                .logBackgroundTaskStart(getTaskType().name, getTaskName())
        } catch (ignored: Exception) {
        }
    }

    private fun logBackgroundComplete(elapsed: Long, success: Boolean, summary: String) {
        try {
            val logger = DownloadLogger.getInstance()
            logger.logBackgroundTaskComplete(getTaskType().name, getTaskName(), elapsed, success)
            logger.log(DownloadLogger.LogLevel.INFO, TAG, summary, null, null)
        } catch (ignored: Exception) {
        }
    }

    override fun isPausable(): Boolean = true

    override suspend fun pause() {
        Log.d(TAG, "暂停清理任务")
        isPaused = true
        updateState(TaskState.PAUSED)
        appendTaskLog("任务已暂停")
    }

    override suspend fun resume() {
        Log.d(TAG, "恢复清理任务")
        isPaused = false
        updateState(TaskState.RUNNING)
        appendTaskLog("任务已恢复")
    }

    override suspend fun cancel() {
        Log.d(TAG, "取消清理任务")
        isCancelled = true
        isPaused = false
        notifyCancelled()
    }

    override fun getProgressDetail(): String? {
        return if (totalProgress > 0) {
            "$scannedCount/$totalProgress (清理: $cleanedCount, 保留: $keptCount, 忽略: $ignoredCount)"
        } else {
            null
        }
    }

    fun setStatusListener(listener: StatusListener?) {
        statusListener = listener
    }

    interface StatusListener {
        fun onStatus(current: Int, total: Int, cleaned: Int, kept: Int, ignored: Int)
    }

    /**
     * 同步执行任务（用于Java调用）
     */
    fun executeSync() {
        runBlocking {
            execute()
        }
    }

    /**
     * 获取清理的文件数量
     */
    fun getCleanedCount(): Int = cleanedCount
}
