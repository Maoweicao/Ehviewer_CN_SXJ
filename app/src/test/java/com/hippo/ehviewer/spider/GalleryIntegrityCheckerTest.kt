package com.hippo.ehviewer.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GalleryIntegrityChecker.summarize] 的纯判定测试。
 *
 * 目录 I/O 部分由真机清单覆盖；这里锁定「缺失 / 0 字节 / 非文件」到
 * [GalleryIntegrityResult] 的汇总语义，避免三处调用点判定漂移。
 */
class GalleryIntegrityCheckerTest {

    @Test
    fun testAllPagesValidIsComplete() {
        val result = GalleryIntegrityChecker.summarize(
            expectedPages = 3,
            statuses = listOf(PageStatus.VALID, PageStatus.VALID, PageStatus.VALID),
        )

        assertTrue(result.isComplete)
        assertEquals(3, result.validPages)
        assertEquals(0, result.legacy)
        assertTrue(result.invalidIndices.isEmpty())
    }

    @Test
    fun testMissingPageCountsAsLegacy() {
        val result = GalleryIntegrityChecker.summarize(
            expectedPages = 3,
            statuses = listOf(PageStatus.VALID, PageStatus.MISSING, PageStatus.VALID),
        )

        assertFalse(result.isComplete)
        assertEquals(2, result.validPages)
        assertEquals(1, result.legacy)
        assertEquals(listOf(1), result.missingIndices)
        assertEquals(listOf(1), result.invalidIndices)
    }

    @Test
    fun testEmptyAndNonFileAreInvalid() {
        val result = GalleryIntegrityChecker.summarize(
            expectedPages = 3,
            statuses = listOf(PageStatus.VALID, PageStatus.EMPTY, PageStatus.NON_FILE),
        )

        assertFalse(result.isComplete)
        assertEquals(1, result.validPages)
        assertEquals(2, result.legacy)
        assertEquals(listOf(1), result.emptyIndices)
        assertEquals(listOf(2), result.nonFileIndices)
        assertEquals(listOf(1, 2), result.invalidIndices)
    }

    @Test
    fun testMissingStatusesBeyondListAreCounted() {
        // 扫描被中断或页数不一致时，未提供状态的页按缺失处理
        val result = GalleryIntegrityChecker.summarize(
            expectedPages = 4,
            statuses = listOf(PageStatus.VALID, PageStatus.VALID),
        )

        assertFalse(result.isComplete)
        assertEquals(2, result.validPages)
        assertEquals(2, result.legacy)
        assertEquals(listOf(2, 3), result.missingIndices)
    }

    @Test
    fun testMissingDirectoryIsNeverComplete() {
        val result = GalleryIntegrityChecker.summarize(
            expectedPages = 0,
            statuses = emptyList(),
            dirMissing = true,
            spiderInfoMissing = true,
        )

        assertFalse(result.isComplete)
        assertTrue(result.dirMissing)
        assertTrue(result.spiderInfoMissing)
        assertEquals(0, result.legacy)
    }

    @Test
    fun testZeroExpectedPagesIsIncomplete() {
        val result = GalleryIntegrityChecker.summarize(
            expectedPages = 0,
            statuses = emptyList(),
        )

        assertFalse(result.isComplete)
        assertEquals(0, result.legacy)
    }

    @Test
    fun testSpiderInfoMissingIsReportedForManualTask() {
        val result = GalleryIntegrityChecker.summarize(
            expectedPages = 2,
            statuses = listOf(PageStatus.VALID, PageStatus.VALID),
            spiderInfoMissing = true,
        )

        // 文件层面完整，但手动校验任务仍会据此重置（保持改造前语义）
        assertTrue(result.isComplete)
        assertTrue(result.spiderInfoMissing)
    }
}
