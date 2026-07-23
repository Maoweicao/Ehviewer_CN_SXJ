package com.hippo.ehviewer.task.scheduled

import org.json.JSONObject

/**
 * 重试配置
 */
data class RetryConfig(
    val mode: RetryMode = RetryMode.NO_RETRY,
    val maxRetries: Int = 3,
    val delayMillis: Long = 60_000  // 延迟重试间隔（毫秒）
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("mode", mode.name)
            put("maxRetries", maxRetries)
            put("delayMillis", delayMillis)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): RetryConfig {
            return RetryConfig(
                mode = try {
                    RetryMode.valueOf(json.optString("mode", RetryMode.NO_RETRY.name))
                } catch (e: Exception) {
                    RetryMode.NO_RETRY
                },
                maxRetries = json.optInt("maxRetries", 3),
                delayMillis = json.optLong("delayMillis", 60_000)
            )
        }
    }
}
