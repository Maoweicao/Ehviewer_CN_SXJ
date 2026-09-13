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

package com.hippo.ehviewer.lab.source

import android.content.Context
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.lab.LabConfig
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.log.SnapshotLogger
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.beerbelly.SimpleDiskCache
import com.hippo.streampipe.InputStreamPipe
import com.hippo.unifile.UniFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 跨设备图片来源聚合器
 *
 * 协议对应：v3.0 §5.21。
 *
 * 调用流程（默认顺序）：
 * <ol>
 *   <li><b>本机下载目录</b></li>
 *   <li><b>本机磁盘缓存</b></li>
 *   <li><b>局域网设备</b>（按 RTT 升序）</li>
 *   <li><b>源站代理</b>（v2.7 mode=proxy 逻辑）</li>
 * </ol>
 */
class ImageSourceChain(context: Context) {

    private val appContext: Context = context.applicationContext
    private val labManager: LabManager = LabManager.getInstance(context)
    private val lanFetcher: LanImageFetcher = LanImageFetcher.getInstance(context)

    /**
     * 跨设备拉取图片。
     */
    fun fetch(gallery: GalleryInfo, page: Int): ImageSourceResult? {
        if (!labManager.isLabEnabled("crossDeviceImage")) return null
        val cfg = labManager.configStore.get()
        var priority: List<String> = cfg.imageSourcePriority
        if (priority.isEmpty()) {
            priority = listOf(
                LabConfig.SOURCE_LOCAL,
                LabConfig.SOURCE_LAN,
                LabConfig.SOURCE_REMOTE_PROXY,
            )
        }

        val chain = ArrayList<String>()

        // 阶段 1：本机下载目录
        if (LabConfig.SOURCE_LOCAL in priority) {
            chain.add(ImageSource.LOCAL)
            val local = tryLocalFile(gallery, page)
            if (local != null) return wrap(local, ImageSource.Kind.LOCAL_FILE, chain)
            chain.add(ImageSource.LOCAL_CACHE)
            val cached = tryLocalCache(gallery.gid, page)
            if (cached != null) return wrap(cached, ImageSource.Kind.LOCAL_CACHE, chain)
        }

        // 阶段 2：局域网设备
        if (LabConfig.SOURCE_LAN in priority) {
            for (peer in lanFetcher.listByRtt()) {
                chain.add(ImageSource.LAN_PREFIX + peer.deviceId)
                try {
                    val r = lanFetcher.fetchImage(peer, gallery.gid, page)
                    if (r != null) {
                        val result = ImageSourceResult()
                        result.data = r.data
                        result.mimeType = r.mimeType
                        result.source = ImageSource.Builder()
                            .kind(ImageSource.Kind.LAN)
                            .deviceId(peer.deviceId)
                            .deviceName(peer.deviceName)
                            .latencyMs(r.source.latencyMs)
                            .triedChain(chain)
                            .build()
                        return result
                    }
                } catch (e: Exception) {
                    SnapshotLogger.d(
                        "ImageSourceChain",
                        "LAN fetch failed for ${peer.deviceName}: ${e.message}",
                    )
                }
            }
        }

        // 阶段 3：源站代理
        if (LabConfig.SOURCE_REMOTE_PROXY in priority) {
            chain.add(ImageSource.REMOTE)
            try {
                val remote = tryRemoteProxy(gallery, page)
                if (remote != null) {
                    remote.source = ImageSource.Builder()
                        .kind(ImageSource.Kind.REMOTE)
                        .latencyMs(-1)
                        .triedChain(chain)
                        .build()
                    return remote
                }
            } catch (e: Exception) {
                SnapshotLogger.d(
                    "ImageSourceChain", "Remote proxy failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * 按 prefer 偏好获取（用于 §5.21.1 mode=lab&prefer=...）。
     */
    fun fetchWithPreference(gallery: GalleryInfo, page: Int, prefer: String): ImageSourceResult? {
        val chain = ArrayList<String>()
        return when (prefer) {
            LabConfig.SOURCE_LOCAL -> {
                chain.add(ImageSource.LOCAL)
                val r = tryLocalFile(gallery, page)
                if (r != null) return wrap(r, ImageSource.Kind.LOCAL_FILE, chain)
                chain.add(ImageSource.LOCAL_CACHE)
                val r2 = tryLocalCache(gallery.gid, page)
                if (r2 != null) return wrap(r2, ImageSource.Kind.LOCAL_CACHE, chain)
                null
            }
            LabConfig.SOURCE_LAN -> {
                for (peer in lanFetcher.listByRtt()) {
                    chain.add(ImageSource.LAN_PREFIX + peer.deviceId)
                    val r = lanFetcher.fetchImage(peer, gallery.gid, page)
                    if (r != null) {
                        val result = ImageSourceResult()
                        result.data = r.data
                        result.mimeType = r.mimeType
                        return wrap(result, ImageSource.Kind.LAN, chain)
                    }
                }
                null
            }
            LabConfig.SOURCE_REMOTE_PROXY -> {
                chain.add(ImageSource.REMOTE)
                val r = tryRemoteProxy(gallery, page)
                if (r != null) return wrap(r, ImageSource.Kind.REMOTE, chain)
                null
            }
            else -> fetch(gallery, page)
        }
    }

    private fun wrap(r: ImageSourceResult, kind: ImageSource.Kind, chain: List<String>): ImageSourceResult {
        r.source = ImageSource.Builder()
            .kind(kind)
            .latencyMs(0)
            .triedChain(chain)
            .build()
        return r
    }

    // ==================== 阶段实现 ====================

    private fun tryLocalFile(gallery: GalleryInfo, page: Int): ImageSourceResult? {
        val dm: DownloadManager = EhApplication.getDownloadManager(appContext) ?: return null
        val info: DownloadInfo = dm.getDownloadInfo(gallery.gid) ?: return null
        val downloadDir: UniFile = SpiderDen.getGalleryDownloadDir(info) ?: return null
        if (!downloadDir.isDirectory) return null
        val imageFile = SpiderDen.findImageFile(downloadDir, page - 1) ?: return null
        return try {
            imageFile.openInputStream().use { input ->
                val data = readAll(input)
                val mime = guessMime(imageFile.name)
                ImageSourceResult().apply {
                    this.data = data
                    this.mimeType = mime
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun tryLocalCache(gid: Long, page: Int): ImageSourceResult? {
        val cache: SimpleDiskCache = SpiderDen.getCache() ?: return null
        val key = EhCacheKeyFactory.getImageKey(gid, page - 1)
        if (!cache.contain(key)) return null
        val pipe: InputStreamPipe = cache.getInputStreamPipe(key) ?: return null
        return try {
            pipe.obtain()
            val data = readAll(pipe.open())
            ImageSourceResult().apply {
                this.data = data
                this.mimeType = "image/jpeg"
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun tryRemoteProxy(gallery: GalleryInfo, page: Int): ImageSourceResult? {
        val dm: DownloadManager = EhApplication.getDownloadManager(appContext) ?: return null
        val info: DownloadInfo = dm.getDownloadInfo(gallery.gid) ?: return null

        val queen = SpiderQueen.obtainSpiderQueen(appContext, info, SpiderQueen.MODE_DOWNLOAD)
        return try {
            val latch = CountDownLatch(1)
            val success = AtomicBoolean(false)
            val holder = arrayOfNulls<ByteArray>(1)

            queen.addOnSpiderListener(object : SpiderQueen.OnSpiderListener {
                override fun onGetPages(pages: Int) {}
                override fun onGet509(index: Int) {}
                override fun onPageDownload(index: Int, cl: Long, rs: Long, br: Int) {}
                override fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) {
                    if (index == page - 1) {
                        success.set(true)
                        latch.countDown()
                    }
                }
                override fun onPageFailure(index: Int, error: String?, f: Int, d: Int, t: Int) {
                    if (index == page - 1) latch.countDown()
                }
                override fun onFinish(finished: Int, downloaded: Int, total: Int) {}
                override fun onGetImageSuccess(index: Int, image: com.hippo.lib.image.Image?) {}
                override fun onGetImageFailure(index: Int, error: String?) {
                    if (index == page - 1) latch.countDown()
                }
            })

            queen.request(page - 1)
            if (!latch.await(30, TimeUnit.SECONDS) || !success.get()) return null

            val downloadDir = SpiderDen.getGalleryDownloadDir(info) ?: return null
            if (!downloadDir.isDirectory) return null
            val imageFile = SpiderDen.findImageFile(downloadDir, page - 1) ?: return null
            imageFile.openInputStream().use { input -> holder[0] = readAll(input) }
            if (holder[0] == null) return null

            ImageSourceResult().apply {
                this.data = holder[0]!!
                this.mimeType = guessMime(imageFile.name)
            }
        } catch (e: InterruptedException) {
            null
        } finally {
            SpiderQueen.releaseSpiderQueen(queen, SpiderQueen.MODE_DOWNLOAD)
        }
    }

    // ==================== 工具 ====================

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun guessMime(filename: String?): String {
        if (filename.isNullOrEmpty()) return "image/jpeg"
        val f = filename.lowercase()
        return when {
            f.endsWith(".png") -> "image/png"
            f.endsWith(".gif") -> "image/gif"
            f.endsWith(".webp") -> "image/webp"
            f.endsWith(".avif") -> "image/avif"
            else -> "image/jpeg"
        }
    }

    /**
     * ImageSourceChain 返回结果
     */
    class ImageSourceResult {
        var data: ByteArray? = null
        var mimeType: String? = null
        var source: ImageSource? = null
    }
}