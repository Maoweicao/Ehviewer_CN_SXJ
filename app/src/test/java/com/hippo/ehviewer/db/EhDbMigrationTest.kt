package com.hippo.ehviewer.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.hippo.ehviewer.dao.DownloadHistory
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.GalleryTags
import com.hippo.ehviewer.dao.HistoryInfo
import com.hippo.ehviewer.dao.QuickSearch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * eh.db 从 greenDAO schema v14 迁移到 Room v15 的回归测试。
 *
 * <p>用旧版 greenDAO 的建表 SQL 造一个 v14 库并写入数据，再用 Room 打开：
 * Room 会校验实体定义与真实表结构是否逐列一致，任何列名/类型/主键/索引差异都会抛异常。
 * 这比肉眼比对 DDL 可靠。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EhDbMigrationTest {

    private lateinit var context: Context
    private val dbName = "eh_migration_test.db"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrateFromV14_keepsDataAndPassesRoomValidation() {
        createLegacyV14Database()

        val db = EhDatabase.open(context, dbName)
        try {
            // 触发打开：Room 在这里执行 14→15 迁移并校验 schema，失败会抛异常
            db.openHelper.writableDatabase

            // 下载列表
            val downloads = db.downloadsDao().loadAllOrderByTimeDesc()
            assertEquals(1, downloads.size)
            val download = downloads[0]
            assertEquals(1001L, download.gid)
            assertEquals("token-1001", download.token)
            assertEquals("标题一", download.title)
            assertEquals(DownloadInfo.STATE_WAIT, download.state)
            assertEquals(1700000000000L, download.time)
            assertEquals("标签A", download.label)

            // 下载历史（含 v14 新增的三个文件列）
            val history = db.downloadHistoryDao().load(2001L)
            assertNotNull(history)
            assertEquals(12, history!!.totalFiles)
            assertEquals(7, history.downloadedFiles)
            assertEquals("a,b,c", history.fileTokens)
            assertEquals(DownloadHistory.DELETION_NONE, history.deletionType)

            // 历史记录（继承自 GalleryInfo 的列）
            val historyList = db.historyDao().loadAllOrderByTimeDesc()
            assertEquals(1, historyList.size)
            val historyInfo: HistoryInfo = historyList[0]
            assertEquals(3001L, historyInfo.gid)
            assertEquals("历史标题", historyInfo.title)
            assertEquals(1.5f, historyInfo.rating, 0.001f)

            // 快速搜索（含 PAGE_FROM / PAGE_TO / ADVANCE_SEARCH 等列）
            val searches = db.quickSearchDao().loadAllOrderByTimeAsc()
            assertEquals(1, searches.size)
            val search: QuickSearch = searches[0]
            assertEquals("书签一", search.name)
            assertEquals(2, search.pageFrom)
            assertEquals(5, search.pageTo)

            // 标签表（LOCATION 列由历史 onOpen 补列）
            val tags: GalleryTags? = db.galleryTagsDao().load(4001L)
            assertNotNull(tags)
            assertEquals("artist-a", tags!!.artist)
            assertEquals("outdoors", tags.location)

            // 已下载文件表（FILE_TOKEN 列由历史自愈逻辑补列）
            val file = db.downloadedFilesDao().load("file-1")
            assertNotNull(file)
            assertEquals("ft-1", file!!.fileToken)
            assertEquals(4096L, file.size)

            // 迁移后应写入 Room 的 user_version
            val version = db.openHelper.readableDatabase
                .query("PRAGMA user_version").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
            assertEquals(EhDatabase.VERSION, version)

            // 停用功能对应的表应被删除
            db.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type='table' AND name='GALLERY_VERSION_MAP'")
                .use { cursor -> assertEquals(0, cursor.count) }

            // 迁移后仍能正常写入（upsert 语义）
            val newInfo = DownloadInfo(5001L).apply {
                token = "token-5001"
                title = "新下载"
                state = DownloadInfo.STATE_NONE
                time = 1L
            }
            db.downloadsDao().upsert(newInfo)
            assertNotNull(db.downloadsDao().load(5001L))
        } finally {
            db.close()
        }
    }

    @Test
    fun freshDatabase_matchesEntitySchema() {
        val db = EhDatabase.open(context, dbName)
        try {
            db.openHelper.writableDatabase
            assertEquals(0, db.downloadsDao().loadAll().size)
            // 新库不应残留停用表
            db.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type='table' AND name='GALLERY_VERSION_MAP'")
                .use { cursor -> assertEquals(0, cursor.count) }
        } finally {
            db.close()
        }
    }

    @Test
    fun downloadInfoUpsert_doesNotDuplicateRows() {
        val db = EhDatabase.open(context, dbName)
        try {
            db.openHelper.writableDatabase
            val info = DownloadInfo(6001L).apply {
                token = "t"
                title = "第一版"
                time = 10L
            }
            db.downloadsDao().upsert(info)
            info.title = "第二版"
            db.downloadsDao().upsert(info)

            assertEquals(1, db.downloadsDao().loadAll().size)
            assertEquals("第二版", db.downloadsDao().load(6001L).title)
        } finally {
            db.close()
        }
    }

    private fun createLegacyV14Database() {
        val path = context.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        path.delete()

        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            // ---- 以下 DDL 取自 greenDAO 生成的 *Dao.createTable（schema v14） ----
            db.execSQL(
                "CREATE TABLE \"Gallery_Tags\" (" +
                        "\"GID\" INTEGER PRIMARY KEY NOT NULL ," +
                        "\"ROWS\" TEXT,\"ARTIST\" TEXT,\"COSPLAYER\" TEXT,\"CHARACTER\" TEXT," +
                        "\"FEMALE\" TEXT,\"GROUP\" TEXT,\"LANGUAGE\" TEXT,\"MALE\" TEXT,\"MISC\" TEXT," +
                        "\"MIXED\" TEXT,\"OTHER\" TEXT,\"PARODY\" TEXT,\"RECLASS\" TEXT," +
                        "\"CREATE_TIME\" INTEGER,\"UPDATE_TIME\" INTEGER,\"LOCATION\" TEXT);"
            )
            db.execSQL(
                "CREATE TABLE \"DOWNLOADS\" (" +
                        "\"GID\" INTEGER PRIMARY KEY NOT NULL ,\"TOKEN\" TEXT,\"TITLE\" TEXT," +
                        "\"TITLE_JPN\" TEXT,\"THUMB\" TEXT,\"CATEGORY\" INTEGER NOT NULL ," +
                        "\"POSTED\" TEXT,\"UPLOADER\" TEXT,\"RATING\" REAL NOT NULL ," +
                        "\"SIMPLE_LANGUAGE\" TEXT,\"STATE\" INTEGER NOT NULL ," +
                        "\"LEGACY\" INTEGER NOT NULL ,\"TIME\" INTEGER NOT NULL ,\"LABEL\" TEXT," +
                        "\"ARCHIVE_URI\" TEXT);"
            )
            db.execSQL(
                "CREATE TABLE \"DOWNLOAD_LABELS\" (" +
                        "\"_id\" INTEGER PRIMARY KEY ,\"LABEL\" TEXT,\"TIME\" INTEGER NOT NULL );"
            )
            db.execSQL(
                "CREATE TABLE \"DOWNLOAD_DIRNAME\" (" +
                        "\"GID\" INTEGER PRIMARY KEY NOT NULL ,\"DIRNAME\" TEXT);"
            )
            db.execSQL(
                "CREATE TABLE \"DOWNLOADED_FILES\" (" +
                        "\"TOKEN\" TEXT PRIMARY KEY NOT NULL ,\"FILE_TOKEN\" TEXT," +
                        "\"GID\" INTEGER NOT NULL ,\"FILENAME\" TEXT NOT NULL ,\"MD5\" TEXT," +
                        "\"PATH\" TEXT NOT NULL ,\"SIZE\" INTEGER," +
                        "\"DOWNLOAD_TIME\" INTEGER NOT NULL ,\"LAST_ACCESSED\" INTEGER," +
                        "\"STATUS\" INTEGER NOT NULL );"
            )
            db.execSQL(
                "CREATE TABLE \"HISTORY\" (" +
                        "\"GID\" INTEGER PRIMARY KEY NOT NULL ,\"TOKEN\" TEXT,\"TITLE\" TEXT," +
                        "\"TITLE_JPN\" TEXT,\"THUMB\" TEXT,\"CATEGORY\" INTEGER NOT NULL ," +
                        "\"POSTED\" TEXT,\"UPLOADER\" TEXT,\"RATING\" REAL NOT NULL ," +
                        "\"SIMPLE_LANGUAGE\" TEXT,\"MODE\" INTEGER NOT NULL ," +
                        "\"TIME\" INTEGER NOT NULL );"
            )
            db.execSQL(
                "CREATE TABLE \"QUICK_SEARCH\" (" +
                        "\"_id\" INTEGER PRIMARY KEY ,\"NAME\" TEXT,\"MODE\" INTEGER NOT NULL ," +
                        "\"CATEGORY\" INTEGER NOT NULL ,\"KEYWORD\" TEXT," +
                        "\"ADVANCE_SEARCH\" INTEGER NOT NULL ,\"MIN_RATING\" INTEGER NOT NULL ," +
                        "\"PAGE_FROM\" INTEGER NOT NULL ,\"PAGE_TO\" INTEGER NOT NULL ," +
                        "\"TIME\" INTEGER NOT NULL );"
            )
            db.execSQL(
                "CREATE TABLE \"Black_List\" (" +
                        "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT ,\"BADGAYNAME\" TEXT," +
                        "\"REASON\" TEXT,\"ANGRYWITH\" TEXT,\"ADD_TIME\" TEXT,\"MODE\" INTEGER);"
            )
            db.execSQL("CREATE INDEX \"IDX_Black_List_BADGAYNAME\" ON Black_List (\"BADGAYNAME\");")
            db.execSQL(
                "CREATE TABLE \"LOCAL_FAVORITES\" (" +
                        "\"GID\" INTEGER PRIMARY KEY NOT NULL ,\"TOKEN\" TEXT,\"TITLE\" TEXT," +
                        "\"TITLE_JPN\" TEXT,\"THUMB\" TEXT,\"CATEGORY\" INTEGER NOT NULL ," +
                        "\"POSTED\" TEXT,\"UPLOADER\" TEXT,\"RATING\" REAL NOT NULL ," +
                        "\"SIMPLE_LANGUAGE\" TEXT,\"TIME\" INTEGER NOT NULL );"
            )
            db.execSQL(
                "CREATE TABLE \"BOOKMARKS\" (" +
                        "\"GID\" INTEGER PRIMARY KEY NOT NULL ,\"TOKEN\" TEXT,\"TITLE\" TEXT," +
                        "\"TITLE_JPN\" TEXT,\"THUMB\" TEXT,\"CATEGORY\" INTEGER NOT NULL ," +
                        "\"POSTED\" TEXT,\"UPLOADER\" TEXT,\"RATING\" REAL NOT NULL ," +
                        "\"SIMPLE_LANGUAGE\" TEXT,\"PAGE\" INTEGER NOT NULL ," +
                        "\"TIME\" INTEGER NOT NULL );"
            )
            db.execSQL(
                "CREATE TABLE \"FILTER\" (" +
                        "\"_id\" INTEGER PRIMARY KEY ,\"MODE\" INTEGER NOT NULL ," +
                        "\"TEXT\" TEXT,\"ENABLE\" INTEGER);"
            )
            // 旧库里 MILESTONE 由 createAllTables 建出，KEY 上是内联 UNIQUE
            db.execSQL(
                "CREATE TABLE \"MILESTONE\" (" +
                        "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT ," +
                        "\"KEY\" TEXT NOT NULL UNIQUE ,\"VALUE\" TEXT," +
                        "\"UPDATE_TIME\" INTEGER NOT NULL );"
            )
            db.execSQL(
                "CREATE TABLE \"PTOKENS_INDEX\" (" +
                        "\"GID\" INTEGER PRIMARY KEY NOT NULL ,\"PTOKENS\" TEXT," +
                        "\"PAGES\" INTEGER NOT NULL ,\"UPDATED_AT\" INTEGER NOT NULL );"
            )
            db.execSQL(
                "CREATE TABLE \"SYSTEM_DOWNLOAD_TASKS\" (" +
                        "\"DOWNLOAD_ID\" INTEGER PRIMARY KEY NOT NULL ,\"GID\" INTEGER NOT NULL ," +
                        "\"PAGE_INDEX\" INTEGER NOT NULL ,\"RESOLVED_URL\" TEXT," +
                        "\"STATUS\" TEXT NOT NULL ,\"RETRY_COUNT\" INTEGER NOT NULL ," +
                        "\"CREATED_AT\" INTEGER NOT NULL );"
            )
            db.execSQL(
                "CREATE TABLE \"DOWNLOAD_HISTORY\" (" +
                        "\"GID\" INTEGER PRIMARY KEY NOT NULL ,\"TOKEN\" TEXT,\"TITLE\" TEXT," +
                        "\"TITLE_JPN\" TEXT,\"FILE_PATH\" TEXT,\"COMPLETED_AT\" INTEGER NOT NULL ," +
                        "\"LAST_DOWNLOADED_AT\" INTEGER NOT NULL ,\"DOWNLOAD_COUNT\" INTEGER NOT NULL ," +
                        "\"DELETION_TYPE\" INTEGER NOT NULL ,\"MERGED_TARGET_GID\" INTEGER NOT NULL ," +
                        "\"DELETED_AT\" INTEGER NOT NULL ,\"TOTAL_FILES\" INTEGER NOT NULL ," +
                        "\"DOWNLOADED_FILES\" INTEGER NOT NULL ,\"FILE_TOKENS\" TEXT);"
            )
            db.execSQL(
                "CREATE TABLE \"GALLERY_AI_INFO\" (" +
                        "\"GID\" INTEGER PRIMARY KEY NOT NULL ,\"SUMMARY\" TEXT,\"TAGS\" TEXT," +
                        "\"DESCRIPTIONS\" TEXT,\"AESTHETIC_SCORE\" REAL NOT NULL ," +
                        "\"UPDATED_AT\" INTEGER NOT NULL );"
            )
            // 停用功能的孤儿表：迁移时应被删除
            db.execSQL(
                "CREATE TABLE \"GALLERY_VERSION_MAP\" (" +
                        "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT ," +
                        "\"CURRENT_GID\" INTEGER NOT NULL ,\"ORIGINAL_GID\" INTEGER NOT NULL ," +
                        "\"TITLE\" TEXT,\"CREATE_TIME\" INTEGER NOT NULL ," +
                        "\"UPDATE_TIME\" INTEGER NOT NULL );"
            )

            // ---- 数据 ----
            db.execSQL(
                "INSERT INTO DOWNLOADS (GID,TOKEN,TITLE,CATEGORY,RATING,STATE,LEGACY,TIME,LABEL)" +
                        " VALUES (1001,'token-1001','标题一',1,4.5,1,0,1700000000000,'标签A');"
            )
            db.execSQL(
                "INSERT INTO DOWNLOAD_HISTORY (GID,TOKEN,TITLE,COMPLETED_AT,LAST_DOWNLOADED_AT," +
                        "DOWNLOAD_COUNT,DELETION_TYPE,MERGED_TARGET_GID,DELETED_AT,TOTAL_FILES," +
                        "DOWNLOADED_FILES,FILE_TOKENS)" +
                        " VALUES (2001,'token-2001','历史',1,2,1,0,0,0,12,7,'a,b,c');"
            )
            db.execSQL(
                "INSERT INTO HISTORY (GID,TOKEN,TITLE,CATEGORY,RATING,MODE,TIME)" +
                        " VALUES (3001,'token-3001','历史标题',1,1.5,0,99);"
            )
            db.execSQL(
                "INSERT INTO QUICK_SEARCH (_id,NAME,MODE,CATEGORY,ADVANCE_SEARCH,MIN_RATING," +
                        "PAGE_FROM,PAGE_TO,TIME) VALUES (1,'书签一',0,1,0,0,2,5,10);"
            )
            db.execSQL(
                "INSERT INTO Gallery_Tags (GID,ARTIST,LOCATION,CREATE_TIME,UPDATE_TIME)" +
                        " VALUES (4001,'artist-a','outdoors',1,2);"
            )
            db.execSQL(
                "INSERT INTO DOWNLOADED_FILES (TOKEN,FILE_TOKEN,GID,FILENAME,PATH," +
                        "DOWNLOAD_TIME,STATUS,SIZE) VALUES ('file-1','ft-1',1001,'1.jpg','/tmp/1.jpg',1,0,4096);"
            )
            db.execSQL(
                "INSERT INTO LOCAL_FAVORITES (GID,TOKEN,TITLE,CATEGORY,RATING,TIME)" +
                        " VALUES (7001,'t7001','收藏',1,3.0,5);"
            )
            db.execSQL("INSERT INTO Black_List (BADGAYNAME,REASON) VALUES ('bad','reason');")
            db.execSQL(
                "INSERT INTO SYSTEM_DOWNLOAD_TASKS (DOWNLOAD_ID,GID,PAGE_INDEX,STATUS,RETRY_COUNT,CREATED_AT)" +
                        " VALUES (9,1001,0,'PENDING',0,1);"
            )
            db.execSQL(
                "INSERT INTO PTOKENS_INDEX (GID,PTOKENS,PAGES,UPDATED_AT) VALUES (1001,'p1,p2',2,1);"
            )
            db.execSQL(
                "INSERT INTO GALLERY_AI_INFO (GID,SUMMARY,AESTHETIC_SCORE,UPDATED_AT)" +
                        " VALUES (1001,'summary',0.5,1);"
            )

            db.version = 14
        } finally {
            db.close()
        }
    }
}
