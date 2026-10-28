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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.ui.scene.download.part.ThumbDataContainer
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.widget.FixedThumb
import com.hippo.lib.yorozuya.FileUtils

/**
 * 清理明细列表项。
 *
 * 受保护的画廊（命中下载设置里的排除规则）不可选中，但仍展示占用，
 * 这样用户能清楚地知道「这部分空间是被保护起来的」。
 */
class CleanupGalleryAdapter(
    private val items: List<GalleryCleanupActivity.CleanupItem>,
    private val readOnly: Boolean,
    private val onToggle: (GalleryCleanupActivity.CleanupItem, Int) -> Unit,
    private val onSingleDelete: (GalleryCleanupActivity.CleanupItem) -> Unit,
) : RecyclerView.Adapter<CleanupGalleryAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.select_checkbox)
        val thumb: FixedThumb = view.findViewById(R.id.thumb)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
        val badges: TextView = view.findViewById(R.id.badges)
        val size: TextView = view.findViewById(R.id.size)
        val delete: MaterialButton = view.findViewById(R.id.single_delete)
        val content: View = view.findViewById(R.id.root_content)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cleanup_gallery, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val selectable = !readOnly && !item.protectedFromDelete

        holder.title.text = EhUtils.getSuitableTitle(item.info)?.takeIf { it.isNotEmpty() }
            ?: item.info.gid.toString()
        holder.meta.text = buildString {
            append(EhUtils.getCategoryName(context, item.info.category))
            val rating = item.info.effectiveRating
            if (rating > 0f) {
                append(" · ")
                append(String.format(java.util.Locale.getDefault(), "%.2f", rating))
            }
            item.info.uploader?.takeIf { it.isNotEmpty() }?.let {
                append(" · ")
                append(it)
            }
        }
        holder.size.text = FileUtils.humanReadableByteCount(item.size, false)

        holder.badges.visibility = if (item.protectionText != null) View.VISIBLE else View.GONE
        holder.badges.text = item.protectionText ?: ""

        holder.thumb.load(
            EhCacheKeyFactory.getThumbKey(item.info.gid),
            item.info.thumb,
            ThumbDataContainer(item.info),
            true,
            false,
        )

        holder.checkbox.isEnabled = selectable
        holder.checkbox.isChecked = item.selected && selectable
        holder.content.isEnabled = selectable
        holder.content.alpha = if (selectable) 1f else 0.55f
        holder.content.setOnClickListener {
            if (selectable) onToggle(item, holder.bindingAdapterPosition)
        }
        holder.delete.visibility = if (readOnly || item.protectedFromDelete) View.GONE else View.VISIBLE
        holder.delete.setOnClickListener { onSingleDelete(item) }
    }
}