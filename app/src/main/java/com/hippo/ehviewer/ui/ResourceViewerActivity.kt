package com.hippo.ehviewer.ui

import android.os.Bundle
import android.os.Debug
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.network.NetworkHealthTracker
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 资源查看器：实时展示当前进程的资源占用（内存/线程/下载/图片缓存/网络/线程池），
 * 并提供线程列表浏览与导出，方便后续进行资源优化。
 *
 * - 头部指标卡与线程列表每 [REFRESH_INTERVAL_MS] 刷新一次，仅在页面可见时采样；
 * - 线程采样在后台线程执行（[Thread.getAllStackTraces] 稍重，避免卡 UI）；
 * - 支持按线程名过滤、点击查看完整堆栈、菜单导出全量线程及堆栈到文本文件。
 */
class ResourceViewerActivity : EhActivity() {

    companion object {
        private const val TAG = "ResourceViewer"
        private const val REFRESH_INTERVAL_MS = 2000L
    }

    private lateinit var summaryTitle: TextView
    private lateinit var summaryJvm: TextView
    private lateinit var summaryJvmBar: ProgressBar
    private lateinit var summaryRow1: TextView
    private lateinit var summaryRow2: TextView
    private lateinit var summaryNetwork: TextView
    private lateinit var summaryPools: TextView
    private lateinit var filterInput: EditText
    private lateinit var threadCountText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyHint: TextView

    private lateinit var adapter: ThreadAdapter

    // 最近一次采样的全量线程（过滤前的数据，供实时过滤）
    private var allThreads: List<ThreadInfo> = emptyList()

    @Volatile
    private var sampling = false

    private var sampler: Thread? = null

    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun getThemeResId(theme: Int): Int {
        return when (theme) {
            Settings.THEME_DARK -> R.style.AppTheme_Dark
            Settings.THEME_BLACK -> R.style.AppTheme_Black
            else -> R.style.AppTheme
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_resource_viewer)

        val actionBar: ActionBar? = supportActionBar
        actionBar?.let {
            it.setTitle(R.string.resource_viewer_title)
            it.setDisplayHomeAsUpEnabled(true)
        }

        summaryTitle = findViewById(R.id.resource_summary_title)
        summaryJvm = findViewById(R.id.resource_summary_jvm)
        summaryJvmBar = findViewById(R.id.resource_summary_jvm_bar)
        summaryRow1 = findViewById(R.id.resource_summary_row1)
        summaryRow2 = findViewById(R.id.resource_summary_row2)
        summaryNetwork = findViewById(R.id.resource_summary_network)
        summaryPools = findViewById(R.id.resource_summary_pools)
        filterInput = findViewById(R.id.resource_thread_filter)
        threadCountText = findViewById(R.id.resource_thread_count)
        recycler = findViewById(R.id.resource_thread_recycler)
        emptyHint = findViewById(R.id.resource_empty_hint)

        adapter = ThreadAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        filterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.refresh()
            }
        })

        refreshOnce()
    }

    override fun onResume() {
        super.onResume()
        startSampling()
    }

    override fun onPause() {
        stopSampling()
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_resource_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_export_threads -> {
                exportThreads()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ==================== 采样 ====================

    private fun startSampling() {
        if (sampling && sampler?.isAlive == true) return
        sampling = true
        sampler = Thread({
            while (sampling) {
                try {
                    val snapshot = collectSample()
                    runOnUiThread { render(snapshot) }
                } catch (e: Throwable) {
                    // 采样失败不中断循环，下一周期重试
                }
                try {
                    Thread.sleep(REFRESH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "ResourceViewer-Sampler").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun stopSampling() {
        sampling = false
        val t = sampler
        if (t != null && t.isAlive) {
            t.interrupt()
        }
        sampler = null
    }

    private fun refreshOnce() {
        Thread({
            val snapshot = collectSample()
            runOnUiThread { render(snapshot) }
        }, "ResourceViewer-Once").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    // ==================== 数据采集 ====================

    private data class HeaderStats(
        val jvmUsedMB: Long,
        val jvmMaxMB: Long,
        val jvmPercent: Int,
        val nativeMB: Long,
        val threadCount: Int,
        val activities: Int,
        val downloading: Int,
        val waiting: Int,
        val cacheUsedMB: Int,
        val cacheMaxMB: Int,
        val limiterActive: Int,
        val limiterLimit: Int,
        val netInflight: Int,
        val netConnections: Int,
        val netIdle: Int,
        val netSlow: Long,
        val netTimeout: Long,
        val pools: String
    )

    private data class ThreadInfo(
        val name: String,
        val id: Long,
        val priority: Int,
        val isDaemon: Boolean,
        val state: Thread.State,
        val stack: Array<StackTraceElement>
    ) {
        val stackDepth: Int get() = stack.size
    }

    private fun collectSample(): Sample {
        val rt = Runtime.getRuntime()
        val jvmUsed = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val jvmMax = rt.maxMemory() / (1024 * 1024)
        val jvmPercent = if (jvmMax > 0) (jvmUsed * 100 / jvmMax).toInt().coerceIn(0, 100) else 0
        val nativeMB = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        var activities = 0
        var downloading = 0
        var waiting = 0
        var cacheUsed = 0
        var cacheMax = 0
        try {
            val app = EhApplication.getInstance()
            if (app != null) {
                activities = app.activeActivityCount
            }
        } catch (_: Throwable) {
        }
        try {
            val dm = EhApplication.getDownloadManager()
            downloading = dm.downloadingCount
            waiting = dm.waitingCount
        } catch (_: Throwable) {
        }
        try {
            val app = EhApplication.getInstance()
            if (app != null) {
                val cache = EhApplication.getConaco(app).beerBelly.memoryCache
                if (cache != null) {
                    cacheUsed = cache.size() / (1024 * 1024)
                    cacheMax = cache.maxSize() / (1024 * 1024)
                }
            }
        } catch (_: Throwable) {
        }

        var limiterActive = 0
        var limiterLimit = 0
        var netInflight = 0
        var netConnections = -1
        var netIdle = -1
        var netSlow = 0L
        var netTimeout = 0L
        try {
            limiterActive = EhEngine.getNetworkLimiterActive()
            limiterLimit = EhEngine.getNetworkConcurrencyLimit()
        } catch (_: Throwable) {
        }
        try {
            val net = NetworkHealthTracker.getSnapshot()
            netInflight = net.inflight
            netConnections = net.connectionCount
            netIdle = net.idleConnectionCount
            netSlow = net.slowCalls
            netTimeout = net.timeoutCalls
        } catch (_: Throwable) {
        }

        var pools = ""
        try {
            pools = com.hippo.ehviewer.BackgroundTaskManager.getInstance().describeExecutorStats()
        } catch (_: Throwable) {
        }

        val header = HeaderStats(
            jvmUsedMB = jvmUsed, jvmMaxMB = jvmMax, jvmPercent = jvmPercent,
            nativeMB = nativeMB,
            threadCount = Thread.activeCount(),
            activities = activities,
            downloading = downloading, waiting = waiting,
            cacheUsedMB = cacheUsed, cacheMaxMB = cacheMax,
            limiterActive = limiterActive, limiterLimit = limiterLimit,
            netInflight = netInflight, netConnections = netConnections, netIdle = netIdle,
            netSlow = netSlow, netTimeout = netTimeout,
            pools = pools
        )

        // 采集线程列表（getAllStackTraces 稍重，放后台线程）
        val threads = ArrayList<ThreadInfo>(128)
        try {
            for ((t, stack) in Thread.getAllStackTraces()) {
                threads.add(
                    ThreadInfo(
                        name = t.name,
                        id = t.id,
                        priority = t.priority,
                        isDaemon = t.isDaemon,
                        state = t.state,
                        stack = stack
                    )
                )
            }
            threads.sortWith(
                compareBy(
                    { stateOrder(it.state) },
                    { it.name.lowercase(Locale.ROOT) }
                )
            )
        } catch (_: Throwable) {
        }

        return Sample(header, threads)
    }

    private fun stateOrder(state: Thread.State): Int = when (state) {
        Thread.State.RUNNABLE -> 0
        Thread.State.BLOCKED -> 1
        Thread.State.WAITING, Thread.State.TIMED_WAITING -> 2
        else -> 3
    }

    // ==================== 渲染 ====================

    private fun render(sample: Sample) {
        val h = sample.header
        summaryTitle.text = getString(R.string.resource_viewer_header_title, timeFormat.format(Date()))
        summaryJvm.text = getString(R.string.resource_viewer_stat_jvm, h.jvmUsedMB.toInt(), h.jvmMaxMB.toInt(), h.jvmPercent)
        summaryJvmBar.progress = h.jvmPercent
        summaryJvm.setTextColor(
            when {
                h.jvmPercent >= 80 -> 0xFFFF4444.toInt()
                h.jvmPercent >= 60 -> 0xFFFFAA00.toInt()
                else -> 0xFF4CAF50.toInt()
            }
        )
        summaryRow1.text = getString(
            R.string.resource_viewer_stat_row1,
            h.nativeMB.toInt(), h.threadCount, h.activities,
            h.downloading, h.waiting, h.cacheUsedMB, h.cacheMaxMB
        )
        summaryRow2.text = getString(
            R.string.resource_viewer_stat_row2,
            h.limiterActive, h.limiterLimit
        )
        summaryNetwork.text = getString(
            R.string.resource_viewer_stat_network,
            h.netInflight, h.netConnections, h.netIdle, h.netSlow, h.netTimeout
        )
        summaryPools.text = getString(R.string.resource_viewer_pools, h.pools)

        allThreads = sample.threads
        adapter.refresh()
    }

    // ==================== 导出 ====================

    private fun exportThreads() {
        Thread({
            val file = writeThreadDump()
            runOnUiThread {
                if (file != null) {
                    Toast.makeText(
                        this@ResourceViewerActivity,
                        getString(R.string.resource_viewer_export_success, file.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@ResourceViewerActivity,
                        R.string.resource_viewer_export_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }, "ResourceViewer-Export").apply {
            isDaemon = true
            start()
        }
    }

    private fun writeThreadDump(): File? {
        val dir = AppConfig.getExternalPerformanceDir() ?: return null
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "threads-${fileDateFormat.format(Date())}.txt")

        try {
            val sample = collectSample()
            val h = sample.header
            FileWriter(file).use { writer ->
                writer.write("===== EhViewer 线程列表导出 =====\n")
                writer.write("导出时间: ${timeFormat.format(Date())}\n\n")

                writer.write("===== 资源快照 =====\n")
                writer.write("JVM内存: ${h.jvmUsedMB}/${h.jvmMaxMB} MB (${h.jvmPercent}%)\n")
                writer.write("Native: ${h.nativeMB} MB\n")
                writer.write("线程总数: ${h.threadCount}\n")
                writer.write("Activity: ${h.activities}\n")
                writer.write("下载中: ${h.downloading}  等待中: ${h.waiting}\n")
                writer.write("图片缓存: ${h.cacheUsedMB}/${h.cacheMaxMB} MB\n")
                writer.write("网络限流: ${h.limiterActive}/${h.limiterLimit}\n")
                writer.write("网络: 在飞=${h.netInflight} 连接池=${h.netConnections}(空闲=${h.netIdle}) 慢请求=${h.netSlow} 超时=${h.netTimeout}\n")
                writer.write("线程池: ${h.pools}\n\n")

                writer.write("===== 线程列表 (${sample.threads.size}) =====\n")
                for (t in sample.threads) {
                    writer.write(
                        "\"${t.name}\" id=${t.id} prio=${t.priority} state=${t.state} daemon=${t.isDaemon} stack=${t.stackDepth}\n"
                    )
                    for (element in t.stack) {
                        writer.write("    at $element\n")
                    }
                    writer.write("\n")
                }
                writer.write("===== 导出结束 =====\n")
            }
            return file
        } catch (e: OutOfMemoryError) {
            return null
        } catch (e: Throwable) {
            return null
        }
    }

    // ==================== Adapter ====================

    private data class Sample(val header: HeaderStats, val threads: List<ThreadInfo>)

    private inner class ThreadAdapter : RecyclerView.Adapter<ThreadAdapter.VH>() {

        private var filtered: List<ThreadInfo> = emptyList()

        fun refresh() {
            val query = if (::filterInput.isInitialized) {
                filterInput.text?.toString()?.trim() ?: ""
            } else ""
            filtered = if (query.isEmpty()) {
                allThreads
            } else {
                allThreads.filter { it.name.contains(query, ignoreCase = true) }
            }
            notifyDataSetChanged()

            val runnableCount = filtered.count { it.state == Thread.State.RUNNABLE }
            threadCountText.text = getString(
                R.string.resource_viewer_thread_count_format, filtered.size, runnableCount
            )
            if (filtered.isEmpty()) {
                recycler.visibility = View.GONE
                emptyHint.visibility = View.VISIBLE
            } else {
                recycler.visibility = View.VISIBLE
                emptyHint.visibility = View.GONE
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_thread_row, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = filtered.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(filtered[position])
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.thread_name)
            private val state: TextView = itemView.findViewById(R.id.thread_state)
            private val detail: TextView = itemView.findViewById(R.id.thread_detail)

            fun bind(info: ThreadInfo) {
                name.text = info.name
                state.text = info.state.name
                state.setTextColor(stateColor(info.state))
                detail.text = "id=${info.id} prio=${info.priority} daemon=${info.isDaemon} stack=${info.stackDepth}"
                itemView.setOnClickListener {
                    showStackDialog(info)
                }
            }

            private fun stateColor(state: Thread.State): Int = when (state) {
                Thread.State.RUNNABLE -> 0xFF4CAF50.toInt()
                Thread.State.BLOCKED -> 0xFFFF4444.toInt()
                Thread.State.WAITING, Thread.State.TIMED_WAITING -> 0xFFFFAA00.toInt()
                else -> 0xFF888888.toInt()
            }

            private fun showStackDialog(info: ThreadInfo) {
                val sb = StringBuilder()
                sb.append("\"${info.name}\" id=${info.id} prio=${info.priority}\n")
                sb.append("state=${info.state} daemon=${info.isDaemon}\n\n")
                if (info.stack.isEmpty()) {
                    sb.append(getString(R.string.resource_viewer_no_stack))
                } else {
                    for (element in info.stack) {
                        sb.append("    at ").append(element).append('\n')
                    }
                }
                AlertDialog.Builder(this@ResourceViewerActivity)
                    .setTitle(info.name)
                    .setMessage(sb.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }
}
