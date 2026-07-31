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

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.transfer.data.RelayTask;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 接力任务列表适配器
 */
public class RelayTaskAdapter extends RecyclerView.Adapter<RelayTaskAdapter.RelayViewHolder> {

    private List<RelayTask> tasks = new ArrayList<>();
    private OnRelayActionListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public interface OnRelayActionListener {
        void onAccept(RelayTask task);
        void onReject(RelayTask task);
        void onCancel(RelayTask task);
        void onRetrieve(RelayTask task);
        void onDelete(RelayTask task);
    }

    public void setOnRelayActionListener(OnRelayActionListener listener) {
        this.listener = listener;
    }

    public void setTasks(List<RelayTask> tasks) {
        this.tasks = tasks != null ? tasks : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RelayViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_relay_task, parent, false);
        return new RelayViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RelayViewHolder holder, int position) {
        RelayTask task = tasks.get(position);
        holder.bind(task);
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    class RelayViewHolder extends RecyclerView.ViewHolder {

        private final TextView title;
        private final TextView statusBadge;
        private final TextView direction;
        private final TextView deviceInfo;
        private final LinearLayout progressSection;
        private final TextView progressText;
        private final TextView speed;
        private final ProgressBar progressBar;
        private final TextView errorMessage;
        private final TextView createdTime;
        private final LinearLayout actions;
        private final Button btnAccept;
        private final Button btnReject;
        private final Button btnCancel;
        private final Button btnRetrieve;
        private final Button btnDelete;

        RelayViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.relay_title);
            statusBadge = itemView.findViewById(R.id.relay_status_badge);
            direction = itemView.findViewById(R.id.relay_direction);
            deviceInfo = itemView.findViewById(R.id.relay_device_info);
            progressSection = itemView.findViewById(R.id.relay_progress_section);
            progressText = itemView.findViewById(R.id.relay_progress_text);
            speed = itemView.findViewById(R.id.relay_speed);
            progressBar = itemView.findViewById(R.id.relay_progress_bar);
            errorMessage = itemView.findViewById(R.id.relay_error_message);
            createdTime = itemView.findViewById(R.id.relay_created_time);
            actions = itemView.findViewById(R.id.relay_actions);
            btnAccept = itemView.findViewById(R.id.relay_btn_accept);
            btnReject = itemView.findViewById(R.id.relay_btn_reject);
            btnCancel = itemView.findViewById(R.id.relay_btn_cancel);
            btnRetrieve = itemView.findViewById(R.id.relay_btn_retrieve);
            btnDelete = itemView.findViewById(R.id.relay_btn_delete);
        }

        void bind(RelayTask task) {
            // Title
            String displayTitle = task.getTitle();
            if (displayTitle == null || displayTitle.isEmpty()) {
                displayTitle = "GID: " + task.getGid();
            }
            title.setText(displayTitle);

            // Status badge
            statusBadge.setText(task.getStatusDisplayName());
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(12f);
            badgeBg.setColor(getStatusColor(task.getStatus()));
            statusBadge.setBackground(badgeBg);

            // Direction
            direction.setText(task.getDirectionDisplayName());

            // Device info - show who is involved and who accepted
            StringBuilder deviceInfoText = new StringBuilder();
            if (task.isIncoming()) {
                String source = task.getSourceDevice();
                deviceInfoText.append(source != null ? "来自 " + source : "来自未知设备");
            } else {
                String target = task.getTargetDevice();
                deviceInfoText.append(target != null ? "委托给 " + target : "目标未知");
            }
            // 如果任务已被接受，显示接受方信息
            String accepted = task.getAcceptedDevice();
            if (accepted != null && !accepted.isEmpty()) {
                deviceInfoText.append(" · 由 ").append(accepted).append(" 接受");
            }
            deviceInfo.setText(deviceInfoText.toString());

            // Progress section
            if (task.isDownloading()) {
                progressSection.setVisibility(View.VISIBLE);
                int total = task.getTotal();
                int finished = task.getFinished();
                if (total > 0) {
                    progressText.setText(finished + "/" + total + " (" + formatSize(task.getDownloadedSize()) + ")");
                    progressBar.setMax(total);
                    progressBar.setProgress(finished);
                } else {
                    progressText.setText("准备中...");
                    progressBar.setIndeterminate(true);
                }
                long spd = task.getSpeed();
                speed.setText(spd > 0 ? formatSpeed(spd) : "");
            } else {
                progressSection.setVisibility(View.GONE);
            }

            // Error message
            if (task.isFailed() && task.getErrorMessage() != null) {
                errorMessage.setVisibility(View.VISIBLE);
                errorMessage.setText(task.getErrorMessage());
            } else {
                errorMessage.setVisibility(View.GONE);
            }

            // Created time
            createdTime.setText(dateFormat.format(new Date(task.getCreatedTime())));

            // Action buttons based on status
            setupActions(task);
        }

        private void setupActions(RelayTask task) {
            // Hide all buttons first
            btnAccept.setVisibility(View.GONE);
            btnReject.setVisibility(View.GONE);
            btnCancel.setVisibility(View.GONE);
            btnRetrieve.setVisibility(View.GONE);
            btnDelete.setVisibility(View.GONE);

            switch (task.getStatus()) {
                case RelayTask.STATUS_PENDING:
                    if (task.isIncoming()) {
                        btnAccept.setVisibility(View.VISIBLE);
                        btnReject.setVisibility(View.VISIBLE);
                    } else {
                        btnCancel.setVisibility(View.VISIBLE);
                    }
                    break;

                case RelayTask.STATUS_ACCEPTED:
                case RelayTask.STATUS_DOWNLOADING:
                    btnCancel.setVisibility(View.VISIBLE);
                    break;

                case RelayTask.STATUS_RETURNED:
                    if (task.isOutgoing()) {
                        btnRetrieve.setVisibility(View.VISIBLE);
                    }
                    btnDelete.setVisibility(View.VISIBLE);
                    break;

                case RelayTask.STATUS_REJECTED:
                case RelayTask.STATUS_CANCELLED:
                case RelayTask.STATUS_FAILED:
                    btnDelete.setVisibility(View.VISIBLE);
                    break;

                case RelayTask.STATUS_COMPLETED:
                    // Waiting for packaging, show cancel
                    btnCancel.setVisibility(View.VISIBLE);
                    break;
            }

            // Click listeners
            btnAccept.setOnClickListener(v -> {
                if (listener != null) listener.onAccept(task);
            });
            btnReject.setOnClickListener(v -> {
                if (listener != null) listener.onReject(task);
            });
            btnCancel.setOnClickListener(v -> {
                if (listener != null) listener.onCancel(task);
            });
            btnRetrieve.setOnClickListener(v -> {
                if (listener != null) listener.onRetrieve(task);
            });
            btnDelete.setOnClickListener(v -> {
                if (listener != null) listener.onDelete(task);
            });
        }

        private int getStatusColor(String status) {
            switch (status) {
                case RelayTask.STATUS_PENDING:
                    return Color.parseColor("#FF9800"); // Orange
                case RelayTask.STATUS_ACCEPTED:
                    return Color.parseColor("#2196F3"); // Blue
                case RelayTask.STATUS_DOWNLOADING:
                    return Color.parseColor("#4CAF50"); // Green
                case RelayTask.STATUS_COMPLETED:
                    return Color.parseColor("#9C27B0"); // Purple
                case RelayTask.STATUS_RETURNED:
                    return Color.parseColor("#00BCD4"); // Cyan
                case RelayTask.STATUS_REJECTED:
                    return Color.parseColor("#F44336"); // Red
                case RelayTask.STATUS_CANCELLED:
                    return Color.parseColor("#795548"); // Brown
                case RelayTask.STATUS_FAILED:
                    return Color.parseColor("#F44336"); // Red
                default:
                    return Color.parseColor("#9E9E9E"); // Grey
            }
        }

        private String formatSpeed(long bytesPerSecond) {
            if (bytesPerSecond <= 0) return "";
            if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
            if (bytesPerSecond < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0);
            if (bytesPerSecond < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024));
            return String.format(Locale.US, "%.2f GB/s", bytesPerSecond / (1024.0 * 1024 * 1024));
        }

        private String formatSize(long bytes) {
            if (bytes <= 0) return "0 B";
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
