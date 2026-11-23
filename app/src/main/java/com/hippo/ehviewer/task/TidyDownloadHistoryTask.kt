package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.DownloadHistory
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.GalleryFileHelper
import com.hippo.ehviewer.download.progressive.ProgressiveStats
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.ehviewer.transfer.core.ResponseCache
import com.hippo.ehviewer.util.DownloadHistoryStatusResolver
import com.hippo.unifile.UniFile
import com.hippo.util.ExceptionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** 重复/被包含的文件夹如何处理。 */
enum class FileDisposal {
    /** 移入下载目录下的 .recycle_bin，可手动找回 */
    RECYCLE_BIN,

    /** 直接递归删除，不可恢复 */
    PERMANENT_DELETE
}

/**
 * 梳理下载历史。
 *
 * 重新检查此前“恢复下载 / 从历史恢复”可能留下的重复项：
 * 同一画廊的多个目录、被另一个 gid 占用的目录、以及 pToken 集被更完整画廊包含的目录。
 * 每一组只保留最完整的一份（下载列表中的条目永远保留），其余：
 *
 *  - 文件夹按 [fileDisposal] 移入回收站或永久删除；
 *  - 下载历史记录不物理删除，标记为“已合并”（[DownloadHistory.DELETION_DUPLICATE_MERGED]
 *    / [DownloadHistory.DELETION_PROGRESSIVE_MERGED]）并写入合并目标 gid；
 *  - 清理/修正 DIRNAME 映射，避免已删除的目录继续占用映射。
 *
 * 判定保守：只有 pToken 集完整（hash 数不少于页数）的目录才参与包含关系判断；
 * 下载列表中的画廊只报告、不处理。
 */
class TidyDownloadHistoryTask(
    context: Context,
    private val fileDisposal: FileDisposal
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "TidyDownloadHistory"

        /** 与删除任务共用互斥组，避免并发操作同一下载目录 */
        private const val MUTEX_GROUP = "task:delete_range_download"
    }

    @Volatile
    private var isCancelled = false

    private val downloadManager: DownloadManager = EhApplication.getDownloadManager(context)

    private var scannedFolders = 0
    private var entryCount = 0
    private var groupCount = 0
    private var keptCount = 0
    private var protectedSkipped = 0
    private var markedMerged = 0
    private var disposedFolders = 0
    private var failedFolders = 0
    private var clearedMappings = 0
    private var fixedPaths = 0

    override fun getTaskId(): String = "tidy_download_history_${System.currentTimeMillis()}"

    override fun getTaskName(): String = context.getString(R.string.settings_download_tidy_download_history)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.CLEANUP

    override fun getTaskDescription(): String? =
        context.getString(R.string.settings_download_tidy_download_history_summary)

    override fun getMutexGroup(): String = MUTEX_GROUP

    override fun isPausable(): Boolean = false

    private class Entry(
        val gid: Long,
        val dirname: String,
        val dir: UniFile,
        val fileCount: Int,
        val mtime: Long,
        val inDownloadList: Boolean,
        var history: DownloadHistory?,
        var hashes: Set<String> = emptySet(),
        var pages: Int = 0
    ) {
        /** pToken 集完整才参与包含判定 */
        val complete: Boolean
            get() = hashes.isNotEmpty() && pages > 0 && hashes.size >= pages
    }

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            appendTaskLog(context.getString(R.string.tidy_download_history_started))
            coroutineContext[Job]?.ensureActive()

            val root = Settings.getDownloadLocation()
            if (root == null || !root.isDirectory) {
                appendTaskLog("下载目录不存在或无效")
                notifyError(Exception("下载目录不存在或无效"))
                return Result.failure(Exception("下载目录不存在或无效"))
            }

            // ── 阶段一：收集目录 ──
            updateProgress(2, context.getString(R.string.tidy_download_history_scanning))
            val entries = collectEntries(root)
            entryCount = entries.size
            appendTaskLog("扫描 $scannedFolders 个文件夹，纳入 ${entries.size} 个画廊目录")
            if (entries.size < 2) {
                appendTaskLog("没有可梳理的重复目录")
                notifyCompleted()
                return Result.success(Unit)
            }

            // ── 阶段二：读取 pToken ──
            coroutineContext[Job]?.ensureActive()
            readHashes(entries)
            val completeCount = entries.count { it.complete }
            appendTaskLog("读取到完整 pToken 集的目录 $completeCount 个")

            // ── 阶段三：聚类 ──
            coroutineContext[Job]?.ensureActive()
            updateProgress(40, context.getString(R.string.tidy_download_history_grouping))
            val components = buildComponents(entries)
            groupCount = components.count { it.size > 1 }
            appendTaskLog("发现 $groupCount 组重复/被包含目录")
            if (groupCount == 0) {
                appendTaskLog("没有需要处理的重复项")
                finishCaches()
                notifyCompleted()
                return Result.success(Unit)
            }

            // ── 阶段四：保留最完整的一份，处理其余 ──
            updateProgress(55, context.getString(R.string.tidy_download_history_processing))
            val dirnameOwner = EhDB.getDownloadDirnameMap()
            val disposedDirnames = HashSet<String>()
            val keepers = ArrayList<Entry>()

            for ((index, group) in components.withIndex()) {
                if (isCancelled) throw CancellationException("任务已取消")
                coroutineContext[Job]?.ensureActive()
                if (group.size < 2) continue

                val keeperIndex = group.maxWithOrNull(entryComparator(entries)) ?: continue
                val keeper = entries[keeperIndex]
                keepers.add(keeper)
                keptCount++

                for (i in group) {
                    if (i == keeperIndex) continue
                    val loser = entries[i]

                    // 下载列表中的画廊只报告，不动文件和历史
                    if (loser.inDownloadList) {
                        protectedSkipped++
                        appendTaskLog(
                            "保留（在下载列表中）: ${loser.dirname}（GID: ${loser.gid}）" +
                                " → 更完整的是 ${keeper.dirname}（GID: ${keeper.gid}）"
                        )
                        continue
                    }

                    // 同一物理目录被两个 gid 认领：只能把归属让给保留项，绝不能删掉这份文件夹
                    val sameFolder = loser.dirname == keeper.dirname

                    // 1) 先处理文件夹；失败则保留历史与映射原样，避免出现“记录已合并但文件夹还在”
                    if (!sameFolder) {
                        if (!disposeFolder(loser.dir)) {
                            failedFolders++
                            appendTaskLog("处理文件夹失败: ${loser.dirname}")
                            continue
                        }
                        disposedFolders++
                        disposedDirnames.add(loser.dirname)
                    }

                    // 2) 文件夹确实没了，才把下载历史标记为已合并（不物理删行）
                    if (loser.gid != keeper.gid) {
                        val history = loser.history
                        if (history != null && history.deletionType == DownloadHistory.DELETION_NONE) {
                            val type = if (isProgressive(loser, keeper)) {
                                DownloadHistory.DELETION_PROGRESSIVE_MERGED
                            } else {
                                DownloadHistory.DELETION_DUPLICATE_MERGED
                            }
                            EhDB.markDownloadHistoryDeleted(loser.gid, type, keeper.gid)
                            markedMerged++
                            appendTaskLog(
                                "标记已合并: ${loser.dirname}（GID: ${loser.gid}）" +
                                    " → ${keeper.dirname}（GID: ${keeper.gid}）"
                            )
                        }
                    }

                    // 3) 清理指向已消失目录的映射
                    if (dirnameOwner[loser.dirname] == loser.gid) {
                        EhDB.removeDownloadDirname(loser.gid)
                        clearedMappings++
                    }
                }

                updateProgress(
                    55 + ((index + 1) * 35 / components.size.coerceAtLeast(1)),
                    context.getString(R.string.tidy_download_history_processing_group, index + 1, components.size)
                )
            }

            // ── 阶段五：修正保留项的映射与历史路径 ──
            // 重新读一次映射表：上一步已删掉一批指向被处理目录的映射
            fixKeepers(keepers, disposedDirnames, EhDB.getDownloadDirnameMap())

            finishCaches()

            val summary = context.getString(
                R.string.tidy_download_history_summary,
                groupCount, keptCount, markedMerged, disposedFolders, failedFolders, protectedSkipped
            )
            Log.d(TAG, summary)
            appendTaskLog(summary)
            updateProgress(100, summary)
            notifyCompleted()
            Result.success(Unit)
        } catch (e: CancellationException) {
            appendTaskLog("梳理任务已取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            Log.e(TAG, "Tidy download history failed", e)
            appendTaskLog("梳理任务出错: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }

    // ==================== 收集 ====================

    private fun collectEntries(root: UniFile): List<Entry> {
        val entries = LinkedHashMap<String, Entry>()
        val downloadInfos = try {
            downloadManager.getAllDownloadInfoSnapshot()
        } catch (e: Exception) {
            Log.w(TAG, "读取下载列表失败", e)
            emptyList()
        }
        val protectedGids = downloadInfos.mapTo(HashSet()) { it.gid }

        // 1) 下载列表中的目录（永远保留）
        for (info in downloadInfos) {
            val dir = try {
                SpiderDen.getExistingGalleryDownloadDir(info)
            } catch (e: Exception) {
                null
            } ?: continue
            if (!dir.isDirectory) continue
            val dirname = dir.name ?: continue
            putEntry(entries, info.gid, dirname, dir, inDownloadList = true, history = null)
        }

        // 2) 有效下载历史指向的目录
        val histories = try {
            EhDB.getAllDownloadHistory().filter { it.deletionType == DownloadHistory.DELETION_NONE }
        } catch (e: Exception) {
            Log.w(TAG, "读取下载历史失败", e)
            emptyList()
        }
        for (history in histories) {
            val dirname = history.filePath?.takeIf { it.isNotEmpty() } ?: continue
            val dir = root.findFile(dirname) ?: continue
            if (!dir.isDirectory) continue
            val key = keyOf(history.gid, dirname)
            val existing = entries[key]
            if (existing != null) {
                existing.history = history
            } else {
                putEntry(
                    entries, history.gid, dirname, dir,
                    inDownloadList = protectedGids.contains(history.gid), history = history
                )
            }
        }

        // 3) 扫描下载目录，把没有被历史/下载列表覆盖的目录也纳入
        val children = try {
            root.listFiles()
        } catch (e: Exception) {
            null
        }
        for (file in children.orEmpty()) {
            if (isCancelled) break
            if (!file.isDirectory) continue
            val dirname = file.name ?: continue
            if (dirname.startsWith(".") || dirname == "thumb") continue
            val gid = RestoreSupport.parseGidFromDirName(dirname) ?: continue
            scannedFolders++
            val key = keyOf(gid, dirname)
            if (entries.containsKey(key)) continue
            putEntry(
                entries, gid, dirname, file,
                inDownloadList = protectedGids.contains(gid), history = null
            )
        }

        return entries.values.toList()
    }

    private fun putEntry(
        entries: MutableMap<String, Entry>,
        gid: Long,
        dirname: String,
        dir: UniFile,
        inDownloadList: Boolean,
        history: DownloadHistory?
    ) {
        val key = keyOf(gid, dirname)
        if (entries.containsKey(key)) return
        entries[key] = Entry(
            gid = gid,
            dirname = dirname,
            dir = dir,
            fileCount = RestoreSupport.countImageFiles(dir),
            mtime = dir.lastModified(),
            inDownloadList = inDownloadList,
            history = history
        )
    }

    // ==================== pToken ====================

    private fun readHashes(entries: List<Entry>) {
        val countByGid = entries.groupingBy { it.gid }.eachCount()
        for ((index, entry) in entries.withIndex()) {
            if (isCancelled) return
            if (index % 50 == 0) {
                updateProgress(
                    20 + (index * 20 / entries.size.coerceAtLeast(1)),
                    context.getString(R.string.tidy_download_history_reading, index + 1, entries.size)
                )
            }

            // 目录自带的 .ehviewer 最可靠
            val meta = EhviewerMetaParser.parse(entry.dir)
            if (meta != null && meta.hashes.isNotEmpty()) {
                entry.hashes = meta.hashes.toHashSet()
                entry.pages = meta.pages
                continue
            }

            // 没有 .ehviewer 时，只有该 gid 仅有一个目录才敢用 gid 级缓存
            if (countByGid[entry.gid] != 1) continue

            SimpleScanCache.getHashSet(entry.gid)?.let { cached ->
                entry.hashes = cached
            }
            try {
                val ptokensIndex = EhDB.getPtokensIndex(entry.gid)
                if (ptokensIndex != null) {
                    if (entry.hashes.isEmpty()) {
                        val set = SpiderTokenUtils.parsePtokenString(ptokensIndex.ptokens)
                        if (set.isNotEmpty()) entry.hashes = set
                    }
                    if (entry.pages <= 0) entry.pages = ptokensIndex.pages
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取 pToken 索引失败: gid=${entry.gid}", e)
            }
        }
    }

    // ==================== 聚类 ====================

    private suspend fun buildComponents(entries: List<Entry>): List<List<Int>> {
        val n = entries.size
        val uf = IntArray(n) { it }

        // 同一目录（不同 gid 抢占） / 同一 gid（多个目录）
        val byDirname = HashMap<String, Int>()
        val byGid = HashMap<Long, Int>()
        for (i in 0 until n) {
            byDirname.put(entries[i].dirname, i)?.let { SubsetContainment.union(uf, i, it) }
            byGid.put(entries[i].gid, i)?.let { SubsetContainment.union(uf, i, it) }
        }

        val completeIndices = entries.indices.filter { entries[it].complete }
        if (completeIndices.size > 1) {
            val tokenIds = HashMap<String, Int>()
            val hashes = Array(completeIndices.size) { k ->
                val set = entries[completeIndices[k]].hashes
                val arr = IntArray(set.size)
                var p = 0
                for (token in set) {
                    arr[p++] = tokenIds.getOrPut(token) { tokenIds.size }
                }
                arr.sort()
                arr
            }

            val job = coroutineContext[Job]
            val result = SubsetContainment.buildClusters(
                sortedHashes = hashes,
                onItem = { job?.ensureActive() }
            )

            // 包含关系聚类
            val components = HashMap<Int, MutableList<Int>>()
            for (k in completeIndices.indices) {
                val root = SubsetContainment.find(result.parent, k)
                components.getOrPut(root) { mutableListOf() }.add(k)
            }
            for (group in components.values) {
                if (group.size < 2) continue
                val first = completeIndices[group[0]]
                for (k in 1 until group.size) {
                    SubsetContainment.union(uf, first, completeIndices[group[k]])
                }
            }

            // 等集：包含判定只处理“hash 数更多”，完全相同需要单独合并
            val bySignature = HashMap<Set<String>, Int>()
            for (k in completeIndices.indices) {
                val entryIndex = completeIndices[k]
                val signature = entries[entryIndex].hashes
                val previous = bySignature.put(signature, entryIndex)
                if (previous != null) {
                    SubsetContainment.union(uf, previous, entryIndex)
                }
            }
        }

        val grouped = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            grouped.getOrPut(SubsetContainment.find(uf, i)) { mutableListOf() }.add(i)
        }
        return grouped.values.filter { it.size > 1 }
    }

    private fun entryComparator(entries: List<Entry>) = Comparator<Int> { a, b ->
        compareEntries(entries[a], entries[b])
    }

    /** 正数表示 a 更应保留 */
    private fun compareEntries(a: Entry, b: Entry): Int = when {
        // 先比内容完整度：绝不为了“保护下载列表”而去保留一份更不完整的内容
        a.hashes.size != b.hashes.size -> a.hashes.size.compareTo(b.hashes.size)
        a.pages != b.pages -> a.pages.compareTo(b.pages)
        // 内容等量时优先保留下载列表里的那份，避免无谓地留下重复
        a.inDownloadList != b.inDownloadList -> if (a.inDownloadList) 1 else -1
        a.fileCount != b.fileCount -> a.fileCount.compareTo(b.fileCount)
        a.mtime != b.mtime -> a.mtime.compareTo(b.mtime)
        else -> b.gid.compareTo(a.gid)
    }

    private fun isProgressive(loser: Entry, keeper: Entry): Boolean {
        if (loser.hashes.isEmpty() || keeper.hashes.isEmpty()) return false
        if (loser.hashes.size >= keeper.hashes.size) return false
        return keeper.hashes.containsAll(loser.hashes)
    }

    // ==================== 处理 ====================

    private fun disposeFolder(dir: UniFile): Boolean {
        return try {
            // 兜底保护：绝不对下载根目录本身动手
            val root = Settings.getDownloadLocation()
            if (root != null && dir.uri == root.uri) {
                Log.w(TAG, "拒绝处理下载根目录")
                return false
            }
            if (dir.name.isNullOrEmpty()) return false
            if (!dir.exists()) return true
            when (fileDisposal) {
                FileDisposal.RECYCLE_BIN -> GalleryFileHelper.moveToRecycleBin(dir)
                FileDisposal.PERMANENT_DELETE -> GalleryFileHelper.deleteRecursively(dir)
            }
        } catch (e: Exception) {
            Log.w(TAG, "处理文件夹失败: ${dir.name}", e)
            false
        }
    }

    private fun fixKeepers(
        keepers: List<Entry>,
        disposedDirnames: Set<String>,
        dirnameOwner: Map<String, Long>
    ) {
        for (keeper in keepers) {
            if (isCancelled) return

            // 保留项没有有效历史时补一条，避免梳理后目录在历史里“消失”
            if (!keeper.inDownloadList && EhDB.getDownloadHistory(keeper.gid) == null) {
                val token = EhviewerMetaParser.parse(keeper.dir)?.token
                EhDB.recordDownloadHistoryFromScan(keeper.gid, token, keeper.dirname, keeper.dirname)
            }

            // 历史路径若指向已被处理的目录，改指保留目录
            val history = keeper.history ?: EhDB.getDownloadHistory(keeper.gid)
            if (history != null && history.filePath != null &&
                history.filePath != keeper.dirname &&
                disposedDirnames.contains(history.filePath)
            ) {
                EhDB.updateDownloadHistoryFilePath(keeper.gid, keeper.dirname)
                fixedPaths++
            }

            // 映射缺失或仍指向已处理的目录时，改指保留目录
            val owner = dirnameOwner[keeper.dirname]
            val mapped = EhDB.getDownloadDirname(keeper.gid)
            val stale = mapped == null || mapped != keeper.dirname
            if (stale && (owner == null || owner == keeper.gid)) {
                val replaceExisting = mapped != null
                val claim = EhDB.claimDownloadDirname(keeper.gid, keeper.dirname, replaceExisting)
                if (claim == EhDB.DIRNAME_CLAIM_UPDATED) {
                    clearedMappings++
                }
            }
        }
    }

    private fun finishCaches() {
        try {
            SimpleScanCache.invalidate()
        } catch (e: Exception) {
            Log.w(TAG, "invalidate SimpleScanCache failed", e)
        }
        try {
            ProgressiveStats.invalidate()
        } catch (e: Exception) {
            Log.w(TAG, "invalidate ProgressiveStats failed", e)
        }
        try {
            DownloadHistoryStatusResolver.invalidateAll()
        } catch (e: Exception) {
            Log.w(TAG, "invalidate DownloadHistoryStatusResolver failed", e)
        }
        try {
            ResponseCache.getInstance().invalidateGalleries()
        } catch (e: Exception) {
            Log.w(TAG, "invalidate ResponseCache failed", e)
        }
    }

    private fun keyOf(gid: Long, dirname: String): String = "$gid|$dirname"

    override suspend fun cancel() {
        isCancelled = true
        notifyCancelled()
    }
}
