package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 修复分类未知的下载画廊任务
 * 批量修复所有分类显示为未知的已下载画廊的元数据信息
 */
class RepairUnknownCategoryGalleryTask(
    context: Context
) : BaseBackgroundTask(context) {
    
    companion object {
        private const val TAG = "RepairUnknownCategoryTask"
        private const val CONCURRENT_REQUESTS = 20 // 并发请求数量
    }
    
    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false
    private var statusListener: StatusListener? = null
    
    private var totalProgress = 0
    private var successCount = 0
    private var failedCount = 0

    private fun notifyStatus() {
        statusListener?.onStatus(currentProgress, totalProgress, successCount, failedCount)
    }
    
    override fun getTaskId(): String = "repair_unknown_category"
    
    override fun getTaskName(): String = context.getString(R.string.settings_download_repair_unknown_category_gallery)
    
    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.UPDATE
    
    override fun getTaskDescription(): String? = context.getString(R.string.settings_download_repair_unknown_category_gallery_summary)
    
    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            Log.d(TAG, "开始修复分类未知的下载画廊信息")
            appendTaskLog("开始修复分类未知的下载画廊信息")
            
            // 检查是否已取消
            coroutineContext[Job]?.ensureActive()
            
            val downloadManager = EhApplication.getDownloadManager(context)
            val downloadInfoList = downloadManager.allDownloadInfoList
            
            if (downloadInfoList.isNullOrEmpty()) {
                Log.d(TAG, "没有需要修复的下载画廊")
                appendTaskLog("没有需要修复的下载画廊")
                notifyCompleted()
                notifyStatus()
                return Result.success(Unit)
            }
            
            // 筛选出分类为未知的画廊
            val unknownCategoryGalleries = downloadInfoList.filter { it.category == EhUtils.UNKNOWN }
            
            if (unknownCategoryGalleries.isEmpty()) {
                Log.d(TAG, "没有分类为未知的下载画廊")
                appendTaskLog("没有分类为未知的下载画廊")
                notifyCompleted()
                notifyStatus()
                return Result.success(Unit)
            }
            
            totalProgress = unknownCategoryGalleries.size
            successCount = 0
            failedCount = 0
            notifyStatus()
            
            Log.d(TAG, "总共需要修复 $totalProgress 个分类未知的画廊")
            appendTaskLog("总共需要修复 $totalProgress 个分类未知的画廊")
            
            // 分批处理，每批CONCURRENT_REQUESTS个
            val batches = unknownCategoryGalleries.chunked(CONCURRENT_REQUESTS)
            
            for ((batchIndex, batch) in batches.withIndex()) {
                // 检查取消状态
                if (isCancelled) {
                    throw CancellationException("任务已取消")
                }
                
                // 检查暂停状态
                while (isPaused && !isCancelled) {
                    Thread.sleep(100)
                }
                
                appendTaskLog("开始处理第 ${batchIndex + 1}/${batches.size} 批 (${batch.size} 个画廊)")
                
                // 并发处理当前批次
                val batchResults = coroutineScope {
                    batch.map { info ->
                        async {
                            repairSingleGallery(info, downloadManager)
                        }
                    }.awaitAll()
                }
                
                // 统计结果
                batchResults.forEach { result ->
                    currentProgress++
                    if (result) {
                        successCount++
                    } else {
                        failedCount++
                    }
                }
                
                val progressPercent = if (totalProgress > 0) currentProgress * 100 / totalProgress else 0
                val progressDetail = "修复中 $currentProgress/$totalProgress (成功: $successCount, 失败: $failedCount)"
                
                updateProgress(currentProgress, totalProgress, progressDetail)
                notifyStatus()
                
                // 检查是否已取消
                coroutineContext[Job]?.ensureActive()
                
                // 批次间稍作延迟，避免请求过于频繁
                if (batchIndex < batches.size - 1) {
                    Thread.sleep(500)
                }
            }
            
            val finalResult = "修复完成: 成功 $successCount 个, 失败 $failedCount 个"
            Log.i(TAG, finalResult)
            appendTaskLog(finalResult)
            notifyCompleted()
            
            if (failedCount == 0) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("部分画廊修复失败: 成功 $successCount, 失败 $failedCount"))
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "修复任务被取消")
            appendTaskLog("修复任务被取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "修复任务出错", e)
            appendTaskLog("修复任务出错: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }
    
    private suspend fun repairSingleGallery(info: DownloadInfo, downloadManager: DownloadManager): Boolean {
        return try {
            Log.d(TAG, "修复画廊: ${info.title} (${info.gid})")
            appendTaskLog("开始修复: ${info.title} (${info.gid})")
            
            val success = downloadManager.repairGalleryInfo(info.gid)
            if (success) {
                Log.d(TAG, "修复成功: ${info.title}")
                appendTaskLog("修复成功: ${info.title}")
            } else {
                Log.w(TAG, "修复失败: ${info.title}")
                appendTaskLog("修复失败: ${info.title}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "修复画廊时出错: ${info.title}", e)
            appendTaskLog("修复画廊时出错: ${info.title} - ${e.message}")
            false
        }
    }
    
    override fun isPausable(): Boolean = true
    
    override suspend fun pause() {
        Log.d(TAG, "暂停修复任务")
        isPaused = true
        updateState(TaskState.PAUSED)
        appendTaskLog("任务已暂停")
    }
    
    override suspend fun resume() {
        Log.d(TAG, "恢复修复任务")
        isPaused = false
        updateState(TaskState.RUNNING)
        appendTaskLog("任务已恢复")
    }
    
    override suspend fun cancel() {
        Log.d(TAG, "取消修复任务")
        isCancelled = true
        isPaused = false
        notifyCancelled()
    }

    override fun getProgressDetail(): String? {
        return if (totalProgress > 0) {
            "$currentProgress/$totalProgress (成功: $successCount, 失败: $failedCount)"
        } else {
            null
        }
    }

    fun setStatusListener(listener: StatusListener?) {
        statusListener = listener
    }

    interface StatusListener {
        fun onStatus(current: Int, total: Int, success: Int, failed: Int)
    }
    
    /**
     * 阻塞执行任务（供 Java 代码调用）
     */
    fun executeBlocking(): Result<Unit> {
        return kotlinx.coroutines.runBlocking {
            execute()
        }
    }

    /**
     * 阻塞执行并在失败时抛出异常（便于 Java 调用方处理错误）
     */
    fun executeBlockingOrThrow() {
        kotlinx.coroutines.runBlocking {
            execute().getOrThrow()
        }
    }
    
    /**
     * 获取修复结果统计
     */
    fun getRepairResult(): RepairResult {
        return RepairResult(successCount, failedCount)
    }
    
    data class RepairResult(
        val successCount: Int,
        val failedCount: Int
    )
}
