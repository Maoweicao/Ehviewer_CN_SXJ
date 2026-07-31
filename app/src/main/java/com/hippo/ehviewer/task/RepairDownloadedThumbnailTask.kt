package com.hippo.ehviewer.task

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 修复已下载缩略图任务
 *
 * 模式说明：
 * - [preferUrl]=false（设置页使用）：缺失缩略图时直接用首张图片生成
 * - [preferUrl]=true（下载信息页使用）：优先从网络请求画廊缩略图URL，失败再用首张图片
 */
class RepairDownloadedThumbnailTask(
    context: Context,
    private val preferUrl: Boolean = false
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "RepairThumbTask"
        private const val THUMB_MAX_WIDTH = 300
        private const val THUMB_MAX_HEIGHT = 400
        private const val THUMB_QUALITY = 80
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 15_000
    }

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false
    private var statusListener: StatusListener? = null

    private var totalProgress = 0
    private var fixedCount = 0
    private var skippedCount = 0

    private fun notifyStatus() {
        statusListener?.onStatus(currentProgress, totalProgress, fixedCount, skippedCount)
    }

    override fun getTaskId(): String = "repair_thumbnail"

    override fun getTaskName(): String = context.getString(R.string.settings_download_repair_thumbnail)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.CLEANUP

    override fun getTaskDescription(): String? = context.getString(R.string.settings_download_repair_thumbnail_summary)

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            Log.d(TAG, "开始修复缩略图 (preferUrl=$preferUrl)")
            appendTaskLog("开始修复缩略图 (preferUrl=$preferUrl)")

            coroutineContext[Job]?.ensureActive()

            val downloadManager = EhApplication.getDownloadManager(context)
            val downloadInfoList = downloadManager.allDownloadInfoList

            if (downloadInfoList.isNullOrEmpty()) {
                Log.d(TAG, "没有已下载的画廊")
                appendTaskLog("没有已下载的画廊")
                notifyCompleted()
                notifyStatus()
                return Result.success(Unit)
            }

            totalProgress = downloadInfoList.size
            fixedCount = 0
            skippedCount = 0
            notifyStatus()

            Log.d(TAG, "总共需要检查 $totalProgress 个画廊")
            appendTaskLog("总共需要检查 $totalProgress 个画廊")

            for (info in downloadInfoList) {
                if (isCancelled) throw CancellationException("任务已取消")
                while (isPaused && !isCancelled) {
                    Thread.sleep(200)
                }
                coroutineContext[Job]?.ensureActive()

                val result = repairSingleThumbnail(info)
                if (result) {
                    fixedCount++
                    appendTaskLog("修复成功: ${info.title}")
                } else {
                    skippedCount++
                }

                currentProgress++
                val detail = context.getString(R.string.repair_thumbnail_progress,
                    currentProgress, totalProgress, fixedCount, skippedCount)
                updateProgress(currentProgress, totalProgress, detail)
                notifyStatus()
            }

            val msg = context.getString(R.string.repair_thumbnail_complete, fixedCount, skippedCount)
            Log.i(TAG, msg)
            appendTaskLog(msg)
            notifyCompleted()
            notifyStatus()
            Result.success(Unit)
        } catch (e: CancellationException) {
            Log.d(TAG, "任务被取消")
            appendTaskLog("任务已取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "修复缩略图出错", e)
            appendTaskLog("修复缩略图出错: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }

    /**
     * 修复单个画廊的缩略图，返回 true 表示成功修复，false 表示跳过
     */
    private fun repairSingleThumbnail(info: DownloadInfo): Boolean {
        val dir = SpiderDen.getGalleryDownloadDir(info) ?: return false
        if (!dir.isDirectory) return false

        // 检查 .thumb 是否已存在
        val existingThumb = dir.findFile(".thumb")
        if (existingThumb != null) return false

        // 需要修复
        Log.d(TAG, "修复缩略图: ${info.title} (${info.gid})")

        var fixed = false

        // 优先从URL下载缩略图
        if (preferUrl && !info.thumb.isNullOrEmpty()) {
            fixed = downloadThumbnailFromUrl(info.thumb, dir)
        }

        // URL下载失败或不需要URL，从首张图片生成
        if (!fixed) {
            fixed = createThumbnailFromFirstImage(dir)
        }

        return fixed
    }

    /**
     * 从URL下载缩略图
     */
    private fun downloadThumbnailFromUrl(thumbUrl: String, dir: UniFile): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(thumbUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.connect()

            if (conn.responseCode != 200) return false

            val contentType = conn.contentType ?: ""
            if (!contentType.startsWith("image/")) return false

            val inputStream = conn.inputStream ?: return false
            val thumbFile = dir.createFile(".thumb") ?: return false
            val outputStream = thumbFile.openOutputStream()

            val buffer = ByteArray(8192)
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                outputStream.write(buffer, 0, len)
            }
            outputStream.close()
            inputStream.close()

            Log.d(TAG, "从URL下载缩略图成功: ${thumbUrl}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "从URL下载缩略图失败: $thumbUrl", e)
            return false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 从首张图片生成缩略图
     */
    private fun createThumbnailFromFirstImage(dir: UniFile): Boolean {
        try {
            val firstImage = findFirstImage(dir) ?: return false
            val inputStream = firstImage.openInputStream() ?: return false

            // 先读取尺寸
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val tempBytes = readAllBytes(inputStream)
            inputStream.close()

            BitmapFactory.decodeByteArray(tempBytes, 0, tempBytes.size, options)
            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) return false

            // 计算采样率
            var sampleSize = 1
            while (width / sampleSize > THUMB_MAX_WIDTH * 2 || height / sampleSize > THUMB_MAX_HEIGHT * 2) {
                sampleSize *= 2
            }

            // 解码 Bitmap
            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize
            val bitmap = BitmapFactory.decodeByteArray(tempBytes, 0, tempBytes.size, options) ?: return false

            // 缩放到目标尺寸
            val scale = minOf(
                THUMB_MAX_WIDTH.toFloat() / bitmap.width,
                THUMB_MAX_HEIGHT.toFloat() / bitmap.height,
                1f
            )

            val finalBitmap = if (scale < 1f) {
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                if (scaled !== bitmap) bitmap.recycle()
                scaled
            } else {
                bitmap
            }

            // 保存为 .thumb
            val thumbFile = dir.createFile(".thumb") ?: run {
                finalBitmap.recycle()
                return false
            }
            val outputStream = thumbFile.openOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, outputStream)
            outputStream.close()
            finalBitmap.recycle()

            Log.d(TAG, "从首张图片生成缩略图成功")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "从首张图片生成缩略图失败", e)
            return false
        }
    }

    /**
     * 查找下载目录中的第一张图片
     */
    private fun findFirstImage(dir: UniFile): UniFile? {
        val files = dir.listFiles() ?: return null
        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
        return files
            .filter { it.isFile }
            .mapNotNull { file ->
                val name = file.name?.lowercase() ?: return@mapNotNull null
                val ext = name.substringAfterLast('.', "")
                if (ext in imageExtensions) {
                    val num = name.replace(Regex("[^0-9]"), "").toLongOrNull() ?: Long.MAX_VALUE
                    Pair(file, num)
                } else null
            }
            .sortedBy { it.second }
            .map { it.first }
            .firstOrNull()
    }

    private fun readAllBytes(inputStream: InputStream): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val temp = ByteArray(8192)
        var len: Int
        while (inputStream.read(temp).also { len = it } != -1) {
            buffer.write(temp, 0, len)
        }
        return buffer.toByteArray()
    }

    override fun isPausable(): Boolean = true

    override suspend fun pause() {
        isPaused = true
        updateState(TaskState.PAUSED)
        appendTaskLog("任务已暂停")
    }

    override suspend fun resume() {
        isPaused = false
        updateState(TaskState.RUNNING)
        appendTaskLog("任务已恢复")
    }

    override suspend fun cancel() {
        isCancelled = true
        isPaused = false
        notifyCancelled()
    }

    override fun getProgressDetail(): String? {
        return if (totalProgress > 0) {
            "$currentProgress/$totalProgress (${context.getString(R.string.repair_thumbnail_progress, currentProgress, totalProgress, fixedCount, skippedCount)})"
        } else null
    }

    fun setStatusListener(listener: StatusListener?) {
        this.statusListener = listener
    }

    interface StatusListener {
        fun onStatus(current: Int, total: Int, fixed: Int, skipped: Int)
    }
}
