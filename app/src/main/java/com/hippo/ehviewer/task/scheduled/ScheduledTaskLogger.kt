package com.hippo.ehviewer.task.scheduled

import android.content.Context
import android.net.Uri
import android.util.Log
import com.hippo.ehviewer.AppConfig
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务日志记录器
 */
class ScheduledTaskLogger(private val context: Context) {
    companion object {
        private const val TAG = "ScheduledTaskLogger"
        private const val LOG_DIR_NAME = "ScheduledTaskLogs"
        private const val LOG_FILE_PREFIX = "task_log_"
        private const val LOG_FILE_SUFFIX = ".json"
        private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024  // 10MB
        private const val MAX_LOG_FILES = 10
        private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }

    private val logDir = AppConfig.getDirInExternalAppDir(LOG_DIR_NAME)
    private val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())

    data class LogEntry(
        val timestamp: Long,
        val taskId: String,
        val taskName: String,
        val level: LogLevel,
        val message: String,
        val details: String? = null
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("timestamp", timestamp)
                put("taskId", taskId)
                put("taskName", taskName)
                put("level", level.name)
                put("message", message)
                put("details", details)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): LogEntry {
                return LogEntry(
                    timestamp = json.optLong("timestamp", 0),
                    taskId = json.optString("taskId", ""),
                    taskName = json.optString("taskName", ""),
                    level = try {
                        LogLevel.valueOf(json.optString("level", LogLevel.INFO.name))
                    } catch (e: Exception) {
                        LogLevel.INFO
                    },
                    message = json.optString("message", ""),
                    details = runCatching { json.getString("details") }.getOrNull()
                )
            }
        }
    }

    enum class LogLevel {
        INFO, WARN, ERROR, DEBUG
    }

    fun logTaskStarted(task: ScheduledTask) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            taskId = task.id,
            taskName = task.taskDisplayName,
            level = LogLevel.INFO,
            message = "任务开始执行"
        )
        writeLog(entry)
    }

    fun logTaskCompleted(task: ScheduledTask, success: Boolean, duration: Long) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            taskId = task.id,
            taskName = task.taskDisplayName,
            level = if (success) LogLevel.INFO else LogLevel.ERROR,
            message = if (success) "任务执行成功" else "任务执行失败",
            details = "耗时: ${formatDuration(duration)}"
        )
        writeLog(entry)
    }

    fun logTaskFailed(task: ScheduledTask, error: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            taskId = task.id,
            taskName = task.taskDisplayName,
            level = LogLevel.ERROR,
            message = "任务执行失败",
            details = error
        )
        writeLog(entry)
    }

    fun logTaskRetried(task: ScheduledTask, retryCount: Int) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            taskId = task.id,
            taskName = task.taskDisplayName,
            level = LogLevel.WARN,
            message = "任务重试",
            details = "重试次数: $retryCount"
        )
        writeLog(entry)
    }

    fun logTaskCancelled(task: ScheduledTask) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            taskId = task.id,
            taskName = task.taskDisplayName,
            level = LogLevel.WARN,
            message = "任务已取消"
        )
        writeLog(entry)
    }

    fun logConditionMet(task: ScheduledTask, condition: DelayCondition) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            taskId = task.id,
            taskName = task.taskDisplayName,
            level = LogLevel.INFO,
            message = "延时条件满足",
            details = "条件类型: ${condition.name}"
        )
        writeLog(entry)
    }

    fun logConditionCheck(task: ScheduledTask, condition: DelayCondition, met: Boolean) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            taskId = task.id,
            taskName = task.taskDisplayName,
            level = LogLevel.DEBUG,
            message = "检查延时条件",
            details = "条件类型: ${condition.name}, 满足: $met"
        )
        writeLog(entry)
    }

    fun getTaskLogs(taskId: String): List<LogEntry> {
        return getAllLogs().filter { it.taskId == taskId }
    }

    fun getAllLogs(): List<LogEntry> {
        if (logDir == null) return emptyList()

        val logs = mutableListOf<LogEntry>()
        val logFiles = getLogFiles()

        logFiles.forEach { file ->
            try {
                val content = file.readText()
                val lines = content.lines().filter { it.isNotBlank() }
                lines.forEach { line ->
                    try {
                        val json = JSONObject(line)
                        logs.add(LogEntry.fromJson(json))
                    } catch (e: Exception) {
                        // 忽略解析错误的行
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read log file: ${file.name}", e)
            }
        }

        return logs.sortedByDescending { it.timestamp }
    }

    fun clearLogs() {
        if (logDir == null) return

        try {
            getLogFiles().forEach { it.delete() }
            Log.d(TAG, "Cleared all logs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }

    fun exportLogs(uri: Uri) {
        try {
            val logs = getAllLogs()
            val jsonArray = org.json.JSONArray()
            logs.forEach { log ->
                jsonArray.put(log.toJson())
            }
            
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonArray.toString(2).toByteArray())
            }
            
            Log.d(TAG, "Exported ${logs.size} logs to $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
        }
    }

    private fun writeLog(entry: LogEntry) {
        if (logDir == null) {
            Log.w(TAG, "Log directory not available")
            return
        }

        try {
            val logFile = getCurrentLogFile()
            FileWriter(logFile, true).use { writer ->
                writer.append(entry.toJson().toString())
                writer.append("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    private fun getCurrentLogFile(): File {
        if (logDir == null) {
            val dir = File(context.filesDir, LOG_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "${LOG_FILE_PREFIX}current${LOG_FILE_SUFFIX}")
        }

        val files = getLogFiles()
        val currentFile = files.firstOrNull()
        
        return if (currentFile != null && currentFile.length() < MAX_LOG_FILE_SIZE) {
            currentFile
        } else {
            // 轮转日志文件
            rotateLogs()
            val timestamp = System.currentTimeMillis()
            File(logDir, "${LOG_FILE_PREFIX}${timestamp}${LOG_FILE_SUFFIX}")
        }
    }

    private fun getLogFiles(): List<File> {
        if (logDir == null) return emptyList()

        return logDir.listFiles { file ->
            file.isFile && file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_SUFFIX)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun rotateLogs() {
        val files = getLogFiles()
        if (files.size >= MAX_LOG_FILES) {
            // 删除最旧的文件
            files.takeLast(files.size - MAX_LOG_FILES + 1).forEach { it.delete() }
        }
    }

    private fun formatDuration(durationMillis: Long): String {
        val seconds = durationMillis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}小时${minutes % 60}分钟${seconds % 60}秒"
            minutes > 0 -> "${minutes}分钟${seconds % 60}秒"
            else -> "${seconds}秒"
        }
    }
}
