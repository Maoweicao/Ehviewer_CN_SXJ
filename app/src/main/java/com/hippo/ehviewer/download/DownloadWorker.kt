package com.hippo.ehviewer.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.util.MiuiOptimizationHelper
import kotlinx.coroutines.delay

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val downloadManager: DownloadManager =
        EhApplication.getDownloadManager(context)
    private val channelId = "${context.packageName}.download_worker"

    override suspend fun doWork(): Result {
        ensureNotificationChannel()
        setForeground(createForegroundInfo())

        try {
            var priorityBoostTick = 0
            var idleTicks = 0
            // Hard cap so this can never outlive a genuinely stuck state forever.
            val maxTicks = MAX_LIFETIME_SECONDS * 1000 / TICK_MS
            var ticks = 0
            while (!isStopped && ticks < maxTicks) {
                ticks++
                if (!downloadManager.hasActiveDownload()) {
                    // Require a few consecutive idle observations before exiting:
                    // a single gap can happen between one gallery finishing and
                    // the next being picked up by ensureDownload().
                    idleTicks++
                    if (idleTicks >= IDLE_TICKS_BEFORE_EXIT) {
                        break
                    }
                    delay(TICK_MS)
                    continue
                }
                idleTicks = 0
                // Periodically boost SpiderQueen/SpiderWorker thread priority.
                // On HyperOS / aggressive OEMs, even default-priority threads can be
                // throttled when the app is in the background. This is a safety net
                // that pushes them to THREAD_PRIORITY_FOREGROUND (-1) which is less
                // likely to be throttled.
                priorityBoostTick++
                if (priorityBoostTick >= PRIORITY_BOOST_EVERY_TICKS) {
                    priorityBoostTick = 0
                    boostDownloadThreadPriority()
                }
                delay(TICK_MS)
            }
        } finally {
            // Only tear the DownloadService down if it really has nothing left to do.
            // Unconditionally sending ACTION_STOP_ALL here would also abort a
            // download that the user started deliberately while this keep-alive
            // worker was running.
            if (!downloadManager.hasActiveDownload()) {
                stopDownloadService()
            }
        }

        return Result.success()
    }

    /**
     * Find all SpiderQueen / SpiderWorker threads and boost their priority.
     *
     * Uses reflection to call the hidden `Process.setThreadPriority(int tid, int priority)`
     * which has been available since API 1 but is hidden from the public SDK.
     * If reflection fails (e.g., future Android restrictions), we silently no-op.
     */
    private fun boostDownloadThreadPriority() {
        try {
            val setThreadPriority = Process::class.java.getMethod(
                "setThreadPriority",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val boostedPriority = Process.THREAD_PRIORITY_FOREGROUND
            var boostedCount = 0
            for (thread in Thread.getAllStackTraces().keys) {
                if (!thread.isAlive) continue
                val name = thread.name ?: continue
                if (name.startsWith("SpiderQueen-") || name.startsWith("SpiderWorker-")) {
                    try {
                        setThreadPriority.invoke(null, thread.id, boostedPriority)
                        boostedCount++
                    } catch (_: Exception) {
                    }
                }
            }
            if (boostedCount > 0) {
                Log.d(TAG, "Boosted priority of $boostedCount download threads to FOREGROUND")
            }
        } catch (_: Throwable) {
            // setThreadPriority(int, int) not available; rely on SpiderQueen's DEFAULT priority
        }
    }

    private fun stopDownloadService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val intent = android.content.Intent(applicationContext, DownloadService::class.java)
                .setAction(DownloadService.ACTION_STOP_ALL)
            applicationContext.startService(intent)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = MiuiOptimizationHelper.getRecommendedNotificationImportance()
            val channel = NotificationChannel(
                channelId,
                applicationContext.getString(R.string.download_service),
                importance
            ).apply {
                if (MiuiOptimizationHelper.needsAggressiveOptimization()) {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                }
            }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentTitle(applicationContext.getString(R.string.download_service))
            .setContentText(applicationContext.getString(R.string.preparing_download))
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(ID_DOWNLOADING, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID_DOWNLOADING, notification)
        }
    }

    companion object {
        const val ID_DOWNLOADING = 100
        const val UNIQUE_WORK_NAME = "ehviewer_download"
        private const val TAG = "DownloadWorker"

        /** How often the worker wakes up to re-check state / boost threads. */
        private const val TICK_MS = 1000L

        /** Boost SpiderWorker priority every N ticks (~3s). */
        private const val PRIORITY_BOOST_EVERY_TICKS = 3

        /** Consecutive idle observations required before the worker exits. */
        private const val IDLE_TICKS_BEFORE_EXIT = 5

        /**
         * Absolute upper bound on the worker's lifetime. A download can legitimately
         * run for hours, so this is generous; its only purpose is to guarantee the
         * foreground worker can never outlive a wedged state.
         */
        private const val MAX_LIFETIME_SECONDS = 6 * 60 * 60

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}