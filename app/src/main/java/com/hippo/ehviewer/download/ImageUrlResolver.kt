package com.hippo.ehviewer.download

import android.content.Context
import android.util.Log
import android.util.SparseArray
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.exception.Image509Exception
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen

/**
 * 单张图片 URL 解析器：从 gid + pageIndex 出发，调用既有 OkHttp 客户端解析出最终 imageUrl。
 *
 * 已知限制（与 SpiderWorker 一致）：
 * - 不处理 509 重试
 * - 不处理 showKey mismatch 重试
 * - 不处理 origin image 重定向
 *
 * E-Hentai 的 pToken 几小时后会过期。SystemDMBackend 在 enqueue 前批量解析所有 URL 并持久化，
 * 不在下载中途刷新（这是与 SpiderWorker 的根本区别）。如果 pToken 过期导致 404，
 * 单张图会失败但不会让进程崩溃。
 *
 * 网络 I/O 只能在工作线程调用：主线程调用会抛 NetworkOnMainThreadException。
 */
object ImageUrlResolver {

    private const val TAG = "ImageUrlResolver"

    /**
     * 解析单张图片的最终 URL。
     *
     * 每次调用都会新建 Session（不复用 pToken 缓存），批量解析请直接用 [Session]。
     *
     * @return 成功返回 imageUrl，失败返回 null
     */
    fun resolve(context: Context, galleryInfo: GalleryInfo, pageIndex: Int): String? {
        return Session(context, galleryInfo).resolve(pageIndex)
    }

    /**
     * 同一画廊的批量解析会话。
     *
     * pToken 按「预览页」归档在画廊详情页里：一页预览（通常 20 张）只覆盖 pageIndex 的一个区间，
     * 而不是全部页。所以不能只拉一次预览页 0，也不能每页都重新拉一次详情。
     *
     * 本 Session 会：
     * 1. 优先读磁盘 .ehviewer（含完整 pTokenMap，[SpiderInfo.read] 才会带 pToken，
     *    [SpiderInfo.getSpiderInfo] 只读 header）；
     * 2. 缺 pToken 时按 pageIndex 算出所属预览页，只拉那一页，并在本次会话内缓存；
     * 3. 线程安全，可被同一个画廊的多个解析线程共享。
     */
    class Session(context: Context, private val galleryInfo: GalleryInfo) {

        private val httpClient = EhApplication.getOkHttpClient(context)
        private val lock = Any()

        private var spiderInfo: SpiderInfo? = null
        private var loaded = false
        private var previewPages = -1
        private var previewPerPage = -1
        private val fetchedPreviewIndexes = HashSet<Int>()

        @Volatile
        var pages: Int = 0
            private set

        fun resolve(pageIndex: Int): String? {
            val pToken = pTokenFor(pageIndex) ?: return null
            val pageUrl = EhUrl.getPageUrl(galleryInfo.gid, pageIndex, pToken)
            return try {
                val result = EhEngine.getGalleryPage(
                    null, httpClient, pageUrl, galleryInfo.gid, galleryInfo.token
                )
                val imageUrl = result.imageUrl
                if (imageUrl != null && imageUrl.endsWith("/509.gif")) {
                    Log.w(TAG, "Got 509 for gid=${galleryInfo.gid} page=$pageIndex")
                    null
                } else {
                    imageUrl
                }
            } catch (e: Image509Exception) {
                Log.w(TAG, "509 for gid=${galleryInfo.gid} page=$pageIndex", e)
                null
            } catch (e: Throwable) {
                Log.e(TAG, "resolve failed for gid=${galleryInfo.gid} page=$pageIndex", e)
                null
            }
        }

        private fun pTokenFor(pageIndex: Int): String? = synchronized(lock) {
            ensureLoadedLocked()
            var token = validTokenLocked(pageIndex)
            if (token == null) {
                val previewIndex = previewIndexOf(pageIndex)
                if (previewIndex >= 0 && fetchedPreviewIndexes.add(previewIndex)) {
                    fetchPreviewLocked(previewIndex)
                    token = validTokenLocked(pageIndex)
                }
            }
            token
        }

        private fun validTokenLocked(pageIndex: Int): String? {
            val token = spiderInfo?.pTokenMap?.get(pageIndex) ?: return null
            if (token.isEmpty() || token == SpiderInfo.TOKEN_FAILED) {
                return null
            }
            return token
        }

        private fun ensureLoadedLocked() {
            if (loaded) {
                return
            }
            loaded = true
            val local = readLocalSpiderInfo()
            if (local != null) {
                spiderInfo = local
                pages = local.pages
                previewPages = local.previewPages
                previewPerPage = local.previewPerPage
                Log.d(
                    TAG,
                    "local SpiderInfo for gid=${galleryInfo.gid}: pages=${local.pages}, " +
                            "previewPages=${local.previewPages}, previewPerPage=${local.previewPerPage}, " +
                            "ptokens=${local.pTokenMap?.size() ?: 0}"
                )
            } else {
                Log.d(TAG, "no local SpiderInfo for gid=${galleryInfo.gid}; pTokens will be fetched")
            }
        }

        private fun readLocalSpiderInfo(): SpiderInfo? {
            return try {
                val dir = SpiderDen.getExistingGalleryDownloadDir(galleryInfo) ?: return null
                val file = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return null
                val info = SpiderInfo.read(file) ?: return null
                if (info.gid != galleryInfo.gid) {
                    null
                } else if (info.token != galleryInfo.token) {
                    // 画廊 token 变了（重新上传），旧的 pToken 已失效
                    null
                } else {
                    info
                }
            } catch (e: Throwable) {
                Log.w(TAG, "read local SpiderInfo failed for gid=${galleryInfo.gid}", e)
                null
            }
        }

        /** 该页属于第几个预览页（画廊详情页的 ?p= 参数）。未知时退化为 0。 */
        private fun previewIndexOf(pageIndex: Int): Int {
            val perPage = previewPerPage
            if (perPage <= 0) {
                return 0
            }
            var index = pageIndex / perPage
            if (previewPages > 0) {
                index = minOf(index, previewPages - 1)
            }
            return maxOf(index, 0)
        }

        /** 拉取一个预览页并把 pToken 合并进缓存。调用方必须持有 [lock]。 */
        private fun fetchPreviewLocked(previewIndex: Int) {
            try {
                val url = EhUrl.getGalleryDetailUrl(
                    galleryInfo.gid, galleryInfo.token, previewIndex, false
                )
                val pair = EhEngine.getPreviewSet(null, httpClient, url)
                val previewSet = pair.first
                val totalPreviewPages = pair.second ?: -1
                if (totalPreviewPages > 0) {
                    previewPages = totalPreviewPages
                }
                if (previewSet == null || previewSet.size() == 0) {
                    Log.w(
                        TAG,
                        "empty preview set for gid=${galleryInfo.gid} preview=$previewIndex"
                    )
                    return
                }
                if (previewPerPage <= 0) {
                    previewPerPage = if (previewIndex == 0) {
                        previewSet.size()
                    } else {
                        (previewSet.getPosition(0) / previewIndex).coerceAtLeast(1)
                    }
                }
                val info = ensureSpiderInfoLocked()
                for (i in 0 until previewSet.size()) {
                    val parsed = GalleryPageUrlParser.parse(previewSet.getPageUrlAt(i)) ?: continue
                    val pToken = parsed.pToken
                    if (!pToken.isNullOrEmpty() && pToken != SpiderInfo.TOKEN_FAILED) {
                        info.pTokenMap.put(parsed.page, pToken)
                    }
                }
                Log.d(
                    TAG,
                    "preview $previewIndex fetched for gid=${galleryInfo.gid}: " +
                            "ptokens=${info.pTokenMap.size()}"
                )
            } catch (e: Throwable) {
                Log.e(
                    TAG,
                    "fetch preview $previewIndex failed for gid=${galleryInfo.gid}",
                    e
                )
            }
        }

        private fun ensureSpiderInfoLocked(): SpiderInfo {
            spiderInfo?.let { return it }
            val created = SpiderInfo().apply {
                gid = galleryInfo.gid
                token = galleryInfo.token
                pTokenMap = SparseArray()
            }
            spiderInfo = created
            return created
        }
    }
}
