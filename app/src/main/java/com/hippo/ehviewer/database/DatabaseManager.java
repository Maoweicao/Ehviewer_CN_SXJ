/*
 * Copyright 2025 Hippo Seven
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

package com.hippo.ehviewer.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库管理工具类
 * 用于查看应用内所有数据库的版本、表、内容等信息
 */
public class DatabaseManager {
    private static final String TAG = DatabaseManager.class.getSimpleName();
    private final Context mContext;

    public DatabaseManager(Context context) {
        this.mContext = context;
    }

    /**
     * 获取所有数据库文件（轻量：仅名称与版本，懒加载表信息）
     */
    public List<DatabaseInfo> getDatabaseList() {
        List<DatabaseInfo> databases = new ArrayList<>();
        File databaseDir = mContext.getDatabasePath("dummy").getParentFile();
        
        if (databaseDir != null && databaseDir.exists()) {
            File[] files = databaseDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (file.isFile() && !name.endsWith("-journal") && !name.endsWith("-wal") && !name.endsWith("-shm")) {
                        try {
                            DatabaseInfo info = new DatabaseInfo();
                            info.name = name;
                            info.version = getDatabaseVersion(name);
                            databases.add(info);
                        } catch (Exception e) {
                            Log.e(TAG, "Error reading database: " + file.getName(), e);
                        }
                    }
                }
            }
        }
        
        return databases;
    }

    /**
     * 获取所有数据库文件（兼容旧接口，加载完整表信息）
     */
    public List<DatabaseInfo> getAllDatabases() {
        List<DatabaseInfo> databases = new ArrayList<>();
        File databaseDir = mContext.getDatabasePath("dummy").getParentFile();
        
        if (databaseDir != null && databaseDir.exists()) {
            File[] files = databaseDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (file.isFile() && !name.endsWith("-journal") && !name.endsWith("-wal") && !name.endsWith("-shm")) {
                        try {
                            DatabaseInfo info = getDatabaseInfo(name);
                            if (info != null) {
                                databases.add(info);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error reading database: " + file.getName(), e);
                        }
                    }
                }
            }
        }
        
        return databases;
    }

    /**
     * 读取单个数据库的版本号
     */
    private int getDatabaseVersion(String dbName) {
        SQLiteDatabase db = mContext.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null);
        try {
            return db.getVersion();
        } finally {
            db.close();
        }
    }

    /**
     * 获取某个数据库中的所有表（懒加载：不含数据内容）
     */
    public List<TableInfo> getTables(String dbName) {
        SQLiteDatabase db = mContext.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null);
        try {
            return getTables(db);
        } finally {
            db.close();
        }
    }

    /**
     * 分页获取表数据，支持可选 WHERE 过滤条件。
     *
     * @param whereClause 可直接拼接在 WHERE 后的 SQL 表达式（可为 null/空）
     */
    public List<Map<String, String>> getTableData(String dbName, String tableName,
                                                  String whereClause, int offset, int limit) {
        List<Map<String, String>> dataList = new ArrayList<>();
        SQLiteDatabase db = mContext.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null);
        Cursor cursor = null;
        try {
            String sql = buildSelectSql(tableName, whereClause, offset, limit);
            cursor = db.rawQuery(sql, null);
            String[] columnNames = cursor.getColumnNames();
            while (cursor.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columnNames.length; i++) {
                    String value = cursor.getString(i);
                    row.put(columnNames[i], value != null ? value : "NULL");
                }
                dataList.add(row);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            db.close();
        }
        return dataList;
    }

    /**
     * 统计表数据行数，支持可选 WHERE 过滤条件。
     */
    public int getRowCount(String dbName, String tableName, String whereClause) {
        SQLiteDatabase db = mContext.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null);
        Cursor cursor = null;
        try {
            String sql = "SELECT COUNT(*) FROM " + tableName;
            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql += " WHERE (" + whereClause + ")";
            }
            cursor = db.rawQuery(sql, null);
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting row count for table: " + tableName, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            db.close();
        }
        return 0;
    }

    /**
     * 构建带 WHERE 与分页的 SELECT 语句
     */
    private String buildSelectSql(String tableName, String whereClause, int offset, int limit) {
        StringBuilder sb = new StringBuilder("SELECT * FROM ").append(tableName);
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sb.append(" WHERE (").append(whereClause).append(")");
        }
        sb.append(" LIMIT ").append(limit).append(" OFFSET ").append(offset);
        return sb.toString();
    }

    /**
     * 获取单个数据库的信息
     */
    public DatabaseInfo getDatabaseInfo(String dbName) {
        try {
            SQLiteDatabase db = mContext.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null);
            DatabaseInfo info = new DatabaseInfo();
            info.name = dbName;
            info.version = db.getVersion();
            info.tables = getTables(db);
            db.close();
            return info;
        } catch (Exception e) {
            Log.e(TAG, "Error getting database info for: " + dbName, e);
            return null;
        }
    }

    /**
     * 获取数据库中的所有表
     */
    public List<TableInfo> getTables(SQLiteDatabase db) {
        List<TableInfo> tables = new ArrayList<>();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null);
            
            while (cursor.moveToNext()) {
                String tableName = cursor.getString(0);
                TableInfo tableInfo = new TableInfo();
                tableInfo.name = tableName;
                tableInfo.rowCount = getRowCount(db, tableName);
                tableInfo.columns = getColumns(db, tableName);
                tableInfo.data = new ArrayList<>();
                tables.add(tableInfo);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        return tables;
    }

    /**
     * 获取表的行数
     */
    public int getRowCount(SQLiteDatabase db, String tableName) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + tableName, null);
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting row count for table: " + tableName, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0;
    }

    /**
     * 获取表的列信息
     */
    public List<ColumnInfo> getColumns(SQLiteDatabase db, String tableName) {
        List<ColumnInfo> columns = new ArrayList<>();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery("PRAGMA table_info(" + tableName + ")", null);
            
            while (cursor.moveToNext()) {
                ColumnInfo column = new ColumnInfo();
                column.name = cursor.getString(1);
                column.type = cursor.getString(2);
                column.notnull = cursor.getInt(3) == 1;
                column.defaultValue = cursor.getString(4);
                column.primaryKey = cursor.getInt(5) == 1;
                columns.add(column);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        return columns;
    }

    /**
     * 获取表的数据（限制前100行）
     */
    public List<Map<String, String>> getTableData(SQLiteDatabase db, String tableName) {
        List<Map<String, String>> dataList = new ArrayList<>();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery("SELECT * FROM " + tableName + " LIMIT 100", null);
            String[] columnNames = cursor.getColumnNames();
            
            while (cursor.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columnNames.length; i++) {
                    String value = cursor.getString(i);
                    row.put(columnNames[i], value != null ? value : "NULL");
                }
                dataList.add(row);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        return dataList;
    }

    /**
     * 数据库信息类
     */
    public static class DatabaseInfo {
        public String name;
        public int version;
        public List<TableInfo> tables;
    }

    /**
     * 表信息类
     */
    public static class TableInfo {
        public String name;
        public int rowCount;
        public List<ColumnInfo> columns;
        public List<Map<String, String>> data;
    }

    /**
     * 列信息类
     */
    public static class ColumnInfo {
        public String name;
        public String type;
        public boolean notnull;
        public String defaultValue;
        public boolean primaryKey;
    }
}
