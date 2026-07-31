package com.hippo.ehviewer.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.SystemDownloadTask
import com.hippo.ehviewer.network.NetworkLogger
import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicInteger

/**
 * 系统 DownloadManager 后端。
 *
 * - start(gallery): 解析所有图片 URL，逐张 enqueue 到 android.app.DownloadManager，
 *   持久化 downloadId ↔ (gid, pageIndex) 到 SYSTEM_DOWNLOAD_TASKS 表
 * - cleanupForGid(gid): 删 gid 对应的所有 SystemDownloadTask 行 + 调用系统 DM remove()
 * - 进度查询和单张完成处理通过 SystemDownloadCompleteReceiver 完成
 *
 * 与 SpiderBackend 完全独立；通过 BackendRouter 选择。
 */
class SystemDMBackend private constructor(private val appContext: Context) {

    private val dm: DownloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * 解析并入队画廊所有未下载图片。
     *
     * 重要：必须在 SpiderBackend 未启动同一画廊的前提下调用（由 BackendRouter 保证）。
     * 本方法返回时部分图可能还在排队（系统 DM 内部调度），由 SystemDownloadCompleteReceiver
     * 在每张完成时通过 gid + pageIndex 找到对应记录并触发 FinalizeWorker。
     *
     * @return 成功入队的图片数量；0 表示全部解析失败
     */
    fun start(galleryInfo: com.hippo.ehviewer.client.data.GalleryInfo, totalPages: Int): Int {
        // 已经入队的图跳过
        val existing = EhDB.getSystemDownloadTasksForGid(galleryInfo.gid) ?: emptyList()
        val existingPages = existing.map { it.pageIndex }.toSet()

        val enqueued = AtomicInteger(0)
        for (page in 0 until totalPages) {
            if (page in existingPages) {
                continue
            }
            val imageUrl = ImageUrlResolver.resolve(appContext, galleryInfo, page)
            if (imageUrl == null) {
                Log.w(TAG, "resolve failed for gid=${galleryInfo.gid} page=$page, skip")
                continue
            }

            val request = DownloadManager.Request(Uri.parse(imageUrl))
                .setTitle("EHViewer ${galleryInfo.gid} #$page")
            applyRequestDefaults(request, imageUrl)

            try {
                val downloadId = dm.enqueue(request)
                val task = SystemDownloadTask()
                task.downloadId = downloadId
                task.gid = galleryInfo.gid
                task.pageIndex = page
                task.resolvedUrl = imageUrl
                task.status = SystemDownloadTask.STATUS_PENDING
                task.retryCount = 0
                task.createdAt = System.currentTimeMillis()
                EhDB.putSystemDownloadTask(task)
                enqueued.incrementAndGet()
                log(galleryInfo.gid, "enqueued: id=$downloadId, page=$page, host=${Uri.parse(imageUrl).host}")
            } catch (e: Exception) {
                Log.e(TAG, "enqueue failed for gid=${galleryInfo.gid} page=$page", e)
                log(galleryInfo.gid, "enqueue failed: page=$page, ${e.message}")
            }
        }
        Log.i(TAG, "start gid=${galleryInfo.gid}: enqueued ${enqueued.get()} new tasks (existing ${existing.size})")
        return enqueued.get()
    }

    /**
     * 应用默认的 Request 配置（网络策略、隐藏路径、允许漫游、可见性等）。
     */
    private fun applyRequestDefaults(request: DownloadManager.Request, imageUrl: String) {
        // 网络类型：根据用户设置决定是否允许移动网络
        request.setAllowedNetworkTypes(
            DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
        )
        request.setAllowedOverRoaming(true)
        // 不要显示在系统下载 UI（保持隐私）
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
        request.setVisibleInDownloadsUi(false)
        request.addRequestHeader("User-Agent", Settings.getUserAgent())
        request.addRequestHeader("Referer", "https://e-hentai.org/")
        val cookie = HttpUrl.parse(imageUrl)?.let { url ->
            EhApplication.getEhCookieStore(appContext).loadForRequest(url)
                .joinToString("; ") { "${it.name()}=${it.value()}" }
        }
        if (!cookie.isNullOrEmpty()) request.addRequestHeader("Cookie", cookie)
    }

    /**
     * 通过 gid 清理所有相关 SystemDownloadTask 行和系统 DM 任务。
     * 被 privacy cleanup 和 stopAll 时调用。
     */
    fun cleanupForGid(gid: Long) {
        val tasks = EhDB.getSystemDownloadTasksForGid(gid) ?: return
        for (task in tasks) {
            try {
                dm.remove(task.downloadId)
            } catch (e: Exception) {
                Log.w(TAG, "dm.remove failed for ${task.downloadId}", e)
            }
        }
        EhDB.deleteSystemDownloadTasksForGid(gid)
        Log.i(TAG, "cleanupForGid($gid): removed ${tasks.size} system DM tasks")
    }

    /**
     * 列出当前 gid 的所有 pending/running 系统 DM 任务 ID（用于外部 stop 接口）。
     */
    fun pendingDownloadIdsForGid(gid: Long): LongArray {
        val tasks = EhDB.getSystemDownloadTasksForGid(gid) ?: return LongArray(0)
        return tasks.map { it.downloadId }.toLongArray()
    }

    fun hasTasksForGid(gid: Long): Boolean = !(EhDB.getSystemDownloadTasksForGid(gid).isNullOrEmpty())

    private fun log(gid: Long, message: String) {
        NetworkLogger.logDownload("SystemDM: $message")
        try {
            DownloadLogger.getInstance().log(
                DownloadLogger.LogLevel.INFO, TAG, message, gid.toString(), null
            )
        } catch (_: IllegalStateException) {
        }
    }

    /**
     * 列出某 gid 已成功导入的 page 列表（用于系统重启后 resume）。
     */
    fun completedPagesForGid(gid: Long): Set<Int> {
        val tasks = EhDB.getSystemDownloadTasksForGid(gid) ?: return emptySet()
        return tasks
            .filter { it.status == SystemDownloadTask.STATUS_SUCCESS }
            .map { it.pageIndex }.toSet()
    }

    companion object {
        private const val TAG = "SystemDMBackend"

        @Volatile
        private var sInstance: SystemDMBackend? = null

        @JvmStatic
        fun getInstance(context: Context): SystemDMBackend {
            return sInstance ?: synchronized(this) {
                sInstance ?: SystemDMBackend(context.applicationContext).also { sInstance = it }
            }
        }

        /**
         * 检测系统 DownloadManager 是否可用（provider 存在 + service 可获取）。
         * 部分国产 ROM（如小米、华为）可能禁用了系统下载组件。
         */
        @JvmStatic
        fun isAvailable(context: Context): Boolean {
            return try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager
                if (dm == null) return false
                // 最可靠的检测：试着 query 一下（无副作用）
                dm.query(android.app.DownloadManager.Query().setFilterById(-1L))
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
