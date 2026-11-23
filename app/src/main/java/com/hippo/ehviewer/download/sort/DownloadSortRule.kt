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

import androidx.annotation.StringRes
import com.hippo.ehviewer.R

/**
 * 多级下载排序的字段。
 *
 * [wire] 用于设置持久化，[labelRes] 是设置页显示名，
 * [hintRes] 是第二行的方向说明（例如“旧→新”或“按分类详细顺序”）。
 */
enum class DownloadSortField(
    val wire: String,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int,
) {
    TIME(
        "time",
        R.string.download_sort_field_time,
        R.string.download_sort_hint_time,
    ),
    SIZE(
        "size",
        R.string.download_sort_field_size,
        R.string.download_sort_hint_size,
    ),
    CATEGORY(
        "category",
        R.string.download_sort_field_category,
        R.string.download_sort_hint_category,
    ),
    RATING(
        "rating",
        R.string.download_sort_field_rating,
        R.string.download_sort_hint_rating,
    ),
    REMAINING(
        "remaining",
        R.string.download_sort_field_remaining,
        R.string.download_sort_hint_remaining,
    ),
    TOTAL_PAGES(
        "total",
        R.string.download_sort_field_total,
        R.string.download_sort_hint_total,
    );

    companion object {
        @JvmStatic
        fun fromWire(value: String?): DownloadSortField? =
            entries.firstOrNull { it.wire == value }
    }
}

data class DownloadSortRule(
    val field: DownloadSortField,
    val ascending: Boolean,
)
