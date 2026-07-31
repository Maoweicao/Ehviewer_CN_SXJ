package com.hippo.ehviewer.task.automation

import org.json.JSONObject

/**
 * 运行条件：仅当全部条件满足时才执行动作
 */
data class AutomationConditions(
    val requireWifi: Boolean = false,
    val requireNoMetered: Boolean = false,
    val requireCharging: Boolean = false,
    val requireScreenOff: Boolean = false,
    val minBatteryPercent: Int = 0,
    val timeWindow: TimeWindow? = null,
    val weekdayMask: Int = 0,
) {
    fun isEmpty(): Boolean =
        !requireWifi && !requireNoMetered && !requireCharging && !requireScreenOff &&
            minBatteryPercent <= 0 && timeWindow == null && weekdayMask == 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("requireWifi", requireWifi)
        put("requireNoMetered", requireNoMetered)
        put("requireCharging", requireCharging)
        put("requireScreenOff", requireScreenOff)
        put("minBatteryPercent", minBatteryPercent)
        put("timeWindow", timeWindow?.toJson() ?: JSONObject.NULL)
        put("weekdayMask", weekdayMask)
    }

    companion object {
        fun fromJson(json: JSONObject): AutomationConditions = AutomationConditions(
            requireWifi = json.optBoolean("requireWifi", false),
            requireNoMetered = json.optBoolean("requireNoMetered", false),
            requireCharging = json.optBoolean("requireCharging", false),
            requireScreenOff = json.optBoolean("requireScreenOff", false),
            minBatteryPercent = json.optInt("minBatteryPercent", 0),
            timeWindow = if (json.isNull("timeWindow")) null else json.optJSONObject("timeWindow")?.let { TimeWindow.fromJson(it) },
            weekdayMask = json.optInt("weekdayMask", 0),
        )
    }
}

/**
 * 时段窗口（每日有效时间范围）
 */
data class TimeWindow(
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
) {
    fun contains(nowMinuteOfDay: Int): Boolean {
        return if (startMinuteOfDay <= endMinuteOfDay) {
            nowMinuteOfDay in startMinuteOfDay..endMinuteOfDay
        } else {
            nowMinuteOfDay >= startMinuteOfDay || nowMinuteOfDay <= endMinuteOfDay
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("start", startMinuteOfDay)
        put("end", endMinuteOfDay)
    }

    companion object {
        fun fromJson(json: JSONObject): TimeWindow = TimeWindow(
            startMinuteOfDay = json.optInt("start", 0),
            endMinuteOfDay = json.optInt("end", 0),
        )
    }
}