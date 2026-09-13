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

/**
 * 实验室配置
 *
 * 对应 v3.0 协议 §5.19.2。
 *
 * 不可变快照：每次更新都返回新对象，便于跨线程共享。
 */
class LabConfig private constructor(builder: Builder) {

    val enabled: Boolean = builder.enabled

    @JvmField val role: String = builder.role

    @JvmField val autoRelay: AutoRelay = builder.autoRelay

    @JvmField val imageSourcePriority: List<String> = ArrayList(builder.imageSourcePriority)

    @JvmField val incrementalResume: IncrementalResume = builder.incrementalResume

    @JvmField val dbCache: DbCache = builder.dbCache

    @JvmField val trustAuthMode: String = builder.trustAuthMode

    @JvmField val subSwitches: SubSwitches = builder.subSwitches

    fun isEnabled(): Boolean = enabled

    fun getRole(): String = role

    fun getAutoRelay(): AutoRelay = autoRelay

    fun getImageSourcePriority(): List<String> = imageSourcePriority

    fun getIncrementalResume(): IncrementalResume = incrementalResume

    fun getDbCache(): DbCache = dbCache

    fun getTrustAuthMode(): String = trustAuthMode

    fun getSubSwitches(): SubSwitches = subSwitches

    fun isSubEnabled(key: String?): Boolean {
        if (subSwitches == null) return false
        return when (key) {
            "autoRelay" -> subSwitches.autoRelay
            "crossDeviceImage" -> subSwitches.crossDeviceImage
            "dbSnapshot" -> subSwitches.dbSnapshot
            "incrementalResume" -> subSwitches.incrementalResume
            else -> false
        }
    }

    fun toBuilder(): Builder = Builder().also {
        it.enabled(enabled)
        it.role(role)
        it.autoRelay(autoRelay)
        it.imageSourcePriority(ArrayList(imageSourcePriority))
        it.incrementalResume(incrementalResume)
        it.dbCache(dbCache)
        it.trustAuthMode(trustAuthMode)
        it.subSwitches(subSwitches)
    }

    /**
     * 序列化为 JSON（与协议 §5.19.2 响应格式一致）。
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json["enabled"] = enabled
        json["role"] = role

        val ar = JSONObject()
        ar["speedThresholdBps"] = autoRelay.speedThresholdBps
        ar["consecutiveSeconds"] = autoRelay.consecutiveSeconds
        ar["errorRateThreshold"] = autoRelay.errorRateThreshold
        ar["minElapsedSeconds"] = autoRelay.minElapsedSeconds
        ar["maxRetries"] = autoRelay.maxRetries
        json["autoRelay"] = ar

        val sources = JSONArray()
        sources.addAll(imageSourcePriority)
        json["imageSourcePriority"] = sources

        val ir = JSONObject()
        ir["enabled"] = incrementalResume.enabled
        ir["strategy"] = incrementalResume.strategy
        json["incrementalResume"] = ir

        val db = JSONObject()
        db["enabled"] = dbCache.enabled
        db["ttlSeconds"] = dbCache.ttlSeconds
        db["includeThumb"] = dbCache.includeThumb
        json["dbCache"] = db

        json["trustAuthMode"] = trustAuthMode

        val sub = JSONObject()
        sub["autoRelay"] = subSwitches.autoRelay
        sub["crossDeviceImage"] = subSwitches.crossDeviceImage
        sub["dbSnapshot"] = subSwitches.dbSnapshot
        sub["incrementalResume"] = subSwitches.incrementalResume
        json["subSwitches"] = sub

        return json
    }

    companion object {
        const val ROLE_AUTO = "auto"
        const val ROLE_MASTER = "master"
        const val ROLE_SLAVE = "slave"

        const val STRATEGY_UNION = "union"
        const val STRATEGY_PEER_ONLY = "peer_only"
        const val STRATEGY_LOCAL_ONLY = "local_only"

        const val TRUST_MODE_NONE = "none"
        const val TRUST_MODE_PAIRING_CODE = "pairing_code"
        const val TRUST_MODE_PASSWORD = "password"

        const val SOURCE_LOCAL = "local"
        const val SOURCE_LAN = "lan"
        const val SOURCE_REMOTE_PROXY = "remote_proxy"

        @JvmStatic
        fun defaultConfig(): LabConfig = Builder().build()

        /**
         * 从 JSON 反序列化（PUT /api/v1/lab/config 的请求体可能只含部分字段）。
         */
        @JvmStatic
        fun fromJson(json: JSONObject?): LabConfig {
            if (json == null) return defaultConfig()
            val current = defaultConfig().toBuilder()
            if (json.containsKey("enabled")) current.enabled(json.getBooleanValue("enabled"))
            if (json.containsKey("role")) current.role(json.getString("role"))

            if (json.containsKey("autoRelay")) {
                val ar = json.getJSONObject("autoRelay")
                if (ar != null) {
                    val arb = AutoRelay.Builder()
                    if (ar.containsKey("speedThresholdBps"))
                        arb.speedThresholdBps(ar.getLongValue("speedThresholdBps"))
                    if (ar.containsKey("consecutiveSeconds"))
                        arb.consecutiveSeconds(ar.getIntValue("consecutiveSeconds"))
                    if (ar.containsKey("errorRateThreshold"))
                        arb.errorRateThreshold(ar.getDoubleValue("errorRateThreshold"))
                    if (ar.containsKey("minElapsedSeconds"))
                        arb.minElapsedSeconds(ar.getIntValue("minElapsedSeconds"))
                    if (ar.containsKey("maxRetries"))
                        arb.maxRetries(ar.getIntValue("maxRetries"))
                    current.autoRelay(arb.build())
                }
            }

            if (json.containsKey("imageSourcePriority")) {
                val arr = json.getJSONArray("imageSourcePriority")
                if (arr != null) {
                    val list = ArrayList<String>()
                    for (i in 0 until arr.size) list.add(arr.getString(i))
                    current.imageSourcePriority(list)
                }
            }

            if (json.containsKey("incrementalResume")) {
                val ir = json.getJSONObject("incrementalResume")
                if (ir != null) {
                    val irb = IncrementalResume.Builder()
                    if (ir.containsKey("enabled")) irb.enabled(ir.getBooleanValue("enabled"))
                    if (ir.containsKey("strategy")) irb.strategy(ir.getString("strategy"))
                    current.incrementalResume(irb.build())
                }
            }

            if (json.containsKey("dbCache")) {
                val db = json.getJSONObject("dbCache")
                if (db != null) {
                    val dbb = DbCache.Builder()
                    if (db.containsKey("enabled")) dbb.enabled(db.getBooleanValue("enabled"))
                    if (db.containsKey("ttlSeconds")) dbb.ttlSeconds(db.getLongValue("ttlSeconds"))
                    if (db.containsKey("includeThumb")) dbb.includeThumb(db.getBooleanValue("includeThumb"))
                    current.dbCache(dbb.build())
                }
            }

            if (json.containsKey("trustAuthMode")) current.trustAuthMode(json.getString("trustAuthMode"))

            if (json.containsKey("subSwitches")) {
                val sub = json.getJSONObject("subSwitches")
                if (sub != null) {
                    val sb = SubSwitches.Builder()
                    if (sub.containsKey("autoRelay")) sb.autoRelay(sub.getBooleanValue("autoRelay"))
                    if (sub.containsKey("crossDeviceImage"))
                        sb.crossDeviceImage(sub.getBooleanValue("crossDeviceImage"))
                    if (sub.containsKey("dbSnapshot")) sb.dbSnapshot(sub.getBooleanValue("dbSnapshot"))
                    if (sub.containsKey("incrementalResume"))
                        sb.incrementalResume(sub.getBooleanValue("incrementalResume"))
                    current.subSwitches(sb.build())
                }
            }

            return current.build()
        }
    }

    // ==================== 内部数据类 ====================

    class AutoRelay private constructor(builder: Builder) {
        @JvmField val speedThresholdBps: Long = builder.speedThresholdBps
        @JvmField val consecutiveSeconds: Int = builder.consecutiveSeconds
        @JvmField val errorRateThreshold: Double = builder.errorRateThreshold
        @JvmField val minElapsedSeconds: Int = builder.minElapsedSeconds
        @JvmField val maxRetries: Int = builder.maxRetries

        fun getSpeedThresholdBps(): Long = speedThresholdBps
        fun getConsecutiveSeconds(): Int = consecutiveSeconds
        fun getErrorRateThreshold(): Double = errorRateThreshold
        fun getMinElapsedSeconds(): Int = minElapsedSeconds
        fun getMaxRetries(): Int = maxRetries

        class Builder {
            var speedThresholdBps: Long = 51200
                private set
            var consecutiveSeconds: Int = 30
                private set
            var errorRateThreshold: Double = 0.5
                private set
            var minElapsedSeconds: Int = 60
                private set
            var maxRetries: Int = 5
                private set

            fun speedThresholdBps(v: Long) = apply { speedThresholdBps = v }
            fun consecutiveSeconds(v: Int) = apply { consecutiveSeconds = v }
            fun errorRateThreshold(v: Double) = apply { errorRateThreshold = v }
            fun minElapsedSeconds(v: Int) = apply { minElapsedSeconds = v }
            fun maxRetries(v: Int) = apply { maxRetries = v }

            fun build(): AutoRelay = AutoRelay(this)
        }
    }

    class IncrementalResume private constructor(builder: Builder) {
        val enabled: Boolean = builder.enabled
        @JvmField val strategy: String = builder.strategy

        fun isEnabled(): Boolean = enabled
        fun getStrategy(): String = strategy

        class Builder {
            var enabled: Boolean = true
                private set
            var strategy: String = STRATEGY_UNION
                private set

            fun enabled(v: Boolean) = apply { enabled = v }
            fun strategy(v: String) = apply { strategy = v }

            fun build(): IncrementalResume = IncrementalResume(this)
        }
    }

    class DbCache private constructor(builder: Builder) {
        val enabled: Boolean = builder.enabled
        @JvmField val ttlSeconds: Long = builder.ttlSeconds
        val includeThumb: Boolean = builder.includeThumb

        fun isEnabled(): Boolean = enabled
        fun getTtlSeconds(): Long = ttlSeconds
        fun isIncludeThumb(): Boolean = includeThumb

        class Builder {
            var enabled: Boolean = true
                private set
            var ttlSeconds: Long = 86400
                private set
            var includeThumb: Boolean = true
                private set

            fun enabled(v: Boolean) = apply { enabled = v }
            fun ttlSeconds(v: Long) = apply { ttlSeconds = v }
            fun includeThumb(v: Boolean) = apply { includeThumb = v }

            fun build(): DbCache = DbCache(this)
        }
    }

    class SubSwitches private constructor(builder: Builder) {
        val autoRelay: Boolean = builder.autoRelay
        val crossDeviceImage: Boolean = builder.crossDeviceImage
        val dbSnapshot: Boolean = builder.dbSnapshot
        val incrementalResume: Boolean = builder.incrementalResume

        fun isAutoRelay(): Boolean = autoRelay
        fun isCrossDeviceImage(): Boolean = crossDeviceImage
        fun isDbSnapshot(): Boolean = dbSnapshot
        fun isIncrementalResume(): Boolean = incrementalResume

        class Builder {
            var autoRelay: Boolean = true
                private set
            var crossDeviceImage: Boolean = true
                private set
            var dbSnapshot: Boolean = true
                private set
            var incrementalResume: Boolean = true
                private set

            fun autoRelay(v: Boolean) = apply { autoRelay = v }
            fun crossDeviceImage(v: Boolean) = apply { crossDeviceImage = v }
            fun dbSnapshot(v: Boolean) = apply { dbSnapshot = v }
            fun incrementalResume(v: Boolean) = apply { incrementalResume = v }

            fun build(): SubSwitches = SubSwitches(this)
        }
    }

    class Builder {
        var enabled: Boolean = false
            private set
        var role: String = ROLE_AUTO
            private set
        var autoRelay: AutoRelay = AutoRelay.Builder().build()
            private set
        var imageSourcePriority: List<String> =
            ArrayList(listOf(SOURCE_LOCAL, SOURCE_LAN, SOURCE_REMOTE_PROXY))
            private set
        var incrementalResume: IncrementalResume = IncrementalResume.Builder().build()
            private set
        var dbCache: DbCache = DbCache.Builder().build()
            private set
        var trustAuthMode: String = TRUST_MODE_PAIRING_CODE
            private set
        var subSwitches: SubSwitches = SubSwitches.Builder().build()
            private set

        fun enabled(v: Boolean) = apply { enabled = v }
        fun role(v: String) = apply { role = v }
        fun autoRelay(v: AutoRelay) = apply { autoRelay = v }
        fun imageSourcePriority(v: List<String>) = apply { imageSourcePriority = ArrayList(v) }
        fun incrementalResume(v: IncrementalResume) = apply { incrementalResume = v }
        fun dbCache(v: DbCache) = apply { dbCache = v }
        fun trustAuthMode(v: String) = apply { trustAuthMode = v }
        fun subSwitches(v: SubSwitches) = apply { subSwitches = v }

        fun build(): LabConfig = LabConfig(this)
    }
}