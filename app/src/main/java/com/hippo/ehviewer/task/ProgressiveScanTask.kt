package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.unifile.UniFile
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.coroutines.coroutineContext

class ProgressiveScanTask(context: Context) : BaseBackgroundTask(context) {

    private val scanResults = mutableListOf<ProgressiveChain>()
    private var totalFolders = 0
    private var totalComparisons = 0
    private var completedComparisons = 0

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
                            title = f.optString("title", "")
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
        val hashes: Set<String>,
        val hashCount: Int,
        val fileCount: Int,
        val title: String
    )

    data class FolderInfo(
        val gid: Long,
        val name: String,
        val path: String,
        val hashCount: Int,
        val fileCount: Int,
        val hasEhviewer: Boolean,
        val title: String
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
            val folderInfos = collectFolderInfos()
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

    private fun collectFolderInfos(): List<FolderHashInfo> {
        var infos = EhDB.getAllDownloadInfo()
        if (infos == null) {
            infos = Collections.emptyList()
        }

        val result = mutableListOf<FolderHashInfo>()
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
            val meta = parseEhviewerMeta(dir)
            val fileCount = countFiles(dir)
            val title = EhUtils.getSuitableTitle(info)

            if (meta != null && meta.hashes.isNotEmpty()) {
                result.add(
                    FolderHashInfo(
                        gid = info.gid,
                        name = name,
                        path = dir.uri?.toString() ?: "",
                        hashes = meta.hashes,
                        hashCount = meta.hashes.size,
                        fileCount = fileCount,
                        title = title ?: name
                    )
                )
            }

            val pct = if (totalFolders > 0) (i + 1) * 15 / totalFolders else 0
            updateProgress(pct, "Scanning: ${i + 1}/$totalFolders")
        }

        appendTaskLog("Scanned %d folders, %d have .ehviewer", totalFolders, result.size)
        return result
    }

    private suspend fun buildProgressiveChains(folderInfos: List<FolderHashInfo>): List<ProgressiveChain> {
        val sorted = folderInfos.sortedBy { it.hashCount }
        val n = sorted.size
        totalComparisons = n * (n - 1) / 2
        completedComparisons = 0

        val subsetEdges = mutableListOf<Pair<Int, Int>>()

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                ensureNotCancelled()
                completedComparisons++

                if (sorted[i].hashes.size >= sorted[j].hashes.size) continue

                if (sorted[j].hashes.containsAll(sorted[i].hashes)) {
                    subsetEdges.add(i to j)
                }

                if (completedComparisons % 200 == 0 || completedComparisons == totalComparisons) {
                    val pct = 15 + (completedComparisons * 65 / maxOf(totalComparisons, 1))
                    updateProgress(
                        pct,
                        "Comparing: $completedComparisons/$totalComparisons"
                    )
                }
            }
        }

        ensureNotCancelled()
        updateProgress(80, "Building chains...")
        return buildChainsFromEdges(sorted, subsetEdges, n)
    }

    private fun buildChainsFromEdges(
        folderInfos: List<FolderHashInfo>,
        edges: List<Pair<Int, Int>>,
        n: Int
    ): List<ProgressiveChain> {
        val adj = Array(n) { mutableListOf<Int>() }
        val revAdj = Array(n) { mutableListOf<Int>() }
        val inDegree = IntArray(n)
        for ((from, to) in edges) {
            adj[from].add(to)
            revAdj[to].add(from)
            inDegree[to]++
        }

        // Find connected components via undirected traversal
        val visited = BooleanArray(n)
        val components = mutableListOf<List<Int>>()

        for (start in 0 until n) {
            if (visited[start]) continue
            if (adj[start].isEmpty() && inDegree[start] == 0) continue

            val component = mutableListOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                component.add(u)
                // Follow forward edges
                for (v in adj[u]) {
                    if (!visited[v]) {
                        visited[v] = true
                        queue.add(v)
                    }
                }
                // Follow reverse edges (predecessors) using pre-built reverse adjacency
                for (w in revAdj[u]) {
                    if (!visited[w]) {
                        visited[w] = true
                        queue.add(w)
                    }
                }
            }

            if (component.size >= 2) {
                components.add(component)
            }
        }

        val chains = mutableListOf<ProgressiveChain>()
        var chainId = 0

        for (component in components) {
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
                    title = f.title
                )
            }
            val allHashes = mutableSetOf<String>()
            sortedComponent.forEach { idx -> allHashes.addAll(folderInfos[idx].hashes) }
            val commonHashes = sortedComponent.map { folderInfos[it].hashes }
                .reduce { acc, set -> acc.intersect(set) }

            chains.add(
                ProgressiveChain(
                    id = ++chainId,
                    folders = folders,
                    totalUniqueHashes = allHashes.size,
                    commonHashes = commonHashes.size
                )
            )
        }

        return chains
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

    private fun parseEhviewerMeta(dir: UniFile): EhviewerMeta? {
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

            for (i in 3 until lines.size) {
                val parts = lines[i].trim().split(Regex("\\s+"))
                if (parts.size < 2) continue
                try {
                    meta.hashes.add(parts[1])
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

    private fun countFiles(dir: UniFile?): Int {
        if (dir == null || !dir.exists()) return 0
        if (dir.isFile) return 1
        var total = 0
        val children = dir.listFiles()
        if (children != null) {
            for (child in children) {
                total += countFiles(child)
            }
        }
        return total
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        if (closeable == null) return
        try {
            closeable.close()
        } catch (_: Exception) {
        }
    }

    private class EhviewerMeta {
        val hashes: MutableSet<String> = mutableSetOf()
    }
}
