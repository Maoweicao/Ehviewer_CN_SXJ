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

package com.hippo.ehviewer.download.filter

import com.hippo.ehviewer.R
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 发布时间相关的工具。
 *
 * `posted` 字段格式为 `yyyy-MM-dd HH:mm`，这里统一解析成 epoch 毫秒。
 */
object PostedTime {

    private const val POSTED_FORMAT = "yyyy-MM-dd HH:mm"

    /** 解析 `yyyy-MM-dd HH:mm`，失败返回 null。 */
    @JvmStatic
    fun parse(posted: String?): Long? {
        if (posted.isNullOrBlank()) return null
        return try {
            val format = java.text.SimpleDateFormat(POSTED_FORMAT, Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT+9") // E-Hentai 时间固定为 UTC+9
            format.isLenient = false
            format.parse(posted.take(16))?.time
        } catch (e: Exception) {
            // 只取年份做兜底，保证"3 年前"这类粗粒度条件仍可工作
            parseYearOnly(posted)
        }
    }

    @JvmStatic
    fun parseYearOnly(posted: String?): Long? {
        if (posted.isNullOrBlank()) return null
        return try {
            val year = posted.take(4).toInt()
            if (year <= 0) {
                null
            } else {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+9"))
                cal.clear()
                cal.set(year, Calendar.JANUARY, 1, 0, 0, 0)
                cal.timeInMillis
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 从 [posted] 中取发布年份，解析失败返回 0（沿用旧行为）。 */
    @JvmStatic
    fun year(posted: String?): Int {
        val millis = parse(posted) ?: parseYearOnly(posted) ?: return 0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+9"))
        cal.timeInMillis = millis
        return cal.get(Calendar.YEAR)
    }

    /** 距今 [months] 个月之前的时刻。 */
    @JvmStatic
    @JvmOverloads
    fun monthsAgo(months: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.add(Calendar.MONTH, -months)
        return cal.timeInMillis
    }

    /** 距今 [years] 年之前的时刻。 */
    @JvmStatic
    @JvmOverloads
    fun yearsAgo(years: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.add(Calendar.YEAR, -years)
        return cal.timeInMillis
    }
}

/**
 * 内置快捷筛选与面板快速设置按钮的默认配置。
 *
 * 快捷筛选的 7 项由旧版硬编码实现迁移而来：
 * 阈值（100MB / 200MB / 4MB每页 / 3 分 / 2024 年）全部变成可调参数。
 */
/** 1 MB 的字节数，Java 侧作为常量使用。 */
const val MB_BYTES: Long = 1024L * 1024L

/** 体积阈值默认值（MB），旧版写死 100MB / 200MB / 4MB 每页，现已可在设置里调整。 */
object FilterPresets {

    @JvmStatic
    fun mb(): Long = MB_BYTES

    // ---------------- 快捷筛选 ----------------

    const val QF_BIG_LOW_RATING = "qf_big_low_rating"
    const val QF_DUPLICATES = "qf_duplicates"
    const val QF_LOW_SPACE_EFFICIENCY = "qf_low_space_efficiency"
    const val QF_LOW_RATING = "qf_low_rating"
    const val QF_HUGE_FILE = "qf_huge_file"
    const val QF_OLD_UNFAVORITED = "qf_old_unfavorited"
    const val QF_AI_GENERATED = "qf_ai_generated"

    /** 迁移自旧版写死实现的 7 条快捷筛选。 */
    @JvmStatic
    fun defaultQuickFilters(): List<QuickFilterDefinition> = listOf(
        QuickFilterDefinition(
            id = QF_BIG_LOW_RATING,
            name = "Big & low rating",
            nameRes = R.string.quick_filter_big_low_rating,
            kind = QuickFilterKind.BIG_LOW_RATING,
            // 保留旧实现的 3.5 分阈值；改为 0 则跟随“按 catalog 的低分定义”
            ratingMax = 3.5f,
            sizeMinMb = 100L,
        ),
        QuickFilterDefinition(
            id = QF_DUPLICATES,
            name = "Duplicate works",
            nameRes = R.string.quick_filter_duplicates,
            kind = QuickFilterKind.DUPLICATES,
        ),
        QuickFilterDefinition(
            id = QF_LOW_SPACE_EFFICIENCY,
            name = "Low space efficiency",
            nameRes = R.string.quick_filter_low_space_efficiency,
            kind = QuickFilterKind.LOW_SPACE_EFFICIENCY,
            perPageMinKb = 4L * 1024L,
        ),
        QuickFilterDefinition(
            id = QF_LOW_RATING,
            name = "Low rating downloads",
            nameRes = R.string.quick_filter_low_rating,
            kind = QuickFilterKind.LOW_RATING,
            // 0 = 跟随设置里配置的（按 catalog 细粒度的）低分阈值
            ratingMax = 0f,
        ),
        QuickFilterDefinition(
            id = QF_HUGE_FILE,
            name = "Huge files",
            nameRes = R.string.quick_filter_huge_file,
            kind = QuickFilterKind.HUGE_FILE,
            sizeMinMb = 200L,
        ),
        QuickFilterDefinition(
            id = QF_OLD_UNFAVORITED,
            name = "Old & un-favorited",
            nameRes = R.string.quick_filter_old_unfavorited,
            kind = QuickFilterKind.OLD_UNFAVORITED,
            // 旧实现写死 postedYear < 2024，这里改为相对时间
            agoMonths = 12,
        ),
        QuickFilterDefinition(
            id = QF_AI_GENERATED,
            name = "AI generated",
            nameRes = R.string.quick_filter_ai_generated,
            kind = QuickFilterKind.AI_GENERATED,
            // AI 生成判定对未完成下载同样有意义，因此不要求已完成
            requireFinished = false,
        ),
    )

    /** 面板“快速设置”按钮默认配置里已存在的 id（用于判断是否内置）。 */
    @JvmStatic
    fun defaultPanelPresetIds(): Set<String> = setOf(
        "pp_low_rating_3m", "pp_low_rating_6m", "pp_low_rating_12m",
        "pp_low_rating_24m", "pp_low_rating_old", "pp_big_low_rating",
        "pp_huge_file", "pp_low_space_efficiency", "pp_duplicates",
        "pp_unfavorited_old", "pp_many_pages_low_rating", "pp_recent",
        "pp_no_tag",
    )

    /**
     * 面板“快速设置”默认按钮。
     *
     * 命名刻意写明确（“近 3 个月低分”而非“3 个月前低分”），因为
     * “N 个月前” 既可以理解为“最近 N 个月内”，也可以理解为“早于 N 个月”，
     * 这里统一取前者。
     */
    @JvmStatic
    fun defaultPanelPresets(): List<PanelPreset> = listOf(
        preset("pp_low_rating_3m", R.string.filter_preset_low_rating_3m) {
            it.copy(lowRatingOnly = true, postedAgoMonths = 3)
        },
        preset("pp_low_rating_6m", R.string.filter_preset_low_rating_6m) {
            it.copy(lowRatingOnly = true, postedAgoMonths = 6)
        },
        preset("pp_low_rating_12m", R.string.filter_preset_low_rating_12m) {
            it.copy(lowRatingOnly = true, postedAgoMonths = 12)
        },
        preset("pp_low_rating_24m", R.string.filter_preset_low_rating_24m) {
            it.copy(lowRatingOnly = true, postedAgoMonths = 24)
        },
        preset("pp_low_rating_old", R.string.filter_preset_low_rating_old) {
            it.copy(lowRatingOnly = true, postedAgoMonths = 36)
        },
        preset("pp_big_low_rating", R.string.filter_preset_big_low_rating) {
            it.copy(lowRatingOnly = true, sizeFrom = 100L * MB_BYTES)
        },
        preset("pp_huge_file", R.string.filter_preset_huge_file) {
            it.copy(sizeFrom = 200L * MB_BYTES, sort = SortKey.FILE_SIZE_DESC)
        },
        preset("pp_low_space_efficiency", R.string.filter_preset_low_space_efficiency) {
            it.copy(perPageFromBytes = 4L * MB_BYTES)
        },
        preset("pp_duplicates", R.string.filter_preset_duplicates) {
            it.copy(duplicateOnly = true)
        },
        preset("pp_unfavorited_old", R.string.filter_preset_unfavorited_old) {
            it.copy(unfavoritedOnly = true, postedAgoMonths = 12)
        },
        preset("pp_many_pages_low_rating", R.string.filter_preset_many_pages_low_rating) {
            it.copy(lowRatingOnly = true, pageFrom = 100L)
        },
        preset("pp_recent", R.string.filter_preset_recent) {
            it.copy(postedAgoMonths = 3, sort = SortKey.CREATE_TIME_DESC)
        },
        preset("pp_no_tag", R.string.filter_preset_no_tag) {
            it.copy(tagsMissingOnly = true)
        },
    )

    private fun preset(
        id: String,
        nameRes: Int,
        block: (FilterCondition) -> FilterCondition,
    ): PanelPreset = PanelPreset(
        id = id,
        name = "",
        nameRes = nameRes,
        condition = block(FilterCondition.defaultAllStatuses()),
    )
}
