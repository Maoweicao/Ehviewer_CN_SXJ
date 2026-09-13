package com.hippo.ehviewer.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 测试 [SpiderTokenUtils.normalizeCoreTitle] 的归一化行为，
 * 必须与原 [PreDownloadMergeTask] 中的实现保持完全一致。
 */
class SpiderTokenUtilsNormalizeTest {

    @Test
    fun testStripsIsoDate() {
        assertEquals(
            "test gallery",
            SpiderTokenUtils.normalizeCoreTitle("Test Gallery 2026-08-02")
        )
    }

    @Test
    fun testStripsChineseDate() {
        // DATE_REGEX 第一段只匹配 4位-2位-2位，第二段才会吃掉末尾 "日"
        // 验证 "2026/08/02" 完全匹配第一段被剥离
        assertEquals(
            "示例图库",
            SpiderTokenUtils.normalizeCoreTitle("示例图库 2026/08/02")
        )
    }

    @Test
    fun testStripsDotDate() {
        assertEquals(
            "another one",
            SpiderTokenUtils.normalizeCoreTitle("Another One 2026.05.21")
        )
    }

    @Test
    fun testStripsRefreshEmoji() {
        // \uD83D\uDD04 = 🔄 (U+1F504)
        assertEquals(
            "test",
            SpiderTokenUtils.normalizeCoreTitle("Test \uD83D\uDD04")
        )
    }

    @Test
    fun testCaseInsensitive() {
        assertEquals(
            "hello world",
            SpiderTokenUtils.normalizeCoreTitle("Hello WORLD")
        )
    }

    @Test
    fun testNfkcNormalization() {
        // 全角字符 NFKC 归一化
        assertEquals(
            "helloworld",
            SpiderTokenUtils.normalizeCoreTitle("ＨｅｌｌｏＷｏｒｌｄ")
        )
    }

    @Test
    fun testNullAndBlank() {
        assertEquals("", SpiderTokenUtils.normalizeCoreTitle(null))
        assertEquals("", SpiderTokenUtils.normalizeCoreTitle(""))
        assertEquals("", SpiderTokenUtils.normalizeCoreTitle("   "))
    }

    @Test
    fun testTwoTitlesDifferOnlyByDateAreEqual() {
        // 两个仅日期不同的标题应归一化后相等（用于判断为同一画廊）
        assertEquals(
            SpiderTokenUtils.normalizeCoreTitle("画廊 2026-08-02"),
            SpiderTokenUtils.normalizeCoreTitle("画廊 2026-08-09")
        )
    }

    @Test
    fun testDifferentContentStaysDifferent() {
        // 不同内容不应被归一化视为相等
        assertNotEquals(
            SpiderTokenUtils.normalizeCoreTitle("画廊 A"),
            SpiderTokenUtils.normalizeCoreTitle("画廊 B")
        )
    }
}