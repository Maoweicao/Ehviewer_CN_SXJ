package com.hippo.ehviewer.dao;

import android.database.Cursor;
import android.database.sqlite.SQLiteStatement;

import org.greenrobot.greendao.AbstractDao;
import org.greenrobot.greendao.Property;
import org.greenrobot.greendao.database.Database;
import org.greenrobot.greendao.database.DatabaseStatement;
import org.greenrobot.greendao.internal.DaoConfig;

/** DAO for table "GALLERY_AI_INFO". */
public class GalleryAiInfoDao extends AbstractDao<GalleryAiInfo, Long> {
    public static final String TABLENAME = "GALLERY_AI_INFO";

    public static class Properties {
        public static final Property Gid = new Property(0, long.class, "gid", true, "GID");
        public static final Property Summary = new Property(1, String.class, "summary", false, "SUMMARY");
        public static final Property Tags = new Property(2, String.class, "tags", false, "TAGS");
        public static final Property Descriptions = new Property(3, String.class, "descriptions", false, "DESCRIPTIONS");
        public static final Property AestheticScore = new Property(4, float.class, "aestheticScore", false, "AESTHETIC_SCORE");
        public static final Property UpdatedAt = new Property(5, long.class, "updatedAt", false, "UPDATED_AT");
    }

    public GalleryAiInfoDao(DaoConfig config) { super(config); }
    public GalleryAiInfoDao(DaoConfig config, DaoSession daoSession) { super(config, daoSession); }

    public static void createTable(Database db, boolean ifNotExists) {
        db.execSQL("CREATE TABLE " + (ifNotExists ? "IF NOT EXISTS " : "") + "\"GALLERY_AI_INFO\" (" +
                "\"GID\" INTEGER PRIMARY KEY NOT NULL ,\"SUMMARY\" TEXT,\"TAGS\" TEXT," +
                "\"DESCRIPTIONS\" TEXT,\"AESTHETIC_SCORE\" REAL NOT NULL ,\"UPDATED_AT\" INTEGER NOT NULL );");
    }
    public static void dropTable(Database db, boolean ifExists) { db.execSQL("DROP TABLE " + (ifExists ? "IF EXISTS " : "") + "\"GALLERY_AI_INFO\""); }

    @Override protected final void bindValues(DatabaseStatement stmt, GalleryAiInfo e) { bind(stmt, e); }
    @Override protected final void bindValues(SQLiteStatement stmt, GalleryAiInfo e) { bind(stmt, e); }
    private static void bind(DatabaseStatement stmt, GalleryAiInfo e) {
        stmt.clearBindings(); stmt.bindLong(1, e.getGid());
        if (e.getSummary() != null) stmt.bindString(2, e.getSummary()); if (e.getTags() != null) stmt.bindString(3, e.getTags());
        if (e.getDescriptions() != null) stmt.bindString(4, e.getDescriptions());
        stmt.bindDouble(5, e.getAestheticScore()); stmt.bindLong(6, e.getUpdatedAt());
    }
    private static void bind(SQLiteStatement stmt, GalleryAiInfo e) {
        stmt.clearBindings(); stmt.bindLong(1, e.getGid());
        if (e.getSummary() != null) stmt.bindString(2, e.getSummary()); if (e.getTags() != null) stmt.bindString(3, e.getTags());
        if (e.getDescriptions() != null) stmt.bindString(4, e.getDescriptions());
        stmt.bindDouble(5, e.getAestheticScore()); stmt.bindLong(6, e.getUpdatedAt());
    }
    @Override public Long readKey(Cursor c, int o) { return c.getLong(o); }
    @Override public GalleryAiInfo readEntity(Cursor c, int o) { return new GalleryAiInfo(c.getLong(o), c.isNull(o+1)?null:c.getString(o+1), c.isNull(o+2)?null:c.getString(o+2), c.isNull(o+3)?null:c.getString(o+3), c.getFloat(o+4), c.getLong(o+5)); }
    @Override public void readEntity(Cursor c, GalleryAiInfo e, int o) { e.setGid(c.getLong(o)); e.setSummary(c.isNull(o+1)?null:c.getString(o+1)); e.setTags(c.isNull(o+2)?null:c.getString(o+2)); e.setDescriptions(c.isNull(o+3)?null:c.getString(o+3)); e.setAestheticScore(c.getFloat(o+4)); e.setUpdatedAt(c.getLong(o+5)); }
    @Override protected final Long updateKeyAfterInsert(GalleryAiInfo e, long rowId) { e.setGid(rowId); return rowId; }
    @Override public Long getKey(GalleryAiInfo e) { return e == null ? null : e.getGid(); }
    @Override protected final boolean isEntityUpdateable() { return true; }
}
