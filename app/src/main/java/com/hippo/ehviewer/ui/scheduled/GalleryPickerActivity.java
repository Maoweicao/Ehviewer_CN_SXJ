package com.hippo.ehviewer.ui.scheduled;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.EhDB;

import java.util.ArrayList;
import java.util.List;

/**
 * 画廊选择器
 */
public class GalleryPickerActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_SELECTED_GIDS = "selected_gids";

    public static final String MODE_SINGLE = "single";
    public static final String MODE_MULTIPLE = "multiple";

    public static final String SOURCE_DOWNLOADS = "downloads";
    public static final String SOURCE_FAVORITES = "favorites";
    public static final String SOURCE_ALL = "all";

    private String mode = MODE_MULTIPLE;
    private String source = SOURCE_ALL;

    private RecyclerView galleryList;
    private GalleryPickerAdapter adapter;
    private TextView selectedCountText;
    private EditText searchInput;

    private List<GalleryInfo> allItems = new ArrayList<>();
    private List<GalleryInfo> filteredItems = new ArrayList<>();
    private int currentPage = 1;
    private int pageSize = 20;
    private int totalPages = 1;

    public static Intent createIntent(Context context, String mode, String source) {
        Intent intent = new Intent(context, GalleryPickerActivity.class);
        intent.putExtra(EXTRA_MODE, mode);
        intent.putExtra(EXTRA_SOURCE, source);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery_picker);

        mode = getIntent().getStringExtra(EXTRA_MODE);
        source = getIntent().getStringExtra(EXTRA_SOURCE);
        if (mode == null) mode = MODE_MULTIPLE;
        if (source == null) source = SOURCE_ALL;

        initToolbar();
        initViews();
        initTabs();
        loadData();
    }

    private void initToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void initViews() {
        galleryList = findViewById(R.id.gallery_list);
        selectedCountText = findViewById(R.id.selected_count);
        searchInput = findViewById(R.id.search_input);

        adapter = new GalleryPickerAdapter(this, mode.equals(MODE_MULTIPLE));
        adapter.setOnSelectionChangedListener(count -> {
            selectedCountText.setText(getString(R.string.gallery_picker_selected_count, count, filteredItems.size()));
        });

        galleryList.setLayoutManager(new LinearLayoutManager(this));
        galleryList.setAdapter(adapter);

        // 搜索
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterItems(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 分页按钮
        findViewById(R.id.prev_page_button).setOnClickListener(v -> {
            if (currentPage > 1) {
                currentPage--;
                updatePage();
            }
        });

        findViewById(R.id.next_page_button).setOnClickListener(v -> {
            if (currentPage < totalPages) {
                currentPage++;
                updatePage();
            }
        });

        // 全选按钮
        findViewById(R.id.select_all_button).setOnClickListener(v -> {
            if (adapter.getSelectedCount() == adapter.getItemCount()) {
                adapter.deselectAll();
            } else {
                adapter.selectAll();
            }
        });

        // 确认按钮
        findViewById(R.id.confirm_button).setOnClickListener(v -> confirmSelection());

        // 分页大小
        Spinner pageSizeSpinner = findViewById(R.id.page_size_spinner);
        String[] sizes = {"10", "20", "50", "100"};
        ArrayAdapter<String> sizeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, sizes);
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pageSizeSpinner.setAdapter(sizeAdapter);
        pageSizeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int newSize = Integer.parseInt(sizes[position]);
                if (newSize != pageSize) {
                    pageSize = newSize;
                    currentPage = 1;
                    updatePage();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void initTabs() {
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        
        tabLayout.addTab(tabLayout.newTab().setText(R.string.gallery_picker_tab_all));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.gallery_picker_tab_downloads));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.gallery_picker_tab_favorites));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                switch (tab.getPosition()) {
                    case 0:
                        source = SOURCE_ALL;
                        break;
                    case 1:
                        source = SOURCE_DOWNLOADS;
                        break;
                    case 2:
                        source = SOURCE_FAVORITES;
                        break;
                }
                loadData();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void loadData() {
        allItems.clear();

        switch (source) {
            case SOURCE_DOWNLOADS:
                List<DownloadInfo> downloads = EhDB.getAllDownloadInfo();
                allItems.addAll(downloads);
                break;
            case SOURCE_FAVORITES:
                List<GalleryInfo> favorites = EhDB.getAllLocalFavorites();
                allItems.addAll(favorites);
                break;
            case SOURCE_ALL:
                allItems.addAll(EhDB.getAllDownloadInfo());
                allItems.addAll(EhDB.getAllLocalFavorites());
                break;
        }

        filterItems(searchInput.getText().toString());
    }

    private void filterItems(String query) {
        filteredItems.clear();

        if (query.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            String lowerQuery = query.toLowerCase();
            for (GalleryInfo info : allItems) {
                if (info.title != null && info.title.toLowerCase().contains(lowerQuery)) {
                    filteredItems.add(info);
                }
            }
        }

        currentPage = 1;
        updatePage();
    }

    private void updatePage() {
        totalPages = Math.max(1, (filteredItems.size() + pageSize - 1) / pageSize);
        
        int startIndex = (currentPage - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, filteredItems.size());

        List<GalleryInfo> pageItems;
        if (startIndex < filteredItems.size()) {
            pageItems = filteredItems.subList(startIndex, endIndex);
        } else {
            pageItems = new ArrayList<>();
        }

        adapter.setItems(pageItems);

        // 更新分页信息
        TextView pageInfo = findViewById(R.id.page_info);
        pageInfo.setText(currentPage + "/" + totalPages);
        findViewById(R.id.prev_page_button).setEnabled(currentPage > 1);
        findViewById(R.id.next_page_button).setEnabled(currentPage < totalPages);

        // 更新计数
        selectedCountText.setText(getString(R.string.gallery_picker_selected_count, 
                adapter.getSelectedCount(), filteredItems.size()));
    }

    private void confirmSelection() {
        List<Long> selectedGids = adapter.getSelectedGids();
        if (selectedGids.isEmpty()) {
            setResult(RESULT_CANCELED);
        } else {
            Intent resultIntent = new Intent();
            resultIntent.putExtra(EXTRA_SELECTED_GIDS, new ArrayList<>(selectedGids));
            setResult(RESULT_OK, resultIntent);
        }
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 画廊选择器适配器
     */
    private static class GalleryPickerAdapter extends RecyclerView.Adapter<GalleryPickerAdapter.ViewHolder> {

        private final Context context;
        private final boolean multiSelect;
        private final List<GalleryInfo> items = new ArrayList<>();
        private final List<Long> selectedGids = new ArrayList<>();
        private OnSelectionChangedListener listener;

        public interface OnSelectionChangedListener {
            void onSelectionChanged(int count);
        }

        public GalleryPickerAdapter(Context context, boolean multiSelect) {
            this.context = context;
            this.multiSelect = multiSelect;
        }

        public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
            this.listener = listener;
        }

        public void setItems(List<GalleryInfo> items) {
            this.items.clear();
            this.items.addAll(items);
            notifyDataSetChanged();
        }

        public List<Long> getSelectedGids() {
            return new ArrayList<>(selectedGids);
        }

        public int getSelectedCount() {
            return selectedGids.size();
        }

        public void selectAll() {
            selectedGids.clear();
            for (GalleryInfo info : items) {
                selectedGids.add(info.gid);
            }
            notifyDataSetChanged();
            if (listener != null) listener.onSelectionChanged(selectedGids.size());
        }

        public void deselectAll() {
            selectedGids.clear();
            notifyDataSetChanged();
            if (listener != null) listener.onSelectionChanged(0);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(context).inflate(R.layout.item_gallery_picker, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            GalleryInfo info = items.get(position);
            holder.bind(info);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final CheckBox checkbox;
            private final ImageView thumbnail;
            private final TextView title;
            private final TextView category;
            private final TextView pages;
            private final TextView rating;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                checkbox = itemView.findViewById(R.id.checkbox);
                thumbnail = itemView.findViewById(R.id.thumbnail);
                title = itemView.findViewById(R.id.title);
                category = itemView.findViewById(R.id.category);
                pages = itemView.findViewById(R.id.pages);
                rating = itemView.findViewById(R.id.rating);
            }

            void bind(GalleryInfo info) {
                title.setText(info.title);
                category.setText(String.valueOf(info.category));
                pages.setText(info.pages + " pages");
                rating.setText(String.format("%.1f", info.rating));

                checkbox.setVisibility(multiSelect ? View.VISIBLE : View.GONE);
                checkbox.setChecked(selectedGids.contains(info.gid));

                itemView.setOnClickListener(v -> {
                    if (multiSelect) {
                        if (selectedGids.contains(info.gid)) {
                            selectedGids.remove(info.gid);
                        } else {
                            selectedGids.add(info.gid);
                        }
                        checkbox.setChecked(selectedGids.contains(info.gid));
                    } else {
                        selectedGids.clear();
                        selectedGids.add(info.gid);
                        notifyDataSetChanged();
                    }
                    if (listener != null) listener.onSelectionChanged(selectedGids.size());
                });
            }
        }
    }
}
