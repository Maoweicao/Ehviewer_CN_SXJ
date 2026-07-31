package com.hippo.ehviewer.service

import android.content.Context
import android.os.Debug
import android.util.Log
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.network.NetworkHealthTracker
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 性能监测核心模块（单例）
 *
 * 定时采集 JVM 内存、Native 内存、线程数、活跃 Activity 数、下载状态等指标，
 * 存储在环形缓冲区（默认 100 条，约 50 分钟），支持导出为可读文本文件。
 */
object PerformanceMonitor {

    private const val TAG = "PerformanceMonitor"
    private const val MAX_SNAPSHOTS = 100

    /**
     * 单次采样快照
     */
    data class Snapshot(
        val timestamp: Long,
        val jvmUsedMB: Long,
        val jvmMaxMB: Long,
        val jvmUsagePercent: Int,
        val nativeHeapMB: Long,
        val threadCount: Int,
        val activeActivityCount: Int,
        val downloadingCount: Int,
        val waitingCount: Int,
        val conacoCacheSizeKB: Int,
        val conacoCacheMaxKB: Int,
        val networkInflight: Int = 0,
        val networkConnectionCount: Int = -1,
        val networkIdleConnectionCount: Int = -1,
        val networkSlowCalls: Long = 0,
        val networkTimeoutCalls: Long = 0
    )

    private val ringBuffer = LinkedList<Snapshot>()
    private val lock = Any()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    /**
     * 性能快照磁盘滚动日志（有界，按天轮转），确保进程被杀/ANR 后仍保留最近历史。
     */
    private val diskExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PerfMonitor-Disk").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * 采集一次性能快照
     */
    fun collectSnapshot(): Snapshot {
        val rt = Runtime.getRuntime()
        val jvmUsed = rt.totalMemory() - rt.freeMemory()
        val jvmMax = rt.maxMemory()
        val jvmUsedMB = jvmUsed / (1024 * 1024)
        val jvmMaxMB = jvmMax / (1024 * 1024)
        val jvmUsagePercent = if (jvmMax > 0) (jvmUsed * 100 / jvmMax).toInt().coerceIn(0, 100) else 0

        val nativeHeapMB = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        val threadCount = Thread.activeCount()

        val app = EhApplication.getInstance()
        val activeActivityCount = app?.activeActivityCount ?: 0

        var downloadingCount = 0
        var waitingCount = 0
        try {
            val dm = EhApplication.getDownloadManager()
            downloadingCount = dm.downloadingCount
            waitingCount = dm.waitingCount
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query DownloadManager", e)
        }

        var conacoCacheSizeKB = 0
        var conacoCacheMaxKB = 0
        try {
            if (app != null) {
                val conaco = EhApplication.getConaco(app)
                val memoryCache = conaco.beerBelly.memoryCache
                if (memoryCache != null) {
                    conacoCacheSizeKB = memoryCache.size() / 1024
                    conacoCacheMaxKB = memoryCache.maxSize() / 1024
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query Conaco cache", e)
        }

        val net = runCatching { NetworkHealthTracker.getSnapshot() }.getOrNull()

        val snapshot = Snapshot(
            timestamp = System.currentTimeMillis(),
            jvmUsedMB = jvmUsedMB,
            jvmMaxMB = jvmMaxMB,
            jvmUsagePercent = jvmUsagePercent,
            nativeHeapMB = nativeHeapMB,
            threadCount = threadCount,
            activeActivityCount = activeActivityCount,
            downloadingCount = downloadingCount,
            waitingCount = waitingCount,
            conacoCacheSizeKB = conacoCacheSizeKB,
            conacoCacheMaxKB = conacoCacheMaxKB,
            networkInflight = net?.inflight ?: 0,
            networkConnectionCount = net?.connectionCount ?: -1,
            networkIdleConnectionCount = net?.idleConnectionCount ?: -1,
            networkSlowCalls = net?.slowCalls ?: 0,
            networkTimeoutCalls = net?.timeoutCalls ?: 0
        )

        writeRollingLog(snapshot)

        synchronized(lock) {
            if (ringBuffer.size >= MAX_SNAPSHOTS) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(snapshot)
        }

        Log.d(TAG, "Snapshot: JVM=${jvmUsedMB}/${jvmMaxMB}MB (${jvmUsagePercent}%), " +
                "Native=${nativeHeapMB}MB, Threads=$threadCount, " +
                "Activities=$activeActivityCount, Downloads=$downloadingCount/$waitingCount")

        return snapshot
    }

    /**
     * 将快照追加写入磁盘滚动日志（按天轮转 + 自动清理旧文件），确保进程死亡不丢历史。
     */
    private fun writeRollingLog(snapshot: Snapshot) {
        diskExecutor.execute {
            try {
                val dir = AppConfig.getExternalPerformanceDir() ?: return@execute
                if (!dir.exists()) dir.mkdirs()

                val today = dayFormat.format(Date())
                val logFile = File(dir, "performance_$today.log")

                FileWriter(logFile, true).use { writer ->
                    writer.write(
                        "${dateFormat.format(Date(snapshot.timestamp))} | " +
                            "JVM=${snapshot.jvmUsedMB}/${snapshot.jvmMaxMB}MB(${snapshot.jvmUsagePercent}%) " +
                            "Native=${snapshot.nativeHeapMB}MB Threads=${snapshot.threadCount} " +
                            "Act=${snapshot.activeActivityCount} DL=${snapshot.downloadingCount}/${snapshot.waitingCount} " +
                            "Conaco=${snapshot.conacoCacheSizeKB / 1024}MB/${snapshot.conacoCacheMaxKB / 1024}MB " +
                            "Net{inflight=${snapshot.networkInflight} conn=${snapshot.networkConnectionCount}(idle=${snapshot.networkIdleConnectionCount}) slow=${snapshot.networkSlowCalls} timeout=${snapshot.networkTimeoutCalls}}\n"
                    )
                }

                // 清理 7 天前的滚动日志，避免无限增长
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                dir.listFiles { f -> f.name.startsWith("performance_") && f.name.endsWith(".log") }
                    ?.forEach { f ->
                        if (f.lastModified() < cutoff) {
                            try { f.delete() } catch (_: Exception) {}
                        }
                    }
            } catch (e: OutOfMemoryError) {
                // 内存紧张时静默放弃
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to write rolling log", e)
            }
        }
    }

    /**
     * 获取磁盘滚动日志目录中最近的若干性能日志文件（供 UI 导出/展示）。
     */
    fun getRollingLogFiles(): List<File> {
        val dir = AppConfig.getExternalPerformanceDir() ?: return emptyList()
        return (dir.listFiles { f ->
            f.name.startsWith("performance_") && f.name.endsWith(".log")
        } ?: emptyArray()).sortedByDescending { it.lastModified() }
    }

    /**
     * 获取磁盘上所有 pre_anr 诊断文件（供 UI 提示/导出）。
     */
    fun getPreAnrFiles(): List<File> {
        val dir = AppConfig.getExternalWatchdogDir() ?: return emptyList()
        return (dir.listFiles { f ->
            f.name.startsWith("pre_anr_") && f.name.endsWith(".log")
        } ?: emptyArray()).sortedByDescending { it.lastModified() }
    }

    /**
     * 获取所有快照的副本
     */
    fun getSnapshots(): List<Snapshot> {
        synchronized(lock) {
            return ArrayList(ringBuffer)
        }
    }

    /**
     * 获取最新一条快照（可能为 null）
     */
    fun getLatestSnapshot(): Snapshot? {
        synchronized(lock) {
            return if (ringBuffer.isNotEmpty()) ringBuffer.last() else null
        }
    }

    /**
     * 清空缓冲区
     */
    fun clearSnapshots() {
        synchronized(lock) {
            ringBuffer.clear()
        }
        Log.i(TAG, "Snapshots cleared")
    }

    /**
     * 导出性能日志到文件
     * @return 导出的文件，失败返回 null
     */
    fun exportToFile(context: Context): File? {
        val dir = com.hippo.ehviewer.AppConfig.getExternalPerformanceDir()
        if (dir == null) {
            Log.e(TAG, "Cannot get logcat directory")
            return null
        }

        val snapshots = getSnapshots()
        if (snapshots.isEmpty()) {
            Log.w(TAG, "No snapshots to export")
            return null
        }

        val file = File(dir, "performance-${fileDateFormat.format(Date(System.currentTimeMillis()))}.txt")

        try {
            FileWriter(file).use { writer ->
                writer.write("===== EhViewer 性能监测日志 =====\n")
                writer.write("导出时间: ${dateFormat.format(Date(System.currentTimeMillis()))}\n")
                writer.write("采样间隔: 30秒  总记录数: ${snapshots.size}\n")
                writer.write("\n")

                snapshots.forEachIndexed { index, s ->
                    writer.write("--- 采样 #${index + 1} [${dateFormat.format(Date(s.timestamp))}] ---\n")
                    writer.write("  JVM内存:  ${s.jvmUsedMB}/${s.jvmMaxMB} MB (${s.jvmUsagePercent}%)\n")
                    writer.write("  Native:   ${s.nativeHeapMB} MB\n")
                    writer.write("  线程数:   ${s.threadCount}\n")
                    writer.write("  Activity: ${s.activeActivityCount}\n")
                    writer.write("  下载中:   ${s.downloadingCount}  等待中: ${s.waitingCount}\n")
                    val conacoUsed = s.conacoCacheSizeKB / 1024
                    val conacoMax = s.conacoCacheMaxKB / 1024
                    writer.write("  图片缓存: ${conacoUsed}/${conacoMax} MB\n")
                    writer.write("  网络:     在飞=${s.networkInflight} 连接池=${s.networkConnectionCount}(空闲=${s.networkIdleConnectionCount}) 慢请求=${s.networkSlowCalls} 超时=${s.networkTimeoutCalls}\n")
                    writer.write("\n")
                }

                writer.write("===== 日志结束 =====\n")
            }

            Log.i(TAG, "Exported ${snapshots.size} snapshots to ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export performance log", e)
            return null
        }
    }
}
