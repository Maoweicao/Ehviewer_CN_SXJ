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

class ScanLocalGalleryTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "ScanLocalGalleryTask"
    }

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false

    /** 本阶段扫描到的目录总数；由扫描回调填充，0 表示没有目录。 */
    private var totalProgress = 0

    override fun getTaskId(): String = "scan_local_gallery"

    override fun getTaskName(): String = context.getString(R.string.local_gallery_scan_task_name)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.SCAN

    override fun getTaskDescription(): String? = context.getString(R.string.local_gallery_scan_task_summary)

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            Log.d(TAG, "Start local gallery scan")
            appendTaskLog("开始扫描本地画廊")
            coroutineContext[Job]?.ensureActive()

            val manager = LocalGalleryManager.getInstance(context)
            // 直接在本任务线程执行同步扫描。旧实现只是再次调用 scanLocalGalleries(true)，
            // 把任务又交回 Service，真正的目录扫描入口从未执行：进度回调永不触发，
            // 任务还会永远卡在 CountDownLatch 上，详情页也就完全没有进度。
            val results = manager.scanLocalGalleriesSync(
                LocalGalleryManager.ScanProgressCallback { current, total, name ->
                    reportProgress(manager, current, total, name)
                },
            )

            if (isCancelled) {
                throw CancellationException("Task cancelled")
            }

            // 写入缓存并通知所有 UI 监听器（本地画廊列表立即刷新）
            manager.reportLocalScanComplete(results)

            appendTaskLog(context.getString(R.string.local_gallery_scan_dirs_log, totalProgress))
            appendTaskLog(context.getString(R.string.local_gallery_scan_complete, results.size))
            notifyCompleted()
            Result.success(Unit)
        } catch (e: CancellationException) {
            appendTaskLog("任务已取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Local gallery scan failed", e)
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
        // 用户暂停时在此挂起；目录数量有限，轻量自旋足够
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
            context.getString(R.string.local_gallery_scan_progress, current, total, name)
        } else {
            name
        }
        synchronized(this) {
            if (total > 0 && current > 0) {
                updateProgress(current, total, detail)
            } else {
                // 还没拿到分母，退回不确定进度
                updateProgress(-1, detail)
            }
        }
        // 界面侧（本地画廊页的进度对话框）与后台任务详情共用同一份进度
        manager.reportScanProgress(
            LocalGalleryManager.ScanProgress(current, total, name, false)
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
