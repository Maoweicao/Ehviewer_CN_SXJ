package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.unifile.UniFile
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

class ProgressiveBackupTask @JvmOverloads constructor(
    context: Context,
    private val chains: List<ProgressiveScanTask.ProgressiveChain>,
    private val taskId: String = "progressive_backup_${System.currentTimeMillis()}"
) : BaseBackgroundTask(context) {

    private val backupFileNames = mutableListOf<String>()

    fun getBackupFileNames(): List<String> = backupFileNames

    override fun getTaskId(): String = taskId

    override fun getTaskName(): String = context.getString(R.string.progressive_backup_task_name)

    override fun getTaskDescription(): String = context.getString(R.string.progressive_backup_task_desc, chains.size)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.BACKUP

    override fun isUniqueTask(): Boolean = false

    override fun isPausable(): Boolean = false

    override fun isPersistable(): Boolean = false

    companion object {
        private const val TAG = "ProgressiveBackupTask"
    }

    override suspend fun execute(): Result<Unit> {
        return try {
            val backupDir = com.hippo.ehviewer.AppConfig.getProgressiveBackupDir()
            if (backupDir == null) {
                appendTaskLog("ERROR: Cannot create backup directory")
                notifyError(IllegalStateException("Backup directory unavailable"))
                return Result.failure(IllegalStateException("Backup directory unavailable"))
            }
            if (!backupDir.exists()) backupDir.mkdirs()

            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val zipFileName = "backup_$timestamp.zip"
            val zipFile = File(backupDir, zipFileName)

            val allGids = mutableSetOf<Long>()
            for (chain in chains) {
                for (folder in chain.folders) {
                    allGids.add(folder.gid)
                }
            }

            appendTaskLog("Backing up ${allGids.size} galleries to $zipFileName")
            updateProgress(0, "Preparing backup...")

            val addedEntries = mutableSetOf<String>()
            var zos: ZipOutputStream? = null
            var processedCount = 0
            val totalGalleries = allGids.size

            try {
                zos = ZipOutputStream(zipFile.outputStream())

                for (gid in allGids) {
                    ensureNotCancelled()
                    processedCount++

                    val info = EhDB.getDownloadInfo(gid)
                    if (info == null) {
                        appendTaskLog("Skip: gid=$gid has no download record")
                        continue
                    }

                    val dir = SpiderDen.getGalleryDownloadDir(info)
                    if (dir == null || !dir.exists() || !dir.isDirectory) {
                        appendTaskLog("Skip: gid=$gid directory not found")
                        continue
                    }

                    val folderName = sanitizeFolderName(info.title ?: gid.toString())
                    addDirToZip(dir, folderName, zos, addedEntries)

                    val pct = processedCount * 90 / maxOf(totalGalleries, 1)
                    updateProgress(pct, "Backing up: ${info.title ?: gid} ($processedCount/$totalGalleries)")
                }

                zos.close()
                zos = null

                val finalSize = zipFile.length()
                backupFileNames.add(zipFile.absolutePath)
                appendTaskLog("Backup complete: $zipFileName (${formatSize(finalSize)})")
                updateProgress(100, "Backup complete: ${backupFileNames.size} file(s)")

                notifyCompleted()
                Result.success(Unit)
            } catch (e: Throwable) {
                try { zos?.close() } catch (_: Exception) {}
                if (zipFile.exists()) zipFile.delete()
                throw e
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled()
                return Result.failure(e)
            }
            appendTaskLog("ERROR: %s", e.message ?: "Unknown error")
            notifyError(e)
            Result.failure(e)
        }
    }

    private suspend fun ensureNotCancelled() {
        coroutineContext.ensureActive()
    }

    private fun addDirToZip(
        dir: UniFile,
        basePath: String,
        zos: ZipOutputStream,
        addedEntries: MutableSet<String>
    ) {
        if (dir.isDirectory) {
            val dirPath = if (basePath.endsWith("/")) basePath else "$basePath/"
            if (addedEntries.add(dirPath)) {
                zos.putNextEntry(ZipEntry(dirPath))
                zos.closeEntry()
            }
            val children = dir.listFiles() ?: return
            for (child in children) {
                val childPath = if (dirPath.isEmpty()) child.name ?: "" else dirPath + (child.name ?: "")
                addDirToZip(child, childPath, zos, addedEntries)
            }
        } else if (dir.isFile) {
            if (!addedEntries.add(basePath)) return
            zos.putNextEntry(ZipEntry(basePath))
            dir.openInputStream()?.use { input ->
                BufferedInputStream(input).use { bis ->
                    val buffer = ByteArray(8192)
                    var count: Int
                    while (bis.read(buffer).also { count = it } != -1) {
                        zos.write(buffer, 0, count)
                    }
                }
            }
            zos.closeEntry()
        }
    }

    private fun sanitizeFolderName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> String.format(java.util.Locale.US, "%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
        }
    }
}
