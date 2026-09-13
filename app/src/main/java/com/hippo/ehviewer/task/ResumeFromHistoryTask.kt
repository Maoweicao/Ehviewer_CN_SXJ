package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.cache.GalleryCacheManager
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadHistory
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import kotlin.coroutines.coroutineContext

/**
 * 从下载历史恢复下载任务
 *
 * 功能：
 * 1. 查询 DownloadHistory 表获取所有历史记录
 * 2. 过滤掉当前已在下载列表中的
 * 3. 检查本地目录是否存在（磁盘上的画廊）
 * 4. 对于本地存在的：读取 .ehviewer.extra.json 获取完整信息（包括缩略图）
 * 5. 对于本地不存在的：完整下载
 * 6. 批量添加到下载队列
 */
class ResumeFromHistoryTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "ResumeFromHistoryTask"
        private const val API_REQUEST_DELAY_MS = 1500L
    }

    @Volatile
    private var isCancelled = false

    private val downloadManager: DownloadManager = EhApplication.getDownloadManager(context)
    private val httpClient: OkHttpClient = EhApplication.getOkHttpClient(context)

    private var totalAdded = 0
    private var totalFailed = 0
    private var onDiskCount = 0
    private var fullDownloadCount = 0

    override fun getTaskId(): String = "resume_from_history"

    override fun getTaskName(): String = context.getString(R.string.settings_download_resume_from_history)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.IMPORT

    override fun getTaskDescription(): String? = context.getString(R.string.settings_download_resume_from_history_summary)

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            appendTaskLog(context.getString(R.string.resume_from_history_scanning))

            coroutineContext[Job]?.ensureActive()

            // Phase 1: 获取所有下载历史
            val allHistory = EhDB.getAllDownloadHistory()
            appendTaskLog("下载历史中共 ${allHistory.size} 条记录")

            // Phase 2: 过滤出不在当前下载列表中的记录
            // 仅恢复当前有效（deletionType == DELETION_NONE）的历史，跳过用户主动删除或合并掉的记录，
            // 避免误把用户故意删除/合并的画廊重新下载。
            val candidates = allHistory.filter { history ->
                history.deletionType == DownloadHistory.DELETION_NONE &&
                    !downloadManager.containDownloadInfo(history.gid)
            }
            appendTaskLog("不在当前下载列表中的: ${candidates.size} 条")

            if (candidates.isEmpty()) {
                appendTaskLog("没有需要恢复的下载历史")
                notifyCompleted()
                return Result.success(Unit)
            }

            updateProgress(-1, "分析 ${candidates.size} 条历史记录...")

            // Phase 3: 分析每条记录
            onDiskCount = 0
            fullDownloadCount = 0
            val galleriesToAdd = mutableListOf<GalleryInfo>()
            val needApiInfo = mutableListOf<DownloadHistory>()

            for (history in candidates) {
                if (isCancelled) {
                    throw CancellationException("任务已取消")
                }

                // 检查本地目录是否存在
                val dirname = history.filePath
                val dirInfo = checkLocalFilesExist(history.gid, dirname)

                if (dirInfo != null) {
                    onDiskCount++
                    // 本地有文件，读取 .ehviewer.extra.json 获取完整信息（包括缩略图）
                    val galleryInfo = createGalleryInfoFromHistory(history, dirInfo)
                    if (galleryInfo != null) {
                        galleriesToAdd.add(galleryInfo)
                    }
                } else {
                    // 本地没有文件，需要完整下载
                    fullDownloadCount++
                    needApiInfo.add(history)
                }
            }

            appendTaskLog("分析完成: $onDiskCount 个在磁盘上, $fullDownloadCount 个需要完整下载")

            // Phase 4: 从 API 获取需要完整下载的画廊信息
            if (needApiInfo.isNotEmpty()) {
                updateProgress(-1, "从 API 获取 ${needApiInfo.size} 个画廊信息...")
                appendTaskLog("正在从 API 获取 ${needApiInfo.size} 个画廊的详细信息...")

                coroutineContext[Job]?.ensureActive()

                val galleryInfoList = mutableListOf<GalleryInfo>()
                for (history in needApiInfo) {
                    val gi = GalleryInfo()
                    gi.gid = history.gid
                    gi.token = history.token ?: ""
                    gi.title = history.title
                    gi.titleJpn = history.titleJpn
                    galleryInfoList.add(gi)
                }

                try {
                    EhEngine.fillGalleryListByApi(
                        null,
                        httpClient,
                        ArrayList(galleryInfoList),
                        EhUrl.getReferer()
                    )
                    // 用 API 获取到的信息补充列表
                    for (gi in galleryInfoList) {
                        if (gi.title != null && gi.token != null) {
                            galleriesToAdd.add(gi)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to fill gallery list from API", e)
                    appendTaskLog("API 获取失败: ${e.message}")
                    // 仍然添加能用的记录
                    for (gi in galleryInfoList) {
                        if (gi.title != null && gi.token != null) {
                            galleriesToAdd.add(gi)
                        }
                    }
                }
            }

            if (galleriesToAdd.isEmpty()) {
                appendTaskLog("没有可恢复的画廊")
                notifyCompleted()
                return Result.success(Unit)
            }

            // Phase 5: 添加到下载队列
            appendTaskLog("开始添加 ${galleriesToAdd.size} 个画廊到下载队列...")
            updateProgress(-1, "添加 ${galleriesToAdd.size} 个画廊到下载队列...")

            var successCount = 0
            var failCount = 0

            for ((index, galleryInfo) in galleriesToAdd.withIndex()) {
                if (isCancelled) {
                    throw CancellationException("任务已取消")
                }

                try {
                    downloadManager.addDownload(galleryInfo, null)
                    // 如果有目录名信息，保存到 DownloadDirname
                    val history = candidates.find { it.gid == galleryInfo.gid }
                    if (history != null && history.filePath != null) {
                        EhDB.putDownloadDirname(history.gid, history.filePath)
                    }
                    successCount++
                } catch (e: Exception) {
                    failCount++
                    appendTaskLog("添加失败: ${galleryInfo.title} (GID: ${galleryInfo.gid}): ${e.message}")
                }

                updateProgress(index + 1, galleriesToAdd.size, "已处理 ${index + 1}/${galleriesToAdd.size}")
            }

            totalAdded = successCount
            totalFailed = failCount

            val summary = "恢复完成: 成功 $successCount，失败 $failCount (磁盘: $onDiskCount，完整下载: $fullDownloadCount)"
            Log.d(TAG, summary)
            appendTaskLog(summary)
            notifyCompleted()
            return Result.success(Unit)

        } catch (e: CancellationException) {
            Log.d(TAG, "Resume from history cancelled")
            appendTaskLog("任务已取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Throwable) {
            Log.e(TAG, "Resume from history failed", e)
            appendTaskLog("任务失败: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }

    override fun isPausable(): Boolean = false

    override suspend fun cancel() {
        isCancelled = true
        notifyCancelled()
    }

    /**
     * 目录信息数据类
     */
    private data class DirInfo(
        val dir: UniFile,
        val dirname: String
    )

    /**
     * 检查本地是否存在画廊文件，返回目录信息
     */
    private fun checkLocalFilesExist(gid: Long, dirname: String?): DirInfo? {
        if (dirname.isNullOrEmpty()) return null

        try {
            val downloadDir = com.hippo.ehviewer.Settings.getDownloadLocation() ?: return null
            val dir = downloadDir.findFile(dirname) ?: return null
            if (!dir.isDirectory) return null

            // 检查是否有非元数据文件
            val files = dir.listFiles()
            if (files.isNullOrEmpty()) return null

            for (file in files) {
                val name = file.name
                if (file.isFile && name != null && !name.startsWith(".")) {
                    return DirInfo(dir, dirname)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking local files for gid=$gid", e)
        }
        return null
    }

    /**
     * 从 DownloadHistory 创建 GalleryInfo，优先读取 .ehviewer.extra.json 获取完整信息
     */
    private fun createGalleryInfoFromHistory(history: DownloadHistory, dirInfo: DirInfo): GalleryInfo? {
        // 如果 token 为空，尝试从缓存读取
        if (history.token.isNullOrEmpty()) {
            // 尝试读取 .ehviewer.extra.json
            val cacheManager = GalleryCacheManager.getInstance(context)
            val cachedDetail = cacheManager.readGalleryCache(history.gid, dirInfo.dir)
            if (cachedDetail != null) {
                Log.d(TAG, "从缓存恢复画廊信息: ${cachedDetail.title} (GID: ${history.gid})")
                return cachedDetail
            }
            // 如果缓存也没有，返回 null（无法恢复没有有效 token 的记录）
            Log.w(TAG, "无法恢复画廊: GID=${history.gid}, token 为空且无缓存")
            return null
        }

        // 有 token，创建 GalleryInfo
        val galleryInfo = GalleryInfo()
        galleryInfo.gid = history.gid
        galleryInfo.token = history.token
        galleryInfo.title = history.title
        galleryInfo.titleJpn = history.titleJpn

        // 尝试从 .ehviewer.extra.json 补充更多信息
        try {
            val cacheManager = GalleryCacheManager.getInstance(context)
            val cachedDetail = cacheManager.readGalleryCache(history.gid, dirInfo.dir)
            if (cachedDetail != null) {
                // 补充缓存中的信息（缩略图、分类等）
                if (galleryInfo.thumb.isNullOrEmpty() && !cachedDetail.thumb.isNullOrEmpty()) {
                    galleryInfo.thumb = cachedDetail.thumb
                }
                if (galleryInfo.category == 0 && cachedDetail.category != 0) {
                    galleryInfo.category = cachedDetail.category
                }
                if (galleryInfo.pages == 0 && cachedDetail.pages > 0) {
                    galleryInfo.pages = cachedDetail.pages
                }
                // 补充其他信息
                if (galleryInfo.title.isNullOrEmpty() && !cachedDetail.title.isNullOrEmpty()) {
                    galleryInfo.title = cachedDetail.title
                }
                if (galleryInfo.titleJpn.isNullOrEmpty() && !cachedDetail.titleJpn.isNullOrEmpty()) {
                    galleryInfo.titleJpn = cachedDetail.titleJpn
                }
                if (galleryInfo.posted.isNullOrEmpty() && !cachedDetail.posted.isNullOrEmpty()) {
                    galleryInfo.posted = cachedDetail.posted
                }
                if (galleryInfo.uploader.isNullOrEmpty() && !cachedDetail.uploader.isNullOrEmpty()) {
                    galleryInfo.uploader = cachedDetail.uploader
                }
                if (galleryInfo.rating == 0f && cachedDetail.rating > 0) {
                    galleryInfo.rating = cachedDetail.rating
                }
                if (galleryInfo.simpleLanguage.isNullOrEmpty() && !cachedDetail.simpleLanguage.isNullOrEmpty()) {
                    galleryInfo.simpleLanguage = cachedDetail.simpleLanguage
                }
                if (galleryInfo.simpleTags == null && cachedDetail.simpleTags != null) {
                    galleryInfo.simpleTags = cachedDetail.simpleTags
                }
                if (galleryInfo.tgList == null && cachedDetail.tgList != null) {
                    galleryInfo.tgList = cachedDetail.tgList
                }
                Log.d(TAG, "从缓存补充画廊信息: ${galleryInfo.title} (GID: ${history.gid})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取画廊缓存失败: GID=${history.gid}", e)
        }

        return galleryInfo
    }

    /**
     * 获取成功添加的数量
     */
    fun getTotalAdded(): Int = totalAdded

    /**
     * 获取失败的数量
     */
    fun getTotalFailed(): Int = totalFailed
}
