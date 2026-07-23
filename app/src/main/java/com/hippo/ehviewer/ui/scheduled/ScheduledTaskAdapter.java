package com.hippo.ehviewer.ui.scheduled;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.task.scheduled.DelayCondition;
import com.hippo.ehviewer.task.scheduled.RepeatMode;
import com.hippo.ehviewer.task.scheduled.ScheduledTask;
import com.hippo.ehviewer.task.scheduled.ScheduledTaskState;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 定时任务列表适配器
 */
public class ScheduledTaskAdapter extends RecyclerView.Adapter<ScheduledTaskAdapter.ViewHolder> {

    private final Context context;
    private final List<ScheduledTask> tasks = new ArrayList<>();
    private OnTaskActionListener listener;
    private String currentGroupId;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public interface OnTaskActionListener {
        void onEdit(ScheduledTask task);
        void onDelete(ScheduledTask task);
        void onMoveToGroup(ScheduledTask task);
    }

    public ScheduledTaskAdapter(Context context) {
        this.context = context;
    }

    public void setOnTaskActionListener(OnTaskActionListener listener) {
        this.listener = listener;
    }

    public void setTasks(List<ScheduledTask> tasks) {
        this.tasks.clear();
        this.tasks.addAll(tasks);
        notifyDataSetChanged();
    }

    public String getCurrentGroupId() {
        return currentGroupId;
    }

    public List<String> getTaskOrder() {
        List<String> order = new ArrayList<>();
        for (ScheduledTask task : tasks) {
            order.add(task.getId());
        }
        return order;
    }

    public boolean onItemMove(int fromPosition, int toPosition) {
        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(tasks, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(tasks, i, i - 1);
            }
        }
        notifyItemMoved(fromPosition, toPosition);
        return true;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_scheduled_task, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ScheduledTask task = tasks.get(position);
        holder.bind(task);
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView dragHandle;
        private final TextView taskName;
        private final TextView taskType;
        private final TextView taskState;
        private final TextView taskScheduleInfo;
        private final TextView taskExecutionInfo;
        private final ImageButton moreButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            dragHandle = itemView.findViewById(R.id.drag_handle);
            taskName = itemView.findViewById(R.id.task_name);
            taskType = itemView.findViewById(R.id.task_type);
            taskState = itemView.findViewById(R.id.task_state);
            taskScheduleInfo = itemView.findViewById(R.id.task_schedule_info);
            taskExecutionInfo = itemView.findViewById(R.id.task_execution_info);
            moreButton = itemView.findViewById(R.id.more_button);
        }

        void bind(ScheduledTask task) {
            taskName.setText(task.getTaskDisplayName());
            taskType.setText(getTaskTypeName(task.getTaskType()));
            taskState.setText(getStateName(task.getState()));
            taskScheduleInfo.setText(getScheduleInfo(task));
            taskExecutionInfo.setText(getExecutionInfo(task));

            moreButton.setOnClickListener(v -> showPopupMenu(v, task));
        }

        private String getTaskTypeName(com.hippo.ehviewer.task.BackgroundTask.TaskType type) {
            switch (type) {
                case DOWNLOAD:
                    return context.getString(R.string.task_type_download);
                case SYNC:
                    return context.getString(R.string.task_type_sync);
                case SCAN:
                    return context.getString(R.string.task_type_scan);
                case CLEANUP:
                    return context.getString(R.string.task_type_cleanup);
                case MERGE:
                    return context.getString(R.string.task_type_merge);
                case UPDATE:
                    return context.getString(R.string.task_type_update);
                case TRANSFER:
                    return context.getString(R.string.task_type_transfer);
                case IMPORT:
                    return context.getString(R.string.task_type_import);
                case EXPORT:
                    return context.getString(R.string.task_type_export);
                case BACKUP:
                    return context.getString(R.string.task_type_backup);
                case OTHER:
                default:
                    return context.getString(R.string.task_type_other);
            }
        }

        private String getStateName(ScheduledTaskState state) {
            switch (state) {
                case PENDING:
                    return context.getString(R.string.scheduled_task_state_pending);
                case WAITING_CONDITION:
                    return context.getString(R.string.scheduled_task_state_waiting_condition);
                case WAITING_TIME:
                    return context.getString(R.string.scheduled_task_state_waiting_time);
                case QUEUED:
                    return context.getString(R.string.scheduled_task_state_queued);
                case RUNNING:
                    return context.getString(R.string.scheduled_task_state_running);
                case COMPLETED:
                    return context.getString(R.string.scheduled_task_state_completed);
                case FAILED:
                    return context.getString(R.string.scheduled_task_state_failed);
                case CANCELLED:
                    return context.getString(R.string.scheduled_task_state_cancelled);
                case PAUSED:
                    return context.getString(R.string.scheduled_task_state_paused);
                default:
                    return "";
            }
        }

        private String getScheduleInfo(ScheduledTask task) {
            StringBuilder sb = new StringBuilder();

            // 延时条件
            sb.append(getDelayConditionName(task.getDelayCondition()));

            // 重复模式
            sb.append(" | ");
            sb.append(getRepeatModeName(task.getRepeatMode()));

            // 下次执行时间
            if (task.getScheduledTime() != null) {
                sb.append(" | ");
                sb.append(context.getString(R.string.scheduled_task_next_execution,
                        dateFormat.format(new Date(task.getScheduledTime()))));
            }

            return sb.toString();
        }

        private String getDelayConditionName(DelayCondition condition) {
            switch (condition) {
                case IMMEDIATE:
                    return context.getString(R.string.delay_condition_immediate);
                case DOWNLOAD_COMPLETE:
                    return context.getString(R.string.delay_condition_download_complete);
                case OTHER_TASKS_COMPLETE:
                    return context.getString(R.string.delay_condition_other_tasks_complete);
                case SPECIFIC_TIME:
                    return context.getString(R.string.delay_condition_specific_time);
                default:
                    return "";
            }
        }

        private String getRepeatModeName(RepeatMode mode) {
            switch (mode) {
                case ONCE:
                    return context.getString(R.string.repeat_mode_once);
                case DAILY:
                    return context.getString(R.string.repeat_mode_daily);
                case WEEKDAYS:
                    return context.getString(R.string.repeat_mode_weekdays);
                case WEEKENDS:
                    return context.getString(R.string.repeat_mode_weekends);
                case HOLIDAYS:
                    return context.getString(R.string.repeat_mode_holidays);
                case CUSTOM_CRON:
                    return context.getString(R.string.repeat_mode_custom_cron);
                default:
                    return "";
            }
        }

        private String getExecutionInfo(ScheduledTask task) {
            StringBuilder sb = new StringBuilder();

            if (task.getExecutionCount() > 0) {
                sb.append(context.getString(R.string.scheduled_task_execution_count, task.getExecutionCount()));
            }

            if (task.getLastExecutedAt() != null) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(context.getString(R.string.scheduled_task_last_execution,
                        dateFormat.format(new Date(task.getLastExecutedAt()))));
            }

            if (task.getRetryCount() > 0) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(context.getString(R.string.scheduled_task_retry_count, task.getRetryCount()));
            }

            return sb.toString();
        }

        private void showPopupMenu(View view, ScheduledTask task) {
            PopupMenu popup = new PopupMenu(context, view);
            popup.inflate(R.menu.menu_scheduled_task_item);
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_edit) {
                    if (listener != null) listener.onEdit(task);
                    return true;
                } else if (id == R.id.action_delete) {
                    if (listener != null) listener.onDelete(task);
                    return true;
                } else if (id == R.id.action_move_to_group) {
                    if (listener != null) listener.onMoveToGroup(task);
                    return true;
                }
                return false;
            });
            popup.show();
        }
    }
}
