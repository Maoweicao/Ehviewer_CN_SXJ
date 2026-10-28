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
import android.os.StatFs
import android.util.Log
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.GalleryFileHelper
import com.hippo.unifile.UniFile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 存储占用的采集与清理入口。
 *
 * 目录遍历、数据库聚合都在单线程后台执行，结果回主线程回调；
 * 调用方只需 [scanOverview] / [scanCaches] / [clear]。
 */
object StorageScanner {

    private const val TAG = "StorageScanner"

    /** cacheDir 下已单独统计的子目录，统计 TEMP 时排除，避免重复计算。 */
    private val KNOWN_CACHE_DIRS = setOf("thumb", "http_cache", "spider_info")

    /** App 专属目录（/sdcard/EhViewer）下已被单独统计的子目录。 */
    private val COUNTED_APP_DIRS = setOf("download", "logcat", TRANSLATE_CACHE_DIR)

    /** 翻译缓存目录名，与 [com.hippo.ehviewer.lab.translate.cache.TranslateCache] 一致。 */
    private const val TRANSLATE_CACHE_DIR = "ai_translate_cache"

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "StorageScanner").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val scanning = AtomicBoolean(false)

    /** 是否正在扫描中。 */
    val isScanning: Boolean get() = scanning.get()

    /** 扫描存储概览，回调在主线程执行。多次调用会排队，不会互相丢弃。 */
    fun scanOverview(context: Context, onResult: (StorageOverview?) -> Unit) {
        val appContext = context.applicationContext
        scanning.set(true)
        worker.execute {
            val result = try {
                doScanOverview(appContext)
            } catch (e: Throwable) {
                Log.e(TAG, "scanOverview failed", e)
                null
            }
            scanning.set(false)
            mainHandler.post { onResult(result) }
        }
    }

    /** 扫描缓存与清理页所需的明细，回调在主线程执行。多次调用会排队。 */
    fun scanCaches(context: Context, onResult: (List<CacheEntry>) -> Unit) {
        val appContext = context.applicationContext
        scanning.set(true)
        worker.execute {
            val result = try {
                doScanCaches(appContext)
            } catch (e: Throwable) {
                Log.e(TAG, "scanCaches failed", e)
                emptyList()
            }
            scanning.set(false)
            mainHandler.post { onResult(result) }
        }
    }

    /** 清理指定分类的缓存，回调在主线程返回释放的字节数。 */
    fun clear(context: Context, category: StorageCategory, onResult: (Long) -> Unit) {
        val appContext = context.applicationContext
        worker.execute {
            val freed = try {
                doClear(appContext, category)
            } catch (e: Throwable) {
                Log.e(TAG, "clear $category failed", e)
                0L
            }
            mainHandler.post { onResult(freed) }
        }
    }

    // ------------------------------------------------------------------ 采集

    private fun doScanOverview(context: Context): StorageOverview {
        val sizes = StorageCategory.entries.associateWith { 0L }.toMutableMap()

        sizes[StorageCategory.GALLERIES] = GalleryUsageRepository.gallerySizeMap(
            EhDB.getAllDownloadInfo(),
        ).values.sum()
        sizes[StorageCategory.RECYCLE_BIN] = dirSize(GalleryFileHelper.getRecycleBinDir())
        sizes[StorageCategory.THUMBNAIL_CACHE] = thumbnailCacheSize(context)
        sizes[StorageCategory.NETWORK_CACHE] = networkCacheSize(context)
        sizes[StorageCategory.SPIDER_CACHE] = spiderCacheSize(context)
        sizes[StorageCategory.TRANSLATE_CACHE] = translateCacheSize()
        sizes[StorageCategory.DATABASE] = dirSize(context.getDatabasePath("dummy").parentFile)
        sizes[StorageCategory.LOGS] = logsSize()
        sizes[StorageCategory.TEMP] = dirSizeExcluding(context.cacheDir, KNOWN_CACHE_DIRS)
        sizes[StorageCategory.OTHER] = otherSize()

        val slices = StorageCategory.entries.map { StorageSlice(it, sizes[it] ?: 0L) }
        val capacity = readCapacity(context)
        return StorageOverview(
            totalCapacity = capacity.first,
            available = capacity.second,
            used = slices.sumOf { it.size },
            slices = slices,
            scannedAt = System.currentTimeMillis(),
        )
    }

    private fun doScanCaches(context: Context): List<CacheEntry> {
        val thumbnailMax = runCatching {
            EhApplication.getConaco(context).getBeerBelly().diskMaxSize()
        }.getOrDefault(-1L)
        return listOf(
            CacheEntry(
                StorageCategory.THUMBNAIL_CACHE,
                thumbnailCacheSize(context),
                thumbnailMax,
            ),
            CacheEntry(StorageCategory.NETWORK_CACHE, networkCacheSize(context)),
            CacheEntry(StorageCategory.SPIDER_CACHE, spiderCacheSize(context)),
            CacheEntry(StorageCategory.TRANSLATE_CACHE, translateCacheSize()),
            CacheEntry(StorageCategory.LOGS, logsSize()),
            CacheEntry(StorageCategory.RECYCLE_BIN, dirSize(GalleryFileHelper.getRecycleBinDir())),
            CacheEntry(StorageCategory.TEMP, dirSizeExcluding(context.cacheDir, KNOWN_CACHE_DIRS)),
        )
    }

    private fun thumbnailCacheSize(context: Context): Long = runCatching {
        val size = EhApplication.getConaco(context).getBeerBelly().diskSize()
        if (size > 0) size else 0L
    }.getOrDefault(0L)

    private fun networkCacheSize(context: Context): Long =
        runCatching { EhApplication.getOkHttpCache(context).size() }.getOrDefault(0L)

    private fun spiderCacheSize(context: Context): Long = runCatching {
        val size = EhApplication.getSpiderInfoCache(context).size()
        if (size > 0) size else 0L
    }.getOrDefault(0L)

    private fun translateCacheSize(): Long =
        dirSize(AppConfig.getDirInExternalAppDir(TRANSLATE_CACHE_DIR))

    private fun logsSize(): Long = dirSize(AppConfig.getExternalLogcatDir()) +
        dirSize(AppConfig.getExternalCrashDir())

    /** App 专属目录里除下载目录、日志、翻译缓存以外的部分（归档、压缩计划、解析错误等）。 */
    private fun otherSize(): Long =
        dirSizeExcluding(AppConfig.getExternalAppDir(), COUNTED_APP_DIRS)

    // ------------------------------------------------------------------ 清理

    private fun doClear(context: Context, category: StorageCategory): Long {
        // 回收站不能一键清空（需要用户确认逐个恢复/永久删除），这里直接忽略
        if (category == StorageCategory.RECYCLE_BIN) return 0L

        val before = cacheSizeOf(context, category)
        when (category) {
            StorageCategory.THUMBNAIL_CACHE -> {
                runCatching { EhApplication.getConaco(context).getBeerBelly().clearDisk() }
                runCatching { File(context.cacheDir, "thumb").deleteRecursively() }
            }

            StorageCategory.NETWORK_CACHE -> {
                runCatching { EhApplication.getOkHttpCache(context).evictAll() }
                runCatching { File(context.cacheDir, "http_cache").deleteRecursively() }
            }

            StorageCategory.SPIDER_CACHE -> {
                runCatching { EhApplication.getSpiderInfoCache(context).clear() }
                runCatching { File(context.cacheDir, "spider_info").deleteRecursively() }
            }

            StorageCategory.TRANSLATE_CACHE -> {
                AppConfig.getDirInExternalAppDir(TRANSLATE_CACHE_DIR)?.deleteRecursively()
            }

            StorageCategory.LOGS -> {
                AppConfig.getExternalLogcatDir()?.deleteRecursively()
                AppConfig.getExternalCrashDir()?.deleteRecursively()
            }

            StorageCategory.TEMP -> {
                context.cacheDir.listFiles()?.forEach { child ->
                    if (child.name !in KNOWN_CACHE_DIRS) {
                        child.deleteRecursively()
                    }
                }
            }

            else -> return 0L
        }
        return (before - cacheSizeOf(context, category)).coerceAtLeast(0L)
    }

    private fun cacheSizeOf(context: Context, category: StorageCategory): Long = when (category) {
        StorageCategory.THUMBNAIL_CACHE -> thumbnailCacheSize(context)
        StorageCategory.NETWORK_CACHE -> networkCacheSize(context)
        StorageCategory.SPIDER_CACHE -> spiderCacheSize(context)
        StorageCategory.TRANSLATE_CACHE -> translateCacheSize()
        StorageCategory.LOGS -> logsSize()
        StorageCategory.RECYCLE_BIN -> dirSize(GalleryFileHelper.getRecycleBinDir())
        StorageCategory.TEMP -> dirSizeExcluding(context.cacheDir, KNOWN_CACHE_DIRS)
        else -> 0L
    }

    // ------------------------------------------------------------------ 工具

    /** 读取下载位置所在存储卷的 (总容量, 可用空间)，非 file:// 下载位置时回落到内部存储。 */
    private fun readCapacity(context: Context): Pair<Long, Long> {
        val path = runCatching {
            val download = Settings.getDownloadLocation()
            val uri = download?.uri
            if (uri != null && UniFile.isFileUri(uri)) uri.path else context.filesDir.absolutePath
        }.getOrNull() ?: context.filesDir.absolutePath
        return runCatching {
            val stat = StatFs(path)
            val blockSize = stat.blockSizeLong
            (stat.blockCountLong * blockSize) to (stat.availableBlocksLong * blockSize)
        }.getOrDefault(0L to 0L)
    }

    /** 递归统计 UniFile 大小，失败按 0 计。 */
    fun dirSize(file: UniFile?): Long {
        if (file == null || !file.exists()) return 0L
        return runCatching { uniFileSize(file) }.getOrDefault(0L)
    }

    private fun uniFileSize(file: UniFile): Long {
        if (file.isFile) return file.length().coerceAtLeast(0L)
        var total = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) {
            total += uniFileSize(child)
        }
        return total
    }

    /** 递归统计目录大小。 */
    fun dirSize(file: File?): Long = dirSizeExcluding(file, emptySet())

    /** 递归统计目录大小，跳过指定的顶层子目录名。 */
    fun dirSizeExcluding(file: File?, excludedNames: Set<String>): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length().coerceAtLeast(0L)
        var total = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) {
            if (child.name in excludedNames) continue
            total += if (child.isDirectory) {
                dirSizeExcluding(child, emptySet())
            } else {
                child.length().coerceAtLeast(0L)
            }
        }
        return total
    }
}