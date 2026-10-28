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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.hippo.ehviewer.R
import com.hippo.ehviewer.widget.UsageBarView
import com.hippo.lib.yorozuya.FileUtils

/**
 * Tab 3：缓存与清理。
 *
 * 列出各类缓存的当前占用（带上限的显示占比条），支持单项清理与一键清理。
 */
class CacheCleanupFragment : Fragment(R.layout.fragment_cache_cleanup) {

    private var hasLoaded = false
    private var entries: List<CacheEntry> = emptyList()
    private var cleaning = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.clean_all_button)?.setOnClickListener {
            confirmCleanAll()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasLoaded) refresh()
    }

    fun refresh() {
        val root = view ?: return
        val context = context ?: return
        root.findViewById<ProgressBar>(R.id.scan_progress)?.visibility = View.VISIBLE
        StorageScanner.scanCaches(context) { result ->
            val currentView = view ?: return@scanCaches
            currentView.findViewById<ProgressBar>(R.id.scan_progress)?.visibility = View.GONE
            hasLoaded = true
            entries = result
            bind(currentView, result)
        }
    }

    private fun bind(view: View, data: List<CacheEntry>) {
        val context = context ?: return
        val inflater = LayoutInflater.from(context)
        val container = view.findViewById<LinearLayout>(R.id.cache_container)
        container?.removeAllViews()

        val cleanableTotal = data.filter { it.category.cleanable }.sumOf { it.size }
        view.findViewById<TextView>(R.id.cleanable_size)?.text = formatSize(cleanableTotal)
        view.findViewById<MaterialButton>(R.id.clean_all_button)?.apply {
            isEnabled = cleanableTotal > 0
            setText(if (cleaning) R.string.storage_cleaning else R.string.storage_clean_all)
        }

        data.forEach { entry ->
            container?.addView(buildRow(inflater, container, entry))
        }
        view.findViewById<View>(R.id.empty_hint)?.visibility =
            if (data.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildRow(inflater: LayoutInflater, parent: ViewGroup, entry: CacheEntry): View {
        val context = context ?: return View(parent.context)
        val row = inflater.inflate(R.layout.item_cache_entry, parent, false)
        val color = ContextCompat.getColor(context, entry.category.colorRes)

        row.findViewById<View>(R.id.cache_dot).backgroundTintList = ColorStateList.valueOf(color)
        row.findViewById<TextView>(R.id.cache_title).setText(entry.category.labelRes)
        row.findViewById<TextView>(R.id.cache_size).text = formatSize(entry.size)

        val summary = if (entry.maxSize > 0) {
            getString(R.string.storage_cache_with_limit, formatSize(entry.size), formatSize(entry.maxSize))
        } else {
            getString(R.string.storage_cache_occupancy, formatSize(entry.size))
        }
        row.findViewById<TextView>(R.id.cache_summary).text = summary

        val bar = row.findViewById<UsageBarView>(R.id.cache_bar)
        if (entry.maxSize > 0) {
            bar?.visibility = View.VISIBLE
            bar?.setBarColor(color)
            bar?.setProgress(entry.progress / 100f, animate = true)
        }

        val clearButton = row.findViewById<MaterialButton>(R.id.cache_clear)
        if (entry.category.cleanable && entry.size > 0) {
            clearButton?.visibility = View.VISIBLE
            clearButton?.setOnClickListener { clear(entry.category) }
        } else {
            clearButton?.visibility = View.GONE
        }
        return row
    }

    private fun clear(category: StorageCategory) {
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

    private fun confirmCleanAll() {
        val context = context ?: return
        val cleanable = entries.filter { it.category.cleanable && it.size > 0 }
        if (cleanable.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.storage_clean_all)
            .setMessage(getString(R.string.storage_clean_all_message, formatSize(cleanable.sumOf { it.size })))
            .setPositiveButton(R.string.storage_clean) { _, _ ->
                cleaning = true
                view?.findViewById<MaterialButton>(R.id.clean_all_button)?.apply {
                    isEnabled = false
                    setText(R.string.storage_cleaning)
                }
                val targets = cleanable.map { it.category }
                var index = 0
                var freedTotal = 0L
                fun cleanNext() {
                    val currentView = view ?: return
                    if (index >= targets.size) {
                        cleaning = false
                        Toast.makeText(
                            context,
                            if (freedTotal > 0) {
                                getString(R.string.storage_cleaned, formatSize(freedTotal))
                            } else {
                                getString(R.string.storage_cleaned_nothing)
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                        refresh()
                        return
                    }
                    StorageScanner.clear(context, targets[index]) { freed ->
                        freedTotal += freed
                        index++
                        cleanNext()
                    }
                }
                cleanNext()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatSize(bytes: Long): String = FileUtils.humanReadableByteCount(bytes, false)
}