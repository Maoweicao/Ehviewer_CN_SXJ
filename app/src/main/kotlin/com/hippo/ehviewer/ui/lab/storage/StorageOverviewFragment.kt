/*
 * Copyright 2025 EhViewer Contributors
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

package com.hippo.ehviewer.ui.lab.storage

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.hippo.ehviewer.R
import com.hippo.ehviewer.local.LocalGalleryManager
import com.hippo.ehviewer.ui.DatabaseViewerActivity
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.local.LocalGalleryActivity
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.ehviewer.widget.StorageDonutChartView
import com.hippo.ehviewer.widget.UsageBarView
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.scene.StageActivity
import java.util.Locale

/**
 * Tab 1：存储概览。
 *
 * 上半部分是总占用的环形图（中心两行显示总容量与已占用）与图例明细，
 * 下半部分是每个分类的卡片，带「清理缓存 / 跳转管理」等操作。
 */
class StorageOverviewFragment : Fragment(R.layout.fragment_storage_overview) {

    private var hasLoaded = false

    override fun onResume() {
        super.onResume()
        if (!hasLoaded) refresh()
    }

    fun refresh() {
        val root = view ?: return
        val context = context ?: return
        root.findViewById<ProgressBar>(R.id.scan_progress)?.visibility = View.VISIBLE
        StorageScanner.scanOverview(context) { result ->
            val currentView = view ?: return@scanOverview
            if (result == null) {
                // 扫描失败：收起进度条，避免一直转圈
                currentView.findViewById<ProgressBar>(R.id.scan_progress)?.visibility = View.GONE
                Toast.makeText(context, R.string.storage_scan_failed, Toast.LENGTH_SHORT).show()
                return@scanOverview
            }
            currentView.findViewById<ProgressBar>(R.id.scan_progress)?.visibility = View.GONE
            val animate = !hasLoaded
            hasLoaded = true
            bind(currentView, result, animate)
        }
    }

    private fun bind(view: View, data: StorageOverview, animate: Boolean) {
        val context = context ?: return
        val inflater = LayoutInflater.from(context)

        view.findViewById<TextView>(R.id.scan_time)?.text = DateUtils.getRelativeTimeSpanString(
            data.scannedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )

        // 环形图：中心两行 —— 总容量 / 已占用（不同颜色）
        view.findViewById<StorageDonutChartView>(R.id.donut_chart)?.setData(
            slices = data.sortedSlices().map {
                StorageDonutChartView.Slice(it.size.toFloat(), colorOf(context, it.category))
            },
            animate = animate,
            centerPrimary = formatSize(data.totalCapacity),
            centerSecondary = formatSize(data.used),
            centerCaption = getString(
                R.string.storage_chart_caption,
                formatSize(data.available),
                String.format(Locale.getDefault(), "%.1f%%", data.usedPercent),
            ),
        )

        // 图例明细
        val legend = view.findViewById<LinearLayout>(R.id.legend_container)
        legend?.removeAllViews()
        data.sortedSlices().forEach { slice ->
            val row = inflater.inflate(R.layout.item_storage_legend, legend, false)
            row.findViewById<View>(R.id.legend_dot).tint(colorOf(context, slice.category))
            row.findViewById<TextView>(R.id.legend_name).setText(slice.category.labelRes)
            row.findViewById<TextView>(R.id.legend_size).text = formatSize(slice.size)
            row.findViewById<TextView>(R.id.legend_percent).text =
                String.format(Locale.getDefault(), "%.1f%%", data.sharePercentOf(slice))
            legend?.addView(row)
        }

        // 分类卡片
        val container = view.findViewById<LinearLayout>(R.id.card_container)
        container?.removeAllViews()
        val slices = data.sortedSlices()
        slices.forEach { slice ->
            container?.addView(buildCard(inflater, container, data, slice, animate))
        }
        view.findViewById<View>(R.id.empty_hint)?.visibility =
            if (slices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildCard(
        inflater: LayoutInflater,
        parent: ViewGroup,
        data: StorageOverview,
        slice: StorageSlice,
        animate: Boolean,
    ): View {
        val context = context ?: return View(parent.context)
        val card = inflater.inflate(R.layout.item_storage_card, parent, false)
        val color = colorOf(context, slice.category)

        card.findViewById<View>(R.id.card_dot).tint(color)
        card.findViewById<TextView>(R.id.card_title).setText(slice.category.labelRes)
        card.findViewById<TextView>(R.id.card_size).text = formatSize(slice.size)
        card.findViewById<UsageBarView>(R.id.card_bar)?.apply {
            setBarColor(color)
            setProgress(data.sharePercentOf(slice) / 100f, animate = animate)
        }
        card.findViewById<TextView>(R.id.card_summary).text = getString(
            R.string.storage_card_share,
            String.format(Locale.getDefault(), "%.1f%%", data.sharePercentOf(slice)),
            formatSize(data.available),
        )

        val primaryAction = card.findViewById<MaterialButton>(R.id.action_primary)
        val secondaryAction = card.findViewById<MaterialButton>(R.id.action_secondary)
        bindActions(slice.category, primaryAction, secondaryAction)
        // 没有可用操作时不留空行
        card.findViewById<View>(R.id.card_actions)?.visibility =
            if (primaryAction?.visibility == View.VISIBLE || secondaryAction?.visibility == View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }
        return card
    }

    private fun bindActions(
        category: StorageCategory,
        primary: MaterialButton?,
        secondary: MaterialButton?,
    ) {
        val context = context ?: return
        when (category) {
            StorageCategory.GALLERIES -> {
                showAction(primary, R.string.storage_action_manage_downloads) { openDownloads() }
                showAction(secondary, R.string.storage_action_analyze_gallery) {
                    (activity as? StorageAnalysisActivity)
                        ?.showTab(StorageAnalysisActivity.TAB_GALLERY)
                }
            }

            StorageCategory.RECYCLE_BIN -> {
                showAction(primary, R.string.storage_action_empty_recycle_bin) {
                    confirmEmptyRecycleBin()
                }
                showAction(secondary, R.string.storage_action_open_recycle_bin) {
                    LocalGalleryActivity.startRecycleBin(context)
                }
            }

            StorageCategory.DATABASE -> {
                showAction(secondary, R.string.storage_action_view_database) {
                    context.startActivity(Intent(context, DatabaseViewerActivity::class.java))
                }
            }

            else -> if (category.cleanable) {
                showAction(primary, R.string.storage_clean) { clearCategory(category) }
            }
        }
    }

    private fun showAction(button: MaterialButton?, textRes: Int, onClick: () -> Unit) {
        button ?: return
        button.visibility = View.VISIBLE
        button.setText(textRes)
        button.setOnClickListener { onClick() }
    }

    private fun clearCategory(category: StorageCategory) {
        val context = context ?: return
        Toast.makeText(context, R.string.storage_cleaning, Toast.LENGTH_SHORT).show()
        StorageScanner.clear(context, category) { freed ->
            val message = if (freed > 0) {
                getString(R.string.storage_cleaned, formatSize(freed))
            } else {
                getString(R.string.storage_cleaned_nothing)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            view?.let { if (it.isLaidOut) refresh() }
        }
    }

    private fun confirmEmptyRecycleBin() {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(R.string.storage_action_empty_recycle_bin)
            .setMessage(R.string.storage_empty_recycle_bin_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                LocalGalleryManager.getInstance(context).emptyRecycleBin()
                Toast.makeText(context, R.string.storage_recycle_bin_emptied, Toast.LENGTH_SHORT).show()
                view?.let { if (it.isLaidOut) refresh() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDownloads() {
        val context = context ?: return
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                action = StageActivity.ACTION_START_SCENE
                putExtra(StageActivity.KEY_SCENE_NAME, DownloadsScene::class.java.name)
            },
        )
    }

    private fun View.tint(color: Int) {
        backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun colorOf(context: Context, category: StorageCategory): Int =
        ContextCompat.getColor(context, category.colorRes)

    private fun formatSize(bytes: Long): String = FileUtils.humanReadableByteCount(bytes, false)
}