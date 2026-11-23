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

package com.hippo.ehviewer.download

import android.content.Context

/**
 * 批次下载的运行状态。
 *
 * 只保存「是否处于批次模式、当前批还剩几个、冷却到什么时候」，
 * 待下载列表本身仍以 DOWNLOADS 表的 STATE_WAIT 为准，
 * 因此进程被杀后重新进入应用可以继续而不是从头开始。
 */
object BatchDownloadStore {

    private const val PREFS = "download_batch"
    private const val KEY_ACTIVE = "active"
    private const val KEY_REMAINING = "remaining"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until"

    @JvmStatic
    fun isActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    @JvmStatic
    fun start(context: Context, remaining: Int) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_REMAINING, remaining.coerceAtLeast(1))
            .putLong(KEY_COOLDOWN_UNTIL, 0L)
            .apply()
    }

    @JvmStatic
    fun getRemaining(context: Context): Int = prefs(context).getInt(KEY_REMAINING, 0)

    @JvmStatic
    fun setRemaining(context: Context, remaining: Int) {
        prefs(context).edit().putInt(KEY_REMAINING, remaining.coerceAtLeast(0)).apply()
    }

    @JvmStatic
    fun getCooldownUntil(context: Context): Long = prefs(context).getLong(KEY_COOLDOWN_UNTIL, 0L)

    @JvmStatic
    fun setCooldownUntil(context: Context, timeMs: Long) {
        prefs(context).edit().putLong(KEY_COOLDOWN_UNTIL, timeMs).apply()
    }

    @JvmStatic
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
