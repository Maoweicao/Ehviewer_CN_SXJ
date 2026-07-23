package com.hippo.ehviewer.task.scheduled

import com.hippo.ehviewer.task.BackgroundTask
import org.json.JSONObject
import java.util.UUID

/**
 * 定时任务数据模型
 */
data class ScheduledTask(
    val id: String,                              // UUID
    val taskClassName: String,                   // 任务类名
    val taskDisplayName: String,                 // 显示名称
    val taskType: BackgroundTask.TaskType,       // 任务类型
    val paramData: String? = null,               // JSON 格式的参数
    val delayCondition: DelayCondition,          // 延时条件
    val repeatMode: RepeatMode,                  // 重复模式
    val cronExpression: String? = null,          // Cron 表达式（当 repeatMode=CUSTOM_CRON 时）
    val groupId: String = TaskGroup.DEFAULT_GROUP_ID, // 任务组 ID
    val retryConfig: RetryConfig = RetryConfig(),// 重试配置
    var order: Int,                              // 执行顺序
    var state: ScheduledTaskState,               // 状态
    val createdAt: Long,                         // 创建时间
    var scheduledTime: Long? = null,             // 计划执行时间
    var lastExecutedAt: Long? = null,            // 上次执行时间
    var executionCount: Int = 0,                 // 执行次数
    var retryCount: Int = 0                      // 当前重试次数
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("taskClassName", taskClassName)
            put("taskDisplayName", taskDisplayName)
            put("taskType", taskType.name)
            put("paramData", paramData)
            put("delayCondition", delayCondition.name)
            put("repeatMode", repeatMode.name)
            put("cronExpression", cronExpression)
            put("groupId", groupId)
            put("retryConfig", retryConfig.toJson())
            put("order", order)
            put("state", state.name)
            put("createdAt", createdAt)
            put("scheduledTime", scheduledTime ?: JSONObject.NULL)
            put("lastExecutedAt", lastExecutedAt ?: JSONObject.NULL)
            put("executionCount", executionCount)
            put("retryCount", retryCount)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ScheduledTask {
            return ScheduledTask(
                id = json.optString("id", UUID.randomUUID().toString()),
                taskClassName = json.optString("taskClassName", ""),
                taskDisplayName = json.optString("taskDisplayName", ""),
                taskType = try {
                    BackgroundTask.TaskType.valueOf(json.optString("taskType", BackgroundTask.TaskType.OTHER.name))
                } catch (e: Exception) {
                    BackgroundTask.TaskType.OTHER
                },
                paramData = json.optString("paramData", null),
                delayCondition = try {
                    DelayCondition.valueOf(json.optString("delayCondition", DelayCondition.IMMEDIATE.name))
                } catch (e: Exception) {
                    DelayCondition.IMMEDIATE
                },
                repeatMode = try {
                    RepeatMode.valueOf(json.optString("repeatMode", RepeatMode.ONCE.name))
                } catch (e: Exception) {
                    RepeatMode.ONCE
                },
                cronExpression = json.optString("cronExpression", null),
                groupId = json.optString("groupId", TaskGroup.DEFAULT_GROUP_ID),
                retryConfig = RetryConfig.fromJson(json.optJSONObject("retryConfig") ?: JSONObject()),
                order = json.optInt("order", 0),
                state = try {
                    ScheduledTaskState.valueOf(json.optString("state", ScheduledTaskState.PENDING.name))
                } catch (e: Exception) {
                    ScheduledTaskState.PENDING
                },
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                scheduledTime = if (json.isNull("scheduledTime")) null else json.optLong("scheduledTime"),
                lastExecutedAt = if (json.isNull("lastExecutedAt")) null else json.optLong("lastExecutedAt"),
                executionCount = json.optInt("executionCount", 0),
                retryCount = json.optInt("retryCount", 0)
            )
        }

        @JvmStatic
        @JvmOverloads
        fun create(
            taskClassName: String,
            taskDisplayName: String,
            taskType: BackgroundTask.TaskType,
            paramData: String? = null,
            delayCondition: DelayCondition = DelayCondition.IMMEDIATE,
            repeatMode: RepeatMode = RepeatMode.ONCE,
            cronExpression: String? = null,
            groupId: String = TaskGroup.DEFAULT_GROUP_ID,
            retryConfig: RetryConfig = RetryConfig(),
            order: Int = 0,
            scheduledTime: Long? = null
        ): ScheduledTask {
            return ScheduledTask(
                id = UUID.randomUUID().toString(),
                taskClassName = taskClassName,
                taskDisplayName = taskDisplayName,
                taskType = taskType,
                paramData = paramData,
                delayCondition = delayCondition,
                repeatMode = repeatMode,
                cronExpression = cronExpression,
                groupId = groupId,
                retryConfig = retryConfig,
                order = order,
                state = if (delayCondition == DelayCondition.IMMEDIATE) {
                    ScheduledTaskState.PENDING
                } else {
                    ScheduledTaskState.WAITING_CONDITION
                },
                createdAt = System.currentTimeMillis(),
                scheduledTime = scheduledTime
            )
        }
    }
}
