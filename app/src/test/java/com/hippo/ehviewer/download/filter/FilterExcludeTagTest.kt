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

import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryTagGroup
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.sync.GalleryTagsWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排除标签的匹配与「标签覆盖率」统计。
 *
 * 回归背景：下载条目的标签只来自 `simpleTags`/`tgList` 与 `Gallery_Tags`，
 * 而 `Gallery_Tags` 历史上只在打开详情页时写入。这里固定住两个事实：
 * 标签存在时必须能命中；标签未知的条目必须被统计出来（供面板提示）。
 */
class FilterExcludeTagTest {

    private fun fields(tags: List<String>) = FilterMatcher.Fields(
        1,
        "[FudooManken] Arknights Cantabile",
        null,
        "ernie1234",
        { tags },
        { null },
    )

    @Test
    fun testNamespacedTagFromTagTableIsExcluded() {
        val rule = ExcludeRule(excludedTags = setOf("parody:arknights"))
        assertTrue(FilterMatcher.isExcluded(rule, fields(listOf("parody:arknights", "female:sex toys"))))
    }

    @Test
    fun testOtherGalleryIsKept() {
        val rule = ExcludeRule(excludedTags = setOf("parody:arknights"))
        assertFalse(FilterMatcher.isExcluded(rule, fields(listOf("parody:blue archive", "female:glasses"))))
    }

    @Test
    fun testBareValueHitsNamespacedTagThroughAlias() {
        val rule = ExcludeRule(excludedTags = setOf("arknights"))
        // 别名解析由调用方注入（EhTagDatabase 会把裸值解析成规范 ns:value）
        assertTrue(
            FilterMatcher.isExcluded(rule, fields(listOf("parody:arknights"))) { listOf("parody:arknights") }
        )
    }

    @Test
    fun testTagRuleIsIgnoredForEntryWithoutTags() {
        val rule = ExcludeRule(excludedTags = setOf("parody:arknights"))
        // 标签未知的条目不会被“排除”，这正是需要提示用户的原因
        assertFalse(FilterMatcher.isExcluded(rule, fields(emptyList())))
    }

    @Test
    fun testTagsOfPrefersDetailGroups() {
        val parity = GalleryDetail().apply {
            gid = 4257633L
            tags = arrayOf(
                GalleryTagGroup().apply {
                    groupName = "parody"
                    addTag("arknights")
                },
                GalleryTagGroup().apply {
                    groupName = "female"
                    addTag("sex toys")
                },
            )
            // 详情页解析出来的 simpleTags 常为空，必须以 tag group 为准
            simpleTags = null
            tgList = null
        }
        assertEquals(
            listOf("parody:arknights", "female:sex toys"),
            GalleryTagsWriter.tagsOf(parity),
        )
    }

    @Test
    fun testTagsOfFallsBackToSimpleTags() {
        val list = DownloadInfo(1L).apply { simpleTags = arrayOf("parody:arknights") }
        assertEquals(listOf("parody:arknights"), GalleryTagsWriter.tagsOf(list))
    }

    @Test
    fun testCoverageCountsInMemoryAndTableTags() {
        val withSimpleTags = DownloadInfo(1L).apply {
            simpleTags = arrayOf("parody:arknights")
        }
        val withTgList = DownloadInfo(2L).apply {
            tgList = arrayListOf("parody:arknights")
        }
        val onlyInTable = DownloadInfo(3L)
        val unknown = DownloadInfo(4L)
        val emptyTags = DownloadInfo(5L).apply {
            simpleTags = emptyArray()
        }

        val list = listOf(withSimpleTags, withTgList, onlyInTable, unknown, emptyTags)
        // 内存里有标签的两条任何时候都算得到；3 靠 Gallery_Tags；4/5 标签未知
        assertEquals(2, TagCoverage.countTagged(list, emptySet()))
        assertEquals(3, TagCoverage.countTagged(list, setOf(3L)))
        assertEquals(4, TagCoverage.countTagged(list, setOf(3L, 4L)))
        assertTrue(TagCoverage.hasTags(withTgList, emptySet()))
        assertFalse(TagCoverage.hasTags(emptyTags, emptySet()))
        assertTrue(TagCoverage.hasTags(onlyInTable, setOf(3L)))
        assertFalse(TagCoverage.hasTags(unknown, setOf(3L)))
    }
}
