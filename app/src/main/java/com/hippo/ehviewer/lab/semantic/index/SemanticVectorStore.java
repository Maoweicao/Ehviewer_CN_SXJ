package com.hippo.ehviewer.lab.semantic.index;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.hippo.ehviewer.lab.semantic.SemanticConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立的向量索引数据库。
 *
 * <p>刻意与主 GreenDAO 库隔离：语义索引可随实验开关整库删除，不影响主库 schema 版本，
 * 也不会在索引重建时与下载列表的读写互相阻塞。
 *
 * <p>存储格式：int8 对称量化（首 4 字节为 scale），万级条目下全表扫描
 * 只需几毫秒，因此不引入向量数据库。
 */
public class SemanticVectorStore extends SQLiteOpenHelper {

    private static final String TAG = "SemanticVectorStore";

    private static final String TABLE_VECTOR = "SEMANTIC_VECTOR";
    private static final String TABLE_META = "SEMANTIC_META";

    private static final String COL_KEY = "item_key";
    private static final String COL_KIND = "kind";
    private static final String COL_MODEL = "model";
    private static final String COL_DIM = "dim";
    private static final String COL_VEC = "vec";
    private static final String COL_UPDATED = "updated_at";

    private static volatile SemanticVectorStore sInstance;

    public static SemanticVectorStore getInstance(Context context) {
        if (sInstance == null) {
            synchronized (SemanticVectorStore.class) {
                if (sInstance == null) {
                    sInstance = new SemanticVectorStore(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private SemanticVectorStore(Context context) {
        super(context, SemanticConfig.VECTOR_DB_NAME, null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_VECTOR + " ("
                + COL_KEY + " TEXT NOT NULL, "
                + COL_KIND + " INTEGER NOT NULL DEFAULT 0, "
                + COL_MODEL + " TEXT, "
                + COL_DIM + " INTEGER NOT NULL, "
                + COL_VEC + " BLOB NOT NULL, "
                + COL_UPDATED + " INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (" + COL_KIND + ", " + COL_KEY + "))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_semantic_kind ON " + TABLE_VECTOR + " (" + COL_KIND + ")");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_META + " ("
                + "k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 实验室功能：升级直接重建，避免维护迁移
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_VECTOR);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_META);
        onCreate(db);
    }

    /** 写入或更新一条向量。 */
    public void upsert(String key, int kind, String model, float[] vector) {
        if (vector == null || vector.length == 0) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put(COL_KEY, key);
        values.put(COL_KIND, kind);
        values.put(COL_MODEL, model);
        values.put(COL_DIM, vector.length);
        values.put(COL_VEC, VectorMath.quantizeInt8(vector));
        values.put(COL_UPDATED, System.currentTimeMillis());
        try {
            getWritableDatabase().insertWithOnConflict(TABLE_VECTOR, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable t) {
            Log.w(TAG, "upsert failed key=" + key + ": " + t);
        }
    }

    /** 批量写入，使用事务降低开销。 */
    public void upsertBatch(List<String> keys, int kind, String model, List<float[]> vectors) {
        if (keys == null || vectors == null || keys.size() != vectors.size() || keys.isEmpty()) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < keys.size(); i++) {
                float[] vector = vectors.get(i);
                if (vector == null || vector.length == 0) {
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put(COL_KEY, keys.get(i));
                values.put(COL_KIND, kind);
                values.put(COL_MODEL, model);
                values.put(COL_DIM, vector.length);
                values.put(COL_VEC, VectorMath.quantizeInt8(vector));
                values.put(COL_UPDATED, System.currentTimeMillis());
                db.insertWithOnConflict(TABLE_VECTOR, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } catch (Throwable t) {
            Log.w(TAG, "upsertBatch failed: " + t);
        } finally {
            db.endTransaction();
        }
    }

    public void delete(String key, int kind) {
        try {
            getWritableDatabase().delete(TABLE_VECTOR, COL_KIND + "=? AND " + COL_KEY + "=?",
                    new String[]{String.valueOf(kind), key});
        } catch (Throwable t) {
            Log.w(TAG, "delete failed: " + t);
        }
    }

    public long count(int kind) {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE_VECTOR + " WHERE " + COL_KIND + "=?",
                    new String[]{String.valueOf(kind)});
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } catch (Throwable t) {
            return 0L;
        } finally {
            closeQuietly(cursor);
        }
    }

    public long countAll() {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE_VECTOR, null);
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } catch (Throwable t) {
            return 0L;
        } finally {
            closeQuietly(cursor);
        }
    }

    /** 已存在的条目 key（用于增量索引时跳过）。 */
    public List<String> existingKeys(int kind, String model) {
        List<String> keys = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query(TABLE_VECTOR,
                    new String[]{COL_KEY},
                    COL_KIND + "=? AND " + COL_MODEL + "=?",
                    new String[]{String.valueOf(kind), model},
                    null, null, null);
            while (cursor.moveToNext()) {
                keys.add(cursor.getString(0));
            }
        } catch (Throwable t) {
            Log.w(TAG, "existingKeys failed: " + t);
        } finally {
            closeQuietly(cursor);
        }
        return keys;
    }

    /** 一条已量化向量及其 key。 */
    public static final class Entry {
        public final String key;
        public final byte[] quantized;
        public final int dim;

        Entry(String key, byte[] quantized, int dim) {
            this.key = key;
            this.quantized = quantized;
            this.dim = dim;
        }
    }

    /** 全量加载指定类别、相同维度与模型的条目，用于内存暴力扫描。 */
    public List<Entry> loadAll(int kind, String model, int dim) {
        List<Entry> result = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query(TABLE_VECTOR,
                    new String[]{COL_KEY, COL_VEC, COL_DIM},
                    COL_KIND + "=? AND " + COL_MODEL + "=? AND " + COL_DIM + "=?",
                    new String[]{String.valueOf(kind), model, String.valueOf(dim)},
                    null, null, null);
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                byte[] blob = cursor.getBlob(1);
                int entryDim = cursor.getInt(2);
                if (blob != null && entryDim == dim) {
                    result.add(new Entry(key, blob, dim));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "loadAll failed: " + t);
        } finally {
            closeQuietly(cursor);
        }
        return result;
    }

    /** 全量扫描检索：返回 key → 相似度，按相似度降序，最多 limit 条。 */
    public List<Scored> search(int kind, String model, int dim, float[] queryVector, int limit) {
        List<Entry> entries = loadAll(kind, model, dim);
        List<Scored> scored = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            float score = VectorMath.cosineInt8(entry.quantized, queryVector);
            scored.add(new Scored(entry.key, score));
        }
        scored.sort((a, b) -> Float.compare(b.score, a.score));
        return scored.size() > limit ? new ArrayList<>(scored.subList(0, limit)) : scored;
    }

    /** 检索结果条目。 */
    public static final class Scored {
        public final String key;
        public final float score;

        Scored(String key, float score) {
            this.key = key;
            this.score = score;
        }
    }

    /** 清空某类别的索引。 */
    public void clear(int kind) {
        try {
            getWritableDatabase().delete(TABLE_VECTOR, COL_KIND + "=?",
                    new String[]{String.valueOf(kind)});
        } catch (Throwable t) {
            Log.w(TAG, "clear failed: " + t);
        }
    }

    /** 清空全部并关闭数据库（关闭功能时调用）。 */
    public void clearAll() {
        try {
            getWritableDatabase().delete(TABLE_VECTOR, null, null);
            getWritableDatabase().delete(TABLE_META, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "clearAll failed: " + t);
        }
    }

    /** 模型标识写入元表，用于检测模型切换后是否需要重建索引。 */
    public void putMeta(String key, String value) {
        ContentValues values = new ContentValues();
        values.put("k", key);
        values.put("v", value);
        try {
            getWritableDatabase().insertWithOnConflict(TABLE_META, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable t) {
            Log.w(TAG, "putMeta failed: " + t);
        }
    }

    public String getMeta(String key) {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query(TABLE_META, new String[]{"v"},
                    "k=?", new String[]{key}, null, null, null);
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(cursor);
        }
    }

    private static void closeQuietly(Cursor cursor) {
        if (cursor != null) {
            try {
                cursor.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }
}