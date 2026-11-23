package com.hippo.ehviewer.task

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadHistory
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.GalleryPageFetcher
import com.hippo.ehviewer.spider.GalleryIntegrityChecker
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class VerifyDownloadIntegrityTask(
    context: Context
) : BaseBackgroundTask(context) {

    companion object {
        private const val TAG = "VerifyIntegrityTask"

        /** 每校验多少个画廊输出一行性能采样日志。 */
        private const val PERF_SAMPLE_SIZE = 50L
    }

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false

    private var totalProgress = 0
    private var verifyCurrent = 0
    private var completeCount = 0
    private var incompleteCount = 0
    private var removedCount = 0

    // 单画廊耗时（供性能采样使用）
    private var lastScanMs = 0L
    private var lastPages = 0
    private var lastNetMs = 0L

    // 性能采样累计值，每 PERF_SAMPLE_SIZE 个画廊输出一行
    private var sampleGalleries = 0L
    private var samplePages = 0L
    private var sampleScanMs = 0L
    private var sampleNetMs = 0L
    private var sampleNetCalls = 0L


    override fun getTaskId(): String = "verify_integrity"

    override fun getTaskName(): String = context.getString(R.string.settings_download_verify_integrity)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.SCAN

    override fun getTaskDescription(): String? = context.getString(R.string.settings_download_verify_integrity_summary)

    override suspend fun execute(): Result<Unit> {
        return try {
            val taskStartMs = SystemClock.elapsedRealtime()
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
                return Result.success(Unit)
            }

            val finishList = downloadInfoList.filter { it.state == DownloadInfo.STATE_FINISH }
            if (finishList.isEmpty()) {
                val msg = "没有已完成的下载任务"
                Log.d(TAG, msg)
                appendTaskLog(msg)
                notifyCompleted()
                return Result.success(Unit)
            }

            // 只检测未被标记为已删除/重复/递进合并的画廊
            val verifiableList = finishList.filter { !isMarkedDeletedOrMerged(it.gid) }
            if (verifiableList.isEmpty()) {
                val msg = "没有需要校验的画廊（剩余均已标记为已删除或递进关系）"
                Log.d(TAG, msg)
                appendTaskLog(msg)
                notifyCompleted()
                return Result.success(Unit)
            }

            totalProgress = verifiableList.size
            verifyCurrent = 0
            completeCount = 0
            incompleteCount = 0
            removedCount = 0
            sampleGalleries = 0L
            samplePages = 0L
            sampleScanMs = 0L
            sampleNetMs = 0L
            sampleNetCalls = 0L

            Log.i(
                TAG,
                "开始校验: 共 $totalProgress 个画廊, 下载位置类型=" +
                    (com.hippo.ehviewer.Settings.getDownloadLocation()?.javaClass?.simpleName ?: "null")
            )

            appendTaskLog("总共需要校验 $totalProgress 个画廊（跳过 ${finishList.size - verifiableList.size} 个已删除/递进关系画廊）")

            for (info in verifiableList) {
                if (isCancelled) throw CancellationException("任务已取消")
                coroutineContext[Job]?.ensureActive()

                // 等待暂停恢复
                while (isPaused && !isCancelled) {
                    Thread.sleep(200)
                }

                // 全库校验一万多个画廊时，逐条写日志本身也是可观开销：
                // 进度日志每 50 个记一条，完整的画廊只计数，不完整/已删除的仍然逐个记录。
                if (verifyCurrent % 50 == 0) {
                    appendTaskLog("正在校验 [${verifyCurrent + 1}/$totalProgress]: ${info.title} (${info.gid})")
                }

                val result = verifySingleGallery(info)
                recordPerfSample()
                when (result) {
                    VerifyResult.COMPLETE -> {
                        completeCount++
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
            }

            val msg = context.getString(R.string.verify_integrity_complete, completeCount, incompleteCount)
            Log.i(TAG, msg)
            Log.i(
                TAG,
                "校验耗时: ${SystemClock.elapsedRealtime() - taskStartMs}ms, 画廊=$totalProgress," +
                    " 平均扫描=${if (totalProgress > 0) sampleScanMs / totalProgress else 0}ms," +
                    " 平均网络=${if (totalProgress > 0) sampleNetMs / totalProgress else 0}ms," +
                    " 网络请求次数=$sampleNetCalls"
            )
            appendTaskLog(msg)
            appendTaskLog("校验完成: 完整 $completeCount 个, 不完整 $incompleteCount 个, 已删除 $removedCount 个")
            notifyCompleted()
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
        lastScanMs = 0L
        lastPages = 0
        lastNetMs = 0L

        val dir = SpiderDen.getExistingGalleryDownloadDir(info)
        if (dir == null || !dir.isDirectory) {
            Log.w(TAG, "画廊目录不存在: ${info.title} (${info.gid})")
            appendTaskLog("画廊目录不存在，检查是否已删除...")
            val removed = timeNetwork { checkGalleryRemoved(info) }
            if (removed) {
                markAsRemoved(info)
                return VerifyResult.REMOVED
            }
            resetDownloadState(info)
            return VerifyResult.INCOMPLETE
        }

        // 与内置引擎、系统委托下载共用同一完整性判定。
        // 目录已经解析过，直接交给检查器，避免再解析一次（每次解析都读写一次目录名）。
        val scanStart = SystemClock.elapsedRealtime()
        val integrity = GalleryIntegrityChecker.checkIn(dir, info)
        lastScanMs = SystemClock.elapsedRealtime() - scanStart
        lastPages = integrity.expectedPages

        if (integrity.spiderInfoMissing) {
            Log.w(TAG, "SpiderInfo 文件不存在或页数无效: ${info.title} (${info.gid})")
            appendTaskLog("SpiderInfo 文件不存在或页数无效")
            resetDownloadState(info)
            return VerifyResult.INCOMPLETE
        }

        if (integrity.isComplete) {
            Log.d(TAG, "画廊完整: ${info.title} (${info.gid}), ${integrity.expectedPages} 页")
            return VerifyResult.COMPLETE
        }

        Log.w(
            TAG,
            "画廊不完整: ${info.title} (${info.gid}), 期望 ${integrity.expectedPages} 页," +
                " 损坏 ${integrity.invalidIndices.size} 页"
        )
        appendTaskLog("期望 ${integrity.expectedPages} 页, 损坏 ${integrity.invalidIndices.size} 页")

        val removed = timeNetwork { checkGalleryRemoved(info) }
        if (removed) {
            markAsRemoved(info)
            return VerifyResult.REMOVED
        }

        GalleryIntegrityChecker.deleteInvalidFiles(dir, integrity)
        resetDownloadState(info)
        return VerifyResult.INCOMPLETE
    }

    private inline fun <T> timeNetwork(block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            lastNetMs = SystemClock.elapsedRealtime() - start
        }
    }

    /**
     * 每 [PERF_SAMPLE_SIZE] 个画廊汇总一行性能日志，用来区分耗时到底在扫描还是网络。
     */
    private fun recordPerfSample() {
        sampleGalleries++
        samplePages += lastPages
        sampleScanMs += lastScanMs
        sampleNetMs += lastNetMs
        if (lastNetMs > 0L) sampleNetCalls++
        if (sampleGalleries % PERF_SAMPLE_SIZE != 0L) return
        Log.i(
            TAG,
            "性能采样: 画廊=$sampleGalleries 平均页数=${samplePages / sampleGalleries}" +
                " 平均扫描=${sampleScanMs / sampleGalleries}ms" +
                " 平均网络=${sampleNetMs / sampleGalleries}ms" +
                " 网络请求次数=$sampleNetCalls"
        )
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

    /**
     * 判断画廊是否已在下载历史中被标记为已删除或递进/重复合并。
     * 这些画廊不应参与不完整画廊检测。
     */
    private fun isMarkedDeletedOrMerged(gid: Long): Boolean {
        val history = EhDB.getDownloadHistory(gid) ?: return false
        return history.deletionType != DownloadHistory.DELETION_NONE
    }

    private fun markAsRemoved(info: DownloadInfo) {
        info.state = DownloadInfo.STATE_FINISH
        info.total = 0
        info.pages = 0
        info.finished = 0
        info.downloaded = 0
        info.legacy = 0
        EhDB.putDownloadInfo(info)
        // 在下载历史中标记为已删除，避免后续重复校验
        EhDB.recordDownloadAsDuplicate(info, DownloadHistory.DELETION_NORMAL, 0L)
        Log.i(TAG, "画廊已被远端删除，标记为已完成: ${info.title} (${info.gid})")
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



    private enum class VerifyResult {
        COMPLETE,
        INCOMPLETE,
        REMOVED
    }
}
