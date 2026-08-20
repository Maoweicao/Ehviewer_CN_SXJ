package com.hippo.ehviewer.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBar
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.service.PerformanceBenchmark
import com.hippo.ehviewer.service.PerformanceBenchmark.BenchmarkResult
import com.hippo.ehviewer.service.PerformanceBenchmark.RATING_EXCELLENT
import com.hippo.ehviewer.service.PerformanceBenchmark.RATING_FAIR
import com.hippo.ehviewer.service.PerformanceBenchmark.RATING_GOOD
import com.hippo.ehviewer.service.PerformanceBenchmark.RATING_POOR

class PerformanceBenchmarkActivity : EhActivity() {

    private lateinit var deviceInfoView: TextView
    private lateinit var currentTest: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressArea: View
    private lateinit var resultTitle: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var runButton: Button
    private lateinit var exportButton: Button

    private var running = false
    private var hasResults = false
    private var lastResults: List<BenchmarkResult> = emptyList()

    override fun getThemeResId(theme: Int): Int {
        return when (theme) {
            Settings.THEME_DARK -> R.style.AppTheme_Dark
            Settings.THEME_BLACK -> R.style.AppTheme_Black
            else -> R.style.AppTheme
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_performance_benchmark)

        val actionBar: ActionBar? = supportActionBar
        actionBar?.let {
            it.setTitle(R.string.performance_benchmark_title)
            it.setDisplayHomeAsUpEnabled(true)
        }

        deviceInfoView = findViewById(R.id.bench_device_info)
        currentTest = findViewById(R.id.bench_current_test)
        progressBar = findViewById(R.id.bench_progress)
        progressArea = findViewById(R.id.bench_progress_area)
        resultTitle = findViewById(R.id.bench_result_title)
        resultsContainer = findViewById(R.id.bench_results_container)
        scrollView = findViewById(R.id.bench_scroll)
        runButton = findViewById(R.id.bench_run_button)
        exportButton = findViewById(R.id.bench_export_button)

        runButton.setOnClickListener { startBenchmark() }
        exportButton.setOnClickListener { exportReport() }

        loadDeviceInfo()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadDeviceInfo() {
        Thread {
            val info = PerformanceBenchmark.collectDeviceInfo(this@PerformanceBenchmarkActivity)
            runOnUiThread { deviceInfoView.text = info }
        }.start()
    }

    private fun startBenchmark() {
        if (running) return
        running = true
        hasResults = false
        resultTitle.visibility = View.GONE
        resultsContainer.removeAllViews()
        runButton.isEnabled = false
        exportButton.isEnabled = false
        progressArea.visibility = View.VISIBLE
        progressBar.max = 100
        progressBar.progress = 0

        Thread {
            val results = PerformanceBenchmark.runBenchmarks(
                onTestStarted = { nameRes ->
                    runOnUiThread {
                        currentTest.setText(getString(R.string.performance_benchmark_running, getString(nameRes)))
                    }
                },
                onTestFinished = { result ->
                    runOnUiThread {
                        resultsContainer.addView(buildResultCard(result))
                        resultTitle.visibility = View.VISIBLE
                        scrollToBottom()
                    }
                },
                onProgress = { fraction ->
                    runOnUiThread { progressBar.progress = (fraction * 100).toInt() }
                },
            )
            runOnUiThread {
                running = false
                progressArea.visibility = View.GONE
                runButton.isEnabled = true
                exportButton.isEnabled = results.isNotEmpty()
                hasResults = results.isNotEmpty()
                lastResults = results
                if (results.isNotEmpty()) {
                    resultsContainer.addView(buildSummaryCard(results))
                    scrollToBottom()
                    Toast.makeText(this@PerformanceBenchmarkActivity, R.string.performance_benchmark_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@PerformanceBenchmarkActivity, R.string.performance_benchmark_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun buildResultCard(result: BenchmarkResult): View {
        val card = LayoutInflater.from(this).inflate(R.layout.item_benchmark_result, resultsContainer, false)
        card.findViewById<TextView>(R.id.bench_result_name).text = getString(result.nameRes)
        card.findViewById<TextView>(R.id.bench_result_rating).apply {
            text = getString(PerformanceBenchmark.ratingLabelRes(result.rating))
            backgroundTintList = ColorStateList.valueOf(ratingColor(result.rating))
        }
        card.findViewById<TextView>(R.id.bench_result_value).text = result.value
        card.findViewById<TextView>(R.id.bench_result_unit).text = getString(result.unitRes)
        card.findViewById<TextView>(R.id.bench_result_reference).text = getString(
            R.string.performance_benchmark_reference_format,
            formatReference(result.refExcellent),
            formatReference(result.refGood),
            formatReference(result.refFair),
            getString(result.unitRes),
        )
        card.findViewById<TextView>(R.id.bench_result_meaning).text = getString(result.summaryRes)
        return card
    }

    private fun formatReference(value: Double): String {
        return String.format(java.util.Locale.US, "%,.0f", value)
    }

    private fun buildSummaryCard(results: List<BenchmarkResult>): View {
        val card = LayoutInflater.from(this).inflate(R.layout.item_benchmark_summary, resultsContainer, false)
        card.findViewById<TextView>(R.id.bench_summary_title).setText(R.string.performance_benchmark_summary_title)

        val avg = results.map { it.rating }.average()
        val level = when {
            avg >= 2.5 -> RATING_EXCELLENT
            avg >= 1.8 -> RATING_GOOD
            avg >= 1.0 -> RATING_FAIR
            else -> RATING_POOR
        }
        val levelLabelRes = summaryLevelLabelRes(level)
        val levelDescRes = summaryLevelDescRes(level)
        card.findViewById<TextView>(R.id.bench_summary_level).apply {
            text = getString(levelLabelRes)
            setTextColor(ratingColor(level))
        }
        card.findViewById<TextView>(R.id.bench_summary_desc).setText(levelDescRes)

        val excellent = results.count { it.rating == RATING_EXCELLENT }
        val good = results.count { it.rating == RATING_GOOD }
        val fair = results.count { it.rating == RATING_FAIR }
        val poor = results.count { it.rating == RATING_POOR }
        bindSummaryChip(card, R.id.bench_summary_rating_excellent, excellent, R.string.performance_benchmark_rating_excellent, RATING_EXCELLENT)
        bindSummaryChip(card, R.id.bench_summary_rating_good, good, R.string.performance_benchmark_rating_good, RATING_GOOD)
        bindSummaryChip(card, R.id.bench_summary_rating_fair, fair, R.string.performance_benchmark_rating_fair, RATING_FAIR)
        bindSummaryChip(card, R.id.bench_summary_rating_poor, poor, R.string.performance_benchmark_rating_poor, RATING_POOR)
        return card
    }

    private fun bindSummaryChip(card: View, id: Int, count: Int, labelRes: Int, rating: Int) {
        card.findViewById<TextView>(id).apply {
            text = getString(R.string.performance_benchmark_summary_chip_format, getString(labelRes), count)
            backgroundTintList = ColorStateList.valueOf(ratingColor(rating))
        }
    }

    private fun summaryLevelLabelRes(level: Int): Int = when (level) {
        RATING_EXCELLENT -> R.string.performance_benchmark_summary_level_excellent
        RATING_GOOD -> R.string.performance_benchmark_summary_level_good
        RATING_FAIR -> R.string.performance_benchmark_summary_level_fair
        else -> R.string.performance_benchmark_summary_level_poor
    }

    private fun summaryLevelDescRes(level: Int): Int = when (level) {
        RATING_EXCELLENT -> R.string.performance_benchmark_summary_desc_excellent
        RATING_GOOD -> R.string.performance_benchmark_summary_desc_good
        RATING_FAIR -> R.string.performance_benchmark_summary_desc_fair
        else -> R.string.performance_benchmark_summary_desc_poor
    }

    private fun ratingColor(rating: Int): Int = when (rating) {
        RATING_EXCELLENT -> 0xFF2E7D32.toInt()
        RATING_GOOD -> 0xFF1976D2.toInt()
        RATING_FAIR -> 0xFFF57C00.toInt()
        else -> 0xFFD32F2F.toInt()
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun exportReport() {
        if (running) return
        running = true
        exportButton.isEnabled = false
        val deviceInfo = deviceInfoView.text?.toString().orEmpty()

        Thread {
            val file = PerformanceBenchmark.exportReport(
                this@PerformanceBenchmarkActivity,
                deviceInfo,
                lastResults,
            )
            runOnUiThread {
                running = false
                exportButton.isEnabled = hasResults
                if (file != null) {
                    Toast.makeText(
                        this@PerformanceBenchmarkActivity,
                        getString(R.string.performance_benchmark_export_success, file.absolutePath),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        this@PerformanceBenchmarkActivity,
                        R.string.performance_benchmark_export_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }.start()
    }
}
