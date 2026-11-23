package com.hippo.ehviewer.task

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SimpleScanCache] 的容量回归测试。
 *
 * 历史问题：hash 集合缓存没有上限，依次扫描大量画廊时会把每本画廊的完整
 * pToken 集合都留在堆里，最终在下载/索引线程上 OOM。这里确认缓存会按
 * 访问顺序淘汰旧条目，而不是无限增长。
 */
class SimpleScanCacheTest {

    @Before
    fun setUp() {
        SimpleScanCache.invalidate()
    }

    @After
    fun tearDown() {
        SimpleScanCache.invalidate()
    }

    @Test
    fun hashSetCacheKeepsRecentEntries() {
        SimpleScanCache.putHashSet(1L, hashSetOf("a", "b"))

        assertNotNull(SimpleScanCache.getHashSet(1L))
    }

    @Test
    fun hashSetCacheEvictsOldEntriesWhenFull() {
        // 缓存上限为 32 个画廊；写入 200 个后，最早的条目必须已被淘汰。
        for (gid in 1L..200L) {
            SimpleScanCache.putHashSet(gid, HashSet((0 until 50).map { "hash-$gid-$it" }))
        }

        assertNull(SimpleScanCache.getHashSet(1L))
        assertNotNull(SimpleScanCache.getHashSet(200L))
    }

    @Test
    fun invalidateClearsCachedHashSets() {
        SimpleScanCache.putHashSet(42L, hashSetOf("x"))

        SimpleScanCache.invalidate()

        assertNull(SimpleScanCache.getHashSet(42L))
    }

    @Test
    fun cachedCandidatesAreDroppedOnInvalidate() {
        SimpleScanCache.putCandidates(emptyList())

        SimpleScanCache.invalidate()

        assertTrue(SimpleScanCache.getCandidates() == null)
    }
}
