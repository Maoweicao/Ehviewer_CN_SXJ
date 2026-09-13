package com.hippo.ehviewer.task.impl

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.GalleryFileHelper
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.task.BackgroundTask
import com.hippo.ehviewer.task.RedundancyCleaner
import com.hippo.ehviewer.task.TaskState
import com.hippo.ehviewer.transfer.core.ResponseCache
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 批量删除下载后台任务。
 *
 * 三步流水线（前台已从 DownloadManager 内存列表中移除，UI 即时刷新）：
 *  1. 确认被移除的画廊是否存在于下载历史中，如果不存在补充下载历史表
 *  2. 从数据库中的下载表中移除对应条目
 *  3. 如果勾选了删除图像文件直接删除，否则移动文件夹到回收站中
 *
 * 性能与健壮性设计：
 *  - 阶段一/二使用单事务批量操作（EhDB.ensureDownloadHistoryDeletedBulk /
 *    EhDB.removeDownloadInfoBulk），数据库阶段秒级完成；批量失败时逐条兜底，
 *    单条损坏记录不影响其余条目。
 *  - 阶段三为尽力而为的文件操作：单个目录移动/删除失败只记日志并继续，
 *    不判定任务失败——残留文件可由「清除不在下载记录中的本地画廊文件」兜底清理。
 *  - 使用专用互斥组，仅与其他删除任务串行，不会被卡住的扫描/压缩等长任务阻塞排队。
 *
 * 进度分配：
 *  - 阶段一（写入下载历史）：  0% → 40%
 *  - 阶段二（删除数据库记录）：40% → 60%
 *  - 阶段三（清理本地文件）：  60% → 100%
 */
class DeleteRangeDownloadTask(
    context: Context,
    private val downloadManager: DownloadManager,
    private val infoList: List<DownloadInfo>,
    private val deleteFiles: Boolean,
    private val onCompletedCallback: Runnable? = null
) : BaseBackgroundTask(context) {

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false

    /** 上次进度上报时间，用于节流，避免高频进度更新拖慢任务 */
    private var lastProgressAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun getTaskId(): String {
        // 时间戳 + 自增序号，避免同一毫秒内多次提交导致 taskId 冲突
        val seq = sTaskSeq.incrementAndGet()
        return "delete_range_download_${System.currentTimeMillis()}_$seq"
    }

    /**
     * 专用互斥组：多个删除任务之间仍相互串行（避免并发操作同一下载目录），
     * 但不会与扫描/压缩/合并等共用下载目录互斥组的任务互相排队，
     * 防止某个长时间运行的扫描任务把快速删除任务卡在等待队列里。
     */
    override fun getMutexGroup(): String? = MUTEX_GROUP

    private companion object {
        val sTaskSeq = java.util.concurrent.atomic.AtomicLong(0)

        /** 删除任务专用互斥组 */
        const val MUTEX_GROUP = "task:delete_range_download"

        /** 进度上报最小间隔（毫秒） */
        const val PROGRESS_MIN_INTERVAL_MS = 200L
    }

    override fun getTaskName(): String = context.getString(R.string.download_remove_dialog_title)

    override fun getTaskDescription(): String? = context.getString(R.string.download_remove_dialog_title)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.CLEANUP

    override fun isPausable(): Boolean = true

    override suspend fun execute(): Result<Unit> {
        val total = infoList.size
        if (total <= 0) {
            notifyCompleted()
            onCompletedCallback?.run()
            return Result.success(Unit)
        }

        return try {
            updateState(TaskState.RUNNING)
            appendTaskLog("开始删除 $total 个下载记录")

            // ═══════════════════════════════════════════════════
            // 阶段一：确认下载历史，不存在则补充  (0% → 40%)
            // 单事务批量处理；批量失败时逐条兜底
            // ═══════════════════════════════════════════════════
            appendTaskLog("── 阶段一：检查下载历史 ──")
            updateProgressThrottled(2, "检查下载历史…", force = true)
            var ensured = -1
            try {
                ensured = EhDB.ensureDownloadHistoryDeletedBulk(infoList)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appendTaskLog("批量补充下载历史失败(${e.message})，改为逐条处理")
            }
            if (ensured < 0) {
                ensured = 0
                for ((index, info) in infoList.withIndex()) {
                    checkCancelledAndPaused()
                    try {
                        if (!EhDB.hasDownloadHistory(info.gid)) {
                            EhDB.recordDownloadDeleted(info, 0L)
                            ensured++
                        } else {
                            // 已存在的历史（下载完成时写入的 DELETION_NONE）同样要标记为已删除，
                            // 否则"从下载历史恢复 / 完整性校验"会把已删除的画廊重新加回下载队列
                            EhDB.markDownloadHistoryDeletedIfAlive(info.gid)
                        }
                    } catch (e: Exception) {
                        appendTaskLog("写入下载历史失败 gid=${info.gid}: ${e.message}")
                    }
                    updateProgressThrottled(((index + 1) * 40) / total, "检查下载历史 ${index + 1}/$total")
                }
            }
            appendTaskLog("下载历史就绪：新增 ${ensured} 条，其余已存在（共 $total 条）")
            updateProgressThrottled(40, "下载历史完成", force = true)

            // ═══════════════════════════════════════════════════
            // 阶段二：从数据库下载表中移除对应条目  (40% → 60%)
            // 单事务批量删除；批量失败时逐条兜底
            // ═══════════════════════════════════════════════════
            appendTaskLog("── 阶段二：删除数据库记录 ──")
            val gidList = ArrayList<Long>(total)
            for (info in infoList) {
                gidList.add(info.gid)
            }
            var removed = -1
            try {
                removed = EhDB.removeDownloadInfoBulk(gidList)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appendTaskLog("批量删除数据库记录失败(${e.message})，改为逐条处理")
            }
            if (removed < 0) {
                removed = 0
                for ((index, info) in infoList.withIndex()) {
                    checkCancelledAndPaused()
                    try {
                        EhDB.removeDownloadInfo(info.gid)
                        removed++
                    } catch (e: Exception) {
                        appendTaskLog("删除下载记录失败 gid=${info.gid}: ${e.message}")
                    }
                    updateProgressThrottled(40 + ((index + 1) * 20) / total, "删除数据库记录 ${index + 1}/$total")
                }
            } else {
                updateProgressThrottled(60, "删除数据库记录完成", force = true)
            }
            appendTaskLog("数据库记录处理完成：$removed/$total")

            // 在主线程上通知 DownloadManager 内存同步 + 刷新 UI + 继续下一个下载
            // （前台已 removeFromMemoryRange，这里确保 DB 清理后的最终一致性）
            postToMainThread {
                downloadManager.ensureDownload()
            }

            // ═══════════════════════════════════════════════════
            // 阶段三：清理本地文件  (60% → 100%)
            // 尽力而为：单个文件操作失败只记日志继续执行，
            // 残留文件可由「清除不在下载记录中的本地画廊文件」兜底清理
            // ═══════════════════════════════════════════════════
            appendTaskLog("── 阶段三：清理本地文件 ──")
            try {
                if (deleteFiles) {
                    cleanLocalFilesPermanent(total)
                } else {
                    moveLocalFilesToRecycleBin(total)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 文件操作整体出错不算任务失败，残留文件由冗余清理兜底
                appendTaskLog("清理本地文件出错(${e.message})，可稍后使用" +
                        "\"清除不在下载记录中的本地画廊文件\"兜底清理")
                updateProgressThrottled(100, "本地文件清理未完成", force = true)
            }

            appendTaskLog("删除任务完成，共处理 $total 个画廊")
            // 使 Web API 的画廊/收藏列表缓存失效
            try {
                ResponseCache.getInstance().invalidateGalleries()
            } catch (e: Exception) {
                appendTaskLog("刷新缓存失败: ${e.message}")
            }
            notifyCompleted()
            onCompletedCallback?.run()
            Result.success(Unit)
        } catch (e: CancellationException) {
            appendTaskLog("删除任务已取消")
            notifyCancelled()
            onCompletedCallback?.run()
            Result.failure(e)
        } catch (e: Exception) {
            appendTaskLog("删除任务出错: ${e.message}")
            notifyError(e)
            onCompletedCallback?.run()
            Result.failure(e)
        }
    }

    /**
     * 阶段三（deleteFiles=true）：复用冗余清理逻辑，扫描下载目录永久删除孤儿目录。
     * 注意：前台已将被删画廊从内存列表移除，因此它们的目录会被视为孤儿而删除。
     */
    private suspend fun cleanLocalFilesPermanent(total: Int) {
        appendTaskLog("永久删除本地文件")
        updateProgressThrottled(62, "扫描下载目录…", force = true)
        val summary = RedundancyCleaner.cleanOrphanedGalleries(
            context = context,
            onProgress = { scanned, t, cleaned, _, _ ->
                val phaseProgress = 60 + ((scanned * 40) / (t.coerceAtLeast(1)))
                updateProgressThrottled(phaseProgress, "清理本地文件 $scanned/$t（已清理 $cleaned）")
            },
            onLog = { appendTaskLog(it) },
            isCancelled = { isCancelled },
            isPaused = { isPaused }
        )
        appendTaskLog("本地文件清理完成：扫描 ${summary.scanned}，删除 ${summary.cleaned}，" +
                "保留 ${summary.kept}，跳过 ${summary.ignored}（原 $total 个画廊的目录已不在下载列表中）")
        updateProgressThrottled(100, "本地文件清理完成", force = true)
    }

    /**
     * 阶段三（deleteFiles=false）：把本批次画廊目录逐个移入回收站（.recycle_bin）。
     * 单个目录失败只记日志并继续，不影响其余目录，也不判定任务失败。
     */
    private suspend fun moveLocalFilesToRecycleBin(total: Int) {
        var succeeded = 0
        var failed = 0
        var missing = 0
        for ((index, info) in infoList.withIndex()) {
            checkCancelledAndPaused()
            try {
                val dir = resolveDir(info)
                if (dir == null || !dir.isDirectory()) {
                    missing++
                } else if (GalleryFileHelper.moveToRecycleBin(dir)) {
                    succeeded++
                } else {
                    failed++
                    appendTaskLog("移入回收站失败: ${dir.name}")
                }
            } catch (e: Exception) {
                failed++
                appendTaskLog("移入回收站异常 gid=${info.gid}: ${e.message}")
            }
            updateProgressThrottled(
                60 + ((index + 1) * 40) / total,
                "移入回收站 ${index + 1}/$total",
                force = index == total - 1
            )
        }
        appendTaskLog("移入回收站完成：成功 $succeeded，失败 $failed，无本地目录 $missing" +
                (if (failed > 0 || missing > 0) "（残留可用\"清除不在下载记录中的本地画廊文件\"兜底清理）" else ""))
    }

    private suspend fun checkCancelledAndPaused() {
        if (isCancelled) {
            throw CancellationException("任务已取消")
        }
        coroutineContext[Job]?.ensureActive()
        while (isPaused) {
            delay(100)
        }
    }

    /** 节流版进度上报：高频调用合并为每 [PROGRESS_MIN_INTERVAL_MS] 一次，force 时立即上报 */
    private fun updateProgressThrottled(progress: Int, detail: String?, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastProgressAt >= PROGRESS_MIN_INTERVAL_MS) {
            lastProgressAt = now
            updateProgress(progress, detail)
        }
    }

    private fun resolveDir(info: DownloadInfo?): UniFile? {
        if (info == null) {
            return null
        }
        return try {
            SpiderDen.getExistingGalleryDownloadDir(info)
        } catch (e: Exception) {
            appendTaskLog("解析下载目录失败 gid=${info.gid}: ${e.message}")
            null
        }
    }

    private fun postToMainThread(action: Runnable) {
        mainHandler.post(action)
    }

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

    override fun getProgressDetail(): String? = _progressDetail
}
