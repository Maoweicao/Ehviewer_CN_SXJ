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

package com.hippo.ehviewer.download.sort

import android.content.Context
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhConfig
import com.hippo.ehviewer.dao.DownloadInfo
import java.util.Collections

/**
 * 多级下载排序执行器。
 *
 * 规则按顺序依次比较；前面的规则优先，全部相等时保持原有顺序（稳定排序）。
 * 未知值（未知大小/未知页数/未知分类）统一排在最后，不因升序或降序跑到最前。
 */
object DownloadSortEngine {

    private val DEFAULT_CATEGORY_PRIORITY = intArrayOf(
        EhConfig.DOUJINSHI,
        EhConfig.MANGA,
        EhConfig.ARTIST_CG,
        EhConfig.GAME_CG,
        EhConfig.WESTERN,
        EhConfig.NON_H,
        EhConfig.IMAGE_SET,
        EhConfig.COSPLAY,
        EhConfig.ASIAN_PORN,
        EhConfig.MISC,
    )

    @JvmStatic
    fun sort(list: MutableList<DownloadInfo>, context: Context) {
        if (list.isEmpty() || !Settings.getAdvancedDownloadSortEnabled()) return
        val rules = DownloadSortOrder.load(context)
        if (rules.isEmpty()) return
        Collections.sort(list, comparator(rules, getCategoryPriorityMap(context)))
    }

    @JvmStatic
    fun needsPageCount(context: Context): Boolean {
        if (!Settings.getAdvancedDownloadSortEnabled()) return false
        return DownloadSortOrder.load(context).any {
            it.field == DownloadSortField.REMAINING ||
                it.field == DownloadSortField.TOTAL_PAGES
        }
    }

    /** 只有“剩余图片数量”规则需要知道实际已下载页数。 */
    @JvmStatic
    fun needsProgress(context: Context): Boolean {
        if (!Settings.getAdvancedDownloadSortEnabled()) return false
        return DownloadSortOrder.load(context).any {
            it.field == DownloadSortField.REMAINING
        }
    }

    @JvmStatic
    fun comparator(
        rules: List<DownloadSortRule>,
        categoryPriority: IntArray,
    ): Comparator<DownloadInfo> {
        return Comparator { lhs, rhs ->
            for (rule in rules) {
                val left = value(rule.field, lhs, categoryPriority)
                val right = value(rule.field, rhs, categoryPriority)
                // 未知值始终排最后，不受升序/降序影响。
                if (left == null && right == null) continue
                if (left == null) return@Comparator 1
                if (right == null) return@Comparator -1
                val cmp = left.compareTo(right)
                if (cmp != 0) {
                    return@Comparator if (rule.ascending) cmp else -cmp
                }
            }
            0
        }
    }

    @JvmStatic
    fun getCategoryPriorityMap(context: Context): IntArray {
        val saved = Settings.getDownloadCategoryPriorityOrder()
        if (saved.isNullOrEmpty()) return DEFAULT_CATEGORY_PRIORITY
        val parts = saved.split(",")
        val map = IntArray(parts.size)
        for (i in parts.indices) {
            map[i] = parts[i].trim().toIntOrNull() ?: return DEFAULT_CATEGORY_PRIORITY
        }
        return map
    }

    @JvmStatic
    fun getCategoryPriority(category: Int, priorityMap: IntArray): Int {
        for (i in priorityMap.indices) {
            if (priorityMap[i] == category) return i
        }
        return Int.MAX_VALUE
    }

    @JvmStatic
    fun getTotalPages(info: DownloadInfo): Int =
        maxOf(info.pages, info.total)

    @JvmStatic
    fun getRemainingPages(info: DownloadInfo): Int {
        val total = getTotalPages(info)
        if (total <= 0) return -1
        val done = maxOf(info.finished, info.downloaded)
        return (total - done).coerceAtLeast(0)
    }

    private fun value(
        field: DownloadSortField,
        info: DownloadInfo,
        categoryPriority: IntArray,
    ): Long? {
        return when (field) {
            DownloadSortField.TIME ->
                if (info.time > 0) info.time else null
            DownloadSortField.SIZE ->
                if (info.fileSize > 0) info.fileSize else null
            DownloadSortField.CATEGORY -> {
                val priority = getCategoryPriority(info.category, categoryPriority)
                if (priority == Int.MAX_VALUE) null else priority.toLong()
            }
            DownloadSortField.RATING ->
                (info.effectiveRating * 1000f).toLong()
            DownloadSortField.REMAINING -> {
                val remaining = getRemainingPages(info)
                if (remaining < 0) null else remaining.toLong()
            }
            DownloadSortField.TOTAL_PAGES -> {
                val total = getTotalPages(info)
                if (total <= 0) null else total.toLong()
            }
        }
    }
}
