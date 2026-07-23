package com.hippo.ehviewer.task.scheduled

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hippo.ehviewer.task.scheduled.holiday.WeekdayConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时任务持久化存储
 */
class ScheduledTaskPersistence(context: Context) {
    companion object {
        private const val TAG = "ScheduledTaskPersistence"
        private const val PREFS_NAME = "scheduled_tasks"
        private const val KEY_TASKS = "tasks"
        private const val KEY_GROUPS = "groups"
        private const val KEY_WEEKDAY_CONFIG = "weekday_config"
        private const val KEY_HOLIDAY_LAST_UPDATE = "holiday_last_update"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveTasks(tasks: List<ScheduledTask>) {
        try {
            val jsonArray = JSONArray()
            tasks.forEach { task ->
                jsonArray.put(task.toJson())
            }
            prefs.edit()
                .putString(KEY_TASKS, jsonArray.toString())
                .apply()
            Log.d(TAG, "Saved ${tasks.size} tasks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save tasks", e)
        }
    }

    fun loadTasks(): List<ScheduledTask> {
        return try {
            val jsonStr = prefs.getString(KEY_TASKS, null) ?: return emptyList()
            val jsonArray = JSONArray(jsonStr)
            val tasks = mutableListOf<ScheduledTask>()
            
            for (i in 0 until jsonArray.length()) {
                val taskJson = jsonArray.getJSONObject(i)
                tasks.add(ScheduledTask.fromJson(taskJson))
            }
            
            Log.d(TAG, "Loaded ${tasks.size} tasks")
            tasks
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tasks", e)
            emptyList()
        }
    }

    fun saveGroups(groups: List<TaskGroup>) {
        try {
            val jsonArray = JSONArray()
            groups.forEach { group ->
                jsonArray.put(group.toJson())
            }
            prefs.edit()
                .putString(KEY_GROUPS, jsonArray.toString())
                .apply()
            Log.d(TAG, "Saved ${groups.size} groups")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save groups", e)
        }
    }

    fun loadGroups(): List<TaskGroup> {
        return try {
            val jsonStr = prefs.getString(KEY_GROUPS, null)
            if (jsonStr == null) {
                // 返回默认组
                return listOf(TaskGroup.createDefault())
            }
            
            val jsonArray = JSONArray(jsonStr)
            val groups = mutableListOf<TaskGroup>()
            
            for (i in 0 until jsonArray.length()) {
                val groupJson = jsonArray.getJSONObject(i)
                groups.add(TaskGroup.fromJson(groupJson))
            }
            
            // 确保默认组存在
            if (groups.none { it.groupId == TaskGroup.DEFAULT_GROUP_ID }) {
                groups.add(0, TaskGroup.createDefault())
            }
            
            Log.d(TAG, "Loaded ${groups.size} groups")
            groups
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load groups", e)
            listOf(TaskGroup.createDefault())
        }
    }

    fun saveWeekdayConfig(config: WeekdayConfig) {
        try {
            prefs.edit()
                .putString(KEY_WEEKDAY_CONFIG, config.toJson().toString())
                .apply()
            Log.d(TAG, "Saved weekday config")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save weekday config", e)
        }
    }

    fun loadWeekdayConfig(): WeekdayConfig {
        return try {
            val jsonStr = prefs.getString(KEY_WEEKDAY_CONFIG, null) ?: return WeekdayConfig()
            WeekdayConfig.fromJson(JSONObject(jsonStr))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load weekday config", e)
            WeekdayConfig()
        }
    }

    fun saveHolidayLastUpdateTime(timeMillis: Long) {
        prefs.edit()
            .putLong(KEY_HOLIDAY_LAST_UPDATE, timeMillis)
            .apply()
    }

    fun getHolidayLastUpdateTime(): Long {
        return prefs.getLong(KEY_HOLIDAY_LAST_UPDATE, 0)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Cleared all scheduled task data")
    }
}
