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

package com.hippo.ehviewer.lab.pair

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 配对二维码内容编解码（协议 v3.0 §5.19.3 的本地扩展）
 *
 * 格式：
 * ```
 * ehviewer-pair://pair?v=1&host=192.168.1.5&port=8080&code=123456&name=Pixel
 * ```
 *
 * 与传输页的 `ehviewer-transfer://connect` 刻意分开，避免扫错二维码时
 * 误连传输服务。配对码本身只有 60 秒有效期，二维码需随配对码一起刷新。
 */
object PairingQrCodec {

    const val SCHEME = "ehviewer-pair"
    private const val AUTHORITY = "pair"
    private const val VERSION = "1"

    /**
     * 编码为二维码内容。
     */
    @JvmStatic
    fun encode(host: String, port: Int, code: String, name: String?): String {
        require(host.isNotBlank()) { "host is blank" }
        require(port in 1..65535) { "invalid port: $port" }
        require(isValidCode(code)) { "invalid pairing code" }
        val sb = StringBuilder()
        sb.append(SCHEME).append("://").append(AUTHORITY)
        sb.append("?v=").append(VERSION)
        sb.append("&host=").append(encodeParam(host.trim()))
        sb.append("&port=").append(port)
        sb.append("&code=").append(code)
        if (!name.isNullOrBlank()) {
            sb.append("&name=").append(encodeParam(name.trim()))
        }
        return sb.toString()
    }

    /**
     * 解码二维码内容；不是配对二维码或字段非法时返回 null。
     */
    @JvmStatic
    fun decode(content: String?): Params? {
        if (content.isNullOrBlank()) return null
        val uri = try {
            URI(content.trim())
        } catch (e: Exception) {
            return null
        }
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        val authority = uri.host ?: uri.authority
        if (!AUTHORITY.equals(authority, ignoreCase = true)) return null

        val query = parseQuery(uri.rawQuery) ?: return null
        val version = query["v"]
        if (version != null && version != VERSION) return null

        val host = query["host"]?.trim().orEmpty()
        if (host.isEmpty() || host.length > 253) return null

        val port = query["port"]?.trim()?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        val code = query["code"]?.trim().orEmpty()
        if (!isValidCode(code)) return null

        val name = query["name"]?.trim()?.takeIf { it.isNotEmpty() }?.take(64)
        return Params(host, port, code, name)
    }

    @JvmStatic
    fun isValidCode(code: String?): Boolean {
        if (code == null || code.length != 6) return false
        for (c in code) {
            if (c < '0' || c > '9') return false
        }
        return true
    }

    private fun encodeParam(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun parseQuery(rawQuery: String?): Map<String, String>? {
        if (rawQuery.isNullOrEmpty()) return null
        val map = HashMap<String, String>()
        for (part in rawQuery.split('&')) {
            if (part.isEmpty()) continue
            val index = part.indexOf('=')
            if (index <= 0) continue
            val key = decodeParam(part.substring(0, index)) ?: continue
            val value = decodeParam(part.substring(index + 1)) ?: continue
            if (key.isNotEmpty()) map[key] = value
        }
        return if (map.isEmpty()) null else map
    }

    private fun decodeParam(value: String): String? = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        null
    }

    /**
     * 二维码解析结果。
     */
    data class Params(
        val host: String,
        val port: Int,
        val code: String,
        val name: String?,
    )
}
