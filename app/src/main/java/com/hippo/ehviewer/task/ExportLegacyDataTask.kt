package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.util.ReadableTime
import java.io.File
import kotlinx.coroutines.delay

class ExportLegacyDataTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "ExportLegacyDataTask"
    }

    private val taskId = "export_legacy_data_${System.currentTimeMillis()}"

    override fun getTaskId(): String = taskId

    override fun getTaskName(): String = context.getString(R.string.settings_advanced_export_legacy_option)

    override fun getTaskDescription(): String? = context.getString(R.string.settings_advanced_export_data_summary)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.EXPORT

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            updateProgress(0, "准备导出旧版数据...")
            appendTaskLog("开始导出旧版数据（DB格式）")
            delay(300)

            updateProgress(20, "正在导出数据库...")
            val dir = AppConfig.getExternalDataDir()
            if (dir == null) {
                val error = Exception("无法获取外部数据目录")
                appendTaskLog("导出失败: 无法获取外部数据目录")
                notifyError(error)
                return Result.failure(error)
            }

            val file = File(dir, "legacy-${ReadableTime.getFilenamableTime(System.currentTimeMillis())}.db")
            appendTaskLog("导出目标: %s", file.absolutePath)

            updateProgress(50, "正在复制数据库...")
            val success = EhDB.exportLegacyDB(context, file)

            if (success) {
                updateProgress(100, "旧版数据导出成功: ${file.path}")
                appendTaskLog("旧版数据导出成功: %s", file.absolutePath)
                notifyCompleted()
                Result.success(Unit)
            } else {
                val error = Exception("导出旧版数据库失败")
                appendTaskLog("导出失败: EhDB.exportLegacyDB 返回 false")
                notifyError(error)
                Result.failure(error)
            }
        } catch (e: Exception) {
            Log.e(TAG, "导出旧版数据失败", e)
            appendTaskLog("导出失败: %s", e.message ?: "")
            notifyError(e)
            Result.failure(e)
        }
    }
}
