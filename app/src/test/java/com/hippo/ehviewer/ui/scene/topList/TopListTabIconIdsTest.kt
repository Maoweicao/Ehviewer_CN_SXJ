package com.hippo.ehviewer.ui.scene.topList

import com.hippo.ehviewer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：分类 tab 图标必须由代码常量 TOP_LIST_TAB_ICONS 提供，
 * 不能再从资源数组里读。
 *
 * 背景：<array>@drawable/x</array> 读出来是 null，
 * <integer-array>@drawable/x</integer-array> 读出来全是 0，
 * 两种资源写法在本项目都会导致图标拿不到（后者直接抛 Resource ID #0x0 崩溃）。
 */
class TopListTabIconIdsTest {

    @Test
    fun iconIdsAreSevenDistinctNonZero() {
        assertEquals("分类数与图标数必须一致", 7, TOP_LIST_TAB_ICONS.size)
        val distinct = TOP_LIST_TAB_ICONS.toSet()
        assertEquals("图标 id 不应有重复", 7, distinct.size)
        for ((i, id) in TOP_LIST_TAB_ICONS.withIndex()) {
            assertTrue("第 $i 个图标 id 为 0", id != 0)
        }
    }

    @Test
    fun iconIdsMatchExpectedDrawablesInOrder() {
        val expected = listOf(
            R.drawable.ic_toplist_gallery,      // 0 画廊排行榜
            R.drawable.ic_toplist_uploader,     // 1 上传者排行榜
            R.drawable.ic_toplist_tag,          // 2 标签排行榜
            R.drawable.ic_toplist_hentai_home,  // 3 Hentai@Home 排行榜
            R.drawable.ic_toplist_ehtracker,    // 4 EHTracker 排行榜
            R.drawable.ic_toplist_cleanup,      // 5 清理排行榜
            R.drawable.ic_toplist_rating,       // 6 评分与评论排行榜
        )
        assertEquals(expected, TOP_LIST_TAB_ICONS.toList())
    }

    @Test
    fun timeBucketLabelsAreFourNonZero() {
        assertEquals(4, TIME_BUCKET_LABELS.size)
        for ((i, id) in TIME_BUCKET_LABELS.withIndex()) {
            assertTrue("第 $i 个时间分段 id 为 0", id != 0)
        }
    }
}
