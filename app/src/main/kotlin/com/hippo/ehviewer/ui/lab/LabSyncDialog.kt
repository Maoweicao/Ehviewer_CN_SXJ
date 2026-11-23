/*
 * Copyright 2026 EhViewer Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.lab

import android.app.Activity
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.lab.sync.LabSyncDirection
import com.hippo.ehviewer.lab.sync.LabTasks
import com.hippo.ehviewer.task.LabMetadataSyncTask

/**
 * 手动设备间快速同步（协议 §5.30）。
 *
 * <p>只列出已配对（有 presharedKey）且有地址的设备；方向按次选择，
 * 默认值来自实验室设置。图片不随元数据自动复制，勾选「同时同步图片文件」后
 * 才会在同步完成后从对端逐页补齐本次涉及的画廊。
 */
object LabSyncDialog {

    private val DIRECTIONS = arrayOf(
        LabSyncDirection.BIDIRECTIONAL,
        LabSyncDirection.PULL,
        LabSyncDirection.PUSH,
    )

    @JvmStatic
    fun show(activity: Activity) {
        val peers = TrustedPeerStore.getInstance(activity).list()
            .filter { !it.presharedKey.isNullOrEmpty() && !it.host.isNullOrEmpty() && it.port > 0 }
        if (peers.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.lab_sync_title)
                .setMessage(R.string.lab_sync_no_peer)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val labels = peers.map { "${it.deviceName}\n${it.host}:${it.port}" }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.lab_sync_select_peer)
            .setItems(labels) { _, which -> showOptions(activity, peers[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 方向 + 「同时同步图片文件」复选框。
     */
    private fun showOptions(activity: Activity, peer: TrustedPeer) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(activity, 20)
            setPadding(padding, dp(activity, 8), padding, 0)
        }
        val group = RadioGroup(activity)
        val entries = activity.resources.getStringArray(R.array.lab_sync_direction_entries)
        val default = DIRECTIONS.indexOfFirst { it.wire == Settings.getLabSyncDirection() }
            .coerceAtLeast(0)
        entries.forEachIndexed { index, label ->
            val button = RadioButton(activity).apply {
                id = 10_000 + index
                text = label
                isChecked = index == default
            }
            group.addView(button)
        }
        container.addView(group)

        val syncFiles = CheckBox(activity).apply {
            text = activity.getString(R.string.lab_sync_sync_files)
            isChecked = false
        }
        container.addView(syncFiles)

        val desc = TextView(activity).apply {
            text = activity.getString(R.string.lab_sync_sync_files_desc)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(activity, 4), 0, 0, 0)
        }
        container.addView(desc)

        group.setOnCheckedChangeListener { _, checkedId ->
            val pushIndex = 10_000 + DIRECTIONS.indexOf(LabSyncDirection.PUSH)
            val isPush = checkedId == pushIndex
            syncFiles.isEnabled = !isPush
            if (isPush) syncFiles.isChecked = false
        }
        if (default == DIRECTIONS.indexOf(LabSyncDirection.PUSH)) {
            syncFiles.isEnabled = false
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.lab_sync_title)
            .setView(container)
            .setPositiveButton(R.string.lab_sync_start) { _, _ ->
                val checked = group.checkedRadioButtonId
                val index = if (checked >= 0) checked - 10_000 else default
                val direction = DIRECTIONS.getOrElse(index) { DIRECTIONS[0] }
                start(activity, peer, direction, syncFiles.isChecked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun start(
        activity: Activity,
        peer: TrustedPeer,
        direction: LabSyncDirection,
        syncFiles: Boolean,
    ) {
        val padding = dp(activity, 24)
        val status = TextView(activity).apply {
            setPadding(0, 0, 0, dp(activity, 12))
            setText(R.string.lab_sync_running)
        }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(activity, 8), padding, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(status)
            addView(progress)
        }
        @Suppress("DEPRECATION")
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.lab_sync_title)
            .setView(container)
            .setNegativeButton(R.string.lab_sync_hide) { d, _ -> d.dismiss() }
            .show()

        // 同步本身提交为正式后台任务：进度、日志和结果都会出现在「后台任务」，
        // 关闭这个对话框或离开页面也不会中断。
        val task = LabMetadataSyncTask(activity, peer.deviceId, direction, syncFiles)
        task.progressCallback = { phase, current, total, detail ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                status.text = detail ?: phase
                if (total > 0 && current >= 0) {
                    progress.isIndeterminate = false
                    progress.max = total
                    progress.progress = current
                } else {
                    progress.isIndeterminate = true
                }
            }
        }
        task.finishCallback = { _, message ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                dialog.dismiss()
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
        }
        if (!LabTasks.submit(task)) {
            dialog.dismiss()
            Toast.makeText(activity, R.string.lab_sync_busy, Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(activity: Activity, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()
}
