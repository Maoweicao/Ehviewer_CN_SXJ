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

import androidx.annotation.StringRes
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.util.UUID

/**
 * 一条快捷筛选。
 *
 * - [kind] 为内置预设类型时，阈值由 [ratingMax] / [sizeMinMb] / [perPageMinKb] / [agoMonths] 决定
 * - [kind] 为 [QuickFilterKind.CUSTOM] 时，直接复用一份完整的 [condition]（高级筛选配置）
 *
 * [exclude] 是这条筛选自带的额外排除规则，会与全局排除叠加。
 */
data class QuickFilterDefinition(
    val id: String,
    val name: String,
    @field:StringRes val nameRes: Int = 0,
    val enabled: Boolean = true,
    val kind: QuickFilterKind = QuickFilterKind.LOW_RATING,
    val ratingMax: Float = LowRatingConfig.DEFAULT_GLOBAL_THRESHOLD,
    val sizeMinMb: Long = 100L,
    val perPageMinKb: Long = 4096L,
    val agoMonths: Int = 12,
    /** 只在“已下载完成”的条目里筛选。AI 生成这类判定默认关闭。 */
    val requireFinished: Boolean = true,
    val condition: FilterCondition = FilterCondition.defaultAllStatuses(),
    val exclude: ExcludeRule = ExcludeRule.EMPTY,
) {

    /** 内置项在菜单里优先显示 strings 资源名，用户改名后用 [name]。 */
    fun displayName(context: android.content.Context): String =
        if (nameRes != 0) context.getString(nameRes) else name

    /**
     * 一次性写入编辑面板里的可改字段。
     *
     * [ratingMax] 传 0 表示“跟随设置里配置的低分范围”（按 catalog 细粒度）。
     */
    @JvmOverloads
    fun withEdits(
        newId: String = id,
        newName: String = name,
        newEnabled: Boolean = enabled,
        newKind: QuickFilterKind = kind,
        newRatingMax: Float = ratingMax,
        newSizeMinMb: Long = sizeMinMb,
        newPerPageMinKb: Long = perPageMinKb,
        newAgoMonths: Int = agoMonths,
        newRequireFinished: Boolean = requireFinished,
        newCondition: FilterCondition = condition,
        newExclude: ExcludeRule = exclude,
    ): QuickFilterDefinition = QuickFilterDefinition(
        id = newId,
        name = newName,
        nameRes = 0,
        enabled = newEnabled,
        kind = newKind,
        ratingMax = newRatingMax,
        sizeMinMb = newSizeMinMb,
        perPageMinKb = newPerPageMinKb,
        agoMonths = newAgoMonths,
        requireFinished = newRequireFinished,
        condition = newCondition,
        exclude = newExclude,
    )

    fun toJson(): JSONObject {
        val json = JSONObject()
        json["id"] = id
        json["name"] = name
        if (nameRes != 0) json["nameRes"] = nameRes
        json["enabled"] = enabled
        json["kind"] = kind.key
        json["ratingMax"] = ratingMax
        json["sizeMinMb"] = sizeMinMb
        json["perPageMinKb"] = perPageMinKb
        json["agoMonths"] = agoMonths
        json["requireFinished"] = requireFinished
        json["condition"] = condition.toJson()
        if (!exclude.isEmpty) json["exclude"] = exclude.toJson()
        return json
    }

    companion object {
        @JvmStatic
        fun newId(): String = "qf_" + UUID.randomUUID().toString().replace("-", "").take(12)

        @JvmStatic
        fun fromJson(json: JSONObject): QuickFilterDefinition? {
            val id = json.getString("id")?.trim()
            if (id.isNullOrEmpty()) return null
            return QuickFilterDefinition(
                id = id,
                name = json.getString("name").orEmpty(),
                nameRes = json.getIntValue("nameRes"),
                enabled = !json.containsKey("enabled") || json.getBooleanValue("enabled"),
                kind = QuickFilterKind.fromKey(json.getString("kind")),
                ratingMax = LowRatingConfig.clamp(
                    json.getFloatValue("ratingMax").takeIf { it > 0f }
                        ?: LowRatingConfig.DEFAULT_GLOBAL_THRESHOLD,
                ),
                sizeMinMb = json.getLongValue("sizeMinMb").takeIf { it > 0 } ?: 100L,
                perPageMinKb = json.getLongValue("perPageMinKb").takeIf { it > 0 } ?: 4096L,
                agoMonths = json.getIntValue("agoMonths").takeIf { it > 0 } ?: 12,
                requireFinished = !json.containsKey("requireFinished") || json.getBooleanValue("requireFinished"),
                condition = FilterCondition.fromJson(json.getJSONObject("condition")),
                exclude = ExcludeRule.fromJson(json.getJSONObject("exclude")),
            )
        }
    }
}

/**
 * 下载筛选面板里的“快速设置”按钮。
 *
 * 点击后把 [condition] 套用到面板字段（不高亮即执行），用户可以继续微调。
 * 内置按钮名称走 strings 资源，自定义按钮用 [name]。
 */
data class PanelPreset(
    val id: String,
    val name: String,
    @field:StringRes val nameRes: Int = 0,
    val enabled: Boolean = true,
    val condition: FilterCondition = FilterCondition.defaultAllStatuses(),
) {

    fun displayName(context: android.content.Context): String =
        if (nameRes != 0) context.getString(nameRes) else name

    /** Java 侧便捷入口（Kotlin 具名参数在 Java 里不可用）。 */
    @JvmOverloads
    fun withEdits(
        newId: String = id,
        newName: String = name,
        newEnabled: Boolean = enabled,
        newCondition: FilterCondition = condition,
    ): PanelPreset = PanelPreset(
        id = newId,
        name = newName,
        nameRes = 0,
        enabled = newEnabled,
        condition = newCondition,
    )

    fun toJson(): JSONObject {
        val json = JSONObject()
        json["id"] = id
        json["name"] = name
        if (nameRes != 0) json["nameRes"] = nameRes
        json["enabled"] = enabled
        json["condition"] = condition.toJson()
        return json
    }

    companion object {
        @JvmStatic
        fun newId(): String = "pp_" + UUID.randomUUID().toString().replace("-", "").take(12)

        @JvmStatic
        fun fromJson(json: JSONObject): PanelPreset? {
            val id = json.getString("id")?.trim()
            if (id.isNullOrEmpty()) return null
            return PanelPreset(
                id = id,
                name = json.getString("name").orEmpty(),
                nameRes = json.getIntValue("nameRes"),
                enabled = !json.containsKey("enabled") || json.getBooleanValue("enabled"),
                condition = FilterCondition.fromJson(json.getJSONObject("condition")),
            )
        }
    }
}

/** 整个筛选配置的快照（导入 / 导出 / 存储的基本单位）。 */
data class FilterConfigSnapshot(
    val quickFilters: List<QuickFilterDefinition> = emptyList(),
    val panelPresets: List<PanelPreset> = emptyList(),
    val globalExclude: ExcludeRule = ExcludeRule.EMPTY,
    val lowRating: LowRatingConfig = LowRatingConfig(),
) {

    fun toJson(): JSONObject {
        val json = JSONObject()
        json["schemaVersion"] = SCHEMA_VERSION
        json["quickFilters"] = JSONArray().apply { quickFilters.forEach { add(it.toJson()) } }
        json["panelPresets"] = JSONArray().apply { panelPresets.forEach { add(it.toJson()) } }
        json["globalExclude"] = globalExclude.toJson()
        json["lowRating"] = lowRating.toJson()
        return json
    }

    companion object {
        const val SCHEMA_VERSION = 1

        @JvmStatic
        fun fromJson(json: JSONObject?): FilterConfigSnapshot? {
            if (json == null) return null
            val quickFilters = mutableListOf<QuickFilterDefinition>()
            json.getJSONArray("quickFilters")?.forEach { item ->
                (item as? JSONObject)?.let { obj ->
                    QuickFilterDefinition.fromJson(obj)?.let { quickFilters.add(it) }
                }
            }
            val panelPresets = mutableListOf<PanelPreset>()
            json.getJSONArray("panelPresets")?.forEach { item ->
                (item as? JSONObject)?.let { obj ->
                    PanelPreset.fromJson(obj)?.let { panelPresets.add(it) }
                }
            }
            return FilterConfigSnapshot(
                quickFilters = quickFilters,
                panelPresets = panelPresets,
                globalExclude = ExcludeRule.fromJson(json.getJSONObject("globalExclude")),
                lowRating = LowRatingConfig.fromJson(json.getJSONObject("lowRating")),
            )
        }

        /**
         * 合并两份配置：同名（[QuickFilterDefinition.id] 相同）时以 [incoming] 覆盖，
         * 其余按 incoming 追加到末尾。用于“导入 → 合并”模式。
         */
        fun merge(current: FilterConfigSnapshot, incoming: FilterConfigSnapshot): FilterConfigSnapshot {
            val qf = LinkedHashMap<String, QuickFilterDefinition>()
            current.quickFilters.forEach { qf[it.id] = it }
            incoming.quickFilters.forEach { qf[it.id] = it }
            val pp = LinkedHashMap<String, PanelPreset>()
            current.panelPresets.forEach { pp[it.id] = it }
            incoming.panelPresets.forEach { pp[it.id] = it }
            return FilterConfigSnapshot(
                quickFilters = qf.values.toList(),
                panelPresets = pp.values.toList(),
                // 排除规则与低分定义按并集合并，避免导入后丢用户已有配置
                globalExclude = current.globalExclude + incoming.globalExclude,
                lowRating = LowRatingConfig(
                    globalThreshold = incoming.lowRating.globalThreshold,
                    perCategory = LinkedHashMap(current.lowRating.perCategory)
                        .apply { putAll(incoming.lowRating.perCategory) },
                ),
            )
        }
    }
}
