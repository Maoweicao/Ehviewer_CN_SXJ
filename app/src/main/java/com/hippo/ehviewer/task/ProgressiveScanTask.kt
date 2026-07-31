package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.Collections
import kotlin.coroutines.coroutineContext

class ProgressiveScanTask(context: Context) : BaseBackgroundTask(context) {

    private val scanResults = mutableListOf<ProgressiveChain>()
    private var totalFolders = 0
    private var totalCandidates = 0L
    private var checkedCandidates = 0L

    override fun getTaskId(): String = "progressive_scan_${System.currentTimeMillis()}"

    override fun getTaskName(): String = context.getString(R.string.lab_progressive_manager)

    override fun getTaskDescription(): String = context.getString(R.string.progressive_scan_scanning)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.SCAN

    override fun isUniqueTask(): Boolean = true

    override fun isPausable(): Boolean = false

    override fun isPersistable(): Boolean = false

    companion object {
        private const val TAG = "ProgressiveScanTask"

        @JvmStatic
        fun loadResults(): List<ProgressiveChain> = ProgressivePlanManager.loadScanResults()

        @JvmStatic
        fun parseResults(json: JSONObject): List<ProgressiveChain> {
            val results = mutableListOf<ProgressiveChain>()
            val chains = json.optJSONArray("chains") ?: return results
            for (i in 0 until chains.length()) {
                val chainObj = chains.getJSONObject(i)
                val folders = mutableListOf<FolderInfo>()
                val foldersArr = chainObj.getJSONArray("folders")
                for (j in 0 until foldersArr.length()) {
                    val f = foldersArr.getJSONObject(j)
                    folders.add(
                        FolderInfo(
                            gid = f.optLong("gid", -1),
                            name = f.optString("name", ""),
                            path = f.optString("path", ""),
                            hashCount = f.optInt("hashCount", 0),
                            fileCount = f.optInt("fileCount", 0),
                            hasEhviewer = f.optBoolean("hasEhviewer", false),
                            title = f.optString("title", ""),
                            modifiedAt = f.optLong("modifiedAt", 0)
                        )
                    )
                }
                results.add(
                    ProgressiveChain(
                        id = chainObj.optInt("id", i),
                        folders = folders,
                        totalUniqueHashes = chainObj.optInt("totalUniqueHashes", 0),
                        commonHashes = chainObj.optInt("commonHashes", 0)
                    )
                )
            }
            return results
        }
    }

    data class FolderHashInfo(
        val gid: Long,
        val name: String,
        val path: String,
        val hashes: IntArray,
        val hashCount: Int,
        val fileCount: Int,
        val title: String,
        val modifiedAt: Long
    )

    data class FolderInfo(
        val gid: Long,
        val name: String,
        val path: String,
        val hashCount: Int,
        val fileCount: Int,
        val hasEhviewer: Boolean,
        val title: String,
        val modifiedAt: Long
    )

    data class ProgressiveChain(
        val id: Int,
        val folders: List<FolderInfo>,
        val totalUniqueHashes: Int,
        val commonHashes: Int
    ) {
        val mostComplete: FolderInfo? get() = folders.maxByOrNull { it.hashCount }
        val depth: Int get() = folders.size
        val displayName: String
            get() {
                val mc = mostComplete
                return if (mc != null && mc.title.isNotEmpty()) mc.title
                else if (mc != null) mc.name
                else "Chain $id"
            }
    }

    override suspend fun execute(): Result<Unit> {
        return try {
            Log.i(TAG, "Starting progressive scan")
            updateProgress(0, context.getString(R.string.progressive_scan_scanning))

            ensureNotCancelled()
            val (folderInfos, hashToInt) = collectFolderInfos()
            if (folderInfos.isEmpty()) {
                updateProgress(100, context.getString(R.string.progressive_scan_no_folders))
                notifyCompleted()
                return Result.success(Unit)
            }

            ensureNotCancelled()
            scanResults.clear()
            scanResults.addAll(buildProgressiveChains(folderInfos))

            ensureNotCancelled()
            Log.i(TAG, "Found " + scanResults.size + " progressive chains")
            Log.i(TAG, "Scan complete: " + scanResults.size + " chains, saving results")
            saveResults()

            val summary = context.getString(R.string.progressive_chain_count, scanResults.size)
            updateProgress(100, summary)
            appendTaskLog(summary)
            notifyCompleted()
            Result.success(Unit)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled()
                return Result.failure(e)
            }
            Log.e(TAG, "Scan failed", e)
            appendTaskLog("ERROR: %s", e.message ?: "Unknown error")
            notifyError(e)
            Result.failure(e)
        }
    }

    private suspend fun ensureNotCancelled() {
        coroutineContext.ensureActive()
    }

    private fun collectFolderInfos(): Pair<List<FolderHashInfo>, HashMap<String, Int>> {
        var infos = EhDB.getAllDownloadInfo()
        if (infos == null) {
            infos = Collections.emptyList()
        }

        val tempResults = mutableListOf<TempFolderInfo>()
        totalFolders = infos.size

        for (i in infos.indices) {
            val info = infos[i]
            val dir = SpiderDen.getGalleryDownloadDir(info)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                val pct = if (totalFolders > 0) (i + 1) * 15 / totalFolders else 0
                updateProgress(pct, "Scanning: ${i + 1}/$totalFolders")
                continue
            }

            val name = dir.name ?: info.gid.toString()
            val meta = EhviewerMetaParser.parse(dir)
            val title = EhUtils.getSuitableTitle(info)

            if (meta != null && meta.hashes.isNotEmpty()) {
                tempResults.add(
                    TempFolderInfo(
                        gid = info.gid,
                        name = name,
                        path = dir.uri?.toString() ?: "",
                        hashStrings = meta.hashes,
                        title = title ?: name,
                        modifiedAt = maxOf(dir.lastModified(), 0L)
                    )
                )
                // 同步更新 ptoken 索引，避免重复扫描
                try {
                    val ptokens = meta.hashes.joinToString(",")
                    EhDB.putPtokensIndex(info.gid, ptokens, meta.hashes.size)
                } catch (_: Exception) { }
            }

            val pct = if (totalFolders > 0) (i + 1) * 15 / totalFolders else 0
            updateProgress(pct, "Scanning: ${i + 1}/$totalFolders")
        }

        appendTaskLog("Scanned %d folders, %d have .ehviewer", totalFolders, tempResults.size)

        // Build global hash -> int mapping
        val hashToInt = HashMap<String, Int>()
        var nextId = 0
        for (t in tempResults) {
            for (h in t.hashStrings) {
                if (!hashToInt.containsKey(h)) {
                    hashToInt[h] = nextId++
                }
            }
        }
        appendTaskLog("Unique hashes: %d", hashToInt.size)

        // Convert to int-based representation
        val result = tempResults.map { t ->
            val ids = IntArray(t.hashStrings.size) { hashToInt[t.hashStrings[it]]!! }
            ids.sort()
            FolderHashInfo(
                gid = t.gid,
                name = t.name,
                path = t.path,
                hashes = ids,
                hashCount = ids.size,
                fileCount = ids.size,
                title = t.title,
                modifiedAt = t.modifiedAt
            )
        }

        return result to hashToInt
    }

    private data class TempFolderInfo(
        val gid: Long,
        val name: String,
        val path: String,
        val hashStrings: List<String>,
        val title: String,
        val modifiedAt: Long
    )

    private suspend fun buildProgressiveChains(folderInfos: List<FolderHashInfo>): List<ProgressiveChain> {
        val sorted = folderInfos.sortedBy { it.hashCount }
        val n = sorted.size

        // Determine max hash id for array sizing
        var maxHashId = 0
        for (f in sorted) {
            for (h in f.hashes) {
                if (h > maxHashId) maxHashId = h
            }
        }

        // Build inverted index: hashId -> list of gallery indices that contain it
        val hashToOwners = Array(maxHashId + 1) { mutableListOf<Int>() }
        for (i in 0 until n) {
            for (h in sorted[i].hashes) {
                hashToOwners[h].add(i)
            }
        }

        // Union-Find
        val uf = IntArray(n) { it }
        totalCandidates = 0
        checkedCandidates = 0

        for (i in 0 until n) {
            ensureNotCancelled()

            val candidateSet = IntHashSet()
            for (h in sorted[i].hashes) {
                for (j in hashToOwners[h]) {
                    if (j != i && sorted[j].hashCount > sorted[i].hashCount) {
                        candidateSet.add(j)
                    }
                }
            }

            totalCandidates += candidateSet.size

            for (j in candidateSet.values()) {
                ensureNotCancelled()
                checkedCandidates++

                if (intArrayContainsAll(sorted[j].hashes, sorted[i].hashes)) {
                    union(uf, i, j)
                }

                if (checkedCandidates % 500 == 0L) {
                    val pct = 15 + (checkedCandidates * 65 / maxOf(totalCandidates, 1)).toInt()
                    updateProgress(pct, "Comparing: $checkedCandidates/$totalCandidates")
                }
            }

            if (i % 100 == 0) {
                val pct = 15 + (checkedCandidates * 65 / maxOf(totalCandidates, 1)).toInt()
                updateProgress(pct, "Index scan: ${i + 1}/$n")
            }
        }

        // Free inverted index
        for (arr in hashToOwners) arr.clear()

        ensureNotCancelled()
        updateProgress(80, "Building chains...")
        appendTaskLog("Index: %d galleries, %d candidates checked", n, checkedCandidates)
        return buildChainsFromComponents(sorted, uf, n)
    }

    private fun intArrayContainsAll(superArr: IntArray, subArr: IntArray): Boolean {
        if (subArr.isEmpty()) return true
        if (superArr.size < subArr.size) return false
        var si = 0
        var ci = 0
        while (si < superArr.size && ci < subArr.size) {
            when {
                superArr[si] == subArr[ci] -> { si++; ci++ }
                superArr[si] < subArr[ci] -> si++
                else -> return false
            }
        }
        return ci == subArr.size
    }

    private class IntHashSet {
        private var count = 0
        private var data = IntArray(16)

        val size: Int get() = count

        fun add(value: Int) {
            for (i in 0 until count) {
                if (data[i] == value) return
            }
            if (count == data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[count++] = value
        }

        fun values(): IntArray = data.copyOf(count)
    }

    private fun find(uf: IntArray, x: Int): Int {
        var root = x
        while (uf[root] != root) {
            root = uf[root]
        }
        var cur = x
        while (uf[cur] != root) {
            val next = uf[cur]
            uf[cur] = root
            cur = next
        }
        return root
    }

    private fun union(uf: IntArray, a: Int, b: Int) {
        val ra = find(uf, a)
        val rb = find(uf, b)
        if (ra != rb) {
            uf[ra] = rb
        }
    }

    private fun buildChainsFromComponents(
        folderInfos: List<FolderHashInfo>,
        uf: IntArray,
        n: Int
    ): List<ProgressiveChain> {
        val components = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val root = find(uf, i)
            components.getOrPut(root) { mutableListOf() }.add(i)
        }

        val chains = mutableListOf<ProgressiveChain>()
        var chainId = 0

        for (component in components.values) {
            if (component.size < 2) continue
            val sortedComponent = component.sortedBy { folderInfos[it].hashCount }
            val folders = sortedComponent.map { idx ->
                val f = folderInfos[idx]
                FolderInfo(
                    gid = f.gid,
                    name = f.name,
                    path = f.path,
                    hashCount = f.hashCount,
                    fileCount = f.fileCount,
                    hasEhviewer = true,
                    title = f.title,
                    modifiedAt = f.modifiedAt
                )
            }

            // Compute unique and common hash counts using int arrays
            val allHashIds = java.util.TreeSet<Int>()
            for (idx in sortedComponent) {
                for (h in folderInfos[idx].hashes) allHashIds.add(h)
            }
            var common = java.util.TreeSet<Int>(folderInfos[sortedComponent[0]].hashes.toList())
            for (k in 1 until sortedComponent.size) {
                common = intersectSorted(common, folderInfos[sortedComponent[k]].hashes)
            }

            chains.add(
                ProgressiveChain(
                    id = ++chainId,
                    folders = folders,
                    totalUniqueHashes = allHashIds.size,
                    commonHashes = common.size
                )
            )
        }

        return chains
    }

    private fun intersectSorted(a: java.util.TreeSet<Int>, b: IntArray): java.util.TreeSet<Int> {
        val result = java.util.TreeSet<Int>()
        var bi = 0
        for (v in a) {
            while (bi < b.size && b[bi] < v) bi++
            if (bi < b.size && b[bi] == v) result.add(v)
        }
        return result
    }

    private fun saveResults() {
        val scanTime = System.currentTimeMillis()
        if (ProgressivePlanManager.saveScanResults(scanResults, scanTime)) {
            Log.d(TAG, "Results saved to external ProgressiveScan dir")
            appendTaskLog("Results saved (%d chains)", scanResults.size)
        } else {
            Log.e(TAG, "Failed to save results")
            appendTaskLog("ERROR: Failed to save results")
        }
    }
}
