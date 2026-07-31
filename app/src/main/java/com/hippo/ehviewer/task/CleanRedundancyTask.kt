package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import com.hippo.lib.yorozuya.NumberUtils
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext

/**
 * 清理冗余文件任务
 * 扫描下载目录，删除不在下载记录中的文件
 */
class CleanRedundancyTask(
    context: Context
) : BaseBackgroundTask(context) {
    
    companion object {
        private const val TAG = "CleanRedundancyTask"
    }
    
    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false
    private var statusListener: StatusListener? = null
    
    private var totalProgress = 0
    private var cleanedCount = 0

    private fun notifyStatus() {
        statusListener?.onStatus(currentProgress, totalProgress, cleanedCount)
    }
    
    override fun getTaskId(): String = "clean_redundancy"
    
    override fun getTaskName(): String = context.getString(R.string.settings_download_clean_redundancy)
    
    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.CLEANUP
    
    override fun getTaskDescription(): String? = context.getString(R.string.settings_download_clean_redundancy_summary)
    
    override suspend fun execute(): Result<Unit> {
        return try {
            updateState(TaskState.RUNNING)
            Log.d(TAG, "开始清理冗余文件")
            appendTaskLog("开始清理冗余文件")
            
            // 检查是否已取消
            coroutineContext[Job]?.ensureActive()
            
            val downloadManager = EhApplication.getDownloadManager(context)
            val downloadDir = Settings.getDownloadLocation()
            
            if (downloadDir == null || !downloadDir.isDirectory()) {
                Log.w(TAG, "下载目录不存在")
                appendTaskLog("下载目录不存在")
                notifyError(Exception("下载目录不存在"))
                return Result.failure(Exception("下载目录不存在"))
            }
            
            val files = downloadDir.listFiles()
            if (files == null) {
                Log.w(TAG, "无法列出下载目录文件")
                appendTaskLog("无法列出下载目录文件")
                notifyError(Exception("无法列出下载目录文件"))
                return Result.failure(Exception("无法列出下载目录文件"))
            }
            
            totalProgress = files.size
            cleanedCount = 0
            notifyStatus()
            
            Log.d(TAG, "共需处理 $totalProgress 个文件")
            appendTaskLog("共需处理 $totalProgress 个文件")
            
            for (file in files) {
                // 检查取消状态
                if (isCancelled) {
                    throw CancellationException("任务已取消")
                }
                
                // 检查暂停状态
                while (isPaused && !isCancelled) {
                    Thread.sleep(100)
                }
                
                // 处理文件
                if (clearFile(file, downloadManager)) {
                    cleanedCount++
                    Log.d(TAG, "清理文件: ${file.name}")
                    appendTaskLog("清理文件: ${file.name}")
                }
                
                currentProgress++
                updateProgress(currentProgress, totalProgress, "$currentProgress/$totalProgress")
                notifyStatus()
            }
            
            Log.d(TAG, "清理完成，共清理 $cleanedCount 个文件")
            appendTaskLog("清理完成，共清理 $cleanedCount 个文件")
            notifyCompleted()
            Result.success(Unit)
            
        } catch (e: CancellationException) {
            Log.d(TAG, "清理任务被取消")
            appendTaskLog("清理任务被取消")
            notifyCancelled()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "清理任务出错", e)
            appendTaskLog("清理任务出错: ${e.message}")
            notifyError(e)
            Result.failure(e)
        }
    }
    
    /**
     * 清理文件
     * @return true 表示文件被清理
     */
    private fun clearFile(file: UniFile, downloadManager: DownloadManager): Boolean {
        var name = file.name ?: return false
        
        val index = name.indexOf('-')
        if (index >= 0) {
            name = name.substring(0, index)
        }
        
        val gid = NumberUtils.parseLongSafely(name, -1)
        if (gid == -1L) {
            return false
        }
        
        // 检查是否在下载列表中
        val info = downloadManager.getDownloadInfo(gid)
        if (info == null) {
            // 不在下载列表中，删除
            return file.delete()
        }
        
        return false
    }
    
    override fun isPausable(): Boolean = true
    
    override suspend fun pause() {
        Log.d(TAG, "暂停清理任务")
        isPaused = true
        updateState(TaskState.PAUSED)
        appendTaskLog("任务已暂停")
    }
    
    override suspend fun resume() {
        Log.d(TAG, "恢复清理任务")
        isPaused = false
        updateState(TaskState.RUNNING)
        appendTaskLog("任务已恢复")
    }
    
    override suspend fun cancel() {
        Log.d(TAG, "取消清理任务")
        isCancelled = true
        isPaused = false
        notifyCancelled()
    }

    override fun getProgressDetail(): String? {
        return if (totalProgress > 0) {
            "$currentProgress/$totalProgress (清理: $cleanedCount)"
        } else {
            null
        }
    }

    fun setStatusListener(listener: StatusListener?) {
        statusListener = listener
    }

    interface StatusListener {
        fun onStatus(current: Int, total: Int, cleaned: Int)
    }
    
    /**
     * 同步执行任务（用于Java调用）
     */
    fun executeSync() {
        runBlocking {
            execute()
        }
    }
    
    /**
     * 获取清理的文件数量
     */
    fun getCleanedCount(): Int = cleanedCount
}
