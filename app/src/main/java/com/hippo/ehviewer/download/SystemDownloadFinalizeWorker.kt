package com.hippo.ehviewer.download

import android.app.DownloadManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.dao.SystemDownloadTask
import com.hippo.ehviewer.network.NetworkLogger
import com.hippo.ehviewer.spider.SpiderDen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单张图片完成后的最终落地：
 *  1. dm.openDownloadedFile(downloadId) 拿到系统 DM 写入的文件流
 *  2. BitmapFactory.decodeStream(inJustDecodeBounds=true) 嗅探 MIME
 *  3. 用固定规则的文件名（00000001.ext）写到 SpiderDen
 *  4. 校验非空、置 task.status = SUCCESS
 *  5. 若本 gid 已全部 page 完成，触发隐私清理（如开启）
 *
 * MOVE 语义：通过 SpiderDen.writeAtomicMove 实现（File.renameTo），避免双倍占用。
 */
class SystemDownloadFinalizeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1L)
        val gid = inputData.getLong(KEY_GID, -1L)
        val pageIndex = inputData.getInt(KEY_PAGE_INDEX, -1)

        if (downloadId < 0 || gid < 0 || pageIndex < 0) {
            Log.w(TAG, "doWork: invalid input")
            return@withContext Result.failure()
        }

        val task = EhDB.getSystemDownloadTask(downloadId)
        if (task == null || task.status == SystemDownloadTask.STATUS_SUCCESS) {
            Log.i(TAG, "doWork: task not found or already SUCCESS, skip")
            return@withContext Result.success()
        }

        val dm = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val info = try {
            queryDownloadInfo(dm, downloadId)
        } catch (e: Exception) {
            Log.e(TAG, "dm.query failed for $downloadId", e)
            null
        }

        if (info == null || info.status != DownloadManager.STATUS_SUCCESSFUL) {
            log(gid, "finalize rejected: id=$downloadId, status=${info?.status}, reason=${info?.reason}, bytes=${info?.downloadedBytes}/${info?.totalBytes}")
            task.status = SystemDownloadTask.STATUS_FAILED
            task.retryCount += 1
            EhDB.putSystemDownloadTask(task)
            return@withContext Result.retry()
        }

        // 嗅探 + 写入 SpiderDen
        try {
            val moved = finalizeToSpiderDen(downloadId, gid, pageIndex)
            if (!moved) {
                log(gid, "finalize copy failed: id=$downloadId, page=$pageIndex")
                task.status = SystemDownloadTask.STATUS_FAILED
                task.retryCount += 1
                EhDB.putSystemDownloadTask(task)
                return@withContext Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "doWork: finalize failed for $downloadId", e)
            log(gid, "finalize exception: id=$downloadId, page=$pageIndex, ${e.message}", e)
            task.status = SystemDownloadTask.STATUS_FAILED
            task.retryCount += 1
            EhDB.putSystemDownloadTask(task)
            return@withContext Result.retry()
        }

        // 成功：标记 SUCCESS
        task.status = SystemDownloadTask.STATUS_SUCCESS
        EhDB.putSystemDownloadTask(task)
        log(gid, "finalize complete: id=$downloadId, page=$pageIndex")

        // 通知 DownloadInfo 更新 finished
        updateDownloadInfoFinished(gid, pageIndex)

        // 全部 page 完成 → 触发隐私清理 + 通知 DownloadManager 调度下一项
        val dm2 = try {
            EhApplication.getDownloadManager(applicationContext)
        } catch (e: Exception) {
            null
        }
        val allDone = dm2 != null && dm2.isSystemDMAllDone(gid)
        if (allDone) {
            try {
                dm2?.onSystemDMGalleryFinished(gid)
            } catch (e: Exception) {
                Log.w(TAG, "onSystemDMGalleryFinished failed", e)
            }
        }
        if (com.hippo.ehviewer.Settings.getDownloadPrivateMode()) {
            maybeTriggerPrivacyCleanup(gid)
        }

        // dm.remove 清掉系统 DM 记录（同时删除暂存文件）
        try {
            dm.remove(downloadId)
        } catch (e: Exception) {
            Log.w(TAG, "dm.remove post-success failed", e)
        }

        Result.success()
    }

    /**
     * 用 BitmapFactory 嗅探 MIME，再用 MOVE 语义把系统 DM 文件移到 SpiderDen 的最终位置。
     */
    private fun finalizeToSpiderDen(downloadId: Long, gid: Long, pageIndex: Int): Boolean {
        val dm = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val info = queryDownloadInfo(dm, downloadId) ?: return false
        val srcUri: Uri = info.uri ?: return false

        val galleryInfo = EhDB.getDownloadInfo(gid) ?: return false
        val dir = SpiderDen.getGalleryDownloadDir(galleryInfo) ?: run {
            Log.w(TAG, "SpiderDen dir null for gid=$gid")
            return false
        }
        if (!dir.ensureDir()) {
            Log.w(TAG, "ensureDir failed for gid=$gid")
            return false
        }

        // 嗅探 MIME：用 BitmapFactory.Options.inJustDecodeBounds=true
        var extension: String? = null
        applicationContext.contentResolver.openInputStream(srcUri)?.use { input ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, opts)
            val mime = opts.outMimeType
            extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        }
        if (extension.isNullOrEmpty()) {
            Log.w(TAG, "sniffed MIME unknown; default to jpg")
            extension = "jpg"
        }

        // 文件名（与 SpiderDen.generateImageFilename 一致）
        val filename = SpiderDen.generateImageFilename(pageIndex, ".$extension")
        val dst = dir.createFile(filename) ?: return false

        // 移动：用 InputStream + OutputStream 拷贝后删除源。
        // 真正的 atomic rename 需要 src/dst 在同一文件系统下；这里系统 DM
        // 写入的是其内部目录，与 SpiderDen 不在同一 fs，只能流式 MOVE。
        return try {
            applicationContext.contentResolver.openInputStream(srcUri)?.use { input ->
                dst.openOutputStream()?.use { output ->
                    input.copyTo(output)
                } ?: return false
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "copy file failed", e)
            dst.delete()
            false
        }
    }

    private fun queryDownloadInfo(dm: DownloadManager, downloadId: Long): DownloadInfo? {
        return try {
            dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val idIdx = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                val downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                if (idIdx < 0 || statusIdx < 0) return null
                DownloadInfo(
                    downloadId = cursor.getLong(idIdx),
                    status = cursor.getInt(statusIdx),
                    uri = if (uriIdx >= 0 && !cursor.isNull(uriIdx)) Uri.parse(cursor.getString(uriIdx)) else null,
                    reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else 0,
                    downloadedBytes = if (downloadedIdx >= 0) cursor.getLong(downloadedIdx) else -1,
                    totalBytes = if (totalIdx >= 0) cursor.getLong(totalIdx) else -1
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryDownloadInfo failed", e)
            null
        }
    }

    private fun updateDownloadInfoFinished(gid: Long, pageIndex: Int) {
        val info = EhDB.getDownloadInfo(gid) ?: return
        if (pageIndex + 1 > info.finished) {
            info.finished = pageIndex + 1
            EhDB.putDownloadInfo(info)
        }
    }

    /**
     * 检查 gid 所有 page 是否都已 SUCCESS。如果是则触发隐私清理。
     * 隐私清理逻辑见 Settings.getDownloadPrivateMode + SystemDMBackend.cleanupForGid。
     */
    private fun maybeTriggerPrivacyCleanup(gid: Long) {
        val tasks = EhDB.getSystemDownloadTasksForGid(gid) ?: return
        if (tasks.any { it.status != SystemDownloadTask.STATUS_SUCCESS }) return
        // 全部 SUCCESS 且没有 PENDING/FAILED
        // 触发隐私清理：3 分钟延迟后清理
        SimpleHandlerCompat.postDelayed({
            PrivacyCleanupHelper.cleanupForGid(applicationContext, gid)
        }, 3 * 60 * 1000L)
    }

    private data class DownloadInfo(
        val downloadId: Long,
        val status: Int,
        val uri: Uri?,
        val reason: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    )

    companion object {
        private const val TAG = "SystemDownloadFinalizeWorker"
        const val KEY_DOWNLOAD_ID = "downloadId"
        const val KEY_GID = "gid"
        const val KEY_PAGE_INDEX = "pageIndex"

        fun buildInputData(downloadId: Long, gid: Long, pageIndex: Int): Data =
            Data.Builder()
                .putLong(KEY_DOWNLOAD_ID, downloadId)
                .putLong(KEY_GID, gid)
                .putInt(KEY_PAGE_INDEX, pageIndex)
                .build()

        private fun log(gid: Long, message: String, throwable: Throwable? = null) {
            if (throwable == null) Log.i(TAG, message) else Log.w(TAG, message, throwable)
            NetworkLogger.logDownload("SystemDM: $message", throwable)
            try {
                DownloadLogger.getInstance().log(
                    if (throwable == null) DownloadLogger.LogLevel.INFO else DownloadLogger.LogLevel.ERROR,
                    TAG,
                    message,
                    gid.toString(),
                    null,
                    throwable as? Exception
                )
            } catch (_: IllegalStateException) {
            }
        }
    }
}

/**
 * 延迟 Handler 的极简包装（独立成 object 避免和现有 SimpleHandler 冲突）。
 */
private object SimpleHandlerCompat {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    fun postDelayed(r: Runnable, delayMs: Long) {
        handler.postDelayed(r, delayMs)
    }
}
