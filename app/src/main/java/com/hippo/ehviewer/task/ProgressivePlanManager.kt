package com.hippo.ehviewer.task

import android.util.Log
import com.hippo.ehviewer.AppConfig
import org.json.JSONObject
import java.io.File

object ProgressivePlanManager {

    private const val TAG = "ProgressivePlanManager"

    @JvmStatic
    fun getScanResultDir(): File? = AppConfig.getProgressiveScanDir()

    @JvmStatic
    fun getBackupDir(): File? = AppConfig.getProgressiveBackupDir()

    @JvmStatic
    fun saveScanResults(results: List<ProgressiveScanTask.ProgressiveChain>, scanTime: Long): Boolean {
        val dir = AppConfig.getProgressiveScanDir() ?: return false
        if (!dir.exists()) dir.mkdirs()

        val fileName = "scan_result_${formatTimestamp(scanTime)}.json"
        val file = File(dir, fileName)

        return try {
            val json = JSONObject()
            json.put("version", "2.0")
            json.put("scanTime", scanTime)
            json.put("scanTimeDisplay", formatDisplayTime(scanTime))
            json.put("totalChains", results.size)

            val chainsArr = org.json.JSONArray()
            for (chain in results) {
                val chainObj = JSONObject()
                chainObj.put("id", chain.id)
                chainObj.put("totalUniqueHashes", chain.totalUniqueHashes)
                chainObj.put("commonHashes", chain.commonHashes)

                val foldersArr = org.json.JSONArray()
                for (f in chain.folders) {
                    val fObj = JSONObject()
                    fObj.put("gid", f.gid)
                    fObj.put("name", f.name)
                    fObj.put("path", f.path)
                    fObj.put("hashCount", f.hashCount)
                    fObj.put("fileCount", f.fileCount)
                    fObj.put("hasEhviewer", f.hasEhviewer)
                    fObj.put("title", f.title)
                    foldersArr.put(fObj)
                }
                chainObj.put("folders", foldersArr)
                chainsArr.put(chainObj)
            }
            json.put("chains", chainsArr)

            file.writeText(json.toString(2))
            cleanupOldScanResults(dir, fileName)
            Log.i(TAG, "Scan results saved to ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save scan results", e)
            false
        }
    }

    @JvmStatic
    fun loadScanResults(): List<ProgressiveScanTask.ProgressiveChain> {
        val dir = AppConfig.getProgressiveScanDir() ?: return emptyList()
        if (!dir.exists()) return emptyList()

        val files = dir.listFiles { f -> f.name.startsWith("scan_result_") && f.name.endsWith(".json") }
            ?: return emptyList()
        if (files.isEmpty()) return emptyList()

        val latest = files.maxByOrNull { it.lastModified() } ?: return emptyList()

        return try {
            val json = JSONObject(latest.readText())
            val scanTime = json.optLong("scanTime", 0)
            if (scanTime > 0 && (System.currentTimeMillis() - scanTime) > 7L * 24 * 60 * 60 * 1000) {
                Log.i(TAG, "Scan results expired, deleting: ${latest.name}")
                latest.delete()
                return emptyList()
            }
            ProgressiveScanTask.parseResults(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scan results", e)
            emptyList()
        }
    }

    @JvmStatic
    fun saveMergePlan(plan: ProgressiveMergePlan): Boolean {
        val dir = AppConfig.getProgressiveScanDir() ?: return false
        if (!dir.exists()) dir.mkdirs()

        plan.updatedAt = System.currentTimeMillis()
        val fileName = "merge_plan_${plan.planId}.json"
        val file = File(dir, fileName)
        val tmpFile = File(dir, "$fileName.tmp")

        return try {
            tmpFile.writeText(plan.toJson().toString(2))
            tmpFile.renameTo(file)
            Log.i(TAG, "Merge plan saved to ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save merge plan", e)
            try { tmpFile.delete() } catch (_: Exception) {}
            false
        }
    }

    @JvmStatic
    fun loadMergePlan(planId: String): ProgressiveMergePlan? {
        val dir = AppConfig.getProgressiveScanDir() ?: return null
        val file = File(dir, "merge_plan_$planId.json")
        if (!file.exists()) return null

        return try {
            val json = JSONObject(file.readText())
            val plan = ProgressiveMergePlan.fromJson(json)
            if (plan.isExpired) {
                Log.i(TAG, "Merge plan expired, deleting: $planId")
                file.delete()
                return null
            }
            plan
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load merge plan $planId", e)
            null
        }
    }

    @JvmStatic
    fun loadLatestMergePlan(): ProgressiveMergePlan? {
        val dir = AppConfig.getProgressiveScanDir() ?: return null
        if (!dir.exists()) return null

        val files = dir.listFiles { f -> f.name.startsWith("merge_plan_") && f.name.endsWith(".json") }
            ?: return null
        if (files.isEmpty()) return null

        val latest = files.maxByOrNull { it.lastModified() } ?: return null
        return try {
            val json = JSONObject(latest.readText())
            val plan = ProgressiveMergePlan.fromJson(json)
            if (plan.isExpired) {
                latest.delete()
                return null
            }
            plan
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun deleteMergePlan(planId: String) {
        val dir = AppConfig.getProgressiveScanDir() ?: return
        val file = File(dir, "merge_plan_$planId.json")
        if (file.exists()) file.delete()
    }

    @JvmStatic
    @JvmOverloads
    fun updateChainStatus(plan: ProgressiveMergePlan, chainId: Int, status: ChainMergeStatus, errorMsg: String? = null) {
        val entry = plan.chains.find { it.chainId == chainId } ?: return
        entry.status = status
        entry.errorMessage = errorMsg
        if (status == ChainMergeStatus.COMPLETED || status == ChainMergeStatus.FAILED) {
            plan.completedChains++
        }
        plan.updatedAt = System.currentTimeMillis()
        saveMergePlan(plan)
    }

    private fun cleanupOldScanResults(dir: File, keepFile: String) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val files = dir.listFiles { f ->
            f.name.startsWith("scan_result_") && f.name.endsWith(".json") && f.name != keepFile
        } ?: return
        for (f in files) {
            if (f.lastModified() < cutoff) {
                f.delete()
            }
        }
    }

    private fun formatTimestamp(time: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(time))
    }

    private fun formatDisplayTime(time: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(time))
    }
}
