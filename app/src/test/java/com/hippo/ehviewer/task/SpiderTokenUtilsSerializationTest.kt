package com.hippo.ehviewer.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试 [SpiderTokenUtils] 序列化/反序列化与基本工具方法。
 *
 * 对涉及 [com.hippo.ehviewer.client.EhUtils.getSuitableTitle] 的同名同作者判定，
 * 改用 [SpiderTokenUtils.isSameNormalizedTitleAndAuthor] 入口，避开 Settings 依赖。
 */
class SpiderTokenUtilsSerializationTest {

    @Test
    fun testParsePtokenStringRoundTrip() {
        val original = hashSetOf("aaaa", "bbbb", "cccc")
        val serialized = SpiderTokenUtils.serializePtokens(original)
        val parsed = SpiderTokenUtils.parsePtokenString(serialized)
        assertEquals(original, parsed)
    }

    @Test
    fun testParsePtokenStringSkipsFailedToken() {
        // "failed" 是 SpiderInfo.TOKEN_FAILED 的字面值，应在反序列化时被过滤
        val parsed = SpiderTokenUtils.parsePtokenString("aaaa,failed,bbbb")
        assertEquals(setOf("aaaa", "bbbb"), parsed)
    }

    @Test
    fun testParsePtokenStringSkipsBlankTokens() {
        val parsed = SpiderTokenUtils.parsePtokenString("aaaa, ,bbbb,")
        assertEquals(setOf("aaaa", "bbbb"), parsed)
    }

    @Test
    fun testParsePtokenStringEmpty() {
        assertTrue(SpiderTokenUtils.parsePtokenString(null).isEmpty())
        assertTrue(SpiderTokenUtils.parsePtokenString("").isEmpty())
        assertTrue(SpiderTokenUtils.parsePtokenString("   ").isEmpty())
    }

    @Test
    fun testSerializeEmpty() {
        assertEquals("", SpiderTokenUtils.serializePtokens(emptySet()))
    }

    @Test
    fun testIsSameNormalizedTitleAndAuthorMatchesSameTitle() {
        assertTrue(
            SpiderTokenUtils.isSameNormalizedTitleAndAuthor(
                "test", "alice", "test", "alice"
            )
        )
    }

    @Test
    fun testIsSameNormalizedTitleAndAuthorRejectsDifferentAuthors() {
        assertFalse(
            SpiderTokenUtils.isSameNormalizedTitleAndAuthor(
                "test", "alice", "test", "bob"
            )
        )
    }

    @Test
    fun testIsSameNormalizedTitleAndAuthorIgnoresDateInTitle() {
        // 归一化后两个仅日期不同的标题应相等
        val title1 = SpiderTokenUtils.normalizeCoreTitle("Gallery 2026-08-02")
        val title2 = SpiderTokenUtils.normalizeCoreTitle("Gallery 2026-08-09")
        assertTrue(
            SpiderTokenUtils.isSameNormalizedTitleAndAuthor(
                title1, "u", title2, "u"
            )
        )
    }

    @Test
    fun testIsSameNormalizedTitleAndAuthorIgnoresEmptyUploader() {
        // 作者为空时不强制要求一致（部分图库没有作者）
        assertTrue(
            SpiderTokenUtils.isSameNormalizedTitleAndAuthor(
                "test", "", "test", "someone"
            )
        )
    }

    @Test
    fun testIsSameNormalizedTitleAndAuthorRejectsDifferentTitle() {
        assertFalse(
            SpiderTokenUtils.isSameNormalizedTitleAndAuthor(
                "test", "u", "different", "u"
            )
        )
    }

    @Test
    fun testIsSameNormalizedTitleAndAuthorRejectsEmptyTitle() {
        assertFalse(
            SpiderTokenUtils.isSameNormalizedTitleAndAuthor(
                "", "u", "test", "u"
            )
        )
        assertFalse(
            SpiderTokenUtils.isSameNormalizedTitleAndAuthor(
                "test", "u", "", "u"
            )
        )
    }

    @Test
    fun testIsSameNormalizedTitleAndAuthorCaseInsensitiveUploader() {
        assertTrue(
            SpiderTokenUtils.isSameNormalizedTitleAndAuthor(
                "test", "Alice", "test", "ALICE"
            )
        )
    }
}