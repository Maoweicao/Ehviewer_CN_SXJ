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

package com.hippo.ehviewer.lab.snapshot

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
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
import java.io.File

/**
 * 单元测试：DbSnapshotCache
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DbSnapshotCacheTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        context.deleteDatabase(SnapshotDbHelper.DB_NAME)
        DbSnapshotCache.resetForTest()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(SnapshotDbHelper.DB_NAME)
        DbSnapshotCache.resetForTest()
    }

    private fun buildEntry(
        gid: Long,
        source: String,
        title: String = "Title-$gid",
        pages: Int = 10,
        downloadedPages: Int = pages,
        lastUpdated: Long = System.currentTimeMillis(),
        deleted: Boolean = false,
    ): GallerySnapshotEntry {
        return GallerySnapshotEntry.Builder()
                .gid(gid)
                .title(title)
                .token("tok")
                .category(2)
                .pages(pages)
                .downloadedPages(downloadedPages)
                .fileCount(downloadedPages)
                .state(if (downloadedPages >= pages) 3 else 2)
                .sourceDeviceId(source)
                .sourceDeviceName("Device-$source")
                .lastUpdated(lastUpdated)
                .deleted(deleted)
                .build()
    }

    private fun buildResponse(
        version: Long,
        vararg entries: GallerySnapshotEntry,
        removedGids: List<Long> = emptyList(),
    ): SnapshotResponse {
        return SnapshotResponse.Builder()
                .success(true)
                .version(version)
                .deviceId("test-device")
                .deviceName("TestDevice")
                .snapshotTime(System.currentTimeMillis())
                .hasMore(false)
                .galleries(entries.toList())
                .removedGids(removedGids)
                .build()
    }

    @Test
    fun applyResponse_insertsNewEntries() {
        val cache = DbSnapshotCache.getInstance(context)
        val resp = buildResponse(1L,
                buildEntry(100L, "dev-A"),
                buildEntry(101L, "dev-A"),
                buildEntry(102L, "dev-B"))
        cache.applyResponse(resp)

        val all = cache.querySince(0, 100)
        assertEquals(3, all.size)
        assertEquals(0L, cache.getLastVersion())
    }

    @Test
    fun applyResponse_updatesLastVersion() {
        val cache = DbSnapshotCache.getInstance(context)
        cache.applyResponse(buildResponse(42L, buildEntry(1L, "dev-A")))
        assertEquals(42L, cache.getLastVersion())
    }

    @Test
    fun applyResponse_lastUpdatedWins_dropsStaleEntry() {
        val cache = DbSnapshotCache.getInstance(context)
        val t1 = 1000L
        val t2 = 2000L
        // 第一次同步：source=A 时间戳=2000
        cache.applyResponse(buildResponse(1L,
                buildEntry(100L, "dev-A", lastUpdated = t2, title = "newer")))
        // 第二次同步：source=A 时间戳=1000（更旧）
        cache.applyResponse(buildResponse(2L,
                buildEntry(100L, "dev-A", lastUpdated = t1, title = "older")))

        val entries = cache.queryByGid(100L)
        assertEquals(1, entries.size)
        assertEquals("newer", entries[0].title)
    }

    @Test
    fun applyResponse_newerTimestamp_overwrites() {
        val cache = DbSnapshotCache.getInstance(context)
        cache.applyResponse(buildResponse(1L,
                buildEntry(100L, "dev-A", lastUpdated = 1000L, title = "old")))
        cache.applyResponse(buildResponse(2L,
                buildEntry(100L, "dev-A", lastUpdated = 2000L, title = "new")))

        val list = cache.queryByGid(100L)
        assertEquals("new", list[0].title)
    }

    @Test
    fun queryByGid_excludesDeleted() {
        val cache = DbSnapshotCache.getInstance(context)
        cache.applyResponse(buildResponse(1L,
                buildEntry(100L, "dev-A", deleted = true),
                buildEntry(101L, "dev-A")))

        assertEquals(0, cache.queryByGid(100L).size)
        assertEquals(1, cache.queryByGid(101L).size)
    }

    @Test
    fun aggregateByGid_groupsByGid() {
        val cache = DbSnapshotCache.getInstance(context)
        cache.applyResponse(buildResponse(1L,
                buildEntry(100L, "dev-A"),
                buildEntry(100L, "dev-B"),
                buildEntry(101L, "dev-A")))

        val map = cache.aggregateByGid()
        assertEquals(2, map.size)
        assertEquals(2, map[100L]!!.size)
        assertEquals(1, map[101L]!!.size)
    }

    @Test
    fun knownSourceDevices_returnsDistinctIds() {
        val cache = DbSnapshotCache.getInstance(context)
        cache.applyResponse(buildResponse(1L,
                buildEntry(100L, "dev-A"),
                buildEntry(101L, "dev-A"),
                buildEntry(102L, "dev-B")))

        val set = cache.knownSourceDevices()
        assertEquals(2, set.size)
        assertTrue(set.contains("dev-A"))
        assertTrue(set.contains("dev-B"))
    }

    @Test
    fun clearAll_removesAllData() {
        val cache = DbSnapshotCache.getInstance(context)
        cache.applyResponse(buildResponse(1L, buildEntry(100L, "dev-A")))
        assertEquals(1, cache.querySince(0, 100).size)
        cache.clearAll()
        assertEquals(0, cache.querySince(0, 100).size)
        assertEquals(-1L, cache.getLastVersion())
    }

    @Test
    fun applyResponse_handlesFavorites() {
        val cache = DbSnapshotCache.getInstance(context)
        val fav = FavoriteSnapshotEntry(100L, "收藏", "备注", System.currentTimeMillis())
        val resp = SnapshotResponse.Builder()
                .success(true)
                .version(1L)
                .deviceId("d")
                .deviceName("D")
                .snapshotTime(System.currentTimeMillis())
                .hasMore(false)
                .favorites(listOf(fav))
                .build()
        cache.applyResponse(resp)
        assertEquals(1L, cache.getLastVersion())
    }

    @Test
    fun applyResponse_marksRemovedGids() {
        val cache = DbSnapshotCache.getInstance(context)
        // 先插入
        cache.applyResponse(buildResponse(1L, buildEntry(100L, "dev-A")))
        // 再标记删除
        cache.applyResponse(buildResponse(2L,
                buildEntry(101L, "dev-A"),
                removedGids = listOf(100L)))

        // 已删除的 gid 不应在 aggregateByGid / queryByGid 中出现
        assertEquals(0, cache.queryByGid(100L).size)
        assertEquals(1, cache.queryByGid(101L).size)
    }
}