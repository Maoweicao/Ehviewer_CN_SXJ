package com.hippo.ehviewer.dao;

import android.database.Cursor;
import android.database.sqlite.SQLiteStatement;

import org.greenrobot.greendao.AbstractDao;
import org.greenrobot.greendao.Property;
import org.greenrobot.greendao.internal.DaoConfig;
import org.greenrobot.greendao.database.Database;
import org.greenrobot.greendao.database.DatabaseStatement;
import org.greenrobot.greendao.annotation.NotNull;

public class MilestoneDao extends AbstractDao<MilestoneInfo, Long> {

    public static final String TABLENAME = "MILESTONE";

    public static class Properties {
        public final static Property Id = new Property(0, Long.class, "id", true, "_id");
        public final static Property Key = new Property(1, String.class, "key", false, "KEY");
        public final static Property Value = new Property(2, String.class, "value", false, "VALUE");
        public final static Property UpdateTime = new Property(3, long.class, "updateTime", false, "UPDATE_TIME");
    }

    public MilestoneDao(DaoConfig config) {
        super(config);
    }

    public MilestoneDao(DaoConfig config, DaoSession daoSession) {
        super(config, daoSession);
    }

    public static void createTable(Database db, boolean ifNotExists) {
        String constraint = ifNotExists ? "IF NOT EXISTS " : "";
        db.execSQL("CREATE TABLE " + constraint + "\"" + TABLENAME + "\" (" +
                "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT ," +
                "\"KEY\" TEXT NOT NULL UNIQUE ," +
                "\"VALUE\" TEXT," +
                "\"UPDATE_TIME\" INTEGER NOT NULL );");
    }

    public static void dropTable(Database db, boolean ifExists) {
        String sql = "DROP TABLE " + (ifExists ? "IF EXISTS " : "") + "\"" + TABLENAME + "\"";
        db.execSQL(sql);
    }

    @Override
    protected final void bindValues(DatabaseStatement stmt, MilestoneInfo entity) {
        stmt.clearBindings();

        Long id = entity.getId();
        if (id != null) {
            stmt.bindLong(1, id);
        }

        stmt.bindString(2, entity.getKey());

        String value = entity.getValue();
        if (value != null) {
            stmt.bindString(3, value);
        }

        stmt.bindLong(4, entity.getUpdateTime());
    }

    @Override
    protected final void bindValues(SQLiteStatement stmt, MilestoneInfo entity) {
        stmt.clearBindings();

        Long id = entity.getId();
        if (id != null) {
            stmt.bindLong(1, id);
        }

        stmt.bindString(2, entity.getKey());

        String value = entity.getValue();
        if (value != null) {
            stmt.bindString(3, value);
        }

        stmt.bindLong(4, entity.getUpdateTime());
    }

    @Override
    public Long readKey(Cursor cursor, int offset) {
        return cursor.isNull(offset + 0) ? null : cursor.getLong(offset + 0);
    }

    @Override
    public MilestoneInfo readEntity(Cursor cursor, int offset) {
        MilestoneInfo entity = new MilestoneInfo(
                cursor.isNull(offset + 0) ? null : cursor.getLong(offset + 0),
                cursor.getString(offset + 1),
                cursor.isNull(offset + 2) ? null : cursor.getString(offset + 2),
                cursor.getLong(offset + 3)
        );
        return entity;
    }

    @Override
    public void readEntity(Cursor cursor, MilestoneInfo entity, int offset) {
        entity.setId(cursor.isNull(offset + 0) ? null : cursor.getLong(offset + 0));
        entity.setKey(cursor.getString(offset + 1));
        entity.setValue(cursor.isNull(offset + 2) ? null : cursor.getString(offset + 2));
        entity.setUpdateTime(cursor.getLong(offset + 3));
    }

    @Override
    protected final Long updateKeyAfterInsert(MilestoneInfo entity, long rowId) {
        entity.setId(rowId);
        return rowId;
    }

    @Override
    public Long getKey(MilestoneInfo entity) {
        if (entity != null) {
            return entity.getId();
        } else {
            return null;
        }
    }

    @Override
    protected final boolean isEntityUpdateable() {
        return true;
    }
}
