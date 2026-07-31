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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.PerformanceLogActivity
import com.hippo.lib.yorozuya.SimpleHandler

/**
 * 性能监测前台服务
 *
 * 定时（每 30 秒）采集一次性能快照，通知栏显示当前 JVM 使用率概要。
 * 通过 Settings.getPerformanceMonitorEnabled() 控制启停。
 */
class PerformanceMonitorService : Service() {

    companion object {
        private const val TAG = "PerfMonitorService"
        private const val CHANNEL_ID = "eh_performance_monitor"
        private const val NOTIFICATION_ID = 1002
        private const val SAMPLING_INTERVAL_MS = 30_000L

        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, PerformanceMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, PerformanceMonitorService::class.java))
        }
    }

    private val mSamplingRunnable = Runnable { sample() }
    private var mHandler: SimpleHandler? = null

    @SuppressLint("UnspecifiedImmutableFlag")
    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.performance_monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.performance_monitor_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        startForeground(NOTIFICATION_ID, buildNotification(null))
        Log.d(TAG, "Service started")

        mHandler = SimpleHandler.getInstance() as? SimpleHandler
        sample()
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSampling()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSampling()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSampling()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun sample() {
        val snapshot = PerformanceMonitor.collectSnapshot()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(snapshot))

        startSampling()
    }

    private fun startSampling() {
        mHandler?.removeCallbacks(mSamplingRunnable)
        mHandler?.postDelayed(mSamplingRunnable, SAMPLING_INTERVAL_MS)
    }

    private fun stopSampling() {
        mHandler?.removeCallbacks(mSamplingRunnable)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun buildNotification(snapshot: PerformanceMonitor.Snapshot?): android.app.Notification {
        val title = getString(R.string.performance_monitor_notification_title)

        val contentText = if (snapshot != null) {
            getString(
                R.string.performance_monitor_notification_text,
                snapshot.jvmUsagePercent,
                snapshot.nativeHeapMB,
                snapshot.threadCount
            )
        } else {
            getString(R.string.performance_monitor_notification_text, 0, 0L, 0)
        }

        val intent = Intent(this, PerformanceLogActivity::class.java).apply {
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
}
