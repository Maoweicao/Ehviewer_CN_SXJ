/*
 * Copyright 2016 Hippo Seven
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

package com.hippo.ehviewer.preference;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.preference.DialogPreference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dialog preference that allows users to drag-to-reorder gallery categories
 * for download priority. Topmost category downloads first.
 * Each category item displays its distinctive color matching the catalog/gallery UI.
 */
public class CategoryPriorityDialogPreference extends DialogPreference {

    private static final int[] CATEGORY_IDS = {
            EhConfig.DOUJINSHI,
            EhConfig.MANGA,
            EhConfig.ARTIST_CG,
            EhConfig.GAME_CG,
            EhConfig.WESTERN,
            EhConfig.NON_H,
            EhConfig.IMAGE_SET,
            EhConfig.COSPLAY,
            EhConfig.ASIAN_PORN,
            EhConfig.MISC
    };

    private static final int[] CATEGORY_NAME_IDS = {
            R.string.doujinshi,
            R.string.manga,
            R.string.artist_cg,
            R.string.game_cg,
            R.string.western,
            R.string.non_h,
            R.string.image_set,
            R.string.cosplay,
            R.string.asian_porn,
            R.string.misc
    };

    private List<Integer> mOrderedCategories;
    private CategoryAdapter mAdapter;

    public CategoryPriorityDialogPreference(Context context) {
        super(context);
        init();
    }

    public CategoryPriorityDialogPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CategoryPriorityDialogPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mOrderedCategories = loadCategoryOrder();
    }

    private List<Integer> loadCategoryOrder() {
        List<Integer> order = new ArrayList<>();
        String saved = Settings.getDownloadCategoryPriorityOrder();
        if (saved != null && !saved.isEmpty()) {
            String[] parts = saved.split(",");
            for (String part : parts) {
                try {
                    int catId = Integer.parseInt(part.trim());
                    if (isValidCategory(catId)) {
                        order.add(catId);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (int catId : CATEGORY_IDS) {
            if (!order.contains(catId)) {
                order.add(catId);
            }
        }
        return order;
    }

    private void saveCategoryOrder() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mOrderedCategories.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(mOrderedCategories.get(i));
        }
        Settings.setDownloadCategoryPriorityOrder(sb.toString());
    }

    private static boolean isValidCategory(int catId) {
        for (int id : CATEGORY_IDS) {
            if (id == catId) return true;
        }
        return false;
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);
        builder.setTitle(R.string.category_priority_dialog_title);
        builder.setMessage(R.string.category_priority_dialog_drag_hint);
        builder.setPositiveButton(android.R.string.ok, this);
        builder.setNegativeButton(android.R.string.cancel, this);
    }

    @Override
    protected View onCreateDialogView() {
        View contentView = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_category_priority, null);
        RecyclerView recyclerView = contentView.findViewById(R.id.category_recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        return contentView;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);

        RecyclerView recyclerView = view.findViewById(R.id.category_recycler);

        mAdapter = new CategoryAdapter();
        recyclerView.setAdapter(mAdapter);

        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                int fromPos = viewHolder.getAdapterPosition();
                int toPos = target.getAdapterPosition();
                Collections.swap(mOrderedCategories, fromPos, toPos);
                mAdapter.notifyItemMoved(fromPos, toPos);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }
        };

        ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(recyclerView);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (positiveResult && mOrderedCategories != null) {
            saveCategoryOrder();
        }
        mAdapter = null;
    }

    private class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_category_priority, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            int catId = mOrderedCategories.get(position);
            String name = getCategoryName(catId);
            int color = EhUtils.getCategoryColor(catId);

            holder.categoryName.setText(name);

            // 分类名称 Chip：矩形彩色填充 + 白色文字（与画廊标签样式一致）
            Drawable chipBg = holder.categoryName.getBackground();
            if (chipBg == null) {
                chipBg = ContextCompat.getDrawable(holder.itemView.getContext(), R.drawable.category_chip_bg);
                holder.categoryName.setBackground(chipBg);
            }
            if (chipBg instanceof GradientDrawable) {
                GradientDrawable gd = (GradientDrawable) chipBg.mutate();
                gd.setColor(color);
                holder.categoryName.setBackground(gd);
            } else {
                holder.categoryName.getBackground().setTint(color);
            }
        }

        @Override
        public int getItemCount() {
            return mOrderedCategories.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView categoryName;
            final ImageView dragHandler;

            ViewHolder(View itemView) {
                super(itemView);
                categoryName = itemView.findViewById(R.id.category_name);
                dragHandler = itemView.findViewById(R.id.drag_handler);
            }
        }
    }

    private String getCategoryName(int catId) {
        for (int i = 0; i < CATEGORY_IDS.length; i++) {
            if (CATEGORY_IDS[i] == catId) {
                return getContext().getString(CATEGORY_NAME_IDS[i]);
            }
        }
        return "Unknown";
    }
}
