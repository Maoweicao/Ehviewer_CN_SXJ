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

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import com.hippo.ehviewer.transfer.log.TransferLogger

/**
 * 实验室配置持久化与运行时订阅
 *
 * 基于 SharedPreferences 存储，键名加 [PREF_PREFIX] 前缀避免污染。
 *
 * 读时从 SP 加载，[get] 命中内存缓存避免重复解析。
 * 写时双写（内存 + SP），并通过监听器通知 UI（主线程）。
 *
 * 协议对应：v3.0 §5.19.2。
 */
class LabConfigStore private constructor(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var cached: LabConfig? = null

    private val lock = Any()

    private val listeners = mutableListOf<Listener>()

    /**
     * 读取当前配置。命中内存缓存；首次访问时从 SP 加载。
     */
    @NonNull
    fun get(): LabConfig {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: load().also { cached = it }
        }
    }

    /**
     * 更新配置（写入 SP + 内存 + 通知监听器）。
     */
    @NonNull
    fun update(updater: Updater): LabConfig {
        val (oldCfg, newCfg) = synchronized(lock) {
            val oldCfg = get()
            val built = updater.apply(oldCfg) ?: return oldCfg
            cached = built
            save(built)
            oldCfg to built
        }
        TransferLogger.getInstance().d(
            "LabConfigStore",
            "Config updated: enabled=${newCfg.isEnabled()}, role=${newCfg.getRole()}, sources=${newCfg.getImageSourcePriority()}",
        )
        mainHandler.post {
            synchronized(listeners) {
                listeners.toList().forEach { it.onLabConfigChanged(oldCfg, newCfg) }
            }
        }
        return newCfg
    }

    /**
     * 直接覆盖（外部已构造完整的 LabConfig）。
     */
    @NonNull
    fun replace(newCfg: LabConfig): LabConfig = update { newCfg }

    /**
     * 强制清空内存缓存，下次 get() 将从 SP 重新加载。
     * 用于：跨进程收到广播，外部数据源更新了 SP。
     */
    fun invalidate() {
        synchronized(lock) { cached = null }
    }

    fun addListener(l: Listener) {
        if (l != null) synchronized(listeners) { if (!listeners.contains(l)) listeners.add(l) }
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    // ==================== 内部 ====================

    private fun load(): LabConfig {
        return try {
            val storedVersion = prefs.getInt(KEY_CONFIG_VERSION, 0)
            if (storedVersion < CONFIG_VERSION) {
                TransferLogger.getInstance().i(
                    "LabConfigStore",
                    "Migrating lab config schema from $storedVersion to $CONFIG_VERSION",
                )
                prefs.edit().putInt(KEY_CONFIG_VERSION, CONFIG_VERSION).apply()
            }
            LabConfig.defaultConfig().toBuilder().apply {
                enabled(prefs.getBoolean(KEY_ENABLED, false))
                role(prefs.getString(KEY_ROLE, LabConfig.ROLE_AUTO)!!)

                autoRelay(LabConfig.AutoRelay.Builder().apply {
                    speedThresholdBps(prefs.getLong(KEY_AUTO_RELAY_SPEED, 51200L))
                    consecutiveSeconds(prefs.getInt(KEY_AUTO_RELAY_CONSECUTIVE, 30))
                    errorRateThreshold(java.lang.Double.longBitsToDouble(
                        prefs.getLong(KEY_AUTO_RELAY_ERROR_RATE,
                            java.lang.Double.doubleToLongBits(0.5))))
                    minElapsedSeconds(prefs.getInt(KEY_AUTO_RELAY_MIN_ELAPSED, 60))
                    maxRetries(prefs.getInt(KEY_AUTO_RELAY_MAX_RETRIES, 5))
                }.build())

                val sourcesCsv = prefs.getString(
                    KEY_IMAGE_SOURCE_PRIORITY,
                    "${LabConfig.SOURCE_LOCAL},${LabConfig.SOURCE_LAN},${LabConfig.SOURCE_REMOTE_PROXY}",
                )!!
                val sources = sourcesCsv.split(",")
                    .filter { it.isNotEmpty() }
                    .map { it.trim() }
                    .toMutableList()
                if (sources.isEmpty()) {
                    sources.add(LabConfig.SOURCE_LOCAL)
                    sources.add(LabConfig.SOURCE_LAN)
                    sources.add(LabConfig.SOURCE_REMOTE_PROXY)
                }
                imageSourcePriority(sources)

                incrementalResume(LabConfig.IncrementalResume.Builder().apply {
                    enabled(prefs.getBoolean(KEY_INCREMENTAL_RESUME_ENABLED, true))
                    strategy(prefs.getString(
                        KEY_INCREMENTAL_RESUME_STRATEGY,
                        LabConfig.STRATEGY_UNION)!!)
                }.build())

                dbCache(LabConfig.DbCache.Builder().apply {
                    enabled(prefs.getBoolean(KEY_DB_CACHE_ENABLED, true))
                    ttlSeconds(prefs.getLong(KEY_DB_CACHE_TTL, 86400L))
                    includeThumb(prefs.getBoolean(KEY_DB_CACHE_INCLUDE_THUMB, true))
                }.build())

                trustAuthMode(prefs.getString(
                    KEY_TRUST_AUTH_MODE,
                    LabConfig.TRUST_MODE_PAIRING_CODE)!!)

                subSwitches(LabConfig.SubSwitches.Builder().apply {
                    autoRelay(prefs.getBoolean(KEY_SUB_AUTO_RELAY, true))
                    crossDeviceImage(prefs.getBoolean(KEY_SUB_CROSS_DEVICE_IMAGE, true))
                    dbSnapshot(prefs.getBoolean(KEY_SUB_DB_SNAPSHOT, true))
                    incrementalResume(prefs.getBoolean(KEY_SUB_INCREMENTAL_RESUME, true))
                }.build())
            }.build()
        } catch (e: Exception) {
            TransferLogger.getInstance().e("LabConfigStore", "Failed to load config; using defaults", e)
            LabConfig.defaultConfig()
        }
    }

    private fun save(cfg: LabConfig) {
        val e = prefs.edit()
        e.putInt(KEY_CONFIG_VERSION, CONFIG_VERSION)
        e.putBoolean(KEY_ENABLED, cfg.isEnabled())
        e.putString(KEY_ROLE, cfg.getRole())

        val ar = cfg.getAutoRelay()
        e.putLong(KEY_AUTO_RELAY_SPEED, ar.getSpeedThresholdBps())
        e.putInt(KEY_AUTO_RELAY_CONSECUTIVE, ar.getConsecutiveSeconds())
        e.putLong(KEY_AUTO_RELAY_ERROR_RATE,
            java.lang.Double.doubleToLongBits(ar.getErrorRateThreshold()))
        e.putInt(KEY_AUTO_RELAY_MIN_ELAPSED, ar.getMinElapsedSeconds())
        e.putInt(KEY_AUTO_RELAY_MAX_RETRIES, ar.getMaxRetries())

        val sb = StringBuilder()
        cfg.getImageSourcePriority().forEachIndexed { i, src ->
            if (i > 0) sb.append(',')
            sb.append(src)
        }
        e.putString(KEY_IMAGE_SOURCE_PRIORITY, sb.toString())

        val ir = cfg.getIncrementalResume()
        e.putBoolean(KEY_INCREMENTAL_RESUME_ENABLED, ir.isEnabled())
        e.putString(KEY_INCREMENTAL_RESUME_STRATEGY, ir.getStrategy())

        val db = cfg.getDbCache()
        e.putBoolean(KEY_DB_CACHE_ENABLED, db.isEnabled())
        e.putLong(KEY_DB_CACHE_TTL, db.getTtlSeconds())
        e.putBoolean(KEY_DB_CACHE_INCLUDE_THUMB, db.isIncludeThumb())

        e.putString(KEY_TRUST_AUTH_MODE, cfg.getTrustAuthMode())

        val ss = cfg.getSubSwitches()
        e.putBoolean(KEY_SUB_AUTO_RELAY, ss.isAutoRelay())
        e.putBoolean(KEY_SUB_CROSS_DEVICE_IMAGE, ss.isCrossDeviceImage())
        e.putBoolean(KEY_SUB_DB_SNAPSHOT, ss.isDbSnapshot())
        e.putBoolean(KEY_SUB_INCREMENTAL_RESUME, ss.isIncrementalResume())

        e.apply()
    }

    fun interface Updater {
        /**
         * 输入旧值，返回新值；返回 null 表示不动。
         */
        fun apply(current: LabConfig): LabConfig?
    }

    fun interface Listener {
        /**
         * 配置变更回调（主线程）。
         */
        fun onLabConfigChanged(oldConfig: LabConfig, newConfig: LabConfig)
    }

    companion object {
        private const val PREFS_NAME = "lab_config"

        /**
         * 配置 schema 版本。每次加新字段时 +1，用于旧版本 SP 数据迁移。
         */
        private const val CONFIG_VERSION = 3

        private const val KEY_CONFIG_VERSION = "lab_config_version"
        private const val KEY_ENABLED = "lab_enabled"
        private const val KEY_ROLE = "lab_role"
        private const val KEY_AUTO_RELAY_SPEED = "lab_auto_relay_speed"
        private const val KEY_AUTO_RELAY_CONSECUTIVE = "lab_auto_relay_consecutive"
        private const val KEY_AUTO_RELAY_ERROR_RATE = "lab_auto_relay_error_rate"
        private const val KEY_AUTO_RELAY_MIN_ELAPSED = "lab_auto_relay_min_elapsed"
        private const val KEY_AUTO_RELAY_MAX_RETRIES = "lab_auto_relay_max_retries"
        private const val KEY_IMAGE_SOURCE_PRIORITY = "lab_image_source_priority"
        private const val KEY_INCREMENTAL_RESUME_ENABLED = "lab_incremental_enabled"
        private const val KEY_INCREMENTAL_RESUME_STRATEGY = "lab_incremental_strategy"
        private const val KEY_DB_CACHE_ENABLED = "lab_db_cache_enabled"
        private const val KEY_DB_CACHE_TTL = "lab_db_cache_ttl"
        private const val KEY_DB_CACHE_INCLUDE_THUMB = "lab_db_cache_include_thumb"
        private const val KEY_TRUST_AUTH_MODE = "lab_trust_auth_mode"
        private const val KEY_SUB_AUTO_RELAY = "lab_sub_auto_relay"
        private const val KEY_SUB_CROSS_DEVICE_IMAGE = "lab_sub_cross_device_image"
        private const val KEY_SUB_DB_SNAPSHOT = "lab_sub_db_snapshot"
        private const val KEY_SUB_INCREMENTAL_RESUME = "lab_sub_incremental_resume"

        @Volatile private var INSTANCE: LabConfigStore? = null

        /**
         * 测试钩子：仅用于单元测试重置单例。
         */
        @JvmStatic
        fun resetForTest() {
            synchronized(LabConfigStore::class.java) {
                INSTANCE = null
            }
        }

        @JvmStatic
        fun getInstance(context: Context): LabConfigStore {
            return INSTANCE ?: synchronized(LabConfigStore::class.java) {
                INSTANCE ?: LabConfigStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}