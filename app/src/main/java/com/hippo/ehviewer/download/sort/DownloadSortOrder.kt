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

package com.hippo.ehviewer.download.sort

import android.content.Context
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings

/**
 * 多级下载排序规则的读取、迁移和摘要。
 *
 * 新规则保存在 `download_sort_rules_v2`，旧的两层设置只用于首次迁移。
 */
object DownloadSortOrder {

    private const val MAX_RULES_FOR_SUMMARY = 4

    @JvmStatic
    fun load(context: Context): List<DownloadSortRule> {
        if (!Settings.isDownloadSortRulesMigratedV2()) {
            migrate(context)
        }
        return fromJson(Settings.getDownloadSortRulesV2())
    }

    @JvmStatic
    fun save(context: Context, rules: List<DownloadSortRule>) {
        Settings.setDownloadSortRulesV2(toJson(rules))
    }

    /**
     * 旧配置 → 新规则。
     *
     * 纯函数，方便单元测试。
     */
    @JvmStatic
    fun migrateRules(
        primary: Int,
        secondary: Int,
        sortByRemaining: Boolean,
    ): List<DownloadSortRule> {
        val rules = ArrayList<DownloadSortRule>(2)
        val pageField = if (sortByRemaining) {
            DownloadSortField.REMAINING
        } else {
            DownloadSortField.TOTAL_PAGES
        }
        fun add(field: DownloadSortField, ascending: Boolean) {
            if (rules.any { it.field == field }) return
            rules.add(DownloadSortRule(field, ascending))
        }
        when (primary) {
            Settings.DOWNLOAD_QUEUE_ORDER_FEWEST_FIRST -> add(pageField, true)
            Settings.DOWNLOAD_QUEUE_ORDER_MOST_FIRST -> add(pageField, false)
            Settings.DOWNLOAD_QUEUE_ORDER_CATEGORY_PRIORITY ->
                add(DownloadSortField.CATEGORY, true)
        }
        if (primary != Settings.DOWNLOAD_QUEUE_ORDER_DEFAULT) {
            when (secondary) {
                Settings.DOWNLOAD_QUEUE_SECONDARY_FEWEST_FIRST -> add(pageField, true)
                Settings.DOWNLOAD_QUEUE_SECONDARY_MOST_FIRST -> add(pageField, false)
                Settings.DOWNLOAD_QUEUE_SECONDARY_CATEGORY_PRIORITY ->
                    add(DownloadSortField.CATEGORY, true)
            }
        }
        return rules
    }

    @JvmStatic
    fun migrate(context: Context) {
        val rules = migrateRules(
            Settings.getDownloadQueueOrder(),
            Settings.getDownloadQueueOrderSecondary(),
            Settings.getDownloadSortByRemaining(),
        )
        Settings.setDownloadSortRulesV2(toJson(rules))
        Settings.setDownloadSortRulesMigratedV2(true)
    }

    @JvmStatic
    fun toJson(rules: List<DownloadSortRule>): String {
        val array = JSONArray()
        for (rule in rules) {
            array.add(JSONObject().apply {
                put("field", rule.field.wire)
                put("asc", rule.ascending)
            })
        }
        return array.toJSONString()
    }

    @JvmStatic
    fun fromJson(value: String?): List<DownloadSortRule> {
        if (value.isNullOrEmpty()) return emptyList()
        val result = ArrayList<DownloadSortRule>()
        try {
            val array = JSONArray.parseArray(value) ?: return emptyList()
            val seen = HashSet<DownloadSortField>()
            for (i in array.indices) {
                val json = array.getJSONObject(i) ?: continue
                val field = DownloadSortField.fromWire(json.getString("field")) ?: continue
                if (!seen.add(field)) continue
                result.add(DownloadSortRule(field, json.getBooleanValue("asc")))
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return result
    }

    @JvmStatic
    fun describe(context: Context): String =
        describe(context, Settings.getAdvancedDownloadSortEnabled())

    /**
     * 生成排序摘要。[enabled] 显式指定开关状态，便于设置页在开关值写入前立即刷新。
     */
    @JvmStatic
    fun describe(context: Context, enabled: Boolean): String {
        if (!enabled) {
            return context.getString(R.string.download_sort_summary_disabled)
        }
        val rules = load(context)
        if (rules.isEmpty()) {
            return context.getString(R.string.download_sort_summary_default)
        }
        val parts = rules.take(MAX_RULES_FOR_SUMMARY).map { rule ->
            context.getString(rule.field.labelRes) + if (rule.ascending) " ↑" else " ↓"
        }
        val text = parts.joinToString(" → ")
        return if (rules.size > MAX_RULES_FOR_SUMMARY) "$text …" else text
    }
}
