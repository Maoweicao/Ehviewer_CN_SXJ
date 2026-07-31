package com.hippo.ehviewer.task.automation

import org.json.JSONObject

/**
 * 触发器：定义何时触发自动化任务
 */
sealed class AutomationTrigger {

    /**
     * 时间触发器：按计划时间触发
     */
    data class Schedule(
        val repeatMode: RepeatMode = RepeatMode.ONCE,
        val cronExpression: String? = null,
        val scheduledTime: Long? = null,
    ) : AutomationTrigger()

    /**
     * 事件触发器：在某个事件发生时触发
     */
    data class Event(
        val type: EventTriggerType,
        val filter: EventFilter = EventFilter.None,
    ) : AutomationTrigger()

    /**
     * 手动触发器：仅手动运行时触发
     */
    data object Manual : AutomationTrigger()

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_TYPE, when (this@AutomationTrigger) {
            is Schedule -> "schedule"
            is Event -> "event"
            Manual -> "manual"
        })
        when (this@AutomationTrigger) {
            is Schedule -> {
                put(KEY_REPEAT, repeatMode.name)
                put(KEY_CRON, cronExpression ?: JSONObject.NULL)
                put(KEY_SCHEDULED_TIME, scheduledTime ?: JSONObject.NULL)
            }
            is Event -> {
                put(KEY_EVENT_TYPE, type.name)
                put(KEY_EVENT_FILTER, filter.toJson())
            }
            Manual -> {}
        }
    }

    companion object {
        private const val KEY_TYPE = "type"
        private const val KEY_REPEAT = "repeat"
        private const val KEY_CRON = "cron"
        private const val KEY_SCHEDULED_TIME = "scheduledTime"
        private const val KEY_EVENT_TYPE = "eventType"
        private const val KEY_EVENT_FILTER = "filter"

        fun fromJson(json: JSONObject): AutomationTrigger {
            return when (val type = json.optString(KEY_TYPE, "schedule")) {
                "schedule" -> Schedule(
                    repeatMode = runCatching { RepeatMode.valueOf(json.optString(KEY_REPEAT)) }
                        .getOrDefault(RepeatMode.ONCE),
                    cronExpression = if (json.isNull(KEY_CRON)) null else json.optString(KEY_CRON, null),
                    scheduledTime = if (json.isNull(KEY_SCHEDULED_TIME)) null else json.optLong(KEY_SCHEDULED_TIME),
                )
                "event" -> Event(
                    type = runCatching { EventTriggerType.valueOf(json.optString(KEY_EVENT_TYPE)) }
                        .getOrDefault(EventTriggerType.DOWNLOAD_FINISHED),
                    filter = json.optJSONObject(KEY_EVENT_FILTER)?.let { EventFilter.fromJson(it) }
                        ?: EventFilter.None,
                )
                "manual" -> Manual
                else -> Schedule()
            }
        }
    }
}

/**
 * 事件过滤器，用于细化事件触发条件
 */
sealed class EventFilter {
    data object None : EventFilter()
    data class DownloadLabel(val label: String?) : EventFilter()
    data class FilePath(val path: String, val includeSubdirs: Boolean = false) : EventFilter()
    data class IntentFilter(
        val action: String,
        val categories: List<String> = emptyList(),
        val dataScheme: String? = null,
    ) : EventFilter()

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_FILTER_TYPE, when (this@EventFilter) {
            None -> "none"
            is DownloadLabel -> "download_label"
            is FilePath -> "file_path"
            is IntentFilter -> "intent"
        })
        when (this@EventFilter) {
            None -> {}
            is DownloadLabel -> put("label", label ?: JSONObject.NULL)
            is FilePath -> {
                put("path", path)
                put("includeSubdirs", includeSubdirs)
            }
            is IntentFilter -> {
                put("action", action)
                put("categories", org.json.JSONArray(categories))
                put("dataScheme", dataScheme ?: JSONObject.NULL)
            }
        }
    }

    companion object {
        private const val KEY_FILTER_TYPE = "filterType"

        fun fromJson(json: JSONObject): EventFilter {
            return when (json.optString(KEY_FILTER_TYPE, "none")) {
                "download_label" -> DownloadLabel(if (json.isNull("label")) null else json.optString("label"))
                "file_path" -> FilePath(json.optString("path", ""), json.optBoolean("includeSubdirs", false))
                "intent" -> {
                    val cats = json.optJSONArray("categories")
                    val list = if (cats != null) (0 until cats.length()).map { cats.optString(it) } else emptyList()
                    IntentFilter(
                        action = json.optString("action", ""),
                        categories = list,
                        dataScheme = if (json.isNull("dataScheme")) null else json.optString("dataScheme"),
                    )
                }
                else -> None
            }
        }
    }
}