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
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DeleteProtectHelper
import com.hippo.ehviewer.download.DownloadDeleteHelper
import com.hippo.lib.yorozuya.FileUtils

/**
 * 清理明细子页面。
 *
 * 展示某个分组（分类 / 占用特征 / 受保护）下删除后能释放多少空间，
 * 支持单个删除与全选批量删除：
 * - 勾选底部「直接删除文件」→ 永久删除文件；
 * - 不勾选 → 移入回收站，之后可恢复。
 *
 * 删除完全复用下载管理器的逻辑（[DownloadDeleteHelper]），
 * 受保护的画廊默认锁定，不可被选中。
 */
class GalleryCleanupActivity : AppCompatActivity() {

    /** 一条待清理的画廊。 */
    class CleanupItem(
        val info: DownloadInfo,
        val size: Long,
        val protection: DeleteProtectHelper.Protection?,
        /** 给用户看的保护原因，例如「已收藏」。 */
        val protectionText: String?,
    ) {
        var checked = false

        val selected: Boolean get() = checked

        /** 受删除保护：在列表里锁定，不能被选中删除。 */
        val protectedFromDelete: Boolean get() = protection != null
    }

    private val items = ArrayList<CleanupItem>()
    private lateinit var adapter: CleanupGalleryAdapter
    private var readOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery_cleanup)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false)
        intent.getStringExtra(EXTRA_TITLE)?.let { supportActionBar?.title = it }

        val infos = intent.getParcelableArrayListExtra<DownloadInfo>(EXTRA_INFOS).orEmpty()

        // 占用大小随 DownloadInfo 一起传（DownloadInfo.fileSize）；删除保护在这里重新判定
        runCatching { DeleteProtectHelper.warmUpAsync() }
        infos.forEach { info ->
            val protection = runCatching { DeleteProtectHelper.isProtected(info) }.getOrNull()
            items.add(
                CleanupItem(
                    info = info,
                    size = info.fileSize.coerceAtLeast(0L),
                    protection = protection,
                    protectionText = protection?.describe(this),
                ),
            )
        }

        val list = findViewById<RecyclerView>(R.id.gallery_list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = CleanupGalleryAdapter(
            items = items,
            readOnly = readOnly,
            onToggle = ::toggle,
            onSingleDelete = ::confirmSingleDelete,
        )
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.select_all_button)?.setOnClickListener { selectAll() }
        findViewById<MaterialButton>(R.id.invert_button)?.setOnClickListener { invertSelection() }
        findViewById<MaterialButton>(R.id.delete_button)?.setOnClickListener { confirmBatchDelete() }

        if (readOnly) {
            findViewById<View>(R.id.direct_delete_checkbox)?.visibility = View.GONE
            findViewById<View>(R.id.delete_button)?.visibility = View.GONE
            findViewById<TextView>(R.id.list_hint)?.setText(R.string.storage_protected_readonly_hint)
        }
        updateHeader()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------ 交互

    private fun toggle(item: CleanupItem, position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        item.checked = !item.checked
        adapter.notifyItemChanged(position)
        updateHeader()
    }

    private fun selectAll() {
        val changed = items.withIndex().filter { !it.value.protectedFromDelete && !it.value.selected }
        changed.forEach { it.value.checked = true }
        changed.forEach { adapter.notifyItemChanged(it.index) }
        updateHeader()
    }

    private fun invertSelection() {
        val changed = items.withIndex().filter { !it.value.protectedFromDelete }
        changed.forEach { it.value.checked = !it.value.checked }
        changed.forEach { adapter.notifyItemChanged(it.index) }
        updateHeader()
    }

    private fun selectedItems(): List<CleanupItem> =
        items.filter { it.selected && !it.protectedFromDelete }

    private fun updateHeader() {
        val selectable = items.filter { !it.protectedFromDelete }
        findViewById<TextView>(R.id.releasable_size)?.text =
            formatSize(selectable.sumOf { it.size })
        val selected = selectedItems()
        findViewById<TextView>(R.id.selection_summary)?.text = getString(
            R.string.storage_selection_summary,
            selected.size,
            formatSize(selected.sumOf { it.size }),
        )
        val deleteButton = findViewById<MaterialButton>(R.id.delete_button)
        deleteButton?.isEnabled = selected.isNotEmpty()
        deleteButton?.text = getString(R.string.storage_delete_selected_count, selected.size)
    }

    // ------------------------------------------------------------ 删除

    private fun confirmSingleDelete(item: CleanupItem) {
        confirmDelete(listOf(item))
    }

    private fun confirmBatchDelete() {
        val selected = selectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.storage_select_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        confirmDelete(selected)
    }

    private fun confirmDelete(targets: List<CleanupItem>) {
        if (targets.isEmpty()) return
        val directDelete = findViewById<CheckBox>(R.id.direct_delete_checkbox)?.isChecked == true
        val favorited = targets.count { EhDB.containLocalFavorites(it.info.gid) }
        if (favorited > 0) {
            AlertDialog.Builder(this)
                .setTitle(R.string.download_delete_favorite_confirm_title)
                .setMessage(R.string.download_delete_favorite_confirm_message)
                .setPositiveButton(android.R.string.ok) { _, _ -> performDelete(targets, directDelete) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            performDelete(targets, directDelete)
        }
    }

    private fun performDelete(targets: List<CleanupItem>, directDelete: Boolean) {
        // 双保险：即便有画廊在页面上被解除了锁定，也按删除保护规则再筛一次
        val split = DeleteProtectHelper.splitProtected(targets.map { it.info })
        val deletable = split.deletable
        if (deletable.isEmpty()) {
            Toast.makeText(this, R.string.storage_all_protected, Toast.LENGTH_SHORT).show()
            return
        }
        if (split.protectedCount > 0) {
            Toast.makeText(
                this,
                getString(R.string.delete_protect_batch_skipped, split.protectedCount),
                Toast.LENGTH_SHORT,
            ).show()
        }
        DownloadDeleteHelper.performDeleteRange(this, deletable, directDelete)
        val gids = deletable.map { it.gid }.toSet()
        items.removeAll { it.info.gid in gids }
        adapter.notifyDataSetChanged()
        updateHeader()
        Toast.makeText(
            this,
            getString(R.string.storage_delete_started, deletable.size),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun formatSize(bytes: Long): String = FileUtils.humanReadableByteCount(bytes, false)

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_INFOS = "infos"
        private const val EXTRA_READ_ONLY = "read_only"

        /** 打开某个分组的清理明细。 */
        fun start(context: Context, group: GalleryUsageRepository.UsageGroup, readOnly: Boolean = false) {
            context.startActivity(
                Intent(context, GalleryCleanupActivity::class.java).apply {
                    putExtra(EXTRA_TITLE, group.title)
                    // 占用大小放在 DownloadInfo.fileSize 里一起带过去
                    putParcelableArrayListExtra(EXTRA_INFOS, ArrayList(group.items.map { it.info }))
                    putExtra(EXTRA_READ_ONLY, readOnly)
                },
            )
        }
    }
}