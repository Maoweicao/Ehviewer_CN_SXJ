package com.hippo.ehviewer.task

import org.json.JSONArray
import org.json.JSONObject

enum class PlanStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED;

    companion object {
        fun fromString(s: String?): PlanStatus = try { valueOf(s ?: "") } catch (_: Exception) { PENDING }
    }
}

enum class ChainMergeStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED;

    companion object {
        fun fromString(s: String?): ChainMergeStatus = try { valueOf(s ?: "") } catch (_: Exception) { PENDING }
    }
}

data class ChainMergeEntry @JvmOverloads constructor(
    val chainId: Int,
    val displayName: String,
    val targetGid: Long,
    val sourceGids: List<Long>,
    val folderCount: Int,
    var status: ChainMergeStatus = ChainMergeStatus.PENDING,
    var errorMessage: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("chainId", chainId)
        put("displayName", displayName)
        put("targetGid", targetGid)
        put("sourceGids", JSONArray(sourceGids))
        put("folderCount", folderCount)
        put("status", status.name)
        if (errorMessage != null) put("errorMessage", errorMessage)
    }

    companion object {
        fun fromJson(json: JSONObject): ChainMergeEntry {
            val srcArr = json.optJSONArray("sourceGids")
            val sourceGids = mutableListOf<Long>()
            if (srcArr != null) {
                for (i in 0 until srcArr.length()) {
                    sourceGids.add(srcArr.optLong(i, -1L))
                }
            }
            return ChainMergeEntry(
                chainId = json.optInt("chainId", -1),
                displayName = json.optString("displayName", ""),
                targetGid = json.optLong("targetGid", -1L),
                sourceGids = sourceGids,
                folderCount = json.optInt("folderCount", 0),
                status = ChainMergeStatus.fromString(json.optString("status")),
                errorMessage = if (json.has("errorMessage") && !json.isNull("errorMessage"))
                    json.optString("errorMessage") else null
            )
        }
    }
}

data class ProgressiveMergePlan @JvmOverloads constructor(
    val planId: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var status: PlanStatus = PlanStatus.PENDING,
    var backupEnabled: Boolean = false,
    var backupFilePath: String? = null,
    var deleteMissingDownloadTasks: Boolean = false,
    val totalChains: Int = 0,
    var completedChains: Int = 0,
    val chains: MutableList<ChainMergeEntry> = mutableListOf()
) {
    val isExpired: Boolean
        get() = (System.currentTimeMillis() - createdAt) > 7L * 24 * 60 * 60 * 1000

    fun toJson(): JSONObject = JSONObject().apply {
        put("planId", planId)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("status", status.name)
        put("backupEnabled", backupEnabled)
        put("deleteMissingDownloadTasks", deleteMissingDownloadTasks)
        if (backupFilePath != null) put("backupFilePath", backupFilePath)
        put("totalChains", totalChains)
        put("completedChains", completedChains)
        put("chains", JSONArray().apply {
            chains.forEach { put(it.toJson()) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): ProgressiveMergePlan {
            val chainsArr = json.optJSONArray("chains")
            val chains = mutableListOf<ChainMergeEntry>()
            if (chainsArr != null) {
                for (i in 0 until chainsArr.length()) {
                    val obj = chainsArr.optJSONObject(i) ?: continue
                    chains.add(ChainMergeEntry.fromJson(obj))
                }
            }
            return ProgressiveMergePlan(
                planId = json.optString("planId", ""),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                status = PlanStatus.fromString(json.optString("status")),
                backupEnabled = json.optBoolean("backupEnabled", false),
                backupFilePath = if (json.has("backupFilePath") && !json.isNull("backupFilePath"))
                    json.optString("backupFilePath") else null,
                deleteMissingDownloadTasks = json.optBoolean("deleteMissingDownloadTasks", false),
                totalChains = json.optInt("totalChains", 0),
                completedChains = json.optInt("completedChains", 0),
                chains = chains
            )
        }
    }
}
