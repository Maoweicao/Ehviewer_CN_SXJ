package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.GalleryPageFetcher
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class VerifyDownloadIntegrityTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "VerifyIntegrityTask"
    }

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false
    private var statusListener: StatusListener? = null

    private var totalProgress = 0
    private var verifyCurrent = 0
    private var completeCount = 0
    private var incompleteCount = 0
    private var removedCount = 0

    private fun notifyStatus() {
        statusListener?.onStatus(verifyCurrent, totalProgress, completeCount, incompleteCount, removedCount)
    }

    override fun getTaskId(): String = "verify_integrity"

    override fun getTaskName(): String = context.getString(R.string.settings_download_verify_integrity)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.SCAN

    override fun getTaskDescription(): String? = context.getString(R.string.settings_download_verify_integrity_summary)

    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            Log.d(TAG, "开始校验下载完整性")
            appendTaskLog("开始校验下载完整性")

            coroutineContext[Job]?.ensureActive()

            val downloadManager = EhApplication.getDownloadManager(context)
            val downloadInfoList = downloadManager.allDownloadInfoList

            if (downloadInfoList.isNullOrEmpty()) {
                val msg = "没有已下载的画廊"
                Log.d(TAG, msg)
                appendTaskLog(msg)
                notifyCompleted()
                notifyStatus()
                return Result.success(Unit)
            }

            val finishList = downloadInfoList.filter { it.state == DownloadInfo.STATE_FINISH }
            if (finishList.isEmpty()) {
                val msg = "没有已完成的下载任务"
                Log.d(TAG, msg)
                appendTaskLog(msg)
                notifyCompleted()
                notifyStatus()
                return Result.success(Unit)
            }

            totalProgress = finishList.size
            verifyCurrent = 0
            completeCount = 0
            incompleteCount = 0
            removedCount = 0
            notifyStatus()

            appendTaskLog("总共需要校验 $totalProgress 个画廊")

            for (info in finishList) {
                if (isCancelled) throw CancellationException("任务已取消")
                coroutineContext[Job]?.ensureActive()

                // 等待暂停恢复
                while (isPaused && !isCancelled) {
                    Thread.sleep(200)
                }

                appendTaskLog("正在校验 [${verifyCurrent + 1}/$totalProgress]: ${info.title} (${info.gid})")

                val result = verifySingleGallery(info)
                when (result) {
                    VerifyResult.COMPLETE -> {
                        completeCount++
                        appendTaskLog("✓ 画廊完整: ${info.title}")
                    }
                    VerifyResult.INCOMPLETE -> {
                        incompleteCount++
                        appendTaskLog("✗ 画廊不完整: ${info.title}")
                    }
                    VerifyResult.REMOVED -> {
                        removedCount++
                        appendTaskLog("⚠ 画廊已删除: ${info.title}")
                    }
                }

                verifyCurrent++
                val detail = context.getString(R.string.verify_integrity_progress,
                    verifyCurrent, totalProgress, completeCount, incompleteCount)
                updateProgress(verifyCurrent, totalProgress, detail)
                notifyStatus()
            }

            val msg = context.getString(R.string.verify_integrity_complete, completeCount, incompleteCount)
            Log.i(TAG, msg)
            appendTaskLog(msg)
            appendTaskLog("校验完成: 完整 $completeCount 个, 不完整 $incompleteCount 个, 已删除 $removedCount 个")
            notifyCompleted()
            notifyStatus()
            Result.success(Unit)
        } catch (e: CancellationException) {
            Log.d(TAG, "任务被取消")
            appendTaskLog("任务已取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "校验完整性出错", e)
            appendTaskLog("校验出错: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }

    private fun verifySingleGallery(info: DownloadInfo): VerifyResult {
        val dir = SpiderDen.getExistingGalleryDownloadDir(info)
        if (dir == null || !dir.isDirectory) {
            Log.w(TAG, "画廊目录不存在: ${info.title} (${info.gid})")
            appendTaskLog("画廊目录不存在，检查是否已删除...")
            val removed = checkGalleryRemoved(info)
            if (removed) {
                markAsRemoved(info)
                return VerifyResult.REMOVED
            }
            resetDownloadState(info)
            return VerifyResult.INCOMPLETE
        }

        val spiderInfoFile = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
        if (spiderInfoFile == null) {
            Log.w(TAG, "SpiderInfo 文件不存在: ${info.title} (${info.gid})")
            appendTaskLog("SpiderInfo 文件不存在")
            resetDownloadState(info)
            return VerifyResult.INCOMPLETE
        }

        val spiderInfo = SpiderInfo.read(spiderInfoFile)
        if (spiderInfo == null || spiderInfo.pages <= 0) {
            Log.w(TAG, "SpiderInfo 读取失败或页数无效: ${info.title} (${info.gid})")
            appendTaskLog("SpiderInfo 读取失败或页数无效")
            resetDownloadState(info)
            return VerifyResult.INCOMPLETE
        }

        val expectedPages = spiderInfo.pages
        val corruptedIndices = mutableListOf<Int>()

        for (index in 0 until expectedPages) {
            if (isCancelled) throw CancellationException("任务已取消")

            val imageFile = SpiderDen.findImageFile(dir, index)
            if (imageFile == null) {
                Log.w(TAG, "图片缺失: ${info.title} (${info.gid}), 第 ${index + 1} 页")
                corruptedIndices.add(index)
                continue
            }

            if (!imageFile.isFile) {
                Log.w(TAG, "图片不是文件: ${info.title} (${info.gid}), 第 ${index + 1} 页")
                corruptedIndices.add(index)
                continue
            }

            val fileSize = imageFile.length()
            if (fileSize <= 0) {
                Log.w(TAG, "图片文件大小为0: ${info.title} (${info.gid}), 第 ${index + 1} 页")
                corruptedIndices.add(index)
            }
        }

        if (corruptedIndices.isNotEmpty()) {
            Log.w(TAG, "画廊不完整: ${info.title} (${info.gid}), 期望 $expectedPages 页, 损坏 ${corruptedIndices.size} 页")
            appendTaskLog("期望 $expectedPages 页, 损坏 ${corruptedIndices.size} 页")

            val removed = checkGalleryRemoved(info)
            if (removed) {
                markAsRemoved(info)
                return VerifyResult.REMOVED
            }

            deleteCorruptedImages(dir, corruptedIndices)
            resetDownloadState(info)
            return VerifyResult.INCOMPLETE
        }

        Log.d(TAG, "画廊完整: ${info.title} (${info.gid}), $expectedPages 页")
        return VerifyResult.COMPLETE
    }

    private fun checkGalleryRemoved(info: DownloadInfo): Boolean {
        return try {
            val pages = GalleryPageFetcher.fetchPages(context, info)
            pages == -2
        } catch (e: Exception) {
            Log.w(TAG, "检查画廊是否删除时出错: ${info.gid}", e)
            false
        }
    }

    private fun markAsRemoved(info: DownloadInfo) {
        info.state = DownloadInfo.STATE_FINISH
        info.total = 0
        info.pages = 0
        info.finished = 0
        info.downloaded = 0
        info.legacy = 0
        EhDB.putDownloadInfo(info)
        Log.i(TAG, "画廊已被远端删除，标记为已完成: ${info.title} (${info.gid})")
    }

    private fun deleteCorruptedImages(dir: UniFile, corruptedIndices: List<Int>) {
        for (index in corruptedIndices) {
            val imageFile = SpiderDen.findImageFile(dir, index)
            if (imageFile != null && imageFile.isFile) {
                imageFile.delete()
                Log.d(TAG, "已删除损坏图片: 第 ${index + 1} 页")
            }
        }
    }

    private fun resetDownloadState(info: DownloadInfo) {
        info.state = DownloadInfo.STATE_NONE
        info.finished = 0
        info.downloaded = 0
        EhDB.putDownloadInfo(info)
        Log.d(TAG, "已重置下载状态: ${info.title} (${info.gid})")
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
            "$verifyCurrent/$totalProgress (完整: $completeCount, 不完整: $incompleteCount, 已删除: $removedCount)"
        } else null
    }

    fun setStatusListener(listener: StatusListener?) {
        this.statusListener = listener
    }

    interface StatusListener {
        fun onStatus(current: Int, total: Int, complete: Int, incomplete: Int, removed: Int)
    }

    private enum class VerifyResult {
        COMPLETE,
        INCOMPLETE,
        REMOVED
    }
}
