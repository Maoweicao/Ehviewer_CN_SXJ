package com.hippo.ehviewer.task.scheduled.holiday

import org.json.JSONObject
import java.time.DayOfWeek

/**
 * 工作日配置
 */
data class WeekdayConfig(
    val workDays: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY
    )
) {
    fun isWorkday(dayOfWeek: DayOfWeek): Boolean {
        return workDays.contains(dayOfWeek)
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            val daysArray = org.json.JSONArray()
            workDays.forEach { daysArray.put(it.value) }
            put("workDays", daysArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): WeekdayConfig {
            val daysArray = json.optJSONArray("workDays")
            val workDays = mutableSetOf<DayOfWeek>()
            
            if (daysArray != null) {
                for (i in 0 until daysArray.length()) {
                    val dayValue = daysArray.optInt(i, 0)
                    if (dayValue in 1..7) {
                        workDays.add(DayOfWeek.of(dayValue))
                    }
                }
            }
            
            return if (workDays.isEmpty()) {
                // 默认周一到周五
                WeekdayConfig()
            } else {
                WeekdayConfig(workDays)
            }
        }
    }
}
