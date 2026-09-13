/*
 * Copyright 2016 Hippo Seven
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
package com.hippo.ehviewer.client

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import java.util.regex.Pattern

object EhUtils {

    @JvmField
    val NONE: Int = -1 // Use it for homepage
    const val UNKNOWN: Int = 0x400

    @JvmField
    val ALL_CATEGORY: Int = UNKNOWN - 1

    // E-Hentai category colors (ct1-cta)
    const val BG_COLOR_DOUJINSHI: Int = -0x3b1b2    // #fc4e4e
    const val BG_COLOR_MANGA: Int = -0x1873e6       // #e78c1a
    const val BG_COLOR_ARTIST_CG: Int = -0x3840f9   // #c7bf07
    const val BG_COLOR_GAME_CG: Int = -0xe56ce9     // #1a9317
    const val BG_COLOR_WESTERN: Int = -0xa23ec5     // #5dc13b
    const val BG_COLOR_NON_H: Int = -0xf06143       // #0f9ebd
    const val BG_COLOR_IMAGE_SET: Int = -0xd8a956   // #2756aa
    const val BG_COLOR_COSPLAY: Int = -0x77ff3d     // #8800c3
    const val BG_COLOR_ASIAN_PORN: Int = -0x4bad5b  // #b452a5
    const val BG_COLOR_MISC: Int = -0x8f8f90        // #707070
    val BG_COLOR_UNKNOWN: Int = Color.BLACK

    // Remove [XXX], (XXX), {XXX}, ~XXX~ stuff
    val PATTERN_TITLE_PREFIX: Pattern = Pattern.compile(
        "^(?:(?:\\([^\\)]*\\))|(?:\\[[^\\]]*\\])|(?:\\{[^\\}]*\\})|(?:~[^~]*~)|\\s+)*"
    )

    // Remove [XXX], (XXX), {XXX}, ~XXX~ stuff and something like ch. 1-23
    val PATTERN_TITLE_SUFFIX: Pattern = Pattern.compile(
        "(?:\\s+ch.[\\s\\d-]+)?(?:(?:\\([^\\)]*\\))|(?:\\[[^\\]]*\\])|(?:\\{[^\\}]*\\})|(?:~[^~]*~)|\\s+)*$",
        Pattern.CASE_INSENSITIVE
    )

    private val CATEGORY_VALUES = intArrayOf(
        EhConfig.MISC,
        EhConfig.DOUJINSHI,
        EhConfig.MANGA,
        EhConfig.ARTIST_CG,
        EhConfig.GAME_CG,
        EhConfig.IMAGE_SET,
        EhConfig.COSPLAY,
        EhConfig.ASIAN_PORN,
        EhConfig.NON_H,
        EhConfig.WESTERN,
        UNKNOWN
    )

    private val CATEGORY_STRINGS = arrayOf<Array<String>?>(
        arrayOf<String>("misc"),
        arrayOf<String>("doujinshi"),
        arrayOf<String>("manga"),
        arrayOf<String>("artistcg", "Artist CG Sets", "Artist CG"),
        arrayOf<String>("gamecg", "Game CG Sets", "Game CG"),
        arrayOf<String>("imageset", "Image Sets", "Image Set"),
        arrayOf<String>("cosplay"),
        arrayOf<String>("asianporn", "Asian Porn"),
        arrayOf<String>("non-h"),
        arrayOf<String>("western"),
        arrayOf<String>("unknown")
    )

    private val CATEGORY_STRING_RES = intArrayOf(
        R.string.misc,
        R.string.doujinshi,
        R.string.manga,
        R.string.artist_cg,
        R.string.game_cg,
        R.string.image_set,
        R.string.cosplay,
        R.string.asian_porn,
        R.string.non_h,
        R.string.western,
        R.string.unknown
    )

    @JvmStatic
    fun getCategory(type: String?): Int {
        var i: Int
        i = 0
        while (i < CATEGORY_STRINGS.size - 1) {
            for (str in CATEGORY_STRINGS[i]!!) if (str.equals(
                    type,
                    ignoreCase = true
                )
            ) return CATEGORY_VALUES[i]
            i++
        }

        return CATEGORY_VALUES[i]
    }

    @JvmStatic
    fun getCategory(type: Int): String? {
        var i: Int
        i = 0
        while (i < CATEGORY_VALUES.size - 1) {
            if (CATEGORY_VALUES[i] == type) break
            i++
        }
        return CATEGORY_STRINGS[i]!![0]
    }

    @JvmStatic
    fun getCategoryName(context: Context, category: Int): String {
        var i = 0
        while (i < CATEGORY_VALUES.size - 1) {
            if (CATEGORY_VALUES[i] == category) break
            i++
        }
        return context.getString(CATEGORY_STRING_RES[i])
    }

    @JvmStatic
    fun getCategoryColor(category: Int): Int {
        when (category) {
            EhConfig.DOUJINSHI -> return BG_COLOR_DOUJINSHI
            EhConfig.MANGA -> return BG_COLOR_MANGA
            EhConfig.ARTIST_CG -> return BG_COLOR_ARTIST_CG
            EhConfig.GAME_CG -> return BG_COLOR_GAME_CG
            EhConfig.WESTERN -> return BG_COLOR_WESTERN
            EhConfig.NON_H -> return BG_COLOR_NON_H
            EhConfig.IMAGE_SET -> return BG_COLOR_IMAGE_SET
            EhConfig.COSPLAY -> return BG_COLOR_COSPLAY
            EhConfig.ASIAN_PORN -> return BG_COLOR_ASIAN_PORN
            EhConfig.MISC -> return BG_COLOR_MISC
            else -> return BG_COLOR_UNKNOWN
        }
    }

    @JvmStatic
    fun signOut(context: Context) {
        EhApplication.getEhCookieStore(context).signOut()
        Settings.putAvatar(null)
        Settings.putDisplayName(null)
        Settings.putNeedSignIn(true)
    }

    @JvmStatic
    fun needSignedIn(context: Context): Boolean {
        return Settings.getNeedSignIn() && !EhApplication.getEhCookieStore(context).hasSignedIn()
    }

    @JvmStatic
    fun getSuitableTitle(gi: GalleryInfo): String? {
        if (Settings.getShowJpnTitle()) {
            return if (TextUtils.isEmpty(gi.titleJpn)) gi.title else gi.titleJpn
        } else {
            return if (TextUtils.isEmpty(gi.title)) gi.titleJpn else gi.title
        }
    }

    @JvmStatic
    fun judgeSuitableTitle(gi: GalleryInfo, key: String): Boolean {
        val titleB = gi.titleJpn + "" + gi.title
        return titleB.contains(key)
    }

    @JvmStatic
    fun extractTitle(title: String?): String? {
        var title = title
        if (null == title) {
            return null
        }
        title = PATTERN_TITLE_PREFIX.matcher(title).replaceFirst("")
        title = PATTERN_TITLE_SUFFIX.matcher(title).replaceFirst("")
        // Sometimes title is combined by romaji and english translation.
        // Only need romaji.
        // TODO But not sure every '|' means that
        val index = title.indexOf('|')
        if (index >= 0) {
            title = title.substring(0, index)
        }
        if (title.isEmpty()) {
            return null
        } else {
            return title
        }
    }

    // Match content inside brackets: [XXX], (XXX), （XXX）, {XXX}, ~XXX~
    private val PATTERN_BRACKET_CONTENT: Pattern = Pattern.compile(
        "(?:\\[([^\\]]+)\\])|(?:\\(([^\\)]+)\\))|(?:（([^）]+)）)|(?:\\{([^\\}]+)\\})|(?:~([^~]+)~)"
    )

    // Noise tokens that don't help with search
    private val NOISE_PATTERNS = setOf(
        "AI翻译", "机翻", "汉化", "中国翻訳", "個人漢化",
        "无修", "DL版", "中国翻訳", "闲的没事干"
    )

    private fun isNoiseToken(token: String): Boolean {
        if (token.length <= 1) return true
        // Filter pure CJK single chars or noise words
        if (NOISE_PATTERNS.any { token.contains(it) }) return true
        return false
    }

    private fun isAlphaNumeric(c: Char): Boolean {
        return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
    }

    /**
     * Tokenize gallery title into search keywords.
     * Extracts author names, series names, and other meaningful tokens from brackets and title body,
     * filters noise, and returns prioritized tokens for AND-based search.
     *
     * Example: "[Ourobot] GattoSpread (Animal Crossing)（闲的没事干AI翻译）"
     *   -> ["Ourobot", "GattoSpread", "Animal Crossing"]
     */
    @JvmStatic
    fun tokenizeTitle(title: String?): List<String> {
        if (title == null) return emptyList()
        val tokens = LinkedHashSet<String>()

        // Step 1: Extract content from brackets first
        val matcher = PATTERN_BRACKET_CONTENT.matcher(title)
        while (matcher.find()) {
            for (i in 1..5) {
                val group = matcher.group(i)
                if (group != null) {
                    val trimmed = group.trim()
                    // Split bracket content by common delimiters
                    for (part in trimmed.split(Regex("[\\s|\\-_/]+"))) {
                        val t = part.trim()
                        if (t.isNotEmpty() && !isNoiseToken(t)) {
                            tokens.add(t)
                        }
                    }
                }
            }
        }

        // Step 2: Extract text between brackets
        var remaining = PATTERN_BRACKET_CONTENT.matcher(title).replaceAll(" ").trim()
        // Remove leftover bracket characters
        remaining = remaining.replace(Regex("[\\[\\]\\(\\)（）\\{\\}~]"), " ")
        // Split by whitespace and common delimiters
        for (part in remaining.split(Regex("[\\s|\\-]+"))) {
            val t = part.trim()
            if (t.isNotEmpty() && !isNoiseToken(t)) {
                tokens.add(t)
            }
        }

        // Step 3: Sort by priority - alphanumeric/English first, then by length desc
        return tokens.toList().sortedWith(compareByDescending<String> { token ->
            var score = 0
            // English/alphanumeric tokens get higher priority
            if (token.any { isAlphaNumeric(it) }) score += 1000
            // Longer tokens are more distinctive
            score += token.length
            score
        })
    }

    // 整段标记匹配则整个丢弃，例如 [AI Generated]、[Digital]、[English Translation]
    private val NOISE_MARKER_PATTERNS: List<Regex> = listOf(
        Regex("(?i)ai-generated|ai generated|ai art|full color|english translation|scanlation|complete ver|preview|sample|update|digital"),
        Regex("AI翻译|机翻|汉化|无修|无修正|DL版|完全版")
    )

    // 批次/章节数字标记：P3、Vol.2、Part 1、Ch.5、No.12...
    private val PATTERN_SEQUENCE: Regex = Regex(
        "(?i)^(?:v(?:ol)?\\.?|p(?:art)?\\.?|ch(?:apter)?\\.?|no\\.?|ep(?:isode)?\\.?|volume|part|chapter)?\\d+$"
    )

    // 弱区分度的英文停用词，作为正文词保留对相似搜索无益
    private val STOP_WORDS: Set<String> = setOf(
        "the", "a", "an", "of", "and", "or", "in", "on", "for", "with", "to", "at", "no"
    )

    private fun isNoiseMarker(text: String): Boolean {
        return NOISE_MARKER_PATTERNS.any { it.containsMatchIn(text) }
    }

    private fun isSequenceToken(token: String): Boolean {
        return PATTERN_SEQUENCE.matches(token)
    }

    private fun appendSearchToken(parts: MutableList<String>, token: String) {
        val t = token.trim()
        if (t.isEmpty()) return
        // 含空白或常见分隔符的整体词加引号，保持作者名等整体语义
        if (t.any { it.isWhitespace() } || t.any { it == '/' || it == '.' || it == ',' }) {
            parts.add("\"$t\"")
        } else {
            parts.add(t)
        }
    }

    /**
     * 宽松提取相似画廊搜索关键词（用于“相似画廊”检索）。
     * - 括号/花括号等包裹的内容整体保留并自动加引号（作者名等），整段为噪声标记时丢弃
     * - 过滤 [AI Generated]、[Digital]、汉化、无修 等标记
     * - 过滤批次/章节数字（P3、Vol.2、Part 1、Ch.5...）
     * - 保留正文有效词语，去掉英文停用词
     *
     * 示例: "( KANTEIA ART ) IMAGE SET SOCIAL MEDIA P3 [AI Generated]"
     *   -> "KANTEIA ART" IMAGE SET SOCIAL MEDIA
     */
    @JvmStatic
    fun extractSearchKeywords(title: String?): String? {
        if (title == null) return null
        val parts = ArrayList<String>()

        // Step 1: 括号内容整体保留
        val matcher = PATTERN_BRACKET_CONTENT.matcher(title)
        while (matcher.find()) {
            for (i in 1..5) {
                val group = matcher.group(i) ?: continue
                val trimmed = group.trim()
                if (trimmed.isEmpty() || isNoiseMarker(trimmed)) continue
                appendSearchToken(parts, trimmed)
            }
        }

        // Step 2: 括号之间保留正文词
        var remaining = PATTERN_BRACKET_CONTENT.matcher(title).replaceAll(" ").trim()
        remaining = remaining.replace(Regex("[\\[\\]\\(\\)（）\\{\\}~]"), " ")
        for (part in remaining.split(Regex("[\\s|\\-_/]+"))) {
            val t = part.trim()
            if (t.isEmpty() || isNoiseToken(t) || isSequenceToken(t)) continue
            if (STOP_WORDS.contains(t.lowercase())) continue
            appendSearchToken(parts, t)
        }

        if (parts.isEmpty()) return null
        return parts.joinToString(" ")
    }

    // 下载目录名前缀：gid - 标题。例如 "12345 - [Artist] Title"
    private val PATTERN_GID_PREFIX: Regex = Regex("""^\d+\s*-\s*""")

    // 展会前缀：(C93) / （C93） / [C93] / ［C93］
    private val PATTERN_EXHIBITION: Regex = Regex(
        """^[（(\[［]\s*[Cc][0-9]{1,3}\s*[）)\]\］]\s*"""
    )

    // 平台前缀（方括号或裸词形式）
    private val PATTERN_PLATFORM_BRACKET: Regex = Regex(
        """^[\[［]\s*(?:pixiv|twitter|fanbox|fantia|skeb|patreon)[^\]］]*[\]］]\s*""",
        RegexOption.IGNORE_CASE
    )
    private val PATTERN_PLATFORM_BARE: Regex = Regex(
        """^(?:pixiv|twitter|fanbox|fantia|skeb|patreon)\s+""",
        RegexOption.IGNORE_CASE
    )

    // CJK 区间：汉字、平假名、片假名追加假名、谚文
    private fun isCjk(c: Char): Boolean {
        return c in '\u4e00'..'\u9fff' || c in '\u3040'..'\u30ff' ||
                c in '\u31f0'..'\u31ff' || c in '\uac00'..'\ud7af'
    }

    private fun hasCJK(text: String): Boolean {
        return text.any { isCjk(it) }
    }

    private fun cleanArtistName(raw: String): String? {
        var s = raw.trim().trim(' ', '\t', ',', ';', '·', '-', '|', '（', '）')
        s = s.trim()
        if (s.isEmpty() || s.length > 40) return null
        if (isNoiseMarker(s)) return null
        return s
    }

    private fun stripPlatformPrefix(text: String): String {
        var t = text
        var changed: Boolean
        do {
            changed = false
            val afterBracket = PATTERN_PLATFORM_BRACKET.replaceFirst(t, "").trim()
            if (afterBracket != t) {
                t = afterBracket
                changed = true
            }
            val afterBare = PATTERN_PLATFORM_BARE.replaceFirst(t, "").trim()
            if (afterBare != t) {
                t = afterBare
                changed = true
            }
        } while (changed)
        return t
    }

    /**
     * 从单个标题/目录名文本中提取作者名。
     * 规则：gid 前缀 → 展会前缀 → 平台前缀 → Patreon artist- → 首括号 [X (Y)]
     * 外层拉丁且内层全 CJK 时取外层（如 Abyonus(アビョノス)→Abyonus） → 正文
     * por/by（取后）、·（仅左侧非 CJK）、" - "（左侧非 CJK 且不超过 5 个词）。
     * 噪声/序列标记（isNoiseMarker/PATTERN_SEQUENCE）会被跳过，歧义时返回 null。
     */
    private fun extractArtistFromTitle(input: String): String? {
        var text = input.trim()
        if (text.isEmpty()) return null

        // gid 前缀：12345 - [Artist] Title
        text = PATTERN_GID_PREFIX.replaceFirst(text, "").trim()
        if (text.isEmpty()) return null

        // 展会前缀：(C93)、[C93] 等
        text = PATTERN_EXHIBITION.replaceFirst(text, "").trim()
        if (text.isEmpty()) return null

        // 平台前缀：[Pixiv]、Pixiv 等
        text = stripPlatformPrefix(text)
        if (text.isEmpty()) return null

        // Patreon artist- XXX / artist: XXX
        val patreon = Regex("""(?i)^\s*artist\s*[-:]\s*(.+)$""").find(text)
        if (patreon != null) {
            return cleanArtistName(patreon.groupValues[1])
        }

        // 正文 por/by：取右侧
        val porBy = Regex("""(?i)\s+(?:por|by)\s+([A-Za-z\u00C0-\u024F][^,;]*)$""").find(text)
        if (porBy != null) {
            val right = porBy.groupValues[1].trim()
            if (right.isNotEmpty() && !hasCJK(right) && right.length <= 30) {
                return cleanArtistName(right)
            }
        }

        // 首个有效括号内容
        val matcher = PATTERN_BRACKET_CONTENT.matcher(text)
        while (matcher.find()) {
            for (i in 1..5) {
                val content = matcher.group(i) ?: continue
                val trimmed = content.trim()
                if (trimmed.isEmpty() || isNoiseMarker(trimmed)) continue
                if (PATTERN_SEQUENCE.matches(trimmed)) continue
                if (trimmed.all { it.isDigit() }) continue
                val innerMatcher = Regex("""\(([^()]*)\)""").find(trimmed)
                if (innerMatcher != null) {
                    val inner = innerMatcher.groupValues[1].trim()
                    val outer = (trimmed.substring(0, innerMatcher.range.first) +
                            trimmed.substring(innerMatcher.range.last + 1)).trim()
                    val outerHasLatin = outer.isNotEmpty() && outer.any { isAlphaNumeric(it) }
                    val innerHasCJK = inner.isNotEmpty() && hasCJK(inner)
                    val candidate = if (outerHasLatin && innerHasCJK) outer else inner
                    val artist = cleanArtistName(candidate)
                    if (artist != null) {
                        return artist
                    }
                } else {
                    val artist = cleanArtistName(trimmed)
                    if (artist != null) {
                        return artist
                    }
                }
            }
        }

        // 括号被移除后的正文
        var remaining = PATTERN_BRACKET_CONTENT.matcher(text).replaceAll(" ").trim()
        remaining = remaining.replace(Regex("""[\[\]()（）{}~]"""), " ").trim()
        if (remaining.isEmpty()) return null

        // · 分割：左侧非 CJK
        val dotIndex = remaining.indexOf('·')
        if (dotIndex > 0) {
            val left = remaining.substring(0, dotIndex).trim()
            if (left.isNotEmpty() && !hasCJK(left) && left.length <= 30) {
                return cleanArtistName(left)
            }
        }

        // " - " 分割：左侧非 CJK 且不超过 5 个词
        val dashMatch = Regex("""^(.+?)\s*-\s*""").find(remaining)
        if (dashMatch != null) {
            val left = dashMatch.groupValues[1].trim()
            if (left.isNotEmpty() && !hasCJK(left) &&
                    left.split(Regex("""\s+""")).size <= 5) {
                return cleanArtistName(left)
            }
        }

        return null
    }

    /**
     * 从标题、日文原标题与下载目录名中可靠地提取作者名，供"按作者搜索相似画廊"使用。
     */
    @JvmStatic
    fun extractArtistName(title: String?, titleJpn: String?, downloadDirname: String?): String? {
        val candidates = ArrayList<String>()
        if (!title.isNullOrEmpty()) {
            candidates.add(title)
        }
        if (!titleJpn.isNullOrEmpty() && titleJpn != title) {
            candidates.add(titleJpn)
        }
        if (!downloadDirname.isNullOrEmpty()) {
            candidates.add(downloadDirname)
        }
        for (candidate in candidates) {
            val artist = extractArtistFromTitle(candidate)
            if (artist != null) {
                return artist
            }
        }
        return null
    }

    @JvmStatic
    fun handleThumbUrlResolution(url: String?): String? {
        if (null == url) {
            return null
        }

        val resolution: String?
        when (Settings.getThumbResolution()) {
            0 -> return url
            1 -> resolution = "250"
            2 -> resolution = "300"
            else -> return url
        }

        val index1 = url.lastIndexOf('_')
        val index2 = url.lastIndexOf('.')
        if (index1 >= 0 && index2 >= 0 && index1 < index2) {
            return url.substring(0, index1 + 1) + resolution + url.substring(index2)
        } else {
            return url
        }
    }
}
