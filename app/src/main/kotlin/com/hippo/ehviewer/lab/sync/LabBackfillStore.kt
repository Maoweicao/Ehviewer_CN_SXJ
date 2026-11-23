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

package com.hippo.ehviewer.lab.sync

import android.content.Context

/**
 * 图片补齐断点状态。
 *
 * 只持久化「还没补齐完的 gid」；具体哪些页已完成以本地文件为准，
 * 因此进程被杀/手动停止后，下次会从缺失页继续，而不是从头再来。
 */
object LabBackfillStore {

    private const val PREFS = "lab_backfill"
    private const val KEY_PENDING = "pending_gids"

    @Synchronized
    @JvmStatic
    fun add(context: Context, gids: Collection<Long>) {
        if (gids.isEmpty()) return
        val prefs = prefs(context)
        val set = HashSet(prefs.getStringSet(KEY_PENDING, emptySet()) ?: emptySet())
        for (gid in gids) if (gid > 0) set.add(gid.toString())
        prefs.edit().putStringSet(KEY_PENDING, set).apply()
    }

    @Synchronized
    fun remove(context: Context, gid: Long) {
        val prefs = prefs(context)
        val set = HashSet(prefs.getStringSet(KEY_PENDING, emptySet()) ?: emptySet())
        if (set.remove(gid.toString())) {
            prefs.edit().putStringSet(KEY_PENDING, set).apply()
        }
    }

    @Synchronized
    @JvmStatic
    fun pending(context: Context): List<Long> {
        val set = prefs(context).getStringSet(KEY_PENDING, emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.filter { it > 0 }.sorted()
    }

    @Synchronized
    fun hasPending(context: Context): Boolean =
        !(prefs(context).getStringSet(KEY_PENDING, emptySet()) ?: emptySet()).isEmpty()

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
