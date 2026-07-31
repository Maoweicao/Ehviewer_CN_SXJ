package com.hippo.ehviewer.dao;

import android.database.Cursor;
import android.database.sqlite.SQLiteStatement;

import org.greenrobot.greendao.AbstractDao;
import org.greenrobot.greendao.Property;
import org.greenrobot.greendao.internal.DaoConfig;
import org.greenrobot.greendao.database.Database;
import org.greenrobot.greendao.database.DatabaseStatement;

/**
 * DAO for table "PTOKENS_INDEX".
 */
public class PtokensIndexDao extends AbstractDao<PtokensIndex, Long> {

    public static final String TABLENAME = "PTOKENS_INDEX";

    /**
     * Properties of entity PtokensIndex.<br/>
     * Can be used for QueryBuilder and for referencing column names.
     */
    public static class Properties {
        public final static Property Gid = new Property(0, long.class, "gid", true, "GID");
        public final static Property Ptokens = new Property(1, String.class, "ptokens", false, "PTOKENS");
        public final static Property Pages = new Property(2, int.class, "pages", false, "PAGES");
        public final static Property UpdatedAt = new Property(3, long.class, "updatedAt", false, "UPDATED_AT");
    }

    public PtokensIndexDao(DaoConfig config) {
        super(config);
    }

    public PtokensIndexDao(DaoConfig config, DaoSession daoSession) {
        super(config, daoSession);
    }

    /** Creates the underlying database table. */
    public static void createTable(Database db, boolean ifNotExists) {
        String constraint = ifNotExists ? "IF NOT EXISTS " : "";
        db.execSQL("CREATE TABLE " + constraint + "\"PTOKENS_INDEX\" (" +
                "\"GID\" INTEGER PRIMARY KEY NOT NULL ," +
                "\"PTOKENS\" TEXT," +
                "\"PAGES\" INTEGER NOT NULL ," +
                "\"UPDATED_AT\" INTEGER NOT NULL );");
    }

    /** Drops the underlying database table. */
    public static void dropTable(Database db, boolean ifExists) {
        String sql = "DROP TABLE " + (ifExists ? "IF EXISTS " : "") + "\"PTOKENS_INDEX\"";
        db.execSQL(sql);
    }

    @Override
    protected final void bindValues(DatabaseStatement stmt, PtokensIndex entity) {
        stmt.clearBindings();
        stmt.bindLong(1, entity.getGid());

        String ptokens = entity.getPtokens();
        if (ptokens != null) {
            stmt.bindString(2, ptokens);
        }
        stmt.bindLong(3, entity.getPages());
        stmt.bindLong(4, entity.getUpdatedAt());
    }

    @Override
    protected final void bindValues(SQLiteStatement stmt, PtokensIndex entity) {
        stmt.clearBindings();
        stmt.bindLong(1, entity.getGid());

        String ptokens = entity.getPtokens();
        if (ptokens != null) {
            stmt.bindString(2, ptokens);
        }
        stmt.bindLong(3, entity.getPages());
        stmt.bindLong(4, entity.getUpdatedAt());
    }

    @Override
    public Long readKey(Cursor cursor, int offset) {
        return cursor.getLong(offset + 0);
    }

    @Override
    public PtokensIndex readEntity(Cursor cursor, int offset) {
        PtokensIndex entity = new PtokensIndex(
            cursor.getLong(offset + 0),
            cursor.isNull(offset + 1) ? null : cursor.getString(offset + 1),
            cursor.getInt(offset + 2),
            cursor.getLong(offset + 3)
        );
        return entity;
    }

    @Override
    public void readEntity(Cursor cursor, PtokensIndex entity, int offset) {
        entity.setGid(cursor.getLong(offset + 0));
        entity.setPtokens(cursor.isNull(offset + 1) ? null : cursor.getString(offset + 1));
        entity.setPages(cursor.getInt(offset + 2));
        entity.setUpdatedAt(cursor.getLong(offset + 3));
    }

    @Override
    protected final Long updateKeyAfterInsert(PtokensIndex entity, long rowId) {
        entity.setGid(rowId);
        return rowId;
    }

    @Override
    public Long getKey(PtokensIndex entity) {
        if (entity != null) {
            return entity.getGid();
        } else {
            return null;
        }
    }

    @Override
    protected final boolean isEntityUpdateable() {
        return true;
    }
}
