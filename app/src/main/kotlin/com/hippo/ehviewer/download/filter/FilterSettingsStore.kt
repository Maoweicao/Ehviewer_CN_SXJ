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

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import androidx.annotation.WorkerThread
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.transfer.log.TransferLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载筛选配置存储。
 *
 * 整个 [FilterConfigSnapshot] 序列化成一份 JSON 存在 SharedPreferences 里，
 * 写入是原子的一次 `putString`，避免多键部分写入导致配置半残。
 *
 * 首次读取时若为空，会写入内置默认值（旧版 7 条快捷筛选 + 面板快速按钮），
 * 之后所有修改都基于这份配置。
 */
class FilterSettingsStore private constructor(appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val listeners = mutableListOf<Listener>()

    @Volatile
    private var cached: FilterConfigSnapshot? = null

    @NonNull
    fun get(): FilterConfigSnapshot {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: load().also { cached = it }
        }
    }

    @NonNull
    fun update(updater: Updater): FilterConfigSnapshot {
        val (oldSnapshot, newSnapshot) = synchronized(lock) {
            val oldSnapshot = get()
            val updated = updater.apply(oldSnapshot) ?: return oldSnapshot
            cached = updated
            save(updated)
            oldSnapshot to updated
        }
        mainHandler.post {
            synchronized(listeners) {
                listeners.toList().forEach { it.onFilterConfigChanged(oldSnapshot, newSnapshot) }
            }
        }
        return newSnapshot
    }

    fun addListener(listener: Listener) {
        if (listener != null) synchronized(listeners) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    /** 强制下次 [get] 回读 SP（外部直接改 SP 时使用）。 */
    fun invalidate() {
        synchronized(lock) { cached = null }
    }

    // ---------------- 快捷筛选增删改 ----------------

    fun addQuickFilter(definition: QuickFilterDefinition): QuickFilterDefinition =
        update { it.copy(quickFilters = it.quickFilters + definition) }.quickFilters
            .last { d -> d.id == definition.id }

    fun updateQuickFilter(definition: QuickFilterDefinition) = update { snapshot ->
        snapshot.copy(
            quickFilters = snapshot.quickFilters.map { if (it.id == definition.id) definition else it },
        )
    }

    fun removeQuickFilter(id: String) = update { snapshot ->
        snapshot.copy(quickFilters = snapshot.quickFilters.filterNot { it.id == id })
    }

    fun moveQuickFilter(fromIndex: Int, toIndex: Int) = update { snapshot ->
        val list = snapshot.quickFilters.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices) return@update snapshot
        list.add(toIndex, list.removeAt(fromIndex))
        snapshot.copy(quickFilters = list)
    }

    fun toggleQuickFilter(id: String, enabled: Boolean) = update { snapshot ->
        snapshot.copy(
            quickFilters = snapshot.quickFilters.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            },
        )
    }

    /** 把内置快捷筛选恢复成出厂默认值（不删除用户新增的项）。 */
    fun resetQuickFilters() = update { snapshot ->
        val defaults = FilterPresets.defaultQuickFilters().associateBy { it.id }
        snapshot.copy(
            quickFilters = snapshot.quickFilters.map { existing ->
                defaults[existing.id] ?: existing
            },
        )
    }

    fun findQuickFilter(id: String?): QuickFilterDefinition? {
        if (id.isNullOrEmpty()) return null
        return get().quickFilters.firstOrNull { it.id == id }
    }

    /** 溢出菜单里要显示的（已启用的）快捷筛选。 */
    fun enabledQuickFilters(): List<QuickFilterDefinition> =
        get().quickFilters.filter { it.enabled }

    // ---------------- 面板快速设置按钮 ----------------

    fun addPanelPreset(preset: PanelPreset): PanelPreset {
        update { it.copy(panelPresets = it.panelPresets + preset) }
        return preset
    }

    fun updatePanelPreset(preset: PanelPreset) = update { snapshot ->
        snapshot.copy(
            panelPresets = snapshot.panelPresets.map { if (it.id == preset.id) preset else it },
        )
    }

    fun removePanelPreset(id: String) = update { snapshot ->
        snapshot.copy(panelPresets = snapshot.panelPresets.filterNot { it.id == id })
    }

    fun movePanelPreset(fromIndex: Int, toIndex: Int) = update { snapshot ->
        val list = snapshot.panelPresets.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices) return@update snapshot
        list.add(toIndex, list.removeAt(fromIndex))
        snapshot.copy(panelPresets = list)
    }

    fun togglePanelPreset(id: String, enabled: Boolean) = update { snapshot ->
        snapshot.copy(
            panelPresets = snapshot.panelPresets.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            },
        )
    }

    fun resetPanelPresets() = update { snapshot ->
        val defaults = FilterPresets.defaultPanelPresets().associateBy { it.id }
        snapshot.copy(
            panelPresets = snapshot.panelPresets.map { existing ->
                defaults[existing.id] ?: existing
            },
        )
    }

    fun enabledPanelPresets(): List<PanelPreset> = get().panelPresets.filter { it.enabled }

    // ---------------- 全局排除 / 低分定义 ----------------

    fun setGlobalExclude(rule: ExcludeRule) = update { it.copy(globalExclude = rule) }

    fun globalExclude(): ExcludeRule = get().globalExclude

    fun setLowRatingConfig(config: LowRatingConfig) = update { it.copy(lowRating = config) }

    fun lowRatingConfig(): LowRatingConfig = get().lowRating

    // ---------------- 导入 / 导出 ----------------

    /** 导出为 JSON 文本（带 schema 版本与导出时间）。 */
    fun exportJson(): String {
        val json = get().toJson()
        json["exportedAt"] = System.currentTimeMillis()
        json["app"] = APP_TAG
        return JSON.toJSONString(json, true)
    }

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "ehviewer-filter-config-$stamp.json"
    }

    /**
     * 从 JSON 文本导入。
     *
     * @param merge true = 与现有配置合并（同名覆盖），false = 完全替换
     */
    @WorkerThread
    fun importJson(text: String?, merge: Boolean): ImportResult {
        if (text.isNullOrBlank()) {
            return ImportResult.Failure("配置内容为空")
        }
        val parsed = try {
            JSON.parseObject(text)
        } catch (e: Exception) {
            return ImportResult.Failure("不是合法的 JSON 文件")
        } ?: return ImportResult.Failure("配置内容为空")

        val version = parsed.getIntValue("schemaVersion")
        if (version <= 0 || version > FilterConfigSnapshot.SCHEMA_VERSION) {
            return ImportResult.Failure("配置版本不受支持（$version）")
        }

        val incoming = FilterConfigSnapshot.fromJson(parsed)
            ?: return ImportResult.Failure("配置格式无法识别")
        if (incoming.quickFilters.isEmpty() && incoming.panelPresets.isEmpty()) {
            return ImportResult.Failure("配置里没有可导入的快捷筛选")
        }

        val applied = update { current ->
            if (merge) FilterConfigSnapshot.merge(current, incoming) else incoming
        }
        TransferLogger.getInstance().i(
            "FilterSettingsStore",
            "Imported filter config: merge=$merge, quickFilters=${applied.quickFilters.size}, " +
                "panelPresets=${applied.panelPresets.size}",
        )
        return ImportResult.Success(applied.quickFilters.size, applied.panelPresets.size)
    }

    /** 导入结果。 */
    sealed class ImportResult {
        data class Success(val quickFilters: Int, val panelPresets: Int) : ImportResult()
        data class Failure(val message: String) : ImportResult()
    }

    // ---------------- 内部 ----------------

    private fun load(): FilterConfigSnapshot {
        return try {
            val stored = prefs.getString(KEY_CONFIG, null)
            if (stored.isNullOrBlank()) {
                val defaults = FilterConfigSnapshot(
                    quickFilters = FilterPresets.defaultQuickFilters(),
                    panelPresets = FilterPresets.defaultPanelPresets(),
                )
                save(defaults)
                defaults
            } else {
                val json: JSONObject = JSON.parseObject(stored)
                FilterConfigSnapshot.fromJson(json) ?: FilterConfigSnapshot(
                    quickFilters = FilterPresets.defaultQuickFilters(),
                    panelPresets = FilterPresets.defaultPanelPresets(),
                )
            }
        } catch (e: Exception) {
            TransferLogger.getInstance().e("FilterSettingsStore", "Failed to load config", e)
            FilterConfigSnapshot(
                quickFilters = FilterPresets.defaultQuickFilters(),
                panelPresets = FilterPresets.defaultPanelPresets(),
            )
        }
    }

    private fun save(snapshot: FilterConfigSnapshot) {
        prefs.edit()
            .putString(KEY_CONFIG, JSON.toJSONString(snapshot.toJson()))
            .putInt(KEY_SCHEMA_VERSION, FilterConfigSnapshot.SCHEMA_VERSION)
            .apply()
    }

    fun interface Updater {
        /** 返回 null 表示放弃本次修改。 */
        fun apply(current: FilterConfigSnapshot): FilterConfigSnapshot?
    }

    fun interface Listener {
        /** 回调保证在主线程。 */
        fun onFilterConfigChanged(oldConfig: FilterConfigSnapshot, newConfig: FilterConfigSnapshot)
    }

    companion object {
        private const val PREFS_NAME = "download_filter_settings"
        private const val KEY_CONFIG = "filter_config_json"
        private const val KEY_SCHEMA_VERSION = "filter_schema_version"
        private const val APP_TAG = "ehviewer-download-filter"

        @Volatile
        private var INSTANCE: FilterSettingsStore? = null

        @JvmStatic
        fun getInstance(context: Context): FilterSettingsStore =
            INSTANCE ?: synchronized(FilterSettingsStore::class.java) {
                INSTANCE ?: FilterSettingsStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
