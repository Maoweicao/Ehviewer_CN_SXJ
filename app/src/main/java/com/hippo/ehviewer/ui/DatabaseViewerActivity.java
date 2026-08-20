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
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.database.DatabaseManager;
import com.hippo.ehviewer.task.ExportDatabaseFileTask;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据库查看Activity
 * 展示应用内所有数据库文件（名称+版本），支持进入查看表、导出分享单个数据库
 */
public class DatabaseViewerActivity extends ToolbarActivity
        implements DatabaseListAdapter.OnDatabaseActionListener {

    private DatabaseManager mDatabaseManager;
    private DatabaseListAdapter mAdapter;
    private List<DatabaseManager.DatabaseInfo> mDatabases = new ArrayList<>();
    private TextView mTip;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_database_viewer);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
        setTitle(R.string.settings_advanced_database_viewer);

        RecyclerView recyclerView = (RecyclerView) ViewUtils.$$(this, R.id.recycler_view);
        mTip = (TextView) ViewUtils.$$(this, R.id.tip);

        mAdapter = new DatabaseListAdapter(mDatabases, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        recyclerView.setAdapter(mAdapter);

        mDatabaseManager = new DatabaseManager(this);
        loadDatabases();
    }

    private void loadDatabases() {
        new Thread(() -> {
            List<DatabaseManager.DatabaseInfo> databases = mDatabaseManager.getDatabaseList();
            runOnUiThread(() -> {
                mDatabases.clear();
                mDatabases.addAll(databases);
                mAdapter.notifyDataSetChanged();
                updateTip();
            });
        }).start();
    }

    private void updateTip() {
        if (mTip != null) {
            mTip.setVisibility(mDatabases.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onOpenDatabase(DatabaseManager.DatabaseInfo db) {
        Context context = this;
        Intent intent = new Intent(context, DatabaseTablesActivity.class);
        intent.putExtra(DatabaseTablesActivity.EXTRA_DB_NAME, db.name);
        intent.putExtra(DatabaseTablesActivity.EXTRA_DB_VERSION, db.version);
        context.startActivity(intent);
    }

    @Override
    public void onShareDatabase(DatabaseManager.DatabaseInfo db) {
        DatabaseExportHelper.submitAndShare(this, new ExportDatabaseFileTask(this, db.name));
    }
}