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

package com.hippo.ehviewer.lab.source

/**
 * 解析服务端响应头中的实验室图片源信息
 *
 * 协议对应：v3.0 §5.21 响应头：
 * <ul>
 *   <li>[X-Image-Source]: local | local:cache | lab:<deviceId> | remote</li>
 *   <li>[X-Lab-Source-Chain]: [local,local:cache,lab:uuid-bbb,remote]</li>
 *   <li>[X-Image-Latency-Ms]: 23</li>
 * </ul>
 */
object ImageSourceHeader {

    /**
     * 解析 [X-Image-Source] 头。
     */
    @JvmStatic
    fun parseKind(headerValue: String?): ImageSource.Kind {
        if (headerValue.isNullOrEmpty()) return ImageSource.Kind.UNKNOWN
        val v = headerValue.trim()
        return when {
            ImageSource.LOCAL == v -> ImageSource.Kind.LOCAL_FILE
            ImageSource.LOCAL_CACHE == v -> ImageSource.Kind.LOCAL_CACHE
            ImageSource.REMOTE == v -> ImageSource.Kind.REMOTE
            v.startsWith(ImageSource.LAN_PREFIX) -> ImageSource.Kind.LAN
            else -> ImageSource.Kind.UNKNOWN
        }
    }

    /**
     * 解析 [X-Image-Source] 中的 deviceId（仅 LAN 时有意义）。
     */
    @JvmStatic
    fun parseLanDeviceId(headerValue: String?): String? {
        if (headerValue == null) return null
        val v = headerValue.trim()
        if (!v.startsWith(ImageSource.LAN_PREFIX)) return null
        val id = v.substring(ImageSource.LAN_PREFIX.length)
        return id.ifEmpty { null }
    }

    /**
     * 解析 [X-Lab-Source-Chain] 头。返回字符串列表（不含中括号）。
     */
    @JvmStatic
    fun parseChain(headerValue: String?): List<String> {
        if (headerValue.isNullOrEmpty()) return emptyList()
        var v = headerValue.trim()
        if (v.startsWith("[")) v = v.substring(1)
        if (v.endsWith("]")) v = v.substring(0, v.length - 1)
        if (v.isEmpty()) return emptyList()
        return v.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 解析 [X-Image-Latency-Ms] 头。
     */
    @JvmStatic
    fun parseLatencyMs(headerValue: String?): Long {
        if (headerValue.isNullOrEmpty()) return -1
        return try {
            headerValue.trim().toLong()
        } catch (e: NumberFormatException) {
            -1
        }
    }
}