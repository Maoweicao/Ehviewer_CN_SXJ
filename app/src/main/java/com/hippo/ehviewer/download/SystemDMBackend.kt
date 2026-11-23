package com.hippo.ehviewer.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.SystemDownloadTask
import com.hippo.ehviewer.network.NetworkLogger
import com.hippo.ehviewer.spider.SpiderDen
import okhttp3.HttpUrl
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * 注意：start() 内含大量网络 I/O（解析图片页）与磁盘 I/O，必须在工作线程调用。
 */
class SystemDMBackend private constructor(private val appContext: Context) {

    private val dm: DownloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** gid → 正在运行的 start() 取消标记；stop/clear 时置 true 让解析线程尽快退出。 */
    private val activeStarts = ConcurrentHashMap<Long, AtomicBoolean>()

    /**
     * 解析并入队画廊所有未下载图片。
     *
     * 重要：必须在 SpiderBackend 未启动同一画廊的前提下调用（由 BackendRouter 保证）。
     * 本方法返回时部分图可能还在排队（系统 DM 内部调度），由 SystemDownloadCompleteReceiver
     * 在每张完成时通过 gid + pageIndex 找到对应记录并触发 FinalizeWorker。
     *
     * 本方法会阻塞到所有缺页解析完毕，因此只能在后台线程调用。
     *
     * @return 成功入队的图片数量；0 表示全部解析失败
     */
    fun start(galleryInfo: GalleryInfo, totalPages: Int): Int {
        val gid = galleryInfo.gid
        val cancelFlag = AtomicBoolean(false)
        activeStarts[gid] = cancelFlag
        try {
            // 只有仍在排队的页才算「已入队」。失败行会阻止同一页重新入队，
            // 因此在开始前清理；成功行以磁盘为准，不阻塞缺失页补下。
            val existing = EhDB.getSystemDownloadTasksForGid(gid) ?: emptyList()
            val activePages = HashSet<Int>()
            for (task in existing) {
                when (task.status) {
                    SystemDownloadTask.STATUS_PENDING,
                    SystemDownloadTask.STATUS_RUNNING -> activePages.add(task.pageIndex)

                    SystemDownloadTask.STATUS_FAILED -> {
                        try {
                            dm.remove(task.downloadId)
                        } catch (e: Exception) {
                            Log.w(TAG, "dm.remove failed for ${task.downloadId}", e)
                        }
                        EhDB.deleteSystemDownloadTask(task.downloadId)
                        Log.i(TAG, "gid=$gid drop failed task: id=${task.downloadId}, page=${task.pageIndex}")
                    }
                    // SUCCESS 不参与判定：页是否还需要下载由磁盘文件决定
                }
            }

            // 磁盘上已存在的页（含预下载合并剪切进来的文件）也跳过，只对缺失部分入队
            val downloadDir = SpiderDen.getExistingGalleryDownloadDir(galleryInfo)

            val enqueued = AtomicInteger(0)
            val skipped = AtomicInteger(0)
            val resolveFailed = AtomicInteger(0)
            val failedPages = Collections.synchronizedList(ArrayList<Int>())
            val nextPage = AtomicInteger(0)

            // 同一个 Session 共享 pToken 缓存：pToken 按预览页归档，拉错页或每页重拉都会导致
            // 大量重复请求（曾经的实现每页都拉一次详情页，2000 页就是 2000 次请求）。
            val session = ImageUrlResolver.Session(appContext, galleryInfo)

            val workerCount = Settings.getMultiThreadDownload().coerceIn(1, MAX_RESOLVE_THREADS)
                .coerceAtMost(if (totalPages > 0) totalPages else 1)

            val work = Runnable {
                try {
                    while (!cancelFlag.get()) {
                        val page = nextPage.getAndIncrement()
                        if (page >= totalPages) {
                            break
                        }
                        if (page in activePages) {
                            continue
                        }
                        if (downloadDir != null && SpiderDen.findImageFile(downloadDir, page) != null) {
                            skipped.incrementAndGet()
                            continue
                        }
                        val imageUrl = resolveWithRetry(session, page, cancelFlag)
                        if (cancelFlag.get()) {
                            break
                        }
                        if (imageUrl == null) {
                            resolveFailed.incrementAndGet()
                            if (failedPages.size < MAX_REPORTED_FAILED_PAGES) {
                                failedPages.add(page)
                            }
                            continue
                        }
                        if (enqueue(galleryInfo, page, imageUrl, cancelFlag)) {
                            enqueued.incrementAndGet()
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "resolve worker failed for gid=$gid", t)
                }
            }

            if (workerCount <= 1) {
                work.run()
            } else {
                val pool = Executors.newFixedThreadPool(workerCount) { runnable ->
                    Thread(runnable, "SystemDMResolve-$gid").apply { isDaemon = true }
                }
                try {
                    val futures = (0 until workerCount).map { pool.submit(work) }
                    for (future in futures) {
                        future.get()
                    }
                } finally {
                    pool.shutdownNow()
                }
            }

            if (cancelFlag.get()) {
                Log.i(TAG, "start gid=$gid cancelled: enqueued ${enqueued.get()} page(s)")
                log(gid, "start cancelled: enqueued=${enqueued.get()}")
                return enqueued.get()
            }

            val failedDetail = if (failedPages.isEmpty()) "" else ", failedPages=$failedPages"
            Log.i(
                TAG,
                "start gid=$gid: enqueued ${enqueued.get()} new tasks " +
                        "(existing ${existing.size}, skipped ${skipped.get()}, failed ${resolveFailed.get()})" +
                        failedDetail
            )
            log(
                gid,
                "start: enqueued=${enqueued.get()}, skipped=${skipped.get()}, " +
                        "failed=${resolveFailed.get()}$failedDetail"
            )
            return enqueued.get()
        } finally {
            // ConcurrentHashMap.remove(key, value) 是 API 24 才有的重载，minSdk 23 上避免使用
            if (activeStarts[gid] === cancelFlag) {
                activeStarts.remove(gid)
            }
        }
    }

    /**
     * 单页解析，失败重试一次。网络抖动时直接放弃会让该页永久缺失（不会再有任务重试）。
     */
    private fun resolveWithRetry(
        session: ImageUrlResolver.Session,
        page: Int,
        cancelFlag: AtomicBoolean
    ): String? {
        for (attempt in 0 until RESOLVE_ATTEMPTS) {
            if (cancelFlag.get()) {
                return null
            }
            val imageUrl = session.resolve(page)
            if (imageUrl != null) {
                return imageUrl
            }
            if (attempt + 1 < RESOLVE_ATTEMPTS) {
                try {
                    Thread.sleep(RESOLVE_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    private fun enqueue(
        galleryInfo: GalleryInfo,
        page: Int,
        imageUrl: String,
        cancelFlag: AtomicBoolean
    ): Boolean {
        return try {
            val request = DownloadManager.Request(Uri.parse(imageUrl))
                .setTitle("EHViewer ${galleryInfo.gid} #$page")
            applyRequestDefaults(request, imageUrl)

            val downloadId = dm.enqueue(request)
            if (cancelFlag.get()) {
                // 期间被 stop/clear：撤回刚入队的任务，避免清理之后又冒出孤儿记录
                try {
                    dm.remove(downloadId)
                } catch (e: Exception) {
                    Log.w(TAG, "dm.remove failed for cancelled ${downloadId}", e)
                }
                return false
            }
            val task = SystemDownloadTask()
            task.downloadId = downloadId
            task.gid = galleryInfo.gid
            task.pageIndex = page
            task.resolvedUrl = imageUrl
            task.status = SystemDownloadTask.STATUS_PENDING
            task.retryCount = 0
            task.createdAt = System.currentTimeMillis()
            EhDB.putSystemDownloadTask(task)
            true
        } catch (e: Exception) {
            Log.e(TAG, "enqueue failed for gid=${galleryInfo.gid} page=$page", e)
            false
        }
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
        // 通知仍在解析的 start() 退出，否则清理完还会继续入队
        activeStarts[gid]?.set(true)
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

    /**
     * 是否还有排队 / 运行中的系统 DM 任务。
     *
     * 与 [hasTasksForGid] 的区别：只有 SUCCESS 行时返回 false，避免「本次一页都没入队、
     * 但历史成功行还在」被误判为下载已委托出去而永远停在下载中。
     */
    fun hasPendingTasksForGid(gid: Long): Boolean {
        val tasks = EhDB.getSystemDownloadTasksForGid(gid) ?: return false
        return tasks.any {
            it.status == SystemDownloadTask.STATUS_PENDING ||
                    it.status == SystemDownloadTask.STATUS_RUNNING
        }
    }

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

        /** 解析图片 URL 的最大并发数，同时受用户「多线程下载」设置约束。 */
        private const val MAX_RESOLVE_THREADS = 8

        /** 单页解析失败后的重试次数（含首次）。 */
        private const val RESOLVE_ATTEMPTS = 2

        private const val RESOLVE_RETRY_DELAY_MS = 1000L

        /** 失败页号最多回报多少个，避免日志过长。 */
        private const val MAX_REPORTED_FAILED_PAGES = 20

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
