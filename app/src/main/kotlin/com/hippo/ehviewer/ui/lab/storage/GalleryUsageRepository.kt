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

package com.hippo.ehviewer.ui.lab.storage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hippo.ehviewer.DownloadedFileManager
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.callBack.DownloadSearchCallback
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DeleteProtectHelper
import com.hippo.ehviewer.download.filter.FilterPresets
import com.hippo.ehviewer.sync.DownloadListInfosExecutor
import java.util.concurrent.Executors

/**
 * 画廊占用分析的数据层。
 *
 * - 按分类分组：直接用 [DownloadInfo.category] 聚合；
 * - 按占用特征分组：复用下载列表的快捷筛选（[DownloadListInfosExecutor]），
 *   口径与用户在「设置 → 下载 → 快捷筛选」里看到的完全一致；
 * - 受保护判定：跟随「设置 → 下载 → 删除保护」（[DeleteProtectHelper]），
 *   命中的画廊（已收藏 / 递进合并过多）在清理页面默认锁定，避免误删。
 */
object GalleryUsageRepository {

    private const val TAG = "GalleryUsageRepository"

    /** SQLite `IN` 参数上限保守取值，分批查询避免超限。 */
    private const val QUERY_CHUNK = 200

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GalleryUsage").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 一条画廊的占用记录。 */
    data class GalleryUsage(
        val info: DownloadInfo,
        val size: Long,
        /** 命中「设置 → 下载 → 删除保护」的画廊带有保护原因，否则为 null。 */
        val protection: DeleteProtectHelper.Protection?,
    ) {
        val gid: Long get() = info.gid

        /** 受保护：清理时默认锁定。 */
        val protectedFromDelete: Boolean get() = protection != null
    }

    /** 一个分组（分类或占用特征）。 */
    data class UsageGroup(
        /** 稳定 id：分类用 "cat:<n>"，特征用快捷筛选 id。 */
        val id: String,
        val title: String,
        val items: List<GalleryUsage>,
        val color: Int,
        /** 分组维度，用于决定子页面文案。 */
        val dimension: Dimension,
    ) {
        val count: Int get() = items.size
        val totalSize: Long get() = items.sumOf { it.size }
        val largest: GalleryUsage? get() = items.maxByOrNull { it.size }
    }

    enum class Dimension { CATEGORY, FEATURE, PROTECTED }

    /** 加载全部下载画廊的占用信息，回调在主线程执行。 */
    fun loadUsages(onResult: (List<GalleryUsage>) -> Unit) {
        worker.execute {
            val usages = runCatching { loadUsagesBlocking() }.getOrDefault(emptyList())
            mainHandler.post { onResult(usages) }
        }
    }

    private fun loadUsagesBlocking(): List<GalleryUsage> {
        val downloads = runCatching { EhDB.getAllDownloadInfo() }.getOrDefault(emptyList())
        if (downloads.isEmpty()) return emptyList()
        val sizes = gallerySizeMap(downloads)
        // 递进传递统计要全表扫，提前在后台把索引建好
        runCatching { DeleteProtectHelper.warmUpAsync() }
        return downloads
            .map { info ->
                val size = sizes[info.gid] ?: 0L
                // 顺带写进 DownloadInfo.fileSize，清理页通过 Intent 传递时能一起带走
                info.fileSize = size
                GalleryUsage(
                    info = info,
                    size = size,
                    protection = runCatching { DeleteProtectHelper.isProtected(info) }.getOrNull(),
                )
            }
            .sortedByDescending { it.size }
    }

    /** 批量取画廊文件总大小（走物化视图缓存，避免重复扫盘）。 */
    fun gallerySizeMap(downloads: List<DownloadInfo>): Map<Long, Long> {
        val manager = runCatching { DownloadedFileManager.getInstance() }.getOrNull()
            ?: return emptyMap()
        val result = HashMap<Long, Long>(downloads.size * 2)
        for (chunk in downloads.chunked(QUERY_CHUNK)) {
            val gids = chunk.map { it.gid }
            runCatching { manager.getGalleryFilesTotalSizeMap(gids) }
                .getOrDefault(emptyMap())
                .forEach { (gid, size) -> result[gid] = size }
        }
        return result
    }

    /** 受保护画廊（命中删除保护规则）。 */
    fun protectedUsages(usages: List<GalleryUsage>): List<GalleryUsage> =
        usages.filter { it.protectedFromDelete }

    /** 按画廊分类聚合，占用从大到小。 */
    fun groupByCategory(context: Context, usages: List<GalleryUsage>): List<UsageGroup> =
        usages.groupBy { it.info.category }
            .map { (category, items) ->
                UsageGroup(
                    id = "cat:$category",
                    title = EhUtils.getCategoryName(context, category),
                    items = items.sortedByDescending { it.size },
                    color = EhUtils.getCategoryColor(category),
                    dimension = Dimension.CATEGORY,
                )
            }
            .sortedByDescending { it.totalSize }

    /**
     * 按“占用特征”聚合：逐条执行下载列表的快捷筛选，再统计各自占用。
     *
     * 快捷筛选本身已经叠加了全局排除规则，因此被排除规则命中的画廊
     * 不会出现在这些分组里。
     */
    fun groupByFeature(context: Context, usages: List<GalleryUsage>, onResult: (List<UsageGroup>) -> Unit) {
        val appContext = context.applicationContext
        val byGid = HashMap<Long, GalleryUsage>(usages.size * 2).apply {
            usages.forEach { put(it.gid, it) }
        }
        val presets = FilterPresets.defaultQuickFilters()
        val groups = ArrayList<UsageGroup>(presets.size)
        val executors = ArrayList<DownloadListInfosExecutor>(presets.size)

        fun runAt(index: Int) {
            if (index >= presets.size) {
                executors.forEach { runCatching { it.shutdown() } }
                mainHandler.post { onResult(groups) }
                return
            }
            val preset = presets[index]
            val executor = DownloadListInfosExecutor(usages.map { it.info }, "", appContext)
            executors.add(executor)
            executor.setDownloadSearchingListener(object : DownloadSearchCallback {
                override fun onDownloadSearchSuccess(list: List<DownloadInfo>?) {
                    val items = list.orEmpty().mapNotNull { byGid[it.gid] }
                    groups.add(
                        UsageGroup(
                            id = preset.id,
                            title = if (preset.nameRes != 0) {
                                appContext.getString(preset.nameRes)
                            } else {
                                preset.name
                            },
                            items = items.sortedByDescending { it.size },
                            color = FEATURE_COLORS[index % FEATURE_COLORS.size],
                            dimension = Dimension.FEATURE,
                        ),
                    )
                    runAt(index + 1)
                }

                override fun onDownloadListHandleSuccess(list: List<DownloadInfo>?) = Unit

                override fun onDownloadSearchFailed(list: List<DownloadInfo>?) {
                    Log.w(TAG, "quick filter failed: " + preset.id)
                    runAt(index + 1)
                }
            })
            executor.executeQuickFilter(preset)
        }

        worker.execute { runAt(0) }
    }

    /** 特征分组的配色，与分类色系区分开。 */
    private val FEATURE_COLORS = intArrayOf(
        0xFFE53935.toInt(),
        0xFFFB8C00.toInt(),
        0xFF43A047.toInt(),
        0xFF1E88E5.toInt(),
        0xFF8E24AA.toInt(),
        0xFF00ACC1.toInt(),
        0xFF6D4C41.toInt(),
    )
}