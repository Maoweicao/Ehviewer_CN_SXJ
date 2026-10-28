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

import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 排除规则的纯匹配逻辑（不含任何 IO / DB 访问，便于单测与复用）。
 *
 * 调用方负责把懒加载的字段（Tag 列表、AI 描述）准备好传进来。
 */
object FilterMatcher {

    /**
     * 一条待匹配的字段集合。
     *
     * [tags] 与 [description] 都是懒加载的（可能来自 DB / AI 缓存），
     * 只有当排除规则真的需要看它们时才会去取值。
     */
    class Fields(
        @JvmField val category: Int,
        @JvmField val title: String?,
        @JvmField val titleJpn: String?,
        @JvmField val uploader: String?,
        tagsProvider: () -> List<String>,
        descriptionProvider: () -> String?,
    ) {
        val tags: List<String> by lazy(tagsProvider)

        val description: String? by lazy(descriptionProvider)

        private var nameText: String? = null
        private var allText: String? = null
        private var tagsLowerCache: List<String>? = null

        /** 名字（日文标题 + 标题）拼成一段文本。 */
        fun nameText(): String {
            nameText?.let { return it }
            val sb = StringBuilder()
            titleJpn?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }
            title?.takeIf { it.isNotEmpty() }?.let { sb.append(it) }
            return sb.toString().also { nameText = it }
        }

        /** 全部可见文本（名字 + 作者 + Tag），用于 [KeywordScope.ANY]。 */
        fun allText(): String {
            allText?.let { return it }
            val sb = StringBuilder(nameText())
            uploader?.takeIf { it.isNotEmpty() }?.let { sb.append('\n').append(it) }
            tags.forEach { sb.append('\n').append(it) }
            return sb.toString().also { allText = it }
        }

        fun tagsLower(): List<String> =
            tagsLowerCache ?: tags.map { normalizeTagToken(it) }.also { tagsLowerCache = it }
    }

    /** 标签 / 关键词归一化：小写，e-hentai 标签中的 '+' 表示空格。 */
    @JvmStatic
    fun normalizeTagToken(s: String?): String =
        s?.trim()?.lowercase(Locale.ROOT)?.replace('+', ' ') ?: ""

    /**
     * 判断条目是否被排除规则命中。
     *
     * @param aliasResolver 可选：把关键词/标签解析成别名（含中文翻译），用于中英互排
     */
    @JvmStatic
    @JvmOverloads
    fun isExcluded(
        rule: ExcludeRule,
        fields: Fields,
        aliasResolver: ((String) -> List<String>)? = null,
    ): Boolean {
        if (rule.isEmpty) return false

        if (rule.excludedCategories.isNotEmpty() && rule.excludedCategories.contains(fields.category)) {
            return true
        }

        if (rule.excludedTags.isNotEmpty()) {
            val fieldTags = fields.tagsLower()
            val fieldTagSet = HashSet(fieldTags)
            for (rawTag in rule.excludedTags) {
                if (matchesTag(rawTag, fieldTagSet, aliasResolver)) {
                    return true
                }
            }
        }

        if (rule.keywords.isNotEmpty()) {
            for (keyword in rule.keywords) {
                if (matchesKeyword(keyword, fields, aliasResolver)) {
                    return true
                }
            }
        }

        return false
    }

    /** 排除 Tag：支持 `ns:value`、裸值、以及解析出的中英别名（精确 token，不做子串）。 */
    private fun matchesTag(
        rawTag: String,
        fieldTagSet: Set<String>,
        aliasResolver: ((String) -> List<String>)?,
    ): Boolean {
        val needle = normalizeTagToken(rawTag)
        if (needle.isEmpty()) return false
        if (fieldTagSet.contains(needle)) return true
        // 忽略命名空间前缀：配置 "arknights" 也能命中 "parody:arknights"
        val idx = needle.indexOf(':')
        if (idx >= 0 && fieldTagSet.contains(needle.substring(idx + 1))) {
            return true
        }
        aliasResolver?.invoke(rawTag)?.forEach { alias ->
            val a = normalizeTagToken(alias)
            if (a.isEmpty()) return@forEach
            if (fieldTagSet.contains(a)) return true
            val i = a.indexOf(':')
            if (i >= 0 && fieldTagSet.contains(a.substring(i + 1))) return true
        }
        return false
    }

    private fun matchesKeyword(
        keyword: ScopedKeyword,
        fields: Fields,
        aliasResolver: ((String) -> List<String>)?,
    ): Boolean {
        val text = keyword.text.trim()
        if (text.isEmpty()) return false
        val haystacks = haystacksFor(keyword.scope, fields)
        if (haystacks.isEmpty()) return false

        val pattern = compilePattern(text, keyword)
        if (pattern != null) {
            // 正则 / 整词模式直接对原文匹配，忽略大小写交给正则 flag
            return haystacks.any { runCatching { pattern.matcher(it).find() }.getOrDefault(false) }
        }

        val needle = if (keyword.ignoreCase) text.lowercase(Locale.ROOT) else text
        val candidates = buildList {
            add(needle)
            aliasResolver?.invoke(text)?.let { resolved ->
                resolved.forEach { alias ->
                    add(if (keyword.ignoreCase) alias.lowercase(Locale.ROOT) else alias)
                }
            }
        }
        return haystacks.any { haystack ->
            val lower = if (keyword.ignoreCase) haystack.lowercase(Locale.ROOT) else haystack
            candidates.any { candidate ->
                if (candidate.isEmpty()) {
                    false
                } else if (keyword.wholeWord) {
                    containsWholeWord(lower, candidate)
                } else {
                    lower.contains(candidate)
                }
            }
        }
    }

    /** 按作用域挑出要匹配的文本片段。 */
    private fun haystacksFor(scope: KeywordScope, fields: Fields): List<String> =
        when (scope) {
            KeywordScope.NAME -> listOf(fields.nameText())
            KeywordScope.UPLOADER -> listOfNotNull(fields.uploader?.takeIf { it.isNotEmpty() })
            KeywordScope.TAG -> fields.tags
            KeywordScope.DESC -> listOfNotNull(fields.description?.takeIf { it.isNotEmpty() })
            KeywordScope.ANY ->
                buildList {
                    add(fields.nameText())
                    fields.uploader?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    addAll(fields.tags)
                    fields.description?.takeIf { it.isNotEmpty() }?.let { add(it) }
                }
        }

    private fun compilePattern(text: String, keyword: ScopedKeyword): Pattern? = try {
        val flags = if (keyword.ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
        val regex = when {
            keyword.regex -> text
            keyword.wholeWord -> "(?<!\\p{L}\\p{N})" + Pattern.quote(text) + "(?!\\p{L}\\p{N})"
            else -> Pattern.quote(text)
        }
        Pattern.compile(regex, flags)
    } catch (e: PatternSyntaxException) {
        null
    }

    /** 整词匹配（Unicode 字母/数字边界）。 */
    private fun containsWholeWord(haystack: String, needle: String): Boolean {
        val lowerNeedle = needle.lowercase(Locale.ROOT)
        var from = 0
        while (true) {
            val idx = haystack.indexOf(lowerNeedle, from)
            if (idx < 0) return false
            val before = if (idx == 0) ' ' else haystack[idx - 1]
            val afterIndex = idx + lowerNeedle.length
            val after = if (afterIndex >= haystack.length) ' ' else haystack[afterIndex]
            if (!isWordChar(before) && !isWordChar(after)) return true
            from = idx + 1
        }
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** 有效评分是否落在“低分”范围（未评分 = -1 不算低分）。 */
    @JvmStatic
    fun isLowRating(effectiveRating: Float, threshold: Float): Boolean =
        effectiveRating > 0f && effectiveRating < threshold
}
