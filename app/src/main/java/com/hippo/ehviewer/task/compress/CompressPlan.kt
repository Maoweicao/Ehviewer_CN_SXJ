package com.hippo.ehviewer.task.compress

import org.json.JSONArray
import org.json.JSONObject

enum class PlanState {
    PENDING, IN_PROGRESS, COMPLETED, FAILED;

    companion object {
        fun fromString(s: String?): PlanState = try { valueOf(s ?: "") } catch (_: Exception) { PENDING }
    }
}

enum class PlanItemState {
    PENDING, IN_PROGRESS, COMPLETED, CORRUPT;

    companion object {
        fun fromString(s: String?): PlanItemState = try { valueOf(s ?: "") } catch (_: Exception) { PENDING }
    }
}

data class FileEntry(
    val name: String,
    val size: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("size", size)
    }

    companion object {
        fun fromJson(json: JSONObject): FileEntry = FileEntry(
            name = json.optString("name", ""),
            size = json.optLong("size", 0L)
        )
    }
}

data class CompressGalleryEntry(
    val gid: Long,
    val title: String,
    var state: PlanItemState = PlanItemState.PENDING,
    val folderName: String,
    val fileCount: Int,
    val sizeBytes: Long,
    val files: MutableList<FileEntry> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("gid", gid)
        put("title", title)
        put("state", state.name)
        put("folderName", folderName)
        put("fileCount", fileCount)
        put("sizeBytes", sizeBytes)
        put("files", JSONArray().apply {
            files.forEach { put(it.toJson()) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): CompressGalleryEntry {
            val filesArray = json.optJSONArray("files")
            val files = mutableListOf<FileEntry>()
            if (filesArray != null) {
                for (i in 0 until filesArray.length()) {
                    val obj = filesArray.optJSONObject(i) ?: continue
                    files.add(FileEntry.fromJson(obj))
                }
            }
            return CompressGalleryEntry(
                gid = json.optLong("gid", -1L),
                title = json.optString("title", ""),
                state = PlanItemState.fromString(json.optString("state")),
                folderName = json.optString("folderName", ""),
                fileCount = json.optInt("fileCount", 0),
                sizeBytes = json.optLong("sizeBytes", 0L),
                files = files
            )
        }
    }
}

data class CompressPart(
    val partIndex: Int,
    val zipFileName: String,
    val zipFilePath: String,
    var state: PlanItemState = PlanItemState.PENDING,
    var sizeBytes: Long = 0L,
    val galleries: MutableList<CompressGalleryEntry> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("partIndex", partIndex)
        put("zipFileName", zipFileName)
        put("zipFilePath", zipFilePath)
        put("state", state.name)
        put("sizeBytes", sizeBytes)
        put("galleries", JSONArray().apply {
            galleries.forEach { put(it.toJson()) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): CompressPart {
            val galleriesArray = json.optJSONArray("galleries")
            val galleries = mutableListOf<CompressGalleryEntry>()
            if (galleriesArray != null) {
                for (i in 0 until galleriesArray.length()) {
                    val obj = galleriesArray.optJSONObject(i) ?: continue
                    galleries.add(CompressGalleryEntry.fromJson(obj))
                }
            }
            return CompressPart(
                partIndex = json.optInt("partIndex", 0),
                zipFileName = json.optString("zipFileName", ""),
                zipFilePath = json.optString("zipFilePath", ""),
                state = PlanItemState.fromString(json.optString("state")),
                sizeBytes = json.optLong("sizeBytes", 0L),
                galleries = galleries
            )
        }
    }
}

data class CompressPlan(
    val version: Int = 1,
    val taskId: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var state: PlanState = PlanState.PENDING,
    val outputDir: String = "",
    val splitSizeBytes: Long = 0L,
    val totalGalleries: Int = 0,
    var completedGalleries: Int = 0,
    val parts: MutableList<CompressPart> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("taskId", taskId)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("state", state.name)
        put("outputDir", outputDir)
        put("splitSizeBytes", splitSizeBytes)
        put("totalGalleries", totalGalleries)
        put("completedGalleries", completedGalleries)
        put("parts", JSONArray().apply {
            parts.forEach { put(it.toJson()) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): CompressPlan {
            val partsArray = json.optJSONArray("parts")
            val parts = mutableListOf<CompressPart>()
            if (partsArray != null) {
                for (i in 0 until partsArray.length()) {
                    val obj = partsArray.optJSONObject(i) ?: continue
                    parts.add(CompressPart.fromJson(obj))
                }
            }
            return CompressPlan(
                version = json.optInt("version", 1),
                taskId = json.optString("taskId", ""),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                state = PlanState.fromString(json.optString("state")),
                outputDir = json.optString("outputDir", ""),
                splitSizeBytes = json.optLong("splitSizeBytes", 0L),
                totalGalleries = json.optInt("totalGalleries", 0),
                completedGalleries = json.optInt("completedGalleries", 0),
                parts = parts
            )
        }
    }
}
