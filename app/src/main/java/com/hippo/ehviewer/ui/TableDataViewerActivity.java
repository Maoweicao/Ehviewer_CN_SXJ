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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.database.DatabaseManager;
import com.hippo.ehviewer.task.ExportTableCsvTask;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据表内容查看器
 * 支持普通关键词 / WHERE 表达式双模式搜索、分页浏览、导出 CSV 与复制到剪贴板
 */
public class TableDataViewerActivity extends ToolbarActivity {

    public static final String EXTRA_DB_NAME = "extra_db_name";
    public static final String EXTRA_TABLE_NAME = "extra_table_name";
    public static final String EXTRA_TABLE_ROW_COUNT = "extra_table_row_count";
    public static final String EXTRA_TABLE_COLUMNS = "extra_table_columns";

    private static final int[] PAGE_SIZES = {10, 50, 100};
    private static final long SEARCH_DEBOUNCE_MS = 400;

    private DatabaseManager mDatabaseManager;

    private String mDbName;
    private String mTableName;
    private ArrayList<String> mColumns = new ArrayList<>();

    private LinearLayout mTableContainer;
    private HorizontalScrollView mTableScroll;
    private TextView mTableEmpty;
    private EditText mSearchInput;
    private TextView mSearchModeToggle;
    private ImageView mSearchClear;
    private TextView mPrevButton;
    private TextView mNextButton;
    private TextView mPageText;
    private TextView mTotalRowsText;
    private Spinner mPageSizeSpinner;

    private List<Map<String, String>> mData = new ArrayList<>();
    private boolean mIsWhereMode = false;
    private String mWhereClause;
    private int mCurrentPage = 0;
    private int mPageSize = 50;
    private int mTotalRows = 0;
    private boolean mSuppressSearch = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSearchRunnable = this::applySearch;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_table_data_viewer);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);

        mDbName = getIntent().getStringExtra(EXTRA_DB_NAME);
        mTableName = getIntent().getStringExtra(EXTRA_TABLE_NAME);
        if (mDbName == null) mDbName = "";
        if (mTableName == null) mTableName = "";
        mTotalRows = getIntent().getIntExtra(EXTRA_TABLE_ROW_COUNT, 0);
        List<String> columns = getIntent().getStringArrayListExtra(EXTRA_TABLE_COLUMNS);
        if (columns != null) {
            mColumns.addAll(columns);
        }
        setTitle(mTableName);

        mDatabaseManager = new DatabaseManager(this);

        mTableContainer = (LinearLayout) ViewUtils.$$(this, R.id.table_container);
        mTableScroll = (HorizontalScrollView) ViewUtils.$$(this, R.id.table_scroll);
        mTableEmpty = (TextView) ViewUtils.$$(this, R.id.table_empty);
        mSearchInput = (EditText) ViewUtils.$$(this, R.id.search_input);
        mSearchModeToggle = (TextView) ViewUtils.$$(this, R.id.search_mode_toggle);
        mSearchClear = (ImageView) ViewUtils.$$(this, R.id.search_clear);
        mPrevButton = (TextView) ViewUtils.$$(this, R.id.button_prev);
        mNextButton = (TextView) ViewUtils.$$(this, R.id.button_next);
        mPageText = (TextView) ViewUtils.$$(this, R.id.text_page);
        mTotalRowsText = (TextView) ViewUtils.$$(this, R.id.text_total_rows);
        mPageSizeSpinner = (Spinner) ViewUtils.$$(this, R.id.spinner_page_size);

        setupSearchBar();
        setupPagination();
        reloadData();
    }

    private void setupSearchBar() {
        updateModeUi();
        mSearchModeToggle.setOnClickListener(v -> {
            mIsWhereMode = !mIsWhereMode;
            updateModeUi();
            // 切回关键词模式时立即按当前文本过滤
            if (!mIsWhereMode) {
                mHandler.removeCallbacks(mSearchRunnable);
                mHandler.post(mSearchRunnable);
            }
        });
        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                mHandler.removeCallbacks(mSearchRunnable);
                mSearchRunnable.run();
                return true;
            }
            return false;
        });
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (mSuppressSearch || mIsWhereMode) {
                    return;
                }
                mHandler.removeCallbacks(mSearchRunnable);
                mHandler.postDelayed(mSearchRunnable, SEARCH_DEBOUNCE_MS);
            }
        });
        mSearchClear.setOnClickListener(v -> {
            mHandler.removeCallbacks(mSearchRunnable);
            mSuppressSearch = true;
            mSearchInput.setText("");
            mSuppressSearch = false;
            mWhereClause = null;
            mCurrentPage = 0;
            reloadData();
        });
    }

    private void updateModeUi() {
        if (mIsWhereMode) {
            mSearchModeToggle.setText(R.string.database_viewer_search_mode_where);
            mSearchInput.setHint(R.string.database_viewer_search_where_hint);
        } else {
            mSearchModeToggle.setText(R.string.database_viewer_search_mode_keyword);
            mSearchInput.setHint(R.string.database_viewer_search_hint);
        }
    }

    private void setupPagination() {
        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new Integer[]{10, 50, 100});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mPageSizeSpinner.setAdapter(adapter);
        int idx = 1;
        for (int i = 0; i < PAGE_SIZES.length; i++) {
            if (PAGE_SIZES[i] == mPageSize) {
                idx = i;
                break;
            }
        }
        mPageSizeSpinner.setSelection(idx);
        mPageSizeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int newSize = PAGE_SIZES[position];
                if (newSize != mPageSize) {
                    mPageSize = newSize;
                    mCurrentPage = 0;
                    reloadData();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        mPrevButton.setOnClickListener(v -> {
            if (mCurrentPage > 0) {
                mCurrentPage--;
                reloadData();
            }
        });
        mNextButton.setOnClickListener(v -> {
            if (mCurrentPage + 1 < getTotalPages()) {
                mCurrentPage++;
                reloadData();
            }
        });
    }

    private int getTotalPages() {
        if (mTotalRows <= 0) {
            return 0;
        }
        return (mTotalRows + mPageSize - 1) / mPageSize;
    }

    /**
     * 根据当前搜索模式与输入框内容，构建 SQL WHERE 表达式；无过滤时返回 null。
     */
    private String buildWhereClause() {
        String text = mSearchInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        if (mIsWhereMode) {
            return text;
        }
        // 普通关键词模式：任一列 LIKE 包含匹配
        String escaped = text.replace("'", "''");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mColumns.size(); i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            sb.append('(').append(mColumns.get(i)).append(" LIKE '%").append(escaped).append("%')");
        }
        return sb.toString();
    }

    private void applySearch() {
        mWhereClause = buildWhereClause();
        mCurrentPage = 0;
        reloadData();
    }

    private void reloadData() {
        final int page = mCurrentPage;
        final int size = mPageSize;
        final String where = mWhereClause;
        new Thread(() -> {
            final int[] status = {0};
            final int[] total = {0};
            final List<Map<String, String>>[] rows = new List[]{new ArrayList<>()};
            try {
                total[0] = mDatabaseManager.getRowCount(mDbName, mTableName, where);
                rows[0] = mDatabaseManager.getTableData(mDbName, mTableName, where, page * size, size);
            } catch (Exception e) {
                status[0] = -1;
            }
            final int s = status[0];
            final int t = total[0];
            final List<Map<String, String>> r = rows[0];
            runOnUiThread(() -> {
                if (s == -1) {
                    Toast.makeText(this, R.string.database_viewer_search_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                mTotalRows = t;
                mData = r;
                renderTable(mData);
                updatePagination();
            });
        }).start();
    }

    private void renderTable(List<Map<String, String>> data) {
        mTableContainer.removeAllViews();
        if (data == null || data.isEmpty()) {
            mTableEmpty.setVisibility(View.VISIBLE);
            return;
        }
        mTableEmpty.setVisibility(View.GONE);
        mTableContainer.addView(buildHeaderRow());
        int index = 0;
        for (Map<String, String> row : data) {
            mTableContainer.addView(buildDataRow(row, index % 2 == 1));
            index++;
        }
        // 回到最左侧，便于查看新一页
        mTableScroll.scrollTo(0, 0);
    }

    private LinearLayout buildHeaderRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(0xFFE3E3E3);
        for (String column : mColumns) {
            row.addView(createCell(column, true, false));
        }
        return row;
    }

    private LinearLayout buildDataRow(Map<String, String> data, boolean alt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(alt ? 0xFFF5F5F5 : 0xFFFFFFFF);
        for (String column : mColumns) {
            String value = data.get(column);
            row.addView(createCell(value != null ? value : "NULL", false, alt));
        }
        return row;
    }

    private TextView createCell(String text, boolean header, boolean alt) {
        TextView cell = new TextView(this);
        cell.setText(truncate(text, 80));
        cell.setTextSize(12);
        cell.setTextColor(header ? 0xFF212121 : 0xFF424242);
        cell.setTypeface(android.graphics.Typeface.DEFAULT, header ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        cell.setPadding(dp(8), dp(6), dp(8), dp(6));
        cell.setSingleLine(true);
        cell.setEllipsize(TextUtils.TruncateAt.END);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setLayoutParams(new LinearLayout.LayoutParams(dp(160), dp(32)));
        return cell;
    }

    private void updatePagination() {
        int totalPages = getTotalPages();
        if (mTotalRows <= 0) {
            mTableEmpty.setVisibility(View.VISIBLE);
        }
        mPageText.setText(getString(R.string.database_viewer_page_info, mCurrentPage + 1, Math.max(totalPages, 1)));
        mTotalRowsText.setText(getString(R.string.database_viewer_total_rows, mTotalRows));
        mPrevButton.setEnabled(mCurrentPage > 0);
        mNextButton.setEnabled(mCurrentPage + 1 < totalPages);
        mPrevButton.setAlpha(mPrevButton.isEnabled() ? 1.0f : 0.4f);
        mNextButton.setAlpha(mNextButton.isEnabled() ? 1.0f : 0.4f);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.activity_table_data_viewer, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_export_csv) {
            DatabaseExportHelper.submitAndShare(this,
                    new ExportTableCsvTask(this, mDbName, mTableName, mWhereClause));
            return true;
        } else if (id == R.id.action_copy_csv) {
            copyCsv();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void copyCsv() {
        if (mData.isEmpty()) {
            Toast.makeText(this, R.string.database_viewer_table_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            final String csv = buildCsvText();
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("CSV", csv));
                Toast.makeText(this, R.string.database_viewer_copy_done, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private String buildCsvText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mColumns.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvEscape(mColumns.get(i)));
        }
        sb.append('\n');
        for (Map<String, String> row : mData) {
            for (int i = 0; i < mColumns.size(); i++) {
                if (i > 0) sb.append(',');
                String value = row.get(mColumns.get(i));
                sb.append(csvEscape(value != null ? value : "NULL"));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String csvEscape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String truncate(String text, int maxLength) {
        if (text != null && text.length() > maxLength) {
            return text.substring(0, maxLength) + "…";
        }
        return text;
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }
}