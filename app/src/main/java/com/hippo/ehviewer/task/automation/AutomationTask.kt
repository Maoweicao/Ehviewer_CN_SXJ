package com.hippo.ehviewer.task.automation

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条自动化任务（替代原 ScheduledTask）
 */
data class AutomationTask(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: AutomationTrigger,
    val conditions: AutomationConditions = AutomationConditions(),
    val actions: List<AutomationAction>,
    val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL,
    val retryConfig: RetryConfig = RetryConfig(),
    val cooldownMillis: Long = 0L,
    val order: Int = 0,
    val state: AutomationState = AutomationState.PENDING,
    val createdAt: Long,
    var lastExecutedAt: Long? = null,
    var nextScheduledTime: Long? = null,
    var executionCount: Int = 0,
    var retryCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("trigger", trigger.toJson())
        put("conditions", conditions.toJson())
        put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })
        put("executionMode", executionMode.name)
        put("retryConfig", retryConfig.toJson())
        put("cooldownMillis", cooldownMillis)
        put("order", order)
        put("state", state.name)
        put("createdAt", createdAt)
        put("lastExecutedAt", lastExecutedAt ?: JSONObject.NULL)
        put("nextScheduledTime", nextScheduledTime ?: JSONObject.NULL)
        put("executionCount", executionCount)
        put("retryCount", retryCount)
    }

    companion object {
        fun fromJson(json: JSONObject): AutomationTask {
            val actionsJson = json.optJSONArray("actions") ?: JSONArray()
            val actionsList = (0 until actionsJson.length()).mapNotNull {
                runCatching { actionsJson.getJSONObject(it) }.getOrNull()
            }.map { AutomationAction.fromJson(it) }
            return AutomationTask(
                id = json.optString("id", java.util.UUID.randomUUID().toString()),
                name = json.optString("name", ""),
                enabled = json.optBoolean("enabled", true),
                trigger = json.optJSONObject("trigger")?.let { AutomationTrigger.fromJson(it) }
                    ?: AutomationTrigger.Schedule(),
                conditions = json.optJSONObject("conditions")?.let { AutomationConditions.fromJson(it) }
                    ?: AutomationConditions(),
                actions = actionsList,
                executionMode = runCatching { ExecutionMode.valueOf(json.optString("executionMode")) }
                    .getOrDefault(ExecutionMode.SEQUENTIAL),
                retryConfig = json.optJSONObject("retryConfig")?.let { RetryConfig.fromJson(it) } ?: RetryConfig(),
                cooldownMillis = json.optLong("cooldownMillis", 0L),
                order = json.optInt("order", 0),
                state = runCatching { AutomationState.valueOf(json.optString("state")) }
                    .getOrDefault(AutomationState.PENDING),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                lastExecutedAt = if (json.isNull("lastExecutedAt")) null else json.optLong("lastExecutedAt"),
                nextScheduledTime = if (json.isNull("nextScheduledTime")) null else json.optLong("nextScheduledTime"),
                executionCount = json.optInt("executionCount", 0),
                retryCount = json.optInt("retryCount", 0),
            )
        }
    }
}

/**
 * 失败重试配置
 */
data class RetryConfig(
    val mode: RetryMode = RetryMode.NO_RETRY,
    val maxRetries: Int = 3,
    val delayMillis: Long = 60_000L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.name)
        put("maxRetries", maxRetries)
        put("delayMillis", delayMillis)
    }

    companion object {
        fun fromJson(json: JSONObject): RetryConfig = RetryConfig(
            mode = runCatching { RetryMode.valueOf(json.optString("mode")) }.getOrDefault(RetryMode.NO_RETRY),
            maxRetries = json.optInt("maxRetries", 3),
            delayMillis = json.optLong("delayMillis", 60_000L),
        )
    }
}