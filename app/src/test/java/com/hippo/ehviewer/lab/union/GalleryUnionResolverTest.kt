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

package com.hippo.ehviewer.lab.union

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.hippo.ehviewer.lab.LabConfig
import com.hippo.ehviewer.lab.LabConfigStore
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.snapshot.DbSnapshotCache
import com.hippo.ehviewer.lab.snapshot.GallerySnapshotEntry
import com.hippo.ehviewer.lab.snapshot.SnapshotResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 单元测试：GalleryUnionResolver
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GalleryUnionResolverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        context.deleteDatabase("lab_snapshot.db")
        DbSnapshotCache.resetForTest()
        GalleryUnionResolver.resetForTest()
        LabConfigStore.resetForTest()
        // 启用实验室总开关（DbCache 子开关默认开启）
        LabManager.getInstance(context).configStore.replace(LabConfig.defaultConfig())
    }

    @After
    fun tearDown() {
        context.deleteDatabase("lab_snapshot.db")
        DbSnapshotCache.resetForTest()
        GalleryUnionResolver.resetForTest()
        LabConfigStore.resetForTest()
    }

    private fun entry(
        gid: Long, source: String,
        pages: Int = 10, downloadedPages: Int = pages,
        lastUpdated: Long = System.currentTimeMillis(),
    ): GallerySnapshotEntry {
        return GallerySnapshotEntry.Builder()
                .gid(gid)
                .title("Title-$gid")
                .pages(pages)
                .downloadedPages(downloadedPages)
                .fileCount(downloadedPages)
                .state(if (downloadedPages >= pages) 3 else 2)
                .sourceDeviceId(source)
                .lastUpdated(lastUpdated)
                .build()
    }

    @Test
    fun unionGids_returnsAllGids() {
        val cache = DbSnapshotCache.getInstance(context)
        cache.applyResponse(SnapshotResponse.Builder()
                .success(true).version(1L)
                .deviceId("d").deviceName("D").snapshotTime(1)
                .hasMore(false)
                .galleries(listOf(
                        entry(1L, "dev-A"),
                        entry(2L, "dev-A"),
                        entry(1L, "dev-B")  // 同 gid 不同设备
                )).build())

        val resolver = GalleryUnionResolver.getInstance(context)
        resolver.refresh()
        val gids = resolver.unionGids()
        assertEquals(2, gids.size)
        assertTrue(gids.contains(1L))
        assertTrue(gids.contains(2L))
    }

    @Test
    fun unionDownloadedPages_returnsMaxAcrossDevices() {
        val cache = DbSnapshotCache.getInstance(context)
        cache.applyResponse(SnapshotResponse.Builder()
                .success(true).version(1L)
                .deviceId("d").deviceName("D").snapshotTime(1)
                .hasMore(false)
                .galleries(listOf(
                        entry(1L, "dev-A", pages = 10, downloadedPages = 3),
                        entry(1L, "dev-B", pages = 10, downloadedPages = 8)
                )).build())

        val resolver = GalleryUnionResolver.getInstance(context)
        resolver.refresh()
        assertEquals(8, resolver.unionDownloadedPages(1L))
    }

    @Test
    fun computeLocalMissing_returnsMissingPages() {
        val cache = DbSnapshotCache.getInstance(context)
        cache.applyResponse(SnapshotResponse.Builder()
                .success(true).version(1L)
                .deviceId("d").deviceName("D").snapshotTime(1)
                .hasMore(false)
                .galleries(listOf(
                        entry(1L, "dev-B", pages = 10, downloadedPages = 8)
                )).build())

        val resolver = GalleryUnionResolver.getInstance(context)
        resolver.refresh()
        // 本机 0，union=8，应该返回 1..8 已覆盖（baseline=8），9, 10 是缺
        val missing = resolver.computeLocalMissing(1L, 0)
        assertEquals(listOf(9, 10), missing)

        // 本机已有 5 页：baseline = max(5, 8) = 8 → 同上
        val missing2 = resolver.computeLocalMissing(1L, 5)
        assertEquals(listOf(9, 10), missing2)
    }

    @Test
    fun pickBestSource_choosesDeviceWithMostPages() {
        val cache = DbSnapshotCache.getInstance(context)
        // 让本机 deviceId 与 LabManager 一致（这里不冲突，因为 LabManager 单例 deviceId 是随机 UUID）
        // dev-A 进度 = 5, dev-B 进度 = 9 → 选 dev-B
        cache.applyResponse(SnapshotResponse.Builder()
                .success(true).version(1L)
                .deviceId("d").deviceName("D").snapshotTime(1)
                .hasMore(false)
                .galleries(listOf(
                        entry(1L, "dev-A", pages = 10, downloadedPages = 5),
                        entry(1L, "dev-B", pages = 10, downloadedPages = 9)
                )).build())

        val resolver = GalleryUnionResolver.getInstance(context)
        resolver.refresh()
        assertEquals("dev-B", resolver.pickBestSource(1L))
    }

    @Test
    fun uniqueGalleryCount_reflectsUnion() {
        val cache = DbSnapshotCache.getInstance(context)
        cache.applyResponse(SnapshotResponse.Builder()
                .success(true).version(1L)
                .deviceId("d").deviceName("D").snapshotTime(1)
                .hasMore(false)
                .galleries(listOf(
                        entry(1L, "dev-A"),
                        entry(2L, "dev-A"),
                        entry(1L, "dev-B")
                )).build())

        val resolver = GalleryUnionResolver.getInstance(context)
        resolver.refresh()
        assertEquals(2, resolver.uniqueGalleryCount())
    }

    @Test
    fun duplicateCount_countsGidsCompletedOnMultipleDevices() {
        val cache = DbSnapshotCache.getInstance(context)
        // gid=1 在 dev-A + dev-B 都完成；gid=2 只在 dev-A 完成
        cache.applyResponse(SnapshotResponse.Builder()
                .success(true).version(1L)
                .deviceId("d").deviceName("D").snapshotTime(1)
                .hasMore(false)
                .galleries(listOf(
                        entry(1L, "dev-A", pages = 10, downloadedPages = 10),
                        entry(1L, "dev-B", pages = 10, downloadedPages = 10),
                        entry(2L, "dev-A", pages = 10, downloadedPages = 10)
                )).build())

        val resolver = GalleryUnionResolver.getInstance(context)
        resolver.refresh()
        assertEquals(1, resolver.duplicateCount())
    }

    @Test
    fun refresh_invalidatesCache() {
        val cache = DbSnapshotCache.getInstance(context)
        cache.applyResponse(SnapshotResponse.Builder()
                .success(true).version(1L)
                .deviceId("d").deviceName("D").snapshotTime(1)
                .hasMore(false)
                .galleries(listOf(entry(1L, "dev-A"))).build())

        val resolver = GalleryUnionResolver.getInstance(context)
        resolver.refresh()
        assertEquals(1, resolver.uniqueGalleryCount())

        // 写入新数据后必须 invalidate 才看得见
        cache.applyResponse(SnapshotResponse.Builder()
                .success(true).version(2L)
                .deviceId("d").deviceName("D").snapshotTime(2)
                .hasMore(false)
                .galleries(listOf(entry(2L, "dev-A"))).build())

        // 不 invalidate → 还是旧数据
        assertEquals(1, resolver.uniqueGalleryCount())

        resolver.invalidate()
        resolver.refresh()
        assertEquals(2, resolver.uniqueGalleryCount())
    }
}