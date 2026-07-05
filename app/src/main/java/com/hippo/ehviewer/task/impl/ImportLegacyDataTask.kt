package com.hippo.ehviewer.task.impl

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.task.BackgroundTask
import com.hippo.ehviewer.task.TaskState
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class ImportLegacyDataTask(
    context: Context,
    private val csvFile: File
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "ImportLegacyDataTask"
    }

    private val taskId = "import_legacy_data_${System.currentTimeMillis()}"

    override fun getTaskId(): String = taskId

    override fun getTaskName(): String = context.getString(R.string.settings_advanced_import_legacy_data)

    override fun getTaskDescription(): String? = context.getString(R.string.settings_advanced_import_legacy_data_summary)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.IMPORT

    private var successCount = 0
    private var failCount = 0
    private var skipCount = 0

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            updateProgress(0, "准备导入旧版数据列表...")
            appendTaskLog("开始导入旧版数据列表: %s", csvFile.name)

            if (!csvFile.exists() || !csvFile.isFile) {
                val error = Exception("文件不存在: ${csvFile.name}")
                appendTaskLog("错误: %s", error.message ?: "")
                notifyError(error)
                return Result.failure(error)
            }

            updateProgress(10, "正在读取文件...")
            val content = csvFile.readText(Charsets.UTF_8)
            val lines = content.split("\n")
            appendTaskLog("文件共 %d 行", lines.size)

            val galleryInfos = mutableListOf<GalleryInfo>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith(DownloadManager.DOWNLOAD_INFO_HEADER)) continue
                val gi = GalleryInfo.fromCSV(trimmed)
                if (gi != null) {
                    galleryInfos.add(gi)
                }
            }

            val total = galleryInfos.size
            appendTaskLog("解析到 %d 条下载记录", total)

            if (total == 0) {
                updateProgress(100, "没有可导入的下载记录")
                appendTaskLog("没有可导入的下载记录")
                notifyCompleted()
                return Result.success(Unit)
            }

            val downloadManager = EhApplication.getDownloadManager(context)
            successCount = 0
            failCount = 0
            skipCount = 0

            for ((index, gi) in galleryInfos.withIndex()) {
                coroutineContext.ensureActive()
                try {
                    if (downloadManager.getDownloadInfo(gi.gid) == null) {
                        downloadManager.addDownload(gi, null)
                        successCount++
                        appendTaskLog("导入成功: %s (GID: %d)", gi.title, gi.gid)
                    } else {
                        skipCount++
                        appendTaskLog("跳过已存在: %s (GID: %d)", gi.title, gi.gid)
                    }
                } catch (e: Exception) {
                    failCount++
                    appendTaskLog("导入失败: %s - %s", gi.title, e.message ?: "")
                }
                val progress = 20 + (index * 70 / total)
                updateProgress(progress, "正在导入: ${index + 1}/$total (成功:$successCount 跳过:$skipCount 失败:$failCount)")
            }

            val summary = "导入完成: 成功 $successCount, 跳过 $skipCount, 失败 $failCount"
            updateProgress(100, summary)
            appendTaskLog(summary)
            notifyCompleted()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "导入旧版数据列表失败", e)
            appendTaskLog("导入失败: %s", e.message ?: "")
            notifyError(e)
            Result.failure(e)
        }
    }
}
