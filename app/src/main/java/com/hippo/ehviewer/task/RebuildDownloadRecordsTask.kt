package com.hippo.ehviewer.task

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.cache.GalleryCacheManager
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.NumberUtils
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/**
 * 重建下载记录任务
 * 扫描下载目录，重建丢失的下载记录，并同步画廊详情（标签、缩略图）到缓存
 */
class RebuildDownloadRecordsTask(
    context: Context
) : BaseBackgroundTask(context) {
    
    companion object {
        private const val TAG = "RebuildDownloadRecordsTask"
        /** API 请求间隔（毫秒），避免被 EH 限流 */
        private const val API_REQUEST_DELAY_MS = 1500L
    }
    
    /**
     * 待同步画廊信息
     */
    private data class GallerySyncInfo(
        val gid: Long,
        val token: String,
        val thumbUrl: String?,
        val dir: UniFile
    )
    
    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false
    private var statusListener: StatusListener? = null
    
    private var totalProgress = 0
    private var rebuildCount = 0
    private var syncSuccess = 0
    private var syncFailed = 0
    private var syncSkipped = 0
    private val galleriesToSync = mutableListOf<GallerySyncInfo>()

    private fun notifyStatus(detail: String? = null) {
        statusListener?.onStatus(currentProgress, totalProgress, rebuildCount, detail)
    }
    
    override fun getTaskId(): String = "rebuild_download_records"
    
    override fun getTaskName(): String = context.getString(R.string.settings_download_rebuild_download_records)
    
    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.SCAN
    
    override fun getTaskDescription(): String? = context.getString(R.string.settings_download_rebuild_download_records_summary)
    
    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            Log.d(TAG, "开始重建下载记录")
            appendTaskLog("开始重建下载记录")
            
            // 检查是否已取消
            coroutineContext[Job]?.ensureActive()
            
            val downloadDir = Settings.getDownloadLocation()
            if (downloadDir == null || !downloadDir.isDirectory()) {
                Log.w(TAG, "下载目录不存在")
                appendTaskLog("下载目录不存在")
                notifyError(Exception("下载目录不存在"))
                return Result.failure(Exception("下载目录不存在"))
            }
            
            val files = downloadDir.listFiles()
            if (files == null) {
                Log.w(TAG, "无法列出下载目录文件")
                appendTaskLog("无法列出下载目录文件")
                notifyError(Exception("无法列出下载目录文件"))
                return Result.failure(Exception("无法列出下载目录文件"))
            }
            
            totalProgress = files.size
            rebuildCount = 0
            syncSuccess = 0
            syncFailed = 0
            syncSkipped = 0
            galleriesToSync.clear()
            notifyStatus()
            
            val downloadManager = EhApplication.getDownloadManager(context)
            val galleriesToAdd = mutableListOf<GalleryInfo>()
            
            Log.d(TAG, "共需处理 $totalProgress 个文件")
            appendTaskLog("共需处理 $totalProgress 个文件")
            
            // Phase 1: 扫描目录，收集需要重建和同步的画廊
            for (dir in files) {
                // 检查取消状态
                if (isCancelled) {
                    throw CancellationException("任务已取消")
                }
                
                // 检查暂停状态
                while (isPaused && !isCancelled) {
                    Thread.sleep(100)
                }
                
                // 处理目录
                if (dir.isDirectory()) {
                    processDirectory(dir, downloadManager, galleriesToAdd)
                }
                
                currentProgress++
                updateProgress(currentProgress, totalProgress, "$currentProgress/$totalProgress")
                notifyStatus("已发现待重建: ${galleriesToAdd.size}, 待同步: ${galleriesToSync.size}")
            }
            
            // 批量添加画廊信息
            if (galleriesToAdd.isNotEmpty()) {
                Log.d(TAG, "批量添加 ${galleriesToAdd.size} 个画廊")
                appendTaskLog("批量添加 ${galleriesToAdd.size} 个画廊")
                for (galleryInfo in galleriesToAdd) {
                    if (isCancelled) {
                        throw CancellationException("任务已取消")
                    }
                    
                    try {
                        downloadManager.addDownload(galleryInfo, null)
                        rebuildCount++
                        appendTaskLog("已重建: ${galleryInfo.gid}")
                    } catch (e: Exception) {
                        Log.e(TAG, "添加下载失败: ${galleryInfo.gid}", e)
                        appendTaskLog("添加失败: ${galleryInfo.gid} - ${e.message}")
                    }
                }
            }
            
            // Phase 2: 同步画廊详情（标签、缩略图）到缓存
            syncGalleryDetails()
            
            // 完成
            val summary = "重建完成: 重建=$rebuildCount, 同步成功=$syncSuccess, 同步失败=$syncFailed, 跳过=$syncSkipped"
            Log.d(TAG, summary)
            appendTaskLog(summary)
            notifyCompleted()
            Result.success(Unit)
            
        } catch (e: CancellationException) {
            Log.d(TAG, "重建任务被取消")
            appendTaskLog("重建任务被取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "重建任务出错", e)
            appendTaskLog("重建任务出错: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }
    
    /**
     * 处理目录
     */
    private fun processDirectory(
        dir: UniFile,
        downloadManager: DownloadManager,
        galleriesToAdd: MutableList<GalleryInfo>
    ) {
        val ehViewerFile = dir.findFile(DownloadManager.DOWNLOAD_INFO_FILENAME) ?: return
        
        try {
            // 读取.ehviewer文件
            val content = IOUtils.readString(ehViewerFile.openInputStream(), StandardCharsets.UTF_8.name())
            val lines = content.split("\n")
            
            if (lines.isEmpty()) {
                return
            }
            
            // 解析基本信息
            val gid: Long
            val token: String
            val thumbUrl: String?
            
            if (lines[0].trim() == "VERSION2") {
                // VERSION2 格式: VERSION, ?, gid, token
                if (lines.size < 4) return
                gid = NumberUtils.parseLongSafely(lines[2].trim(), -1)
                token = lines[3].trim()
                thumbUrl = null  // VERSION2 没有 thumb URL
            } else {
                // 原有格式: gid, token, title, titleJpn, thumb, ...
                if (lines.size < 3) return
                gid = NumberUtils.parseLongSafely(lines[0], -1)
                token = lines[1]
                thumbUrl = if (lines.size > 4) lines[4].trim().ifEmpty { null } else null
            }
            
            if (gid == -1L || token.isEmpty()) {
                return
            }
            
            // 检查是否需要同步详情（标签、缩略图）
            val cacheManager = GalleryCacheManager.getInstance(context)
            val hasTags = cacheManager.hasValidTags(gid)
            val hasThumb = cacheManager.hasValidThumbBase64(gid)
            
            if (!hasTags || !hasThumb) {
                val reason = buildString {
                    if (!hasTags) append("缺少标签")
                    if (!hasTags && !hasThumb) append(", ")
                    if (!hasThumb) append("缺少缩略图")
                }
                galleriesToSync.add(GallerySyncInfo(gid, token, thumbUrl, dir))
                appendTaskLog("发现需要同步详情: $gid ($reason)")
            } else {
                syncSkipped++
            }
            
            // 检查是否已存在下载记录
            if (downloadManager.getDownloadInfo(gid) != null) {
                return
            }
            
            // 解析画廊信息
            val galleryInfo = parseGalleryInfoFromLines(lines) ?: return
            
            // 添加到待处理列表
            galleriesToAdd.add(galleryInfo)
            appendTaskLog("发现需要重建: ${galleryInfo.gid} - ${galleryInfo.title}")
            
        } catch (e: IOException) {
            Log.e(TAG, "读取.ehviewer文件失败: ${dir.name}", e)
            appendTaskLog("读取.ehviewer文件失败: ${dir.name} - ${e.message}")
        }
    }
    
    /**
     * 同步画廊详情（标签、缩略图）到缓存
     */
    private suspend fun syncGalleryDetails() {
        if (galleriesToSync.isEmpty()) {
            appendTaskLog("无需同步画廊详情")
            return
        }
        
        val totalCount = galleriesToSync.size
        Log.d(TAG, "开始同步画廊详情，共 $totalCount 个待同步")
        appendTaskLog("开始同步画廊详情，共 $totalCount 个待同步")
        
        val cacheManager = GalleryCacheManager.getInstance(context)
        val httpClient = EhApplication.getOkHttpClient(context)
        
        // 重置进度条为同步阶段
        totalProgress = totalCount
        currentProgress = 0
        
        for ((index, info) in galleriesToSync.withIndex()) {
            // 检查取消状态
            if (isCancelled) {
                throw CancellationException("任务已取消")
            }
            
            // 检查暂停状态
            while (isPaused && !isCancelled) {
                Thread.sleep(100)
            }
            
            try {
                val url = EhUrl.getGalleryDetailUrl(info.gid, info.token)
                Log.d(TAG, "同步画廊详情: ${info.gid}, url=$url")
                
                val detail = EhEngine.getGalleryDetail(null, httpClient, url)
                
                // 确保缩略图已缓存并获取 Base64
                ensureThumbnail(info.gid, info.thumbUrl ?: detail.thumb)
                
                // 保存缓存到目录
                val saved = cacheManager.saveGalleryCacheToDir(detail, info.dir)
                if (saved) {
                    syncSuccess++
                    appendTaskLog("同步成功: ${info.gid} - ${detail.title}")
                } else {
                    syncFailed++
                    appendTaskLog("同步失败: ${info.gid} - 保存缓存失败")
                }
            } catch (e: Exception) {
                syncFailed++
                Log.e(TAG, "同步画廊详情失败: ${info.gid}", e)
                appendTaskLog("同步失败: ${info.gid} - ${e.message}")
            }
            
            // API 限流
            delay(API_REQUEST_DELAY_MS)
            
            // 更新进度
            currentProgress = index + 1
            updateProgress(currentProgress, totalCount, "同步: $currentProgress/$totalCount")
            notifyStatus("同步详情: $currentProgress/$totalCount (成功=$syncSuccess, 失败=$syncFailed)")
        }
        
        appendTaskLog("同步完成: 成功=$syncSuccess, 失败=$syncFailed, 跳过=$syncSkipped")
    }
    
    /**
     * 确保缩略图已缓存
     * 如果磁盘缓存中没有，从 URL 下载并存入缓存
     */
    private fun ensureThumbnail(gid: Long, thumbUrl: String?) {
        if (thumbUrl.isNullOrEmpty()) {
            Log.d(TAG, "缩略图URL为空，跳过: $gid")
            return
        }
        
        val beerBelly = EhApplication.getConaco(context).getBeerBelly()
        val key = EhCacheKeyFactory.getThumbKey(gid)
        
        // 检查是否已在缓存中
        val baos = ByteArrayOutputStream()
        if (beerBelly.pullFromDiskCache(key, baos)) {
            Log.d(TAG, "缩略图已在缓存中: $gid")
            return
        }
        
        // 从 URL 下载
        try {
            val client = EhApplication.getOkHttpClient(context)
            val request = Request.Builder()
                .url(thumbUrl)
                .addHeader("Referer", EhUrl.getReferer())
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body()?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        beerBelly.putToDisk(key, bitmap)
                        Log.d(TAG, "缩略图下载并缓存成功: $gid")
                    } else {
                        Log.w(TAG, "缩略图解码失败: $gid")
                    }
                } else {
                    Log.w(TAG, "缩略图下载内容为空: $gid")
                }
            } else {
                Log.w(TAG, "缩略图下载失败: $gid, code=${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "缩略图下载异常: $gid", e)
        }
    }
    
    /**
     * 从.ehviewer文件行解析画廊信息
     */
    private fun parseGalleryInfoFromLines(lines: List<String>): GalleryInfo? {
        if (lines.size < 3) {
            return null
        }
        
        val gid: Long
        val token: String
        val title: String
        
        if (lines[0].trim() == "VERSION2") {
            // VERSION2 格式
            if (lines.size < 4) return null
            gid = NumberUtils.parseLongSafely(lines[2].trim(), -1)
            token = lines[3].trim()
            // 从目录名提取标题
            title = ""
        } else {
            // 原有格式
            gid = NumberUtils.parseLongSafely(lines[0], -1)
            token = lines[1]
            title = lines[2]
        }
        
        if (gid == -1L || token.isEmpty()) {
            return null
        }
        
        val galleryInfo = GalleryInfo()
        galleryInfo.gid = gid
        galleryInfo.token = token
        galleryInfo.title = title
        
        // 尝试读取更多信息
        if (lines.size > 4 && lines[0].trim() != "VERSION2") {
            galleryInfo.thumb = lines[4].trim()
        }
        if (lines.size > 5) {
            galleryInfo.category = try { lines[5].trim().toInt() } catch (e: Exception) { 0 }
        }
        if (lines.size > 6) {
            galleryInfo.posted = lines[6].trim()
        }
        if (lines.size > 7) {
            galleryInfo.uploader = lines[7].trim()
        }
        if (lines.size > 8) {
            galleryInfo.rating = NumberUtils.parseFloatSafely(lines[8].trim(), 0.0f)
        }
        
        return galleryInfo
    }
    
    override fun isPausable(): Boolean = true
    
    override suspend fun pause() {
        Log.d(TAG, "暂停重建任务")
        isPaused = true
        updateState(TaskState.PAUSED)
        appendTaskLog("任务已暂停")
    }
    
    override suspend fun resume() {
        Log.d(TAG, "恢复重建任务")
        isPaused = false
        updateState(TaskState.RUNNING)
        appendTaskLog("任务已恢复")
    }
    
    override suspend fun cancel() {
        Log.d(TAG, "取消重建任务")
        isCancelled = true
        isPaused = false
        notifyCancelled()
    }

    override fun getProgressDetail(): String? {
        return if (totalProgress > 0) {
            "$currentProgress/$totalProgress (重建: $rebuildCount, 同步: $syncSuccess/$syncFailed)"
        } else {
            null
        }
    }
    
    /**
     * 同步执行任务（用于Java调用）
     */
    fun executeSync() {
        runBlocking {
            execute()
        }
    }
    
    /**
     * 获取重建的记录数量
     */
    fun getRebuildCount(): Int = rebuildCount
    
    /**
     * 获取同步成功数量
     */
    fun getSyncSuccess(): Int = syncSuccess
    
    /**
     * 获取同步失败数量
     */
    fun getSyncFailed(): Int = syncFailed

    fun setStatusListener(listener: StatusListener?) {
        statusListener = listener
    }

    interface StatusListener {
        fun onStatus(current: Int, total: Int, rebuilt: Int, detail: String?)
    }
}
