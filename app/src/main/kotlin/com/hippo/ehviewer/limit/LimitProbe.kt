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

import com.hippo.ehviewer.client.EhUrl
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * 限流后的辅助探测：公网 IP 与论坛页内容。
 *
 * 使用独立的纯 HTTP 请求，不经过 EhEngine，避免再次触发自身的限流判定。
 */
object LimitProbe {

    private const val CLOUDFLARE_TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val IPIFY_URL = "https://api.ipify.org"
    private const val IFCONFIG_URL = "https://ifconfig.me/ip"
    private const val MAX_STORED_CONTENT = 500

    private val IP_ENDPOINTS = arrayOf(CLOUDFLARE_TRACE_URL, IPIFY_URL, IFCONFIG_URL)
    private val IP_VALUE_PATTERN = Regex("^[0-9a-fA-F:.]{3,64}$")
    private val CLOUDFLARE_IP_PATTERN = Regex("(?m)^ip=([0-9a-fA-F:.]+)\\s*$")
    private val TITLE_PATTERN = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
    private val SCRIPT_PATTERN = Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
    private val STYLE_PATTERN = Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE)
    private val TAG_PATTERN = Regex("<[^>]+>")
    private val WHITESPACE_PATTERN = Regex("\\s+")

    data class IpResult(val ip: String?, val error: String?)

    data class ForumResult(
        val code: Int,
        val cloudflare: Boolean,
        val banned: Boolean,
        val content: String?,
        val durationMs: Long,
        val error: String?,
        val targetPage: Boolean = true,
    )

    @JvmStatic
    fun fetchPublicIp(client: OkHttpClient): IpResult {
        var lastError: String? = null
        for (url in IP_ENDPOINTS) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "text/plain, */*")
                    .build()
                client.newCall(request).execute().use { response ->
                    val ip = parsePublicIp(response.body()?.string())
                    if (response.isSuccessful && ip != null) {
                        return IpResult(ip, null)
                    }
                    lastError = "HTTP ${response.code()} from $url"
                }
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
            }
        }
        return IpResult(null, lastError)
    }

    @JvmStatic
    fun fetchForum(client: OkHttpClient): ForumResult {
        return try {
            val request = Request.Builder()
                .url(EhUrl.URL_FORUMS)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body()?.string()
                parseForumResult(response.code(), response.headers(), body)
            }
        } catch (t: Throwable) {
            ForumResult(
                code = 0,
                cloudflare = false,
                banned = false,
                content = null,
                durationMs = 0L,
                error = "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    /**
     * 限流判定的权威依据：EH 设置页（uconfig.php）。
     *
     * 该页面被限流时会如实返回纯文本封禁信息，例如
     * "This IP address has been temporarily banned ... The ban expires in 59 minutes and 13 seconds"，
     * 正常时返回设置页面本身，因此比“我的标签”更适合判断限流真伪。
     */
    @JvmStatic
    fun fetchEhSettings(client: OkHttpClient): ForumResult {
        return try {
            val request = Request.Builder()
                .url(EhUrl.getUConfigUrl())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body()?.string()
                val result = parseForumResult(response.code(), response.headers(), body)
                result.copy(
                    targetPage = response.isSuccessful && isEhSettingsPage(body),
                )
            }
        } catch (t: Throwable) {
            ForumResult(
                code = 0,
                cloudflare = false,
                banned = false,
                content = null,
                durationMs = 0L,
                error = "${t.javaClass.simpleName}: ${t.message}",
                targetPage = false,
            )
        }
    }

    /** 只有确实拿到 EH 设置页才算正常，登录页/跳转页不算。 */
    @JvmStatic
    fun isEhSettingsPage(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val lower = body.lowercase(Locale.US)
        if (lower.contains("act=login")) return false
        if (LimitSignals.isLimitBody(body)) return false
        return lower.contains("uconfig") || lower.contains("eh-settings") ||
            lower.contains("e-hentai galleries - settings") ||
            lower.contains("e-hentai galleries - my settings")
    }

    /** 解析 Cloudflare trace 的 `ip=` 行，或 ipify/ifconfig 的纯文本 IP。 */
    @JvmStatic
    fun parsePublicIp(body: String?): String? {
        if (body.isNullOrBlank()) return null
        CLOUDFLARE_IP_PATTERN.find(body)?.groupValues?.getOrNull(1)?.let { candidate ->
            if (isValidIp(candidate)) return candidate
        }
        val firstLine = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return firstLine.takeIf { isValidIp(it) }
    }

    @JvmStatic
    fun parseForumResult(code: Int, body: String?): ForumResult =
        parseForumResult(code, null, body)

    @JvmStatic
    fun parseForumResult(code: Int, headers: Headers?, body: String?): ForumResult {
        val cloudflare = LimitSignals.isCloudflareChallenge(code, headers, body)
        val banned = !cloudflare &&
            (LimitSignals.isLimitCode(code) || LimitSignals.isLimitBody(body))
        return ForumResult(
            code = code,
            cloudflare = cloudflare,
            banned = banned,
            content = normalizeContent(body),
            durationMs = if (cloudflare) 0L else LimitSignals.parseLimitDurationMs(body),
            error = null,
        )
    }

    /**
     * 论坛首页内容转成便于展示的一行文本：优先保留 `<title>`，再去掉脚本/样式/标签。
     */
    @JvmStatic
    fun normalizeContent(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val title = TITLE_PATTERN.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val text = body
            .replace(SCRIPT_PATTERN, " ")
            .replace(STYLE_PATTERN, " ")
            .replace(TAG_PATTERN, " ")
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
        return listOfNotNull(title, text.takeIf { it.isNotEmpty() })
            .joinToString(" | ")
            .take(MAX_STORED_CONTENT)
            .ifBlank { null }
    }

    private fun isValidIp(value: String): Boolean {
        if (!IP_VALUE_PATTERN.matches(value)) return false
        if (value.contains(':')) return true // IPv6，交给网络层校验
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val number = part.toIntOrNull() ?: return false
            number in 0..255
        }
    }
}
