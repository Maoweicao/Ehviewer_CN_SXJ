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

/**
 * [SortKey]（持久化的稳定字符串）与菜单 `R.id`（运行时）之间的映射。
 *
 * 排序只在执行时需要 `R.id`，配置里始终存字符串，两边通过这里转换。
 */
object FilterIdMapping {

    @JvmStatic
    fun menuIdOf(sort: SortKey): Int = when (sort) {
        SortKey.DEFAULT -> R.id.sort_by_default
        SortKey.GALLERY_ID_ASC -> R.id.sort_by_gallery_id_asc
        SortKey.GALLERY_ID_DESC -> R.id.sort_by_gallery_id_desc
        SortKey.CREATE_TIME_ASC -> R.id.sort_by_create_time_asc
        SortKey.CREATE_TIME_DESC -> R.id.sort_by_create_time_desc
        SortKey.RATING_ASC -> R.id.sort_by_rating_asc
        SortKey.RATING_DESC -> R.id.sort_by_rating_desc
        SortKey.NAME_ASC -> R.id.sort_by_name_asc
        SortKey.NAME_DESC -> R.id.sort_by_name_desc
        SortKey.FILE_SIZE_ASC -> R.id.sort_by_file_size_asc
        SortKey.FILE_SIZE_DESC -> R.id.sort_by_file_size_desc
        SortKey.STATE_QUEUE_ASC -> R.id.sort_by_state_queue_asc
        SortKey.STATE_QUEUE_DESC -> R.id.sort_by_state_queue_desc
        SortKey.REMAINING_PAGES_ASC -> R.id.sort_by_remaining_pages_asc
        SortKey.REMAINING_PAGES_DESC -> R.id.sort_by_remaining_pages_desc
    }

    @JvmStatic
    fun sortKeyOf(menuId: Int): SortKey = when (menuId) {
        R.id.sort_by_gallery_id_asc -> SortKey.GALLERY_ID_ASC
        R.id.sort_by_gallery_id_desc -> SortKey.GALLERY_ID_DESC
        R.id.sort_by_create_time_asc -> SortKey.CREATE_TIME_ASC
        R.id.sort_by_create_time_desc -> SortKey.CREATE_TIME_DESC
        R.id.sort_by_rating_asc -> SortKey.RATING_ASC
        R.id.sort_by_rating_desc -> SortKey.RATING_DESC
        R.id.sort_by_name_asc -> SortKey.NAME_ASC
        R.id.sort_by_name_desc -> SortKey.NAME_DESC
        R.id.sort_by_file_size_asc -> SortKey.FILE_SIZE_ASC
        R.id.sort_by_file_size_desc -> SortKey.FILE_SIZE_DESC
        R.id.sort_by_state_queue_asc -> SortKey.STATE_QUEUE_ASC
        R.id.sort_by_state_queue_desc -> SortKey.STATE_QUEUE_DESC
        R.id.sort_by_remaining_pages_asc -> SortKey.REMAINING_PAGES_ASC
        R.id.sort_by_remaining_pages_desc -> SortKey.REMAINING_PAGES_DESC
        else -> SortKey.DEFAULT
    }
}
