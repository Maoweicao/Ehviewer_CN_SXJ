/*
 * Copyright 2026 EhViewer Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.limit

import android.content.Context
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

/**
 * 一次下载会话的速率记录。
 */
data class LimitSession(
    val gid: Long,
    val title: String?,
    val startedAt: Long,
    val endedAt: Long,
    val bytes: Long,
    val durationMs: Long,
    val avgSpeed: Long,
    val peakSpeed: Long,
    val pages: Int,
    val stopReason: String?,
)

/**
 * 限流状态与下载会话历史的持久化。
 */
object RemoteLimitStore {

    private const val PREFS = "remote_limit"
    private const val KEY_BLOCKED_UNTIL = "blocked_until"
    private const val KEY_REASON = "reason"
    private const val KEY_SOURCE = "source"
    private const val KEY_EVIDENCE = "evidence"
    private const val KEY_DETECTED_AT = "detected_at"
    private const val KEY_HISTORY = "history"
    private const val KEY_LAST_IP = "last_ip"
    private const val KEY_FORUM_PROBE_CODE = "forum_probe_code"
    private const val KEY_FORUM_PROBE_CLOUDFLARE = "forum_probe_cloudflare"
    private const val KEY_FORUM_PROBE_BANNED = "forum_probe_banned"
    private const val KEY_FORUM_PROBE_CONTENT = "forum_probe_content"
    private const val KEY_FORUM_PROBE_AT = "forum_probe_at"
    private const val KEY_SETTINGS_PROBE_CODE = "settings_probe_code"
    private const val KEY_SETTINGS_PROBE_CLOUDFLARE = "settings_probe_cloudflare"
    private const val KEY_SETTINGS_PROBE_BANNED = "settings_probe_banned"
    private const val KEY_SETTINGS_PROBE_CONTENT = "settings_probe_content"
    private const val KEY_SETTINGS_PROBE_AT = "settings_probe_at"
    private const val MAX_HISTORY = 20

    @JvmStatic
    fun setBlocked(
        context: Context,
        untilMs: Long,
        reason: String,
        source: String?,
        evidence: String?,
        detectedAt: Long = System.currentTimeMillis(),
    ) {
        prefs(context).edit()
            .putLong(KEY_BLOCKED_UNTIL, untilMs)
            .putString(KEY_REASON, reason)
            .putString(KEY_SOURCE, source)
            .putString(KEY_EVIDENCE, evidence?.take(800))
            .putLong(KEY_DETECTED_AT, detectedAt)
            .apply()
    }

    @JvmStatic
    fun getBlockedUntil(context: Context): Long =
        prefs(context).getLong(KEY_BLOCKED_UNTIL, 0L)

    @JvmStatic
    fun getReason(context: Context): String? =
        prefs(context).getString(KEY_REASON, null)

    @JvmStatic
    fun getSource(context: Context): String? =
        prefs(context).getString(KEY_SOURCE, null)

    @JvmStatic
    fun getEvidence(context: Context): String? =
        prefs(context).getString(KEY_EVIDENCE, null)

    @JvmStatic
    fun getDetectedAt(context: Context): Long =
        prefs(context).getLong(KEY_DETECTED_AT, 0L)

    @JvmStatic
    fun clearBlock(context: Context) {
        // 保留 last_ip：下次再次被限流时仍可和当前 IP 对比。
        prefs(context).edit()
            .remove(KEY_BLOCKED_UNTIL)
            .remove(KEY_REASON)
            .remove(KEY_SOURCE)
            .remove(KEY_EVIDENCE)
            .remove(KEY_DETECTED_AT)
            .remove(KEY_FORUM_PROBE_CODE)
            .remove(KEY_FORUM_PROBE_CLOUDFLARE)
            .remove(KEY_FORUM_PROBE_BANNED)
            .remove(KEY_FORUM_PROBE_CONTENT)
            .remove(KEY_FORUM_PROBE_AT)
            .remove(KEY_SETTINGS_PROBE_CODE)
            .remove(KEY_SETTINGS_PROBE_CLOUDFLARE)
            .remove(KEY_SETTINGS_PROBE_BANNED)
            .remove(KEY_SETTINGS_PROBE_CONTENT)
            .remove(KEY_SETTINGS_PROBE_AT)
            .apply()
    }

    // ==================== 限流时的公网 IP 记录 ====================

    @JvmStatic
    fun getLastIp(context: Context): String? =
        prefs(context).getString(KEY_LAST_IP, null)

    @JvmStatic
    fun setLastIp(context: Context, ip: String?) {
        val editor = prefs(context).edit()
        if (ip.isNullOrBlank()) {
            editor.remove(KEY_LAST_IP)
        } else {
            editor.putString(KEY_LAST_IP, ip.trim().take(64))
        }
        editor.apply()
    }

    // ==================== 限流时的论坛探测结果 ====================

    @JvmStatic
    fun setForumProbe(
        context: Context,
        code: Int,
        cloudflare: Boolean,
        banned: Boolean,
        content: String?,
    ) {
        prefs(context).edit()
            .putInt(KEY_FORUM_PROBE_CODE, code)
            .putBoolean(KEY_FORUM_PROBE_CLOUDFLARE, cloudflare)
            .putBoolean(KEY_FORUM_PROBE_BANNED, banned)
            .putString(KEY_FORUM_PROBE_CONTENT, content?.take(600))
            .putLong(KEY_FORUM_PROBE_AT, System.currentTimeMillis())
            .apply()
    }

    @JvmStatic
    fun getForumProbeCode(context: Context): Int =
        prefs(context).getInt(KEY_FORUM_PROBE_CODE, 0)

    @JvmStatic
    fun isForumProbeCloudflare(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORUM_PROBE_CLOUDFLARE, false)

    @JvmStatic
    fun isForumProbeBanned(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORUM_PROBE_BANNED, false)

    @JvmStatic
    fun getForumProbeContent(context: Context): String? =
        prefs(context).getString(KEY_FORUM_PROBE_CONTENT, null)

    @JvmStatic
    fun getForumProbeAt(context: Context): Long =
        prefs(context).getLong(KEY_FORUM_PROBE_AT, 0L)

    // ==================== 限流时的 EH 设置页探测结果 ====================

    @JvmStatic
    fun setSettingsProbe(
        context: Context,
        code: Int,
        cloudflare: Boolean,
        banned: Boolean,
        content: String?,
    ) {
        prefs(context).edit()
            .putInt(KEY_SETTINGS_PROBE_CODE, code)
            .putBoolean(KEY_SETTINGS_PROBE_CLOUDFLARE, cloudflare)
            .putBoolean(KEY_SETTINGS_PROBE_BANNED, banned)
            .putString(KEY_SETTINGS_PROBE_CONTENT, content?.take(600))
            .putLong(KEY_SETTINGS_PROBE_AT, System.currentTimeMillis())
            .apply()
    }

    @JvmStatic
    fun getSettingsProbeCode(context: Context): Int =
        prefs(context).getInt(KEY_SETTINGS_PROBE_CODE, 0)

    @JvmStatic
    fun isSettingsProbeCloudflare(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETTINGS_PROBE_CLOUDFLARE, false)

    @JvmStatic
    fun isSettingsProbeBanned(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETTINGS_PROBE_BANNED, false)

    @JvmStatic
    fun getSettingsProbeContent(context: Context): String? =
        prefs(context).getString(KEY_SETTINGS_PROBE_CONTENT, null)

    @JvmStatic
    fun getSettingsProbeAt(context: Context): Long =
        prefs(context).getLong(KEY_SETTINGS_PROBE_AT, 0L)

    @JvmStatic
    @Synchronized
    fun addSession(context: Context, session: LimitSession) {
        val array = readHistoryArray(context)
        array.add(session.toJson())
        // 注意：Kotlin 对 Java 的 JSONArray 调用 array.remove(0) 会被解析成
        // remove(Object)，0 装箱成 Integer 后永远匹配不到元素，while 会死循环，
        // 直接卡死主线程并 ANR。必须用 removeAt(index) 才走 List.remove(int)。
        var overflow = array.size - MAX_HISTORY
        while (overflow > 0 && array.isNotEmpty()) {
            array.removeAt(0)
            overflow--
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toJSONString()).apply()
    }

    @JvmStatic
    @Synchronized
    fun getHistory(context: Context): List<LimitSession> {
        val array = readHistoryArray(context)
        val result = ArrayList<LimitSession>(array.size)
        for (i in array.indices) {
            val json = array.getJSONObject(i) ?: continue
            result.add(json.toSession() ?: continue)
        }
        return result
    }

    @JvmStatic
    fun getLastSession(context: Context): LimitSession? = getHistory(context).lastOrNull()

    private fun readHistoryArray(context: Context): JSONArray {
        val text = prefs(context).getString(KEY_HISTORY, null)
        if (text.isNullOrEmpty()) return JSONArray()
        return try {
            JSONArray.parseArray(text) ?: JSONArray()
        } catch (_: Throwable) {
            JSONArray()
        }
    }

    private fun LimitSession.toJson(): JSONObject = JSONObject().apply {
        put("gid", gid)
        put("title", title)
        put("startedAt", startedAt)
        put("endedAt", endedAt)
        put("bytes", bytes)
        put("durationMs", durationMs)
        put("avgSpeed", avgSpeed)
        put("peakSpeed", peakSpeed)
        put("pages", pages)
        put("stopReason", stopReason)
    }

    private fun JSONObject.toSession(): LimitSession? {
        return try {
            LimitSession(
                getLongValue("gid"),
                getString("title"),
                getLongValue("startedAt"),
                getLongValue("endedAt"),
                getLongValue("bytes"),
                getLongValue("durationMs"),
                getLongValue("avgSpeed"),
                getLongValue("peakSpeed"),
                getIntValue("pages"),
                getString("stopReason"),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
