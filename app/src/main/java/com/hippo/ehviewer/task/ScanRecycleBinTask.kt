package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.R
import com.hippo.ehviewer.local.LocalGalleryManager
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class ScanRecycleBinTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "ScanRecycleBinTask"
    }

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false

    /** 本阶段扫描到的目录总数；由扫描回调填充，0 表示没有目录。 */
    private var totalProgress = 0

    override fun getTaskId(): String = "scan_recycle_bin"

    override fun getTaskName(): String = context.getString(R.string.recycle_bin_scan_task_name)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.SCAN

    override fun getTaskDescription(): String? = context.getString(R.string.recycle_bin_scan_task_summary)

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            Log.d(TAG, "Start recycle bin scan")
            appendTaskLog("开始扫描回收站")
            coroutineContext[Job]?.ensureActive()

            val manager = LocalGalleryManager.getInstance(context)
            // 与本地画廊扫描同样的修复：直接执行同步扫描并接收逐目录进度回调，
            // 不再依赖 scanLocalGalleries(true) 里那套永远不执行的监听等待。
            val results = manager.scanRecycleBinSync(
                LocalGalleryManager.ScanProgressCallback { current, total, name ->
                    reportProgress(manager, current, total, name)
                },
            )

            if (isCancelled) {
                throw CancellationException("Task cancelled")
            }

            manager.reportRecycleBinScanComplete(results)

            appendTaskLog(context.getString(R.string.recycle_bin_scan_dirs_log, totalProgress))
            appendTaskLog(context.getString(R.string.recycle_bin_scan_complete, results.size))
            notifyCompleted()
            Result.success(Unit)
        } catch (e: CancellationException) {
            appendTaskLog("任务已取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Recycle bin scan failed", e)
            appendTaskLog("扫描失败: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }

    /**
     * 扫描回调：上报后台任务进度，并同步给界面监听器。
     * 回调可能来自多个扫描线程，进度写入需要串行化。
     *
     * @return false 表示取消扫描
     */
    private fun reportProgress(
        manager: LocalGalleryManager,
        current: Int,
        total: Int,
        name: String,
    ): Boolean {
        while (isPaused && !isCancelled) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                isCancelled = true
            }
        }
        if (isCancelled) return false

        totalProgress = total
        val detail = if (total > 0 && current > 0) {
            context.getString(R.string.recycle_bin_scan_progress, current, total, name)
        } else {
            name
        }
        synchronized(this) {
            if (total > 0 && current > 0) {
                updateProgress(current, total, detail)
            } else {
                updateProgress(-1, detail)
            }
        }
        manager.reportScanProgress(
            LocalGalleryManager.ScanProgress(current, total, name, true)
        )
        return true
    }

    override fun isPausable(): Boolean = true

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

    // 故意不覆写 getProgressDetail()：
    // 基类会返回 updateProgress 传进去的那句「扫描进度 12/57 - 名称」，
    // 覆写成 "$currentProgress/$totalProgress" 只会把百分比和总数混在一起。
}
