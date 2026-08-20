package com.hippo.ehviewer.task

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.util.ReadableTime
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 导出指定数据库文件任务（复制 .db 文件）
 * 复用 ExportDatabaseTask 的分块复制与进度逻辑，可导出任意内部数据库。
 */
class ExportDatabaseFileTask(
    context: Context,
    private val dbName: String
) : BaseBackgroundTask(context), ExportFileResult {

    companion object {
        private const val TAG = "ExportDatabaseFileTask"
        private const val BUFFER_SIZE = 8192
    }

    private val taskId = "export_db_file_${System.currentTimeMillis()}_$dbName"

    /** 导出的成品文件路径（任务完成后读取用于分享） */
    @Volatile
    override var exportedFile: File? = null
        private set

    override fun getTaskId(): String = taskId

    override fun getTaskName(): String = context.getString(R.string.database_viewer_export_db_task)

    override fun getTaskDescription(): String? = context.getString(R.string.database_viewer_export_db_summary, dbName)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.EXPORT

    /** 导出任务无需互斥等待，避免因已有活跃唯一任务被静默拒绝 */
    override fun isUniqueTask(): Boolean = false

    override fun getMutexGroup(): String? = null

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            updateProgress(0, "准备导出数据库...")
            appendTaskLog("开始导出数据库文件: %s", dbName)
            delay(200)

            val dbFile = context.getDatabasePath(dbName)
            if (dbFile == null || !dbFile.exists()) {
                val error = Exception("数据库文件不存在: $dbName")
                appendTaskLog("导出失败: 数据库文件 %s 不存在", dbName)
                notifyError(error)
                return Result.failure(error)
            }

            // 先做 WAL checkpoint，把未落盘的数据合并进主库文件，保证导出快照一致
            try {
                val walDb = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
                try {
                    walDb.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                } finally {
                    walDb.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "WAL checkpoint failed, exporting raw main file", e)
            }

            val exportDir = AppConfig.getExternalDataDir()
            if (exportDir == null) {
                val error = Exception("无法获取外部数据目录")
                appendTaskLog("导出失败: 无法获取外部数据目录")
                notifyError(error)
                return Result.failure(error)
            }

            val targetName = "ehviewer_db_${dbName}_${ReadableTime.getFilenamableTime(System.currentTimeMillis())}.db"
            val targetFile = File(exportDir, targetName)
            appendTaskLog("目标文件: %s", targetFile.absolutePath)

            val totalSize = dbFile.length()
            var copiedSize = 0L

            updateProgress(10, "正在复制数据库文件...")

            FileInputStream(dbFile).use { fis ->
                FileOutputStream(targetFile).use { fos ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var count: Int
                    while (fis.read(buffer).also { count = it } > 0) {
                        coroutineContext.ensureActive()
                        fos.write(buffer, 0, count)
                        copiedSize += count
                        if (totalSize > 0) {
                            val progress = 10 + (copiedSize * 80 / totalSize).toInt()
                            val mbCopied = copiedSize / (1024 * 1024)
                            val mbTotal = totalSize / (1024 * 1024)
                            updateProgress(progress, "正在复制: ${mbCopied}MB / ${mbTotal}MB")
                        }
                    }
                }
            }

            exportedFile = targetFile
            updateProgress(100, "数据库导出成功: ${targetFile.path}")
            appendTaskLog("数据库导出成功: %s", targetFile.absolutePath)
            notifyCompleted()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "导出数据库失败", e)
            appendTaskLog("导出失败: %s", e.message ?: "")
            notifyError(e)
            Result.failure(e)
        }
    }
}