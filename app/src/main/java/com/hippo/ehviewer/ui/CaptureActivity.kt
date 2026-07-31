package com.hippo.ehviewer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.network.TrafficCaptureManager
import com.hippo.ehviewer.service.CaptureService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class CaptureActivity : EhActivity() {

    private lateinit var captureSwitch: Switch
    private lateinit var captureStatus: TextView
    private lateinit var statsCard: View
    private lateinit var statsRequests: TextView
    private lateinit var statsDuration: TextView
    private lateinit var statsSent: TextView
    private lateinit var statsReceived: TextView
    private lateinit var btnSave: Button
    private lateinit var harFileList: RecyclerView
    private lateinit var emptyState: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val statsUpdateRunnable = object : Runnable {
        override fun run() {
            if (TrafficCaptureManager.isActive()) {
                updateStats()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun getThemeResId(theme: Int): Int {
        return when (theme) {
            Settings.THEME_DARK -> R.style.AppTheme_Dark
            Settings.THEME_BLACK -> R.style.AppTheme_Black
            else -> R.style.AppTheme
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_capture)

        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setTitle(R.string.capture_title)
            actionBar.setDisplayHomeAsUpEnabled(true)
        }

        captureSwitch = findViewById(R.id.capture_switch)
        captureStatus = findViewById(R.id.capture_status)
        statsCard = findViewById(R.id.stats_card)
        statsRequests = findViewById(R.id.stats_requests)
        statsDuration = findViewById(R.id.stats_duration)
        statsSent = findViewById(R.id.stats_sent)
        statsReceived = findViewById(R.id.stats_received)
        btnSave = findViewById(R.id.btn_save)
        harFileList = findViewById(R.id.har_file_list)
        emptyState = findViewById(R.id.empty_state)

        harFileList.layoutManager = LinearLayoutManager(this)

        captureSwitch.setOnCheckedChangeListener(null)
        captureSwitch.isChecked = Settings.getTrafficCaptureEnabled() && TrafficCaptureManager.isActive()
        updateStatusDisplay()

        captureSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                CaptureService.start(this)
                Settings.putTrafficCaptureEnabled(true)
                handler.postDelayed(statsUpdateRunnable, 1000)
            } else {
                autoSaveAndStop()
            }
            updateStatusDisplay()
        }

        btnSave.setOnClickListener {
            if (TrafficCaptureManager.isActive()) {
                CaptureService.saveAndStop(this)
                handler.removeCallbacks(statsUpdateRunnable)
                Settings.putTrafficCaptureEnabled(false)
                captureSwitch.setOnCheckedChangeListener(null)
                captureSwitch.isChecked = false
                captureSwitch.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        CaptureService.start(this)
                        Settings.putTrafficCaptureEnabled(true)
                        handler.postDelayed(statsUpdateRunnable, 1000)
                    } else {
                        autoSaveAndStop()
                    }
                    updateStatusDisplay()
                }
                updateStatusDisplay()
                handler.postDelayed({ loadHarFiles() }, 500)
                Toast.makeText(this, R.string.capture_saved, Toast.LENGTH_SHORT).show()
            }
        }

        loadHarFiles()
    }

    override fun onResume() {
        super.onResume()
        val settingsEnabled = Settings.getTrafficCaptureEnabled()
        val actuallyActive = TrafficCaptureManager.isActive()

        if (settingsEnabled && !actuallyActive) {
            Settings.putTrafficCaptureEnabled(false)
        }

        captureSwitch.setOnCheckedChangeListener(null)
        captureSwitch.isChecked = actuallyActive
        captureSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                CaptureService.start(this)
                Settings.putTrafficCaptureEnabled(true)
                handler.postDelayed(statsUpdateRunnable, 1000)
            } else {
                autoSaveAndStop()
            }
            updateStatusDisplay()
        }

        updateStatusDisplay()
        if (actuallyActive) {
            handler.postDelayed(statsUpdateRunnable, 1000)
        }
        loadHarFiles()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statsUpdateRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsUpdateRunnable)
        super.onDestroy()
    }

    private fun autoSaveAndStop() {
        if (TrafficCaptureManager.isActive()) {
            CaptureService.saveAndStop(this)
            handler.postDelayed({ loadHarFiles() }, 500)
        } else {
            CaptureService.stop(this)
        }
        Settings.putTrafficCaptureEnabled(false)
        handler.removeCallbacks(statsUpdateRunnable)
    }

    private fun updateStatusDisplay() {
        if (TrafficCaptureManager.isActive()) {
            captureStatus.setText(R.string.capture_status_active)
            statsCard.visibility = View.VISIBLE
            btnSave.isEnabled = true
            updateStats()
        } else {
            captureStatus.setText(R.string.capture_status_idle)
            statsCard.visibility = View.GONE
            btnSave.isEnabled = false
        }
    }

    private fun updateStats() {
        val count = TrafficCaptureManager.getEntryCount()
        val durationMs = TrafficCaptureManager.getCaptureDurationMs()
        val sent = TrafficCaptureManager.getTotalBytesSent()
        val received = TrafficCaptureManager.getTotalBytesReceived()

        statsRequests.text = getString(R.string.capture_stats_requests, count)

        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        statsDuration.text = getString(R.string.capture_stats_duration, minutes, secs)

        statsSent.text = getString(R.string.capture_stats_sent, TrafficCaptureManager.formatBytes(sent))
        statsReceived.text = getString(R.string.capture_stats_received, TrafficCaptureManager.formatBytes(received))
    }

    private fun loadHarFiles() {
        val dir = TrafficCaptureManager.getCaptureDir()
        val files = dir.listFiles { f -> f.extension == "har" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            harFileList.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            harFileList.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            harFileList.adapter = HarFileAdapter(files)
        }
    }

    private inner class HarFileAdapter(
        private val files: List<File>
    ) : RecyclerView.Adapter<HarFileAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val filename: TextView = itemView.findViewById(R.id.har_filename)
            val filesize: TextView = itemView.findViewById(R.id.har_filesize)
            val btnShare: ImageButton = itemView.findViewById(R.id.btn_share)
            val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_har_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

            holder.filename.text = file.name
            holder.filesize.text = getString(
                R.string.capture_file_info,
                TrafficCaptureManager.formatBytes(file.length()),
                dateFormat.format(file.lastModified())
            )

            holder.btnShare.setOnClickListener { shareHarFile(file) }

            holder.btnDelete.setOnClickListener {
                AlertDialog.Builder(this@CaptureActivity)
                    .setTitle(R.string.capture_delete_title)
                    .setMessage(getString(R.string.capture_delete_confirm, file.name))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (file.delete()) {
                            loadHarFiles()
                            Toast.makeText(this@CaptureActivity, R.string.capture_deleted, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        override fun getItemCount(): Int = files.size
    }

    private fun shareHarFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "EhViewer Network Capture HAR")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.capture_share)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.capture_share_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        @Suppress("DEPRECATION")
        onBackPressed()
        return true
    }
}
