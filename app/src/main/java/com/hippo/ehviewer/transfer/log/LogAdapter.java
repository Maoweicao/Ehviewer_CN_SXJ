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

package com.hippo.ehviewer.transfer.log;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志列表适配器
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {

    /** 日志列表容量上限，防止长时间运行后无限累积导致 OOM */
    private static final int MAX_LOG_ITEMS = 1000;

    private final List<LogEntry> logs = new ArrayList<>();
    private RecyclerView recyclerView;

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.recyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        this.recyclerView = null;
    }

    /**
     * 添加日志
     */
    public void addLog(LogEntry entry) {
        // 超出容量上限时移除最旧的日志，防止无限累积
        while (logs.size() >= MAX_LOG_ITEMS) {
            logs.remove(0);
            notifyItemRemoved(0);
        }
        logs.add(entry);
        notifyItemInserted(logs.size() - 1);

        // 自动滚动到底部
        if (recyclerView != null) {
            recyclerView.post(() -> {
                recyclerView.scrollToPosition(logs.size() - 1);
            });
        }
    }

    /**
     * 设置日志列表
     */
    public void setLogs(List<LogEntry> logs) {
        this.logs.clear();
        // 只保留最新的一部分日志
        int start = Math.max(0, logs.size() - MAX_LOG_ITEMS);
        for (int i = start; i < logs.size(); i++) {
            this.logs.add(logs.get(i));
        }
        notifyDataSetChanged();

        // 滚动到底部
        if (recyclerView != null && !this.logs.isEmpty()) {
            recyclerView.post(() -> {
                recyclerView.scrollToPosition(this.logs.size() - 1);
            });
        }
    }

    /**
     * 清空日志
     */
    public void clear() {
        logs.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log_entry, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        LogEntry entry = logs.get(position);

        holder.time.setText(entry.formatTime());
        holder.level.setText(entry.getLevel().getShortName());
        holder.level.setTextColor(Color.parseColor(entry.getLevel().getColor()));
        holder.tag.setText(entry.getTag());
        holder.message.setText(entry.getMessage());
    }

    @Override
    public int getItemCount() {
        return logs.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView time;
        final TextView level;
        final TextView tag;
        final TextView message;

        ViewHolder(View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.log_time);
            level = itemView.findViewById(R.id.log_level);
            tag = itemView.findViewById(R.id.log_tag);
            message = itemView.findViewById(R.id.log_message);
        }
    }
}
