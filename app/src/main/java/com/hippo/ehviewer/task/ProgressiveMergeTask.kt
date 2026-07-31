package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.coroutineContext

class ProgressiveMergeTask @JvmOverloads constructor(
    context: Context,
    private val targetGid: Long,
    private val sourceGids: List<Long>,
    private val taskId: String = "progressive_merge_${System.currentTimeMillis()}"
) : BaseBackgroundTask(context) {

    private var copiedCount = 0
    private var deletedCount = 0
    private var errorCount = 0
    private var lastError = ""

    private val imageExtensions = setOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp")

    override fun getTaskId(): String = taskId

    override fun getTaskName(): String = context.getString(R.string.progressive_merge_task_name)

    override fun getTaskDescription(): String = context.getString(R.string.progressive_merge_task_desc, sourceGids.size)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.MERGE

    override fun isUniqueTask(): Boolean = false

    override fun isPausable(): Boolean = false

    override fun isPersistable(): Boolean = false

    companion object {
        private const val TAG = "ProgressiveMergeTask"
    }

    override suspend fun execute(): Result<Unit> {
        return try {
            Log.i(TAG, "Starting merge: targetGid=$targetGid, sourceGids=$sourceGids")
            updateProgress(0, context.getString(R.string.progressive_merge_preparing))

            ensureNotCancelled()

            val targetInfo = EhDB.getDownloadInfo(targetGid)
            if (targetInfo == null) {
                lastError = "Target download record not found: gid=$targetGid"
                appendTaskLog("ERROR: %s", lastError)
                notifyError(IllegalStateException(lastError))
                return Result.failure(IllegalStateException(lastError))
            }

            val targetDir = SpiderDen.getGalleryDownloadDir(targetInfo)
            if (targetDir == null || !targetDir.exists() || !targetDir.isDirectory) {
                lastError = "Target directory not found: gid=$targetGid"
                appendTaskLog("ERROR: %s", lastError)
                notifyError(IllegalStateException(lastError))
                return Result.failure(IllegalStateException(lastError))
            }

            val targetName = targetDir.name ?: targetGid.toString()
            Log.d(TAG, "Target folder: $targetName (gid=$targetGid)")
            appendTaskLog("Target: %s (gid=%d)", targetName, targetGid)

            ensureNotCancelled()
            updateProgress(5, "Collecting source folders...")

            val sourceFolders = mutableListOf<SourceFolder>()
            for (gid in sourceGids) {
                ensureNotCancelled()
                val info = EhDB.getDownloadInfo(gid)
                if (info == null) {
                    appendTaskLog("Skip: gid=%d has no download record", gid)
                    continue
                }
                val dir = SpiderDen.getGalleryDownloadDir(info)
                if (dir == null || !dir.exists() || !dir.isDirectory) {
                    Log.w(TAG, "Source directory not found: gid=$gid")
                    appendTaskLog("Skip: gid=%d directory not found", gid)
                    continue
                }
                val name = dir.name ?: gid.toString()
                Log.d(TAG, "Source folder: $name (gid=$gid)")
                sourceFolders.add(SourceFolder(gid = gid, info = info, dir = dir, name = name))
            }

            if (sourceFolders.isEmpty()) {
                Log.w(TAG, "No valid source folders to merge for target gid=$targetGid")
                appendTaskLog("No valid source folders to merge")
                updateProgress(100, "Skipped: no valid sources")
                notifyCompleted()
                return Result.success(Unit)
            }

            val totalSteps = sourceFolders.size
            var step = 0

            ensureNotCancelled()
            updateProgress(10, "Merging content...")

            val targetMeta = parseEhviewerMeta(targetDir)
            val mergedHashes = targetMeta?.hashes?.toMutableSet()
            for (source in sourceFolders) {
                ensureNotCancelled()
                step++
                val pct = 10 + (step * 70 / totalSteps)
                updateProgress(pct, "Merging: ${source.name}")
                Log.i(TAG, "Merging source: ${source.name} (gid=${source.gid}) -> target gid=$targetGid")

                if (targetMeta != null && mergedHashes != null) {
                    val sourceMeta = parseEhviewerMeta(source.dir)
                    if (sourceMeta != null) {
                        mergeByHash(targetDir, mergedHashes, source.dir, sourceMeta)
                    } else {
                        mergeByMd5(targetDir, source.dir)
                    }
                } else {
                    mergeByMd5(targetDir, source.dir)
                }
            }

            ensureNotCancelled()
            updateProgress(80, "Deleting source folders...")

            for (source in sourceFolders) {
                ensureNotCancelled()
                if (deleteRecursively(source.dir)) {
                    EhDB.removeDownloadDirname(source.gid)
                    EhDB.removeDownloadInfo(source.gid)
                    deletedCount++
                    Log.i(TAG, "Deleted source: ${source.name} (gid=${source.gid}), removed DB records")
                    appendTaskLog("Deleted: %s (gid=%d)", source.name, source.gid)
                } else {
                    errorCount++
                    Log.e(TAG, "Failed to delete source: ${source.name} (gid=${source.gid})")
                    appendTaskLog("ERROR: Failed to delete %s", source.name)
                }
            }

            // Update target download record with new page count
            if (copiedCount > 0) {
                try {
                    targetInfo.pages = targetInfo.pages + copiedCount
                    targetInfo.finished = targetInfo.finished + copiedCount
                    EhDB.putDownloadInfo(targetInfo)
                    Log.i(TAG, "Updated target DownloadInfo: gid=$targetGid pages=${targetInfo.pages}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update target DownloadInfo", e)
                }
            }

            Log.i(TAG, "Merge complete: targetGid=$targetGid, copied=$copiedCount, deleted=$deletedCount, errors=$errorCount")
            updateProgress(100, "Done: copied $copiedCount, deleted $deletedCount, errors $errorCount")
            appendTaskLog("Merge complete: copied=%d, deleted=%d, errors=%d", copiedCount, deletedCount, errorCount)
            notifyCompleted()
            Result.success(Unit)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled()
                return Result.failure(e)
            }
            lastError = e.message ?: "Unknown error"
            appendTaskLog("ERROR: %s", lastError)
            notifyError(e)
            Result.failure(e)
        }
    }

    private suspend fun ensureNotCancelled() {
        coroutineContext.ensureActive()
    }

    private fun mergeByHash(
        targetDir: com.hippo.unifile.UniFile,
        mergedHashes: MutableSet<String>,
        sourceDir: com.hippo.unifile.UniFile,
        sourceMeta: EhviewerMeta
    ) {
        var maxIndex = getMaxEhviewerIndex(targetDir)
        var currentIndex = maxIndex + 1

        val hashToFile = buildHashToFileMap(sourceDir, sourceMeta)
        val newEntries = mutableListOf<Pair<Int, String>>()

        for ((hashVal, sourceFile) in hashToFile) {
            if (mergedHashes.contains(hashVal)) continue

            val ext = extensionOf(sourceFile.name)
            val newName = String.format(Locale.US, "%08d%s", currentIndex + 1, ext)
            val targetFile = createUniqueFile(targetDir, newName)
            if (targetFile == null || !copyFile(sourceFile, targetFile)) {
                errorCount++
                continue
            }
            newEntries.add(currentIndex to hashVal)
            mergedHashes.add(hashVal)
            currentIndex++
            copiedCount++
        }

        if (newEntries.isNotEmpty()) {
            appendToEhviewer(targetDir, newEntries)
        }
    }

    private fun getMaxEhviewerIndex(targetDir: com.hippo.unifile.UniFile): Int {
        val file = targetDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return -1
        var maxIdx = -1
        var reader: BufferedReader? = null
        try {
            reader = java.io.BufferedReader(java.io.InputStreamReader(file.openInputStream(), StandardCharsets.UTF_8))
            var lineCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineCount++
                if (lineCount <= 8) continue
                val sepIdx = line.indexOf(' ')
                if (sepIdx <= 0) continue
                val idx = line.substring(0, sepIdx).toIntOrNull() ?: continue
                if (idx > maxIdx) maxIdx = idx
            }
        } catch (_: Exception) {
        } finally {
            closeQuietly(reader)
        }
        return maxIdx
    }

    private fun mergeByMd5(targetDir: com.hippo.unifile.UniFile, sourceDir: com.hippo.unifile.UniFile) {
        val targetMd5s = collectImageMd5(targetDir)
        val files = sourceDir.listFiles() ?: return

        for (file in files) {
            if (file == null || !file.isFile) continue
            val name = file.name ?: continue
            if (name == SpiderQueen.SPIDER_INFO_FILENAME) continue

            if (isImageFile(name)) {
                val md5 = md5Of(file) ?: continue
                if (targetMd5s.containsKey(md5)) continue
                val targetFile = createUniqueFile(targetDir, name)
                if (targetFile == null || !copyFile(file, targetFile)) {
                    errorCount++
                } else {
                    targetMd5s[md5] = targetFile.name
                    copiedCount++
                }
            } else {
                if (targetDir.findFile(name) != null) continue
                val targetFile = targetDir.createFile(name)
                if (targetFile == null || !copyFile(file, targetFile)) {
                    errorCount++
                } else {
                    copiedCount++
                }
            }
        }
    }

    private fun buildHashToFileMap(
        dir: com.hippo.unifile.UniFile,
        meta: EhviewerMeta
    ): Map<String, com.hippo.unifile.UniFile> {
        val result = mutableMapOf<String, com.hippo.unifile.UniFile>()
        val imageFiles = getSortedImageFiles(dir)
        for ((idx, hashVal) in meta.files) {
            if (idx >= 0 && idx < imageFiles.size) {
                result[hashVal] = imageFiles[idx]
            }
        }
        return result
    }

    private fun getSortedImageFiles(dir: com.hippo.unifile.UniFile): List<com.hippo.unifile.UniFile> {
        val files = mutableListOf<com.hippo.unifile.UniFile>()
        val children = dir.listFiles() ?: return files
        for (child in children) {
            if (child != null && child.isFile && isImageFile(child.name)) {
                files.add(child)
            }
        }
        files.sortWith { a, b ->
            val an = a.name
            val bn = b.name
            when {
                an == null && bn == null -> 0
                an == null -> 1
                bn == null -> -1
                else -> an.compareTo(bn, ignoreCase = true)
            }
        }
        return files
    }

    private fun appendToEhviewer(
        targetDir: com.hippo.unifile.UniFile,
        entries: List<Pair<Int, String>>
    ): Boolean {
        val ehFile = targetDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return false
        var os: OutputStream? = null
        return try {
            os = ehFile.openOutputStream(true)
            for ((idx, hashVal) in entries) {
                val line = "\n$idx $hashVal"
                os?.write(line.toByteArray(StandardCharsets.UTF_8))
            }
            os?.flush()
            true
        } catch (_: Exception) {
            false
        } finally {
            closeQuietly(os)
        }
    }

    private fun collectImageMd5(dir: com.hippo.unifile.UniFile): MutableMap<String, String?> {
        val map = mutableMapOf<String, String?>()
        val files = dir.listFiles() ?: return map
        for (file in files) {
            if (file != null && file.isFile && isImageFile(file.name)) {
                val md5 = md5Of(file)
                if (md5 != null) map[md5] = file.name
            }
        }
        return map
    }

    private fun copyFile(source: com.hippo.unifile.UniFile, target: com.hippo.unifile.UniFile): Boolean {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        return try {
            inputStream = BufferedInputStream(source.openInputStream())
            outputStream = target.openOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = inputStream.read(buffer)
                if (count == -1) break
                outputStream?.write(buffer, 0, count)
            }
            outputStream?.flush()
            true
        } catch (_: Exception) {
            false
        } finally {
            closeQuietly(inputStream)
            closeQuietly(outputStream)
        }
    }

    private fun createUniqueFile(
        dir: com.hippo.unifile.UniFile,
        preferredName: String
    ): com.hippo.unifile.UniFile? {
        var name = preferredName
        val base = baseName(name)
        val ext = extensionOf(name)
        var suffix = 1
        while (dir.findFile(name) != null && suffix <= 1000) {
            name = "${base}_$suffix$ext"
            suffix++
        }
        return dir.createFile(name)
    }

    private fun deleteRecursively(file: com.hippo.unifile.UniFile?): Boolean {
        if (file == null || !file.exists()) return true
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursively(child)) return false
                }
            }
        }
        return file.delete()
    }

    private fun parseEhviewerMeta(dir: com.hippo.unifile.UniFile): EhviewerMeta? {
        val file = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
        if (file == null || !file.exists() || !file.isFile) return null

        var inputStream: InputStream? = null
        var reader: BufferedReader? = null
        return try {
            val meta = EhviewerMeta()
            inputStream = file.openInputStream()
            reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            val lines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                lines.add(line)
            }
            if (lines.size < 3) return null

            meta.gid = safeTrim(lines[1])
            meta.token = safeTrim(lines[2])
            for (i in 3 until lines.size) {
                val parts = lines[i].trim().split(Regex("\\s+"))
                if (parts.size < 2) continue
                try {
                    val idx = parts[0].toInt()
                    val hashVal = parts[1]
                    meta.files[idx] = hashVal
                    meta.hashes.add(hashVal)
                } catch (_: NumberFormatException) {
                }
            }
            meta
        } catch (_: Exception) {
            null
        } finally {
            closeQuietly(reader)
            closeQuietly(inputStream)
        }
    }

    private fun md5Of(file: com.hippo.unifile.UniFile): String? {
        var inputStream: InputStream? = null
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            inputStream = file.openInputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
            val hash = digest.digest()
            val sb = StringBuilder(hash.size * 2)
            for (b in hash) {
                sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                sb.append(Character.forDigit(b.toInt() and 0xF, 16))
            }
            sb.toString()
        } catch (_: Exception) {
            null
        } finally {
            closeQuietly(inputStream)
        }
    }

    private fun isImageFile(name: String?): Boolean {
        if (name == null) return false
        val ext = extensionOf(name).lowercase(Locale.ROOT)
        return imageExtensions.contains(ext)
    }

    private fun extensionOf(name: String?): String {
        if (name == null) return ""
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot) else ""
    }

    private fun baseName(name: String?): String {
        if (name == null) return "file"
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(0, dot) else name
    }

    private fun safeTrim(value: String?): String = value?.trim() ?: ""

    private fun closeQuietly(closeable: AutoCloseable?) {
        if (closeable == null) return
        try {
            closeable.close()
        } catch (_: Exception) {
        }
    }

    private data class SourceFolder(
        val gid: Long,
        val info: com.hippo.ehviewer.dao.DownloadInfo,
        val dir: com.hippo.unifile.UniFile,
        val name: String
    )

    class EhviewerMeta {
        var gid: String = ""
        var token: String = ""
        val files: MutableMap<Int, String> = mutableMapOf()
        val hashes: MutableSet<String> = mutableSetOf()
    }
}
