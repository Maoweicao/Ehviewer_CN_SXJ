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
 * 同步元数据里的页数缓存。
 *
 * `DownloadInfo.pages` 是 @Ignore 字段，不落库；同步只写 DOWNLOADS 行时页数会丢。
 * 这里按 gid 持久化同步得到的页数，供本地完整性检查和图片补齐使用。
 */
object LabPageStore {

    private const val PREFS = "lab_page_index"
    private const val PREFIX = "pages_"

    @JvmStatic
    @Synchronized
    fun putAll(context: Context, pages: Map<Long, Int>) {
        if (pages.isEmpty()) return
        val editor = prefs(context).edit()
        for ((gid, value) in pages) {
            if (gid > 0 && value > 0) editor.putInt("$PREFIX$gid", value)
        }
        editor.apply()
    }

    @JvmStatic
    @Synchronized
    fun put(context: Context, gid: Long, pages: Int) {
        if (gid <= 0 || pages <= 0) return
        prefs(context).edit().putInt("$PREFIX$gid", pages).apply()
    }

    @JvmStatic
    @Synchronized
    fun get(context: Context, gid: Long): Int =
        prefs(context).getInt("$PREFIX$gid", 0)

    @JvmStatic
    @Synchronized
    fun remove(context: Context, gid: Long) {
        prefs(context).edit().remove("$PREFIX$gid").apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
