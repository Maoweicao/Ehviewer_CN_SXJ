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

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhConfig
import com.hippo.ehviewer.dao.DownloadInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSortEngineTest {

    private val priority = intArrayOf(
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

    private fun info(
        gid: Long,
        time: Long = 0,
        size: Long = -1,
        pages: Int = 0,
        total: Int = 0,
        finished: Int = 0,
        category: Int = EhConfig.MISC,
        rating: Float = 0f,
    ) = DownloadInfo(gid).apply {
        this.time = time
        this.fileSize = size
        this.pages = pages
        this.total = total
        this.finished = finished
        this.category = category
        this.rating = rating
    }

    private fun sort(items: List<DownloadInfo>, vararg rules: DownloadSortRule): List<Long> =
        items.sortedWith(DownloadSortEngine.comparator(rules.toList(), priority))
            .map { it.gid }

    @Test
    fun `time sorts in both directions`() {
        val items = listOf(info(1, time = 300), info(2, time = 100), info(3, time = 200))
        assertEquals(
            listOf(2L, 3L, 1L),
            sort(items, DownloadSortRule(DownloadSortField.TIME, true)),
        )
        assertEquals(
            listOf(1L, 3L, 2L),
            sort(items, DownloadSortRule(DownloadSortField.TIME, false)),
        )
    }

    @Test
    fun `size puts unknown last regardless of direction`() {
        val items = listOf(info(1, size = 500), info(2, size = -1), info(3, size = 100))
        assertEquals(
            listOf(3L, 1L, 2L),
            sort(items, DownloadSortRule(DownloadSortField.SIZE, true)),
        )
        assertEquals(
            listOf(1L, 3L, 2L),
            sort(items, DownloadSortRule(DownloadSortField.SIZE, false)),
        )
    }

    @Test
    fun `category follows the detail order and its reverse`() {
        val items = listOf(
            info(1, category = EhConfig.MISC),
            info(2, category = EhConfig.DOUJINSHI),
            info(3, category = EhConfig.MANGA),
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            sort(items, DownloadSortRule(DownloadSortField.CATEGORY, true)),
        )
        assertEquals(
            listOf(1L, 3L, 2L),
            sort(items, DownloadSortRule(DownloadSortField.CATEGORY, false)),
        )
    }

    @Test
    fun `rating sorts low to high`() {
        val items = listOf(info(1, rating = 3.5f), info(2, rating = 1.0f), info(3, rating = 4.9f))
        assertEquals(
            listOf(2L, 1L, 3L),
            sort(items, DownloadSortRule(DownloadSortField.RATING, true)),
        )
    }

    @Test
    fun `remaining and total page rules`() {
        val items = listOf(
            info(1, pages = 100, finished = 90),
            info(2, pages = 50, finished = 10),
            info(3, pages = 20, finished = 19),
        )
        assertEquals(
            listOf(3L, 1L, 2L),
            sort(items, DownloadSortRule(DownloadSortField.REMAINING, true)),
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            sort(items, DownloadSortRule(DownloadSortField.TOTAL_PAGES, false)),
        )
    }

    @Test
    fun `unknown pages sort last in descending order`() {
        val items = listOf(info(1, pages = 10), info(2, pages = 0, total = 0), info(3, pages = 40))
        assertEquals(
            listOf(3L, 1L, 2L),
            sort(items, DownloadSortRule(DownloadSortField.TOTAL_PAGES, false)),
        )
    }

    @Test
    fun `rules are applied in order`() {
        val items = listOf(
            info(1, size = 100, time = 10),
            info(2, size = 100, time = 30),
            info(3, size = 200, time = 20),
        )
        assertEquals(
            listOf(3L, 2L, 1L),
            sort(
                items,
                DownloadSortRule(DownloadSortField.SIZE, false),
                DownloadSortRule(DownloadSortField.TIME, false),
            ),
        )
    }

    @Test
    fun `json round trip keeps rules`() {
        val rules = listOf(
            DownloadSortRule(DownloadSortField.CATEGORY, true),
            DownloadSortRule(DownloadSortField.REMAINING, false),
        )
        val parsed = DownloadSortOrder.fromJson(DownloadSortOrder.toJson(rules))
        assertEquals(rules, parsed)
    }

    @Test
    fun `empty json means insertion order`() {
        assertTrue(DownloadSortOrder.fromJson("").isEmpty())
        assertTrue(DownloadSortOrder.fromJson("not json").isEmpty())
    }

    @Test
    fun `migration maps old queue settings`() {
        assertEquals(
            listOf(DownloadSortRule(DownloadSortField.TOTAL_PAGES, true)),
            DownloadSortOrder.migrateRules(
                Settings.DOWNLOAD_QUEUE_ORDER_FEWEST_FIRST,
                Settings.DOWNLOAD_QUEUE_SECONDARY_NONE,
                false,
            ),
        )
        assertEquals(
            listOf(
                DownloadSortRule(DownloadSortField.TOTAL_PAGES, false),
                DownloadSortRule(DownloadSortField.CATEGORY, true),
            ),
            DownloadSortOrder.migrateRules(
                Settings.DOWNLOAD_QUEUE_ORDER_MOST_FIRST,
                Settings.DOWNLOAD_QUEUE_SECONDARY_CATEGORY_PRIORITY,
                false,
            ),
        )
        assertEquals(
            listOf(DownloadSortRule(DownloadSortField.REMAINING, false)),
            DownloadSortOrder.migrateRules(
                Settings.DOWNLOAD_QUEUE_ORDER_MOST_FIRST,
                Settings.DOWNLOAD_QUEUE_SECONDARY_NONE,
                true,
            ),
        )
        assertTrue(
            DownloadSortOrder.migrateRules(
                Settings.DOWNLOAD_QUEUE_ORDER_DEFAULT,
                Settings.DOWNLOAD_QUEUE_SECONDARY_CATEGORY_PRIORITY,
                false,
            ).isEmpty(),
        )
    }

    @Test
    fun `migration avoids duplicate fields`() {
        val rules = DownloadSortOrder.migrateRules(
            Settings.DOWNLOAD_QUEUE_ORDER_CATEGORY_PRIORITY,
            Settings.DOWNLOAD_QUEUE_SECONDARY_CATEGORY_PRIORITY,
            false,
        )
        assertEquals(listOf(DownloadSortRule(DownloadSortField.CATEGORY, true)), rules)
    }
}
