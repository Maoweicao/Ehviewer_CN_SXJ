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
import android.os.Handler
import android.os.Looper
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.log.SnapshotLogger
import com.hippo.ehviewer.lab.snapshot.DbSnapshotCache
import com.hippo.ehviewer.lab.snapshot.GallerySnapshotEntry
import java.util.Collections

/**
 * 画廊联合视图解析器
 *
 * 协议对应：v3.0 §5.20 §5.21。
 *
 * 核心职责：把本地下载 + 远端快照聚合成「虚拟设备视图」。
 * <ul>
 *   <li>[aggregateByGid] → gid → 跨设备最新条目集（union）</li>
 *   <li>[computeLocalMissing] → 本机缺哪些页（增量续传用）</li>
 *   <li>[computeUnionProgress] → 跨设备最深的 downloadedPages</li>
 *   <li>[pickBestSource] → 给定 gid，最优下载源</li>
 * </ul>
 */
class GalleryUnionResolver private constructor(context: Context) {

    private val cache: DbSnapshotCache = DbSnapshotCache.getInstance(context)
    private val labManager: LabManager = LabManager.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedUnion: Map<Long, List<GallerySnapshotEntry>>? = null

    private val listeners = mutableListOf<Listener>()

    fun addListener(l: Listener) {
        if (l != null && !listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /**
     * 重新加载缓存。本地 SQLite 查询，UI 线程也可调用（实测 < 50ms）。
     */
    fun refresh() {
        try {
            cachedUnion = cache.aggregateByGid()
            mainHandler.post {
                synchronized(listeners) { listeners.toList() }.forEach { it.onUnionChanged() }
            }
            SnapshotLogger.d(
                "GalleryUnionResolver", "Refreshed union: ${cachedUnion?.size ?: 0} unique gids")
        } catch (e: Exception) {
            SnapshotLogger.e("GalleryUnionResolver", "Failed to refresh union", e)
        }
    }

    /**
     * 清除内存索引。下次查询会触发 [refresh]。
     */
    fun invalidate() {
        cachedUnion = null
    }

    private fun getOrRefresh(): Map<Long, List<GallerySnapshotEntry>>? {
        val map = cachedUnion
        if (map != null) return map
        refresh()
        return cachedUnion
    }

    /**
     * 获取某 gid 在所有设备的快照条目列表（含本机和其他设备）。
     * 已按 [lastUpdated] DESC 排序。
     */
    fun entriesFor(gid: Long): List<GallerySnapshotEntry> {
        val map = getOrRefresh() ?: return emptyList()
        return map[gid] ?: emptyList()
    }

    /**
     * 跨设备去重后所有画廊的 gid 集合。
     */
    fun unionGids(): Set<Long> = getOrRefresh()?.keys ?: emptySet()

    /**
     * 联合下载进度：所有设备中该 gid 已下载的最大页数。-1 表示未知。
     */
    fun unionDownloadedPages(gid: Long): Int {
        var max = 0
        var any = false
        for (e in entriesFor(gid)) {
            any = true
            if (e.downloadedPages > max) max = e.downloadedPages
        }
        return if (any) max else -1
    }

    /**
     * 联合页数：所有设备中该 gid pages 的最大值。
     */
    fun unionPages(gid: Long): Int {
        var max = 0
        var any = false
        for (e in entriesFor(gid)) {
            any = true
            if (e.pages > max) max = e.pages
        }
        return if (any) max else -1
    }

    /**
     * 本机缺失的页号列表（基于 union）。
     * 算法：missingPages = [i for i in 1..unionPages if i > unionDownloaded]
     */
    fun computeLocalMissing(gid: Long, localDownloaded: Int): List<Int> {
        val total = unionPages(gid)
        val covered = unionDownloadedPages(gid)
        if (total <= 0) return emptyList()
        val baseline = maxOf(localDownloaded, covered)
        if (baseline >= total) return emptyList()
        val missing = ArrayList<Int>(total - baseline)
        for (i in baseline + 1..total) missing.add(i)
        return missing
    }

    /**
     * 挑选「当前持有该 gid 最多页」的远端设备作为接力源。
     */
    fun pickBestSource(gid: Long): String? {
        var best: GallerySnapshotEntry? = null
        var bestPages = -1
        val selfId = labManager.getSelfDeviceId()
        for (e in entriesFor(gid)) {
            if (selfId == e.sourceDeviceId) continue // 跳过本机
            if (e.downloadedPages > bestPages) {
                bestPages = e.downloadedPages
                best = e
            }
        }
        return best?.sourceDeviceId
    }

    fun totalDownloadedPages(): Long {
        val map = getOrRefresh() ?: return 0
        var sum = 0L
        for (list in map.values) {
            var max = 0
            for (e in list) {
                if (e.downloadedPages > max) max = e.downloadedPages
            }
            sum += max
        }
        return sum
    }

    fun uniqueGalleryCount(): Int = getOrRefresh()?.size ?: 0

    fun duplicateCount(): Int {
        val map = getOrRefresh() ?: return 0
        var dups = 0
        for (list in map.values) {
            var completed = 0
            for (e in list) {
                if (e.isComplete()) completed++
            }
            if (completed > 1) dups++
        }
        return dups
    }

    fun knownDeviceIds(): Set<String> {
        val map = getOrRefresh() ?: return emptySet()
        val ids = HashSet<String>()
        for (list in map.values) for (e in list) ids.add(e.sourceDeviceId)
        return ids
    }

    fun deviceDisplayNames(): Map<String, String> {
        val map = getOrRefresh() ?: return emptyMap()
        val out = HashMap<String, String>()
        for (list in map.values) {
            for (e in list) {
                e.sourceDeviceName?.takeIf { it.isNotEmpty() }?.let { out[e.sourceDeviceId] = it }
            }
        }
        return out
    }

    interface Listener {
        fun onUnionChanged()
    }

    companion object {
        @Volatile private var INSTANCE: GalleryUnionResolver? = null

        @JvmStatic
        fun resetForTest() {
            synchronized(GalleryUnionResolver::class.java) {
                INSTANCE = null
            }
        }

        @JvmStatic
        fun getInstance(context: Context): GalleryUnionResolver {
            return INSTANCE ?: synchronized(GalleryUnionResolver::class.java) {
                INSTANCE ?: GalleryUnionResolver(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}