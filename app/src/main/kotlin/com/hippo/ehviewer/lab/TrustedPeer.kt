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

package com.hippo.ehviewer.lab

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.util.Collections

/**
 * 信任设备（TrustedPeer）数据模型
 *
 * 对应 v3.0 协议 §5.19.1 中描述的信任设备列表条目。
 *
 * 字段约定：
 * <ul>
 *   <li>[deviceId] 作为主键，全局唯一</li>
 *   <li>[presharedKey] 仅在配对后写入本地，<b>绝不能</b>序列化到网络响应或日志</li>
 *   <li>[capabilities] 是设备声明的能力子集（[gallery_list] / [page_read] / [download] / [relay] / [snapshot] / [thumbnail]）</li>
 *   <li>[role] 与本机 LabConfig.role 协调；存储的是对端的角色，本机角色由本机配置决定</li>
 * </ul>
 */
class TrustedPeer private constructor(builder: Builder) {

    val deviceId: String = builder.deviceId
    val deviceName: String = builder.deviceName
    val deviceType: String = builder.deviceType

    @Volatile var host: String? = builder.host
        private set
    @Volatile var port: Int = builder.port
        private set
    @Volatile var trustedAt: Long = builder.trustedAt
        private set
    @Volatile var rttMs: Long = builder.rttMs
        private set
    @Volatile var pubkeyHint: String? = builder.pubkeyHint
        private set
    @Volatile var presharedKey: String? = builder.presharedKey
        private set
    @Volatile var role: String = builder.role
        private set

    @Volatile
    var capabilities: List<String> = builder.capabilities
        private set

    @Volatile
    var online: Boolean = builder.online
        private set

    @Volatile
    var lastSeen: Long = builder.lastSeen
        private set

    /**
     * 更新 [lastSeen] 时自动标记 [online]=true。
     */
    fun touchLastSeen(lastSeen: Long) {
        this.lastSeen = lastSeen
        this.online = true
    }

    fun setHost(host: String?) { this.host = host }
    fun setPort(port: Int) { this.port = port }
    fun setTrustedAt(trustedAt: Long) { this.trustedAt = trustedAt }

    /**
     * 同时更新 [lastSeen] 与 [online]=true。Java 调用方使用此名以便迁移兼容。
     */
    fun setLastSeen(lastSeen: Long) {
        this.lastSeen = lastSeen
        this.online = true
    }

    fun touchLastSeen() {
        setLastSeen(System.currentTimeMillis())
    }
    fun setOnline(online: Boolean) { this.online = online }
    fun setCapabilities(capabilities: List<String>) {
        this.capabilities = ArrayList(capabilities)
    }
    fun setRole(role: String) { this.role = role }
    fun setPubkeyHint(hint: String?) { this.pubkeyHint = hint }
    fun setPresharedKey(key: String?) { this.presharedKey = key }
    fun setRttMs(rttMs: Long) { this.rttMs = rttMs }

    fun hasCapability(cap: String?): Boolean = capabilities.contains(cap)

    /**
     * 序列化为 JSON（对应协议 §5.19.1.1 响应中的 peer 条目）。
     * <b>注意</b>：[presharedKey] 不参与序列化。
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json["deviceId"] = deviceId
        json["deviceName"] = deviceName
        json["deviceType"] = deviceType
        if (host != null) json["host"] = host
        json["port"] = port
        json["trustedAt"] = trustedAt
        json["lastSeen"] = lastSeen
        json["online"] = online
        val caps = JSONArray()
        caps.addAll(capabilities)
        json["capabilities"] = caps
        json["role"] = role
        if (pubkeyHint != null) json["pubkeyHint"] = pubkeyHint
        return json
    }

    companion object {
        const val CAP_GALLERY_LIST = "gallery_list"
        const val CAP_PAGE_READ = "page_read"
        const val CAP_DOWNLOAD = "download"
        const val CAP_RELAY = "relay"
        const val CAP_SNAPSHOT = "snapshot"
        const val CAP_THUMBNAIL = "thumbnail"

        @JvmField
        val ALL_CAPABILITIES: List<String> = Collections.unmodifiableList(
            listOf(CAP_GALLERY_LIST, CAP_PAGE_READ, CAP_DOWNLOAD, CAP_RELAY, CAP_SNAPSHOT, CAP_THUMBNAIL)
        )

        @JvmStatic
        fun fromJson(json: JSONObject?): TrustedPeer? {
            if (json == null) return null
            return try {
                Builder(
                    json.getString("deviceId"),
                    json.getString("deviceName"),
                    json.getString("deviceType"),
                ).apply {
                    if (json.containsKey("host")) host(json.getString("host"))
                    if (json.containsKey("port")) port(json.getIntValue("port"))
                    if (json.containsKey("trustedAt")) trustedAt(json.getLongValue("trustedAt"))
                    if (json.containsKey("lastSeen")) lastSeen(json.getLongValue("lastSeen"))
                    if (json.containsKey("online")) online(json.getBooleanValue("online"))
                    val caps = json.getJSONArray("capabilities")
                    if (caps != null) {
                        val list = ArrayList<String>()
                        for (i in 0 until caps.size) list.add(caps.getString(i))
                        capabilities(list)
                    }
                    if (json.containsKey("role")) role(json.getString("role"))
                    if (json.containsKey("pubkeyHint")) pubkeyHint(json.getString("pubkeyHint"))
                }.build()
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 用于本地持久化的 JSON（包含 presharedKey）。<b>绝不</b>用于网络响应。
         */
        @JvmStatic
        fun toLocalJson(peer: TrustedPeer): JSONObject {
            val json = peer.toJson()
            if (peer.presharedKey != null) json["presharedKey"] = peer.presharedKey
            json["rttMs"] = peer.rttMs
            return json
        }

        @JvmStatic
        fun fromLocalJson(json: JSONObject?): TrustedPeer? {
            val peer = fromJson(json) ?: return null
            if (json != null && json.containsKey("presharedKey")) {
                peer.setPresharedKey(json.getString("presharedKey"))
            }
            if (json != null && json.containsKey("rttMs")) {
                peer.setRttMs(json.getLongValue("rttMs"))
            }
            return peer
        }
    }

    /**
     * Builder
     */
    class Builder(
        val deviceId: String,
        val deviceName: String,
        val deviceType: String,
    ) {
        var host: String? = null
            private set
        var port: Int = 0
            private set
        var trustedAt: Long = System.currentTimeMillis()
            private set
        var lastSeen: Long = 0
            private set
        var online: Boolean = false
            private set
        var capabilities: List<String> = ArrayList()
            private set
        var role: String = LabConfig.ROLE_AUTO
            private set
        var pubkeyHint: String? = null
            private set
        var presharedKey: String? = null
            private set
        var rttMs: Long = -1
            private set

        fun host(v: String?) = apply { this.host = v }
        fun port(v: Int) = apply { this.port = v }
        fun trustedAt(v: Long) = apply { this.trustedAt = v }
        fun lastSeen(v: Long) = apply { this.lastSeen = v }
        fun online(v: Boolean) = apply { this.online = v }
        fun capabilities(v: List<String>?) = apply {
            this.capabilities = v?.let { ArrayList(it) } ?: ArrayList()
        }
        fun role(v: String?) = apply { this.role = v ?: LabConfig.ROLE_AUTO }
        fun pubkeyHint(v: String?) = apply { this.pubkeyHint = v }
        fun presharedKey(v: String?) = apply { this.presharedKey = v }
        fun rttMs(v: Long) = apply { this.rttMs = v }

        fun build(): TrustedPeer = TrustedPeer(this)
    }
}