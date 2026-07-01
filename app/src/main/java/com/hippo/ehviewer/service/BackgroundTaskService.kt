/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hippo.ehviewer.BackgroundTaskManager
import com.hippo.ehviewer.R
import com.hippo.lib.yorozuya.SimpleHandler

/**
 * 后台任务前台服务 - 防止锁屏/休眠中断后台任务（压缩、合并、扫描等）
 *
 * 在 BackgroundTaskManager 有活跃任务时启动，任务全部完成后自动停止。
 * 持有 PARTIAL_WAKE_LOCK 防止 CPU 休眠，每 5 分钟刷新一次。
 *
 * 设计参考 DownloadService 的 WakeLock 模式，但不包含网络相关锁（WifiLock），
 * 因为后台任务主要是 CPU/IO 密集型。
 */
class BackgroundTaskService : Service() {

    companion object {
        private const val TAG = "BackgroundTaskService"
        private const val CHANNEL_ID = "eh_background_tasks"
        private const val NOTIFICATION_ID = 1001
        private const val LOCK_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        const val EXTRA_ACTIVE_TASK_COUNT = "active_task_count"
        const val EXTRA_TASK_NAME = "task_name"

        @JvmStatic
        fun start(context: Context, taskName: String?, activeCount: Int) {
            val intent = Intent(context, BackgroundTaskService::class.java).apply {
                putExtra(EXTRA_ACTIVE_TASK_COUNT, activeCount)
                putExtra(EXTRA_TASK_NAME, taskName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundTaskService::class.java))
        }
    }

    private var mWakeLock: PowerManager.WakeLock? = null
    private var mLockRefreshActive = false
    private val mLockRefreshRunnable = Runnable { refreshLock() }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()

        // Ensure notification channel exists (same channel as BackgroundTaskManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.background_tasks_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.background_tasks_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        // Call startForeground immediately in onCreate to avoid
        // ForegroundServiceDidNotStartInTimeException
        startForeground(NOTIFICATION_ID, buildNotification(0, null))
        Log.d(TAG, "startForeground called in onCreate")

        // Initialize WakeLock
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            mWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EhViewer:BackgroundTaskWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
            Log.i(TAG, "WakeLock initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WakeLock", e)
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Acquire WakeLock to prevent CPU sleep
        acquireWakeLock()

        // Start lock refresh timer
        startLockRefresh()

        // Update foreground notification with actual task info
        val activeCount = intent?.getIntExtra(EXTRA_ACTIVE_TASK_COUNT, 0) ?: 0
        val taskName = intent?.getStringExtra(EXTRA_TASK_NAME)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(activeCount, taskName))

        Log.d(TAG, "Service started, activeTasks=$activeCount, taskName=$taskName")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLockRefresh()
        releaseWakeLock()
        Log.i(TAG, "Service destroyed, locks released")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User removed app from recents - clean up everything
        try {
            BackgroundTaskManager.getInstance().forceStopAllTasks()
        } catch (_: Exception) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopLockRefresh()
        releaseWakeLock()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // ==================== Notification ====================

    private fun buildNotification(activeCount: Int, taskName: String?): android.app.Notification {
        val title = if (activeCount > 1) {
            resources.getQuantityString(R.plurals.background_tasks_running, activeCount, activeCount)
        } else {
            getString(R.string.background_task_running)
        }

        val contentText = taskName ?: getString(R.string.background_task_running_description)

        val intent = Intent(this, com.hippo.ehviewer.ui.task.BackgroundTaskActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    // ==================== WakeLock Management ====================

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            if (mWakeLock == null) return

            // Release first if already held (refresh strategy)
            if (mWakeLock!!.isHeld) {
                mWakeLock!!.release()
                Log.d(TAG, "WakeLock released for refresh")
            }

            mWakeLock!!.acquire()
            Log.i(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (mWakeLock != null && mWakeLock!!.isHeld) {
                mWakeLock!!.release()
                Log.i(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }

    // ==================== Lock Refresh (every 5 min) ====================

    private fun startLockRefresh() {
        if (mLockRefreshActive) return
        mLockRefreshActive = true
        SimpleHandler.getInstance().postDelayed(mLockRefreshRunnable, LOCK_REFRESH_INTERVAL_MS)
        Log.d(TAG, "Lock refresh started (interval=5min)")
    }

    private fun stopLockRefresh() {
        mLockRefreshActive = false
        SimpleHandler.getInstance().removeCallbacks(mLockRefreshRunnable)
        Log.d(TAG, "Lock refresh stopped")
    }

    private fun refreshLock() {
        if (!mLockRefreshActive) return

        acquireWakeLock()
        Log.d(TAG, "Lock refreshed, next refresh in 5min")

        if (mLockRefreshActive) {
            SimpleHandler.getInstance().postDelayed(mLockRefreshRunnable, LOCK_REFRESH_INTERVAL_MS)
        }
    }
}
