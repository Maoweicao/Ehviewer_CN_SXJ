package com.hippo.ehviewer.dao;

import android.database.Cursor;
import android.database.sqlite.SQLiteStatement;

import org.greenrobot.greendao.AbstractDao;
import org.greenrobot.greendao.Property;
import org.greenrobot.greendao.database.Database;
import org.greenrobot.greendao.database.DatabaseStatement;
import org.greenrobot.greendao.internal.DaoConfig;

/** DAO for table "DOWNLOAD_HISTORY". */
public class DownloadHistoryDao extends AbstractDao<DownloadHistory, Long> {
    public static final String TABLENAME = "DOWNLOAD_HISTORY";

    public static class Properties {
        public static final Property Gid = new Property(0, long.class, "gid", true, "GID");
        public static final Property Token = new Property(1, String.class, "token", false, "TOKEN");
        public static final Property Title = new Property(2, String.class, "title", false, "TITLE");
        public static final Property TitleJpn = new Property(3, String.class, "titleJpn", false, "TITLE_JPN");
        public static final Property FilePath = new Property(4, String.class, "filePath", false, "FILE_PATH");
        public static final Property CompletedAt = new Property(5, long.class, "completedAt", false, "COMPLETED_AT");
        public static final Property LastDownloadedAt = new Property(6, long.class, "lastDownloadedAt", false, "LAST_DOWNLOADED_AT");
        public static final Property DownloadCount = new Property(7, int.class, "downloadCount", false, "DOWNLOAD_COUNT");
        public static final Property DeletionType = new Property(8, int.class, "deletionType", false, "DELETION_TYPE");
        public static final Property MergedTargetGid = new Property(9, long.class, "mergedTargetGid", false, "MERGED_TARGET_GID");
        public static final Property DeletedAt = new Property(10, long.class, "deletedAt", false, "DELETED_AT");
        public static final Property TotalFiles = new Property(11, int.class, "totalFiles", false, "TOTAL_FILES");
        public static final Property DownloadedFiles = new Property(12, int.class, "downloadedFiles", false, "DOWNLOADED_FILES");
        public static final Property FileTokens = new Property(13, String.class, "fileTokens", false, "FILE_TOKENS");
    }

    public DownloadHistoryDao(DaoConfig config) { super(config); }
    public DownloadHistoryDao(DaoConfig config, DaoSession daoSession) { super(config, daoSession); }

    /** 只会创建表，不会添加新列。新列通过 ALTER TABLE 添加（见 EhDB） */
    public static void createTable(Database db, boolean ifNotExists) {
        db.execSQL("CREATE TABLE " + (ifNotExists ? "IF NOT EXISTS " : "") + "\"DOWNLOAD_HISTORY\" (" +
                "\"GID\" INTEGER PRIMARY KEY NOT NULL ," +
                "\"TOKEN\" TEXT," +
                "\"TITLE\" TEXT," +
                "\"TITLE_JPN\" TEXT," +
                "\"FILE_PATH\" TEXT," +
                "\"COMPLETED_AT\" INTEGER NOT NULL ," +
                "\"LAST_DOWNLOADED_AT\" INTEGER NOT NULL ," +
                "\"DOWNLOAD_COUNT\" INTEGER NOT NULL ," +
                "\"DELETION_TYPE\" INTEGER NOT NULL ," +
                "\"MERGED_TARGET_GID\" INTEGER NOT NULL ," +
                "\"DELETED_AT\" INTEGER NOT NULL ," +
                "\"TOTAL_FILES\" INTEGER NOT NULL ," +
                "\"DOWNLOADED_FILES\" INTEGER NOT NULL ," +
                "\"FILE_TOKENS\" TEXT);");
    }

    public static void dropTable(Database db, boolean ifExists) { db.execSQL("DROP TABLE " + (ifExists ? "IF EXISTS " : "") + "\"DOWNLOAD_HISTORY\""); }

    @Override protected final void bindValues(DatabaseStatement stmt, DownloadHistory e) { bind(stmt, e); }
    @Override protected final void bindValues(SQLiteStatement stmt, DownloadHistory e) { bind(stmt, e); }
    private static void bind(DatabaseStatement stmt, DownloadHistory e) {
        stmt.clearBindings();
        stmt.bindLong(1, e.getGid());
        if (e.getToken() != null) stmt.bindString(2, e.getToken());
        if (e.getTitle() != null) stmt.bindString(3, e.getTitle());
        if (e.getTitleJpn() != null) stmt.bindString(4, e.getTitleJpn());
        if (e.getFilePath() != null) stmt.bindString(5, e.getFilePath());
        stmt.bindLong(6, e.getCompletedAt());
        stmt.bindLong(7, e.getLastDownloadedAt());
        stmt.bindLong(8, e.getDownloadCount());
        stmt.bindLong(9, e.getDeletionType());
        stmt.bindLong(10, e.getMergedTargetGid());
        stmt.bindLong(11, e.getDeletedAt());
        stmt.bindLong(12, e.getTotalFiles());
        stmt.bindLong(13, e.getDownloadedFiles());
        if (e.getFileTokens() != null) stmt.bindString(14, e.getFileTokens());
    }
    private static void bind(SQLiteStatement stmt, DownloadHistory e) {
        stmt.clearBindings();
        stmt.bindLong(1, e.getGid());
        if (e.getToken() != null) stmt.bindString(2, e.getToken());
        if (e.getTitle() != null) stmt.bindString(3, e.getTitle());
        if (e.getTitleJpn() != null) stmt.bindString(4, e.getTitleJpn());
        if (e.getFilePath() != null) stmt.bindString(5, e.getFilePath());
        stmt.bindLong(6, e.getCompletedAt());
        stmt.bindLong(7, e.getLastDownloadedAt());
        stmt.bindLong(8, e.getDownloadCount());
        stmt.bindLong(9, e.getDeletionType());
        stmt.bindLong(10, e.getMergedTargetGid());
        stmt.bindLong(11, e.getDeletedAt());
        stmt.bindLong(12, e.getTotalFiles());
        stmt.bindLong(13, e.getDownloadedFiles());
        if (e.getFileTokens() != null) stmt.bindString(14, e.getFileTokens());
    }

    @Override public Long readKey(Cursor c, int o) { return c.getLong(o); }
    @Override public DownloadHistory readEntity(Cursor c, int o) {
        DownloadHistory entity = new DownloadHistory();
        readEntity(c, entity, o);
        return entity;
    }
    @Override public void readEntity(Cursor c, DownloadHistory e, int o) {
        e.setGid(c.getLong(o));
        e.setToken(c.isNull(o+1) ? null : c.getString(o+1));
        e.setTitle(c.isNull(o+2) ? null : c.getString(o+2));
        e.setTitleJpn(c.isNull(o+3) ? null : c.getString(o+3));
        e.setFilePath(c.isNull(o+4) ? null : c.getString(o+4));
        e.setCompletedAt(c.getLong(o+5));
        e.setLastDownloadedAt(c.getLong(o+6));
        e.setDownloadCount(c.getInt(o+7));
        e.setDeletionType(c.getInt(o+8));
        e.setMergedTargetGid(c.getLong(o+9));
        e.setDeletedAt(c.getLong(o+10));
        e.setTotalFiles(c.getInt(o+11));
        e.setDownloadedFiles(c.getInt(o+12));
        e.setFileTokens(c.isNull(o+13) ? null : c.getString(o+13));
    }
    @Override protected final Long updateKeyAfterInsert(DownloadHistory e, long rowId) { e.setGid(rowId); return rowId; }
    @Override public Long getKey(DownloadHistory e) { return e == null ? null : e.getGid(); }
    @Override protected final boolean isEntityUpdateable() { return true; }
}
