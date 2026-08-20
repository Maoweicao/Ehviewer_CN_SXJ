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
 * 数据库文件列表适配器
 */
public class DatabaseListAdapter extends RecyclerView.Adapter<DatabaseListAdapter.VH> {

    private final List<DatabaseManager.DatabaseInfo> mData;
    private final OnDatabaseActionListener mListener;

    public interface OnDatabaseActionListener {
        void onOpenDatabase(DatabaseManager.DatabaseInfo db);

        void onShareDatabase(DatabaseManager.DatabaseInfo db);
    }

    public DatabaseListAdapter(List<DatabaseManager.DatabaseInfo> data,
                               OnDatabaseActionListener listener) {
        mData = data;
        mListener = listener;
    }

    public static class VH extends RecyclerView.ViewHolder {
        public final TextView name;
        public final TextView version;
        public final ImageView share;

        public VH(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.text_database_name);
            version = itemView.findViewById(R.id.text_database_version);
            share = itemView.findViewById(R.id.button_database_share);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return new VH(inflater.inflate(R.layout.item_database_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        DatabaseManager.DatabaseInfo db = mData.get(position);
        holder.name.setText(db.name);
        holder.version.setText("v" + db.version);
        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onOpenDatabase(mData.get(holder.getBindingAdapterPosition()));
            }
        });
        holder.share.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onShareDatabase(mData.get(holder.getBindingAdapterPosition()));
            }
        });
    }

    @Override
    public int getItemCount() {
        return mData.size();
    }
}