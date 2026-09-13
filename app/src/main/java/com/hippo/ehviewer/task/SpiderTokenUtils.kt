package com.hippo.ehviewer.task

import android.content.Context
import android.util.SparseArray
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.PreviewSet
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderInfo
import java.text.Normalizer
import java.util.Locale

/**
 * 预下载预碰撞检测/预下载合并共用的工具方法。
 *
 * 集中维护画廊图片 token（pToken）的拉取、归一化与比较工具，避免在
 * [PreDownloadMergeTask] 与 [WaitListProgressiveCheckTask] 之间出现重复实现。
 */
object SpiderTokenUtils {

    /**
     * 匹配两个下载项是否可能属于同一画廊（同名同作者）。
     * 作者为空的画廊被视为"作者不限"，仍按归一化标题比较。
     */
    @JvmStatic
    fun isSameCoreTitleAndAuthor(a: DownloadInfo, b: DownloadInfo): Boolean {
        if (a.gid == b.gid) return false
        val titleA = normalizeCoreTitle(com.hippo.ehviewer.client.EhUtils.getSuitableTitle(a))
        val titleB = normalizeCoreTitle(com.hippo.ehviewer.client.EhUtils.getSuitableTitle(b))
        return isSameNormalizedTitleAndAuthor(titleA, a.uploader, titleB, b.uploader)
    }

    /**
     * 在已得到归一化标题的前提下做同名同作者判定，便于单元测试避免 Settings/EhUtils 依赖。
     */
    @JvmStatic
    fun isSameNormalizedTitleAndAuthor(
        titleA: String,
        uploaderA: String?,
        titleB: String,
        uploaderB: String?
    ): Boolean {
        if (titleA.isEmpty() || titleB.isEmpty()) return false
        if (titleA != titleB) return false
        val uA = uploaderA?.trim()?.lowercase(Locale.ROOT) ?: ""
        val uB = uploaderB?.trim()?.lowercase(Locale.ROOT) ?: ""
        // 任一作者为空则不强制要求一致；都非空时必须一致
        if (uA.isNotEmpty() && uB.isNotEmpty() && uA != uB) return false
        return true
    }

    /** 标题归一化：剥离日期标签（如 2026.08.02 / 2026-05-21），NFKC 后小写。 */
    @JvmStatic
    fun normalizeCoreTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        var t = title.replace(DATE_REGEX, " ")
        // \uD83D\uDD04 = 🔄 (U+1F504)，用代理对书写避免源码编码不一致
        t = t.replace("\uD83D\uDD04", " ")
        t = Normalizer.normalize(t, Normalizer.Form.NFKC)
        return t.trim().lowercase(Locale.ROOT)
    }

    /** 从 SpiderInfo 提取非空 pToken 集合。失败 / 空集均返回空集合。 */
    fun toHashSet(spi: SpiderInfo?): HashSet<String> {
        val set = HashSet<String>()
        if (spi == null) return set
        for (i in 0 until spi.pTokenMap.size()) {
            val t = spi.pTokenMap.valueAt(i)
            if (!t.isNullOrEmpty() && t != SpiderInfo.TOKEN_FAILED) {
                set.add(t)
            }
        }
        return set
    }

    /**
     * 递进关系决策：根据两张画廊的图片 token 集与页数，判断是否构成递进/重复关系，
     * 以及应当被移除的旧版本。
     *
     * 判定规则（保守策略）：
     *   - cand ⊂ target（且 cand 非空）→ candidate 是旧版本，返回 REMOVE_CANDIDATE
     *   - target ⊂ candidate（且 target 非空）→ target 是旧版本，返回 REMOVE_TARGET
     *   - token 集相等 + pages 不同 → 视为完全重复，按页数大小决定移除方向
     *   - token 集相等 + pages 相同 → 保留两者（保守）
     *   - 其他 → 不构成递进关系，返回 NONE
     */
    fun decideProgressiveRelation(
        targetSet: Set<String>,
        targetPages: Int,
        candidateSet: Set<String>,
        candidatePages: Int
    ): ProgressiveDecision {
        // 先判断完全相等：避免被「互相包含」的子集判断吃掉
        if (targetSet == candidateSet) {
            return when {
                candidatePages > targetPages -> ProgressiveDecision.REMOVE_EQUAL_TARGET
                targetPages > candidatePages -> ProgressiveDecision.REMOVE_EQUAL_CANDIDATE
                else -> ProgressiveDecision.NONE
            }
        }
        if (candidateSet.isNotEmpty() && targetSet.containsAll(candidateSet)) {
            return ProgressiveDecision.REMOVE_CANDIDATE
        }
        if (targetSet.isNotEmpty() && candidateSet.containsAll(targetSet)) {
            return ProgressiveDecision.REMOVE_TARGET
        }
        return ProgressiveDecision.NONE
    }

    enum class ProgressiveDecision {
        /** 无递进关系 */
        NONE,
        /** candidate ⊂ target，移除 candidate */
        REMOVE_CANDIDATE,
        /** target ⊂ candidate，移除 target */
        REMOVE_TARGET,
        /** token 集相等且 candidate 页数更小，移除 candidate */
        REMOVE_EQUAL_CANDIDATE,
        /** token 集相等且 target 页数更小，移除 target */
        REMOVE_EQUAL_TARGET
    }

    /** 构建 pToken → 页面索引 的反向映射，用于合并阶段定位目标页号。 */
    fun buildHashToNewIndex(spi: SpiderInfo?): HashMap<String, Int> {
        val map = HashMap<String, Int>()
        if (spi == null) return map
        for (i in 0 until spi.pTokenMap.size()) {
            val t = spi.pTokenMap.valueAt(i)
            if (!t.isNullOrEmpty()) {
                map[t] = spi.pTokenMap.keyAt(i)
            }
        }
        return map
    }

    /**
     * 解析逗号分隔的 ptoken 字符串为集合（来自 [com.hippo.ehviewer.dao.PtokensIndex]）。
     */
    fun parsePtokenString(serialized: String?): HashSet<String> {
        val set = HashSet<String>()
        if (serialized.isNullOrBlank()) return set
        for (part in serialized.split(",")) {
            val t = part.trim()
            if (t.isNotEmpty() && t != SpiderInfo.TOKEN_FAILED) set.add(t)
        }
        return set
    }

    /**
     * 把 pToken 集合序列化为逗号字符串，便于存入 [com.hippo.ehviewer.dao.PtokensIndex]。
     */
    fun serializePtokens(set: Set<String>): String = set.joinToString(",")

    /**
     * 从远程拉取画廊的完整 preview 列表，得到 [SpiderInfo]。
     *
     * 任一次网络调用失败都返回 null，由调用方决定是否放弃此次对比。
     *
     * @param onPageFetched 拉取第 i 页（从 0 开始）后的回调，可用于上报进度
     */
    suspend fun fetchSpiderInfoFromRemote(
        context: Context,
        info: DownloadInfo,
        onPageFetched: suspend (page: Int, previewPages: Int) -> Unit = { _, _ -> }
    ): SpiderInfo? {
        val token = info.token
        if (token.isNullOrEmpty()) return null
        val client = EhApplication.getOkHttpClient(context)
        val gid = info.gid
        val gd = try {
            EhEngine.getGalleryDetail(null, client, EhUrl.getGalleryDetailUrl(gid, token, 0, false))
        } catch (_: Throwable) {
            null
        } ?: return null
        val pages = if (gd.SpiderInfoPages > 0) gd.SpiderInfoPages else info.pages
        val previewPages = gd.SpiderInfoPreviewPages
        if (pages <= 0 || previewPages <= 0) return null

        val spi = SpiderInfo()
        spi.gid = gid
        spi.token = token
        spi.pages = pages
        spi.previewPages = previewPages
        spi.pTokenMap = SparseArray(pages)
        val firstSet = gd.SpiderInfoPreviewSet
        if (firstSet != null && firstSet.size() > 0) {
            spi.previewPerPage = firstSet.size()
            accumulatePreviews(spi, firstSet)
        }
        for (i in 1 until previewPages) {
            try {
                val pair = EhEngine.getPreviewSet(
                    null, client, EhUrl.getGalleryDetailUrl(gid, token, i, false)
                )
                accumulatePreviews(spi, pair.first)
            } catch (_: Throwable) {
                return null
            }
            onPageFetched(i, previewPages)
        }
        if (spi.pTokenMap.size() == 0) return null
        return spi
    }

    private fun accumulatePreviews(spi: SpiderInfo, set: PreviewSet) {
        for (i in 0 until set.size()) {
            val r = GalleryPageUrlParser.parse(set.getPageUrlAt(i)) ?: continue
            val pToken = r.pToken
            if (!pToken.isNullOrEmpty() && pToken != SpiderInfo.TOKEN_FAILED) {
                spi.pTokenMap.put(r.page, pToken)
            }
        }
    }

    private val DATE_REGEX = Regex(
        "(\\d{4}[-./年]\\d{1,2}[-./月]\\d{1,2})|(\\d{1,2}[-./月]\\d{1,2}[-./日]\\d{2,4})"
    )
}