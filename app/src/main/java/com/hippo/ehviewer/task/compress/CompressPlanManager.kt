package com.hippo.ehviewer.task.compress

import android.util.Log
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.unifile.UniFile
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile

object CompressPlanManager {

    private const val TAG = "CompressPlanManager"

    data class ScanResult(
        val sizeBytes: Long,
        val files: List<FileEntry>
    )

    /**
     * Build a full compress plan by scanning all selected galleries.
     */
    fun buildPlan(
        outputDir: UniFile,
        selectedList: List<DownloadInfo>,
        splitSizeBytes: Long,
        taskId: String
    ): CompressPlan? {
        val outputPath = getRealPath(outputDir) ?: return null
        val plan = CompressPlan(
            taskId = taskId,
            outputDir = outputPath,
            splitSizeBytes = splitSizeBytes,
            totalGalleries = selectedList.size
        )

        var partIndex = 1
        var currentPartSize = 0L
        val baseName = "ehviewer_${System.currentTimeMillis()}"

        val allEntries = mutableListOf<CompressGalleryEntry>()

        // Phase 1: Scan all galleries
        for (info in selectedList) {
            val galleryDir = SpiderDen.getGalleryDownloadDir(info)
            if (galleryDir == null || !galleryDir.exists()) {
                Log.w(TAG, "Gallery ${info.gid} dir not found, skipping")
                continue
            }

            val scanResult = fastScanDir(galleryDir)
            val folderName = sanitizeFileName(info.title ?: info.gid.toString())
            val sizeBytes = scanResult?.sizeBytes ?: 0L
            val files = scanResult?.files ?: emptyList()
            val fileCount = if (files.isNotEmpty()) files.size else galleryDir.listFiles()?.size ?: 0

            val entry = CompressGalleryEntry(
                gid = info.gid,
                title = info.title ?: info.gid.toString(),
                folderName = folderName,
                fileCount = fileCount,
                sizeBytes = sizeBytes,
                files = files.toMutableList()
            )
            allEntries.add(entry)
        }

        if (allEntries.isEmpty()) {
            Log.w(TAG, "No valid galleries found for plan")
            return null
        }

        // Phase 2: Split into parts
        var currentPart = createPart(partIndex, baseName, outputPath)
        for (entry in allEntries) {
            val gallerySize = if (entry.sizeBytes > 0) entry.sizeBytes else 1L
            if (splitSizeBytes > 0 && currentPartSize > 0 && currentPartSize + gallerySize > splitSizeBytes) {
                plan.parts.add(currentPart)
                partIndex++
                currentPartSize = 0L
                currentPart = createPart(partIndex, baseName, outputPath)
            }
            currentPart.galleries.add(entry)
            currentPartSize += gallerySize
        }
        if (currentPart.galleries.isNotEmpty()) {
            plan.parts.add(currentPart)
        }

        plan.state = PlanState.PENDING
        Log.i(TAG, "Plan built: ${plan.totalGalleries} galleries in ${plan.parts.size} parts")
        return plan
    }

    private fun createPart(partIndex: Int, baseName: String, outputPath: String): CompressPart {
        val zipFileName = if (partIndex <= 1) "$baseName.zip" else "$baseName-part$partIndex.zip"
        return CompressPart(
            partIndex = partIndex,
            zipFileName = zipFileName,
            zipFilePath = "$outputPath/$zipFileName"
        )
    }

    /**
     * Load an existing plan from disk.
     */
    fun loadPlan(taskId: String): CompressPlan? {
        val planDir = AppConfig.getCompressPlanDir() ?: return null
        val planFile = File(planDir, "$taskId.json")
        if (!planFile.exists()) return null

        return try {
            val json = JSONObject(planFile.readText())
            CompressPlan.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plan $taskId", e)
            null
        }
    }

    /**
     * Save plan to disk atomically (write .tmp then rename).
     */
    fun savePlan(plan: CompressPlan): Boolean {
        val planDir = AppConfig.getCompressPlanDir() ?: return false
        if (!planDir.exists()) planDir.mkdirs()

        plan.updatedAt = System.currentTimeMillis()
        val planFile = File(planDir, "${plan.taskId}.json")
        val tmpFile = File(planDir, "${plan.taskId}.json.tmp")

        return try {
            tmpFile.writeText(plan.toJson().toString(2))
            tmpFile.renameTo(planFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save plan ${plan.taskId}", e)
            try { tmpFile.delete() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Mark a gallery as completed and update plan atomically.
     */
    fun markGalleryCompleted(plan: CompressPlan, gid: Long) {
        for (part in plan.parts) {
            for (gallery in part.galleries) {
                if (gallery.gid == gid && gallery.state == PlanItemState.IN_PROGRESS) {
                    gallery.state = PlanItemState.COMPLETED
                    plan.completedGalleries++
                    savePlan(plan)
                    return
                }
            }
        }
    }

    /**
     * Mark a part and all its galleries as corrupt, requiring re-compress.
     */
    fun markPartCorrupt(plan: CompressPlan, part: CompressPart) {
        part.state = PlanItemState.CORRUPT
        for (gallery in part.galleries) {
            if (gallery.state == PlanItemState.COMPLETED) {
                gallery.state = PlanItemState.PENDING
                plan.completedGalleries--
            }
        }
        // Delete the corrupt ZIP
        try { File(part.zipFilePath).delete() } catch (_: Exception) {}
        savePlan(plan)
    }

    /**
     * Verify a ZIP file's integrity by comparing entry count with the plan.
     */
    fun verifyZipIntegrity(part: CompressPart): Boolean {
        val zipFile = File(part.zipFilePath)
        if (!zipFile.exists()) {
            Log.w(TAG, "ZIP file missing: ${part.zipFilePath}")
            return false
        }

        return try {
            val expectedCount = part.galleries
                .filter { it.state == PlanItemState.COMPLETED || it.state == PlanItemState.IN_PROGRESS }
                .sumOf { it.fileCount }

            ZipFile(zipFile).use { zf ->
                val entries = zf.entries()
                var actualCount = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entry.size // may throw if entry is corrupt
                    actualCount++
                }
                Log.d(TAG, "ZIP integrity check: ${part.zipFileName} expected=$expectedCount actual=$actualCount")
                actualCount >= expectedCount
            }
        } catch (e: Exception) {
            Log.w(TAG, "ZIP integrity check failed for ${part.zipFileName}: ${e.message}")
            false
        }
    }

    /**
     * On resume, re-verify all completed parts/zips.
     * Returns the number of corrupt parts found.
     */
    fun verifyAllCompleted(plan: CompressPlan): Int {
        var corruptCount = 0
        for (part in plan.parts) {
            if (part.state == PlanItemState.COMPLETED) {
                if (!verifyZipIntegrity(part)) {
                    Log.w(TAG, "Part ${part.zipFileName} is corrupt, resetting")
                    markPartCorrupt(plan, part)
                    corruptCount++
                }
            }
        }
        return corruptCount
    }

    /**
     * Fast directory scan using shell commands with Java fallback.
     */
    fun fastScanDir(dir: UniFile): ScanResult? {
        val path = getRealPath(dir) ?: return fallbackScan(dir)

        return try {
            shellScan(path)
        } catch (e: Exception) {
            Log.d(TAG, "Shell scan failed for $path, using fallback: ${e.message}")
            fallbackScan(dir)
        }
    }

    /**
     * Shell-based fast scan using du + find (only for file:// paths).
     */
    private fun shellScan(path: String): ScanResult {
        val sizeBytes = execShell("du -sb \"$path\"")?.trim()?.split('\t', ' ')
            ?.firstOrNull()?.toLongOrNull() ?: 0L

        val files = mutableListOf<FileEntry>()
        val output = execShell("find \"$path\" -type f -printf '%s\t%P\n'")
        if (output != null) {
            for (line in output.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split('\t', limit = 2)
                if (parts.size == 2) {
                    val size = parts[0].toLongOrNull() ?: continue
                    val name = parts[1]
                    files.add(FileEntry(name = name, size = size))
                }
            }
        }

        return ScanResult(sizeBytes, files)
    }

    /**
     * Java fallback scan using UniFile recursive traversal.
     */
    private fun fallbackScan(dir: UniFile): ScanResult {
        var totalSize = 0L
        val files = mutableListOf<FileEntry>()

        val stack = ArrayDeque<Pair<UniFile, String>>()
        stack.addLast(dir to "")

        while (stack.isNotEmpty()) {
            val (current, prefix) = stack.removeLast()
            if (current.isFile) {
                val size = current.length()
                val name = if (prefix.isEmpty()) current.name ?: "" else "$prefix/${current.name}"
                files.add(FileEntry(name = name, size = size))
                totalSize += size
            } else if (current.isDirectory) {
                val children = current.listFiles() ?: continue
                val childPrefix = if (prefix.isEmpty()) current.name ?: "" else "$prefix/${current.name}"
                for (child in children) {
                    stack.addLast(child to childPrefix)
                }
            }
        }

        return ScanResult(totalSize, files)
    }

    /**
     * Execute a shell command and return stdout as string.
     */
    private fun execShell(cmd: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readText()
            reader.close()
            process.waitFor()
            if (result.isBlank()) null else result
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the real filesystem path from a UniFile, or null for SAF URIs.
     */
    fun getRealPath(file: UniFile): String? {
        val uri = file.uri
        val scheme = uri.scheme
        return if (scheme == "file") {
            uri.path
        } else {
            null
        }
    }

    /**
     * Sanitize a string for use as a folder name in ZIP.
     */
    fun sanitizeFileName(input: String): String {
        return input.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}
