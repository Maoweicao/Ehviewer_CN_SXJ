package com.hippo.ehviewer.task

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.util.ReadableTime
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 导出数据表为 CSV 文件任务
 * 复用 ExportDownloadItemsTask 的 CSV 写入模式，支持可选 WHERE 过滤，逐行流式写出。
 */
class ExportTableCsvTask(
    context: Context,
    private val dbName: String,
    private val tableName: String,
    private val whereClause: String?
) : BaseBackgroundTask(context), ExportFileResult {

    companion object {
        private const val TAG = "ExportTableCsvTask"
    }

    private val taskId = "export_table_csv_${System.currentTimeMillis()}_$tableName"

    /** 导出的成品文件路径（任务完成后读取用于分享） */
    @Volatile
    override var exportedFile: File? = null
        private set

    override fun getTaskId(): String = taskId

    override fun getTaskName(): String = context.getString(R.string.database_viewer_export_csv_task)

    override fun getTaskDescription(): String? = context.getString(R.string.database_viewer_export_csv_summary, tableName)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.EXPORT

    /** 导出任务无需互斥等待，避免因已有活跃唯一任务被静默拒绝 */
    override fun isUniqueTask(): Boolean = false

    override fun getMutexGroup(): String? = null

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            updateProgress(0, "准备导出数据表...")
            appendTaskLog("开始导出数据表: %s", tableName)
            delay(200)

            val exportDir = AppConfig.getExternalDataDir()
            if (exportDir == null) {
                val error = Exception("无法获取外部数据目录")
                appendTaskLog("导出失败: 无法获取外部数据目录")
                notifyError(error)
                return Result.failure(error)
            }

            val fileName = "ehviewer_${tableName}_${ReadableTime.getFilenamableTime(System.currentTimeMillis())}.csv"
            val targetFile = File(exportDir, fileName)
            appendTaskLog("目标文件: %s", targetFile.absolutePath)

            val where = whereClause?.takeIf { it.isNotBlank() }

            val db = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                // 总行数用于进度
                val countSql = "SELECT COUNT(*) FROM $tableName" + if (where != null) " WHERE ($where)" else ""
                var total = 0
                var c: Cursor? = null
                try {
                    c = db.rawQuery(countSql, null)
                    if (c.moveToFirst()) {
                        total = c.getInt(0)
                    }
                } finally {
                    c?.close()
                }

                val sql = "SELECT * FROM $tableName" + if (where != null) " WHERE ($where)" else ""
                appendTaskLog("查询语句: %s", sql)

                updateProgress(20, "正在写入数据...")
                val writer = BufferedWriter(FileWriter(targetFile, false))
                try {
                    var rowIndex = 0
                    var cursor: Cursor? = null
                    try {
                        cursor = db.rawQuery(sql, null)
                        val columnCount = cursor.columnCount
                        val columnNames = Array(columnCount) { cursor.getColumnName(it) }
                        // 表头
                        writer.write(columnNames.joinToString(",") { csvEscape(it) })
                        writer.newLine()

                        while (cursor.moveToNext()) {
                            coroutineContext.ensureActive()
                            val line = StringBuilder()
                            for (i in 0 until columnCount) {
                                if (i > 0) {
                                    line.append(',')
                                }
                                line.append(csvEscape(cursor.getString(i) ?: "NULL"))
                            }
                            writer.write(line.toString())
                            writer.newLine()
                            rowIndex++
                            if (total > 0) {
                                val progress = 20 + (rowIndex * 80 / total)
                                updateProgress(progress, "正在导出: $rowIndex/$total")
                            }
                        }
                    } finally {
                        cursor?.close()
                    }
                    writer.flush()
                } finally {
                    writer.close()
                }

                if (total == 0) {
                    updateProgress(100, "数据表为空，已导出空文件")
                    appendTaskLog("数据表为空")
                } else {
                    updateProgress(100, "数据表导出成功: ${targetFile.path}")
                }
            } finally {
                db.close()
            }

            exportedFile = targetFile
            appendTaskLog("导出成功: %s", targetFile.absolutePath)
            notifyCompleted()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "导出数据表失败", e)
            appendTaskLog("导出失败: %s", e.message ?: "")
            notifyError(e)
            Result.failure(e)
        }
    }

    private fun csvEscape(value: String): String {
        if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            return "\"" + value.replace("\"", "\"\"") + "\""
        }
        return value
    }
}