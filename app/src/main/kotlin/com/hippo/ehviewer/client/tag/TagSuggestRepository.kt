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

package com.hippo.ehviewer.client.tag

import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.dao.GalleryTags
import com.hippo.ehviewer.util.SearchDebugLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 标签智能输入的建议源。
 *
 * 合并两个来源，**本地优先**：
 *
 * 1. **本地已下载画廊的标签**（`Gallery_Tags` 表去重 + 计数）—— 用户自己的语料，
 *    排序按“我在多少个画廊里见过这个 tag”降序。
 * 2. **在线标签词库**（[EhTagDatabase]，含中英翻译）—— 补全本地没有的 tag。
 *
 * 为什么要专门做本地索引：`Gallery_Tags` 每个 namespace 一列、值是逗号分隔的
 * 裸 tag（不带 namespace），没有任何聚合查询，只能一次性扫描后建索引缓存。
 *
 * **重建策略**：扫描整张表比较贵，所以只在 [invalidate] 后重建一次；
 * 重建期间旧索引继续可用（[suggest] 不会因为重建而变慢或返回空）。
 */
object TagSuggestRepository {

    /** 建议的作用域。 */
    enum class SuggestScope {
        /** 只给 `ns:value` 形式（排除 tag / 屏蔽过滤器的 tag 模式）。 */
        TAG,

        /** 给裸词（排除关键词的“名字/作者/AI描述”作用域）。 */
        TEXT,
    }

    /** 建议来源，用于 UI 上区分与排序。 */
    enum class Source {
        LOCAL,
        DICT,
    }

    /**
     * 一条建议。[tag] 一律是 `namespace:value` 规范形式。
     *
     * 排序：本地 > 词库；同为本地按命中次数降序；同来源按匹配等级（精确 > 前缀 > 子串）。
     */
    data class TagSuggestion(
        val tag: String,
        /** 中文翻译，词库才有；本地独有时为 null。 */
        val translation: String?,
        /** 本地出现次数，0 表示本地没有。 */
        val localCount: Int,
        val source: Source,
        /** 匹配等级：0=精确 1=前缀 2=子串，越小越靠前。 */
        private val matchRank: Int,
    ) : Comparable<TagSuggestion> {

        override fun compareTo(other: TagSuggestion): Int {
            val bySource = source.ordinal - other.source.ordinal
            if (bySource != 0) return bySource
            val byRank = matchRank - other.matchRank
            if (byRank != 0) return byRank
            val byCount = other.localCount - localCount
            if (byCount != 0) return byCount
            return tag.compareTo(other.tag)
        }
    }

    private val building = AtomicBoolean(false)

    /** 只增不减的代数，用来判断「构建期间数据又变了」。 */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    /** 旧索引在重建期间继续提供服务，所以都是 volatile 引用替换。 */
    @Volatile
    private var localCounts: Map<String, Int> = emptyMap()

    /** 裸值 -> 规范 `ns:value`，用户只输入 `arknights` 也能命中 `parody:arknights`。 */
    @Volatile
    private var bareToTags: Map<String, List<String>> = emptyMap()

    @Volatile
    private var localIndexReady = false

    @Volatile
    private var stale = true

    /**
     * 需要时在后台建本地索引。
     *
     * 建索引要读整张 `Gallery_Tags` 表并拆逗号，**绝不能在主线程**——
     * [suggest] 是每敲一个键就跑一次的热路径，只允许读现成的 Map。
     */
    @JvmStatic
    fun warmUpAsync() {
        if (!stale && localIndexReady) return
        if (!building.compareAndSet(false, true)) return
        val thread = Thread {
            val gen = generation.get()
            try {
                if (stale) {
                    val built = buildIndex()
                    localCounts = built.first
                    bareToTags = built.second
                    localIndexReady = true
                }
            } catch (t: Throwable) {
                // 建索引失败只影响建议质量，不影响筛选功能本身
                localIndexReady = true
            } finally {
                building.set(false)
                // 构建期间数据又变了：这一版已经过期，排下一轮
                stale = generation.get() != gen
                if (stale) {
                    warmUpAsync()
                }
            }
        }
        thread.name = "tag-suggest-index"
        thread.isDaemon = true
        thread.priority = Thread.NORM_PRIORITY - 1
        thread.start()
    }

    /** 标签数据变了（下载增删 / 同步了画廊标签）就标脏，后台重建一次。 */
    @JvmStatic
    fun invalidate() {
        generation.incrementAndGet()
        stale = true
        warmUpAsync()
    }

    private fun buildIndex(): Pair<Map<String, Int>, Map<String, List<String>>> {
        val counts = LinkedHashMap<String, Int>()
        val bare = HashMap<String, MutableList<String>>()
        val rows: List<GalleryTags> = try {
            EhDB.getAllGalleryTags()
        } catch (t: Throwable) {
            emptyList()
        }
        for (row in rows) {
            for (ns in NAMESPACES) {
                val column = columnOf(row, ns) ?: continue
                for (value in column.split(',')) {
                    val v = value.trim()
                    if (v.isEmpty()) continue
                    // e-hentai 的 tag 里 '+' 表示空格，归一化成空格再入库
                    val normalized = v.lowercase().replace('+', ' ')
                    val key = "$ns:$normalized"
                    counts[key] = (counts[key] ?: 0) + 1
                    bare.getOrPut(normalized) { ArrayList() }.add(key)
                }
            }
        }
        bare.values.forEach { it.sort() }
        return counts to bare
    }

    /**
     * 查询建议。**热路径**：不做 IO，只读现成的 Map。
     *
     * @param query 用户输入的碎片（可以直接带 namespace 前缀，如 `parody:ark`）
     * @param scope 见 [SuggestScope]
     * @param limit 上限
     */
    @JvmStatic
    @JvmOverloads
    fun suggest(
        query: String?,
        scope: SuggestScope = SuggestScope.TAG,
        limit: Int = 20,
    ): List<TagSuggestion> {
        val raw = query?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty() || limit <= 0) return emptyList()

        // 顺手把过期索引排掉；重建在后台，本次仍用旧索引
        if (stale) warmUpAsync()

        val counts = localCounts
        val bare = bareToTags

        // 用户可能直接打 "parody:ark"，拆出 namespace 过滤与词条
        val colon = raw.indexOf(':')
        val nsFilter = if (colon > 0) raw.substring(0, colon) else null
        val term = if (colon > 0) raw.substring(colon + 1) else raw
        if (term.isEmpty()) return emptyList()

        val result = ArrayList<TagSuggestion>(minOf(limit, MAX_RESULTS))
        val seen = HashSet<String>()

        // ---- 1. 本地标签（优先） ----
        if (isLocalEnabled() && localIndexReady && counts.isNotEmpty()) {
            val localExact = ArrayList<TagSuggestion>()
            val localPrefix = ArrayList<TagSuggestion>()
            val localSub = ArrayList<TagSuggestion>()
            for ((key, count) in counts) {
                if (nsFilter != null && !key.startsWith("$nsFilter:")) continue
                val bareValue = key.substringAfter(':', "")
                if (bareValue.isEmpty()) continue
                val rank = when {
                    bareValue == term -> 0
                    bareValue.startsWith(term) -> 1
                    else -> 2
                }
                val item = TagSuggestion(key, null, count, Source.LOCAL, rank)
                when (rank) {
                    0 -> localExact.add(item)
                    1 -> localPrefix.add(item)
                    else -> if (bareValue.contains(term)) localSub.add(item)
                }
            }
            // 裸值反查：用户记得值不记得 namespace
            if (nsFilter == null) {
                for (key in bare[term].orEmpty()) {
                    val count = counts[key] ?: continue
                    localExact.add(TagSuggestion(key, null, count, Source.LOCAL, 0))
                }
            }
            localExact.sorted().forEach { addIfNew(result, seen, it, limit) }
            localPrefix.sorted().forEach { addIfNew(result, seen, it, limit) }
            localSub.sorted().forEach { addIfNew(result, seen, it, limit) }
        }

        // ---- 2. 词库（补全本地没有的） ----
        if (result.size < limit && isDictEnabled()) {
            val db = tagDatabase()
            if (db != null) {
                val pairs = db.suggest(if (nsFilter != null) "$nsFilter:$term" else term)
                for (pair in pairs) {
                    if (result.size >= limit) break
                    val english = pair?.second ?: continue
                    if (english.isEmpty() || seen.contains(english)) continue
                    if (nsFilter != null && !english.startsWith("$nsFilter:")) continue
                    val chinese = pair.first?.takeIf { it.isNotEmpty() && it != "null" }
                    val count = counts[english] ?: 0
                    val bareValue = english.substringAfter(':', "")
                    val rank = when {
                        bareValue == term -> 0
                        bareValue.startsWith(term) -> 1
                        else -> 2
                    }
                    addIfNew(
                        result, seen,
                        TagSuggestion(english, chinese, count, if (count > 0) Source.LOCAL else Source.DICT, rank),
                        limit,
                    )
                }
            }
        }

        val dbAvailable = if (isDictEnabled()) tagDatabase() != null else false
        SearchDebugLog.d(
            "TagSuggestRepo",
            "suggest('" + query + "' scope=" + scope + ") -> " + result.size
                + " | localReady=" + localIndexReady + " localTags=" + counts.size
                + " dictAvailable=" + dbAvailable,
        )
        return result
    }

    /** 该 tag 在本地出现过多少次；0 表示本地没有。 */
    @JvmStatic
    fun localCountOf(tag: String?): Int = if (tag.isNullOrEmpty()) 0 else (localCounts[tag] ?: 0)

    /** 词库翻译；词库未加载或该 tag 不在词库时返回 null。 */
    @JvmStatic
    fun translationOf(tag: String?): String? {
        if (tag.isNullOrEmpty()) return null
        val db = tagDatabase() ?: return null
        return db.getTranslation(tag)?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun addIfNew(
        out: MutableList<TagSuggestion>,
        seen: MutableSet<String>,
        item: TagSuggestion,
        limit: Int,
    ) {
        if (out.size >= limit) return
        if (!seen.add(item.tag)) return
        out.add(item)
    }

    /** 读设置时兜底为「开启」，避免 Settings 初始化顺序问题影响可用性。 */
    private fun isDictEnabled(): Boolean = try {
        com.hippo.ehviewer.Settings.getTagSuggestDictEnabled()
    } catch (t: Throwable) {
        true
    }

    private fun isLocalEnabled(): Boolean = try {
        com.hippo.ehviewer.Settings.getTagSuggestLocalEnabled()
    } catch (t: Throwable) {
        true
    }

    /** 取词库实例；拿不到 Context 或词库未加载时返回 null（只影响建议，不影响筛选）。 */
    private fun tagDatabase(): EhTagDatabase? {
        val ctx = appContext() ?: return null
        return try {
            EhTagDatabase.getInstance(ctx)
        } catch (t: Throwable) {
            null
        }
    }

    private fun appContext(): android.content.Context? = try {
        com.hippo.ehviewer.EhApplication.getInstance()?.applicationContext
    } catch (t: Throwable) {
        null
    }

    private fun columnOf(row: GalleryTags, ns: String): String? = when (ns) {
        "rows" -> row.rows
        "artist" -> row.artist
        "cosplayer" -> row.cosplayer
        "character" -> row.character
        "female" -> row.female
        "group" -> row.group
        "language" -> row.language
        "male" -> row.male
        "misc" -> row.misc
        "mixed" -> row.mixed
        "other" -> row.other
        "parody" -> row.parody
        "reclass" -> row.reclass
        "location" -> row.location
        else -> null
    }

    /** `Gallery_Tags` 的 namespace 列名（= namespace 名）。 */
    private val NAMESPACES = arrayOf(
        "rows", "artist", "cosplayer", "character", "female", "group",
        "language", "male", "misc", "mixed", "other", "parody", "reclass", "location",
    )

    private const val MAX_RESULTS = 40
}
