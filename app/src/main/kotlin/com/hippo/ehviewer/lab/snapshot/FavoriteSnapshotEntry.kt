/*
 * Copyright 2025 EhViewer Contributors
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

package com.hippo.ehviewer.lab.snapshot

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.alibaba.fastjson.JSONObject

/**
 * 收藏快照条目
 *
 * 对应协议 §5.22.1 中的 favorites 数组。
 */
class FavoriteSnapshotEntry(
    @NonNull val gid: Long,
    @Nullable val favCat: String?,
    @Nullable val favNote: String?,
    val lastUpdated: Long,
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json["gid"] = gid
        if (favCat != null) json["favCat"] = favCat
        if (favNote != null) json["favNote"] = favNote
        json["lastUpdated"] = lastUpdated
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject?): FavoriteSnapshotEntry? {
            if (json == null) return null
            return try {
                val gid = json.getLongValue("gid")
                val cat = json.getString("favCat")
                val note = json.getString("favNote")
                var ts = json.getLongValue("lastUpdated")
                if (ts == 0L) ts = System.currentTimeMillis()
                FavoriteSnapshotEntry(gid, cat, note, ts)
            } catch (e: Exception) {
                null
            }
        }
    }

    @NonNull
    override fun toString(): String =
        "FavoriteSnapshotEntry{gid=$gid, cat=$favCat}"
}