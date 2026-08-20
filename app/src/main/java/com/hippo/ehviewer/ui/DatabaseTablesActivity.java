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

package com.hippo.ehviewer.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.database.DatabaseManager;
import com.hippo.ehviewer.task.ExportDatabaseFileTask;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据库表列表Activity
 * 展示某个数据库下的所有数据表（名称+行数），点击行进入表内容查看器，右侧信息按钮查看列结构
 */
public class DatabaseTablesActivity extends ToolbarActivity
        implements DatabaseTablesAdapter.OnTableActionListener {

    public static final String EXTRA_DB_NAME = "extra_db_name";
    public static final String EXTRA_DB_VERSION = "extra_db_version";

    private DatabaseManager mDatabaseManager;
    private DatabaseTablesAdapter mAdapter;
    private List<DatabaseManager.TableInfo> mTables = new ArrayList<>();
    private TextView mTip;
    private String mDbName;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_database_tables);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);

        mDbName = getIntent().getStringExtra(EXTRA_DB_NAME);
        if (mDbName == null) {
            mDbName = "";
        }
        int version = getIntent().getIntExtra(EXTRA_DB_VERSION, 0);
        setTitle(mDbName + " (v" + version + ")");

        RecyclerView recyclerView = (RecyclerView) ViewUtils.$$(this, R.id.recycler_view);
        mTip = (TextView) ViewUtils.$$(this, R.id.tip);

        mAdapter = new DatabaseTablesAdapter(mTables, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        recyclerView.setAdapter(mAdapter);

        mDatabaseManager = new DatabaseManager(this);
        loadTables();
    }

    private void loadTables() {
        final String dbName = mDbName;
        new Thread(() -> {
            List<DatabaseManager.TableInfo> tables = new ArrayList<>();
            try {
                tables = mDatabaseManager.getTables(dbName);
            } catch (Exception e) {
                // ignore, show empty
            }
            final List<DatabaseManager.TableInfo> result = tables;
            runOnUiThread(() -> {
                mTables.clear();
                mTables.addAll(result);
                mAdapter.notifyDataSetChanged();
                if (mTip != null) {
                    mTip.setVisibility(mTables.isEmpty() ? View.VISIBLE : View.GONE);
                }
            });
        }).start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.activity_database_tables, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_share_db) {
            DatabaseExportHelper.submitAndShare(this, new ExportDatabaseFileTask(this, mDbName));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onOpenTable(DatabaseManager.TableInfo table) {
        Context context = this;
        Intent intent = new Intent(context, TableDataViewerActivity.class);
        intent.putExtra(TableDataViewerActivity.EXTRA_DB_NAME, mDbName);
        intent.putExtra(TableDataViewerActivity.EXTRA_TABLE_NAME, table.name);
        intent.putExtra(TableDataViewerActivity.EXTRA_TABLE_ROW_COUNT, table.rowCount);
        ArrayList<String> columnNames = new ArrayList<>();
        for (DatabaseManager.ColumnInfo column : table.columns) {
            columnNames.add(column.name);
        }
        intent.putStringArrayListExtra(TableDataViewerActivity.EXTRA_TABLE_COLUMNS, columnNames);
        context.startActivity(intent);
    }

    @Override
    public void onShowTableInfo(DatabaseManager.TableInfo table) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < table.columns.size(); i++) {
            DatabaseManager.ColumnInfo column = table.columns.get(i);
            sb.append(i + 1).append(". ").append(column.name)
                    .append(" (").append(column.type == null ? "" : column.type).append(")");
            if (column.primaryKey) {
                sb.append(" [PK]");
            }
            if (column.notnull) {
                sb.append(" [NOT NULL]");
            }
            if (column.defaultValue != null) {
                sb.append("\n    默认值: ").append(column.defaultValue);
            }
            sb.append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.database_viewer_table_info, table.name))
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}