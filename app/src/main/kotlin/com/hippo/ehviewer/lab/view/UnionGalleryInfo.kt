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

package com.hippo.ehviewer.lab.view

import androidx.annotation.NonNull
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.lab.snapshot.GallerySnapshotEntry

/**
 * 联合画廊条目（UI 层使用）
 *
 * 与本地 [GalleryInfo] 相比，额外携带：
 * <ul>
 *   <li>[source] 来源设备（local / 某个具体 peer）</li>
 *   <li>[localExists] 本机是否已下载</li>
 *   <li>[unionDownloadedPages] 跨设备最大已下载页数</li>
 *   <li>[unionPages] 跨设备最大总页数</li>
 * </ul>
 *
 * UI 层可以基于本类直接渲染来源徽标 + 跨设备进度，无需感知 LabManager。
 */
class UnionGalleryInfo private constructor(builder: Builder) : GalleryInfo() {

    @NonNull val source: Source = builder.source
    val localExists: Boolean = builder.localExists
    @JvmField val unionDownloadedPages: Int = builder.unionDownloadedPages
    @JvmField val unionPages: Int = builder.unionPages

    init {
        gid = builder.gid
        token = builder.token
        title = builder.title
        titleJpn = builder.titleJpn
        thumb = builder.thumb
        category = builder.category
        posted = builder.posted
        uploader = builder.uploader
        rating = builder.rating
        simpleLanguage = builder.simpleLanguage
        simpleTags = builder.simpleTags
        pages = builder.pages
    }

    /**
     * 是否本地已下载（state==3 FINISH）。
     */
    fun isLocalComplete(): Boolean = localExists

    /**
     * 跨设备 union 已下载页数。
     */
    fun getUnionDownloadedPages(): Int = unionDownloadedPages

    /**
     * 跨设备 union 总页数。
     */
    fun getUnionPages(): Int = unionPages

    /**
     * 来源设备 id（local 表示本机）。
     */
    @NonNull
    fun getSourceDeviceId(): String = source.deviceId

    /**
     * 来源设备展示名。
     */
    @NonNull
    fun getSourceDeviceName(): String = source.deviceName

    /**
     * 是否有任何设备完整下载。
     */
    fun isUnionComplete(): Boolean = unionPages > 0 && unionDownloadedPages >= unionPages

    /**
     * 来源描述（用于徽标 tooltip）。
     */
    fun getSourceHint(): String? =
        if (source.deviceId == "local") "本机"
        else "${source.deviceName} (${source.deviceId.substring(0, kotlin.math.min(8, source.deviceId.length))})"

    companion object {
        /**
         * 构造：纯本地画廊。
         */
        @JvmStatic
        fun fromLocal(@NonNull local: GalleryInfo, downloadedPages: Int, pages: Int): UnionGalleryInfo {
            val b = Builder()
                .gid(local.gid)
                .token(local.token)
                .title(local.title)
                .titleJpn(local.titleJpn)
                .thumb(local.thumb)
                .category(local.category)
                .posted(local.posted)
                .uploader(local.uploader)
                .rating(local.rating)
                .simpleLanguage(local.simpleLanguage)
                .simpleTags(local.simpleTags)
                .pages(if (pages > 0) pages else local.pages)
                .source(Source("local", "本机"))
                .localExists(true)
                .unionDownloadedPages(downloadedPages)
                .unionPages(if (pages > 0) pages else local.pages)
            return b.build()
        }

        /**
         * 构造：纯远端画廊（本地没有该画廊）。
         */
        @JvmStatic
        fun fromRemote(@NonNull entry: GallerySnapshotEntry): UnionGalleryInfo {
            val b = Builder()
                .gid(entry.gid)
                .token(if (entry.token != null) entry.token!! else "")
                .title(if (entry.title != null) entry.title!! else "(未知标题)")
                .titleJpn(entry.titleJpn)
                .thumb(entry.thumbBase64)
                .category(entry.category)
                .posted(entry.posted)
                .uploader(entry.uploader)
                .rating(entry.rating)
                .simpleLanguage(entry.language)
                .pages(entry.pages)
                .source(Source(entry.sourceDeviceId,
                    entry.sourceDeviceName ?: entry.sourceDeviceId))
                .localExists(false)
                .unionDownloadedPages(entry.downloadedPages)
                .unionPages(entry.pages)
            if (!entry.tags.isEmpty()) {
                b.simpleTags(entry.tags.toTypedArray())
            }
            return b.build()
        }
    }

    class Source(
        @NonNull val deviceId: String,
        @NonNull val deviceName: String,
    )

    class Builder {
        var gid: Long = 0
            private set
        var token: String? = null
            private set
        var title: String? = null
            private set
        var titleJpn: String? = null
            private set
        var thumb: String? = null
            private set
        var category: Int = 0
            private set
        var posted: String? = null
            private set
        var uploader: String? = null
            private set
        var rating: Float = 0f
            private set
        var simpleLanguage: String? = null
            private set
        var simpleTags: Array<String>? = null
            private set
        var pages: Int = 0
            private set
        var source: Source = Source("local", "本机")
            private set
        var localExists: Boolean = false
            private set
        var unionDownloadedPages: Int = 0
            private set
        var unionPages: Int = 0
            private set

        fun gid(v: Long) = apply { gid = v }
        fun token(v: String?) = apply { token = v }
        fun title(v: String?) = apply { title = v }
        fun titleJpn(v: String?) = apply { titleJpn = v }
        fun thumb(v: String?) = apply { thumb = v }
        fun category(v: Int) = apply { category = v }
        fun posted(v: String?) = apply { posted = v }
        fun uploader(v: String?) = apply { uploader = v }
        fun rating(v: Float) = apply { rating = v }
        fun simpleLanguage(v: String?) = apply { simpleLanguage = v }
        fun simpleTags(v: Array<String>?) = apply { simpleTags = v }
        fun pages(v: Int) = apply { pages = v }
        fun source(v: Source) = apply { source = v }
        fun localExists(v: Boolean) = apply { localExists = v }
        fun unionDownloadedPages(v: Int) = apply { unionDownloadedPages = v }
        fun unionPages(v: Int) = apply { unionPages = v }

        fun build(): UnionGalleryInfo = UnionGalleryInfo(this)
    }
}