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

package com.hippo.ehviewer.download.filter

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo

/**
 * 排除内容规则：在一次筛选中把不该出现的条目剔掉。
 *
 * - [excludedCategories] 排除某些 catalog（同人志 / 漫画 / …）
 * - [keywords] 排除关键词，可指定作用域（名字 / 作者 / Tag / AI 描述）
 * - [excludedTags] 排除 Tag，支持 `ns:value` 与裸值，支持中英别名匹配
 *
 * 全局规则（设置里配置）对所有筛选入口无条件叠加；
 * 单次面板规则只在当次筛选生效。
 */
data class ExcludeRule(
    val excludedCategories: Set<Int> = emptySet(),
    val keywords: List<ScopedKeyword> = emptyList(),
    val excludedTags: Set<String> = emptySet(),
) {

    val isEmpty: Boolean
        get() = excludedCategories.isEmpty() && keywords.isEmpty() && excludedTags.isEmpty()

    fun copyWith(
        categories: Set<Int>? = null,
        keywords: List<ScopedKeyword>? = null,
        tags: Set<String>? = null,
    ): ExcludeRule = copy(
        excludedCategories = categories ?: excludedCategories,
        keywords = keywords ?: this.keywords,
        excludedTags = tags ?: excludedTags,
    )

    /** 两条排除规则合并（用于“全局 + 单次”叠加）。 */
    operator fun plus(other: ExcludeRule): ExcludeRule = ExcludeRule(
        excludedCategories = excludedCategories + other.excludedCategories,
        keywords = keywords + other.keywords,
        excludedTags = excludedTags + other.excludedTags,
    )

    fun toJson(): JSONObject {
        val json = JSONObject()
        json["categories"] = JSONArray(excludedCategories.toList())
        json["tags"] = JSONArray(excludedTags.toList())
        json["keywords"] = JSONArray().apply {
            keywords.forEach { add(it.toJson()) }
        }
        return json
    }

    companion object {
        @JvmField
        val EMPTY = ExcludeRule()

        @JvmStatic
        fun fromJson(json: JSONObject?): ExcludeRule {
            if (json == null) return EMPTY
            val categories = LinkedHashSet<Int>()
            json.getJSONArray("categories")?.forEach { item ->
                (item as? Number)?.toInt()?.let { categories.add(it) }
            }
            val tags = LinkedHashSet<String>()
            json.getJSONArray("tags")?.forEach { item ->
                item?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { tags.add(it) }
            }
            val keywords = mutableListOf<ScopedKeyword>()
            json.getJSONArray("keywords")?.forEach { item ->
                val obj = item as? JSONObject ?: return@forEach
                ScopedKeyword.fromJson(obj)?.let { keywords.add(it) }
            }
            return ExcludeRule(categories, keywords, tags)
        }
    }
}

/** 一条带作用域的排除关键词。 */
data class ScopedKeyword(
    val text: String = "",
    val scope: KeywordScope = KeywordScope.ANY,
    val ignoreCase: Boolean = true,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
) {

    fun toJson(): JSONObject {
        val json = JSONObject()
        json["text"] = text
        json["scope"] = scope.key
        json["ignoreCase"] = ignoreCase
        json["wholeWord"] = wholeWord
        json["regex"] = regex
        return json
    }

    /** Java 侧便捷入口（Kotlin 具名参数在 Java 里不可用）。 */
    fun withOptions(ignore: Boolean, whole: Boolean, asRegex: Boolean): ScopedKeyword =
        copy(ignoreCase = ignore, wholeWord = whole, regex = asRegex)

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): ScopedKeyword? {
            val text = json.getString("text")?.trim().orEmpty()
            if (text.isEmpty()) return null
            return ScopedKeyword(
                text = text,
                scope = KeywordScope.fromKey(json.getString("scope")),
                ignoreCase = json.getBooleanValue("ignoreCase"),
                wholeWord = json.getBooleanValue("wholeWord"),
                regex = json.getBooleanValue("regex"),
            )
        }
    }
}

/**
 * 完整的筛选条件 —— 下载列表“高级筛选”的全部字段 + 排除规则。
 *
 * 这是**唯一一份筛选条件的表示**，快捷筛选的自定义类型与面板的快速设置按钮
 * 都复用它，避免同一套条件出现三份实现。
 *
 * 注意时间有两个维度：
 * - [timeFrom] / [timeTo]：**下载**目录时间（沿用旧行为）
 * - [postedFrom] / [postedTo]：**发布时间**（“N 个月前的低分内容”用这个）
 */
data class FilterCondition @JvmOverloads constructor(
    val categories: Set<Int> = emptySet(),
    val statuses: Set<Int> = emptySet(),
    val sort: SortKey = SortKey.DEFAULT,
    val timeFrom: Long? = null,
    val timeTo: Long? = null,
    val sizeFrom: Long? = null,
    val sizeTo: Long? = null,
    val pageFrom: Long? = null,
    val pageTo: Long? = null,
    val duplicateOnly: Boolean = false,
    val ratingFrom: Float = 0f,
    val ratingTo: Float = 5f,
    /** 启用“低分”判定：按 catalog 各自的低分阈值（见 [LowRatingConfig]）动态过滤，忽略 ratingFrom/ratingTo。 */
    val lowRatingOnly: Boolean = false,
    val postedFrom: Long? = null,
    val postedTo: Long? = null,
    /**
     * “发布时间在最近 N 个月内”的相对条件。
     *
     * 配置里存**相对月数**而不是绝对时间戳 —— 否则预设保存下来的那一刻
     * 时间就冻结了，“近 3 个月”会变成一个永远不变的绝对日期。
     * 执行时由 [PostedTime.monthsAgo] 换算成实际区间。
     */
    val postedAgoMonths: Int = 0,
    val perPageFromBytes: Long? = null,
    val unfavoritedOnly: Boolean = false,
    val aiGeneratedOnly: Boolean = false,
    /** 只保留没有任何标签的条目（标签库与 simpleTags 都为空）。 */
    val tagsMissingOnly: Boolean = false,
    val requireFinished: Boolean = false,
    val searchKeyword: String? = null,
    val searchOption: Int = 0,
    val exclude: ExcludeRule = ExcludeRule.EMPTY,
) {

    /** 是否设置了“发布时间范围”条件。 */
    val hasPostedRange: Boolean
        get() = postedFrom != null || postedTo != null || postedAgoMonths > 0

    /** 相对条件换算出的发布时间上界；无相对条件时返回显式的 [postedTo]。 */
    @JvmOverloads
    fun effectivePostedTo(now: Long = System.currentTimeMillis()): Long? =
        if (postedAgoMonths > 0) PostedTime.monthsAgo(postedAgoMonths, now) else postedTo

    /** Java 侧使用的便捷入口（Kotlin 具名参数在 Java 里不可用）。 */
    fun withExclude(rule: ExcludeRule): FilterCondition = copy(exclude = rule)

    /**
     * 写入“低分 / 发布时间 / 未收藏 / AI 生成 / 无标签 / 每页体积”这组新增条件。
     *
     * 由 [FilterExtraOptionsView] 读取界面后回填，避免在 Java 里手写 20 个参数的 copy。
     */
    fun withExtraOptions(
        lowRating: Boolean,
        postedAgoMonths: Int,
        perPageFrom: Long?,
        onlyUnfavorited: Boolean,
        onlyAiGenerated: Boolean,
        onlyTagsMissing: Boolean,
    ): FilterCondition = copy(
        lowRatingOnly = lowRating,
        postedFrom = null,
        postedTo = null,
        postedAgoMonths = postedAgoMonths,
        perPageFromBytes = perPageFrom,
        unfavoritedOnly = onlyUnfavorited,
        aiGeneratedOnly = onlyAiGenerated,
        tagsMissingOnly = onlyTagsMissing,
    )

    /** 只更新搜索相关字段（搜索面板使用）。 */
    fun withSearch(keyword: String?, option: Int): FilterCondition =
        copy(searchKeyword = keyword, searchOption = option)

    fun toJson(): JSONObject {
        val json = JSONObject()
        json["categories"] = JSONArray(categories.toList())
        json["statuses"] = JSONArray(statuses.toList())
        json["sort"] = sort.key
        json["timeFrom"] = timeFrom
        json["timeTo"] = timeTo
        json["sizeFrom"] = sizeFrom
        json["sizeTo"] = sizeTo
        json["pageFrom"] = pageFrom
        json["pageTo"] = pageTo
        json["duplicateOnly"] = duplicateOnly
        json["ratingFrom"] = ratingFrom
        json["ratingTo"] = ratingTo
        json["lowRatingOnly"] = lowRatingOnly
        json["postedFrom"] = postedFrom
        json["postedTo"] = postedTo
        json["postedAgoMonths"] = postedAgoMonths
        json["perPageFromBytes"] = perPageFromBytes
        json["unfavoritedOnly"] = unfavoritedOnly
        json["tagsMissingOnly"] = tagsMissingOnly
        val excludeJson = exclude.toJson()
        if (!exclude.isEmpty) {
            json["exclude"] = excludeJson
        }
        json["requireFinished"] = requireFinished
        json["aiGeneratedOnly"] = aiGeneratedOnly
        if (!searchKeyword.isNullOrEmpty()) {
            json["searchKeyword"] = searchKeyword
        }
        if (searchOption != 0) {
            json["searchOption"] = searchOption
        }
        return json
    }

    companion object {
        @JvmField
        val EMPTY = FilterCondition()

        /** 仅填“全部下载状态”的默认条件。 */
        @JvmStatic
        fun defaultAllStatuses(): FilterCondition = FilterCondition(
            statuses = ALL_DOWNLOAD_STATUSES,
            categories = setOf(EhUtils.ALL_CATEGORY),
        )

        @JvmStatic
        fun fromJson(json: JSONObject?): FilterCondition {
            if (json == null) return defaultAllStatuses()
            val categories = readIntSet(json.getJSONArray("categories"), setOf(EhUtils.ALL_CATEGORY))
            val statuses = readIntSet(json.getJSONArray("statuses"), ALL_DOWNLOAD_STATUSES)
            val exclude = ExcludeRule.fromJson(json.getJSONObject("exclude"))
            val keyword = json.getString("searchKeyword")?.trim()
            return FilterCondition(
                categories = categories,
                statuses = statuses,
                sort = SortKey.fromKey(json.getString("sort")),
                timeFrom = json.getLong("timeFrom")?.takeIf { it > 0 },
                timeTo = json.getLong("timeTo")?.takeIf { it > 0 },
                sizeFrom = json.getLong("sizeFrom")?.takeIf { it >= 0 },
                sizeTo = json.getLong("sizeTo")?.takeIf { it >= 0 },
                pageFrom = json.getLong("pageFrom")?.takeIf { it > 0 },
                pageTo = json.getLong("pageTo")?.takeIf { it > 0 },
                duplicateOnly = json.getBooleanValue("duplicateOnly"),
                ratingFrom = json.getFloatValue("ratingFrom"),
                ratingTo = json.getFloatValue("ratingTo").takeIf { it > 0f } ?: 5f,
                lowRatingOnly = json.getBooleanValue("lowRatingOnly"),
                postedFrom = json.getLong("postedFrom")?.takeIf { it > 0 },
                postedTo = json.getLong("postedTo")?.takeIf { it > 0 },
                postedAgoMonths = json.getIntValue("postedAgoMonths").takeIf { it > 0 } ?: 0,
                perPageFromBytes = json.getLong("perPageFromBytes")?.takeIf { it > 0 },
                unfavoritedOnly = json.getBooleanValue("unfavoritedOnly"),
                tagsMissingOnly = json.getBooleanValue("tagsMissingOnly"),
                aiGeneratedOnly = json.getBooleanValue("aiGeneratedOnly"),
                requireFinished = json.getBooleanValue("requireFinished"),
                searchKeyword = keyword?.takeIf { it.isNotEmpty() },
                searchOption = json.getIntValue("searchOption"),
                exclude = exclude,
            )
        }

        private fun readIntSet(array: JSONArray?, fallback: Set<Int>): Set<Int> {
            if (array == null || array.isEmpty()) return fallback
            val set = LinkedHashSet<Int>()
            array.forEach { item -> (item as? Number)?.toInt()?.let { set.add(it) } }
            return set.ifEmpty { fallback }
        }
    }
}

/**
 * 低分定义：全局默认阈值 + 按 catalog 细粒度覆盖。
 *
 * “低分”没有统一定义 —— 西方 CG 和同人志的评分分布完全不同，
 * 所以允许每个 catalog 单独定阈值，未设置的走 [globalThreshold]。
 */
data class LowRatingConfig(
    val globalThreshold: Float = DEFAULT_GLOBAL_THRESHOLD,
    val perCategory: Map<Int, Float> = emptyMap(),
) {

    fun thresholdFor(category: Int): Float =
        perCategory[category] ?: globalThreshold

    fun setThreshold(category: Int, threshold: Float?): LowRatingConfig {
        val next = LinkedHashMap(perCategory)
        if (threshold == null) {
            next.remove(category)
        } else {
            next[category] = threshold
        }
        return copy(perCategory = next)
    }

    /** Java 侧便捷入口。 */
    fun withGlobalThreshold(threshold: Float): LowRatingConfig =
        copy(globalThreshold = clamp(threshold))

    /** 已覆盖阈值的 catalog（保持 [Catalogs.ORDERED] 顺序）。 */
    fun overriddenCategories(): List<Int> =
        Catalogs.ORDERED.filter { perCategory.containsKey(it) }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json["globalThreshold"] = globalThreshold
        val cats = JSONObject()
        perCategory.forEach { (k, v) -> cats[k.toString()] = v }
        json["perCategory"] = cats
        return json
    }

    companion object {
        const val DEFAULT_GLOBAL_THRESHOLD = 3.0f

        /** 阈值合法范围：0 ~ 5，步长 0.1。 */
        @JvmStatic
        fun clamp(value: Float): Float =
            if (value.isNaN()) DEFAULT_GLOBAL_THRESHOLD else value.coerceIn(0f, 5f)

        @JvmStatic
        fun fromJson(json: JSONObject?): LowRatingConfig {
            if (json == null) return LowRatingConfig()
            val perCategory = LinkedHashMap<Int, Float>()
            json.getJSONObject("perCategory")?.let { obj ->
                for (key in obj.keys) {
                    val category = key.toIntOrNull() ?: continue
                    perCategory[category] = clamp(obj.getFloatValue(key))
                }
            }
            val global = json.containsKey("globalThreshold")
                .takeIf { it }?.let { clamp(json.getFloatValue("globalThreshold")) }
                ?: DEFAULT_GLOBAL_THRESHOLD
            return LowRatingConfig(global, perCategory)
        }
    }
}

/** 所有下载状态（用于“全选”的默认值）。 */
val ALL_DOWNLOAD_STATUSES: Set<Int> = setOf(
    DownloadInfo.STATE_NONE,
    DownloadInfo.STATE_WAIT,
    DownloadInfo.STATE_DOWNLOAD,
    DownloadInfo.STATE_FINISH,
    DownloadInfo.STATE_FAILED,
    DownloadInfo.STATE_RELAY_DOWNLOAD,
)
