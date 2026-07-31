package com.hippo.ehviewer.dao;

import android.database.Cursor;
import android.database.sqlite.SQLiteStatement;

import org.greenrobot.greendao.AbstractDao;
import org.greenrobot.greendao.Property;
import org.greenrobot.greendao.internal.DaoConfig;
import org.greenrobot.greendao.database.Database;
import org.greenrobot.greendao.database.DatabaseStatement;

/**
 * DAO for table "SYSTEM_DOWNLOAD_TASKS".
 */
public class SystemDownloadTaskDao extends AbstractDao<SystemDownloadTask, Long> {

    public static final String TABLENAME = "SYSTEM_DOWNLOAD_TASKS";

    /**
     * Properties of entity SystemDownloadTask.<br/>
     * Can be used for QueryBuilder and for referencing column names.
     */
    public static class Properties {
        public final static Property DownloadId = new Property(0, long.class, "downloadId", true, "DOWNLOAD_ID");
        public final static Property Gid = new Property(1, long.class, "gid", false, "GID");
        public final static Property PageIndex = new Property(2, int.class, "pageIndex", false, "PAGE_INDEX");
        public final static Property ResolvedUrl = new Property(3, String.class, "resolvedUrl", false, "RESOLVED_URL");
        public final static Property Status = new Property(4, String.class, "status", false, "STATUS");
        public final static Property RetryCount = new Property(5, int.class, "retryCount", false, "RETRY_COUNT");
        public final static Property CreatedAt = new Property(6, long.class, "createdAt", false, "CREATED_AT");
    }

    public SystemDownloadTaskDao(DaoConfig config) {
        super(config);
    }

    public SystemDownloadTaskDao(DaoConfig config, DaoSession daoSession) {
        super(config, daoSession);
    }

    /** Creates the underlying database table. */
    public static void createTable(Database db, boolean ifNotExists) {
        String constraint = ifNotExists ? "IF NOT EXISTS " : "";
        db.execSQL("CREATE TABLE " + constraint + "\"SYSTEM_DOWNLOAD_TASKS\" (" +
                "\"DOWNLOAD_ID\" INTEGER PRIMARY KEY NOT NULL ," +
                "\"GID\" INTEGER NOT NULL ," +
                "\"PAGE_INDEX\" INTEGER NOT NULL ," +
                "\"RESOLVED_URL\" TEXT," +
                "\"STATUS\" TEXT NOT NULL ," +
                "\"RETRY_COUNT\" INTEGER NOT NULL ," +
                "\"CREATED_AT\" INTEGER NOT NULL );");
    }

    /** Drops the underlying database table. */
    public static void dropTable(Database db, boolean ifExists) {
        String sql = "DROP TABLE " + (ifExists ? "IF EXISTS " : "") + "\"SYSTEM_DOWNLOAD_TASKS\"";
        db.execSQL(sql);
    }

    @Override
    protected final void bindValues(DatabaseStatement stmt, SystemDownloadTask entity) {
        stmt.clearBindings();
        stmt.bindLong(1, entity.getDownloadId());
        stmt.bindLong(2, entity.getGid());
        stmt.bindLong(3, entity.getPageIndex());
        String resolvedUrl = entity.getResolvedUrl();
        if (resolvedUrl != null) {
            stmt.bindString(4, resolvedUrl);
        }
        stmt.bindString(5, entity.getStatus());
        stmt.bindLong(6, entity.getRetryCount());
        stmt.bindLong(7, entity.getCreatedAt());
    }

    @Override
    protected final void bindValues(SQLiteStatement stmt, SystemDownloadTask entity) {
        stmt.clearBindings();
        stmt.bindLong(1, entity.getDownloadId());
        stmt.bindLong(2, entity.getGid());
        stmt.bindLong(3, entity.getPageIndex());
        String resolvedUrl = entity.getResolvedUrl();
        if (resolvedUrl != null) {
            stmt.bindString(4, resolvedUrl);
        }
        stmt.bindString(5, entity.getStatus());
        stmt.bindLong(6, entity.getRetryCount());
        stmt.bindLong(7, entity.getCreatedAt());
    }

    @Override
    public Long readKey(Cursor cursor, int offset) {
        return cursor.getLong(offset + 0);
    }

    @Override
    public SystemDownloadTask readEntity(Cursor cursor, int offset) {
        return new SystemDownloadTask(
            cursor.getLong(offset + 0),
            cursor.getLong(offset + 1),
            cursor.getInt(offset + 2),
            cursor.isNull(offset + 3) ? null : cursor.getString(offset + 3),
            cursor.getString(offset + 4),
            cursor.getInt(offset + 5),
            cursor.getLong(offset + 6)
        );
    }

    @Override
    public void readEntity(Cursor cursor, SystemDownloadTask entity, int offset) {
        entity.setDownloadId(cursor.getLong(offset + 0));
        entity.setGid(cursor.getLong(offset + 1));
        entity.setPageIndex(cursor.getInt(offset + 2));
        entity.setResolvedUrl(cursor.isNull(offset + 3) ? null : cursor.getString(offset + 3));
        entity.setStatus(cursor.getString(offset + 4));
        entity.setRetryCount(cursor.getInt(offset + 5));
        entity.setCreatedAt(cursor.getLong(offset + 6));
    }

    @Override
    protected final Long updateKeyAfterInsert(SystemDownloadTask entity, long rowId) {
        entity.setDownloadId(rowId);
        return rowId;
    }

    @Override
    public Long getKey(SystemDownloadTask entity) {
        if (entity != null) {
            return entity.getDownloadId();
        } else {
            return null;
        }
    }

    @Override
    protected final boolean isEntityUpdateable() {
        return true;
    }
}