package com.hippo.ehviewer.task

import com.hippo.ehviewer.task.DownloadingRecheckTask.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 测试 [DownloadingRecheckTask.pickKeeper] 的「保留最完整者」选择逻辑。
 *
 * 规则：token（图片）数量最多 → 页数最多 → gid 最大（同名最新）。
 */
class DownloadingRecheckKeeperTest {

    @Test
    fun testMostTokensWins() {
        val a = Candidate(gid = 1, pages = 100, tokenCount = 10)
        val b = Candidate(gid = 2, pages = 50, tokenCount = 30)
        val c = Candidate(gid = 3, pages = 80, tokenCount = 20)
        assertEquals(b, DownloadingRecheckTask.pickKeeper(listOf(a, b, c)))
    }

    @Test
    fun testTieOnTokensBiggerPagesWins() {
        val a = Candidate(gid = 1, pages = 20, tokenCount = 50)
        val b = Candidate(gid = 2, pages = 80, tokenCount = 50)
        assertEquals(b, DownloadingRecheckTask.pickKeeper(listOf(a, b)))
    }

    @Test
    fun testTieOnTokensAndPagesNewestGidWins() {
        val a = Candidate(gid = 100, pages = 50, tokenCount = 50)
        val b = Candidate(gid = 200, pages = 50, tokenCount = 50)
        // 同名最新一条：gid 最大
        assertEquals(b, DownloadingRecheckTask.pickKeeper(listOf(a, b)))
    }

    @Test
    fun testSingleCandidate() {
        val a = Candidate(gid = 7, pages = 12, tokenCount = 5)
        assertEquals(a, DownloadingRecheckTask.pickKeeper(listOf(a)))
    }

    @Test
    fun testEmptyReturnsNull() {
        assertNull(DownloadingRecheckTask.pickKeeper(emptyList()))
    }

    @Test
    fun testUnknownTokenCountsFallBackToPagesThenGid() {
        // tokenCount = -1 表示未能拉取到 token：回退到页数、其次 gid
        val a = Candidate(gid = 10, pages = 30, tokenCount = -1)
        val b = Candidate(gid = 11, pages = 40, tokenCount = -1)
        assertEquals(b, DownloadingRecheckTask.pickKeeper(listOf(a, b)))
    }
}
