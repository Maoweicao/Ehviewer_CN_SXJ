package com.hippo.ehviewer.task.automation

import android.content.Context
import android.net.Uri
import android.util.Log
import com.hippo.ehviewer.AppConfig
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 自动化任务日志：复用 ScheduledTaskLogger 的日志存储与轮转结构，类型换成 AutomationTask。
 */
class AutomationLogger(private val context: Context) {
    companion object {
        private const val TAG = "AutomationLogger"
        private const val LOG_DIR_NAME = "AutomationLogs"
        private const val LOG_FILE_PREFIX = "automation_log_"
        private const val LOG_FILE_SUFFIX = ".json"
        private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024
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
        val details: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("timestamp", timestamp)
            put("taskId", taskId)
            put("taskName", taskName)
            put("level", level.name)
            put("message", message)
            put("details", details)
        }

        companion object {
            fun fromJson(json: JSONObject): LogEntry = LogEntry(
                timestamp = json.optLong("timestamp", 0),
                taskId = json.optString("taskId", ""),
                taskName = json.optString("taskName", ""),
                level = runCatching { LogLevel.valueOf(json.optString("level", LogLevel.INFO.name)) }
                    .getOrDefault(LogLevel.INFO),
                message = json.optString("message", ""),
                details = if (json.isNull("details")) null else json.optString("details"),
            )
        }
    }

    enum class LogLevel { INFO, WARN, ERROR, DEBUG }

    fun logTriggered(task: AutomationTask, details: String? = null) {
        writeLog(LogEntry(System.currentTimeMillis(), task.id, task.name, LogLevel.INFO, "自动化已触发", details))
    }

    fun logStarted(task: AutomationTask) {
        writeLog(LogEntry(System.currentTimeMillis(), task.id, task.name, LogLevel.INFO, "动作开始执行"))
    }

    fun logCompleted(task: AutomationTask, success: Boolean, durationMillis: Long) {
        val entry = LogEntry(
            System.currentTimeMillis(),
            task.id,
            task.name,
            if (success) LogLevel.INFO else LogLevel.ERROR,
            if (success) "动作执行成功" else "动作执行失败",
            "耗时: ${formatDuration(durationMillis)}",
        )
        writeLog(entry)
    }

    fun logFailed(task: AutomationTask, error: String) {
        writeLog(LogEntry(System.currentTimeMillis(), task.id, task.name, LogLevel.ERROR, "执行失败", error))
    }

    fun logRetried(task: AutomationTask, retryCount: Int) {
        writeLog(LogEntry(System.currentTimeMillis(), task.id, task.name, LogLevel.WARN, "任务重试", "重试次数: $retryCount"))
    }

    fun logConditionsBlocked(task: AutomationTask, reasons: List<String>) {
        writeLog(LogEntry(System.currentTimeMillis(), task.id, task.name, LogLevel.DEBUG, "条件未满足，跳过", reasons.joinToString(",")))
    }

    fun logEventReceived(type: EventTriggerType, taskId: String) {
        writeLog(LogEntry(System.currentTimeMillis(), taskId, type.name, LogLevel.DEBUG, "事件已接收", type.displayNameResId.toString()))
    }

    fun getTaskLogs(taskId: String): List<LogEntry> = getAllLogs().filter { it.taskId == taskId }

    fun getAllLogs(): List<LogEntry> {
        val dir = logDir ?: return emptyList()
        val logs = mutableListOf<LogEntry>()
        getLogFiles().forEach { file ->
            try {
                file.readLines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    runCatching { LogEntry.fromJson(JSONObject(line)) }.getOrNull()?.let(logs::add)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read log file: ${file.name}", e)
            }
        }
        return logs.sortedByDescending { it.timestamp }
    }

    fun clearLogs() {
        logDir ?: return
        runCatching { getLogFiles().forEach { it.delete() } }
            .onFailure { Log.e(TAG, "Failed to clear logs", it) }
    }

    fun exportLogs(uri: Uri) {
        try {
            val arr = org.json.JSONArray()
            getAllLogs().forEach { arr.put(it.toJson()) }
            context.contentResolver.openOutputStream(uri)?.use { it.write(arr.toString(2).toByteArray()) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
        }
    }

    private fun writeLog(entry: LogEntry) {
        val dir = logDir ?: return
        try {
            val file = getCurrentLogFile()
            FileWriter(file, true).use { writer ->
                writer.append(entry.toJson().toString())
                writer.append("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    private fun getCurrentLogFile(): File {
        val dir = logDir
        if (dir == null) {
            val fallback = File(context.filesDir, LOG_DIR_NAME).apply { if (!exists()) mkdirs() }
            return File(fallback, "${LOG_FILE_PREFIX}current${LOG_FILE_SUFFIX}")
        }
        val files = getLogFiles()
        val current = files.firstOrNull()
        return if (current != null && current.length() < MAX_LOG_FILE_SIZE) {
            current
        } else {
            rotateLogs()
            File(dir, "${LOG_FILE_PREFIX}${System.currentTimeMillis()}${LOG_FILE_SUFFIX}")
        }
    }

    private fun getLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith(LOG_FILE_PREFIX) && f.name.endsWith(LOG_FILE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun rotateLogs() {
        val files = getLogFiles()
        if (files.size >= MAX_LOG_FILES) {
            files.takeLast(files.size - MAX_LOG_FILES + 1).forEach { it.delete() }
        }
    }

    private fun formatDuration(durationMillis: Long): String {
        val seconds = durationMillis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h${minutes % 60}m${seconds % 60}s"
            minutes > 0 -> "${minutes}m${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}