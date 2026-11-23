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

package com.hippo.ehviewer.limit

import com.hippo.ehviewer.client.exception.Image509Exception
import okhttp3.Headers
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 纯文本/异常分类逻辑，不依赖 Android Context，便于单元测试。
 */
object LimitSignals {

    private val LIMIT_BODY_PHRASES = arrayOf(
        "kokomade",
        "これ以上",
        "temporarily banned",
        "ip address has been temporarily banned",
        "too many requests",
        "exceeds the limit",
        "bandwidth limit exceeded",
        "excessive page requests",
        "you are viewing too many pages",
        "image limit",
    )

    /**
     * Cloudflare 验证页特征。Cloudflare 与真正的站点限流/封禁要分开：
     * 验证页只代表需要浏览器验证，并不证明 IP 被站点限速。
     */
    private val CLOUDFLARE_PHRASES = arrayOf(
        "<title>just a moment",
        "just a moment...",
        "enable javascript and cookies to continue",
        "checking your browser before accessing",
        "cf-browser-verification",
        "cf-challenge",
        "cf_chl_",
        "attention required! | cloudflare",
        "cf-error-details",
        "cf-please-wait",
    )

    private val LIMIT_MESSAGE_PHRASES = LIMIT_BODY_PHRASES + arrayOf(
        "rate limit",
        "rate-limited",
        "request limit",
        "image limit",
        "temporarily unavailable due to",
    )

    private val DURATION_PATTERNS = arrayOf(
        Regex(
            "(?i)(?:try again|wait|retry|banned|limited|limit|blocked)[^.\\n]{0,80}?" +
                "(\\d+)\\s*(second|sec|minute|min|hour|hr|day)s?"
        ),
        Regex(
            "(?i)(\\d+)\\s*(second|sec|minute|min|hour|hr|day)s?\\s*" +
                "(?:later|left|remaining|until)"
        ),
    )

    /** 站点封禁纯文本所在的一句（按句号/换行切分）。 */
    private val BAN_SENTENCE_PATTERN = Regex(
        "(?i)[^.\\n]*(?:banned|ban expires|excessive request rate|rate limit)[^.\\n]*"
    )

    private val BAN_TIME_TOKEN_PATTERN = Regex(
        "(?i)(\\d+)\\s*(second|sec|minute|min|hour|hr|day)s?"
    )

    @JvmStatic
    fun isLimitCode(code: Int): Boolean = code == 429 || code == 509

    /**
     * 是否是 Cloudflare 人机验证/挑战页（而非站点限流）。
     */
    @JvmStatic
    fun isCloudflareChallenge(code: Int, headers: Headers?, body: String?): Boolean {
        if (headers?.get("cf-mitigated")?.equals("challenge", ignoreCase = true) == true) {
            return true
        }
        if (body.isNullOrEmpty()) return false
        val lower = body.lowercase(Locale.US)
        if (CLOUDFLARE_PHRASES.any { lower.contains(it) }) return true
        // 部分 Cloudflare 页面没有标题特征，但会带 server: cloudflare 和挑战脚本。
        val server = headers?.get("server")?.lowercase(Locale.US).orEmpty()
        return server.contains("cloudflare") &&
            (code == 403 || code == 503) &&
            (lower.contains("__cf_chl") || lower.contains("challenge-platform"))
    }

    @JvmStatic
    fun isLimitBody(body: String?): Boolean {
        if (body.isNullOrEmpty()) return false
        val lower = body.lowercase(Locale.US)
        return LIMIT_BODY_PHRASES.any { lower.contains(it) }
    }

    @JvmStatic
    fun isLimitMessage(message: String?): Boolean {
        if (message.isNullOrEmpty()) return false
        val lower = message.lowercase(Locale.US)
        return LIMIT_MESSAGE_PHRASES.any { lower.contains(it) } ||
            lower.contains("509") ||
            lower.contains("429") ||
            lower.contains("kokomade") ||
            lower.contains("sad panda")
    }

    @JvmStatic
    fun isLimitThrowable(t: Throwable?): Boolean {
        var current = t
        var depth = 0
        while (current != null && depth < 8) {
            if (current is Image509Exception) return true
            if (isLimitMessage(current.message)) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * 真正的网络断联/超时：没有服务器响应内容，不应判定为站点封禁。
     */
    @JvmStatic
    fun isNetworkDisconnect(t: Throwable?): Boolean {
        var current = t
        var depth = 0
        while (current != null && depth < 8) {
            if (current is UnknownHostException ||
                current is ConnectException ||
                current is SocketTimeoutException
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /** 解析 Retry-After：支持秒数和 HTTP 日期。返回毫秒，无法解析返回 0。 */
    @JvmStatic
    fun parseRetryAfter(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val trimmed = value.trim()
        trimmed.toLongOrNull()?.let { seconds ->
            if (seconds in 0..(7L * 24 * 60 * 60)) return seconds * 1000L
        }
        return try {
            val formats = arrayOf(
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, dd MMM yyyy HH:mm:ss Z",
            )
            for (pattern in formats) {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val date: Date = format.parse(trimmed) ?: continue
                val delay = date.time - System.currentTimeMillis()
                if (delay > 0) return delay
            }
            0L
        } catch (_: Throwable) {
            0L
        }
    }

    /** 从响应正文中解析“try again in 5 minutes”一类的时长。 */
    @JvmStatic
    fun parseDurationMs(body: String?): Long {
        if (body.isNullOrEmpty()) return 0L
        for (pattern in DURATION_PATTERNS) {
            val match = pattern.find(body) ?: continue
            val amount = match.groupValues.getOrNull(1)?.toLongOrNull() ?: continue
            val unit = match.groupValues.getOrNull(2)?.lowercase(Locale.US).orEmpty()
            val multiplier = unitMultiplier(unit)
            if (multiplier > 0) {
                return (amount * multiplier).coerceIn(60_000L, 7L * 24 * 60 * 60 * 1000)
            }
        }
        return 0L
    }

    /**
     * 解析站点纯文本封禁信息里的剩余时间，例如：
     * "The ban expires in 59 minutes and 13 seconds"。
     * 同一句里的多个时间片段会相加（1 hour and 30 minutes）。
     */
    @JvmStatic
    fun parseBanDurationMs(body: String?): Long {
        if (body.isNullOrEmpty()) return 0L
        // 封禁信息可能分成多句，逐句找带时间的那一句（例如第二句 "The ban expires in …"）。
        val sentences = BAN_SENTENCE_PATTERN.findAll(body).map { it.value }.toList()
        val candidates = if (sentences.isEmpty()) listOf(body) else sentences
        for (sentence in candidates) {
            var total = 0L
            var found = false
            for (match in BAN_TIME_TOKEN_PATTERN.findAll(sentence)) {
                val amount = match.groupValues.getOrNull(1)?.toLongOrNull() ?: continue
                val unit = match.groupValues.getOrNull(2)?.lowercase(Locale.US).orEmpty()
                val multiplier = unitMultiplier(unit)
                if (multiplier > 0) {
                    total += amount * multiplier
                    found = true
                }
            }
            if (found) {
                return total.coerceIn(60_000L, 7L * 24 * 60 * 60 * 1000)
            }
        }
        return 0L
    }

    /** 限流时长统一入口：先按封禁纯文本解析，再回退到普通“try again in …”。 */
    @JvmStatic
    fun parseLimitDurationMs(body: String?): Long {
        val ban = parseBanDurationMs(body)
        return if (ban > 0L) ban else parseDurationMs(body)
    }

    private fun unitMultiplier(unit: String): Long = when {
        unit.startsWith("sec") -> 1000L
        unit.startsWith("min") -> 60_000L
        unit.startsWith("hour") || unit.startsWith("hr") -> 3_600_000L
        unit.startsWith("day") -> 86_400_000L
        else -> 0L
    }

    /** 是否是典型的“连接层面”IO 异常（不代表站点已经响应）。 */
    @JvmStatic
    fun isConnectionIo(t: Throwable?): Boolean {
        var current = t
        var depth = 0
        while (current != null && depth < 8) {
            if (current is IOException) return true
            current = current.cause
            depth++
        }
        return false
    }
}
