package com.hippo.ehviewer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.network.TrafficCaptureManager
import com.hippo.ehviewer.ui.CaptureActivity

class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "eh_network_capture"
        private const val NOTIFICATION_ID = 2001
        private const val NOTIFICATION_UPDATE_MS = 1000L

        const val ACTION_START = "com.hippo.ehviewer.CaptureService.START"
        const val ACTION_STOP = "com.hippo.ehviewer.CaptureService.STOP"
        const val ACTION_SAVE_STOP = "com.hippo.ehviewer.CaptureService.SAVE_STOP"

        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
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
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        @JvmStatic
        fun saveAndStop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_SAVE_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val notificationUpdateRunnable = object : Runnable {
        override fun run() {
            if (TrafficCaptureManager.isActive()) {
                updateNotification()
                handler.postDelayed(this, NOTIFICATION_UPDATE_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(TAG, "Service created, foreground started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                TrafficCaptureManager.start()
                handler.post(notificationUpdateRunnable)
                Log.i(TAG, "Capture started")
            }
            ACTION_STOP -> {
                TrafficCaptureManager.stop()
                handler.removeCallbacks(notificationUpdateRunnable)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "Capture stopped")
            }
            ACTION_SAVE_STOP -> {
                TrafficCaptureManager.stop()
                handler.removeCallbacks(notificationUpdateRunnable)
                val file = TrafficCaptureManager.exportHar()
                if (file != null) {
                    Log.i(TAG, "HAR saved: ${file.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to save HAR")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "Capture saved and stopped")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(notificationUpdateRunnable)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.capture_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = Intent(this, CaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingContent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CaptureService::class.java).apply {
            action = ACTION_SAVE_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val entryCount = TrafficCaptureManager.getEntryCount()
        val sent = TrafficCaptureManager.formatBytes(TrafficCaptureManager.getTotalBytesSent())
        val received = TrafficCaptureManager.formatBytes(TrafficCaptureManager.getTotalBytesReceived())

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text, entryCount, sent, received))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                getString(R.string.capture_notification_text, entryCount, sent, received)
            ))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingContent)
            .addAction(R.mipmap.ic_launcher, getString(R.string.capture_stop_save), pendingStop)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }
}
