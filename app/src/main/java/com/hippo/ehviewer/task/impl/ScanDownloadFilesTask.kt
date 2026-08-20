package com.hippo.ehviewer.task.impl

import android.content.Context
import com.hippo.ehviewer.R
import com.hippo.ehviewer.DownloadedFileManager
import com.hippo.ehviewer.DownloadedFileManagerScanListener
import com.hippo.ehviewer.task.BackgroundTask
import com.hippo.ehviewer.task.TaskState
import com.hippo.ehviewer.task.BackgroundTask.TaskType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 扫描下载文件任务
 */
class ScanDownloadFilesTask(context: Context) : BaseBackgroundTask(context) {
    
    @Volatile
    private var isPausedFlag = false
    
    override fun getTaskId(): String = "scan_download_files"
    
    override fun getTaskName(): String = context.getString(R.string.settings_download_scan_download_files)
    
    override fun getTaskDescription(): String? = context.getString(R.string.settings_download_scan_download_files_summary)
    
    override fun getTaskType(): TaskType = TaskType.SCAN
    
    override fun isPausable(): Boolean = true
    
    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            updateProgress(0, context.getString(R.string.scan_download_files_initializing))
            
            val manager = DownloadedFileManager.getInstance()
            var totalFiles = 0
            var processedFiles = 0
            
            // 首先获取总文件数
            updateProgress(0, context.getString(R.string.scan_download_files_counting))
            delay(500) // 给用户一些反馈时间
            
            // 使用 CountDownLatch 等待异步扫描完成，保证 execute() 返回时扫描结果已确定，
            // 异常才能正确上抛给运行任务的代码统一处理
            val scanDone = java.util.concurrent.CountDownLatch(1)
            val scanError = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
            
            // 创建进度监听器
            val scanListener = object : DownloadedFileManagerScanListener {
                override fun onProgress(current: Int, total: Int) {
                    processedFiles = current
                    totalFiles = total
                    val progress = if (total > 0) (current * 100 / total) else -1
                    val detail = context.getString(R.string.scan_download_files_progress, current, total)
                    updateProgress(progress, detail)
                    // 检查暂停状态
                    runBlocking {
                        checkPauseState()
                    }
                }
                
                override fun onCompleted() {
                    // 进度与完成通知统一由 execute() 在等待结束后处理，这里只需放行等待
                    scanDone.countDown()
                }
                
                override fun onError(e: Exception) {
                    scanError.set(e)
                    scanDone.countDown()
                }
            }
            
            // 执行扫描
            manager.scanDownloadDirectories(scanListener)

            // 等待扫描完成。并发扫描会通过 onError 立即返回，
            // 此处仅在回调异常未触发时兜底等待 60 秒后按失败处理，避免任务卡死。
            val completed = scanDone.await(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                val e = IllegalStateException("扫描超时，请稍后重试")
                notifyError(e)
                return Result.failure(e)
            }

            scanError.get()?.let { e ->
                notifyError(e)
                return Result.failure(e)
            }
            
            updateProgress(100, context.getString(R.string.scan_download_files_completed))
            notifyCompleted()
            Result.success(Unit)
            
        } catch (e: Exception) {
            notifyError(e)
            Result.failure(e)
        }
    }
    
    override suspend fun pause() {
        if (!isPausable()) {
            throw UnsupportedOperationException("Task does not support pause")
        }
        isPausedFlag = true
        updateState(TaskState.PAUSED)
        appendTaskLog("任务已暂停")
    }
    
    override suspend fun resume() {
        if (!isPausable()) {
            throw UnsupportedOperationException("Task does not support resume")
        }
        isPausedFlag = false
        updateState(TaskState.RUNNING)
        appendTaskLog("任务已恢复")
    }
    
    /**
     * 检查暂停状态，如果暂停则等待
     * 注意：由于扫描是异步的，暂停效果会在下次进度回调时生效
     */
    private suspend fun checkPauseState() {
        while (isPausedFlag) {
            delay(200)
        }
    }
}