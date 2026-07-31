package com.hippo.ehviewer.task.impl

import android.content.Context
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.task.BackgroundTask
import com.hippo.ehviewer.task.compress.CompressPlan
import com.hippo.ehviewer.task.compress.CompressPlanManager
import com.hippo.ehviewer.task.compress.PlanItemState
import com.hippo.ehviewer.task.compress.PlanState
import com.hippo.unifile.UniFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 压缩选择的下载画廊任务 (Plan-based)
 *
 * 执行流程:
 * 1. 尝试加载已有的 CompressPlan JSON (断点续传)
 * 2. 若无 Plan → 扫描所有画廊 → 生成计划 → 保存 JSON
 * 3. 按计划逐 gallery 压缩，每个完成后更新 JSON
 * 4. 每个 ZIP 分卷完成后校验完整性，损坏则重做
 * 5. 断点续传时先检查所有已完成 ZIP 的完整性
 */
class CompressSelectedGalleriesTask @JvmOverloads constructor(
    context: Context,
    private val selectedList: List<DownloadInfo>,
    private val taskId: String = "compress_selected_galleries_${System.currentTimeMillis()}"
) : BaseBackgroundTask(context) {

    private val outputFileNames = mutableListOf<String>()
    private val addedEntries = mutableSetOf<String>()

    fun getOutputFileNames(): List<String> = outputFileNames

    override fun getTaskId(): String = taskId

    override fun getTaskName(): String = context.getString(R.string.compress_selected_galleries)

    override fun getTaskDescription(): String? = context.getString(R.string.compress_selected_galleries)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.CLEANUP

    override fun isUniqueTask(): Boolean = false

    override fun isPausable(): Boolean = false

    override fun isPersistable(): Boolean = true

    override fun getTaskPersistData(): String? {
        return JSONObject().apply {
            val array = JSONArray()
            for (info in selectedList) {
                array.put(info.gid)
            }
            put("gids", array)
        }.toString()
    }

    override suspend fun execute(): Result<Unit> {
        appendTaskLog("开始压缩 ${selectedList.size} 个画廊")

        if (selectedList.isEmpty()) {
            updateProgress(100, context.getString(R.string.compress_selected_galleries))
            notifyCompleted()
            appendTaskLog("没有要压缩的画廊，任务结束")
            return Result.success(Unit)
        }

        val outputDir = com.hippo.ehviewer.Settings.getExportLocation()
            ?: return Result.failure(IOException("Export location unavailable"))

        if (!outputDir.exists() && !outputDir.ensureDir()) {
            return Result.failure(IOException("Failed to create export directory"))
        }

        val splitSizeBytes = com.hippo.ehviewer.Settings.getCompressSplitSizeBytes()

        // Phase 1: Load or build plan
        var plan = CompressPlanManager.loadPlan(taskId)

        if (plan != null) {
            // RESUME mode
            appendTaskLog("发现已有压缩计划，进入恢复模式")
            val corrupted = CompressPlanManager.verifyAllCompleted(plan)
            if (corrupted > 0) {
                appendTaskLog("发现 $corrupted 个损坏的压缩包，将重新压缩")
            }
            updateProgress(plan.completedGalleries, plan.totalGalleries,
                "${context.getString(R.string.compress_selected_galleries)} ${plan.completedGalleries}/${plan.totalGalleries}")

            // Collect completed ZIP names for output
            for (part in plan.parts) {
                if (part.state == PlanItemState.COMPLETED && !outputFileNames.contains(part.zipFileName)) {
                    outputFileNames.add(part.zipFileName)
                }
            }
        } else {
            // CREATE mode
            appendTaskLog("扫描画廊文件夹，生成压缩计划...")
            plan = CompressPlanManager.buildPlan(outputDir, selectedList, splitSizeBytes, taskId)
                ?: return Result.failure(IOException("Failed to build compress plan"))

            if (!CompressPlanManager.savePlan(plan)) {
                return Result.failure(IOException("Failed to save compress plan"))
            }
            appendTaskLog("计划已生成: ${plan.totalGalleries} 个画廊, ${plan.parts.size} 个压缩包")
            updateProgress(0, plan.totalGalleries,
                "${context.getString(R.string.compress_selected_galleries)} 0/${plan.totalGalleries}")
        }

        // Phase 2: Execute plan
        return try {
            plan.state = PlanState.IN_PROGRESS
            CompressPlanManager.savePlan(plan)

            var zos: ZipOutputStream? = null
            var currentPart: com.hippo.ehviewer.task.compress.CompressPart? = null
            var currentPartFile: UniFile? = null

            var partIndex = 0
            while (partIndex < plan.parts.size) {
                val part = plan.parts[partIndex]

                // Skip completed parts
                if (part.state == PlanItemState.COMPLETED && CompressPlanManager.verifyZipIntegrity(part)) {
                    appendTaskLog("压缩包 ${part.zipFileName} 已完成且完整，跳过")
                    partIndex++
                    continue
                }

                if (part.state == PlanItemState.COMPLETED) {
                    // Marked COMPLETED but ZIP is corrupt
                    appendTaskLog("压缩包 ${part.zipFileName} 不完整，重新压缩")
                    CompressPlanManager.markPartCorrupt(plan, part)
                }

                // Handle in-progress part: close and delete partial ZIP
                if (zos != null && currentPart != part) {
                    try { zos.closeEntry() } catch (_: Exception) {}
                    try { zos.close() } catch (_: Exception) {}
                    zos = null
                    if (currentPart != null) {
                        if (!CompressPlanManager.verifyZipIntegrity(currentPart)) {
                            appendTaskLog("压缩包 ${currentPart.zipFileName} 校验失败，重新压缩")
                            CompressPlanManager.markPartCorrupt(plan, currentPart)
                        }
                    }
                }

                for (gallery in part.galleries) {
                    if (gallery.state == PlanItemState.COMPLETED) {
                        // Already done - ensure output name is tracked
                        if (!outputFileNames.contains(part.zipFileName)) {
                            outputFileNames.add(part.zipFileName)
                        }
                        continue
                    }

                    val galleryDir = SpiderDen.getGalleryDownloadDir(
                        DownloadInfo(gallery.gid).apply { title = gallery.title }
                    )
                    if (galleryDir == null || !galleryDir.exists()) {
                        appendTaskLog("画廊 ${gallery.gid} 不存在，跳过")
                        gallery.state = PlanItemState.COMPLETED
                        plan.completedGalleries++
                        CompressPlanManager.savePlan(plan)
                        updateProgress(plan.completedGalleries, plan.totalGalleries,
                            "${context.getString(R.string.compress_selected_galleries)} ${plan.completedGalleries}/${plan.totalGalleries}")
                        continue
                    }

                    appendTaskLog("压缩画廊 ${gallery.gid} - ${gallery.title}")

                    // Open or reuse ZIP part
                    if (zos == null) {
                        addedEntries.clear()
                        val zipFile = File(part.zipFilePath)
                        // Delete any incomplete partial file
                        if (zipFile.exists() && part.state != PlanItemState.COMPLETED) {
                            zipFile.delete()
                        }
                        currentPartFile = outputDir.createFile(part.zipFileName)
                            ?: return Result.failure(IOException("Failed to create ${part.zipFileName}"))
                        zos = ZipOutputStream(currentPartFile!!.openOutputStream())
                        currentPart = part
                        if (!outputFileNames.contains(currentPartFile!!.name)) {
                            outputFileNames.add(currentPartFile!!.name ?: part.zipFileName)
                        }
                    }

                    // Compress
                    gallery.state = PlanItemState.IN_PROGRESS
                    CompressPlanManager.savePlan(plan)

                    val folderName = gallery.folderName
                    addUniFileToZip(galleryDir, folderName, zos!!)

                    gallery.state = PlanItemState.COMPLETED
                    plan.completedGalleries++
                    CompressPlanManager.savePlan(plan)

                    updateProgress(plan.completedGalleries, plan.totalGalleries,
                        "${context.getString(R.string.compress_selected_galleries)} ${plan.completedGalleries}/${plan.totalGalleries}")
                }

                // Part done - close ZIP and verify
                if (zos != null) {
                    try {
                        zos.closeEntry()
                    } catch (_: Exception) {}
                    try {
                        zos.close()
                    } catch (_: Exception) {}
                    zos = null

                    if (CompressPlanManager.verifyZipIntegrity(part)) {
                        part.state = PlanItemState.COMPLETED
                        CompressPlanManager.savePlan(plan)
                        appendTaskLog("压缩包 ${part.zipFileName} 完成并校验通过")
                        partIndex++  // Move to next part
                    } else {
                        appendTaskLog("压缩包 ${part.zipFileName} 校验失败，将重新压缩")
                        CompressPlanManager.markPartCorrupt(plan, part)
                        // Stay on same partIndex to retry
                    }
                } else {
                    partIndex++
                }
                currentPart = null
                currentPartFile = null
            }

            // Final: close any remaining output
            try { zos?.close() } catch (_: Exception) {}

            plan.state = PlanState.COMPLETED
            CompressPlanManager.savePlan(plan)

            notifyCompleted()
            appendTaskLog("压缩完成，生成 ${outputFileNames.size} 个压缩包: ${outputFileNames.joinToString(", ")}")

            Result.success(Unit)
        } catch (e: Exception) {
            try { /* final close */ } catch (_: Exception) {}
            // Save plan state so resume can pick up
            try {
                CompressPlanManager.savePlan(plan)
            } catch (_: Exception) {}
            appendTaskLog("压缩任务出错: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }

    private fun addUniFileToZip(uniFile: UniFile, basePath: String, zos: ZipOutputStream) {
        if (uniFile.isDirectory) {
            val dirPath = if (basePath.endsWith("/")) basePath else "$basePath/"
            if (addZipEntry(zos, dirPath)) {
                zos.closeEntry()
            }
            val children = uniFile.listFiles() ?: return
            for (child in children) {
                val childPath = if (dirPath.isEmpty()) child.name ?: "" else dirPath + (child.name ?: "")
                addUniFileToZip(child, childPath, zos)
            }
        } else if (uniFile.isFile) {
            val entryName = basePath
            if (!addZipEntry(zos, entryName)) {
                appendTaskLog("忽略重复条目: $entryName")
                return
            }
            uniFile.openInputStream()?.use { input ->
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

    private fun addZipEntry(zos: ZipOutputStream, entryName: String): Boolean {
        if (!addedEntries.add(entryName)) {
            return false
        }
        return try {
            zos.putNextEntry(ZipEntry(entryName))
            true
        } catch (e: java.util.zip.ZipException) {
            false
        }
    }

    companion object {
        @JvmStatic
        fun restore(context: Context, taskId: String, persistData: String?): CompressSelectedGalleriesTask? {
            if (persistData.isNullOrEmpty()) {
                // Try plan-based restore: if a plan file exists, extract gids from it
                val plan = CompressPlanManager.loadPlan(taskId)
                if (plan != null) {
                    val gids = mutableListOf<Long>()
                    for (part in plan.parts) {
                        for (gallery in part.galleries) {
                            gids.add(gallery.gid)
                        }
                    }
                    if (gids.isNotEmpty()) {
                        val selected = gids.map { DownloadInfo(it) }
                        return CompressSelectedGalleriesTask(context, selected, taskId)
                    }
                }
                return null
            }
            return try {
                val json = JSONObject(persistData)
                val gids = json.optJSONArray("gids") ?: return null
                val selected = mutableListOf<DownloadInfo>()
                for (i in 0 until gids.length()) {
                    val gid = gids.optLong(i, -1)
                    if (gid > 0) {
                        selected.add(DownloadInfo(gid))
                    }
                }
                if (selected.isEmpty()) null
                else CompressSelectedGalleriesTask(context, selected, taskId)
            } catch (e: Exception) {
                null
            }
        }
    }
}
