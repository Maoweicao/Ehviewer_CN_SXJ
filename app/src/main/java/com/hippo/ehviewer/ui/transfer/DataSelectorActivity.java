/*
 * Copyright 2025 EhViewer Contributors
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

package com.hippo.ehviewer.ui.transfer;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.ui.ToolbarActivity;
import com.hippo.ehviewer.ui.transfer.adapter.DataSelectorAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据选择器Activity
 * 支持分页浏览和选择
 */
public class DataSelectorActivity extends ToolbarActivity {

    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_SELECTED_GIDS = "selected_gids";

    public static final String TYPE_BOOKMARKS = "bookmarks";
    public static final String TYPE_DOWNLOADS = "downloads";
    public static final String TYPE_FAVORITES = "favorites";

    private String type;
    private int currentPage = 1;
    private int pageSize = 20;
    private int totalPages = 1;
    private int totalItems = 0;

    private List<GalleryInfo> allItems = new ArrayList<>();
    private DataSelectorAdapter adapter;

    // UI components
    private TextView titleText;
    private TextView selectedCountText;
    private Button selectAllButton;
    private Button confirmButton;
    private RecyclerView dataList;
    private Button prevPageButton;
    private Button nextPageButton;
    private TextView pageInfoText;
    private Spinner pageSizeSpinner;

    public static Intent createIntent(Context context, String type) {
        Intent intent = new Intent(context, DataSelectorActivity.class);
        intent.putExtra(EXTRA_TYPE, type);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data_selector);

        type = getIntent().getStringExtra(EXTRA_TYPE);
        if (type == null) {
            finish();
            return;
        }

        initializeUI();
        loadData();
    }

    private void initializeUI() {
        titleText = findViewById(R.id.title_text);
        selectedCountText = findViewById(R.id.selected_count);
        selectAllButton = findViewById(R.id.select_all_button);
        confirmButton = findViewById(R.id.confirm_button);
        dataList = findViewById(R.id.data_list);
        prevPageButton = findViewById(R.id.prev_page_button);
        nextPageButton = findViewById(R.id.next_page_button);
        pageInfoText = findViewById(R.id.page_info);
        pageSizeSpinner = findViewById(R.id.page_size_spinner);

        // 设置标题
        titleText.setText(getTitleForType(type));

        // 设置适配器
        adapter = new DataSelectorAdapter(this);
        adapter.setOnSelectionChangedListener(count -> {
            selectedCountText.setText(getString(R.string.selected_count, count));
        });
        dataList.setLayoutManager(new LinearLayoutManager(this));
        dataList.setAdapter(adapter);

        // 设置按钮
        selectAllButton.setOnClickListener(v -> {
            if (adapter.getSelectedCount() == adapter.getItemCount()) {
                adapter.deselectAll();
                selectAllButton.setText(R.string.select_all);
            } else {
                adapter.selectAll();
                selectAllButton.setText(R.string.deselect_all);
            }
        });

        confirmButton.setOnClickListener(v -> confirmSelection());

        prevPageButton.setOnClickListener(v -> {
            if (currentPage > 1) {
                currentPage--;
                updatePage();
            }
        });

        nextPageButton.setOnClickListener(v -> {
            if (currentPage < totalPages) {
                currentPage++;
                updatePage();
            }
        });

        // 设置分页大小Spinner
        setupPageSizeSpinner();

        // 读取分页大小设置
        pageSize = Settings.getSelectorPageSize();
    }

    private void setupPageSizeSpinner() {
        String[] sizes = {"10", "20", "50", "100"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, sizes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pageSizeSpinner.setAdapter(adapter);

        // 设置默认选中
        int defaultIndex = 1; // 20
        for (int i = 0; i < sizes.length; i++) {
            if (Integer.parseInt(sizes[i]) == pageSize) {
                defaultIndex = i;
                break;
            }
        }
        pageSizeSpinner.setSelection(defaultIndex);

        pageSizeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int newSize = Integer.parseInt(sizes[position]);
                if (newSize != pageSize) {
                    pageSize = newSize;
                    Settings.putSelectorPageSize(pageSize);
                    currentPage = 1;
                    updatePage();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void loadData() {
        switch (type) {
            case TYPE_BOOKMARKS:
                // TODO: Implement bookmarks loading
                allItems = new ArrayList<>();
                break;
            case TYPE_DOWNLOADS:
                List<DownloadInfo> downloads = com.hippo.ehviewer.EhDB.getAllDownloadInfo();
                allItems = new ArrayList<>(downloads);
                break;
            case TYPE_FAVORITES:
                // TODO: Implement favorites loading
                allItems = new ArrayList<>();
                break;
        }

        totalItems = allItems.size();
        totalPages = Math.max(1, (totalItems + pageSize - 1) / pageSize);
        currentPage = 1;

        updatePage();
    }

    private void updatePage() {
        int startIndex = (currentPage - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalItems);

        List<GalleryInfo> pageItems;
        if (startIndex < totalItems) {
            pageItems = allItems.subList(startIndex, endIndex);
        } else {
            pageItems = new ArrayList<>();
        }

        adapter.setItems(pageItems);

        // 更新分页信息
        pageInfoText.setText(getString(R.string.page_info, currentPage, totalPages));
        prevPageButton.setEnabled(currentPage > 1);
        nextPageButton.setEnabled(currentPage < totalPages);

        // 更新全选按钮状态
        updateSelectAllButton();
    }

    private void updateSelectAllButton() {
        if (adapter.getSelectedCount() == adapter.getItemCount() && adapter.getItemCount() > 0) {
            selectAllButton.setText(R.string.deselect_all);
        } else {
            selectAllButton.setText(R.string.select_all);
        }
    }

    private void confirmSelection() {
        List<Long> selectedGids = adapter.getSelectedGids();
        if (selectedGids.isEmpty()) {
            setResult(RESULT_CANCELED);
        } else {
            Intent resultIntent = new Intent();
            resultIntent.putExtra(EXTRA_TYPE, type);
            resultIntent.putExtra(EXTRA_SELECTED_GIDS, new ArrayList<>(selectedGids));
            setResult(RESULT_OK, resultIntent);
        }
        finish();
    }

    private int getTitleForType(String type) {
        switch (type) {
            case TYPE_BOOKMARKS:
                return R.string.select_bookmarks;
            case TYPE_DOWNLOADS:
                return R.string.select_downloads;
            case TYPE_FAVORITES:
                return R.string.select_favorites;
            default:
                return R.string.select_data;
        }
    }
}
