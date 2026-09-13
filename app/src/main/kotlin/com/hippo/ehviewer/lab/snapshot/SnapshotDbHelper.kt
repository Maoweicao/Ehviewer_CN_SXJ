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
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hippo.ehviewer.lab.log.SnapshotLogger

/**
 * 实验室快照缓存数据库助手
 *
 * 协议对应：v3.0 §5.22。
 *
 * 数据库设计要点：
 * <ul>
 *   <li><b>独立 SQLite 文件</b>（{@code lab_snapshot.db}），不与主 GreenDAO 数据库混用</li>
 *   <li>{@code lastUpdated} 用 {@code long} 毫秒时间戳，配合索引支持增量拉取</li>
 *   <li>缩略图直接 base64 内嵌，避免 JOIN</li>
 *   <li>{@code removedGids} 通过 deleted 标志实现</li>
 *   <li>提供迁移策略</li>
 * </ul>
 */
class SnapshotDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val context = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        SnapshotLogger.d(TAG, "Creating snapshot DB schema")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_GALLERY (
                $COL_GID INTEGER NOT NULL,
                $COL_TOKEN TEXT,
                $COL_TITLE TEXT,
                $COL_TITLE_JPN TEXT,
                $COL_THUMB_BASE64 TEXT,
                $COL_CATEGORY INTEGER NOT NULL DEFAULT 0,
                $COL_POSTED TEXT,
                $COL_UPLOADER TEXT,
                $COL_RATING REAL NOT NULL DEFAULT 0,
                $COL_PAGES INTEGER NOT NULL DEFAULT 0,
                $COL_DOWNLOADED_PAGES INTEGER NOT NULL DEFAULT 0,
                $COL_FILE_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_STATE INTEGER NOT NULL DEFAULT 0,
                $COL_LABEL TEXT,
                $COL_LANGUAGE TEXT,
                $COL_TAGS_JSON TEXT,
                $COL_SOURCE_DEVICE_ID TEXT NOT NULL,
                $COL_SOURCE_DEVICE_NAME TEXT,
                $COL_LAST_UPDATED INTEGER NOT NULL,
                $COL_DELETED INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY ($COL_GID, $COL_SOURCE_DEVICE_ID))
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gallery_last_updated ON $TABLE_GALLERY ($COL_LAST_UPDATED)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gallery_source ON $TABLE_GALLERY ($COL_SOURCE_DEVICE_ID)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_FAVORITES (
                $COL_FAV_GID INTEGER NOT NULL,
                $COL_FAV_CAT TEXT,
                $COL_FAV_NOTE TEXT,
                $COL_FAV_LAST_UPDATED INTEGER NOT NULL,
                PRIMARY KEY ($COL_FAV_GID))
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_META (
                $COL_META_KEY TEXT NOT NULL PRIMARY KEY,
                $COL_META_VALUE TEXT)
        """.trimIndent())

        // 初始化默认值
        val cv = ContentValues()
        cv.put(COL_META_KEY, META_KEY_LAST_VERSION)
        cv.put(COL_META_VALUE, "0")
        db.insert(TABLE_META, null, cv)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        SnapshotLogger.i(
            TAG, "Upgrading snapshot DB: $oldVersion → $newVersion")
        // M2 初始版本：未来升级按需 ALTER TABLE；当前保守地 drop + recreate
        if (oldVersion < newVersion) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_GALLERY")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_FAVORITES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_META")
            onCreate(db)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        SnapshotLogger.w(
            TAG, "Downgrading snapshot DB: $oldVersion → $newVersion")
        onUpgrade(db, oldVersion, newVersion)
    }

    companion object {
        private const val TAG = "SnapshotDb"

        const val DB_NAME = "lab_snapshot.db"
        private const val DB_VERSION = 1

        // 画廊快照表
        const val TABLE_GALLERY = "gallery_snapshot"

        const val COL_GID = "gid"
        const val COL_TOKEN = "token"
        const val COL_TITLE = "title"
        const val COL_TITLE_JPN = "title_jpn"
        const val COL_THUMB_BASE64 = "thumb_base64"
        const val COL_CATEGORY = "category"
        const val COL_POSTED = "posted"
        const val COL_UPLOADER = "uploader"
        const val COL_RATING = "rating"
        const val COL_PAGES = "pages"
        const val COL_DOWNLOADED_PAGES = "downloaded_pages"
        const val COL_FILE_COUNT = "file_count"
        const val COL_STATE = "state"
        const val COL_LABEL = "label"
        const val COL_LANGUAGE = "language"
        const val COL_TAGS_JSON = "tags_json"
        const val COL_SOURCE_DEVICE_ID = "source_device_id"
        const val COL_SOURCE_DEVICE_NAME = "source_device_name"
        const val COL_LAST_UPDATED = "last_updated"
        const val COL_DELETED = "deleted"

        // 收藏快照表
        const val TABLE_FAVORITES = "favorites_snapshot"
        const val COL_FAV_GID = "gid"
        const val COL_FAV_CAT = "fav_cat"
        const val COL_FAV_NOTE = "fav_note"
        const val COL_FAV_LAST_UPDATED = "last_updated"

        // 元数据
        const val TABLE_META = "snapshot_meta"
        const val COL_META_KEY = "key"
        const val COL_META_VALUE = "value"

        const val META_KEY_LAST_VERSION = "last_version"
        const val META_KEY_LAST_SYNC_AT = "last_sync_at"
        const val META_KEY_LAST_DEVICE_ID = "last_device_id"
    }
}