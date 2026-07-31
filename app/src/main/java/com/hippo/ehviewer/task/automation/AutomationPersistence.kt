package com.hippo.ehviewer.task.automation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hippo.ehviewer.task.scheduled.ScheduledTask
import com.hippo.ehviewer.task.scheduled.TaskGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动化任务持久化存储。
 *
 * 数据布局：
 * - 新格式：SharedPreferences `automation_tasks`，KEY_TASKS 保存 JSON 数组。
 * - 首次加载时若新格式不存在但旧 `scheduled_tasks` 存在，自动迁移并清除旧数据。
 */
class AutomationPersistence(context: Context) {
    companion object {
        private const val TAG = "AutomationPersistence"

        private const val PREFS_NAME = "automation_tasks"
        private const val KEY_TASKS = "tasks"
        private const val KEY_VERSION = "version"
        private const val KEY_MIGRATED = "migrated_from_v1"

        private const val CURRENT_VERSION = 2
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val appContext: Context = context.applicationContext

    fun saveTasks(tasks: List<AutomationTask>) {
        try {
            val array = JSONArray()
            tasks.forEach { array.put(it.toJson()) }
            prefs.edit()
                .putString(KEY_TASKS, array.toString())
                .putInt(KEY_VERSION, CURRENT_VERSION)
                .apply()
            Log.d(TAG, "Saved ${tasks.size} automation tasks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save automation tasks", e)
        }
    }

    fun loadTasks(): List<AutomationTask> {
        val raw = prefs.getString(KEY_TASKS, null)
        if (raw != null) {
            return parseTaskArray(raw)
        }

        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            val migrated = migrateFromLegacy()
            if (migrated.isNotEmpty()) {
                saveTasks(migrated)
            }
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return migrated
        }

        return emptyList()
    }

    fun isMigrated(): Boolean = prefs.getBoolean(KEY_MIGRATED, false)

    private fun parseTaskArray(raw: String): List<AutomationTask> {
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<AutomationTask>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                runCatching { AutomationTask.fromJson(obj) }.getOrNull()?.let(list::add)
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse automation tasks", e)
            emptyList()
        }
    }

    /**
     * 迁移旧 ScheduledTask 数据。
     * 同一 groupId 下的多个 ScheduledTask 合并为一个 AutomationTask 的多个 actions，
     * 顺序/并行由原 TaskGroup.executionMode 决定。
     */
    private fun migrateFromLegacy(): List<AutomationTask> {
        return try {
            val oldPrefs = appContext.getSharedPreferences("scheduled_tasks", Context.MODE_PRIVATE)
            val groupsJson = oldPrefs.getString("groups", null) ?: "[]"
            val tasksJson = oldPrefs.getString("tasks", null) ?: "[]"

            val groups = runCatching {
                val arr = JSONArray(groupsJson)
                (0 until arr.length()).map { TaskGroup.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(listOf(TaskGroup.createDefault()))

            val groupMode = groups.associate { it.groupId to it.executionMode }

            val oldTasks = runCatching {
                val arr = JSONArray(tasksJson)
                (0 until arr.length()).map { ScheduledTask.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())

            if (oldTasks.isEmpty()) return emptyList()

            val byGroup = oldTasks.groupBy { it.groupId }
            val result = mutableListOf<AutomationTask>()

            byGroup.forEach { (groupId, tasks) ->
                val mode = groupMode[groupId] ?: com.hippo.ehviewer.task.scheduled.ExecutionMode.SEQUENTIAL
                val sorted = tasks.sortedBy { it.order }
                if (sorted.size == 1) {
                    result.add(sorted.first().toAutomationTask(mode))
                } else {
                    val first = sorted.first()
                    val actions = sorted.map { it.toAction() }
                    result.add(
                        AutomationTask(
                            id = "auto_${groupId}",
                            name = first.taskDisplayName.ifBlank { "迁移的自动化规则" },
                            enabled = first.state != com.hippo.ehviewer.task.scheduled.ScheduledTaskState.PAUSED,
                            trigger = first.toAutomationTrigger(),
                            conditions = AutomationConditions(),
                            actions = actions,
                            executionMode = if (mode == com.hippo.ehviewer.task.scheduled.ExecutionMode.SEQUENTIAL)
                                ExecutionMode.SEQUENTIAL else ExecutionMode.PARALLEL,
                            retryConfig = first.retryConfig.toRetryConfig(),
                            cooldownMillis = 0L,
                            order = first.order,
                            state = AutomationState.PENDING,
                            createdAt = first.createdAt,
                            lastExecutedAt = first.lastExecutedAt,
                            nextScheduledTime = first.scheduledTime,
                            executionCount = first.executionCount,
                            retryCount = first.retryCount,
                        ),
                    )
                }
            }

            Log.i(TAG, "Migrated ${result.size} automation rules from legacy scheduled_tasks")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate from legacy", e)
            emptyList()
        }
    }
}

/**
 * 把旧 ScheduledTask 转成新的 AutomationAction。
 */
private fun ScheduledTask.toAction(): AutomationAction = AutomationAction(
    id = "act_$id",
    taskClassName = taskClassName,
    displayName = taskDisplayName,
    taskType = taskType.name,
    params = paramData,
    enabled = true,
)

private fun ScheduledTask.toAutomationTask(mode: com.hippo.ehviewer.task.scheduled.ExecutionMode): AutomationTask {
    val action = toAction()
    return AutomationTask(
        id = "auto_$id",
        name = taskDisplayName.ifBlank { "迁移的自动化规则" },
        enabled = state != com.hippo.ehviewer.task.scheduled.ScheduledTaskState.PAUSED,
        trigger = toAutomationTrigger(),
        conditions = AutomationConditions(),
        actions = listOf(action),
        executionMode = if (mode == com.hippo.ehviewer.task.scheduled.ExecutionMode.SEQUENTIAL)
            ExecutionMode.SEQUENTIAL else ExecutionMode.PARALLEL,
        retryConfig = retryConfig.toRetryConfig(),
        cooldownMillis = 0L,
        order = order,
        state = AutomationState.PENDING,
        createdAt = createdAt,
        lastExecutedAt = lastExecutedAt,
        nextScheduledTime = scheduledTime,
        executionCount = executionCount,
        retryCount = retryCount,
    )
}

private fun ScheduledTask.toAutomationTrigger(): AutomationTrigger {
    return when (delayCondition) {
        com.hippo.ehviewer.task.scheduled.DelayCondition.IMMEDIATE ->
            AutomationTrigger.Schedule(
                repeatMode = repeatMode.toRepeatMode(),
                cronExpression = cronExpression,
                scheduledTime = scheduledTime,
            )
        com.hippo.ehviewer.task.scheduled.DelayCondition.DOWNLOAD_COMPLETE ->
            AutomationTrigger.Event(EventTriggerType.ALL_DOWNLOADS_COMPLETED)
        com.hippo.ehviewer.task.scheduled.DelayCondition.OTHER_TASKS_COMPLETE ->
            AutomationTrigger.Event(EventTriggerType.DOWNLOAD_FINISHED)
        com.hippo.ehviewer.task.scheduled.DelayCondition.SPECIFIC_TIME ->
            AutomationTrigger.Schedule(
                repeatMode = repeatMode.toRepeatMode(),
                cronExpression = cronExpression,
                scheduledTime = scheduledTime,
            )
    }
}

private fun com.hippo.ehviewer.task.scheduled.RepeatMode.toRepeatMode(): RepeatMode = when (this) {
    com.hippo.ehviewer.task.scheduled.RepeatMode.ONCE -> RepeatMode.ONCE
    com.hippo.ehviewer.task.scheduled.RepeatMode.DAILY -> RepeatMode.DAILY
    com.hippo.ehviewer.task.scheduled.RepeatMode.WEEKDAYS -> RepeatMode.WEEKDAYS
    com.hippo.ehviewer.task.scheduled.RepeatMode.WEEKENDS -> RepeatMode.WEEKENDS
    com.hippo.ehviewer.task.scheduled.RepeatMode.HOLIDAYS -> RepeatMode.HOLIDAYS
    com.hippo.ehviewer.task.scheduled.RepeatMode.CUSTOM_CRON -> RepeatMode.CUSTOM_CRON
}

private fun com.hippo.ehviewer.task.scheduled.RetryConfig.toRetryConfig(): RetryConfig {
    val mode = when (mode) {
        com.hippo.ehviewer.task.scheduled.RetryMode.NO_RETRY -> RetryMode.NO_RETRY
        com.hippo.ehviewer.task.scheduled.RetryMode.IMMEDIATE -> RetryMode.IMMEDIATE
        com.hippo.ehviewer.task.scheduled.RetryMode.DELAYED -> RetryMode.DELAYED
        com.hippo.ehviewer.task.scheduled.RetryMode.NEXT_SCHEDULE -> RetryMode.NEXT_SCHEDULE
    }
    return RetryConfig(mode, maxRetries, delayMillis)
}