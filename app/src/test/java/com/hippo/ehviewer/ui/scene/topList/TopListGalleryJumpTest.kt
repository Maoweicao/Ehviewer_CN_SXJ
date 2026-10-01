package com.hippo.ehviewer.ui.scene.topList

import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回归测试：画廊榜单的 href 是 **gtoken** URL，必须走 ACTION_GID_TOKEN 直连详情，
 * 不能走 ProgressScene.ACTION_GALLERY_TOKEN 把它当 ptoken 去换 gtoken。
 *
 * 这个区分是排行榜"点条目进不去/进错"的根因：
 *  - `/g/{gid}/{10位hex}`  → GalleryDetailUrlParser → token 就是 gtoken → ACTION_GID_TOKEN
 *  - `/s/{10位hex}/{gid}-{page}` → GalleryPageUrlParser → pToken 是另一个命名空间的
 *    短 token，必须经 api.php 的 gtoken 方法换算 → ProgressScene
 *
 * 两者形似但语义不同，之前把前者塞进 ptoken 位导致跳转失败。
 */
class TopListGalleryJumpTest {

    /** e-hentai.org/toplist.php 画廊榜单的真实 href 形态。 */
    private val realTopListHref = "https://e-hentai.org/g/596447/3894f02c20/"

    @Test
    fun topListHrefParsesAsGalleryDetailUrl() {
        val r = GalleryDetailUrlParser.parse(realTopListHref, false)
        assertNotNull("画廊榜单 href 必须能解析出 gid/token", r)
        assertEquals(596447L, r!!.gid)
        assertEquals("3894f02c20", r.token)
    }

    @Test
    fun topListHrefIsNotAPtokenUrl() {
        // 关键：它不是 /s/ 形态，绝不能被当成需要换 gtoken 的 ptoken URL
        assertNull(
            "画廊榜单 href 不该被 GalleryPageUrlParser 解析",
            GalleryPageUrlParser.parse(realTopListHref, false)
        )
    }

    @Test
    fun realPtokenUrlStillParsesAsPageUrl() {
        // 真正的 ptoken 链接仍然走老路径，这条不能被改坏
        val r = GalleryPageUrlParser.parse("https://e-hentai.org/s/3894f02c20/596447-1", false)
        assertNotNull(r)
        assertEquals(596447L, r!!.gid)
        assertEquals("3894f02c20", r.pToken)
        assertEquals(0, r.page)
    }

    /**
     * toplist 上非画廊分类的 href 是 /uploader/{name}，两种解析器都不该命中，
     * 这样才能落到 uploader:"名称" 关键词搜索分支。
     * 依据实测：toplist.php 原始 HTML 里 240 条 /uploader/ 链接、0 条 /g/ 或 /tag/。
     */
    @Test
    fun uploaderHrefMatchesNeitherParser() {
        val href = "https://e-hentai.org/uploader/milannews"
        assertNull(GalleryDetailUrlParser.parse(href, false))
        assertNull(GalleryPageUrlParser.parse(href, false))
    }

    /** 名字带空格时 href 里是 +，也不能被误判成画廊链接。 */
    @Test
    fun uploaderHrefWithPlusSpaceMatchesNeitherParser() {
        val href = "https://e-hentai.org/uploader/Zero+Angel"
        assertNull(GalleryDetailUrlParser.parse(href, false))
        assertNull(GalleryPageUrlParser.parse(href, false))
    }
}
