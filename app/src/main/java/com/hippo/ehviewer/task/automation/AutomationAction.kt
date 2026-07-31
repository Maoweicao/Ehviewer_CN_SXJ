package com.hippo.ehviewer.task.automation

import org.json.JSONObject

/**
 * 单个动作（一个 BackgroundTask）
 */
data class AutomationAction(
    val id: String,
    val taskClassName: String,
    val displayName: String,
    val taskType: String = "",
    val params: String? = null,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("taskClassName", taskClassName)
        put("displayName", displayName)
        put("taskType", taskType)
        put("params", params ?: JSONObject.NULL)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(json: JSONObject): AutomationAction = AutomationAction(
            id = json.optString("id", java.util.UUID.randomUUID().toString()),
            taskClassName = json.optString("taskClassName", ""),
            displayName = json.optString("displayName", ""),
            taskType = json.optString("taskType", ""),
            params = if (json.isNull("params")) null else json.optString("params", null),
            enabled = json.optBoolean("enabled", true),
        )
    }
}