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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
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

        // stop() 合并延迟：任务全部结束后延迟一段时间再真正停服务。
        // 任务快速启停时（上一任务刚结束、下一任务立即开始），频繁销毁/重建
        // 前台服务会让系统反复打开 startForegroundService -> startForeground
        // 的超时窗口，是 ForegroundServiceDidNotStartInTimeException 的重要诱因。
        private const val STOP_DELAY_MS = 2000L

        const val EXTRA_ACTIVE_TASK_COUNT = "active_task_count"
        const val EXTRA_TASK_NAME = "task_name"

        // 用于串行化 start/stop 竞态，以及去重排队中的启动投递。
        // BackgroundTaskManager 从工作线程调用 start()/stop()，若不做保护，
        // 快速的任务启停会在主线程消息队列中堆积多个 doStart，或与 stop 交错，
        // 导致系统要求的 startForeground() 未在超时窗口内被调用而崩溃。
        private val lock = Any()
        private var startScheduled = false

        // 主线程上待执行的延迟停止任务；新的 start() 会取消它。
        private var pendingStop: Runnable? = null

        // 服务当前是否已处于运行（前台）状态。以 Service 生命周期为准：
        // onCreate 置 true，onDestroy 置 false。
        //
        // 系统对每一次 startForegroundService() 调用都要求随后在超时窗口内
        // （主线程能跑起来的前提下）完成 startForeground()，计时按墙钟走。
        // 大规模任务场景下每秒都有任务启停，若每次都走 startForegroundService，
        // 倒计时窗口会被反复打开；一旦主线程被进度消息淹没，onStartCommand
        // 排队超时即崩溃（本应用历史上最频发的 ForegroundServiceDidNotStartInTimeException）。
        // 因此：服务已运行时一律用普通 startService() 更新（不开新窗口，
        // 且持有前台服务的进程对 startService 没有后台启动限制），
        // 只在服务真正不运行时才允许 startForegroundService()。
        @Volatile
        private var sServiceRunning = false

        @JvmStatic
        fun start(context: Context, taskName: String?, activeCount: Int): Boolean {
            // startForegroundService 必须在主线程调用，且系统要求在超时窗口内
            // 调用 startForeground()。若从后台线程调用，服务创建与 startForeground()
            // 会与主线程繁忙产生竞争，导致 ForegroundServiceDidNotStartInTimeException。
            // 因此统一投递到主线程执行，确保 startForegroundService 与 onStartCommand
            // 中的 startForeground 在同一主线程消息队列中紧邻执行。
            return try {
                val intent = Intent(context, BackgroundTaskService::class.java).apply {
                    putExtra(EXTRA_ACTIVE_TASK_COUNT, activeCount)
                    putExtra(EXTRA_TASK_NAME, taskName)
                }
                synchronized(lock) {
                    // 新任务到来：取消尚未执行的延迟停止，避免服务刚停又被重建。
                    pendingStop?.let { SimpleHandler.getInstance().removeCallbacks(it) }
                    pendingStop = null
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        startScheduled = false
                        doStart(context, intent)
                    } else {
                        if (startScheduled) {
                            // 已排队一个启动投递，避免重复堆积。
                            // 排队中的投递持有旧 intent（旧任务名/数量），服务已在
                            // 运行时仅用于更新通知，短暂滞后可接受。
                            true
                        } else {
                            startScheduled = true
                            SimpleHandler.getInstance().post {
                                synchronized(lock) { startScheduled = false }
                                doStart(context, intent)
                            }
                            true
                        }
                    }
                }
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (Android 12+),
                // SecurityException (Android 14 dataSync restrictions), etc.
                Log.e(TAG, "Failed to start foreground service", e)
                false
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            // 与 start 共享锁，确保 stop 不会夹在“已投递但尚未执行的 start”之间。
            // 真正的 stopService 延迟 STOP_DELAY_MS 执行，期间若有新任务 start()
            // 会取消该次停止，从而把“反复销毁/重建前台服务”合并为一次生命周期。
            // 注意：这里不取消已排队的 start 投递——若 stop 与新任务的 start 交错，
            // start 投递先执行（服务启动），随后延迟停止在 2 秒后兜底收尾，
            // 语义依然一致。
            synchronized(lock) {
                startScheduled = false
                // 若已有待执行的延迟停止，先移除旧的可执行体再重新计时，
                // 避免多个 stop 叠加时执行过期的旧 context。
                pendingStop?.let { SimpleHandler.getInstance().removeCallbacks(it) }
                val stopTask = Runnable {
                    synchronized(lock) {
                        pendingStop = null
                        try {
                            context.stopService(Intent(context, BackgroundTaskService::class.java))
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to stop foreground service", e)
                        }
                    }
                }
                pendingStop = stopTask
                SimpleHandler.getInstance().postDelayed(stopTask, STOP_DELAY_MS)
            }
        }

        private fun doStart(context: Context, intent: Intent): Boolean {
            return try {
                if (sServiceRunning) {
                    // 服务已在前台运行：用普通 startService 更新即可，
                    // 不再打开新的 startForeground 超时窗口。
                    try {
                        context.startService(intent)
                        return true
                    } catch (e: IllegalStateException) {
                        // 竞态下服务可能刚好被系统停止，退回 startForegroundService 重建。
                        Log.w(TAG, "startService failed on running service, falling back to startForegroundService", e)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    @Suppress("DEPRECATION")
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
                false
            }
        }

    }

    private var mWakeLock: PowerManager.WakeLock? = null
    private var mLockRefreshActive = false
    private val mLockRefreshRunnable = Runnable { refreshLock() }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        // 以服务真实生命周期维护运行标志：此后 companion 的 doStart 会改走
        // 普通 startService 更新，不再打开新的 startForeground 超时窗口。
        sServiceRunning = true

        // 系统要求 startForegroundService() 后必须在超时窗口（约 5s）内调用
        // startForeground()，否则抛出 ForegroundServiceDidNotStartInTimeException。
        // 因此这里把 startForeground() 作为 onCreate 的第一优先级操作，先以最小
        // 通知满足时限，再补建渠道/刷新通知内容。任何情况下都不能在未调用
        // startForeground() 之前 return。
        if (!startForegroundSafely(0, null)) {
            // 所有尝试都失败时：先调用一次不带类型的最稳妥 startForeground 兜底，
            // 仍失败才停止服务。绝不静默返回而漏掉 startForeground()。
            Log.e(TAG, "startForeground ultimately failed in onCreate, stopping service")
            try {
                stopSelf()
            } catch (_: Throwable) {
            }
            return
        }
        // 满足时限后再确保渠道存在，通知的真实内容/数量由 onStartCommand 提供并刷新
        try {
            ensureNotificationChannel()
        } catch (e: Throwable) {
            // 渠道创建失败不影响已满足时限的前台状态，仅记录
            Log.w(TAG, "Failed to ensure notification channel in onCreate", e)
        }
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

    private fun ensureNotificationChannel() {
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
    }

    /**
     * 尽力调用 startForeground()，返回是否成功。
     *
     * 在 Android 14+（API 34）优先使用带前台服务类型的三参数版本，若该调用抛出
     * （例如 dataSync 类型在当前时机被系统拒绝），则回退到两参数版本（系统会使用
     * 清单中声明的 dataSync 类型）。两参数也失败才返回 false。
     *
     * 该函数只负责“满足系统 startForegroundService→startForeground 时限”这一件事，
     * 调用方绝不能在未成功的情况下静默返回，否则会触发
     * ForegroundServiceDidNotStartInTimeException。
     */
    @SuppressLint("MissingPermission")
    private fun startForegroundSafely(activeCount: Int, taskName: String?): Boolean {
        val notification = try {
            buildNotification(activeCount, taskName)
        } catch (e: Throwable) {
            // 通知构建失败：不再尝试以 null 调用（startForeground 不接受 null），
            // 直接视为未能在时限内成功，交由调用方停止服务。
            Log.e(TAG, "Failed to build notification", e)
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } catch (e: Throwable) {
                    // 带类型版本失败，回退到无类型版本（使用清单声明的类型）
                    Log.w(TAG, "Typed startForeground failed, falling back to untyped", e)
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-call startForeground to satisfy the system requirement after
        // startForegroundService() when the service is already running.
        // Without this, Android throws ForegroundServiceDidNotStartInTimeException.
        val activeCount = intent?.getIntExtra(EXTRA_ACTIVE_TASK_COUNT, 0) ?: 0
        val taskName = intent?.getStringExtra(EXTRA_TASK_NAME)
        if (!startForegroundSafely(activeCount, taskName)) {
            // 前台状态未能确立：继续运行也无法保证不被系统回收/触发异常，先停止服务。
            // 注意：这里只有在 startForegroundSafely 内部把 3 参数与 2 参数都尝试过、
            // 仍失败时才走到，不会出现“漏调 startForeground()”的情况。
            Log.e(TAG, "Failed to startForeground in onStartCommand, stopping service")
            try {
                stopSelf()
            } catch (_: Throwable) {
            }
            return START_NOT_STICKY
        }

        // Acquire WakeLock to prevent CPU sleep
        acquireWakeLock()

        // Start lock refresh timer
        startLockRefresh()

        Log.d(TAG, "Service started, activeTasks=$activeCount, taskName=$taskName")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 服务生命周期结束：此后如再有任务需要前台保护，doStart 会重新走
        // startForegroundService 完整流程（打开一次性超时窗口并立即满足）。
        sServiceRunning = false
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
