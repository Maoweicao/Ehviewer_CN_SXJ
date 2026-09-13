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

import androidx.annotation.NonNull
import androidx.annotation.Nullable

/**
 * 图片来源标记
 *
 * 协议对应：v3.0 §5.21 响应头 [X-Image-Source]。
 * UI 用此值在画廊查看器页标注「来自本机/局域网PC-客厅/源站」。
 */
class ImageSource private constructor(builder: Builder) {

    @NonNull val kind: Kind = builder.kind
    /** 仅 LAN 时有值：来源 deviceId */
    @Nullable val deviceId: String? = builder.deviceId
    /** 仅 LAN 时有值：来源设备展示名 */
    @Nullable val deviceName: String? = builder.deviceName
    /** 命中耗时（毫秒）。-1 表示未知。 */
    val latencyMs: Long = builder.latencyMs
    /** 本次解析实际尝试的来源链路，便于调试。 */
    @NonNull val triedChain: List<String> = ArrayList(builder.triedChain)

    /**
     * 序列化为 wire 字符串（与 [X-Image-Source] 一致）。
     */
    @NonNull
    fun toHeader(): String = when (kind) {
        Kind.LOCAL_FILE -> LOCAL
        Kind.LOCAL_CACHE -> LOCAL_CACHE
        Kind.LAN -> if (deviceId != null) "$LAN_PREFIX$deviceId" else "$LAN_PREFIX" + "unknown"
        Kind.REMOTE -> REMOTE
        Kind.UNKNOWN -> "unknown"
    }

    /**
     * 序列化为 [X-Lab-Source-Chain] 头值。
     */
    @NonNull
    fun chainHeader(): String = "[" + triedChain.joinToString(",") + "]"

    enum class Kind {
        LOCAL_FILE,    // 本机下载目录命中
        LOCAL_CACHE,   // 本机 SimpleDiskCache 命中
        LAN,           // 局域网设备命中
        REMOTE,        // 远端代理（源站）
        UNKNOWN
    }

    companion object {
        const val LOCAL = "local"
        const val LOCAL_CACHE = "local:cache"
        const val LAN_PREFIX = "lab:"     // lab:<deviceId>
        const val REMOTE = "remote"       // e-hentai proxy
    }

    class Builder {
        var kind: Kind = Kind.UNKNOWN
            private set
        var deviceId: String? = null
            private set
        var deviceName: String? = null
            private set
        var latencyMs: Long = -1
            private set
        var triedChain: List<String> = ArrayList()
            private set

        fun kind(v: Kind) = apply { kind = v }
        fun deviceId(v: String?) = apply { deviceId = v }
        fun deviceName(v: String?) = apply { deviceName = v }
        fun latencyMs(v: Long) = apply { latencyMs = v }
        fun triedChain(v: List<String>) = apply { triedChain = ArrayList(v) }

        fun build(): ImageSource = ImageSource(this)
    }
}