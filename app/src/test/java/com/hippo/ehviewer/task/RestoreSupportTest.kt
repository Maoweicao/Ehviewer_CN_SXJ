package com.hippo.ehviewer.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RestoreSupport] 的纯函数测试。
 *
 * 目录名解析是恢复/梳理流程判断“某个文件夹属于哪个画廊”的兜底手段：
 * 解析错了会把别的画廊的目录当成重复项处理，所以这里锁定边界行为。
 */
class RestoreSupportTest {

    @Test
    fun testParseGidFromDirName() {
        assertEquals(123456L, RestoreSupport.parseGidFromDirName("123456"))
        assertEquals(123456L, RestoreSupport.parseGidFromDirName("123456-some title"))
        assertEquals(1L, RestoreSupport.parseGidFromDirName("1"))
        assertNull(RestoreSupport.parseGidFromDirName(""))
        assertNull(RestoreSupport.parseGidFromDirName("title-only"))
        assertNull(RestoreSupport.parseGidFromDirName("12345678901234567890123"))
    }
}
