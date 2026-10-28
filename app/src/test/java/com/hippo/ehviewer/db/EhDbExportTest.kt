package com.hippo.ehviewer.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.HistoryInfo
import com.hippo.ehviewer.dao.LocalFavoriteInfo
import com.hippo.ehviewer.dao.QuickSearch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 导出功能的集成回归：验证导出库能真实落盘、含数据、且兼容版导出把
 * user_version 写回 7（旧 BiLi PC Gamer 版本按 v7 读取）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EhDbExportTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        context.deleteDatabase(EhDatabase.NAME)
        EhDB.initialize(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(EhDatabase.NAME)
        context.deleteDatabase("eh.export.db")
        context.deleteDatabase("eh.legacy.export.db")
    }

    @Test
    fun exportDb_writesRowsAndKeepsCurrentVersion() {
        seed()

        val target = File(context.cacheDir, "export.db")
        assertTrue(EhDB.exportDB(context, target))
        assertTrue(target.isFile)

        val db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            assertEquals(EhDatabase.VERSION, db.version)
            assertCount(db, "DOWNLOADS", 1)
            assertCount(db, "LOCAL_FAVORITES", 1)
            assertCount(db, "HISTORY", 1)
            assertCount(db, "QUICK_SEARCH", 1)
            assertCount(db, "DOWNLOAD_DIRNAME", 0)

            db.rawQuery("SELECT TITLE FROM DOWNLOADS", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("导出标题", cursor.getString(0))
            }
        } finally {
            db.close()
            target.delete()
        }
    }

    @Test
    fun exportLegacyDb_setsVersionSeven() {
        seed()

        val target = File(context.cacheDir, "legacy.db")
        assertTrue(EhDB.exportLegacyDB(context, target))
        assertTrue(target.isFile)

        val db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            assertEquals(7, db.version)
            assertCount(db, "DOWNLOADS", 1)
            assertCount(db, "LOCAL_FAVORITES", 1)
        } finally {
            db.close()
            target.delete()
        }
    }

    private fun seed() {
        val download = DownloadInfo(8001L).apply {
            token = "t8001"
            title = "导出标题"
            time = 100L
            state = DownloadInfo.STATE_NONE
        }
        EhDB.putDownloadInfo(download)

        val favorite = LocalFavoriteInfo(8001L).apply {
            token = "t8001"
            title = "导出标题"
            time = 1L
        }
        EhDB.putLocalFavorite(favorite)

        val history = HistoryInfo(8001L).apply {
            title = "历史"
            time = 2L
        }
        EhDB.putHistoryInfo(history)

        EhDB.insertQuickSearch(QuickSearch().apply {
            name = "书签"
            keyword = "tag:a"
            time = 3L
        })

        assertNotNull(EhDB.getDownloadInfo(8001L))
    }

    private fun assertCount(db: SQLiteDatabase, table: String, expected: Int) {
        db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("$table row count", expected, cursor.getInt(0))
        }
    }
}
