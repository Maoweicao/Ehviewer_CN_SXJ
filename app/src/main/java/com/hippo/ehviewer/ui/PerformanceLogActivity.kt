package com.hippo.ehviewer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.service.PerformanceMonitor
import com.hippo.lib.yorozuya.SimpleHandler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PerformanceLogActivity : EhActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var recordCount: TextView
    private lateinit var summaryTitle: TextView
    private lateinit var summaryJvm: TextView
    private lateinit var summaryJvmBar: ProgressBar
    private lateinit var summaryNative: TextView
    private lateinit var summaryThreads: TextView
    private lateinit var summaryDownload: TextView

    private lateinit var adapter: SnapshotAdapter
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val refreshRunnable = Runnable { refreshData() }
    private val REFRESH_INTERVAL_MS = 10_000L

    override fun getThemeResId(theme: Int): Int {
        return when (theme) {
            Settings.THEME_DARK -> R.style.AppTheme_Dark
            Settings.THEME_BLACK -> R.style.AppTheme_Black
            else -> R.style.AppTheme
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_performance_log)

        val actionBar: ActionBar? = supportActionBar
        actionBar?.let {
            it.setTitle(R.string.performance_log_title)
            it.setDisplayHomeAsUpEnabled(true)
        }

        summaryTitle = findViewById(R.id.perf_summary_title)
        summaryJvm = findViewById(R.id.perf_summary_jvm)
        summaryJvmBar = findViewById(R.id.perf_summary_jvm_bar)
        summaryNative = findViewById(R.id.perf_summary_native)
        summaryThreads = findViewById(R.id.perf_summary_threads)
        summaryDownload = findViewById(R.id.perf_summary_download)
        recycler = findViewById(R.id.perf_log_recycler)
        emptyHint = findViewById(R.id.perf_empty_hint)
        recordCount = findViewById(R.id.perf_record_count)

        adapter = SnapshotAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        refreshData()
    }

    override fun onResume() {
        super.onResume()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        stopAutoRefresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_performance_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                exportLog()
                true
            }
            R.id.action_clear -> {
                PerformanceMonitor.clearSnapshots()
                refreshData()
                Toast.makeText(this, R.string.performance_log_clear, Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startAutoRefresh() {
        SimpleHandler.getInstance().postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    private fun stopAutoRefresh() {
        SimpleHandler.getInstance().removeCallbacks(refreshRunnable)
    }

    private fun refreshData() {
        val snapshots = PerformanceMonitor.getSnapshots()
        val latest = PerformanceMonitor.getLatestSnapshot()

        if (latest != null) {
            summaryTitle.text = getString(R.string.performance_log_header_format, snapshots.size)
            summaryJvm.text = getString(
                R.string.performance_log_summary_jvm,
                latest.jvmUsedMB.toInt(),
                latest.jvmMaxMB.toInt(),
                latest.jvmUsagePercent
            )
            summaryJvmBar.progress = latest.jvmUsagePercent
            summaryNative.text = getString(R.string.performance_log_summary_native, latest.nativeHeapMB.toInt())
            summaryThreads.text = getString(R.string.performance_log_summary_threads, latest.threadCount)
            summaryDownload.text = getString(
                R.string.performance_log_summary_download,
                latest.downloadingCount,
                latest.waitingCount
            )
        }

        if (snapshots.isEmpty()) {
            recycler.visibility = View.GONE
            emptyHint.visibility = View.VISIBLE
            recordCount.visibility = View.GONE
        } else {
            recycler.visibility = View.VISIBLE
            emptyHint.visibility = View.GONE
            recordCount.visibility = View.VISIBLE
            recordCount.text = getString(R.string.performance_log_header_format, snapshots.size)
            adapter.setData(snapshots)
        }

        SimpleHandler.getInstance().postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    private fun exportLog() {
        Thread {
            val file = PerformanceMonitor.exportToFile(this@PerformanceLogActivity)
            runOnUiThread {
                if (file != null) {
                    Toast.makeText(
                        this@PerformanceLogActivity,
                        getString(R.string.performance_log_export_success, file.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@PerformanceLogActivity,
                        R.string.performance_log_export_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    // ==================== Adapter ====================

    inner class SnapshotAdapter : RecyclerView.Adapter<SnapshotAdapter.VH>() {

        private var data: List<PerformanceMonitor.Snapshot> = emptyList()

        fun setData(snapshots: List<PerformanceMonitor.Snapshot>) {
            data = snapshots.reversed() // 最新的在前面
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_performance_snapshot, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(data[position], position)
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val snapshotTime: TextView = itemView.findViewById(R.id.snapshot_time)
            private val snapshotIndex: TextView = itemView.findViewById(R.id.snapshot_index)
            private val snapshotJvmBar: ProgressBar = itemView.findViewById(R.id.snapshot_jvm_bar)
            private val snapshotJvmText: TextView = itemView.findViewById(R.id.snapshot_jvm_text)
            private val snapshotNative: TextView = itemView.findViewById(R.id.snapshot_native)
            private val snapshotThreads: TextView = itemView.findViewById(R.id.snapshot_threads)
            private val snapshotActivities: TextView = itemView.findViewById(R.id.snapshot_activities)
            private val snapshotDownloading: TextView = itemView.findViewById(R.id.snapshot_downloading)
            private val snapshotWaiting: TextView = itemView.findViewById(R.id.snapshot_waiting)
            private val snapshotCache: TextView = itemView.findViewById(R.id.snapshot_cache)

            fun bind(snapshot: PerformanceMonitor.Snapshot, position: Int) {
                snapshotTime.text = dateFormat.format(Date(snapshot.timestamp))
                snapshotIndex.text = "#${data.size - position}"

                snapshotJvmBar.progress = snapshot.jvmUsagePercent
                snapshotJvmText.text = "${snapshot.jvmUsedMB}/${snapshot.jvmMaxMB}MB (${snapshot.jvmUsagePercent}%)"

                snapshotNative.text = "Native: ${snapshot.nativeHeapMB}MB"
                snapshotThreads.text = "Threads: ${snapshot.threadCount}"
                snapshotActivities.text = "Act: ${snapshot.activeActivityCount}"

                snapshotDownloading.text = "DL: ${snapshot.downloadingCount}"
                snapshotWaiting.text = "Wait: ${snapshot.waitingCount}"
                val cacheMB = snapshot.conacoCacheSizeKB / 1024
                val cacheMaxMB = snapshot.conacoCacheMaxKB / 1024
                snapshotCache.text = "Cache: ${cacheMB}/${cacheMaxMB}MB"

                // JVM > 80% 标红警告
                val warningColor = if (snapshot.jvmUsagePercent >= 80) {
                    0xFFFF4444.toInt()
                } else if (snapshot.jvmUsagePercent >= 60) {
                    0xFFFFAA00.toInt()
                } else {
                    0xFF4CAF50.toInt()
                }
                snapshotJvmText.setTextColor(warningColor)
            }
        }
    }
}
