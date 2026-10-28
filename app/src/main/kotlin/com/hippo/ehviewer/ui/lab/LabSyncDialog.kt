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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.lab.sync.LabSyncDirection
import com.hippo.ehviewer.lab.sync.LabSyncEngine

/**
 * 手动设备间快速同步（协议 §5.30）。
 *
 * <p>只列已配对（有 presharedKey）且有地址的设备；方向按次选择，
 * 默认值来自实验室设置。图片不复制，按需从对端读取。
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
            .setItems(labels) { _, which -> showDirection(activity, peers[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDirection(activity: Activity, peer: TrustedPeer) {
        val default = DIRECTIONS.indexOfFirst { it.wire == Settings.getLabSyncDirection() }
            .coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(R.string.lab_sync_direction)
            .setSingleChoiceItems(R.array.lab_sync_direction_entries, default) { dialog, which ->
                dialog.dismiss()
                start(activity, peer, DIRECTIONS[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun start(activity: Activity, peer: TrustedPeer, direction: LabSyncDirection) {
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

        Thread({
            val outcome = LabSyncEngine.getInstance(activity).requestSync(peer, direction) {
                    phase, current, total, detail ->
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
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
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                dialog.dismiss()
                val message = if (outcome.success) {
                    activity.getString(R.string.lab_sync_done, outcome.applied)
                } else {
                    activity.getString(R.string.lab_sync_failed, outcome.error.orEmpty())
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
        }, "LabSyncManual").apply { isDaemon = true }.start()
    }

    private fun dp(activity: Activity, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()
}
