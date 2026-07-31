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
            val latch = java.util.concurrent.CountDownLatch(1)
            val recycleResults = java.util.concurrent.atomic.AtomicReference<List<com.hippo.ehviewer.client.data.LocalGalleryInfo>>() 

            val listener = object : LocalGalleryManager.LocalGalleryListener {
                override fun onScanStart() {
                    appendTaskLog("扫描开始")
                }

                override fun onScanProgress(current: String) {
                    if (isCancelled) return
                    val progressDetail = context.getString(R.string.recycle_bin_scan_progress_simple, 0, 0)
                    updateProgress(-1, progressDetail)
                }

                override fun onScanComplete(localGalleries: List<com.hippo.ehviewer.client.data.LocalGalleryInfo>, recycleBinGalleries: List<com.hippo.ehviewer.client.data.LocalGalleryInfo>) {
                    recycleResults.set(recycleBinGalleries)
                    appendTaskLog("扫描完成，发现 ${recycleBinGalleries.size} 个回收站画廊")
                    latch.countDown()
                }

                override fun onGalleryDeleted(gallery: com.hippo.ehviewer.client.data.LocalGalleryInfo, success: Boolean) {
                    // no-op
                }

                override fun onGalleryRestored(gallery: com.hippo.ehviewer.client.data.LocalGalleryInfo, success: Boolean) {
                    // no-op
                }
            }

            manager.addListener(listener)
            try {
                manager.scanLocalGalleries(true)
                while (!latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (isCancelled) {
                        throw CancellationException("Task cancelled")
                    }
                }
            } finally {
                manager.removeListener(listener)
            }

            val results = recycleResults.get() ?: manager.getCachedRecycleBinGalleries()
            if (isCancelled) {
                throw CancellationException("Task cancelled")
            }

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

    override fun getProgressDetail(): String? {
        return if (totalProgress > 0) "$currentProgress/$totalProgress" else null
    }
}
