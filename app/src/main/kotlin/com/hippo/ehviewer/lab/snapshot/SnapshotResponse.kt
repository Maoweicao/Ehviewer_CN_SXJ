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
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

/**
 * 快照响应（来自 §5.22.1 GET /api/v1/lab/db/snapshot）
 *
 * 与协议字段一一对应。
 */
class SnapshotResponse private constructor(builder: Builder) {

    val success: Boolean = builder.success
    val version: Long = builder.version
    @NonNull val deviceId: String = builder.deviceId
    @NonNull val deviceName: String = builder.deviceName
    val snapshotTime: Long = builder.snapshotTime
    val hasMore: Boolean = builder.hasMore
    @Nullable val nextCursor: String? = builder.nextCursor
    @NonNull val galleries: List<GallerySnapshotEntry> = ArrayList(builder.galleries)
    @NonNull val removedGids: List<Long> = ArrayList(builder.removedGids)
    @NonNull val favorites: List<FavoriteSnapshotEntry> = ArrayList(builder.favorites)

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject?): SnapshotResponse? {
            if (json == null) return null
            val b = Builder()
                .success(json.getBooleanValue("success"))
                .version(json.getLongValue("version"))
                .deviceId(json.getString("deviceId"))
                .deviceName(json.getString("deviceName"))
                .snapshotTime(json.getLongValue("snapshotTime"))
                .hasMore(json.getBooleanValue("hasMore"))

            if (json.containsKey("nextCursor")) b.nextCursor(json.getString("nextCursor"))

            val arr = json.getJSONArray("galleries")
            if (arr != null) {
                val list = ArrayList<GallerySnapshotEntry>()
                for (i in 0 until arr.size) {
                    val e = GallerySnapshotEntry.fromJson(arr.getJSONObject(i))
                    if (e != null) list.add(e)
                }
                b.galleries(list)
            }

            val removed = json.getJSONArray("removedGids")
            if (removed != null) {
                val list = ArrayList<Long>()
                for (i in 0 until removed.size) list.add(removed.getLongValue(i))
                b.removedGids(list)
            }

            val favs = json.getJSONArray("favorites")
            if (favs != null) {
                val list = ArrayList<FavoriteSnapshotEntry>()
                for (i in 0 until favs.size) {
                    val e = FavoriteSnapshotEntry.fromJson(favs.getJSONObject(i))
                    if (e != null) list.add(e)
                }
                b.favorites(list)
            }

            return b.build()
        }
    }

    class Builder {
        var success: Boolean = true
            private set
        var version: Long = 0
            private set
        var deviceId: String = ""
            private set
        var deviceName: String = ""
            private set
        var snapshotTime: Long = 0
            private set
        var hasMore: Boolean = false
            private set
        var nextCursor: String? = null
            private set
        var galleries: List<GallerySnapshotEntry> = ArrayList()
            private set
        var removedGids: List<Long> = ArrayList()
            private set
        var favorites: List<FavoriteSnapshotEntry> = ArrayList()
            private set

        fun success(v: Boolean) = apply { success = v }
        fun version(v: Long) = apply { version = v }
        fun deviceId(v: String) = apply { deviceId = v }
        fun deviceName(v: String) = apply { deviceName = v }
        fun snapshotTime(v: Long) = apply { snapshotTime = v }
        fun hasMore(v: Boolean) = apply { hasMore = v }
        fun nextCursor(v: String?) = apply { nextCursor = v }
        fun galleries(v: List<GallerySnapshotEntry>) = apply { galleries = ArrayList(v) }
        fun removedGids(v: List<Long>) = apply { removedGids = ArrayList(v) }
        fun favorites(v: List<FavoriteSnapshotEntry>) = apply { favorites = ArrayList(v) }

        fun build(): SnapshotResponse = SnapshotResponse(this)
    }
}