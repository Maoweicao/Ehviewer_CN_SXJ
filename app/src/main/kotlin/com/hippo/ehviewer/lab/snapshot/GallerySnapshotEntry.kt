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
 * 画廊快照条目
 *
 * 对应协议 §5.22.1 中的 [GallerySnapshotEntry]。
 *
 * 每个条目代表某设备在某时间点上的画廊目录元数据快照。
 * 同 gid 的不同设备条目可以共存（联合视图场景）。
 */
class GallerySnapshotEntry private constructor(builder: Builder) {

    val gid: Long = builder.gid
    @Nullable val token: String? = builder.token
    @Nullable val title: String? = builder.title
    @Nullable val titleJpn: String? = builder.titleJpn
    @Nullable val thumbBase64: String? = builder.thumbBase64
    val category: Int = builder.category
    @Nullable val posted: String? = builder.posted
    @Nullable val uploader: String? = builder.uploader
    val rating: Float = builder.rating
    val pages: Int = builder.pages
    val downloadedPages: Int = builder.downloadedPages
    val fileCount: Int = builder.fileCount
    val state: Int = builder.state
    @Nullable val label: String? = builder.label
    @Nullable val language: String? = builder.language
    @NonNull val tags: List<String> = ArrayList(builder.tags)
    @NonNull val sourceDeviceId: String = builder.sourceDeviceId
    @Nullable val sourceDeviceName: String? = builder.sourceDeviceName
    val lastUpdated: Long = builder.lastUpdated
    val deleted: Boolean = builder.deleted

    /**
     * 是否已完成下载（state == 3 / FINISH）。
     */
    fun isComplete(): Boolean = state == 3 || (pages > 0 && downloadedPages >= pages)

    fun toJson(): JSONObject {
        val json = JSONObject()
        json["gid"] = gid
        if (token != null) json["token"] = token
        if (title != null) json["title"] = title
        if (titleJpn != null) json["titleJpn"] = titleJpn
        if (thumbBase64 != null) json["thumbBase64"] = thumbBase64
        json["category"] = category
        if (posted != null) json["posted"] = posted
        if (uploader != null) json["uploader"] = uploader
        json["rating"] = rating
        json["pages"] = pages
        json["downloadedPages"] = downloadedPages
        json["fileCount"] = fileCount
        json["state"] = state
        if (label != null) json["label"] = label
        if (language != null) json["language"] = language
        val arr = JSONArray()
        arr.addAll(tags)
        json["tags"] = arr
        json["sourceDeviceId"] = sourceDeviceId
        if (sourceDeviceName != null) json["sourceDeviceName"] = sourceDeviceName
        json["lastUpdated"] = lastUpdated
        json["deleted"] = deleted
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject?): GallerySnapshotEntry? {
            if (json == null) return null
            return try {
                Builder()
                    .gid(json.getLongValue("gid"))
                    .category(json.getIntValue("category"))
                    .rating(json.getFloatValue("rating"))
                    .pages(json.getIntValue("pages"))
                    .downloadedPages(json.getIntValue("downloadedPages"))
                    .fileCount(json.getIntValue("fileCount"))
                    .state(json.getIntValue("state"))
                    .sourceDeviceId(json.getString("sourceDeviceId"))
                    .lastUpdated(json.getLongValue("lastUpdated"))
                    .deleted(json.getBooleanValue("deleted"))
                    .apply {
                        if (json.containsKey("token")) token(json.getString("token"))
                        if (json.containsKey("title")) title(json.getString("title"))
                        if (json.containsKey("titleJpn")) titleJpn(json.getString("titleJpn"))
                        if (json.containsKey("thumbBase64")) thumbBase64(json.getString("thumbBase64"))
                        if (json.containsKey("posted")) posted(json.getString("posted"))
                        if (json.containsKey("uploader")) uploader(json.getString("uploader"))
                        if (json.containsKey("label")) label(json.getString("label"))
                        if (json.containsKey("language")) language(json.getString("language"))
                        if (json.containsKey("sourceDeviceName"))
                            sourceDeviceName(json.getString("sourceDeviceName"))
                        val arr = json.getJSONArray("tags")
                        if (arr != null) {
                            val tags = ArrayList<String>()
                            for (i in 0 until arr.size) {
                                val s = arr.getString(i)
                                if (s != null && !s.isEmpty()) tags.add(s)
                            }
                            tags(tags)
                        }
                    }
                    .build()
            } catch (e: Exception) {
                null
            }
        }
    }

    class Builder {
        var gid: Long = 0
            private set
        var token: String? = null
            private set
        var title: String? = null
            private set
        var titleJpn: String? = null
            private set
        var thumbBase64: String? = null
            private set
        var category: Int = 0
            private set
        var posted: String? = null
            private set
        var uploader: String? = null
            private set
        var rating: Float = 0f
            private set
        var pages: Int = 0
            private set
        var downloadedPages: Int = 0
            private set
        var fileCount: Int = 0
            private set
        var state: Int = 0
            private set
        var label: String? = null
            private set
        var language: String? = null
            private set
        var tags: List<String> = ArrayList()
            private set
        var sourceDeviceId: String = ""
            private set
        var sourceDeviceName: String? = null
            private set
        var lastUpdated: Long = System.currentTimeMillis()
            private set
        var deleted: Boolean = false
            private set

        fun gid(v: Long) = apply { gid = v }
        fun token(v: String?) = apply { token = v }
        fun title(v: String?) = apply { title = v }
        fun titleJpn(v: String?) = apply { titleJpn = v }
        fun thumbBase64(v: String?) = apply { thumbBase64 = v }
        fun category(v: Int) = apply { category = v }
        fun posted(v: String?) = apply { posted = v }
        fun uploader(v: String?) = apply { uploader = v }
        fun rating(v: Float) = apply { rating = v }
        fun pages(v: Int) = apply { pages = v }
        fun downloadedPages(v: Int) = apply { downloadedPages = v }
        fun fileCount(v: Int) = apply { fileCount = v }
        fun state(v: Int) = apply { state = v }
        fun label(v: String?) = apply { label = v }
        fun language(v: String?) = apply { language = v }
        fun tags(v: List<String>) = apply { tags = ArrayList(v) }
        fun sourceDeviceId(v: String) = apply { sourceDeviceId = v }
        fun sourceDeviceName(v: String?) = apply { sourceDeviceName = v }
        fun lastUpdated(v: Long) = apply { lastUpdated = v }
        fun deleted(v: Boolean) = apply { deleted = v }

        fun build(): GallerySnapshotEntry = GallerySnapshotEntry(this)
    }
}