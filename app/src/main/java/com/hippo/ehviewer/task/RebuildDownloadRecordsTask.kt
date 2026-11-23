package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.cache.GalleryCacheManager
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.dao.DownloadDirname
import com.hippo.ehviewer.dao.DownloadHistory
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 重建下载列表（快速路径）。
 *
 * 输入 = `DOWNLOAD_HISTORY`（只取“最终状态”，即 `DELETION_TYPE == DELETION_NONE`，
 * 已合并 / 已删除的记录一律跳过）∪ 下载目录里仍存在的画廊目录。
 *
 * 刻意**不做** [RestoreCandidateValidator] 的四步校验：那一步要为全库读取 pToken 集合并
 * 做两两包含判断，1.5 万条记录是小时级；这里只做「按 gid 去重 + 文件数择优」，把
 * “列表回来”和“内容去重”拆成两件事。
 *
 * 元数据全部来自本地：
 *  1. `.ehviewer`（VERSION2 文本 / CBOR）→ gid、token、总页数
 *  2. `.ehviewer.extra.json` → 标题、缩略图、分类、标签、页数
 *  3. 目录内图片数量 → 完成度
 *
 * 结果：文件数 ≥ 总页数 → 已完成（STATE_FINISH）；否则闲置（STATE_NONE）。
 * 默认不发起任何下载；[queueMissingDownloads] 为 true 时才把缺页的加入等待队列。
 */
class RebuildDownloadRecordsTask @JvmOverloads constructor(
    context: Context,
    private val queueMissingDownloads: Boolean = false,
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "RebuildDownloadRecordsTask"
        private const val PROGRESS_STEP = 200
        private val IMAGE_EXTENSIONS = arrayOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp"
        )
    }

    /** 磁盘上的一个画廊目录（`.ehviewer` 只读一次） */
    private class DiskDir(
        val dir: UniFile,
        val gid: Long,
        val dirname: String,
        val token: String?,
        val pages: Int,
        val fileCount: Int,
    )

    /** 历史记录与磁盘目录合并后的候选 */
    private class Candidate(
        val gid: Long,
        var disk: DiskDir?,
        var dirname: String?,
        var token: String?,
        var title: String?,
        var time: Long,
        var historyTotalFiles: Int,
    )

    private class Stats {
        var addedFinished = 0
        var addedIdle = 0
        var queued = 0
        var skippedExisting = 0
        var skippedMerged = 0
        var skippedNoMeta = 0
        var skippedDirConflict = 0
    }

    @Volatile private var isCancelled = false

    private val taskIdValue = "rebuild_download_records_" + System.currentTimeMillis()
    private val stats = Stats()

    override fun getTaskId(): String = taskIdValue

    override fun getTaskName(): String =
        context.getString(R.string.settings_download_rebuild_download_records)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.IMPORT

    override fun getTaskDescription(): String? =
        context.getString(R.string.settings_download_rebuild_download_records_summary)

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            appendTaskLog("开始重建下载列表（本地快速路径，不联网、不下载）")

            coroutineContext[Job]?.ensureActive()

            val downloadManager = EhApplication.getDownloadManager(context)
            val root = Settings.getDownloadLocation()
            if (root == null || !root.isDirectory) {
                appendTaskLog("下载目录不存在或无效")
                notifyError(Exception("下载目录不存在或无效"))
                return Result.failure(Exception("下载目录不存在或无效"))
            }

            // ── 阶段一：扫描磁盘目录（每个目录只读一次 .ehviewer）──
            appendTaskLog("正在扫描下载目录…")
            val diskDirs = scanDiskDirs(root)
            appendTaskLog("磁盘上识别出 ${diskDirs.size} 个画廊目录")

            coroutineContext[Job]?.ensureActive()

            // ── 阶段二：合并下载历史（只保留最终状态）──
            val histories = try {
                EhDB.getAllDownloadHistory() ?: emptyList()
            } catch (e: Throwable) {
                Log.w(TAG, "load download history failed", e)
                emptyList()
            }
            appendTaskLog("下载历史共 ${histories.size} 条")

            val candidates = LinkedHashMap<Long, Candidate>()
            // 被合并 / 已删除的 gid：即使目录还在磁盘上，也不能重新出现在下载列表
            val blockedGids = HashSet<Long>()
            for (history in histories) {
                if (isCancelled) throw CancellationException("任务已取消")
                if (history.deletionType != DownloadHistory.DELETION_NONE) {
                    // 已合并 / 已删除：属于旧状态，不允许重新出现在下载列表
                    blockedGids.add(history.gid)
                    stats.skippedMerged++
                    continue
                }
                val gid = history.gid
                if (gid <= 0) continue
                val disk = diskDirs[gid]
                val time = when {
                    history.lastDownloadedAt > 0 -> history.lastDownloadedAt
                    history.completedAt > 0 -> history.completedAt
                    else -> 0L
                }
                candidates[gid] = Candidate(
                    gid = gid,
                    disk = disk,
                    dirname = history.filePath?.takeIf { it.isNotEmpty() } ?: disk?.dirname,
                    token = history.token?.takeIf { it.isNotEmpty() },
                    title = history.title?.takeIf { it.isNotEmpty() },
                    time = time,
                    historyTotalFiles = history.totalFiles,
                )
            }
            appendTaskLog("其中最终状态 ${candidates.size} 条，跳过已合并/已删除 ${stats.skippedMerged} 条")

            // 磁盘上有、历史里没有的目录也补进来（扫描历史漏掉的那部分）
            var diskOnly = 0
            for ((gid, disk) in diskDirs) {
                if (candidates.containsKey(gid)) continue
                if (blockedGids.contains(gid)) continue
                candidates[gid] = Candidate(
                    gid = gid,
                    disk = disk,
                    dirname = disk.dirname,
                    token = null,
                    title = null,
                    time = 0L,
                    historyTotalFiles = 0,
                )
                diskOnly++
            }
            if (diskOnly > 0) appendTaskLog("磁盘上另有 $diskOnly 个目录不在历史中，一并重建")

            if (candidates.isEmpty()) {
                appendTaskLog("没有可重建的画廊")
                notifyCompleted()
                return Result.success(Unit)
            }

            // ── 阶段三：组织下载记录 ──
            val dirnameOwners = try {
                EhDB.getDownloadDirnameMap()
            } catch (e: Throwable) {
                Log.w(TAG, "read dirname map failed", e)
                emptyMap()
            }

            val toInsert = ArrayList<DownloadInfo>(candidates.size)
            val dirnameRows = ArrayList<DownloadDirname>(candidates.size)
            var processed = 0

            for (candidate in candidates.values) {
                if (isCancelled) throw CancellationException("任务已取消")
                processed++
                if (processed % PROGRESS_STEP == 0) {
                    updateProgress(processed, candidates.size, "解析 $processed/${candidates.size}")
                }

                if (downloadManager.containDownloadInfo(candidate.gid)) {
                    stats.skippedExisting++
                    continue
                }

                val disk = candidate.disk
                val cache = disk?.let { readExtraCache(candidate.gid, it.dir) }

                var pages = disk?.pages ?: 0
                if (pages <= 0) pages = cache?.pages ?: 0
                if (pages <= 0 && candidate.historyTotalFiles > 0) {
                    // 目录已删除，只能借用历史里记过的文件数做展示
                    pages = candidate.historyTotalFiles
                }
                val fileCount = disk?.fileCount ?: 0

                val token = disk?.token?.takeIf { it.isNotEmpty() }
                    ?: cache?.token?.takeIf { it.isNotEmpty() }
                    ?: candidate.token
                if (token.isNullOrEmpty() && disk == null) {
                    // 没有目录也没有 token：加进去也打不开
                    stats.skippedNoMeta++
                    continue
                }

                val dirname = candidate.dirname ?: disk?.dirname
                if (dirname != null) {
                    val owner = dirnameOwners[dirname]
                    if (owner != null && owner != candidate.gid) {
                        stats.skippedDirConflict++
                        continue
                    }
                }

                val info = DownloadInfo()
                info.gid = candidate.gid
                info.token = token ?: ""
                info.title = cache?.title?.takeIf { it.isNotEmpty() }
                    ?: candidate.title
                    ?: titleFromDirname(dirname)
                    ?: candidate.gid.toString()
                info.titleJpn = cache?.titleJpn
                info.thumb = cache?.thumb
                info.category = cache?.category ?: 0
                info.posted = cache?.posted
                info.uploader = cache?.uploader
                info.rating = cache?.rating ?: 0f
                info.simpleLanguage = cache?.simpleLanguage
                info.simpleTags = cache?.simpleTags
                info.time = if (candidate.time > 0) candidate.time else System.currentTimeMillis()
                info.label = null
                info.legacy = 0

                val complete = pages > 0 && fileCount >= pages
                if (complete) {
                    info.pages = pages
                    info.finished = pages
                    info.state = DownloadInfo.STATE_FINISH
                    stats.addedFinished++
                } else {
                    info.pages = if (pages > 0) pages else fileCount
                    info.finished = fileCount
                    if (queueMissingDownloads && pages > 0) {
                        info.state = DownloadInfo.STATE_WAIT
                        stats.queued++
                    } else {
                        info.state = DownloadInfo.STATE_NONE
                        stats.addedIdle++
                    }
                }

                toInsert.add(info)
                if (dirname != null) {
                    dirnameRows.add(DownloadDirname(candidate.gid, dirname))
                }
            }

            if (isCancelled) throw CancellationException("任务已取消")

            // ── 阶段四：一次性落库 + 一次性刷新 ──
            updateProgress(candidates.size, candidates.size, "写入 ${toInsert.size} 条记录…")
            EhDB.putDownloadInfoList(toInsert)
            EhDB.putDownloadDirnameList(dirnameRows)
            if (toInsert.isNotEmpty()) {
                downloadManager.reloadAllFromDatabase()
            }

            val summary = "重建完成：已加入 ${toInsert.size} 条" +
                "（已完成 ${stats.addedFinished} / 闲置 ${stats.addedIdle}" +
                (if (stats.queued > 0) " / 加入下载队列 ${stats.queued}" else "") + "）；" +
                "跳过：已在列表 ${stats.skippedExisting}，已合并/已删除 ${stats.skippedMerged}，" +
                "缺元数据 ${stats.skippedNoMeta}，目录冲突 ${stats.skippedDirConflict}"
            Log.d(TAG, summary)
            appendTaskLog(summary)
            notifyCompleted()
            Result.success(Unit)
        } catch (e: CancellationException) {
            Log.d(TAG, "rebuild cancelled")
            appendTaskLog("任务已取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Throwable) {
            Log.e(TAG, "rebuild failed", e)
            appendTaskLog("任务失败: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }

    // ==================== 磁盘目录 ====================

    /**
     * 扫描下载目录，返回 gid → 目录。
     *
     * gid 优先取 `.ehviewer` 里的值（与目录名前缀不一致时以文件为准），解析失败再退回
     * 目录名前缀。同一 gid 出现多个目录时保留文件更多的那个。
     */
    private fun scanDiskDirs(root: UniFile): Map<Long, DiskDir> {
        val result = HashMap<Long, DiskDir>()
        val files = root.listFiles() ?: return result
        for (file in files) {
            if (isCancelled) break
            if (!file.isDirectory) continue
            val name = file.name ?: continue
            if (name.startsWith(".") || name == "thumb") continue

            var gid = -1L
            var token: String? = null
            var pages = 0
            val siFile = file.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
            if (siFile != null && siFile.exists() && siFile.isFile) {
                try {
                    val header = SpiderInfo.readHeader(siFile) ?: SpiderInfo.readCborHeader(siFile)
                    if (header != null) {
                        if (header.gid > 0) gid = header.gid
                        if (header.pages > 0) pages = header.pages
                        if (!header.token.isNullOrEmpty()) token = header.token
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "read header failed: $name", e)
                }
            }
            if (gid <= 0) {
                gid = parseGidFromDirName(name) ?: continue
            }

            val fileCount = countImageFiles(file)
            val existing = result[gid]
            if (existing == null || fileCount > existing.fileCount) {
                result[gid] = DiskDir(file, gid, name, token, pages, fileCount)
            }
        }
        return result
    }

    private fun parseGidFromDirName(dirName: String): Long? {
        var index = 0
        while (index < dirName.length && dirName[index].isDigit()) index++
        if (index == 0) return null
        return try {
            dirName.substring(0, index).toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun titleFromDirname(dirname: String?): String? {
        if (dirname == null) return null
        val idx = dirname.indexOf(" - ")
        return if (idx > 0) dirname.substring(idx + 3).takeIf { it.isNotEmpty() } else null
    }

    private fun countImageFiles(dir: UniFile): Int {
        return try {
            dir.listFiles()?.count { file ->
                if (!file.isFile) return@count false
                val name = file.name ?: return@count false
                if (name.startsWith(".")) return@count false
                IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    // ==================== 本地附加信息 ====================

    /** 只读 `.ehviewer.extra.json`（`.ehviewer` 已在扫描阶段读过） */
    private fun readExtraCache(gid: Long, dir: UniFile): GalleryDetail? {
        return try {
            GalleryCacheManager.getInstance(context).readGalleryCache(gid, dir)
        } catch (e: Throwable) {
            Log.w(TAG, "read .ehviewer.extra.json failed: gid=$gid", e)
            null
        }
    }

    override fun isPausable(): Boolean = false

    override suspend fun cancel() {
        isCancelled = true
        notifyCancelled()
    }
}
