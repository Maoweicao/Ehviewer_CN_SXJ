package com.hippo.ehviewer.download

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.exception.Image509Exception
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser
import com.hippo.ehviewer.spider.SpiderInfo

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
 */
object ImageUrlResolver {

    private const val TAG = "ImageUrlResolver"

    /**
     * 解析单张图片的最终 URL。
     *
     * @return 成功返回 imageUrl，失败返回 null
     */
    fun resolve(context: Context, galleryInfo: GalleryInfo, pageIndex: Int): String? {
        val httpClient = EhApplication.getOkHttpClient(context)

        val spiderInfo = SpiderInfo.getSpiderInfo(galleryInfo)
        var pToken: String? = spiderInfo?.pTokenMap?.get(pageIndex)

        if (pToken == null) {
            Log.w(TAG, "No pToken cached for page $pageIndex, gid=${galleryInfo.gid}; fetching gallery detail")
            // 拉一次画廊详情，从 preview 链接里拿 pToken
            val fetched = fetchAndCacheSpiderInfo(context, galleryInfo)
            pToken = fetched?.pTokenMap?.get(pageIndex)
        }

        if (pToken == null) {
            Log.w(TAG, "Still no pToken for page $pageIndex, gid=${galleryInfo.gid}")
            return null
        }

        val pageUrl = EhUrl.getPageUrl(galleryInfo.gid, pageIndex, pToken)
        return try {
            val result = EhEngine.getGalleryPage(null, httpClient, pageUrl, galleryInfo.gid, galleryInfo.token)
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

    /**
     * 拉画廊详情，构造一个 SpiderInfo 用于读 pToken。
     * 不写磁盘（避免污染 ehviewer 自己的下载目录）。
     */
    private fun fetchAndCacheSpiderInfo(context: Context, galleryInfo: GalleryInfo): SpiderInfo? {
        val httpClient = EhApplication.getOkHttpClient(context)
        return try {
            val url = EhUrl.getGalleryDetailUrl(galleryInfo.gid, galleryInfo.token, 0, false)
            val detail = EhEngine.getGalleryDetail(null, httpClient, url) ?: return null

            // 从 previewSet 抽 pTokenMap
            val pTokenMap = android.util.SparseArray<String>()
            val previewSet = detail.previewSet
            if (previewSet != null) {
                val n = previewSet.size()
                for (i in 0 until n) {
                    val pageUrl = previewSet.getPageUrlAt(i)
                    val parsed = GalleryPageUrlParser.parse(pageUrl)
                    if (parsed != null) {
                        pTokenMap.put(parsed.page, parsed.pToken)
                    }
                }
            }

            SpiderInfo().apply {
                gid = galleryInfo.gid
                token = galleryInfo.token
                pages = detail.pages
                this.pTokenMap = pTokenMap
            }
        } catch (e: Throwable) {
            Log.e(TAG, "fetchAndCacheSpiderInfo failed", e)
            null
        }
    }
}