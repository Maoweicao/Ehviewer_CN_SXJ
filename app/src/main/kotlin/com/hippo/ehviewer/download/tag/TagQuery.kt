/*
 * Copyright 2026 EhViewer Contributors
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

package com.hippo.ehviewer.download.tag

import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.GalleryTags
import com.hippo.ehviewer.dao.QuickSearch
import java.util.Locale

/**
 * 一条标签条件。
 *
 * [namespace] 为空表示匹配任意命名空间的值；[exact] 对应 E-Hentai 查询里的 `$` 后缀。
 */
data class TagTerm(
    val namespace: String?,
    val value: String,
    val exact: Boolean,
)

/**
 * 快速搜索条目（画廊右滑面板里的“书签”）解析后的标签查询。
 *
 * 只处理能被本地 `Gallery_Tags` 判定的部分：
 *  - `namespace:value` / `namespace:value$` / 引号包裹的多词值；
 *  - `-term` 否定；
 *  - `a ~b` 形式的或（并到前一个条件组）。
 *
 * 含标题 / 作者等非标签字段、通配符或未闭合引号时 [isValid] 为 false，
 * 调用方应把该分类标成“非标签”，而不是静默当成命中 0 条。
 */
class TagQuery internal constructor(
    val source: String,
    /** 组间是“与”，组内是“或” */
    val groups: List<List<TagTerm>>,
    val negatives: List<TagTerm>,
    val isValid: Boolean,
    val invalidReason: String?,
) {

    fun matches(tokens: Set<String>): Boolean {
        if (!isValid) return false
        for (group in groups) {
            if (group.none { matchesTerm(it, tokens) }) return false
        }
        for (term in negatives) {
            if (matchesTerm(term, tokens)) return false
        }
        return true
    }

    private fun matchesTerm(term: TagTerm, tokens: Set<String>): Boolean {
        for (token in tokens) {
            val colon = token.indexOf(':')
            if (colon <= 0) continue
            if (term.namespace != null && term.namespace != token.substring(0, colon)) {
                continue
            }
            val value = token.substring(colon + 1)
            if (if (term.exact) value == term.value else value.contains(term.value)) {
                return true
            }
        }
        return false
    }

    companion object {
        @JvmStatic
        fun invalid(source: String, reason: String): TagQuery =
            TagQuery(source, emptyList(), emptyList(), false, reason)
    }
}

object TagQueryParser {

    /** `Gallery_Tags` 的列名，同时也是 E-Hentai 的标签命名空间。 */
    private val NAMESPACES = setOf(
        "rows", "artist", "cosplayer", "character", "female", "group",
        "language", "location", "male", "misc", "mixed", "other", "parody", "reclass",
    )

    /** EH 查询里有特殊含义、但本地标签无法判定的字符。 */
    private val UNSUPPORTED = charArrayOf('|', '(', ')', '*', '?', '!', '{', '}', '[', ']', '\\')

    @JvmStatic
    fun parse(query: String?): TagQuery {
        val source = query?.trim().orEmpty()
        if (source.isEmpty()) {
            return TagQuery.invalid(source, "empty")
        }
        val tokens = tokenize(source)
            ?: return TagQuery.invalid(source, "unclosed_quote")

        val groups = ArrayList<MutableList<TagTerm>>()
        val negatives = ArrayList<TagTerm>()

        for (token in tokens) {
            if (token.isEmpty()) continue

            var raw = token
            var negated = false
            var orWithPrevious = false

            if (raw.startsWith("-")) {
                negated = true
                raw = raw.substring(1)
            }
            if (raw.startsWith("~")) {
                if (negated) return TagQuery.invalid(source, "negated_or")
                orWithPrevious = true
                raw = raw.substring(1)
            }

            var exact = false
            if (raw.endsWith("$")) {
                exact = true
                raw = raw.dropLast(1)
            }
            if (raw.isEmpty()) {
                return TagQuery.invalid(source, "empty_term")
            }
            if (raw.any { it in UNSUPPORTED }) {
                return TagQuery.invalid(source, "wildcard")
            }

            val colon = raw.indexOf(':')
            val namespace: String?
            val value: String
            if (colon > 0) {
                namespace = raw.substring(0, colon).trim().lowercase(Locale.ROOT)
                if (namespace !in NAMESPACES) {
                    // title: / uploader: / tags: 等不是标签字段
                    return TagQuery.invalid(source, "non_tag_field")
                }
                value = TagStore.normalize(raw.substring(colon + 1))
            } else {
                namespace = null
                value = TagStore.normalize(raw)
            }
            if (value.isEmpty()) {
                return TagQuery.invalid(source, "empty_value")
            }

            val term = TagTerm(namespace, value, exact)
            if (negated) {
                negatives.add(term)
            } else if (orWithPrevious) {
                if (groups.isEmpty()) {
                    return TagQuery.invalid(source, "leading_or")
                }
                groups[groups.size - 1].add(term)
            } else {
                groups.add(mutableListOf(term))
            }
        }

        if (groups.isEmpty() && negatives.isEmpty()) {
            return TagQuery.invalid(source, "no_terms")
        }
        return TagQuery(source, groups, negatives, true, null)
    }

    /**
     * 按空白拆分，双引号内不拆；引号本身不进入结果。
     *
     * @return 未闭合引号时返回 null
     */
    private fun tokenize(input: String): List<String>? {
        val result = ArrayList<String>()
        val builder = StringBuilder()
        var inQuote = false
        var quoted = false
        for (ch in input) {
            when {
                ch == '"' -> {
                    inQuote = !inQuote
                    quoted = true
                }
                ch.isWhitespace() && !inQuote -> {
                    if (builder.isNotEmpty() || quoted) {
                        result.add(builder.toString())
                        builder.setLength(0)
                        quoted = false
                    }
                }
                else -> builder.append(ch)
            }
        }
        if (inQuote) return null
        if (builder.isNotEmpty() || quoted) {
            result.add(builder.toString())
        }
        return result
    }
}

/**
 * `Gallery_Tags` 与内存标签的统一读取口。
 *
 * 统一转成 `namespace:value`，值按 `GalleryTagsWriter` 的口径归一化
 * （小写、`+` 转空格），保证计数与下载列表的标签搜索看到的是同一份数据。
 */
object TagStore {

    private val COLUMNS: Array<Pair<String, (GalleryTags) -> String?>> = arrayOf(
        "rows" to { it.rows },
        "artist" to { it.artist },
        "cosplayer" to { it.cosplayer },
        "character" to { it.character },
        "female" to { it.female },
        "group" to { it.group },
        "language" to { it.language },
        "location" to { it.location },
        "male" to { it.male },
        "misc" to { it.misc },
        "mixed" to { it.mixed },
        "other" to { it.other },
        "parody" to { it.parody },
        "reclass" to { it.reclass },
    )

    @JvmStatic
    fun normalize(value: String?): String =
        value?.trim()?.lowercase(Locale.ROOT)?.replace('+', ' ') ?: ""

    /** `GID -> 标签 token 集合`；只包含 `Gallery_Tags` 里已有标签的画廊。 */
    @JvmStatic
    fun loadAll(): Map<Long, Set<String>> {
        val result = HashMap<Long, MutableSet<String>>()
        val rows = try {
            com.hippo.ehviewer.EhDB.getAllGalleryTags()
        } catch (t: Throwable) {
            emptyList<GalleryTags>()
        }
        for (row in rows) {
            val tokens = tokensOfRow(row)
            if (tokens.isNotEmpty()) {
                result[row.gid] = tokens
            }
        }
        return result
    }

    @JvmStatic
    fun tokensOfRow(row: GalleryTags?): MutableSet<String> {
        val tokens = LinkedHashSet<String>()
        if (row == null) return tokens
        for ((namespace, getter) in COLUMNS) {
            val content = try {
                getter(row)
            } catch (t: Throwable) {
                null
            }
            addColumn(tokens, namespace, content)
        }
        return tokens
    }

    /** 条目内存里的 `simpleTags` / `tgList`（尚未落库的标签）。 */
    @JvmStatic
    fun tokensOf(info: GalleryInfo?): MutableSet<String> {
        val tokens = LinkedHashSet<String>()
        if (info == null) return tokens
        info.simpleTags?.forEach { addToken(tokens, it) }
        info.tgList?.forEach { addToken(tokens, it) }
        return tokens
    }

    /** 表格标签 + 条目内存标签。 */
    @JvmStatic
    fun resolve(gid: Long, table: Map<Long, Set<String>>?, info: GalleryInfo?): Set<String> {
        val fromTable = table?.get(gid)
        if (fromTable.isNullOrEmpty()) return tokensOf(info)
        if (info == null) return fromTable
        val merged = LinkedHashSet(fromTable)
        merged.addAll(tokensOf(info))
        return merged
    }

    private fun addColumn(out: MutableSet<String>, namespace: String, content: String?) {
        if (content.isNullOrEmpty()) return
        for (raw in content.split(',')) {
            val value = normalize(raw)
            if (value.isNotEmpty()) {
                out.add("$namespace:$value")
            }
        }
    }

    private fun addToken(out: MutableSet<String>, raw: String?) {
        if (raw.isNullOrEmpty()) return
        val colon = raw.indexOf(':')
        if (colon <= 0) return
        val namespace = raw.substring(0, colon).trim().lowercase(Locale.ROOT)
        if (namespace.isEmpty()) return
        val value = normalize(raw.substring(colon + 1))
        if (value.isNotEmpty()) {
            out.add("$namespace:$value")
        }
    }
}

/** 一个书签分类及其在当前范围内的命中数。 */
data class TagCategory(
    val id: Long,
    val name: String,
    val keyword: String,
    val count: Int,
    /** 关键字是否能按本地标签判定；false 时右侧显示“—”且点击不筛选 */
    val valid: Boolean,
)

/**
 * 用倒排后的“每条画廊的标签集合”给快速搜索书签算命中数。
 *
 * 一次遍历所有画廊即可；调用方在 IO 线程执行。
 */
object TagCategoryBuilder {

    @JvmStatic
    @JvmOverloads
    fun build(
        searches: List<QuickSearch>?,
        tagsByGid: Map<Long, Set<String>>?,
        gids: Collection<Long>? = null,
    ): List<TagCategory> {
        if (searches.isNullOrEmpty()) return emptyList()
        val table = tagsByGid ?: emptyMap()
        val scope = gids ?: table.keys
        val result = ArrayList<TagCategory>(searches.size)
        for (search in searches) {
            val keyword = search.keyword?.trim().orEmpty()
            val name = search.name?.trim()?.takeIf { it.isNotEmpty() } ?: keyword
            val parsed = TagQueryParser.parse(keyword)
            var count = 0
            if (parsed.isValid) {
                for (gid in scope) {
                    val tokens = table[gid] ?: emptySet()
                    if (parsed.matches(tokens)) count++
                }
            }
            result.add(
                TagCategory(
                    id = search.id ?: -1L,
                    name = name,
                    keyword = keyword,
                    count = count,
                    valid = parsed.isValid,
                )
            )
        }
        return result
    }
}
