package com.hippo.ehviewer.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.network.NetworkHealthTracker
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主线程无响应预判看门狗。
 *
 * 在独立后台线程上运行，定期向主线程 Looper 投递心跳；若主线程在
 * [BLOCK_THRESHOLD_MS] 内未处理该心跳，即判定主线程被阻塞（早于系统 5s ANR），
 * 立即抓取线程栈 + 性能快照 + 网络健康快照并落盘，争取在应用被杀前保留现场。
 *
 * 同时提供 [requestDump] 供低内存、未捕获异常等场景主动触发落盘。
 */
object HealthWatchdog {

    private const val TAG = "HealthWatchdog"
    private const val HEARTBEAT_INTERVAL_MS = 2000L
    private const val BLOCK_THRESHOLD_MS = 3500L
    private const val DUMP_COOLDOWN_MS = 30_000L
    // 阻塞时长上报上限：进程被冻结/看门狗自身被挂起时，基线会过期而算出一个
    // 离谱的时长（历史出现过 1183054ms ≈ 19 分钟），上报前钳制并重置基线。
    private const val MAX_BLOCK_REPORT_MS = 120_000L
    private const val SAMPLE_INTERVAL_MS = 10_000L

    /**
     * 主线程恢复后的"余震窗口"。
     *
     * 系统 ANR 弹窗的判定（约 5s）晚于看门狗的预判（3.5s），因此弹窗经常在主线程
     * 刚刚恢复之后才真正弹出。旧逻辑只在"仍在阻塞"时请求清理，恢复后立即停止，
     * 导致残留弹窗没人关。这里在恢复后继续清理一段时间。
     */
    private const val RECOVERY_DISMISS_WINDOW_MS = 30_000L

    @Volatile
    private var running = false

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastProcessedTime = 0L

    /** 最近一次判定主线程阻塞的 uptimeMillis；用于恢复后的余震清理与弹窗清除门槛。 */
    @Volatile
    private var lastBlockedAt = 0L

    private val blocked = AtomicBoolean(false)

    private var lastDumpMs = 0L
    private var lastSampleMs = 0L

    private var thread: Thread? = null
    private var dumpExecutor: ExecutorService? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized
    fun start() {
        if (running) return
        running = true
        // uptimeMillis excludes deep sleep: wall-clock based measurement
        // previously produced false "blocked for hours" reports after doze.
        lastProcessedTime = SystemClock.uptimeMillis()
        lastDumpMs = 0L
        lastBlockedAt = 0L

        dumpExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "HealthWatchdog-Dump").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }

        val t = Thread({
            runLoop()
        }, "HealthWatchdog").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
        thread = t
        t.start()
        Log.i(TAG, "HealthWatchdog started")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            thread?.interrupt()
        } catch (_: Exception) {
        }
        thread = null
        dumpExecutor?.shutdown()
        try {
            dumpExecutor?.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        dumpExecutor = null
        Log.i(TAG, "HealthWatchdog stopped")
    }

    fun isMainThreadBlocked(): Boolean = blocked.get()

    /**
     * 最近 [RECOVERY_DISMISS_WINDOW_MS] 内是否判定过主线程阻塞。
     *
     * 供 [AnrDialogDismissService] 作为清理门槛：纯界面操作导致的 ANR 没有活跃
     * 后台任务，旧门槛会直接跳过，弹窗只能手动关。
     */
    fun isRecentlyBlocked(): Boolean {
        val last = lastBlockedAt
        return last != 0L && SystemClock.uptimeMillis() - last <= RECOVERY_DISMISS_WINDOW_MS
    }

    private fun runLoop() {
        while (running) {
            try {
                // 向主线程投递心跳，主线程处理时刷新 lastProcessedTime
                mainHandler.post {
                    lastProcessedTime = SystemClock.uptimeMillis()
                }

                val now = SystemClock.uptimeMillis()
                val sinceProcessed = now - lastProcessedTime

                if (sinceProcessed >= BLOCK_THRESHOLD_MS) {
                    lastBlockedAt = now
                    // 主线程阻塞持续中：周期性请求清除系统 ANR 弹窗（点击"等待"）。
                    // 系统 ANR 弹窗通常在无响应约 5 秒后弹出，看门狗 3.5s 即预判阻塞，
                    // 心跳 2s 一次的调用配合服务内部防抖正好覆盖弹窗出现前后的窗口期；
                    // 服务内部会再校验设置开关与"活跃任务/最近阻塞"条件，平时不产生任何动作。
                    AnrDialogDismissService.requestDismiss()
                    if (blocked.compareAndSet(false, true)) {
                        val reported = if (sinceProcessed > MAX_BLOCK_REPORT_MS) MAX_BLOCK_REPORT_MS else sinceProcessed
                        Log.w(TAG, "Main thread blocked for ${sinceProcessed}ms, dumping diagnostics")
                        requestDump("main_thread_blocked(${reported}ms)", false)
                        if (sinceProcessed > MAX_BLOCK_REPORT_MS) {
                            // 基线明显过期（跨前后台/冻结），重置，避免后续持续误判
                            lastProcessedTime = now
                        }
                    }
                } else {
                    blocked.set(false)
                    // 心跳恢复：刷新基线，保证下一次判定基于最新时间
                    lastProcessedTime = now
                    // 余震窗口：系统 ANR 弹窗常在阻塞刚结束时才弹出，或阻塞期间点击
                    // "等待"失败；恢复后继续请求清理，直到窗口期结束。
                    if (lastBlockedAt != 0L) {
                        if (now - lastBlockedAt <= RECOVERY_DISMISS_WINDOW_MS) {
                            AnrDialogDismissService.requestDismiss()
                        } else {
                            lastBlockedAt = 0L
                        }
                    }
                }

                // 即使主线程卡住，也由看门狗线程周期性采集性能快照并落盘
                if (now - lastSampleMs >= SAMPLE_INTERVAL_MS) {
                    lastSampleMs = now
                    try {
                        PerformanceMonitor.collectSnapshot()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Periodic sample failed", e)
                    }
                }

                Thread.sleep(HEARTBEAT_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            } catch (e: Throwable) {
                Log.e(TAG, "Watchdog loop error", e)
            }
        }
    }

    /**
     * 请求一次诊断落盘。
     * @param reason 触发原因，写入文件头部
     * @param force  是否忽略冷却时间（低内存/异常场景建议 true）
     */
    @Synchronized
    fun requestDump(reason: String, force: Boolean) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        if (!force) {
            if (now - lastDumpMs < DUMP_COOLDOWN_MS) return
        }
        lastDumpMs = now
        val executor = dumpExecutor ?: return
        executor.execute {
            try {
                dumpInternal(reason)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to dump diagnostics", e)
            }
        }
    }

    private fun dumpInternal(reason: String) {
        val dir = AppConfig.getExternalWatchdogDir() ?: run {
            Log.w(TAG, "Cannot get watchdog log directory for dump")
            return
        }
        if (!dir.exists()) dir.mkdirs()

        val timestamp = dateFormat.format(Date())
        val file = File(dir, "pre_anr_$timestamp.log")

        val sb = StringBuilder()
        sb.append("===== EhViewer 无响应前诊断 (pre-ANR dump) =====\n")
        sb.append("时间: ${logDateFormat.format(Date())}\n")
        sb.append("触发原因: $reason\n")
        sb.append("主线程是否仍处于阻塞: ${blocked.get()}\n\n")

        // 1. 线程栈
        try {
            val traces = Thread.getAllStackTraces()
            sb.append("===== 线程栈 (${traces.size} threads) =====\n")
            val sorted = traces.entries.sortedByDescending { it.key.name == "main" }
            for ((t, stack) in sorted) {
                sb.append("\"${t.name}\" prio=${t.priority} state=${t.state}")
                if (t.name == "main") sb.append("  <-- MAIN")
                sb.append('\n')
                for (element in stack) {
                    sb.append("    at $element\n")
                }
                sb.append('\n')
            }
        } catch (e: Throwable) {
            sb.append("线程栈获取失败: ${e.message}\n\n")
        }

        // 2. 性能快照
        try {
            val snap = PerformanceMonitor.collectSnapshot()
            sb.append("===== 性能快照 =====\n")
            sb.append("JVM内存: ${snap.jvmUsedMB}/${snap.jvmMaxMB} MB (${snap.jvmUsagePercent}%)\n")
            sb.append("Native: ${snap.nativeHeapMB} MB\n")
            sb.append("线程数: ${snap.threadCount}\n")
            sb.append("Activity: ${snap.activeActivityCount}\n")
            sb.append("下载中: ${snap.downloadingCount}  等待中: ${snap.waitingCount}\n")
            sb.append("图片缓存: ${snap.conacoCacheSizeKB / 1024}/${snap.conacoCacheMaxKB / 1024} MB\n\n")
        } catch (e: Throwable) {
            sb.append("性能快照获取失败: ${e.message}\n\n")
        }

        // 3. 网络健康
        try {
            NetworkHealthTracker.format(sb)
        } catch (e: Throwable) {
            sb.append("网络健康获取失败: ${e.message}\n\n")
        }

        try {
            FileWriter(file, false).use { it.write(sb.toString()) }
            Log.i(TAG, "Pre-ANR diagnostics written to ${file.absolutePath}")
        } catch (e: OutOfMemoryError) {
            // 内存极度紧张时静默放弃，避免二次 OOM
            Log.e(TAG, "OOM while writing pre-ANR dump")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write pre-ANR dump", e)
        }

        // 清理超过保留期限的旧日志
        cleanupOldLogs(dir)
    }

    private fun cleanupOldLogs(dir: File) {
        try {
            val retentionMs = Settings.getWatchdogLogRetentionMinutes() * 60L * 1000L
            val cutoff = System.currentTimeMillis() - retentionMs
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) {
                    try { f.delete() } catch (_: Exception) {}
                }
            }
        } catch (_: Throwable) {}
    }
}
