/*
 * Copyright 2024 Hippo Seven
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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.database.DatabaseManager;

import java.util.List;

/**
 * 数据表列表适配器
 */
public class DatabaseTablesAdapter extends RecyclerView.Adapter<DatabaseTablesAdapter.VH> {

    private final List<DatabaseManager.TableInfo> mData;
    private final OnTableActionListener mListener;

    public interface OnTableActionListener {
        void onOpenTable(DatabaseManager.TableInfo table);

        void onShowTableInfo(DatabaseManager.TableInfo table);
    }

    public DatabaseTablesAdapter(List<DatabaseManager.TableInfo> data,
                                 OnTableActionListener listener) {
        mData = data;
        mListener = listener;
    }

    public static class VH extends RecyclerView.ViewHolder {
        public final TextView name;
        public final TextView rowCount;
        public final ImageView info;

        public VH(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.text_table_name);
            rowCount = itemView.findViewById(R.id.text_table_row_count);
            info = itemView.findViewById(R.id.button_table_info);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return new VH(inflater.inflate(R.layout.item_database_table, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        DatabaseManager.TableInfo table = mData.get(position);
        holder.name.setText(table.name);
        holder.rowCount.setText(String.valueOf(table.rowCount));
        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onOpenTable(mData.get(holder.getBindingAdapterPosition()));
            }
        });
        holder.info.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onShowTableInfo(mData.get(holder.getBindingAdapterPosition()));
            }
        });
    }

    @Override
    public int getItemCount() {
        return mData.size();
    }
}