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

package com.hippo.ehviewer.ui.transfer.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据选择器适配器
 * 显示缩略图、分类、张数
 */
public class DataSelectorAdapter extends RecyclerView.Adapter<DataSelectorAdapter.ViewHolder> {

    private Context context;
    private List<GalleryInfo> items = new ArrayList<>();
    private Set<Long> selectedGids = new HashSet<>();
    private OnSelectionChangedListener listener;

    public DataSelectorAdapter(Context context) {
        this.context = context;
    }

    public void setItems(List<GalleryInfo> items) {
        this.items.clear();
        this.items.addAll(items);
        notifyDataSetChanged();
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.listener = listener;
    }

    public void selectAll() {
        selectedGids.clear();
        for (GalleryInfo info : items) {
            selectedGids.add(info.gid);
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void deselectAll() {
        selectedGids.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public List<Long> getSelectedGids() {
        return new ArrayList<>(selectedGids);
    }

    public int getSelectedCount() {
        return selectedGids.size();
    }

    private void notifySelectionChanged() {
        if (listener != null) {
            listener.onSelectionChanged(selectedGids.size());
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_data_selector, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        GalleryInfo info = items.get(position);

        // 设置标题
        holder.title.setText(info.title != null ? info.title : info.titleJpn);

        // 设置分类
        holder.category.setText(getCategoryName(info.category));

        // 设置页数
        holder.pages.setText(context.getString(R.string.pages_count, info.pages));

        // 设置评分
        holder.rating.setText(String.format("★ %.1f", info.rating));

        // 设置选中状态
        holder.checkbox.setChecked(selectedGids.contains(info.gid));

        // 设置缩略图
        // TODO: 使用图片加载库加载缩略图
        // Glide或Conaco加载
        String thumbKey = EhCacheKeyFactory.getThumbKey(info.gid);
        // holder.thumbnail.load(thumbKey, info.thumb);

        // 点击切换选中状态
        holder.itemView.setOnClickListener(v -> {
            if (selectedGids.contains(info.gid)) {
                selectedGids.remove(info.gid);
            } else {
                selectedGids.add(info.gid);
            }
            notifyItemChanged(position);
            notifySelectionChanged();
        });

        // Checkbox点击
        holder.checkbox.setOnClickListener(v -> {
            if (holder.checkbox.isChecked()) {
                selectedGids.add(info.gid);
            } else {
                selectedGids.remove(info.gid);
            }
            notifySelectionChanged();
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String getCategoryName(int category) {
        String[] categories = {
            "Misc", "Doujinshi", "Manga", "Artist CG", "Game CG",
            "Image Set", "Cosplay", "Asian Porn", "Non-H", "Western"
        };

        int index = -1;
        int temp = category;
        while (temp > 0) {
            temp >>= 1;
            index++;
        }

        if (index >= 0 && index < categories.length) {
            return categories[index];
        }
        return "Unknown";
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        CheckBox checkbox;
        ImageView thumbnail;
        TextView title;
        TextView category;
        TextView pages;
        TextView rating;

        ViewHolder(View itemView) {
            super(itemView);
            checkbox = itemView.findViewById(R.id.select_checkbox);
            thumbnail = itemView.findViewById(R.id.thumbnail);
            title = itemView.findViewById(R.id.title);
            category = itemView.findViewById(R.id.category);
            pages = itemView.findViewById(R.id.pages);
            rating = itemView.findViewById(R.id.rating);
        }
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }
}
