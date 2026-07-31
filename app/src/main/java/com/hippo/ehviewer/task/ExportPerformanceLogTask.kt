package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.R
import com.hippo.ehviewer.service.PerformanceMonitor
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import kotlinx.coroutines.delay

/**
 * 导出性能监测日志任务
 */
class ExportPerformanceLogTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "ExportPerfLogTask"
    }

    private val taskId = "export_perf_log_${System.currentTimeMillis()}"

    override fun getTaskId(): String = taskId

    override fun getTaskName(): String = context.getString(R.string.performance_log_export)

    override fun getTaskDescription(): String? = context.getString(R.string.settings_advanced_performance_monitor_log_summary)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.EXPORT

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            updateProgress(0, "准备导出性能日志...")
            appendTaskLog("开始导出性能监测日志")
            delay(200)

            updateProgress(50, "正在生成日志文件...")
            val file = PerformanceMonitor.exportToFile(context)

            if (file != null) {
                updateProgress(100, "导出成功: ${file.path}")
                appendTaskLog("性能日志导出成功: %s", file.absolutePath)
                notifyCompleted()
                Result.success(Unit)
            } else {
                val error = Exception("性能日志导出失败：无数据或目录不可用")
                appendTaskLog("导出失败: %s", error.message ?: "")
                notifyError(error)
                Result.failure(error)
            }
        } catch (e: Exception) {
            Log.e(TAG, "导出性能日志失败", e)
            appendTaskLog("导出失败: %s", e.message ?: "")
            notifyError(e)
            Result.failure(e)
        }
    }
}
