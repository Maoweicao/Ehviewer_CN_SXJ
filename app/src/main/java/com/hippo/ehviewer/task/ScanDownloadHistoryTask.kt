package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.cache.GalleryCacheManager
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.NumberUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * 扫描下载目录补建 DOWNLOAD_HISTORY 任务
 *
 * 分三个阶段执行：
 * 1. 阶段一：扫描所有画廊文件夹，解析 GID，批量检查已存在记录
 *    - 从目录名解析 GID
 *    - 批量查询 DOWNLOAD_HISTORY 已存在的 GID
 *    - 直接写入能确定的信息（GID, dirname）
 * 2. 阶段二：读取 .ehviewer 文件补全 TOKEN
 *    - 读取每个画廊目录下的 .ehviewer 文件
 *    - 解析 TOKEN 并更新 DOWNLOAD_HISTORY 记录
 * 3. 阶段三：API 补登
 *    - 对仍无 TOKEN 的记录，通过 API 获取信息
 *    - 更新 DOWNLOAD_HISTORY 记录
 */
class ScanDownloadHistoryTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "ScanDownloadHistoryTask"
        private const val BATCH_SIZE = 100
        private const val API_REQUEST_DELAY_MS = 1500L
    }

    /**
     * 扫描结果数据类
     */
    private data class ScanResult(
        val gid: Long,
        val token: String?,
        val title: String?,
        val dirname: String,
        val needReadEhviewer: Boolean = false,
        val expectedPages: Int = 0, // 预期的页数（来自缓存或历史）
        val hasIncremental: Boolean = false // 是否有增量文件
    )

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false

    private var totalGalleries = 0
    private var phase1Processed = AtomicInteger(0)
    private var phase2Processed = AtomicInteger(0)
    private var phase3Processed = AtomicInteger(0)

    private var phase1Added = 0
    private var phase2Updated = 0
    private var phase2IncrementalDetected = 0
    private var phase3Updated = 0
    private var phase3Failed = 0

    // 待处理队列
    private val needEhviewerFiles = ConcurrentLinkedQueue<ScanResult>()
    private val needApiGids = ConcurrentLinkedQueue<ScanResult>()

    // 下载目录路径
    private var downloadDirPath: String? = null

    override fun getTaskId(): String = "scan_download_history"

    override fun getTaskName(): String = context.getString(R.string.settings_download_scan_download_history)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.SCAN

    override fun getTaskDescription(): String? = context.getString(R.string.settings_download_scan_download_history_summary)

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            Log.d(TAG, "开始扫描下载目录补建下载历史")
            appendTaskLog("开始扫描下载目录补建下载历史")

            coroutineContext[Job]?.ensureActive()

            // 获取下载目录
            val downloadDir = Settings.getDownloadLocation()
            if (downloadDir == null || !downloadDir.isDirectory()) {
                Log.w(TAG, "下载目录不存在或无效")
                appendTaskLog("下载目录不存在或无效")
                notifyError(Exception("下载目录不存在或无效"))
                return Result.failure(Exception("下载目录不存在或无效"))
            }

            // 获取下载目录的路径
            downloadDirPath = downloadDir.uri?.path
            if (downloadDirPath == null) {
                Log.w(TAG, "无法获取下载目录路径")
                appendTaskLog("无法获取下载目录路径")
                notifyError(Exception("无法获取下载目录路径"))
                return Result.failure(Exception("无法获取下载目录路径"))
            }

            val dirFile = File(downloadDirPath!!)
            if (!dirFile.exists() || !dirFile.isDirectory) {
                Log.w(TAG, "无法访问下载目录")
                appendTaskLog("无法访问下载目录")
                notifyError(Exception("无法访问下载目录"))
                return Result.failure(Exception("无法访问下载目录"))
            }

            // 收集所有画廊目录
            val galleryDirs = dirFile.listFiles { file ->
                file.isDirectory &&
                    !file.name.startsWith(".") &&
                    file.name != "thumb" &&
                    parseGidFromDirName(file.name) != null
            }?.toList() ?: emptyList()

            totalGalleries = galleryDirs.size
            if (totalGalleries == 0) {
                appendTaskLog("未发现任何画廊目录")
                Settings.putDownloadHistoryLastScanTime(System.currentTimeMillis())
                notifyCompleted()
                return Result.success(Unit)
            }

            appendTaskLog("共发现 $totalGalleries 个画廊目录")

            // ============ 阶段一：扫描目录，解析 GID ============
            Log.d(TAG, "阶段一：扫描目录，解析 GID")
            appendTaskLog("阶段一：扫描目录，解析 GID...")
            phase1()

            // ============ 阶段二：读取 .ehviewer 文件 ============
            if (needEhviewerFiles.isNotEmpty()) {
                Log.d(TAG, "阶段二：读取 .ehviewer 文件补全 TOKEN")
                appendTaskLog("阶段二：读取 .ehviewer 文件补全 TOKEN...")
                phase2()
            } else {
                appendTaskLog("阶段二：无需要读取 .ehviewer 文件的目录")
            }

            // ============ 阶段三：API 补登 ============
            if (needApiGids.isNotEmpty()) {
                Log.d(TAG, "阶段三：API 补登")
                appendTaskLog("阶段三：API 补登...")
                phase3()
            } else {
                appendTaskLog("阶段三：无需要 API 补登的记录")
            }

            // 更新扫描时间戳
            Settings.putDownloadHistoryLastScanTime(System.currentTimeMillis())

            // 完成
            val summary = "补建完成：阶段一新增 $phase1Added 条，" +
                    "阶段二更新 $phase2Updated 条（增量检测 $phase2IncrementalDetected 个），" +
                    "阶段三更新 $phase3Updated 条，失败 $phase3Failed 条"
            Log.d(TAG, summary)
            appendTaskLog(summary)
            notifyCompleted()
            return Result.success(Unit)

        } catch (e: CancellationException) {
            Log.d(TAG, "扫描任务被取消")
            appendTaskLog("扫描任务被取消")
            Settings.putDownloadHistoryLastScanTime(System.currentTimeMillis())
            notifyCancelled()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "扫描任务出错", e)
            appendTaskLog("扫描任务出错: ${e.message}")
            Settings.putDownloadHistoryLastScanTime(System.currentTimeMillis())
            notifyError(e)
            Result.failure(e)
        }
    }

    /**
     * 阶段一：扫描目录，解析 GID
     */
    private suspend fun phase1() {
        val path = downloadDirPath ?: return
        val dirFile = File(path)
        val allDirs = dirFile.listFiles { file ->
            file.isDirectory &&
                !file.name.startsWith(".") &&
                file.name != "thumb" &&
                parseGidFromDirName(file.name) != null
        }?.toList() ?: return

        totalGalleries = allDirs.size
        phase1Processed.set(0)

        // 使用 CPU 核心数创建线程池
        val threadCount = Runtime.getRuntime().availableProcessors()
        val executor = Executors.newFixedThreadPool(threadCount)

        // 批量处理
        val batchSize = BATCH_SIZE
        val batches = allDirs.chunked(batchSize)

        for (batch in batches) {
            coroutineContext[Job]?.ensureActive()

            while (isPaused && !isCancelled) {
                kotlinx.coroutines.delay(100)
            }

            if (isCancelled) break

            // 并行处理当前批次
            val futures = batch.map { dir ->
                executor.submit {
                    processDirectoryPhase1(dir)
                }
            }

            // 等待批次完成
            futures.forEach { it.get() }

            // 更新进度
            phase1Processed.addAndGet(batch.size)
            val detail = "阶段一: ${phase1Processed.get()}/$totalGalleries (新增 $phase1Added)"
            updateProgress(phase1Processed.get(), totalGalleries, detail)
        }

        executor.shutdown()
    }

    /**
     * 处理单个目录 - 阶段一
     */
    private fun processDirectoryPhase1(dir: File): ScanResult? {
        val gid = parseGidFromDirName(dir.name) ?: return null

        // 从目录名提取标题
        val title = extractTitleFromDirName(dir.name)

        // 检查 .ehviewer 文件是否存在
        val ehviewerFile = File(dir, DownloadManager.DOWNLOAD_INFO_FILENAME)
        val needReadEhviewer = ehviewerFile.exists()

        // 创建扫描结果
        val result = ScanResult(
            gid = gid,
            token = null,
            title = title,
            dirname = dir.name,
            needReadEhviewer = needReadEhviewer
        )

        // 如果不需要读取 .ehviewer，直接写入（阶段一只能写入 GID 和 dirname）
        if (!needReadEhviewer) {
            val inserted = EhDB.recordDownloadHistoryWithToken(gid, "", title, dir.name)
            if (inserted) {
                phase1Added++
                // 记录需要 API 补登
                needApiGids.offer(result)
            }
        } else {
            // 需要读取 .ehviewer 文件
            needEhviewerFiles.offer(result)
        }

        return result
    }

    /**
     * 阶段二：读取 .ehviewer 文件补全 TOKEN，并进行增量检测
     */
    private suspend fun phase2() {
        val total = needEhviewerFiles.size
        phase2Processed.set(0)
        val path = downloadDirPath ?: return

        while (needEhviewerFiles.isNotEmpty()) {
            coroutineContext[Job]?.ensureActive()

            while (isPaused && !isCancelled) {
                kotlinx.coroutines.delay(100)
            }

            if (isCancelled) break

            val result = needEhviewerFiles.poll() ?: continue

            // 读取 .ehviewer 文件
            val dir = File(path, result.dirname)
            val ehviewerFile = File(dir, DownloadManager.DOWNLOAD_INFO_FILENAME)

            if (!ehviewerFile.exists()) {
                needApiGids.offer(result)
                continue
            }

            try {
                val content = IOUtils.readString(ehviewerFile.inputStream(), "UTF-8")
                val lines = content.split("\n")

                // 增量检测：读取 .ehviewer.extra.json 获取预期页数
                val expectedPages = detectIncrementalPages(dir, result.gid)

                val parsed = parseEhviewerFile(lines)
                if (parsed != null && !parsed.token.isNullOrEmpty()) {
                    // 成功解析到 TOKEN，插入记录
                    val inserted = EhDB.recordDownloadHistoryWithToken(
                        result.gid,
                        parsed.token!!,
                        parsed.title ?: result.title,
                        result.dirname
                    )
                    if (inserted) {
                        phase1Added++
                        phase2Updated++
                    }

                    // 如果检测到增量，更新历史记录
                    if (expectedPages > 0) {
                        val actualFiles = countImageFiles(dir)
                        if (actualFiles > expectedPages) {
                            // 有增量文件，更新 DownloadHistory 中的文件信息
                            updateHistoryForIncremental(result.gid, expectedPages, actualFiles)
                            phase2IncrementalDetected++
                            Log.d(TAG, "检测到增量下载: GID=${result.gid}, 原有=$expectedPages, 现有=$actualFiles")
                        }
                    }
                } else {
                    // 无法解析 TOKEN，标记需要 API 补登
                    needApiGids.offer(result)
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取 .ehviewer 文件失败: ${result.dirname}", e)
                needApiGids.offer(result)
            }

            phase2Processed.incrementAndGet()
            val detail = "阶段二: ${phase2Processed.get()}/$total (更新 $phase2Updated, 增量 $phase2IncrementalDetected)"
            updateProgress(phase2Processed.get(), total, detail)
        }
    }

    /**
     * 检测增量下载：读取 .ehviewer.extra.json 获取预期页数
     * @param dir 画廊目录
     * @param gid 画廊GID
     * @return 预期页数，0 表示无法确定
     */
    private fun detectIncrementalPages(dir: File, gid: Long): Int {
        try {
            // 优先从 DownloadHistory 获取记录的文件数
            val history = EhDB.getDownloadHistory(gid)
            if (history != null && history.totalFiles > 0) {
                return history.totalFiles
            }

            // 其次读取 .ehviewer.extra.json
            val cacheFile = File(dir, GalleryCacheManager.GALLERY_CACHE_FILENAME)
            if (cacheFile.exists()) {
                val content = IOUtils.readString(cacheFile.inputStream(), "UTF-8")
                val json = com.alibaba.fastjson.JSON.parseObject(content)
                if (json != null && json.containsKey("pages")) {
                    return json.getIntValue("pages")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "检测增量页数失败: GID=$gid", e)
        }
        return 0
    }

    /**
     * 统计目录中的图片文件数量
     */
    private fun countImageFiles(dir: File): Int {
        return try {
            dir.listFiles()?.count { file ->
                file.isFile &&
                    !file.name.startsWith(".") &&
                    isImageFile(file.name)
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 判断是否为图片文件
     */
    private fun isImageFile(filename: String): Boolean {
        val lower = filename.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".webp") || lower.endsWith(".bmp")
    }

    /**
     * 更新历史记录以反映增量下载
     */
    private fun updateHistoryForIncremental(gid: Long, expectedPages: Int, actualFiles: Int) {
        try {
            val history = EhDB.getDownloadHistory(gid)
            if (history != null) {
                // 计算新增的文件数
                val newFiles = actualFiles - expectedPages
                // 更新历史记录，标记有增量
                val currentDownloaded = history.downloadedFiles
                if (currentDownloaded >= 0) {
                    // 更新已下载文件数和总文件数
                    EhDB.updateDownloadHistoryFileTokens(gid, actualFiles, currentDownloaded + newFiles, history.fileTokens)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "更新增量历史失败: GID=$gid", e)
        }
    }

    /**
     * 阶段三：API 补登
     */
    private suspend fun phase3() {
        // 将待处理的 GID 取出到列表
        val pendingGids = mutableListOf<ScanResult>()
        while (needApiGids.isNotEmpty()) {
            val item = needApiGids.poll()
            if (item != null) {
                pendingGids.add(item)
            }
        }

        val total = pendingGids.size
        phase3Processed.set(0)

        if (total == 0) return

        appendTaskLog("阶段三：共 $total 个画廊需要 API 补登")

        val httpClient = EhApplication.getOkHttpClient(context)
        val batchSize = 25 // API 每次最多请求 25 个

        // 批量处理
        for (i in pendingGids.indices step batchSize) {
            coroutineContext[Job]?.ensureActive()

            while (isPaused && !isCancelled) {
                kotlinx.coroutines.delay(100)
            }

            if (isCancelled) break

            val batch = pendingGids.subList(i, minOf(i + batchSize, pendingGids.size))

            // 构建 GalleryInfo 列表
            val galleryInfoList = batch.map { result ->
                GalleryInfo().apply {
                    gid = result.gid
                    token = "" // 暂时为空，让 API 返回
                    title = result.title
                }
            }

            try {
                // 调用 API 获取信息
                EhEngine.fillGalleryListByApi(
                    null,
                    httpClient,
                    ArrayList(galleryInfoList),
                    EhUrl.getReferer()
                )

                // 处理返回结果
                for (gi in galleryInfoList) {
                    if (!gi.token.isNullOrEmpty()) {
                        // API 返回了有效的 token
                        val inserted = EhDB.recordDownloadHistoryWithToken(
                            gi.gid,
                            gi.token!!,
                            gi.title,
                            null // dirname 从已有记录获取
                        )
                        if (inserted) {
                            phase3Updated++
                        }
                    } else {
                        phase3Failed++
                    }
                    phase3Processed.incrementAndGet()
                }
            } catch (e: Exception) {
                Log.e(TAG, "API 补登失败", e)
                appendTaskLog("API 补登失败: ${e.message}")
                // 这批全部标记为失败
                phase3Failed += batch.size
                phase3Processed.addAndGet(batch.size)
            }

            // API 限流
            kotlinx.coroutines.delay(API_REQUEST_DELAY_MS)

            val detail = "阶段三: ${phase3Processed.get()}/$total (更新 $phase3Updated, 失败 $phase3Failed)"
            updateProgress(phase3Processed.get(), total, detail)
        }
    }

    /**
     * 从目录名解析 GID
     */
    private fun parseGidFromDirName(dirName: String): Long? {
        var idx = 0
        while (idx < dirName.length && dirName[idx].isDigit()) {
            idx++
        }
        if (idx == 0) return null
        return try {
            dirName.substring(0, idx).toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * 从目录名提取标题部分
     */
    private fun extractTitleFromDirName(dirName: String): String? {
        val idx = dirName.indexOf(" - ")
        return if (idx > 0) {
            dirName.substring(idx + 3)
        } else {
            null
        }
    }

    /**
     * 解析 .ehviewer 文件内容
     */
    private fun parseEhviewerFile(lines: List<String>): ParsedEhviewer? {
        if (lines.isEmpty()) return null

        return try {
            val gid: Long
            val token: String?
            val title: String?
            val titleJpn: String?

            if (lines[0].trim() == "VERSION2") {
                // VERSION2 格式: VERSION, ?, gid, token
                if (lines.size < 4) return null
                gid = NumberUtils.parseLongSafely(lines[2].trim(), -1)
                token = lines[3].trim()
                title = null
                titleJpn = null
            } else {
                // 原有格式: gid, token, title, titleJpn, thumb, ...
                if (lines.size < 2) return null
                gid = NumberUtils.parseLongSafely(lines[0].trim(), -1)
                token = lines[1].trim()
                title = if (lines.size > 2) lines[2].trim().ifEmpty { null } else null
                titleJpn = if (lines.size > 3) lines[3].trim().ifEmpty { null } else null
            }

            if (gid == -1L) return null

            ParsedEhviewer(gid, token, title, titleJpn)
        } catch (e: Exception) {
            Log.w(TAG, "解析 .ehviewer 文件失败", e)
            null
        }
    }

    /**
     * .ehviewer 文件解析结果
     */
    private data class ParsedEhviewer(
        val gid: Long,
        val token: String?,
        val title: String?,
        val titleJpn: String?
    )

    override fun isPausable(): Boolean = true

    override suspend fun pause() {
        Log.d(TAG, "暂停扫描任务")
        isPaused = true
        updateState(TaskState.PAUSED)
        appendTaskLog("任务已暂停")
    }

    override suspend fun resume() {
        Log.d(TAG, "恢复扫描任务")
        isPaused = false
        updateState(TaskState.RUNNING)
        appendTaskLog("任务已恢复")
    }

    override suspend fun cancel() {
        Log.d(TAG, "取消扫描任务")
        isCancelled = true
        isPaused = false
        notifyCancelled()
    }

    override fun getProgressDetail(): String? {
        val phase1Total = totalGalleries
        val phase1Current = phase1Processed.get()
        val phase2Total = phase1Total - phase1Processed.get() // 估算
        val phase2Current = phase2Processed.get()
        val phase3Total = needApiGids.size + phase3Processed.get()
        val phase3Current = phase3Processed.get()

        return when {
            phase1Current < phase1Total -> "阶段一: $phase1Current/$phase1Total"
            phase2Current < phase2Total -> "阶段二: $phase2Current/$phase2Total"
            phase3Current < phase3Total -> "阶段三: $phase3Current/$phase3Total"
            else -> "完成: 阶段一+$phase1Added, 阶段二+$phase2Updated, 阶段三+$phase3Updated/$phase3Failed"
        }
    }
}
