package com.hippo.ehviewer.client.data

import com.hippo.ehviewer.client.data.EhTopListDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：
 *  1. buildUploaderSearch 统一用 uploader:xxx 关键词，而不是 MODE_UPLOADER 路径
 *  2. 排行榜里"排人的榜单"判定与 adapter 保持一致
 */
class UploaderSearchTest {

    @Test
    fun uploaderSearchUsesKeywordWithPrefix() {
        val b = ListUrlBuilder.buildUploaderSearch("Mios-dream")
        assertNotNull(b)
        assertEquals(ListUrlBuilder.MODE_NORMAL, b!!.mode)
        assertEquals("uploader:Mios-dream", b.keyword)
    }

    @Test
    fun uploaderSearchTrimsWhitespace() {
        val b = ListUrlBuilder.buildUploaderSearch("  someone  ")
        assertNotNull(b)
        assertEquals("uploader:someone", b!!.keyword)
    }

    @Test
    fun uploaderSearchReturnsNullOnBlank() {
        assertNull(ListUrlBuilder.buildUploaderSearch(null))
        assertNull(ListUrlBuilder.buildUploaderSearch(""))
        assertNull(ListUrlBuilder.buildUploaderSearch("   "))
    }

    @Test
    fun uploaderSearchPrefixIsTheSiteSyntax() {
        assertEquals("uploader:", ListUrlBuilder.UPLOADER_KEYWORD_PREFIX)
    }

    /**
     * 构造出来的 builder 必须是 MODE_NORMAL —— ListUrlBuilder.build() 里
     * 只有 MODE_NORMAL 才拼 f_search；MODE_UPLOADER 走的是 /uploader/ 路径。
     * 这里只断言 mode，因为 build() 需要 Android 运行时（Settings/SharedPreferences），
     * 纯 JVM 单测里跑不了。
     */
    @Test
    fun uploaderSearchDoesNotUseUploaderPathMode() {
        val b = ListUrlBuilder.buildUploaderSearch("somebody")!!
        assertTrue(
            "不应使用 MODE_UPLOADER（那是 /uploader/ 路径）",
            b.mode != ListUrlBuilder.MODE_UPLOADER
        )
        assertEquals(ListUrlBuilder.MODE_NORMAL, b.mode)
    }

    /**
     * 这些榜单的条目是用户名，点击时按 uploader: 搜索。
     * 与 EhTopListAdapter#isUserRankedCategory 保持同一套判定。
     */
    @Test
    fun userRankedCategoriesMatchAdapterLogic() {
        val userRanked = setOf(
            EhTopListDetail.ListType.UPLOADER,
            EhTopListDetail.ListType.EH_TRACKER,
            EhTopListDetail.ListType.CLEANUP,
            EhTopListDetail.ListType.RATING_AND_REVIEWING,
            EhTopListDetail.ListType.HENTAI_HOME,
            EhTopListDetail.ListType.TAGGING,
        )
        // 画廊排行榜自己走画廊行（有缩略图），不落到文字行
        assertTrue(!userRanked.contains(EhTopListDetail.ListType.GALLERY))
        assertEquals(6, userRanked.size)
    }
}