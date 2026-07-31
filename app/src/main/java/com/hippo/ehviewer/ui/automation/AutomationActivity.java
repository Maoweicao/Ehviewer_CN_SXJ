package com.hippo.ehviewer.ui.automation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.EhActivity;
import com.hippo.ehviewer.task.automation.AutomationAction;
import com.hippo.ehviewer.task.automation.AutomationConditions;
import com.hippo.ehviewer.task.automation.AutomationLogger;
import com.hippo.ehviewer.task.automation.AutomationManager;
import com.hippo.ehviewer.task.automation.AutomationState;
import com.hippo.ehviewer.task.automation.AutomationTask;
import com.hippo.ehviewer.task.automation.AutomationTrigger;
import com.hippo.ehviewer.task.automation.EventTriggerType;
import com.hippo.ehviewer.task.automation.RepeatMode;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
/**
 * 自动化任务列表主页。* 每个任务一张卡片：WHEN / IF / THEN 三段式；顶部 Chips 筛选触发器类型。*/
public class AutomationActivity extends EhActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, AutomationActivity.class));
    }

    private AutomationManager manager;
    private AutomationLogger logger;
    private AutomationAdapter adapter;
    private View emptyView;

    private enum Filter { ALL, SCHEDULE, EVENT, MANUAL }

    private Filter currentFilter = Filter.ALL;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    @Override
    protected int getThemeResId(int theme) {
        switch (theme) {
            case Settings.THEME_LIGHT:
            default:
                return R.style.AppTheme_Toolbar;
            case Settings.THEME_DARK:
                return R.style.AppTheme_Toolbar_Dark;
            case Settings.THEME_BLACK:
                return R.style.AppTheme_Toolbar_Black;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_automation);

        manager = AutomationManager.getInstance(this);
        logger = new AutomationLogger(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        emptyView = findViewById(R.id.empty_view);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AutomationAdapter();
        list.setAdapter(adapter);

        ExtendedFloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> WizardActivity.start(this, null));

        ChipGroup filterChips = findViewById(R.id.filter_chips);
        filterChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chip_all) currentFilter = Filter.ALL;
            else if (id == R.id.chip_schedule) currentFilter = Filter.SCHEDULE;
            else if (id == R.id.chip_event) currentFilter = Filter.EVENT;
            else if (id == R.id.chip_manual) currentFilter = Filter.MANUAL;
            refresh();
        });

        manager.addListener(taskListener);
    }

    @Override
    protected void onDestroy() {
        manager.removeListener(taskListener);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private final AutomationManager.AutomationListener taskListener = new AutomationManager.AutomationListener() {
        @Override
        public void onTaskChanged(String taskId) {
            runOnUiThread(AutomationActivity.this::refresh);
        }

        @Override
        public void onTaskRemoved(String taskId) {
            runOnUiThread(AutomationActivity.this::refresh);
        }
    };

    private void refresh() {
        List<AutomationTask> all = manager.getAllTasks();
        List<AutomationTask> filtered = new ArrayList<>();
        for (AutomationTask task : all) {
            if (matchesFilter(task, currentFilter)) filtered.add(task);
        }
        adapter.setTasks(filtered);
        emptyView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static boolean matchesFilter(AutomationTask task, Filter f) {
        switch (f) {
            case ALL: return true;
            case SCHEDULE: return task.getTrigger() instanceof AutomationTrigger.Schedule;
            case EVENT: return task.getTrigger() instanceof AutomationTrigger.Event;
            case MANUAL: return task.getTrigger() == AutomationTrigger.Manual.INSTANCE;
            default: return true;
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmDelete(AutomationTask task) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.automation_delete)
            .setMessage(R.string.automation_delete_confirm)
            .setPositiveButton(android.R.string.ok, (d, w) -> manager.deleteTask(task.getId()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private final class AutomationAdapter extends RecyclerView.Adapter<AutomationAdapter.VH> {

        private final List<AutomationTask> data = new ArrayList<>();

        void setTasks(List<AutomationTask> tasks) {
            data.clear();
            data.addAll(tasks);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_automation, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AutomationTask task = data.get(position);
            holder.bind(task);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView iconTrigger;
            final TextView textTrigger;
            final MaterialSwitch switchEnabled;
            final TextView textName;
            final ChipGroup conditions;
            final LinearLayout actionsContainer;
            final TextView textState;
            final ImageButton btnRun;
            final ImageButton btnEdit;
            final ImageButton btnMore;

            VH(@NonNull View itemView) {
                super(itemView);
                iconTrigger = itemView.findViewById(R.id.icon_trigger);
                textTrigger = itemView.findViewById(R.id.text_trigger);
                switchEnabled = itemView.findViewById(R.id.switch_enabled);
                switchEnabled.setText("");
                switchEnabled.setTextOn("");
                switchEnabled.setTextOff("");
                textName = itemView.findViewById(R.id.text_name);
                conditions = itemView.findViewById(R.id.chips_conditions);
                actionsContainer = itemView.findViewById(R.id.actions_container);
                textState = itemView.findViewById(R.id.text_state);
                btnRun = itemView.findViewById(R.id.btn_run);
                btnEdit = itemView.findViewById(R.id.btn_edit);
                btnMore = itemView.findViewById(R.id.btn_more);
            }

            void bind(AutomationTask task) {
                textTrigger.setText(describeTrigger(task));
                textName.setText(task.getName());
                textName.setVisibility(task.getName().isEmpty() ? View.GONE : View.VISIBLE);
                switchEnabled.setChecked(task.getEnabled());
                switchEnabled.setOnCheckedChangeListener((b, isChecked) -> {
                    if (isChecked != task.getEnabled()) {
                        manager.setEnabled(task.getId(), isChecked);
                    }
                });
                bindConditions(task.getConditions());
                bindActions(task);
                bindState(task);
                btnRun.setOnClickListener(v -> manager.runNow(task.getId()));
                btnEdit.setOnClickListener(v -> WizardActivity.start(AutomationActivity.this, task.getId()));
                btnMore.setOnClickListener(v -> showItemMenu(task));
            }

            private String describeTrigger(AutomationTask task) {
                AutomationTrigger t = task.getTrigger();
                if (t instanceof AutomationTrigger.Schedule) {
                    AutomationTrigger.Schedule s = (AutomationTrigger.Schedule) t;
                    String time = s.getScheduledTime() != null ? timeFormat.format(new Date(s.getScheduledTime())) : "--";
                    switch (s.getRepeatMode()) {
                        case DAILY:
                            return getString(R.string.automation_schedule_daily, time);
                        case WEEKDAYS:
                            return getString(R.string.automation_schedule_weekdays, time);
                        case WEEKENDS:
                            return getString(R.string.automation_schedule_weekends, time);
                        case HOLIDAYS:
                            return getString(R.string.automation_schedule_holidays, time);
                        case CUSTOM_CRON:
                            return getString(R.string.automation_schedule_cron, s.getCronExpression() != null ? s.getCronExpression() : "--");
                        case ONCE:
                        default:
                            return getString(R.string.automation_schedule_once, time);
                    }
                } else if (t instanceof AutomationTrigger.Event) {
                    EventTriggerType et = ((AutomationTrigger.Event) t).getType();
                    return getString(et.getDisplayNameResId());
                } else if (t == AutomationTrigger.Manual.INSTANCE) {
                    return getString(R.string.automation_schedule_manual);
                }
                return "";
            }

            private void bindConditions(AutomationConditions c) {
                conditions.removeAllViews();
                List<String> chips = new ArrayList<>();
                if (c.getRequireWifi()) chips.add(getString(R.string.automation_condition_wifi));
                if (c.getRequireCharging()) chips.add(getString(R.string.automation_condition_charging));
                if (c.getRequireScreenOff()) chips.add(getString(R.string.automation_condition_screen_off));
                if (c.getMinBatteryPercent() > 0) chips.add(getString(R.string.automation_condition_min_battery, c.getMinBatteryPercent()));
                if (c.getTimeWindow() != null) chips.add(getString(R.string.automation_condition_time_window,
                    String.format(Locale.getDefault(), "%02d:%02d", c.getTimeWindow().getStartMinuteOfDay() / 60, c.getTimeWindow().getStartMinuteOfDay() % 60),
                    String.format(Locale.getDefault(), "%02d:%02d", c.getTimeWindow().getEndMinuteOfDay() / 60, c.getTimeWindow().getEndMinuteOfDay() % 60)));
                if (chips.isEmpty()) {
                    conditions.setVisibility(View.GONE);
                } else {
                    conditions.setVisibility(View.VISIBLE);
                    for (String label : chips) {
                        Chip chip = new Chip(conditions.getContext());
                        chip.setText(label);
                        chip.setClickable(false);
                        chip.setCheckable(false);
                        conditions.addView(chip);
                    }
                }
            }

            private void bindActions(AutomationTask task) {
                actionsContainer.removeAllViews();
                String separator = task.getExecutionMode() == com.hippo.ehviewer.task.automation.ExecutionMode.SEQUENTIAL ? "->" : "||";
                LayoutInflater inflater = LayoutInflater.from(actionsContainer.getContext());
                for (int i = 0; i < task.getActions().size(); i++) {
                    AutomationAction action = task.getActions().get(i);
                    if (i > 0) {
                        TextView sep = new TextView(actionsContainer.getContext());
                        sep.setText(separator + " ");
                        sep.setTextColor(0xFF888888);
                        actionsContainer.addView(sep);
                    }
                    TextView actionView = (TextView) inflater.inflate(android.R.layout.simple_list_item_1, actionsContainer, false);
                    actionView.setText("- " + action.getDisplayName());
                    actionView.setTextSize(14);
                    actionsContainer.addView(actionView);
                }
            }

            private void bindState(AutomationTask task) {
                String stateLabel = stateLabel(task.getState());
                if (task.getLastExecutedAt() != null) {
                    stateLabel += " · " + getString(R.string.scheduled_task_last_execution,
                        timeFormat.format(new Date(task.getLastExecutedAt())));
                }
                textState.setText(stateLabel);
            }

            private String stateLabel(AutomationState s) {
                switch (s) {
                    case PENDING: return getString(R.string.automation_state_pending);
                    case WAITING_CONDITION: return getString(R.string.automation_state_waiting_condition);
                    case WAITING_TIME: return getString(R.string.automation_state_waiting_time);
                    case RUNNING: return getString(R.string.automation_state_running);
                    case COMPLETED: return getString(R.string.automation_state_completed);
                    case FAILED: return getString(R.string.automation_state_failed);
                    case CANCELLED: return getString(R.string.automation_state_cancelled);
                    case PAUSED: return getString(R.string.automation_state_paused);
                    case DISABLED: return getString(R.string.automation_state_disabled);
                    default: return s.name();
                }
            }

            private void showItemMenu(AutomationTask task) {
                String[] items = new String[] {
                    getString(R.string.automation_run_now),
                    getString(R.string.automation_delete)
                };
                new AlertDialog.Builder(AutomationActivity.this)
                    .setItems(items, (d, w) -> {
                        if (w == 0) manager.runNow(task.getId());
                        else confirmDelete(task);
                    })
                    .show();
            }
        }
    }
}