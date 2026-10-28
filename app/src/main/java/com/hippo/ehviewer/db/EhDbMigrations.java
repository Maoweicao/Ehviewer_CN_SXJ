package com.hippo.ehviewer.db;

import android.util.Log;

/**
 * 历史库（greenDAO 时代）到当前 Room schema 的迁移 SQL。
 *
 * <p>迁移阶梯完全沿用旧 {@code EhDB.DBOpenHelper.onUpgrade} 的条件语义
 * （原实现用 {@code oldVersion <= n / < n} 一次性跑完所有满足条件的块），
 * 这里保留同样的判断，供两个入口复用：
 * <ul>
 *   <li>Room 打开 {@code eh.db} 时（版本 1..14 → 15）；</li>
 *   <li>导入外部数据库文件时（先把文件升到当前版本再读）。</li>
 * </ul>
 *
 * <p>末尾的「归一化」步骤是本次迁移新增的：把旧代码靠 onOpen/DAO 自愈补的列
 * 和索引一次性补齐，保证 Room 的 schema 校验能通过。
 */
public final class EhDbMigrations {

    private static final String TAG = "EhDbMigrations";

    /** 只暴露 execSQL，便于同时适配 Room 的 SupportSQLiteDatabase 和原生 SQLiteDatabase。 */
    public interface SqlExecutor {
        void execSQL(String sql);

        /** 执行但忽略失败（例如列已存在导致的 duplicate column）。 */
        void tryExecSQL(String sql);
    }

    private EhDbMigrations() {
    }

    /**
     * @param db         目标数据库
     * @param oldVersion 当前 user_version
     */
    public static void migrateToCurrent(SqlExecutor db, int oldVersion) {
        Log.i(TAG, "Migrating database from version " + oldVersion + " to " + EhDatabase.VERSION);

        // ---------- 旧版本阶梯（与历史实现逐条对应） ----------
        if (oldVersion < 7) {
            db.execSQL("CREATE TABLE IF NOT EXISTS \"DOWNLOADED_FILES\" ("
                    + "\"TOKEN\" TEXT PRIMARY KEY NOT NULL ,"
                    + "\"GID\" INTEGER NOT NULL ,"
                    + "\"FILENAME\" TEXT NOT NULL ,"
                    + "\"MD5\" TEXT,"
                    + "\"PATH\" TEXT NOT NULL ,"
                    + "\"SIZE\" INTEGER,"
                    + "\"DOWNLOAD_TIME\" INTEGER NOT NULL ,"
                    + "\"LAST_ACCESSED\" INTEGER,"
                    + "\"STATUS\" INTEGER NOT NULL );");
        }

        if (oldVersion <= 2) {
            db.execSQL("CREATE TABLE \"FILTER2\" ("
                    + "\"_id\" INTEGER PRIMARY KEY ,"
                    + "\"MODE\" INTEGER NOT NULL ,"
                    + "\"TEXT\" TEXT,"
                    + "\"ENABLE\" INTEGER);");
            db.execSQL("INSERT INTO \"FILTER2\" (_id, MODE, TEXT, ENABLE)"
                    + " SELECT _id, MODE, TEXT, 1 FROM [FILTER];");
            db.execSQL("DROP TABLE [FILTER]");
            db.execSQL("ALTER TABLE FILTER2 RENAME TO [FILTER]");
        }

        if (oldVersion <= 3) {
            db.execSQL("CREATE TABLE \"QUICK_SEARCH2\" ("
                    + "\"_id\" INTEGER PRIMARY KEY ,"
                    + "\"NAME\" TEXT,"
                    + "\"MODE\" INTEGER NOT NULL ,"
                    + "\"CATEGORY\" INTEGER NOT NULL ,"
                    + "\"KEYWORD\" TEXT,"
                    + "\"ADVANCE_SEARCH\" INTEGER NOT NULL ,"
                    + "\"MIN_RATING\" INTEGER NOT NULL ,"
                    + "\"PAGE_FROM\" INTEGER NOT NULL ,"
                    + "\"PAGE_TO\" INTEGER NOT NULL ,"
                    + "\"TIME\" INTEGER NOT NULL );");
            db.execSQL("INSERT INTO \"QUICK_SEARCH2\""
                    + " (_id, NAME, MODE, CATEGORY, KEYWORD, ADVANCE_SEARCH, MIN_RATING, PAGE_FROM, PAGE_TO, TIME)"
                    + " SELECT _id, NAME, MODE, CATEGORY, KEYWORD, ADVANCE_SEARCH, MIN_RATING, -1, -1, TIME"
                    + " FROM QUICK_SEARCH;");
            db.execSQL("DROP TABLE QUICK_SEARCH");
            db.execSQL("ALTER TABLE QUICK_SEARCH2 RENAME TO QUICK_SEARCH");
        }

        if (oldVersion <= 4) {
            db.execSQL("DROP TABLE IF EXISTS \"Black_List\"");
            db.execSQL("CREATE TABLE \"Black_List\" ("
                    + "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT ,"
                    + "\"BADGAYNAME\" TEXT,"
                    + "\"REASON\" TEXT,"
                    + "\"ANGRYWITH\" TEXT,"
                    + "\"ADD_TIME\" TEXT,"
                    + "\"MODE\" INTEGER);");
        }

        if (oldVersion <= 5) {
            db.execSQL("DROP TABLE IF EXISTS \"Gallery_Tags\"");
            db.execSQL("CREATE TABLE \"Gallery_Tags\" ("
                    + "\"GID\" INTEGER PRIMARY KEY NOT NULL ,"
                    + "\"ROWS\" TEXT,"
                    + "\"ARTIST\" TEXT,"
                    + "\"COSPLAYER\" TEXT,"
                    + "\"CHARACTER\" TEXT,"
                    + "\"FEMALE\" TEXT,"
                    + "\"GROUP\" TEXT,"
                    + "\"LANGUAGE\" TEXT,"
                    + "\"MALE\" TEXT,"
                    + "\"MISC\" TEXT,"
                    + "\"MIXED\" TEXT,"
                    + "\"OTHER\" TEXT,"
                    + "\"PARODY\" TEXT,"
                    + "\"RECLASS\" TEXT,"
                    + "\"CREATE_TIME\" INTEGER,"
                    + "\"UPDATE_TIME\" INTEGER,"
                    + "\"LOCATION\" TEXT);");
        }

        if (oldVersion <= 6) {
            db.tryExecSQL("ALTER TABLE \"DOWNLOADS\" ADD COLUMN \"ARCHIVE_URI\" TEXT");
        }

        if (oldVersion < 10) {
            db.execSQL("CREATE TABLE IF NOT EXISTS \"PTOKENS_INDEX\" ("
                    + "\"GID\" INTEGER PRIMARY KEY NOT NULL ,"
                    + "\"PTOKENS\" TEXT,"
                    + "\"PAGES\" INTEGER NOT NULL ,"
                    + "\"UPDATED_AT\" INTEGER NOT NULL );");
        }

        if (oldVersion < 11) {
            db.execSQL("CREATE TABLE IF NOT EXISTS \"SYSTEM_DOWNLOAD_TASKS\" ("
                    + "\"DOWNLOAD_ID\" INTEGER PRIMARY KEY NOT NULL ,"
                    + "\"GID\" INTEGER NOT NULL ,"
                    + "\"PAGE_INDEX\" INTEGER NOT NULL ,"
                    + "\"RESOLVED_URL\" TEXT,"
                    + "\"STATUS\" TEXT NOT NULL ,"
                    + "\"RETRY_COUNT\" INTEGER NOT NULL ,"
                    + "\"CREATED_AT\" INTEGER NOT NULL );");
        }

        if (oldVersion < 12) {
            db.execSQL("CREATE TABLE IF NOT EXISTS \"DOWNLOAD_HISTORY\" ("
                    + "\"GID\" INTEGER PRIMARY KEY NOT NULL ,\"TOKEN\" TEXT,\"TITLE\" TEXT,\"TITLE_JPN\" TEXT,"
                    + "\"FILE_PATH\" TEXT,\"COMPLETED_AT\" INTEGER NOT NULL ,\"LAST_DOWNLOADED_AT\" INTEGER NOT NULL ,"
                    + "\"DOWNLOAD_COUNT\" INTEGER NOT NULL ,\"DELETION_TYPE\" INTEGER NOT NULL ,"
                    + "\"MERGED_TARGET_GID\" INTEGER NOT NULL ,\"DELETED_AT\" INTEGER NOT NULL );");
        }

        if (oldVersion < 13) {
            db.execSQL("CREATE TABLE IF NOT EXISTS \"GALLERY_AI_INFO\" ("
                    + "\"GID\" INTEGER PRIMARY KEY NOT NULL ,\"SUMMARY\" TEXT,\"TAGS\" TEXT,"
                    + "\"DESCRIPTIONS\" TEXT,\"AESTHETIC_SCORE\" REAL NOT NULL ,\"UPDATED_AT\" INTEGER NOT NULL );");
        }

        if (oldVersion < 14) {
            db.tryExecSQL("ALTER TABLE \"DOWNLOAD_HISTORY\" ADD COLUMN \"TOTAL_FILES\" INTEGER NOT NULL DEFAULT 0");
            db.tryExecSQL("ALTER TABLE \"DOWNLOAD_HISTORY\" ADD COLUMN \"DOWNLOADED_FILES\" INTEGER NOT NULL DEFAULT 0");
            db.tryExecSQL("ALTER TABLE \"DOWNLOAD_HISTORY\" ADD COLUMN \"FILE_TOKENS\" TEXT");
        }

        // ---------- 归一化：补齐旧代码靠 onOpen / DAO 自愈补的列与索引 ----------
        db.tryExecSQL("ALTER TABLE \"DOWNLOADED_FILES\" ADD COLUMN \"FILE_TOKEN\" TEXT");
        db.tryExecSQL("ALTER TABLE \"Gallery_Tags\" ADD COLUMN \"LOCATION\" TEXT");
        db.tryExecSQL("ALTER TABLE \"DOWNLOADS\" ADD COLUMN \"ARCHIVE_URI\" TEXT");
        db.tryExecSQL("ALTER TABLE \"DOWNLOAD_HISTORY\" ADD COLUMN \"TOTAL_FILES\" INTEGER NOT NULL DEFAULT 0");
        db.tryExecSQL("ALTER TABLE \"DOWNLOAD_HISTORY\" ADD COLUMN \"DOWNLOADED_FILES\" INTEGER NOT NULL DEFAULT 0");
        db.tryExecSQL("ALTER TABLE \"DOWNLOAD_HISTORY\" ADD COLUMN \"FILE_TOKENS\" TEXT");

        // MILESTONE 从未进入过 onUpgrade 阶梯，旧库可能整张表缺失；
        // 另外旧表在 KEY 上带内联 UNIQUE，与 Room 期望的索引集合不一致，
        // 这里统一重建为 Room 的形态（该表实际未被使用，里程碑存在 SharedPreferences）。
        db.execSQL("DROP TABLE IF EXISTS \"MILESTONE_NEW\"");
        db.execSQL("CREATE TABLE \"MILESTONE_NEW\" ("
                + "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT ,"
                + "\"KEY\" TEXT NOT NULL ,"
                + "\"VALUE\" TEXT,"
                + "\"UPDATE_TIME\" INTEGER NOT NULL );");
        db.tryExecSQL("INSERT OR IGNORE INTO \"MILESTONE_NEW\" (_id, KEY, VALUE, UPDATE_TIME)"
                + " SELECT _id, KEY, VALUE, UPDATE_TIME FROM \"MILESTONE\";");
        db.execSQL("DROP TABLE IF EXISTS \"MILESTONE\"");
        db.execSQL("ALTER TABLE \"MILESTONE_NEW\" RENAME TO \"MILESTONE\"");

        // 旧版本（<=4）重建 Black_List 时没有建索引，Room schema 需要它。
        db.execSQL("CREATE INDEX IF NOT EXISTS \"IDX_Black_List_BADGAYNAME\""
                + " ON \"Black_List\" (\"BADGAYNAME\");");

        // GalleryVersionMap 功能已停用（实体 + 方法体全被注释），直接删表。
        db.execSQL("DROP TABLE IF EXISTS \"GALLERY_VERSION_MAP\"");

        Log.i(TAG, "Database migration finished");
    }
}
