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

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.download.DeleteProtectHelper
import com.hippo.ehviewer.widget.UsageBarView
import com.hippo.lib.yorozuya.FileUtils

/**
 * Tab 2：画廊占用分析。
 *
 * 顶部可在「按分类」与「按占用特征」之间切换：
 * - 按分类：同人志 / 漫画 / CG… 的占用分布；
 * - 按占用特征：复用下载设置里的快捷筛选口径（超大文件、低空间效率、老旧未收藏…）。
 *
 * 受保护卡片跟随下载设置里的**排除规则**，列出命中的画廊及其占用；
 * 这些画廊在清理明细里默认锁定，避免误删。
 */
class GalleryUsageFragment : Fragment(R.layout.fragment_gallery_usage) {

    private var hasLoaded = false
    private var usages: List<GalleryUsageRepository.GalleryUsage> = emptyList()
    private var dimension = DIMENSION_CATEGORY
    private var loading = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Chip>(R.id.chip_category)?.setOnClickListener {
            switchDimension(view, DIMENSION_CATEGORY)
        }
        view.findViewById<Chip>(R.id.chip_feature)?.setOnClickListener {
            switchDimension(view, DIMENSION_FEATURE)
        }
        view.findViewById<MaterialButton>(R.id.protected_action)?.setOnClickListener {
            openProtectedDetail()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasLoaded) refresh()
    }

    fun refresh() {
        val root = view ?: return
        val context = context ?: return
        if (loading) return
        loading = true
        root.findViewById<ProgressBar>(R.id.scan_progress)?.visibility = View.VISIBLE
        GalleryUsageRepository.loadUsages { result ->
            val currentView = view ?: return@loadUsages
            loading = false
            hasLoaded = true
            currentView.findViewById<ProgressBar>(R.id.scan_progress)?.visibility = View.GONE
            usages = result
            bindProtected(currentView, result)
            bindGroups(currentView, result)
        }
    }

    private fun switchDimension(view: View, target: Int) {
        if (dimension == target) return
        dimension = target
        bindGroups(view, usages)
    }

    /** 受保护画廊：命中「设置 → 下载 → 删除保护」的画廊。 */
    private fun bindProtected(view: View, all: List<GalleryUsageRepository.GalleryUsage>) {
        val protectedList = GalleryUsageRepository.protectedUsages(all)
        val total = protectedList.sumOf { it.size }
        view.findViewById<TextView>(R.id.protected_size)?.text = formatSize(total)
        view.findViewById<TextView>(R.id.protected_summary)?.text = when {
            protectedList.isNotEmpty() ->
                getString(R.string.storage_protected_summary, protectedList.size, formatSize(total))

            !DeleteProtectHelper.isProtectionEnabled() -> getString(R.string.storage_protected_disabled)

            else -> getString(R.string.storage_protected_summary_empty)
        }
        view.findViewById<MaterialButton>(R.id.protected_action)?.isEnabled = protectedList.isNotEmpty()
    }

    private fun openProtectedDetail() {
        val protectedList = GalleryUsageRepository.protectedUsages(usages)
        if (protectedList.isEmpty()) return
        val group = GalleryUsageRepository.UsageGroup(
            id = GROUP_PROTECTED,
            title = getString(R.string.storage_protected_title),
            items = protectedList,
            color = 0xFF43A047.toInt(),
            dimension = GalleryUsageRepository.Dimension.PROTECTED,
        )
        GalleryCleanupActivity.start(requireContext(), group, readOnly = true)
    }

    private fun bindGroups(view: View, all: List<GalleryUsageRepository.GalleryUsage>) {
        val context = context ?: return
        val container = view.findViewById<LinearLayout>(R.id.group_container)
        val inflater = LayoutInflater.from(context)
        container?.removeAllViews()
        view.findViewById<TextView>(R.id.group_title)?.setText(
            if (dimension == DIMENSION_CATEGORY) {
                R.string.storage_dimension_category
            } else {
                R.string.storage_dimension_feature
            },
        )

        if (all.isEmpty()) {
            view.findViewById<View>(R.id.empty_hint)?.visibility = View.VISIBLE
            return
        }
        view.findViewById<View>(R.id.empty_hint)?.visibility = View.GONE

        if (dimension == DIMENSION_CATEGORY) {
            val groups = GalleryUsageRepository.groupByCategory(context, all)
            appendGroups(container, inflater, groups)
        } else {
            bindFeatureGroups(container, inflater, all)
        }
    }

    /** 占用特征分组需要逐条跑快捷筛选，异步回填。 */
    private fun bindFeatureGroups(
        container: LinearLayout?,
        inflater: LayoutInflater,
        all: List<GalleryUsageRepository.GalleryUsage>,
    ) {
        val context = context ?: return
        container?.removeAllViews()
        view?.findViewById<ProgressBar>(R.id.scan_progress)?.visibility = View.VISIBLE
        GalleryUsageRepository.groupByFeature(context, all) { groups ->
            val currentContainer =
                view?.findViewById<LinearLayout>(R.id.group_container) ?: return@groupByFeature
            currentContainer.removeAllViews()
            view?.findViewById<ProgressBar>(R.id.scan_progress)?.visibility = View.GONE
            appendGroups(currentContainer, inflater, groups)
        }
    }

    private fun appendGroups(
        container: LinearLayout?,
        inflater: LayoutInflater,
        groups: List<GalleryUsageRepository.UsageGroup>,
    ) {
        val maxSize = groups.maxOfOrNull { it.totalSize }?.toFloat()?.coerceAtLeast(1f) ?: 1f
        groups.forEach { group ->
            container?.addView(buildGroupCard(inflater, container, group, maxSize))
        }
    }

    private fun buildGroupCard(
        inflater: LayoutInflater,
        parent: ViewGroup,
        group: GalleryUsageRepository.UsageGroup,
        maxSize: Float,
    ): View {
        val card = inflater.inflate(R.layout.item_usage_group, parent, false)
        card.findViewById<View>(R.id.group_dot)
            .backgroundTintList = ColorStateList.valueOf(group.color)
        card.findViewById<TextView>(R.id.group_title).text = group.title
        card.findViewById<TextView>(R.id.group_count).text =
            getString(R.string.storage_group_count, group.count)
        card.findViewById<UsageBarView>(R.id.group_bar)?.apply {
            setBarColor(group.color)
            setProgress(group.totalSize / maxSize, animate = true)
        }
        card.findViewById<TextView>(R.id.group_summary).text = buildString {
            append(getString(R.string.storage_group_releasable, formatSize(group.totalSize)))
            group.largest?.let { largest ->
                append('\n')
                append(titleOf(largest))
                append("  ")
                append(formatSize(largest.size))
            }
        }
        card.setOnClickListener {
            GalleryCleanupActivity.start(requireContext(), group)
        }
        return card
    }

    private fun titleOf(usage: GalleryUsageRepository.GalleryUsage): String =
        EhUtils.getSuitableTitle(usage.info)?.takeIf { it.isNotEmpty() }
            ?: usage.gid.toString()

    private fun formatSize(bytes: Long): String = FileUtils.humanReadableByteCount(bytes, false)

    private companion object {
        const val DIMENSION_CATEGORY = 0
        const val DIMENSION_FEATURE = 1
        const val GROUP_PROTECTED = "protected"
    }
}