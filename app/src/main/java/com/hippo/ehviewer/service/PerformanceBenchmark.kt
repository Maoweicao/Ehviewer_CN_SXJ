package com.hippo.ehviewer.service

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.R
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * 基础性能测试引擎。
 *
 * 提供 CPU 整数/浮点、内存读写、存储顺序读写与 4K 随机读写等指标，
 * 每个指标附带其在 EhViewer 中对应的典型任务场景说明，并支持导出为
 * 纯文本报告到 SDCard/EhViewer/benchmark/ 目录。
 */
object PerformanceBenchmark {

    private const val TAG = "PerformanceBenchmark"

    private const val CPU_TEST_DURATION_NS = 2_000_000_000L
    private const val SEQ_FILE_SIZE = 64L * 1024 * 1024
    private const val RAND_FILE_SIZE = 8L * 1024 * 1024
    private const val RAND_BLOCK_SIZE = 4096
    private const val RAND_ITERATIONS = 2000

    /** 单项测试结果 */
    data class BenchmarkResult(
        val nameRes: Int,
        val summaryRes: Int,
        val value: String,
        val unitRes: Int,
        /** 评级等级：0=较弱 1=一般 2=良好 3=优秀 */
        val rating: Int,
        /** 参考比较值（优秀 / 良好 / 一般 阈值） */
        val refExcellent: Double,
        val refGood: Double,
        val refFair: Double,
    )

    /**
     * 依次执行全部测试。
     *
     * @param onTestStarted 某项测试开始时回调，参数为该项名称的资源 ID
     * @param onTestFinished 某项测试完成后回调
     * @param onProgress 整体进度回调，0f..1f
     */
    fun runBenchmarks(
        onTestStarted: (Int) -> Unit,
        onTestFinished: (BenchmarkResult) -> Unit,
        onProgress: (Float) -> Unit,
    ): List<BenchmarkResult> {
        val dir = AppConfig.getExternalBenchmarkDir()
        val results = ArrayList<BenchmarkResult>()
        val total = 7

        fun runTest(
            nameRes: Int,
            summaryRes: Int,
            unitRes: Int,
            thresholds: RatingThresholds,
            format: (Double) -> String,
            bench: () -> Double,
        ) {
            onTestStarted(nameRes)
            val result = try {
                val v = bench()
                BenchmarkResult(
                    nameRes, summaryRes, format(v), unitRes,
                    rate(v, thresholds), thresholds.excellent, thresholds.good, thresholds.fair,
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Benchmark \"$nameRes\" failed", e)
                BenchmarkResult(nameRes, summaryRes, "---", unitRes, RATING_POOR, thresholds.excellent, thresholds.good, thresholds.fair)
            }
            results.add(result)
            onTestFinished(result)
        }

        runTest(
            R.string.performance_benchmark_cpu_int,
            R.string.performance_benchmark_cpu_int_summary,
            R.string.performance_benchmark_unit_ops,
            CPU_INT_THRESHOLDS,
            ::formatInt,
        ) { benchCpuInteger() }
        onProgress(1f / total)

        runTest(
            R.string.performance_benchmark_cpu_float,
            R.string.performance_benchmark_cpu_float_summary,
            R.string.performance_benchmark_unit_mflops,
            CPU_FLOAT_THRESHOLDS,
            ::formatScore,
        ) { benchCpuFloat() }
        onProgress(2f / total)

        runTest(
            R.string.performance_benchmark_memory,
            R.string.performance_benchmark_memory_summary,
            R.string.performance_benchmark_unit_mbps,
            MEMORY_THRESHOLDS,
            ::formatScore,
        ) { benchMemory() }
        onProgress(3f / total)

        if (dir != null) {
            runTest(
                R.string.performance_benchmark_storage_seq_write,
                R.string.performance_benchmark_storage_seq_write_summary,
                R.string.performance_benchmark_unit_mbps,
                SEQ_WRITE_THRESHOLDS,
                ::formatScore,
            ) { benchSeqWrite(dir) }
            onProgress(4f / total)

            runTest(
                R.string.performance_benchmark_storage_seq_read,
                R.string.performance_benchmark_storage_seq_read_summary,
                R.string.performance_benchmark_unit_mbps,
                SEQ_READ_THRESHOLDS,
                ::formatScore,
            ) { benchSeqRead(dir) }
            onProgress(5f / total)

            runTest(
                R.string.performance_benchmark_storage_rand_write,
                R.string.performance_benchmark_storage_rand_write_summary,
                R.string.performance_benchmark_unit_iops,
                RAND_IO_THRESHOLDS,
                ::formatScore,
            ) { benchRandomWrite(dir) }
            onProgress(6f / total)

            runTest(
                R.string.performance_benchmark_storage_rand_read,
                R.string.performance_benchmark_storage_rand_read_summary,
                R.string.performance_benchmark_unit_iops,
                RAND_IO_THRESHOLDS,
                ::formatScore,
            ) { benchRandomRead(dir) }
        }
        onProgress(1f)

        return results
    }

    // ==================== 评级 ====================

    /** 评级等级：0=较弱 1=一般 2=良好 3=优秀 */
    const val RATING_POOR = 0
    const val RATING_FAIR = 1
    const val RATING_GOOD = 2
    const val RATING_EXCELLENT = 3

    /** 评级阈值（优秀 / 良好 / 一般），同时作为页面与报告中的参考比较值 */
    data class RatingThresholds(val excellent: Double, val good: Double, val fair: Double)

    /** CPU 整数（SHA-256 次/秒） */
    private val CPU_INT_THRESHOLDS = RatingThresholds(3000.0, 1800.0, 900.0)

    /** CPU 浮点（百万次浮点运算/秒，双精度点积） */
    private val CPU_FLOAT_THRESHOLDS = RatingThresholds(2000.0, 1000.0, 500.0)

    /** 内存读写带宽（MB/s） */
    private val MEMORY_THRESHOLDS = RatingThresholds(4000.0, 2000.0, 1000.0)

    /** 存储顺序写入（MB/s） */
    private val SEQ_WRITE_THRESHOLDS = RatingThresholds(400.0, 200.0, 100.0)

    /** 存储顺序读取（MB/s） */
    private val SEQ_READ_THRESHOLDS = RatingThresholds(600.0, 300.0, 150.0)

    /** 4K 随机读写（IOPS） */
    private val RAND_IO_THRESHOLDS = RatingThresholds(15000.0, 5000.0, 1500.0)

    private fun rate(v: Double, t: RatingThresholds): Int = when {
        v >= t.excellent -> RATING_EXCELLENT
        v >= t.good -> RATING_GOOD
        v >= t.fair -> RATING_FAIR
        else -> RATING_POOR
    }

    /** 评级标签字符串资源 */
    fun ratingLabelRes(rating: Int): Int = when (rating) {
        RATING_EXCELLENT -> R.string.performance_benchmark_rating_excellent
        RATING_GOOD -> R.string.performance_benchmark_rating_good
        RATING_FAIR -> R.string.performance_benchmark_rating_fair
        else -> R.string.performance_benchmark_rating_poor
    }

    // ==================== 测试项实现 ====================

    /** CPU 整数运算：SHA-256 哈希吞吐（次/秒） */
    private fun benchCpuInteger(): Double {
        val digest = MessageDigest.getInstance("SHA-256")
        val data = ByteArray(64 * 1024) { (it % 251).toByte() }
        var iterations = 0L
        val start = System.nanoTime()
        while (System.nanoTime() - start < CPU_TEST_DURATION_NS) {
            digest.update(data)
            digest.digest()
            iterations++
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        return iterations / elapsed
    }

    /** CPU 浮点运算：双精度点积运算（百万次浮点运算/秒） */
    private fun benchCpuFloat(): Double {
        val size = 4096
        val a = DoubleArray(size) { it * 0.001 }
        val b = DoubleArray(size) { it * 0.002 + 1.0 }
        var sum = 0.0
        var passes = 0L
        val start = System.nanoTime()
        while (System.nanoTime() - start < CPU_TEST_DURATION_NS) {
            sum = 0.0
            var i = 0
            while (i < size) {
                sum += a[i] * b[i]
                i++
            }
            passes++
        }
        if (sum == Double.MAX_VALUE) {
            Log.w(TAG, "unreachable")
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        val flops = passes * size * 2.0
        return flops / elapsed / 1e6
    }

    /** 内存读写带宽：16MB 大数组连续写 + 连续读（MB/s） */
    private fun benchMemory(): Double {
        val size = 4 * 1024 * 1024
        val arr = IntArray(size)
        var sum = 0L
        var bytes = 0L
        val start = System.nanoTime()
        while (System.nanoTime() - start < CPU_TEST_DURATION_NS) {
            var i = 0
            while (i < size) {
                arr[i] = i
                i++
            }
            i = 0
            while (i < size) {
                sum += arr[i]
                i++
            }
            bytes += size * 4L * 2
        }
        if (sum == Long.MIN_VALUE) {
            Log.w(TAG, "unreachable")
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        return bytes / 1e6 / elapsed
    }

    /** 存储顺序写入：64MB 大文件连续写入（MB/s，含落盘刷新） */
    private fun benchSeqWrite(dir: File): Double {
        val file = File(dir, "bench_seq.tmp")
        val buf = ByteArray(1 shl 20) { 0x5A }
        val start = System.nanoTime()
        try {
            FileOutputStream(file).use { os ->
                var written = 0L
                while (written < SEQ_FILE_SIZE) {
                    os.write(buf)
                    written += buf.size.toLong()
                }
                os.flush()
                os.fd.sync()
            }
        } finally {
            file.delete()
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        return SEQ_FILE_SIZE / 1e6 / elapsed
    }

    /** 存储顺序读取：64MB 大文件连续读取（MB/s） */
    private fun benchSeqRead(dir: File): Double {
        val file = File(dir, "bench_seq.tmp")
        try {
            val buf = ByteArray(1 shl 20) { 0x3C }
            file.outputStream().use { os ->
                var written = 0L
                while (written < SEQ_FILE_SIZE) {
                    os.write(buf)
                    written += buf.size.toLong()
                }
                os.flush()
            }
            val start = System.nanoTime()
            file.inputStream().use { ins ->
                val readBuf = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val n = ins.read(readBuf)
                    if (n < 0) break
                    total += n
                }
                if (total < SEQ_FILE_SIZE) {
                    Log.w(TAG, "seq read truncated: $total")
                }
            }
            val elapsed = (System.nanoTime() - start) / 1e9
            return SEQ_FILE_SIZE / 1e6 / elapsed
        } finally {
            file.delete()
        }
    }

    /** 存储 4K 随机写入（IOPS） */
    private fun benchRandomWrite(dir: File): Double {
        val file = File(dir, "bench_rand.tmp")
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(RAND_FILE_SIZE)
                val buf = ByteArray(RAND_BLOCK_SIZE) { 0x7B }
                val rand = Random(0x1234)
                val start = System.nanoTime()
                repeat(RAND_ITERATIONS) {
                    val pos = (rand.nextLong() and Long.MAX_VALUE) % (RAND_FILE_SIZE - RAND_BLOCK_SIZE)
                    raf.seek(pos)
                    raf.write(buf)
                }
                val elapsed = (System.nanoTime() - start) / 1e9
                return RAND_ITERATIONS / elapsed
            }
        } finally {
            file.delete()
        }
    }

    /** 存储 4K 随机读取（IOPS） */
    private fun benchRandomRead(dir: File): Double {
        val file = File(dir, "bench_rand.tmp")
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(RAND_FILE_SIZE)
                val fill = ByteArray(RAND_BLOCK_SIZE) { 0x3C }
                var p = 0L
                while (p < RAND_FILE_SIZE) {
                    raf.seek(p)
                    raf.write(fill)
                    p += RAND_BLOCK_SIZE
                }
                val buf = ByteArray(RAND_BLOCK_SIZE)
                val rand = Random(0x5678)
                val start = System.nanoTime()
                repeat(RAND_ITERATIONS) {
                    val pos = (rand.nextLong() and Long.MAX_VALUE) % (RAND_FILE_SIZE - RAND_BLOCK_SIZE)
                    raf.seek(pos)
                    raf.readFully(buf)
                }
                val elapsed = (System.nanoTime() - start) / 1e9
                return RAND_ITERATIONS / elapsed
            }
        } finally {
            file.delete()
        }
    }

    // ==================== 设备信息 ====================

    /** 收集设备与应用信息，用于页面展示与报告头部 */
    fun collectDeviceInfo(context: Context): String {
        val sb = StringBuilder()
        sb.append(context.getString(R.string.performance_benchmark_device_model)).append(": ")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append(context.getString(R.string.performance_benchmark_device_soc)).append(": ")
            .append(readProcCpuInfo("Hardware")
                .ifEmpty { readProcCpuInfo("model name") }
                .ifEmpty { readProcCpuInfo("Processor") }
                .ifEmpty { Build.HARDWARE }).append('\n')
        val cores = readCpuCoreCount()
        sb.append(context.getString(R.string.performance_benchmark_device_cores)).append(": ")
            .append(cores).append('\n')
        val freqKhz = readMaxCpuFreqKhz()
        if (freqKhz > 0) {
            sb.append(context.getString(R.string.performance_benchmark_device_max_freq)).append(": ")
                .append(context.getString(R.string.performance_benchmark_mhz_format, freqKhz / 1000.0)).append('\n')
        }
        sb.append(context.getString(R.string.performance_benchmark_device_ram)).append(": ")
            .append(context.getString(R.string.performance_benchmark_mb_format, readTotalRamBytes(context) / 1024.0 / 1024.0)).append('\n')
        val freeBytes = readFreeStorageBytes()
        if (freeBytes >= 0) {
            sb.append(context.getString(R.string.performance_benchmark_device_storage_free)).append(": ")
                .append(context.getString(R.string.performance_benchmark_mb_format, freeBytes / 1024.0 / 1024.0)).append('\n')
        }
        sb.append(context.getString(R.string.performance_benchmark_device_android)).append(": ")
            .append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append(context.getString(R.string.performance_benchmark_device_app_version)).append(": ")
            .append(BuildConfig.VERSION_NAME).append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n")
        return sb.toString()
    }

    private fun readProcCpuInfo(key: String): String {
        return try {
            File("/proc/cpuinfo").readText().lineSequence().firstOrNull { it.startsWith(key) }
                ?.substringAfter(':')?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun readCpuCoreCount(): Int {
        try {
            val online = File("/sys/devices/system/cpu/online").readText().trim()
            val range = online.split('-')
            if (range.size == 2) {
                return range[1].toInt() - range[0].toInt() + 1
            }
        } catch (e: Exception) {
            // ignore
        }
        return Runtime.getRuntime().availableProcessors()
    }

    private fun readMaxCpuFreqKhz(): Long {
        return try {
            File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
                .readText().trim().toLong()
        } catch (e: Exception) {
            -1
        }
    }

    private fun readTotalRamBytes(context: Context): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem
        } catch (e: Exception) {
            Runtime.getRuntime().maxMemory()
        }
    }

    private fun readFreeStorageBytes(): Long {
        return try {
            val path = Environment.getExternalStorageDirectory()
            val stat = StatFs(path.absolutePath)
            stat.availableBytes
        } catch (e: Exception) {
            -1
        }
    }

    // ==================== 报告导出 ====================

    /**
     * 导出纯文本报告到 SDCard/EhViewer/benchmark/。
     * @return 导出的文件，失败返回 null
     */
    fun exportReport(context: Context, deviceInfo: String, results: List<BenchmarkResult>): File? {
        val dir = AppConfig.getExternalBenchmarkDir() ?: run {
            Log.e(TAG, "Cannot get benchmark directory")
            return null
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create benchmark directory: ${dir.absolutePath}")
            return null
        }

        val fileStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val displayStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val file = File(dir, "benchmark_${fileStamp.format(Date())}.txt")
        try {
            FileWriter(file, false).use { writer ->
                writer.write(context.getString(R.string.performance_benchmark_report_header_title))
                writer.append('\n')
                writer.write(context.getString(R.string.performance_benchmark_report_time, displayStamp.format(Date())))
                writer.append('\n')
                writer.write(context.getString(R.string.performance_benchmark_report_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                writer.append('\n')
                writer.write(context.getString(R.string.performance_benchmark_report_note))
                writer.append("\n\n")

                writer.write(context.getString(R.string.performance_benchmark_report_header_device))
                writer.append('\n')
                writer.write(deviceInfo)
                writer.append('\n')

                writer.write(context.getString(R.string.performance_benchmark_report_header_results))
                writer.append('\n')
                results.forEachIndexed { index, r ->
                    writer.write("[${index + 1}] ${context.getString(r.nameRes)}\n")
                    writer.write("    ${context.getString(R.string.performance_benchmark_report_value)}: ${r.value} ${context.getString(r.unitRes)}\n")
                    writer.write("    ${context.getString(R.string.performance_benchmark_report_rating)}: ${context.getString(ratingLabelRes(r.rating))}\n")
                    writer.write(
                        "    " + context.getString(
                            R.string.performance_benchmark_reference_format,
                            formatInt(r.refExcellent),
                            formatInt(r.refGood),
                            formatInt(r.refFair),
                            context.getString(r.unitRes),
                        ) + "\n",
                    )
                    writer.write("    ${context.getString(R.string.performance_benchmark_report_meaning)}: ${context.getString(r.summaryRes)}\n\n")
                }
                writer.write(context.getString(R.string.performance_benchmark_report_header_end))
                writer.append('\n')
            }
            Log.i(TAG, "Benchmark report exported: ${file.absolutePath}")
            return file
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM while exporting benchmark report")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export benchmark report", e)
            return null
        }
    }

    // ==================== 格式化 ====================

    private fun formatInt(value: Double): String =
        String.format(Locale.US, "%,.0f", value)

    private fun formatScore(value: Double): String =
        String.format(Locale.US, "%,.1f", value)
}
