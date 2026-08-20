package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.dao.DownloadHistory
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.Date
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class MergeDuplicateGalleryTask @JvmOverloads constructor(
    context: Context,
    private val taskId: String = "merge_duplicate_gallery_${System.currentTimeMillis()}",
    /** 指定只扫描并合并单个画廊（gid），-1L 表示扫描全部 */
    private val targetGid: Long = -1L,
    /** 自动触发时跳过数据库全量备份，避免每次下载完成都复制整个 DB 文件 */
    private val skipBackup: Boolean = false
) : BaseBackgroundTask(context) {

    private val galleryGroups = mutableListOf<GalleryGroup>()
    private val errorLog = StringBuilder()
    private val mergeLog = StringBuilder()

    private val mergedCount = AtomicInteger(0)
    private val skippedCount = AtomicInteger(0)
    private val copiedCount = AtomicInteger(0)
    private val deletedCount = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)
    @Volatile
    private var lastError = ""

    private val targetDirLocks = ConcurrentHashMap<String, Mutex>()

    /** 是否为单画廊合并模式 */
    private val isSingleMode: Boolean get() = targetGid >= 0L

    /** 单画廊模式下缓存的画廊标题，用于任务名 */
    private var singleTitle: String? = null

    override fun getTaskId(): String = taskId

    override fun getTaskName(): String = if (isSingleMode) {
        val title = singleTitle
        if (title != null) {
            context.getString(R.string.merge_duplicate_gallery_single_title) + " - $title"
        } else {
            context.getString(R.string.merge_duplicate_gallery_single_title)
        }
    } else {
        context.getString(R.string.settings_download_merge_duplicate_gallery)
    }

    override fun getTaskDescription(): String = if (isSingleMode) {
        context.getString(R.string.merge_duplicate_gallery_single_summary)
    } else {
        context.getString(R.string.settings_download_merge_duplicate_gallery_summary)
    }

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.MERGE

    override fun isUniqueTask(): Boolean = !isSingleMode

    override fun isPersistable(): Boolean = !isSingleMode

    override fun getTaskPersistData(): String {
        return JSONObject().toString()
    }

    companion object {
        private const val TAG = "MergeDuplicateGalleryTask"
        private const val SHELL_TIMEOUT_SECONDS = 300L

        /**
         * 合并扫描结果缓存，1 小时内共享扫描结果避免重复解析文件元数据。
         * 定义在 companion object 内以便访问私有的 GalleryFolder 类型。
         */
        object MergeScanCache {
            private const val CACHE_TTL_MS = 60 * 60 * 1000L

            @Volatile
            private var scanTime: Long = 0L
            @Volatile
            private var cachedBuckets: Map<String, List<GalleryFolder>>? = null

            @Synchronized
            fun get(): Map<String, List<GalleryFolder>>? {
                return if (System.currentTimeMillis() - scanTime < CACHE_TTL_MS) {
                    cachedBuckets
                } else {
                    null
                }
            }

            @Synchronized
            fun put(buckets: Map<String, List<GalleryFolder>>) {
                scanTime = System.currentTimeMillis()
                cachedBuckets = buckets
            }

            @Synchronized
            fun invalidate() {
                scanTime = 0L
                cachedBuckets = null
            }
        }

        const val STEP_SCAN = 0
        const val STEP_ANALYZE = 1
        const val STEP_MERGE = 2
        const val STEP_BACKUP = 3

        private const val SCAN_WEIGHT = 20
        private const val ANALYZE_WEIGHT = 20
        private const val BACKUP_WEIGHT = 10
        private const val MERGE_WEIGHT = 50
        private const val HASH_SIMILARITY_THRESHOLD = 0.75f

        private val IMAGE_EXTENSIONS = setOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp"
        )

        @JvmStatic
        fun restore(context: Context, taskId: String, persistData: String?): MergeDuplicateGalleryTask? {
            if (persistData == null) {
                return null
            }
            return try {
                MergeDuplicateGalleryTask(context, taskId)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 为单个已下载完成的画廊创建合并任务，只扫描与其同名的其他画廊目录
         */
        @JvmStatic
        fun mergeForGallery(context: Context, gid: Long): MergeDuplicateGalleryTask {
            val taskId = "merge_single_${gid}_${System.currentTimeMillis()}"
            val task = MergeDuplicateGalleryTask(context, taskId, gid, true)
            // 预先加载标题用于任务名
            val info = EhDB.getDownloadInfo(gid)
            if (info != null) {
                val title = com.hippo.ehviewer.client.EhUtils.getSuitableTitle(info)
                task.singleTitle = title
            }
            return task
        }

        /**
         * 解析 Shell 批量扫描输出（D 行 + uniq -c 计数行）。纯函数，便于单测。
         */
        @JvmStatic
        fun parseShellScanOutput(raw: String): Map<String, DirScanEntry> {
            val mtimes = HashMap<String, Long>()
            val counts = HashMap<String, Int>()
            for (line in raw.lines()) {
                if (line.isBlank()) continue
                if (line.startsWith("D\t")) {
                    val parts = line.substring(2).split("\t", limit = 2)
                    val name = parts.getOrElse(0) { "" }
                    if (name.isEmpty()) continue
                    mtimes[name] = parts.getOrElse(1) { "0" }.toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
                } else {
                    val trimmed = line.trim()
                    val sp = trimmed.indexOf(' ')
                    if (sp <= 0) continue
                    val count = trimmed.substring(0, sp).toIntOrNull() ?: continue
                    val path = trimmed.substring(sp + 1).trim()
                    val name = path.removePrefix("./")
                    if (name.isNotEmpty() && name != ".") {
                        counts[name] = count
                    }
                }
            }
            val result = LinkedHashMap<String, DirScanEntry>()
            for ((name, mtime) in mtimes) {
                result[name] = DirScanEntry(
                    dirname = name,
                    fileCount = counts[name] ?: 0,
                    mtime = mtime
                )
            }
            return result
        }

        /**
         * 原生扫描目录（纯 java.io.File，零进程）。纯函数，便于单测。
         */
        @JvmStatic
        fun scanNativeDirectory(root: File): Map<String, DirScanEntry>? {
            val children = root.listFiles() ?: return null
            val result = LinkedHashMap<String, DirScanEntry>()
            for (dir in children) {
                if (!dir.isDirectory) continue
                val name = dir.name
                result[name] = DirScanEntry(
                    dirname = name,
                    fileCount = countFilesNative(dir),
                    mtime = dir.lastModified()
                )
            }
            return result
        }

        @JvmStatic
        fun countFilesNative(dir: File): Int {
            var total = 0
            val children = dir.listFiles() ?: return 0
            for (child in children) {
                total += if (child.isDirectory) countFilesNative(child) else 1
            }
            return total
        }
    }

    data class DirScanEntry(
        val dirname: String,
        val fileCount: Int,
        val mtime: Long
    )

    private fun getRealDownloadPath(): String? {
        val downloadDir = Settings.getDownloadLocation() ?: return null
        return if (downloadDir.uri.scheme == "file") downloadDir.uri.path else null
    }

    /** 按设置分发快速扫描：Shell 批量（默认）或原生 Java */
    private fun runFastScan(downloadPath: String): Map<String, DirScanEntry>? {
        return when (Settings.getMergeScanMode()) {
            Settings.MERGE_SCAN_MODE_NATIVE -> execNativeScan(downloadPath)
            else -> execShellScan(downloadPath)
        }
    }

    /**
     * Shell 批量扫描：进程数与目录数量无关，只启动常数个进程。
     * 用 find/sort/uniq 一次性取回所有子目录名、修改时间与文件数；
     * .ehviewer 元数据改由 Kotlin 用 java.io.File 读取解析（见 buildFromDirScan），
     * 不再为每个目录启动 sed/awk，且顺带修复 v2 元数据解析错误。
     */
    private fun execShellScan(downloadPath: String): Map<String, DirScanEntry>? {
        val escapedPath = downloadPath.replace("'", "'\\''")
        val script = (
            "cd '$escapedPath' 2>/dev/null || exit 1;" +
            "find . -mindepth 1 -maxdepth 1 -type d -printf 'D\\t%f\\t%T@\\n';" +
            "find . -type f -printf '%h\\t\\n' | sort | uniq -c"
            )
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = java.util.concurrent.CompletableFuture<String>()
            val reader = process.inputStream.bufferedReader()
            Thread {
                try {
                    output.complete(buildString {
                        reader.forEachLine { line ->
                            append(line)
                            append('\n')
                        }
                    })
                } catch (_: Exception) {
                    output.complete("")
                }
            }.apply {
                isDaemon = true
                start()
            }
            val completed = process.waitFor(SHELL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                Log.w(TAG, "Shell scan timed out after ${SHELL_TIMEOUT_SECONDS}s, will use fallback")
                return null
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Log.w(TAG, "Shell scan failed with exit code $exitCode")
                null
            } else {
                parseShellScanOutput(output.get())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shell scan failed: ${e.message}")
            null
        }
    }

    /**
     * 原生扫描：纯 java.io.File，零进程，比 Shell 批量更快更稳。
     * 仅当下载目录为 file:// 时使用。
     */
    private fun execNativeScan(downloadPath: String): Map<String, DirScanEntry>? {
        val root = File(downloadPath)
        if (!root.exists() || !root.isDirectory) return null
        return scanNativeDirectory(root)
    }

    private fun readEhviewerMetaFromFile(dirFile: File): EhviewerMeta? {
        val eh = File(dirFile, SpiderQueen.SPIDER_INFO_FILENAME)
        if (!eh.isFile) return null
        return try {
            val reader = BufferedReader(InputStreamReader(FileInputStream(eh), StandardCharsets.UTF_8))
            try {
                val parsed = EhviewerMetaParser.parseFromReader(reader) ?: return null
                val meta = EhviewerMeta()
                meta.gid = parsed.gid.toString()
                meta.token = parsed.token
                meta.files.putAll(parsed.indexToHash)
                meta.hashes.addAll(parsed.hashes)
                meta
            } finally {
                reader.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(): Result<Unit> {
        initErrorLog()
        initMergeLog()
        return try {
            logInfo("开始扫描下载目录")
            dispatchProgress(STEP_SCAN, context.getString(R.string.merge_scanning_galleries), 0, 1)
            ensureNotCancelled()
            if (!scanDownloadedGalleries()) {
                logError("扫描下载目录失败")
                return Result.failure(IllegalStateException(lastError.ifEmpty { "扫描下载目录失败" }))
            }

            ensureNotCancelled()
            logInfo("开始分析重复画廊")
            dispatchProgress(STEP_ANALYZE, context.getString(R.string.merge_analyzing_galleries), 0, 1)
            if (!analyzeDuplicateGalleries()) {
                logError("分析重复画廊失败")
                return Result.failure(IllegalStateException(lastError.ifEmpty { "分析重复画廊失败" }))
            }

            ensureNotCancelled()
            if (skipBackup) {
                logInfo("自动合并模式：跳过数据库备份")
            } else {
                logInfo("开始备份数据库")
                dispatchProgress(STEP_BACKUP, context.getString(R.string.merge_backing_up_database), 0, 1)
                if (!backupDatabase()) {
                    logError("备份数据库失败")
                    return Result.failure(IllegalStateException(lastError.ifEmpty { "备份数据库失败" }))
                }
            }

            ensureNotCancelled()
            logInfo("开始合并重复画廊")
            dispatchProgress(STEP_MERGE, context.getString(R.string.merge_merging_galleries), 0, maxOf(galleryGroups.size, 1))
            if (!mergeDuplicateGalleries()) {
                logError("合并重复画廊失败")
                return Result.failure(IllegalStateException(lastError.ifEmpty { "合并重复画廊失败" }))
            }

            notifyCompleted()
            logInfo("任务完成: 合并 ${mergedCount.get()} 组，跳过 ${skippedCount.get()} 组，复制 ${copiedCount.get()} 个文件，删除 ${deletedCount.get()} 个源目录")
            Result.success(Unit)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled()
                logInfo("任务取消")
                return Result.failure(e)
            }
            lastError = e.message ?: "未知错误"
            logError("合并过程中发生错误: ${e.message}")
            generateErrorReport()
            notifyError(e)
            Result.failure(e)
        }
    }

    private suspend fun ensureNotCancelled() {
        coroutineContext.ensureActive()
    }

    private fun dispatchProgress(step: Int, message: String, current: Int, total: Int) {
        val percent = calculateWeightedPercent(step, current, total)
        if (total > 0) {
            val detail = "$message (${minOf(current, total)}/$total)"
            updateProgress(percent, detail)
        } else {
            updateProgress(percent, message)
        }
    }

    private fun calculateWeightedPercent(step: Int, current: Int, total: Int): Int {
        val (base, span) = when (step) {
            STEP_SCAN -> 0 to SCAN_WEIGHT
            STEP_ANALYZE -> SCAN_WEIGHT to ANALYZE_WEIGHT
            STEP_BACKUP -> (SCAN_WEIGHT + ANALYZE_WEIGHT) to BACKUP_WEIGHT
            STEP_MERGE -> (SCAN_WEIGHT + ANALYZE_WEIGHT + BACKUP_WEIGHT) to MERGE_WEIGHT
            else -> return 0
        }
        if (total <= 0) {
            return base
        }
        val stagePercent = ((minOf(maxOf(current, 0), total) * 100L) / total).toInt()
        return base + stagePercent * span / 100
    }

    private fun initErrorLog() {
        errorLog.setLength(0)
        errorLog.append("=== 合并重复画廊错误日志 ===\n")
        errorLog.append("开始时间: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            .append("\n\n")
    }

    private fun initMergeLog() {
        mergeLog.setLength(0)
        mergeLog.append("=== 合并重复画廊操作日志 ===\n")
        mergeLog.append("开始时间: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            .append("\n\n")
    }

    @Synchronized
    private fun logError(message: String) {
        errorLog.append("[")
            .append(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
            .append("] ERROR: ")
            .append(message)
            .append("\n")
        appendTaskLog("ERROR: %s", message)
        Log.e(TAG, message)
    }

    @Synchronized
    private fun logInfo(message: String) {
        mergeLog.append("[")
            .append(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
            .append("] INFO: ")
            .append(message)
            .append("\n")
        appendTaskLog("INFO: %s", message)
        Log.i(TAG, message)
    }

    private fun generateErrorReport() {
        try {
            val downloadDir = Settings.getDownloadLocation() ?: return
            val sdf = SimpleDateFormat("yyyyMMddHHmm", Locale.US)
            val fileName = "merge-error-${sdf.format(Date())}.log"
            val logFile = downloadDir.createFile(fileName)
            if (logFile != null) {
                logFile.openOutputStream().use { os ->
                    os?.write(errorLog.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate error report", e)
        }
    }

    private suspend fun scanDownloadedGalleries(): Boolean {
        return try {
            val cachedBuckets = MergeScanCache.get()
            if (cachedBuckets != null) {
                logInfo("使用缓存扫描结果（${cachedBuckets.size} 组）")
                return rebuildFromCache(cachedBuckets)
            }

            var infos = EhDB.getAllDownloadInfo()
            if (infos == null) {
                infos = Collections.emptyList()
            }
            val total = infos.size

            val targetCleanName: String? = if (isSingleMode) {
                val targetInfo = infos.find { it.gid == targetGid }
                if (targetInfo == null) {
                    lastError = "未找到目标下载记录: gid=$targetGid"
                    logError(lastError)
                    return false
                }
                val targetDir = SpiderDen.getGalleryDownloadDir(targetInfo)
                if (targetDir == null || !targetDir.exists() || !targetDir.isDirectory) {
                    lastError = "目标下载目录不存在: gid=$targetGid"
                    logError(lastError)
                    return false
                }
                val targetName = targetDir.name ?: targetInfo.gid.toString()
                removeIdPrefix(targetName).also {
                    logInfo("单画廊模式: 目标画廊已清理名称=\"$it\" (gid=$targetGid)")
                }
            } else {
                null
            }

            val realPath = getRealDownloadPath()
            val modeName = when (Settings.getMergeScanMode()) {
                Settings.MERGE_SCAN_MODE_NATIVE -> "原生 Java"
                else -> "Shell"
            }

            if (realPath != null) {
                logInfo("尝试 $modeName 快速扫描: $realPath")
                val entries = runFastScan(realPath)
                if (entries != null && entries.isNotEmpty()) {
                    logInfo("$modeName 扫描完成，发现 ${entries.size} 个目录")
                    return buildFromDirScan(entries, infos, targetCleanName, realPath)
                }
                logInfo("$modeName 扫描失败或无结果，回退到 Java 扫描")
            } else {
                logInfo("下载目录不是 file://，回退到 Java 扫描")
            }
            scanDownloadedGalleriesJava(infos, total, targetCleanName)
        } catch (t: Throwable) {
            lastError = t.message ?: "扫描失败"
            logError("扫描失败: ${t.message}")
            false
        }
    }

    /**
     * 用快速扫描结果（Shell 或原生）构建重复分组。
     * 目录→下载记录匹配优先用目录名中的 gid 前缀走 O(1) 索引，
     * 避免对每条下载记录做 UniFile 往返；无 gid 前缀时回退到名称匹配。
     * .ehviewer 元数据统一由 java.io.File 读取并用 EhviewerMetaParser 解析。
     */
    private fun buildFromDirScan(
        entries: Map<String, DirScanEntry>,
        infos: List<DownloadInfo>,
        targetCleanName: String?,
        realPath: String
    ): Boolean {
        val gidToInfo = HashMap<Long, DownloadInfo>(infos.size)
        for (info in infos) {
            gidToInfo[info.gid] = info
        }
        var dirnameToInfo: Map<String, DownloadInfo>? = null

        fun infoFor(dirname: String): DownloadInfo? {
            val id = extractIdFromFolderName(dirname)
            if (id != null) {
                val info = gidToInfo[id.toLong()]
                if (info != null) return info
            }
            if (dirnameToInfo == null) {
                val map = LinkedHashMap<String, DownloadInfo>()
                for (info in infos) {
                    val dir = SpiderDen.getGalleryDownloadDir(info) ?: continue
                    if (!dir.exists() || !dir.isDirectory) continue
                    val name = dir.name ?: info.gid.toString()
                    map[name] = info
                }
                dirnameToInfo = map
            }
            return dirnameToInfo?.get(dirname)
        }

        val buckets = LinkedHashMap<String, MutableList<GalleryFolder>>()
        for ((dirname, entry) in entries) {
            if (targetCleanName != null && removeIdPrefix(dirname) != targetCleanName) {
                continue
            }
            val info = infoFor(dirname) ?: continue
            val dir = SpiderDen.getGalleryDownloadDir(info) ?: continue
            val cleanName = removeIdPrefix(dirname)
            val ehMeta = readEhviewerMetaFromFile(File(realPath, dirname))

            val folder = GalleryFolder(
                info = info,
                dir = dir,
                name = dirname,
                id = extractIdFromFolderName(dirname),
                modifiedAt = entry.mtime,
                fileCount = entry.fileCount,
                ehMeta = ehMeta
            )
            if (isSingleMode) {
                logInfo(
                    "扫描到下载目录: ${folder.name}，文件数=${folder.fileCount}，修改时间=${folder.modifiedAt}，是否有ehviewer元数据=${folder.ehMeta != null}"
                )
            }
            buckets.getOrPut(cleanName) { mutableListOf() }.add(folder)
        }

        if (!isSingleMode) {
            MergeScanCache.put(HashMap(buckets))
            logInfo("全量扫描结果已缓存至 MergeScanCache")
        }

        galleryGroups.clear()
        for ((key, value) in buckets) {
            if (value.size < 2) continue
            galleryGroups.add(
                GalleryGroup(
                    cleanedName = key,
                    folders = value.toMutableList()
                )
            )
        }

        if (isSingleMode && galleryGroups.isEmpty()) {
            logInfo("单画廊模式: 未发现目标画廊的重复，无需合并")
        } else {
            logInfo("扫描完成，发现 ${galleryGroups.size} 组候选重复画廊")
        }
        return true
    }

    private suspend fun scanDownloadedGalleriesJava(
        infos: List<DownloadInfo>,
        total: Int,
        targetCleanName: String?
    ): Boolean {
        logInfo("扫描到 $total 个下载记录${if (isSingleMode) "（单画廊模式）" else ""}")

        val buckets = LinkedHashMap<String, MutableList<GalleryFolder>>()

        for (i in infos.indices) {
            ensureNotCancelled()
            val info = infos[i]
            val dir = SpiderDen.getGalleryDownloadDir(info)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                dispatchProgress(STEP_SCAN, "扫描中: ${i + 1}/$total", i + 1, maxOf(total, 1))
                continue
            }

            val name = dir.name ?: info.gid.toString()
            val cleanName = removeIdPrefix(name)

            if (isSingleMode && cleanName != targetCleanName) {
                dispatchProgress(STEP_SCAN, "扫描中: ${i + 1}/$total", i + 1, maxOf(total, 1))
                continue
            }

            val folder = GalleryFolder(
                info = info,
                dir = dir,
                name = name,
                id = extractIdFromFolderName(name),
                modifiedAt = maxOf(dir.lastModified(), 0L),
                fileCount = countFiles(dir),
                ehMeta = parseEhviewerMeta(dir)
            )

            if (isSingleMode) {
                logInfo(
                    "扫描到下载目录: ${folder.name}，文件数=${folder.fileCount}，修改时间=${folder.modifiedAt}，是否有ehviewer元数据=${folder.ehMeta != null}"
                )
            }

            buckets.getOrPut(cleanName) { mutableListOf() }.add(folder)
            dispatchProgress(STEP_SCAN, "扫描中: ${i + 1}/$total", i + 1, maxOf(total, 1))
        }

        if (!isSingleMode) {
            MergeScanCache.put(HashMap(buckets))
            logInfo("全量扫描结果已缓存至 MergeScanCache")
        }

        galleryGroups.clear()
        for ((key, value) in buckets) {
            if (value.size < 2) continue
            galleryGroups.add(
                GalleryGroup(
                    cleanedName = key,
                    folders = value.toMutableList()
                )
            )
        }

        if (isSingleMode && galleryGroups.isEmpty()) {
            logInfo("单画廊模式: 未发现目标画廊的重复，无需合并")
        } else {
            logInfo("扫描完成，发现 ${galleryGroups.size} 组候选重复画廊")
        }
        return true
    }

    /**
     * 从缓存的全量 buckets 中重建 galleryGroups。
     * 单画廊模式：只提取目标画廊所在组；全量模式：提取所有 >=2 的组。
     */
    private fun rebuildFromCache(cachedBuckets: Map<String, List<GalleryFolder>>): Boolean {
        galleryGroups.clear()

        if (isSingleMode) {
            // 先找到目标 gid 对应的 cleanName
            val targetCleanName = findTargetCleanNameInCache(cachedBuckets)
            if (targetCleanName == null) {
                logInfo("单画廊模式: 缓存中未找到目标画廊 gid=$targetGid，重新扫描")
                MergeScanCache.invalidate()
                return false
            }
            val folders = cachedBuckets[targetCleanName]
            if (folders == null || folders.size < 2) {
                logInfo("单画廊模式: 缓存中目标画廊 \"$targetCleanName\" 无重复，无需合并")
                return true
            }
            galleryGroups.add(
                GalleryGroup(
                    cleanedName = targetCleanName,
                    folders = folders.toMutableList()
                )
            )
            logInfo("从缓存提取分组: ${galleryGroups[0].cleanedName} (${galleryGroups[0].folders.size} 个目录)")
        } else {
            for ((key, value) in cachedBuckets) {
                if (value.size < 2) continue
                galleryGroups.add(
                    GalleryGroup(
                        cleanedName = key,
                        folders = value.toMutableList()
                    )
                )
            }
            logInfo("从缓存重建 ${galleryGroups.size} 组候选重复画廊")
        }
        return true
    }

    /** 在缓存 buckets 中查找包含目标 gid 的分组 key */
    private fun findTargetCleanNameInCache(buckets: Map<String, List<GalleryFolder>>): String? {
        for ((key, folders) in buckets) {
            for (f in folders) {
                if (f.info.gid == targetGid) {
                    return key
                }
            }
        }
        return null
    }

    private suspend fun analyzeDuplicateGalleries(): Boolean {
        return try {
            val total = maxOf(galleryGroups.size, 1)
            logInfo("开始分析 ${galleryGroups.size} 组候选重复画廊")
            if (galleryGroups.isEmpty()) return true

            val snapshots = galleryGroups.toList()
            withContext(Dispatchers.Default) {
                coroutineScope {
                    snapshots.mapIndexed { i, group ->
                        async {
                            ensureNotCancelled()
                            group.type = analyzeGroupRelationship(group.folders)
                            group.target = chooseTargetFolder(group)
                            group.reason = buildTargetReason(group)
                            dispatchProgress(
                                STEP_ANALYZE,
                                "分析中: ${group.cleanedName} (${i + 1}/$total)",
                                i + 1,
                                total
                            )
                            logInfo("分析分组 ${group.cleanedName} 类型=${group.type.name}，${group.reason}")
                        }
                    }.awaitAll()
                }
            }
            true
        } catch (t: Throwable) {
            lastError = t.message ?: "分析失败"
            logError("分析失败: ${t.message}")
            false
        }
    }

    private fun backupDatabase(): Boolean {
        return try {
            dispatchProgress(STEP_BACKUP, "备份数据库中...", 0, 1)
            logInfo("开始备份数据库")
            val backedUp = EhDB.backupDatabase(context)
            dispatchProgress(STEP_BACKUP, if (backedUp) "数据库备份完成" else "数据库备份失败", 1, 1)
            if (!backedUp) {
                lastError = "数据库备份失败"
                logError(lastError)
            } else {
                logInfo("数据库备份成功")
            }
            backedUp
        } catch (t: Throwable) {
            lastError = t.message ?: "备份失败"
            logError("备份失败: ${t.message}")
            false
        }
    }

    private suspend fun mergeDuplicateGalleries(): Boolean {
        return try {
            logInfo("开始合并 ${galleryGroups.size} 组候选重复画廊")
            if (galleryGroups.isEmpty()) {
                return true
            }

            val total = galleryGroups.size
            val snapshots = galleryGroups.toList()
            val anyError = AtomicInteger(0)

            withContext(Dispatchers.Default) {
                coroutineScope {
                    snapshots.mapIndexed { i, group ->
                        async {
                            ensureNotCancelled()
                            val stats = processGroup(group)
                            copiedCount.addAndGet(stats.copied)
                            deletedCount.addAndGet(stats.deleted)
                            errorCount.addAndGet(stats.errors)
                            if (stats.errors > 0) {
                                anyError.incrementAndGet()
                                skippedCount.incrementAndGet()
                            } else {
                                mergedCount.incrementAndGet()
                            }
                            dispatchProgress(
                                STEP_MERGE,
                                "合并中: ${group.cleanedName} [${group.type.name}]",
                                i + 1,
                                total
                            )
                        }
                    }.awaitAll()
                }
            }

            if (anyError.get() > 0) {
                lastError = "部分分组合并失败"
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            lastError = t.message ?: "合并失败"
            logError("合并失败: ${t.message}")
            false
        }
    }

    private fun analyzeGroupRelationship(folders: List<GalleryFolder>): RelationshipType {
        val withEh = mutableListOf<GalleryFolder>()
        for (folder in folders) {
            if (folder.ehMeta != null && folder.ehMeta.hashes.isNotEmpty()) {
                withEh.add(folder)
            }
        }
        if (withEh.isEmpty()) {
            return RelationshipType.NO_EHVIEWER
        }

        var allSameIdentity = true
        var firstIdentity: String? = null
        for (folder in withEh) {
            val identity = folder.ehMeta!!.gid + "#" + folder.ehMeta!!.token
            if (firstIdentity == null) {
                firstIdentity = identity
            } else if (firstIdentity != identity) {
                allSameIdentity = false
            }
        }
        if (allSameIdentity) {
            return RelationshipType.DUPLICATE
        }

        var common: MutableSet<String>? = null
        var allEqual = true
        val firstSet = withEh[0].ehMeta!!.hashes

        for (folder in withEh) {
            val hashes = folder.ehMeta!!.hashes
            if (common == null) {
                common = HashSet(hashes)
            } else {
                common.retainAll(hashes)
            }
            if (hashes != firstSet) {
                allEqual = false
            }
        }

        if (allEqual) {
            return RelationshipType.DUPLICATE
        }

        val sorted = withEh.toMutableList()
        sorted.sortBy { it.ehMeta!!.hashes.size }
        var progressive = true
        for (i in 0 until sorted.size - 1) {
            if (!sorted[i + 1].ehMeta!!.hashes.containsAll(sorted[i].ehMeta!!.hashes)) {
                progressive = false
                break
            }
        }
        if (progressive) {
            return RelationshipType.PROGRESSIVE
        }

        if (common != null && common.isNotEmpty()) {
            return RelationshipType.PARTIAL_OVERLAP
        }

        var bestSimilarity = 0f
        for (i in withEh.indices) {
            for (j in i + 1 until withEh.size) {
                bestSimilarity = maxOf(
                    bestSimilarity,
                    jaccard(withEh[i].ehMeta!!.hashes, withEh[j].ehMeta!!.hashes)
                )
            }
        }

        return if (bestSimilarity >= HASH_SIMILARITY_THRESHOLD) {
            RelationshipType.PARTIAL_OVERLAP
        } else {
            RelationshipType.NO_OVERLAP
        }
    }

    private fun chooseTargetFolder(group: GalleryGroup): GalleryFolder? {
        if (group.folders.isEmpty()) {
            return null
        }

        if (group.type == RelationshipType.PROGRESSIVE) {
            var best: GalleryFolder? = null
            var bestSize = -1
            for (folder in group.folders) {
                val size = folder.ehMeta?.hashes?.size ?: 0
                if (size > bestSize) {
                    best = folder
                    bestSize = size
                }
            }
            if (best != null) {
                return best
            }
        }

        val sorted = group.folders.toMutableList()
        sorted.sortWith { a, b ->
            if (a.modifiedAt != b.modifiedAt) {
                return@sortWith if (a.modifiedAt > b.modifiedAt) -1 else 1
            }
            if (a.fileCount != b.fileCount) {
                return@sortWith b.fileCount.compareTo(a.fileCount)
            }
            val aHasEh = a.ehMeta != null
            val bHasEh = b.ehMeta != null
            if (aHasEh != bHasEh) {
                return@sortWith if (aHasEh) -1 else 1
            }
            val aid = a.id ?: -1
            val bid = b.id ?: -1
            bid.compareTo(aid)
        }
        return sorted[0]
    }

    private fun buildTargetReason(group: GalleryGroup): String {
        val target = group.target ?: return "无可用目标"
        return "保留 ${target.name}（.ehviewer=${if (target.ehMeta != null) "有" else "无"}，修改时间=${target.modifiedAt}，文件数=${target.fileCount}）"
    }

    private suspend fun processGroup(group: GalleryGroup): MergeStats {
        val stats = MergeStats()
        val target = group.target
        if (target == null) {
            stats.errors++
            logError("分组 ${group.cleanedName} 无可用目标")
            return stats
        }

        val sources = mutableListOf<GalleryFolder>()
        for (folder in group.folders) {
            if (folder !== target) {
                sources.add(folder)
            }
        }
        if (sources.isEmpty()) {
            stats.skipped++
            return stats
        }

        logInfo("处理分组 ${group.cleanedName} 类型=${group.type.name}，${group.reason}")

        if (group.type == RelationshipType.DUPLICATE || group.type == RelationshipType.PROGRESSIVE) {
            val lock = getTargetDirLock(target.dir)
            lock.withLock {
                for (source in sources) {
                    if (deleteRecursively(source.dir)) {
                        EhDB.removeDownloadDirname(source.info.gid)
                        EhDB.removeDownloadInfo(source.info.gid)
                        stats.deleted++
                    } else {
                        logError("删除源目录失败: ${source.name}")
                        stats.errors++
                    }
                }
            }
            return stats
        }

        val mergeStats = if (group.type == RelationshipType.NO_EHVIEWER) {
            guardedMergeByMd5(target, sources)
        } else {
            guardedMergeByEhviewer(target, sources)
        }

        stats.copied += mergeStats.copied
        stats.skipped += mergeStats.skipped
        stats.errors += mergeStats.errors

        if (stats.errors == 0) {
            val lock = getTargetDirLock(target.dir)
            lock.withLock {
                for (source in sources) {
                    if (deleteRecursively(source.dir)) {
                        EhDB.removeDownloadDirname(source.info.gid)
                        EhDB.removeDownloadInfo(source.info.gid)
                        stats.deleted++
                    } else {
                        logError("合并后删除源目录失败: ${source.name}")
                        stats.errors++
                    }
                }
            }
        }
        return stats
    }

    private fun getTargetDirLock(dir: UniFile): Mutex {
        val path = dir.uri?.path ?: dir.uri?.toString() ?: dir.toString()
        return targetDirLocks.getOrPut(path) { Mutex() }
    }

    private suspend fun guardedMergeByEhviewer(target: GalleryFolder, sources: List<GalleryFolder>): MergeStats {
        val lock = getTargetDirLock(target.dir)
        lock.withLock {
            return mergeByEhviewer(target, sources)
        }
    }

    private fun mergeByEhviewer(target: GalleryFolder, sources: List<GalleryFolder>): MergeStats {
        val targetMeta = target.ehMeta ?: return mergeByMd5(target, sources)

        val targetHashes = HashSet(targetMeta.hashes)
        var maxIndex = -1
        for (idx in targetMeta.files.keys) {
            if (idx > maxIndex) {
                maxIndex = idx
            }
        }

        val newEntries = mutableListOf<IntArray>()
        val newHashes = mutableListOf<String>()
        val stats = MergeStats()
        var currentIndex = maxIndex + 1

        for (source in sources) {
            val sourceMeta = source.ehMeta
            if (sourceMeta == null) {
                val fallback = mergeByMd5(target, Collections.singletonList(source))
                stats.copied += fallback.copied
                stats.skipped += fallback.skipped
                stats.errors += fallback.errors
                continue
            }

            val hashToFile = buildHashToFilepath(source, sourceMeta)
            for ((hash, sourceFile) in hashToFile) {
                if (targetHashes.contains(hash)) {
                    stats.skipped++
                    continue
                }
                val ext = extensionOf(sourceFile.name)
                val fileName = String.format(Locale.US, "%06d%s", currentIndex + 1, ext)
                val targetFile = createUniqueFile(target.dir, fileName)
                if (targetFile == null || !copyFile(sourceFile, targetFile)) {
                    stats.errors++
                    logError("复制失败: ${sourceFile.name} -> $fileName")
                    continue
                }
                newEntries.add(intArrayOf(currentIndex))
                newHashes.add(hash)
                targetHashes.add(hash)
                currentIndex++
                stats.copied++
            }
        }

        if (newEntries.isNotEmpty() && !appendToEhviewer(target.dir, newEntries, newHashes)) {
            stats.errors++
        }

        return stats
    }

    private suspend fun guardedMergeByMd5(target: GalleryFolder, sources: List<GalleryFolder>): MergeStats {
        val lock = getTargetDirLock(target.dir)
        lock.withLock {
            return mergeByMd5(target, sources)
        }
    }

    private fun mergeByMd5(target: GalleryFolder, sources: List<GalleryFolder>): MergeStats {
        val stats = MergeStats()
        val targetMd5 = collectImageMd5(target.dir)

        for (source in sources) {
            val files = source.dir.listFiles() ?: continue
            for (file in files) {
                if (file == null || !file.isFile) {
                    continue
                }
                val name = file.name ?: continue
                if (SpiderQueen.SPIDER_INFO_FILENAME == name) {
                    continue
                }

                if (isImageFile(name)) {
                    val md5 = md5Of(file)
                    if (md5 == null) {
                        stats.errors++
                        continue
                    }
                    if (targetMd5.containsKey(md5)) {
                        stats.skipped++
                        continue
                    }
                    val targetFile = createUniqueFile(target.dir, name)
                    if (targetFile == null || !copyFile(file, targetFile)) {
                        stats.errors++
                    } else {
                        targetMd5[md5] = targetFile.name
                        stats.copied++
                    }
                } else {
                    val existed = target.dir.findFile(name)
                    if (existed != null) {
                        stats.skipped++
                        continue
                    }
                    val targetFile = target.dir.createFile(name)
                    if (targetFile == null || !copyFile(file, targetFile)) {
                        stats.errors++
                    } else {
                        stats.copied++
                    }
                }
            }
        }

        return stats
    }

    private fun buildHashToFilepath(folder: GalleryFolder, meta: EhviewerMeta): Map<String, UniFile> {
        val result = HashMap<String, UniFile>()
        val imageFiles = getSortedImageFiles(folder.dir)
        for ((idx, hash) in meta.files) {
            if (idx < 0 || idx >= imageFiles.size) {
                continue
            }
            result[hash] = imageFiles[idx]
        }
        return result
    }

    private fun getSortedImageFiles(dir: UniFile): List<UniFile> {
        val files = mutableListOf<UniFile>()
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

    private fun appendToEhviewer(targetDir: UniFile, entries: List<IntArray>, hashes: List<String>): Boolean {
        val ehFile = targetDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return false
        var os: OutputStream? = null
        return try {
            os = ehFile.openOutputStream(true)
            for (i in entries.indices) {
                val line = "\n${entries[i][0]} ${hashes[i]}"
                os?.write(line.toByteArray(StandardCharsets.UTF_8))
            }
            os?.flush()
            true
        } catch (e: IOException) {
            logError("追加 .ehviewer 失败: ${e.message}")
            false
        } finally {
            closeQuietly(os)
        }
    }

    private fun collectImageMd5(dir: UniFile): MutableMap<String, String?> {
        val md5Map = HashMap<String, String?>()
        val files = dir.listFiles() ?: return md5Map
        for (file in files) {
            if (file != null && file.isFile && isImageFile(file.name)) {
                val md5 = md5Of(file)
                if (md5 != null) {
                    md5Map[md5] = file.name
                }
            }
        }
        return md5Map
    }

    private fun copyFile(source: UniFile, target: UniFile): Boolean {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        return try {
            inputStream = BufferedInputStream(source.openInputStream())
            outputStream = target.openOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = inputStream.read(buffer)
                if (count == -1) {
                    break
                }
                outputStream?.write(buffer, 0, count)
            }
            outputStream?.flush()
            true
        } catch (_: IOException) {
            false
        } finally {
            closeQuietly(inputStream)
            closeQuietly(outputStream)
        }
    }

    private fun createUniqueFile(dir: UniFile, preferredName: String): UniFile? {
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

    private fun deleteRecursively(file: UniFile?): Boolean {
        if (file == null || !file.exists()) {
            return true
        }
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursively(child)) {
                        return false
                    }
                }
            }
        }
        return file.delete()
    }

    private fun parseEhviewerMeta(dir: UniFile): EhviewerMeta? {
        val parsed = EhviewerMetaParser.parse(dir) ?: return null
        val meta = EhviewerMeta()
        meta.gid = parsed.gid.toString()
        meta.token = parsed.token
        meta.files.putAll(parsed.indexToHash)
        meta.hashes.addAll(parsed.hashes)
        return meta
    }

    private fun countFiles(dir: UniFile?): Int {
        if (dir == null || !dir.exists()) {
            return 0
        }
        if (dir.isFile) {
            return 1
        }
        var total = 0
        val children = dir.listFiles()
        if (children != null) {
            for (child in children) {
                total += countFiles(child)
            }
        }
        return total
    }

    private fun removeIdPrefix(name: String?): String {
        if (name == null) {
            return ""
        }
        var cleaned = name.replace(Regex("^\\d+-"), "")
        cleaned = cleaned.replace("🔄", "").trim()
        cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFKC)
        return cleaned.lowercase(Locale.ROOT)
    }

    private fun extractIdFromFolderName(name: String?): Int? {
        if (name == null) {
            return null
        }
        val idx = name.indexOf('-')
        if (idx <= 0) {
            return null
        }
        return try {
            name.substring(0, idx).toInt()
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun jaccard(a: Set<String>?, b: Set<String>?): Float {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) {
            return 0f
        }
        val inter = HashSet(a)
        inter.retainAll(b)
        val union = HashSet(a)
        union.addAll(b)
        if (union.isEmpty()) {
            return 0f
        }
        return inter.size * 1.0f / union.size
    }

    private fun md5Of(file: UniFile): String? {
        var inputStream: InputStream? = null
        return try {
            val digest = MessageDigest.getInstance("MD5")
            inputStream = file.openInputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) {
                    break
                }
                digest.update(buffer, 0, read)
            }
            val hash = digest.digest()
            val sb = StringBuilder(hash.size * 2)
            for (b in hash) {
                sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                sb.append(Character.forDigit(b.toInt() and 0xF, 16))
            }
            sb.toString()
        } catch (_: Throwable) {
            null
        } finally {
            closeQuietly(inputStream)
        }
    }

    private fun isImageFile(name: String?): Boolean {
        if (name == null) {
            return false
        }
        val ext = extensionOf(name).lowercase(Locale.ROOT)
        return IMAGE_EXTENSIONS.contains(ext)
    }

    private fun extensionOf(name: String?): String {
        if (name == null) {
            return ""
        }
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot) else ""
    }

    private fun baseName(name: String?): String {
        if (name == null) {
            return "file"
        }
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(0, dot) else name
    }

    private fun safeTrim(value: String?): String {
        return value?.trim() ?: ""
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        if (closeable == null) {
            return
        }
        try {
            closeable.close()
        } catch (_: Exception) {
        }
    }

    private data class GalleryGroup(
        val cleanedName: String,
        val folders: MutableList<GalleryFolder>,
        var type: RelationshipType = RelationshipType.NO_EHVIEWER,
        var target: GalleryFolder? = null,
        var reason: String = ""
    )

    data class GalleryFolder(
        val info: DownloadInfo,
        val dir: UniFile,
        val name: String,
        val id: Int?,
        val modifiedAt: Long,
        val fileCount: Int,
        val ehMeta: EhviewerMeta?
    )

    class EhviewerMeta {
        var gid: String = ""
        var token: String = ""
        val files: MutableMap<Int, String> = HashMap()
        val hashes: MutableSet<String> = HashSet()
    }

    private class MergeStats {
        var copied = 0
        var skipped = 0
        var errors = 0
        var deleted = 0
    }

    private enum class RelationshipType {
        DUPLICATE,
        PROGRESSIVE,
        PARTIAL_OVERLAP,
        NO_OVERLAP,
        NO_EHVIEWER
    }
}
