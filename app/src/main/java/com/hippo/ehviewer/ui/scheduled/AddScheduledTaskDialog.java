package com.hippo.ehviewer.ui.scheduled;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.task.BackgroundTask;
import com.hippo.ehviewer.task.TaskRegistry;
import com.hippo.ehviewer.task.scheduled.CronExpressionBuilder;
import com.hippo.ehviewer.task.scheduled.DelayCondition;
import com.hippo.ehviewer.task.scheduled.RepeatMode;
import com.hippo.ehviewer.task.scheduled.RetryConfig;
import com.hippo.ehviewer.task.scheduled.RetryMode;
import com.hippo.ehviewer.task.scheduled.ScheduledTask;
import com.hippo.ehviewer.task.scheduled.ScheduledTaskManager;
import com.hippo.ehviewer.task.scheduled.TaskGroup;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 添加定时任务对话框
 */
public class AddScheduledTaskDialog extends AppCompatDialogFragment {

    private OnTaskCreatedListener listener;
    private ScheduledTask editTask; // 编辑模式下的任务

    private AutoCompleteTextView taskTypeSpinner;
    private RadioGroup delayConditionGroup;
    private View specificTimeLayout;
    private AutoCompleteTextView repeatModeSpinner;
    private View weekdayConfigLayout;
    private View cronLayout;
    private TextInputEditText cronExpressionInput;
    private TextView cronPreviewResult;
    private AutoCompleteTextView groupSpinner;
    private AutoCompleteTextView retryModeSpinner;
    private TextInputLayout maxRetriesLayout;
    private TextInputLayout retryDelayLayout;

    private Calendar selectedDateTime = Calendar.getInstance();
    private CronExpressionBuilder cronBuilder = new CronExpressionBuilder();

    public interface OnTaskCreatedListener {
        void onTaskCreated(ScheduledTask task);
    }

    public static AddScheduledTaskDialog newInstance(ScheduledTask task) {
        AddScheduledTaskDialog dialog = new AddScheduledTaskDialog();
        dialog.editTask = task;
        return dialog;
    }

    public void setOnTaskCreatedListener(OnTaskCreatedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_add_scheduled_task, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        setupSpinners();
        setupListeners();
        
        if (editTask != null) {
            loadTaskData(editTask);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // 使对话框占满屏幕
        Dialog dialog = getDialog();
        if (dialog != null) {
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
            );
        }
    }

    private void initViews(View view) {
        taskTypeSpinner = view.findViewById(R.id.task_type_spinner);
        delayConditionGroup = view.findViewById(R.id.delay_condition_group);
        specificTimeLayout = view.findViewById(R.id.specific_time_layout);
        repeatModeSpinner = view.findViewById(R.id.repeat_mode_spinner);
        weekdayConfigLayout = view.findViewById(R.id.weekday_config_layout);
        cronLayout = view.findViewById(R.id.cron_layout);
        cronExpressionInput = view.findViewById(R.id.cron_expression_input);
        cronPreviewResult = view.findViewById(R.id.cron_preview_result);
        groupSpinner = view.findViewById(R.id.group_spinner);
        retryModeSpinner = view.findViewById(R.id.retry_mode_spinner);
        maxRetriesLayout = view.findViewById(R.id.max_retries_layout);
        retryDelayLayout = view.findViewById(R.id.retry_delay_layout);

        // 设置日期时间选择按钮
        MaterialButton selectDateButton = view.findViewById(R.id.select_date_button);
        MaterialButton selectTimeButton = view.findViewById(R.id.select_time_button);
        MaterialButton cronGeneratorButton = view.findViewById(R.id.cron_generator_button);
        MaterialButton cronPreviewButton = view.findViewById(R.id.cron_preview_button);

        selectDateButton.setOnClickListener(v -> showDatePicker());
        selectTimeButton.setOnClickListener(v -> showTimePicker());
        cronGeneratorButton.setOnClickListener(v -> showCronGenerator());
        cronPreviewButton.setOnClickListener(v -> previewCronExpression());

        // 设置确认/取消按钮
        MaterialButton confirmButton = view.findViewById(R.id.confirm_button);
        MaterialButton cancelButton = view.findViewById(R.id.cancel_button);
        if (confirmButton != null) {
            confirmButton.setOnClickListener(v -> createTask());
        }
        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> dismiss());
        }
    }

    private void setupSpinners() {
        // 任务类型
        List<TaskRegistry.TaskMetadata> noParamTasks = TaskRegistry.INSTANCE.getNoParamTasks();
        String[] taskNames = new String[noParamTasks.size()];
        for (int i = 0; i < noParamTasks.size(); i++) {
            taskNames[i] = getString(noParamTasks.get(i).getDisplayNameResId());
        }
        ArrayAdapter<String> taskAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, taskNames);
        taskTypeSpinner.setAdapter(taskAdapter);

        // 重复模式
        String[] repeatModes = {
                getString(R.string.repeat_mode_once),
                getString(R.string.repeat_mode_daily),
                getString(R.string.repeat_mode_weekdays),
                getString(R.string.repeat_mode_weekends),
                getString(R.string.repeat_mode_holidays),
                getString(R.string.repeat_mode_custom_cron)
        };
        ArrayAdapter<String> repeatAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, repeatModes);
        repeatModeSpinner.setAdapter(repeatAdapter);
        repeatModeSpinner.setText(repeatModes[0], false);

        // 任务组
        ScheduledTaskManager taskManager = ScheduledTaskManager.getInstance(requireContext());
        List<TaskGroup> groups = taskManager.getTaskGroups();
        String[] groupNames = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            groupNames[i] = groups.get(i).getGroupName();
        }
        ArrayAdapter<String> groupAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, groupNames);
        groupSpinner.setAdapter(groupAdapter);
        if (groupNames.length > 0) {
            groupSpinner.setText(groupNames[0], false);
        }

        // 重试模式
        String[] retryModes = {
                getString(R.string.retry_mode_no_retry),
                getString(R.string.retry_mode_immediate),
                getString(R.string.retry_mode_delayed),
                getString(R.string.retry_mode_next_schedule)
        };
        ArrayAdapter<String> retryAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, retryModes);
        retryModeSpinner.setAdapter(retryAdapter);
        retryModeSpinner.setText(retryModes[0], false);
    }

    private void setupListeners() {
        // 延时条件变化监听
        delayConditionGroup.setOnCheckedChangeListener((group, checkedId) -> {
            specificTimeLayout.setVisibility(checkedId == R.id.delay_specific_time ? View.VISIBLE : View.GONE);
        });

        // 重复模式变化监听
        repeatModeSpinner.setOnItemClickListener((parent, view, position, id) -> {
            weekdayConfigLayout.setVisibility(position == 2 ? View.VISIBLE : View.GONE); // 工作日
            cronLayout.setVisibility(position == 5 ? View.VISIBLE : View.GONE); // 自定义Cron
        });

        // 重试模式变化监听
        retryModeSpinner.setOnItemClickListener((parent, view, position, id) -> {
            boolean showRetryOptions = position > 0; // 非"不重试"
            maxRetriesLayout.setVisibility(showRetryOptions ? View.VISIBLE : View.GONE);
            retryDelayLayout.setVisibility(position == 2 ? View.VISIBLE : View.GONE); // 延迟重试
        });
    }

    private void showDatePicker() {
        DatePickerDialog dialog = new DatePickerDialog(requireContext(),
                (view, year, month, dayOfMonth) -> {
                    selectedDateTime.set(year, month, dayOfMonth);
                },
                selectedDateTime.get(Calendar.YEAR),
                selectedDateTime.get(Calendar.MONTH),
                selectedDateTime.get(Calendar.DAY_OF_MONTH));
        dialog.show();
    }

    private void showTimePicker() {
        TimePickerDialog dialog = new TimePickerDialog(requireContext(),
                (view, hourOfDay, minute) -> {
                    selectedDateTime.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    selectedDateTime.set(Calendar.MINUTE, minute);
                },
                selectedDateTime.get(Calendar.HOUR_OF_DAY),
                selectedDateTime.get(Calendar.MINUTE),
                true);
        dialog.show();
    }

    private void showCronGenerator() {
        CronExpressionDialog dialog = new CronExpressionDialog();
        dialog.setOnCronSelectedListener(expression -> {
            cronExpressionInput.setText(expression);
            previewCronExpression();
        });
        dialog.show(getChildFragmentManager(), "cron_generator");
    }

    private void previewCronExpression() {
        String expression = cronExpressionInput.getText().toString();
        if (expression.isEmpty()) {
            cronPreviewResult.setText(R.string.cron_expression_invalid);
            return;
        }

        try {
            String description = cronBuilder.getDescription(expression);
            Long nextTime = cronBuilder.getNextExecutionTime(expression);
            
            StringBuilder sb = new StringBuilder();
            sb.append(description);
            if (nextTime != null) {
                sb.append("\n");
                sb.append(getString(R.string.cron_next_execution, 
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(new java.util.Date(nextTime))));
            }
            cronPreviewResult.setText(sb.toString());
        } catch (Exception e) {
            cronPreviewResult.setText(R.string.cron_expression_invalid);
        }
    }

    private void loadTaskData(ScheduledTask task) {
        // 设置任务类型
        TaskRegistry.TaskMetadata metadata = TaskRegistry.INSTANCE.getMetadata(task.getTaskClassName());
        if (metadata != null) {
            taskTypeSpinner.setText(getString(metadata.getDisplayNameResId()), false);
        }

        // 设置延时条件
        switch (task.getDelayCondition()) {
            case IMMEDIATE:
                ((RadioButton) delayConditionGroup.findViewById(R.id.delay_immediate)).setChecked(true);
                break;
            case DOWNLOAD_COMPLETE:
                ((RadioButton) delayConditionGroup.findViewById(R.id.delay_download_complete)).setChecked(true);
                break;
            case OTHER_TASKS_COMPLETE:
                ((RadioButton) delayConditionGroup.findViewById(R.id.delay_other_tasks_complete)).setChecked(true);
                break;
            case SPECIFIC_TIME:
                ((RadioButton) delayConditionGroup.findViewById(R.id.delay_specific_time)).setChecked(true);
                if (task.getScheduledTime() != null) {
                    selectedDateTime.setTimeInMillis(task.getScheduledTime());
                }
                break;
        }

        // 设置重复模式
        repeatModeSpinner.setText(getRepeatModeName(task.getRepeatMode()), false);
        if (task.getRepeatMode() == RepeatMode.CUSTOM_CRON && task.getCronExpression() != null) {
            cronExpressionInput.setText(task.getCronExpression());
        }

        // 设置任务组
        ScheduledTaskManager taskManager = ScheduledTaskManager.getInstance(requireContext());
        List<TaskGroup> groups = taskManager.getTaskGroups();
        for (TaskGroup group : groups) {
            if (group.getGroupId().equals(task.getGroupId())) {
                groupSpinner.setText(group.getGroupName(), false);
                break;
            }
        }

        // 设置重试配置
        retryModeSpinner.setText(getRetryModeName(task.getRetryConfig().getMode()), false);
        if (task.getRetryConfig().getMode() != RetryMode.NO_RETRY) {
            ((EditText) maxRetriesLayout.findViewById(R.id.max_retries_input))
                    .setText(String.valueOf(task.getRetryConfig().getMaxRetries()));
            if (task.getRetryConfig().getMode() == RetryMode.DELAYED) {
                ((EditText) retryDelayLayout.findViewById(R.id.retry_delay_input))
                        .setText(String.valueOf(task.getRetryConfig().getDelayMillis()));
            }
        }
    }

    private void createTask() {
        // 获取任务类型
        List<TaskRegistry.TaskMetadata> noParamTasks = TaskRegistry.INSTANCE.getNoParamTasks();
        int taskTypeIndex = -1;
        String selectedTaskName = taskTypeSpinner.getText().toString();
        for (int i = 0; i < noParamTasks.size(); i++) {
            if (getString(noParamTasks.get(i).getDisplayNameResId()).equals(selectedTaskName)) {
                taskTypeIndex = i;
                break;
            }
        }

        if (taskTypeIndex < 0) {
            Toast.makeText(requireContext(), "请选择任务类型", Toast.LENGTH_SHORT).show();
            return;
        }

        TaskRegistry.TaskMetadata selectedTask = noParamTasks.get(taskTypeIndex);

        // 获取延时条件
        DelayCondition delayCondition;
        int checkedId = delayConditionGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.delay_download_complete) {
            delayCondition = DelayCondition.DOWNLOAD_COMPLETE;
        } else if (checkedId == R.id.delay_other_tasks_complete) {
            delayCondition = DelayCondition.OTHER_TASKS_COMPLETE;
        } else if (checkedId == R.id.delay_specific_time) {
            delayCondition = DelayCondition.SPECIFIC_TIME;
        } else {
            delayCondition = DelayCondition.IMMEDIATE;
        }

        // 获取重复模式
        RepeatMode repeatMode = getRepeatModeFromSpinner();
        String cronExpression = null;
        if (repeatMode == RepeatMode.CUSTOM_CRON) {
            cronExpression = cronExpressionInput.getText().toString();
            if (cronExpression.isEmpty()) {
                Toast.makeText(requireContext(), R.string.cron_expression_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 获取任务组
        ScheduledTaskManager taskManager = ScheduledTaskManager.getInstance(requireContext());
        List<TaskGroup> groups = taskManager.getTaskGroups();
        String groupId = TaskGroup.DEFAULT_GROUP_ID;
        String selectedGroupName = groupSpinner.getText().toString();
        for (TaskGroup group : groups) {
            if (group.getGroupName().equals(selectedGroupName)) {
                groupId = group.getGroupId();
                break;
            }
        }

        // 获取重试配置
        RetryMode retryMode = getRetryModeFromRetrySpinner();
        int maxRetries = 3;
        long retryDelay = 60000;
        if (retryMode != RetryMode.NO_RETRY) {
            try {
                maxRetries = Integer.parseInt(((EditText) maxRetriesLayout.findViewById(R.id.max_retries_input)).getText().toString());
            } catch (NumberFormatException e) {
                maxRetries = 3;
            }
            if (retryMode == RetryMode.DELAYED) {
                try {
                    retryDelay = Long.parseLong(((EditText) retryDelayLayout.findViewById(R.id.retry_delay_input)).getText().toString());
                } catch (NumberFormatException e) {
                    retryDelay = 60000;
                }
            }
        }

        // 创建任务
        ScheduledTask task = ScheduledTask.create(
                selectedTask.getTaskClassName(),
                getString(selectedTask.getDisplayNameResId()),
                selectedTask.getTaskType(),
                null, // paramData - 无参数任务
                delayCondition,
                repeatMode,
                cronExpression,
                groupId,
                new RetryConfig(retryMode, maxRetries, retryDelay),
                0,
                delayCondition == DelayCondition.SPECIFIC_TIME ? selectedDateTime.getTimeInMillis() : null
        );

        if (listener != null) {
            listener.onTaskCreated(task);
        }

        dismiss();
    }

    private RepeatMode getRepeatModeFromSpinner() {
        String selected = repeatModeSpinner.getText().toString();
        if (selected.equals(getString(R.string.repeat_mode_daily))) return RepeatMode.DAILY;
        if (selected.equals(getString(R.string.repeat_mode_weekdays))) return RepeatMode.WEEKDAYS;
        if (selected.equals(getString(R.string.repeat_mode_weekends))) return RepeatMode.WEEKENDS;
        if (selected.equals(getString(R.string.repeat_mode_holidays))) return RepeatMode.HOLIDAYS;
        if (selected.equals(getString(R.string.repeat_mode_custom_cron))) return RepeatMode.CUSTOM_CRON;
        return RepeatMode.ONCE;
    }

    private RetryMode getRetryModeFromRetrySpinner() {
        String selected = retryModeSpinner.getText().toString();
        if (selected.equals(getString(R.string.retry_mode_immediate))) return RetryMode.IMMEDIATE;
        if (selected.equals(getString(R.string.retry_mode_delayed))) return RetryMode.DELAYED;
        if (selected.equals(getString(R.string.retry_mode_next_schedule))) return RetryMode.NEXT_SCHEDULE;
        return RetryMode.NO_RETRY;
    }

    private String getRepeatModeName(RepeatMode mode) {
        switch (mode) {
            case ONCE: return getString(R.string.repeat_mode_once);
            case DAILY: return getString(R.string.repeat_mode_daily);
            case WEEKDAYS: return getString(R.string.repeat_mode_weekdays);
            case WEEKENDS: return getString(R.string.repeat_mode_weekends);
            case HOLIDAYS: return getString(R.string.repeat_mode_holidays);
            case CUSTOM_CRON: return getString(R.string.repeat_mode_custom_cron);
            default: return "";
        }
    }

    private String getRetryModeName(RetryMode mode) {
        switch (mode) {
            case NO_RETRY: return getString(R.string.retry_mode_no_retry);
            case IMMEDIATE: return getString(R.string.retry_mode_immediate);
            case DELAYED: return getString(R.string.retry_mode_delayed);
            case NEXT_SCHEDULE: return getString(R.string.retry_mode_next_schedule);
            default: return "";
        }
    }
}
