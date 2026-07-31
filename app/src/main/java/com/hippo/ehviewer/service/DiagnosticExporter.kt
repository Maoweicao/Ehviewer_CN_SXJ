package com.hippo.ehviewer.service

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.network.NetworkHealthTracker
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将性能滚动日志、无响应前诊断文件、以及当前性能/网络快照合并为单个诊断包，
 * 方便用户一次性导出提交。
 */
object DiagnosticExporter {

    private const val TAG = "DiagnosticExporter"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun export(context: Context): File? {
        val dir = AppConfig.getExternalDiagnosticDir() ?: return null
        if (!dir.exists()) dir.mkdirs()

        val rollingLogs = PerformanceMonitor.getRollingLogFiles()
        val preAnrFiles = PerformanceMonitor.getPreAnrFiles()

        val file = File(dir, "diagnostic_bundle_${dateFormat.format(Date())}.txt")
        try {
            FileWriter(file, false).use { writer ->
                writer.write("===== EhViewer 完整诊断包 =====\n")
                writer.write("导出时间: ${logDateFormat.format(Date())}\n\n")

                // 1. 当前性能快照
                try {
                    val snap = PerformanceMonitor.collectSnapshot()
                    writer.write("===== 当前性能快照 =====\n")
                    writer.write("JVM内存: ${snap.jvmUsedMB}/${snap.jvmMaxMB} MB (${snap.jvmUsagePercent}%)\n")
                    writer.write("Native: ${snap.nativeHeapMB} MB\n")
                    writer.write("线程数: ${snap.threadCount}\n")
                    writer.write("Activity: ${snap.activeActivityCount}\n")
                    writer.write("下载中: ${snap.downloadingCount}  等待中: ${snap.waitingCount}\n")
                    writer.write("图片缓存: ${snap.conacoCacheSizeKB / 1024}/${snap.conacoCacheMaxKB / 1024} MB\n")
                    writer.write("网络: 在飞=${snap.networkInflight} 连接池=${snap.networkConnectionCount}(空闲=${snap.networkIdleConnectionCount}) 慢请求=${snap.networkSlowCalls} 超时=${snap.networkTimeoutCalls}\n\n")
                } catch (e: Throwable) {
                    writer.write("当前性能快照获取失败: ${e.message}\n\n")
                }

                // 2. 网络健康
                try {
                    val sb = StringBuilder()
                    NetworkHealthTracker.format(sb)
                    writer.write(sb.toString())
                    writer.write("\n")
                } catch (e: Throwable) {
                    writer.write("网络健康获取失败: ${e.message}\n\n")
                }

                // 3. 无响应前诊断文件
                if (preAnrFiles.isNotEmpty()) {
                    writer.write("===== 无响应前诊断 (${preAnrFiles.size} 个) =====\n\n")
                    for (f in preAnrFiles) {
                        writer.write("----- ${f.name} -----\n")
                        try {
                            f.readText().lineSequence().forEach { writer.write("$it\n") }
                        } catch (e: Throwable) {
                            writer.write("(读取失败: ${e.message})\n")
                        }
                        writer.write("\n")
                    }
                } else {
                    writer.write("===== 无响应前诊断 =====\n(暂无 pre_anr 记录)\n\n")
                }

                // 4. 性能滚动日志
                if (rollingLogs.isNotEmpty()) {
                    writer.write("===== 性能滚动日志 (${rollingLogs.size} 个) =====\n\n")
                    for (f in rollingLogs) {
                        writer.write("----- ${f.name} -----\n")
                        try {
                            f.readText().lineSequence().forEach { writer.write("$it\n") }
                        } catch (e: Throwable) {
                            writer.write("(读取失败: ${e.message})\n")
                        }
                        writer.write("\n")
                    }
                } else {
                    writer.write("===== 性能滚动日志 =====\n(暂无记录)\n\n")
                }
            }
            Log.i(TAG, "Diagnostic bundle exported: ${file.absolutePath}")
            return file
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM while exporting diagnostic bundle")
            return null
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to export diagnostic bundle", e)
            return null
        }
    }
}
