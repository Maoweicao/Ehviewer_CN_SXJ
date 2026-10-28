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

import androidx.annotation.StringRes
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhConfig

/**
 * 筛选体系的稳定枚举。
 *
 * 全部使用字符串 key 序列化，**不使用 `R.id`**，避免重建后 id 漂移导致配置失效。
 * 运行时仍可与 `R.id` 互转（见 [FilterIdMapping]）。
 */

/** 排除关键词的作用域。 */
enum class KeywordScope(val key: String, @StringRes val labelRes: Int) {
    ANY("any", R.string.filter_exclude_scope_any),
    NAME("name", R.string.filter_exclude_scope_name),
    UPLOADER("uploader", R.string.filter_exclude_scope_uploader),
    TAG("tag", R.string.filter_exclude_scope_tag),
    DESC("desc", R.string.filter_exclude_scope_desc),
    ;

    companion object {
        @JvmStatic
        fun fromKey(key: String?): KeywordScope =
            values().firstOrNull { it.key == key } ?: ANY
    }
}

/**
 * 快捷筛选 / 快速设置按钮的预设类型。
 *
 * [CUSTOM] 表示直接复用一份完整的 [FilterCondition]（即“高级筛选配置”）。
 * 其余为带可调参数的预设类型。
 */
enum class QuickFilterKind(val key: String) {
    CUSTOM("custom"),
    BIG_LOW_RATING("big_low_rating"),
    DUPLICATES("duplicates"),
    LOW_SPACE_EFFICIENCY("low_space_efficiency"),
    LOW_RATING("low_rating"),
    HUGE_FILE("huge_file"),
    OLD_UNFAVORITED("old_unfavorited"),
    AI_GENERATED("ai_generated"),
    ;

    val isBuiltin: Boolean get() = this != CUSTOM

    companion object {
        @JvmStatic
        fun fromKey(key: String?): QuickFilterKind =
            values().firstOrNull { it.key == key } ?: CUSTOM
    }
}

/** 下载目录支持的 catalog（与 [EhConfig] 常量一一对应）。 */
object Catalogs {
    @JvmField
    val ORDERED: IntArray = intArrayOf(
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
    fun isValid(category: Int): Boolean = ORDERED.any { it == category }

    @JvmStatic
    fun labelRes(category: Int): Int = when (category) {
        EhConfig.DOUJINSHI -> R.string.doujinshi
        EhConfig.MANGA -> R.string.manga
        EhConfig.ARTIST_CG -> R.string.artist_cg
        EhConfig.GAME_CG -> R.string.game_cg
        EhConfig.WESTERN -> R.string.western
        EhConfig.NON_H -> R.string.non_h
        EhConfig.IMAGE_SET -> R.string.image_set
        EhConfig.COSPLAY -> R.string.cosplay
        EhConfig.ASIAN_PORN -> R.string.asian_porn
        EhConfig.MISC -> R.string.misc
        else -> R.string.category_all
    }
}

/** 排序方式：稳定字符串 key。 */
enum class SortKey(val key: String, @StringRes val labelRes: Int) {
    DEFAULT("default", R.string.sort_by_default),
    GALLERY_ID_ASC("gallery_id_asc", R.string.sort_by_gallery_id_asc),
    GALLERY_ID_DESC("gallery_id_desc", R.string.sort_by_gallery_id_desc),
    CREATE_TIME_ASC("create_time_asc", R.string.sort_by_create_time_asc),
    CREATE_TIME_DESC("create_time_desc", R.string.sort_by_create_time_desc),
    RATING_ASC("rating_asc", R.string.sort_by_rating_asc),
    RATING_DESC("rating_desc", R.string.sort_by_rating_desc),
    NAME_ASC("name_asc", R.string.sort_by_name_asc),
    NAME_DESC("name_desc", R.string.sort_by_name_desc),
    FILE_SIZE_ASC("file_size_asc", R.string.sort_by_file_size_asc),
    FILE_SIZE_DESC("file_size_desc", R.string.sort_by_file_size_desc),
    STATE_QUEUE_ASC("state_queue_asc", R.string.sort_by_state_queue_asc),
    STATE_QUEUE_DESC("state_queue_desc", R.string.sort_by_state_queue_desc),
    REMAINING_PAGES_ASC("remaining_pages_asc", R.string.sort_by_remaining_pages_asc),
    REMAINING_PAGES_DESC("remaining_pages_desc", R.string.sort_by_remaining_pages_desc),
    ;

    companion object {
        @JvmStatic
        fun fromKey(key: String?): SortKey =
            values().firstOrNull { it.key == key } ?: DEFAULT
    }
}
