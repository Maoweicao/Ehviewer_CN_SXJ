package com.hippo.ehviewer.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.dao.SystemDownloadTask
import com.hippo.ehviewer.network.NetworkLogger

/**
 * 监听系统 DownloadManager 的 ACTION_DOWNLOAD_COMPLETE 广播，
 * 找到对应的 SystemDownloadTask 行（通过 downloadId），把单张图
 * 的"完成事件"丢给 SystemDownloadFinalizeWorker 做后续处理
 * （嗅探 MIME → MOVE 到 SpiderDen → 删系统 DM 临时文件）。
 *
 * 应用启动时的"漏单"通过 resumePendingDownloads() 主动 query 兜底。
 */
class SystemDownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE != intent.action) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return

        val pendingResult = goAsync()
        Thread {
            try {
                val task = EhDB.getSystemDownloadTask(downloadId) ?: return@Thread
                enqueueFinalize(context.applicationContext, task)
            } catch (e: Exception) {
                Log.e(TAG, "handle completion broadcast failed for $downloadId", e)
                log(taskGid = null, "broadcast id=$downloadId handling failed: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "SystemDownloadCompleteReceiver"

        @Volatile
        private var registered = false

        @JvmStatic
        fun ensureRegisteredAndResume(context: Context) {
            if (!registered) {
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    SystemDownloadCompleteReceiver(),
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE).apply {},
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                registered = true
                Log.i(TAG, "Registered ACTION_DOWNLOAD_COMPLETE receiver")
            }
            Thread { resumePendingDownloads(context.applicationContext) }.start()
        }

        /**
         * 应用启动后兜底：所有还活着的 PENDING/RUNNING 任务，
         * 到系统 DM 里 query 一遍状态。成功任务交给 FinalizeWorker；失败、暂停
         * 或系统记录消失的任务会被标为 FAILED，避免下载列表永久显示“正在下载”。
         */
        private fun resumePendingDownloads(context: Context) {
            val tasks = EhDB.getActiveSystemDownloadTasks() ?: return
            if (tasks.isEmpty()) return
            Log.i(TAG, "resumePendingDownloads: ${tasks.size} active tasks to check")

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val tasksById = tasks.associateBy { it.downloadId }
            val ids = tasksById.keys.toLongArray()
            val seenIds = mutableSetOf<Long>()
            val query = DownloadManager.Query().setFilterById(*ids)
            val cursor: Cursor? = try {
                dm.query(query)
            } catch (e: Exception) {
                Log.e(TAG, "dm.query failed during resume", e)
                null
            }

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getLong(idIdx) else -1L
                    val status = if (statusIdx >= 0) c.getInt(statusIdx) else -1
                    val task = tasksById[id] ?: continue
                    seenIds += id
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> enqueueFinalize(context, task)
                        DownloadManager.STATUS_FAILED, DownloadManager.STATUS_PAUSED -> markFailed(
                            task,
                            "system status=${statusName(status)}, reason=${reason(c)}"
                        )
                        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> {
                            if (task.status != SystemDownloadTask.STATUS_RUNNING) {
                                task.status = SystemDownloadTask.STATUS_RUNNING
                                EhDB.putSystemDownloadTask(task)
                            }
                        }
                    }
                }
            }
            for ((id, task) in tasksById) {
                if (id !in seenIds) markFailed(task, "system download record missing")
            }
        }

        private fun enqueueFinalize(context: Context, task: SystemDownloadTask) {
            if (task.status == SystemDownloadTask.STATUS_SUCCESS) return
            task.status = SystemDownloadTask.STATUS_RUNNING
            EhDB.putSystemDownloadTask(task)
            val workRequest = OneTimeWorkRequestBuilder<SystemDownloadFinalizeWorker>()
                .setInputData(SystemDownloadFinalizeWorker.buildInputData(task.downloadId, task.gid, task.pageIndex))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "system-download-finalize-${task.downloadId}",
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            log(task.gid, "finalize queued: id=${task.downloadId}, page=${task.pageIndex}")
        }

        private fun markFailed(task: SystemDownloadTask, detail: String) {
            task.status = SystemDownloadTask.STATUS_FAILED
            task.retryCount += 1
            EhDB.putSystemDownloadTask(task)
            log(task.gid, "system task failed: id=${task.downloadId}, page=${task.pageIndex}, $detail")
            try {
                com.hippo.ehviewer.EhApplication.getDownloadManager().onSystemDMGalleryFailed(task.gid)
            } catch (e: Exception) {
                Log.w(TAG, "failed to update gallery state for gid=${task.gid}", e)
            }
        }

        private fun reason(cursor: Cursor): Int {
            val index = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
            return if (index >= 0) cursor.getInt(index) else 0
        }

        private fun statusName(status: Int) = when (status) {
            DownloadManager.STATUS_FAILED -> "FAILED"
            DownloadManager.STATUS_PAUSED -> "PAUSED"
            else -> status.toString()
        }

        private fun log(taskGid: Long?, message: String) {
            Log.i(TAG, message)
            NetworkLogger.logDownload("SystemDM: $message")
            try {
                DownloadLogger.getInstance().log(
                    DownloadLogger.LogLevel.INFO,
                    TAG,
                    message,
                    taskGid?.toString(),
                    null
                )
            } catch (_: IllegalStateException) {
            }
        }
    }
}
