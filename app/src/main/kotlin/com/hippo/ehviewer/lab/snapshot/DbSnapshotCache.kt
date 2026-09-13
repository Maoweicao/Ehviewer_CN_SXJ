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

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.hippo.ehviewer.lab.log.SnapshotLogger

/**
 * 实验室快照本地缓存
 *
 * 协议对应：v3.0 §5.22。
 *
 * 数据流：
 * <ul>
 *   <li>远端 → 本地：通过 [applyResponse] 写入同步结果</li>
 *   <li>本地 → UI：通过 [queryByGid] / [querySince] / [aggregateByGid] 读取</li>
 * </ul>
 *
 * 同步冲突策略（§5.19.3 决策）：[lastUpdated] 胜出。[upsertEntry] 总是写入最新版本。
 */
class DbSnapshotCache private constructor(private val appContext: Context) {

    private val dbHelper = SnapshotDbHelper(appContext)

    /**
     * 应用同步响应（来自 §5.22.1）。
     * 单次事务，包含：
     * <ol>
     *   <li>upsert 所有 galleries 条目（lastUpdated 冲突时取最新）</li>
     *   <li>应用 removedGids（按 source_device_id 删除）</li>
     *   <li>upsert 所有 favorites</li>
     *   <li>更新 last_version / last_sync_at</li>
     * </ol>
     *
     * @return 写入的画廊条目数
     */
    fun applyResponse(resp: SnapshotResponse): Int {
        if (!resp.success) return 0
        val db = dbHelper.writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (e in resp.galleries) {
                upsertEntry(db, e)
                count++
            }
            for (gid in resp.removedGids) {
                markRemoved(db, gid, resp.deviceId)
            }
            for (fav in resp.favorites) {
                upsertFavorite(db, fav)
            }
            putMeta(db, SnapshotDbHelper.META_KEY_LAST_VERSION, resp.version.toString())
            putMeta(db, SnapshotDbHelper.META_KEY_LAST_SYNC_AT, System.currentTimeMillis().toString())
            putMeta(db, SnapshotDbHelper.META_KEY_LAST_DEVICE_ID, resp.deviceId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        SnapshotLogger.i(
            "DbSnapshotCache",
            "Applied snapshot: $count galleries, ${resp.removedGids.size} removed, " +
                "${resp.favorites.size} favorites, version=${resp.version}",
        )
        return count
    }

    private fun upsertEntry(db: android.database.sqlite.SQLiteDatabase, e: GallerySnapshotEntry) {
        // Conflict strategy: only overwrite if new lastUpdated >= old lastUpdated
        val existing = readLastUpdated(db, e.gid, e.sourceDeviceId)
        if (existing != null && existing > e.lastUpdated) {
            SnapshotLogger.d(
                "DbSnapshotCache",
                "Skip stale entry: gid=${e.gid}, device=${e.sourceDeviceId}, " +
                    "new=${e.lastUpdated} < old=$existing",
            )
            return
        }

        val cv = ContentValues()
        cv.put(SnapshotDbHelper.COL_GID, e.gid)
        e.token?.let { cv.put(SnapshotDbHelper.COL_TOKEN, it) }
        e.title?.let { cv.put(SnapshotDbHelper.COL_TITLE, it) }
        e.titleJpn?.let { cv.put(SnapshotDbHelper.COL_TITLE_JPN, it) }
        e.thumbBase64?.let { cv.put(SnapshotDbHelper.COL_THUMB_BASE64, it) }
        cv.put(SnapshotDbHelper.COL_CATEGORY, e.category)
        e.posted?.let { cv.put(SnapshotDbHelper.COL_POSTED, it) }
        e.uploader?.let { cv.put(SnapshotDbHelper.COL_UPLOADER, it) }
        cv.put(SnapshotDbHelper.COL_RATING, e.rating)
        cv.put(SnapshotDbHelper.COL_PAGES, e.pages)
        cv.put(SnapshotDbHelper.COL_DOWNLOADED_PAGES, e.downloadedPages)
        cv.put(SnapshotDbHelper.COL_FILE_COUNT, e.fileCount)
        cv.put(SnapshotDbHelper.COL_STATE, e.state)
        e.label?.let { cv.put(SnapshotDbHelper.COL_LABEL, it) }
        e.language?.let { cv.put(SnapshotDbHelper.COL_LANGUAGE, it) }
        cv.put(SnapshotDbHelper.COL_TAGS_JSON, encodeTags(e.tags))
        cv.put(SnapshotDbHelper.COL_SOURCE_DEVICE_ID, e.sourceDeviceId)
        e.sourceDeviceName?.let { cv.put(SnapshotDbHelper.COL_SOURCE_DEVICE_NAME, it) }
        cv.put(SnapshotDbHelper.COL_LAST_UPDATED, e.lastUpdated)
        cv.put(SnapshotDbHelper.COL_DELETED, if (e.deleted) 1 else 0)

        db.replace(SnapshotDbHelper.TABLE_GALLERY, null, cv)
    }

    private fun markRemoved(
        db: android.database.sqlite.SQLiteDatabase,
        gid: Long,
        sourceDeviceId: String,
    ) {
        val cv = ContentValues()
        cv.put(SnapshotDbHelper.COL_DELETED, 1)
        cv.put(SnapshotDbHelper.COL_LAST_UPDATED, System.currentTimeMillis())
        val where = "${SnapshotDbHelper.COL_GID}=? AND ${SnapshotDbHelper.COL_SOURCE_DEVICE_ID}=?"
        db.update(
            SnapshotDbHelper.TABLE_GALLERY, cv, where,
            arrayOf(gid.toString(), sourceDeviceId),
        )
    }

    private fun upsertFavorite(
        db: android.database.sqlite.SQLiteDatabase,
        e: FavoriteSnapshotEntry,
    ) {
        val cv = ContentValues()
        cv.put(SnapshotDbHelper.COL_FAV_GID, e.gid)
        e.favCat?.let { cv.put(SnapshotDbHelper.COL_FAV_CAT, it) }
        e.favNote?.let { cv.put(SnapshotDbHelper.COL_FAV_NOTE, it) }
        cv.put(SnapshotDbHelper.COL_FAV_LAST_UPDATED, e.lastUpdated)
        db.replace(SnapshotDbHelper.TABLE_FAVORITES, null, cv)
    }

    private fun readLastUpdated(
        db: android.database.sqlite.SQLiteDatabase,
        gid: Long,
        sourceDeviceId: String,
    ): Long? {
        val c = db.query(
            SnapshotDbHelper.TABLE_GALLERY,
            arrayOf(SnapshotDbHelper.COL_LAST_UPDATED),
            "${SnapshotDbHelper.COL_GID}=? AND ${SnapshotDbHelper.COL_SOURCE_DEVICE_ID}=?",
            arrayOf(gid.toString(), sourceDeviceId),
            null, null, null, "1",
        )
        return try {
            if (c.moveToFirst()) c.getLong(0) else null
        } finally {
            c.close()
        }
    }

    private fun putMeta(
        db: android.database.sqlite.SQLiteDatabase,
        key: String,
        value: String,
    ) {
        val cv = ContentValues()
        cv.put(SnapshotDbHelper.COL_META_KEY, key)
        cv.put(SnapshotDbHelper.COL_META_VALUE, value)
        db.replace(SnapshotDbHelper.TABLE_META, null, cv)
    }

    // ==================== 查询 ====================

    /**
     * 按 gid 查询某画廊在所有设备的快照。
     */
    fun queryByGid(gid: Long): List<GallerySnapshotEntry> {
        val db = dbHelper.readableDatabase
        val out = ArrayList<GallerySnapshotEntry>()
        val c = db.query(
            SnapshotDbHelper.TABLE_GALLERY, null,
            "${SnapshotDbHelper.COL_GID}=? AND ${SnapshotDbHelper.COL_DELETED}=0",
            arrayOf(gid.toString()), null, null,
            "${SnapshotDbHelper.COL_LAST_UPDATED} DESC", null,
        )
        try {
            while (c.moveToNext()) {
                val e = readEntry(c)
                if (e != null) out.add(e)
            }
        } finally {
            c.close()
        }
        return out
    }

    /**
     * 查询 lastUpdated 大于 since 的所有条目。
     */
    fun querySince(since: Long, limit: Int): List<GallerySnapshotEntry> {
        val db = dbHelper.readableDatabase
        val out = ArrayList<GallerySnapshotEntry>()
        val c = db.query(
            SnapshotDbHelper.TABLE_GALLERY, null,
            "${SnapshotDbHelper.COL_LAST_UPDATED}>? AND ${SnapshotDbHelper.COL_DELETED}=0",
            arrayOf(since.toString()), null, null,
            "${SnapshotDbHelper.COL_LAST_UPDATED} DESC", limit.toString(),
        )
        try {
            while (c.moveToNext()) {
                val e = readEntry(c)
                if (e != null) out.add(e)
            }
        } finally {
            c.close()
        }
        return out
    }

    /**
     * 跨设备聚合：返回 gid → 该 gid 在所有设备的最新快照集合（按 sourceDeviceId 去重）。
     */
    fun aggregateByGid(): Map<Long, List<GallerySnapshotEntry>> {
        val db = dbHelper.readableDatabase
        val out = HashMap<Long, MutableList<GallerySnapshotEntry>>()
        val c = db.query(
            SnapshotDbHelper.TABLE_GALLERY, null,
            "${SnapshotDbHelper.COL_DELETED}=0",
            null, null, null, null, null,
        )
        try {
            while (c.moveToNext()) {
                val e = readEntry(c) ?: continue
                val list = out.getOrPut(e.gid) { ArrayList() }
                list.add(e)
            }
        } finally {
            c.close()
        }
        return out
    }

    /**
     * 获取已知的 sourceDeviceId 集合。
     */
    fun knownSourceDevices(): Set<String> {
        val db = dbHelper.readableDatabase
        val out = HashSet<String>()
        val c = db.query(
            true, SnapshotDbHelper.TABLE_GALLERY,
            arrayOf(SnapshotDbHelper.COL_SOURCE_DEVICE_ID),
            null, null, null, null, null, null,
        )
        try {
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (!id.isNullOrEmpty()) out.add(id)
            }
        } finally {
            c.close()
        }
        return out
    }

    /**
     * 上次同步的 version（-1 表示尚未同步）。
     */
    fun getLastVersion(): Long {
        val db = dbHelper.readableDatabase
        val v = getMeta(db, SnapshotDbHelper.META_KEY_LAST_VERSION) ?: return -1
        return try { v.toLong() } catch (e: NumberFormatException) { -1 }
    }

    fun getLastSyncAt(): Long {
        val db = dbHelper.readableDatabase
        val v = getMeta(db, SnapshotDbHelper.META_KEY_LAST_SYNC_AT) ?: return 0
        return try { v.toLong() } catch (e: NumberFormatException) { 0 }
    }

    private fun getMeta(
        db: android.database.sqlite.SQLiteDatabase,
        key: String,
    ): String? {
        val c = db.query(
            SnapshotDbHelper.TABLE_META,
            arrayOf(SnapshotDbHelper.COL_META_VALUE),
            "${SnapshotDbHelper.COL_META_KEY}=?",
            arrayOf(key), null, null, null, "1",
        )
        return try {
            if (c.moveToFirst()) c.getString(0) else null
        } finally {
            c.close()
        }
    }

    private fun readEntry(c: Cursor): GallerySnapshotEntry? {
        return try {
            GallerySnapshotEntry.Builder()
                .gid(c.getLong(c.getColumnIndexOrThrow(SnapshotDbHelper.COL_GID)))
                .category(c.getInt(c.getColumnIndexOrThrow(SnapshotDbHelper.COL_CATEGORY)))
                .rating(c.getFloat(c.getColumnIndexOrThrow(SnapshotDbHelper.COL_RATING)))
                .pages(c.getInt(c.getColumnIndexOrThrow(SnapshotDbHelper.COL_PAGES)))
                .downloadedPages(c.getInt(c.getColumnIndexOrThrow(SnapshotDbHelper.COL_DOWNLOADED_PAGES)))
                .fileCount(c.getInt(c.getColumnIndexOrThrow(SnapshotDbHelper.COL_FILE_COUNT)))
                .state(c.getInt(c.getColumnIndexOrThrow(SnapshotDbHelper.COL_STATE)))
                .sourceDeviceId(c.getString(c.getColumnIndexOrThrow(SnapshotDbHelper.COL_SOURCE_DEVICE_ID)))
                .lastUpdated(c.getLong(c.getColumnIndexOrThrow(SnapshotDbHelper.COL_LAST_UPDATED)))
                .deleted(c.getInt(c.getColumnIndexOrThrow(SnapshotDbHelper.COL_DELETED)) != 0)
                .apply {
                    val idxToken = c.getColumnIndexOrThrow(SnapshotDbHelper.COL_TOKEN)
                    if (!c.isNull(idxToken)) token(c.getString(idxToken))
                    val idxTitle = c.getColumnIndexOrThrow(SnapshotDbHelper.COL_TITLE)
                    if (!c.isNull(idxTitle)) title(c.getString(idxTitle))
                    val idxTitleJpn = c.getColumnIndexOrThrow(SnapshotDbHelper.COL_TITLE_JPN)
                    if (!c.isNull(idxTitleJpn)) titleJpn(c.getString(idxTitleJpn))
                    val idxThumb = c.getColumnIndexOrThrow(SnapshotDbHelper.COL_THUMB_BASE64)
                    if (!c.isNull(idxThumb)) thumbBase64(c.getString(idxThumb))
                    val idxPosted = c.getColumnIndexOrThrow(SnapshotDbHelper.COL_POSTED)
                    if (!c.isNull(idxPosted)) posted(c.getString(idxPosted))
                    val idxUploader = c.getColumnIndexOrThrow(SnapshotDbHelper.COL_UPLOADER)
                    if (!c.isNull(idxUploader)) uploader(c.getString(idxUploader))
                    val idxLabel = c.getColumnIndexOrThrow(SnapshotDbHelper.COL_LABEL)
                    if (!c.isNull(idxLabel)) label(c.getString(idxLabel))
                    val idxLang = c.getColumnIndexOrThrow(SnapshotDbHelper.COL_LANGUAGE)
                    if (!c.isNull(idxLang)) language(c.getString(idxLang))
                    val idxTags = c.getColumnIndexOrThrow(SnapshotDbHelper.COL_TAGS_JSON)
                    if (!c.isNull(idxTags)) tags(decodeTags(c.getString(idxTags)))
                    val idxSrc = c.getColumnIndexOrThrow(SnapshotDbHelper.COL_SOURCE_DEVICE_NAME)
                    if (!c.isNull(idxSrc)) sourceDeviceName(c.getString(idxSrc))
                }
                .build()
        } catch (e: Exception) {
            SnapshotLogger.w("DbSnapshotCache", "Failed to read entry", e)
            null
        }
    }

    private fun encodeTags(tags: List<String>?): String? {
        if (tags.isNullOrEmpty()) return null
        val arr = com.alibaba.fastjson.JSONArray()
        arr.addAll(tags)
        return arr.toJSONString()
    }

    private fun decodeTags(json: String?): List<String> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = com.alibaba.fastjson.JSONArray.parseArray(json) ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.size) {
                val s = arr.getString(i)
                if (!s.isNullOrEmpty()) out.add(s)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 清空所有快照数据（用户主动重置时调用）。
     */
    fun clearAll() {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(SnapshotDbHelper.TABLE_GALLERY, null, null)
            db.delete(SnapshotDbHelper.TABLE_FAVORITES, null, null)
            putMeta(db, SnapshotDbHelper.META_KEY_LAST_VERSION, "0")
            putMeta(db, SnapshotDbHelper.META_KEY_LAST_SYNC_AT, "0")
            putMeta(db, SnapshotDbHelper.META_KEY_LAST_DEVICE_ID, "")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        SnapshotLogger.w("DbSnapshotCache", "Cleared all snapshot cache")
    }

    companion object {
        private const val TAG = "DbSnapshotCache"

        @Volatile private var INSTANCE: DbSnapshotCache? = null

        @JvmStatic
        fun resetForTest() {
            synchronized(DbSnapshotCache::class.java) {
                INSTANCE = null
            }
        }

        @JvmStatic
        fun getInstance(context: Context): DbSnapshotCache {
            return INSTANCE ?: synchronized(DbSnapshotCache::class.java) {
                INSTANCE ?: DbSnapshotCache(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}