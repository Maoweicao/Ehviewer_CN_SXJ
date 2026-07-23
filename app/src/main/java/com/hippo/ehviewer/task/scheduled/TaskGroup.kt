package com.hippo.ehviewer.task.scheduled

import org.json.JSONObject

/**
 * 任务组
 */
data class TaskGroup(
    val groupId: String,                    // 组 ID
    val groupName: String,                  // 组名称
    val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("groupId", groupId)
            put("groupName", groupName)
            put("executionMode", executionMode.name)
        }
    }

    companion object {
        const val DEFAULT_GROUP_ID = "default"

        fun fromJson(json: JSONObject): TaskGroup {
            return TaskGroup(
                groupId = json.optString("groupId", DEFAULT_GROUP_ID),
                groupName = json.optString("groupName", "默认组"),
                executionMode = try {
                    ExecutionMode.valueOf(json.optString("executionMode", ExecutionMode.SEQUENTIAL.name))
                } catch (e: Exception) {
                    ExecutionMode.SEQUENTIAL
                }
            )
        }

        fun createDefault(): TaskGroup {
            return TaskGroup(
                groupId = DEFAULT_GROUP_ID,
                groupName = "默认组",
                executionMode = ExecutionMode.SEQUENTIAL
            )
        }
    }
}
