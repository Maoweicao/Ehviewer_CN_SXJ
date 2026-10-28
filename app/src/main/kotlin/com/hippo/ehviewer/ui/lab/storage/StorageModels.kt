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

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.hippo.ehviewer.R
import com.hippo.lib.yorozuya.FileUtils

/**
 * 存储感知分析里的一块占用分类。
 *
 * 环形图、图例、明细卡片都共用这一份分类定义，保证三处展示永远一致。
 */
enum class StorageCategory(
    @StringRes val labelRes: Int,
    @ColorRes val colorRes: Int,
    /** 是否有“一键清理”能力（缓存类都算，画廊/数据库不算）。 */
    val cleanable: Boolean,
) {
    GALLERIES(
        R.string.storage_category_galleries,
        R.color.storage_chart_galleries,
        false,
    ),
    RECYCLE_BIN(
        R.string.storage_category_recycle_bin,
        R.color.storage_chart_recycle_bin,
        true,
    ),
    THUMBNAIL_CACHE(
        R.string.storage_category_thumbnail_cache,
        R.color.storage_chart_thumbnail_cache,
        true,
    ),
    NETWORK_CACHE(
        R.string.storage_category_network_cache,
        R.color.storage_chart_network_cache,
        true,
    ),
    SPIDER_CACHE(
        R.string.storage_category_spider_cache,
        R.color.storage_chart_spider_cache,
        true,
    ),
    TRANSLATE_CACHE(
        R.string.storage_category_translate_cache,
        R.color.storage_chart_translate_cache,
        true,
    ),
    DATABASE(
        R.string.storage_category_database,
        R.color.storage_chart_database,
        false,
    ),
    LOGS(
        R.string.storage_category_logs,
        R.color.storage_chart_logs,
        true,
    ),
    TEMP(
        R.string.storage_category_temp,
        R.color.storage_chart_temp,
        true,
    ),
    OTHER(
        R.string.storage_category_other,
        R.color.storage_chart_other,
        false,
    ),
}

/** 环形图的一段。 */
data class StorageSlice(val category: StorageCategory, val size: Long)

/** 一次扫描得到的存储概览。 */
data class StorageOverview(
    /** 所在存储卷总容量。 */
    val totalCapacity: Long,
    /** 所在存储卷可用空间。 */
    val available: Long,
    /** 本应用占用合计（所有分项之和）。 */
    val used: Long,
    val slices: List<StorageSlice>,
    val scannedAt: Long,
) {
    /** 占用总容量的百分比（0f~100f）。 */
    val usedPercent: Float
        get() = if (totalCapacity > 0) {
            (used * 100f / totalCapacity).coerceIn(0f, 100f)
        } else {
            0f
        }

    /** 占应用自身占用的百分比（0f~100f）。 */
    fun sharePercentOf(slice: StorageSlice): Float =
        if (used > 0) (slice.size * 100f / used).coerceIn(0f, 100f) else 0f

    fun sizeOf(category: StorageCategory): Long =
        slices.firstOrNull { it.category == category }?.size ?: 0L

    /** 按占用从大到小排序后的分项（只保留有占用的）。 */
    fun sortedSlices(): List<StorageSlice> =
        slices.filter { it.size > 0L }.sortedByDescending { it.size }

    /** 可一键清理的缓存合计。 */
    val cleanableTotal: Long get() = slices.filter { it.category.cleanable }.sumOf { it.size }

    companion object {
        fun formatSize(bytes: Long): String = FileUtils.humanReadableByteCount(bytes, false)
    }
}

/** 缓存与清理页的一项。 */
data class CacheEntry(
    val category: StorageCategory,
    val size: Long,
    /** 上限，-1 表示没有上限。 */
    val maxSize: Long = -1L,
    /** 清理后的简短说明。 */
    val detailRes: Int = 0,
) {
    val progress: Float
        get() = if (maxSize > 0) (size * 100f / maxSize).coerceIn(0f, 100f) else 0f
}