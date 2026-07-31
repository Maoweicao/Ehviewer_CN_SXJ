package com.hippo.ehviewer.ui.automation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.EhActivity;
import com.hippo.ehviewer.task.TaskRegistry;
import com.hippo.ehviewer.task.automation.AutomationAction;
import com.hippo.ehviewer.task.automation.AutomationConditions;
import com.hippo.ehviewer.task.automation.AutomationManager;
import com.hippo.ehviewer.task.automation.AutomationTask;
import com.hippo.ehviewer.task.automation.AutomationTrigger;
import com.hippo.ehviewer.task.automation.EventTriggerType;
import com.hippo.ehviewer.task.automation.ExecutionMode;
import com.hippo.ehviewer.task.automation.RepeatMode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 三步向导：选择触发。-> 设置条件（可选） -> 选择动作。 */
public class WizardActivity extends EhActivity {

    private static final String EXTRA_EDIT_ID = "edit_id";

    public static void start(Context context, @Nullable String editTaskId) {
        Intent intent = new Intent(context, WizardActivity.class);
        if (editTaskId != null) intent.putExtra(EXTRA_EDIT_ID, editTaskId);
        context.startActivity(intent);
    }

    private enum Step { TRIGGER, CONDITIONS, ACTIONS }
    private Step currentStep = Step.TRIGGER;

    private AutomationManager manager;
    private AutomationTask editing;
    private AutomationTrigger trigger;
    private AutomationConditions conditions = new AutomationConditions();
    private final List<AutomationAction> actions = new ArrayList<>();
    private ExecutionMode executionMode = ExecutionMode.SEQUENTIAL;
    private String name = "";

    private void copyConditions(AutomationConditions src) {
        conditions = new AutomationConditions(
            src.getRequireWifi(), src.getRequireNoMetered(), src.getRequireCharging(), src.getRequireScreenOff(),
            src.getMinBatteryPercent(), src.getTimeWindow(), src.getWeekdayMask()
        );
    }

    private FrameLayout stepContainer;
    private Button btnPrev;
    private Button btnNext;

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
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_automation_wizard);

        manager = AutomationManager.getInstance(this);
        String editId = getIntent().getStringExtra(EXTRA_EDIT_ID);
        if (editId != null) {
            editing = manager.getTask(editId);
if (editing != null) {
            trigger = editing.getTrigger();
            copyConditions(editing.getConditions());
            actions.addAll(editing.getActions());
            executionMode = editing.getExecutionMode();
            name = editing.getName();
        }
        }
        if (trigger == null) trigger = new AutomationTrigger.Schedule(RepeatMode.ONCE, null, System.currentTimeMillis());

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.automation_wizard_step1_title);
        }

        stepContainer = findViewById(R.id.step_container);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);

        btnPrev.setOnClickListener(v -> {
            if (currentStep == Step.TRIGGER) return;
            int idx = currentStep.ordinal() - 1;
            currentStep = Step.values()[idx];
            renderStep();
        });

        btnNext.setOnClickListener(v -> {
            if (currentStep == Step.ACTIONS) {
                save();
                return;
            }
            int idx = currentStep.ordinal() + 1;
            currentStep = Step.values()[idx];
            renderStep();
        });

        renderStep();
    }

    private void renderStep() {
        btnPrev.setEnabled(currentStep != Step.TRIGGER);
        btnNext.setText(currentStep == Step.ACTIONS ? R.string.automation_wizard_save : R.string.automation_wizard_next);
        if (getSupportActionBar() != null) {
            int titleRes;
            switch (currentStep) {
                case TRIGGER: titleRes = R.string.automation_wizard_step1_title; break;
                case CONDITIONS: titleRes = R.string.automation_wizard_step2_title; break;
                case ACTIONS: titleRes = R.string.automation_wizard_step3_title; break;
                default: titleRes = R.string.automation_title;
            }
            getSupportActionBar().setTitle(titleRes);
        }
        stepContainer.removeAllViews();
        switch (currentStep) {
            case TRIGGER: renderTriggerStep(); break;
            case CONDITIONS: renderConditionsStep(); break;
            case ACTIONS: renderActionsStep(); break;
        }
    }

    private void renderTriggerStep() {
        View view = LayoutInflater.from(this).inflate(R.layout.wizard_step_trigger, stepContainer, false);
        RadioGroup triggerTypeGroup = view.findViewById(R.id.trigger_type_group);
        View scheduleContainer = view.findViewById(R.id.schedule_container);
        View eventContainer = view.findViewById(R.id.event_container);

        RadioButton rbSchedule = view.findViewById(R.id.rb_schedule);
        RadioButton rbEvent = view.findViewById(R.id.rb_event);
        RadioButton rbManual = view.findViewById(R.id.rb_manual);

        RadioGroup repeatGroup = view.findViewById(R.id.repeat_group);
        ChipGroup eventChips = view.findViewById(R.id.event_chips);

        if (trigger instanceof AutomationTrigger.Schedule) rbSchedule.setChecked(true);
        else if (trigger instanceof AutomationTrigger.Event) rbEvent.setChecked(true);
        else rbManual.setChecked(true);

        // Pre-populate repeat buttons
        AutomationTrigger.Schedule initialSchedule = trigger instanceof AutomationTrigger.Schedule
            ? (AutomationTrigger.Schedule) trigger : new AutomationTrigger.Schedule(RepeatMode.ONCE, null, null);
        int checkedId = repeatGroup.getCheckedRadioButtonId();
        // Initial check
        int targetId;
        switch (initialSchedule.getRepeatMode()) {
            case DAILY: targetId = R.id.rb_repeat_daily; break;
            case WEEKDAYS: targetId = R.id.rb_repeat_weekdays; break;
            case WEEKENDS: targetId = R.id.rb_repeat_weekends; break;
            case HOLIDAYS: targetId = R.id.rb_repeat_holidays; break;
            case ONCE: default: targetId = R.id.rb_repeat_once; break;
        }
        ((RadioButton) view.findViewById(targetId)).setChecked(true);

        // Build event chips
        for (EventTriggerType type : EventTriggerType.values()) {
            Chip chip = new Chip(this);
            chip.setText(type.getDisplayNameResId());
            chip.setCheckable(true);
            chip.setTag(type);
            eventChips.addView(chip);
        }
        if (trigger instanceof AutomationTrigger.Event) {
            EventTriggerType current = ((AutomationTrigger.Event) trigger).getType();
            for (int i = 0; i < eventChips.getChildCount(); i++) {
                Chip c = (Chip) eventChips.getChildAt(i);
                if (c.getTag() == current) c.setChecked(true);
            }
        }

        triggerTypeGroup.setOnCheckedChangeListener((g, id) -> {
            scheduleContainer.setVisibility(id == R.id.rb_schedule ? View.VISIBLE : View.GONE);
            eventContainer.setVisibility(id == R.id.rb_event ? View.VISIBLE : View.GONE);
        });
        triggerTypeGroup.check(triggerTypeGroup.getCheckedRadioButtonId());

        // Save back on next
        view.setTag(initialSchedule);
        btnNext.setOnClickListener(v -> {
            int selectedTypeId = triggerTypeGroup.getCheckedRadioButtonId();
            if (selectedTypeId == R.id.rb_schedule) {
                RepeatMode mode;
                int repId = repeatGroup.getCheckedRadioButtonId();
                if (repId == R.id.rb_repeat_daily) mode = RepeatMode.DAILY;
                else if (repId == R.id.rb_repeat_weekdays) mode = RepeatMode.WEEKDAYS;
                else if (repId == R.id.rb_repeat_weekends) mode = RepeatMode.WEEKENDS;
                else if (repId == R.id.rb_repeat_holidays) mode = RepeatMode.HOLIDAYS;
                else mode = RepeatMode.ONCE;
                trigger = new AutomationTrigger.Schedule(mode, null, System.currentTimeMillis());
            } else if (selectedTypeId == R.id.rb_event) {
                EventTriggerType selected = null;
                for (int i = 0; i < eventChips.getChildCount(); i++) {
                    Chip c = (Chip) eventChips.getChildAt(i);
                    if (c.isChecked()) { selected = (EventTriggerType) c.getTag(); break; }
                }
                if (selected == null) selected = EventTriggerType.DOWNLOAD_FINISHED;
                trigger = new AutomationTrigger.Event(selected, com.hippo.ehviewer.task.automation.EventFilter.None.INSTANCE);
            } else {
                trigger = AutomationTrigger.Manual.INSTANCE;
            }
            // Move to conditions step
            currentStep = Step.CONDITIONS;
            renderStep();
        });

        btnPrev.setOnClickListener(v -> finish());
        stepContainer.addView(view);
    }

    private void renderConditionsStep() {
        View view = LayoutInflater.from(this).inflate(R.layout.wizard_step_conditions, stepContainer, false);
        MaterialSwitch swWifi = view.findViewById(R.id.sw_wifi);
        MaterialSwitch swNoMetered = view.findViewById(R.id.sw_no_metered);
        MaterialSwitch swCharging = view.findViewById(R.id.sw_charging);
        MaterialSwitch swScreenOff = view.findViewById(R.id.sw_screen_off);
        TextInputEditText etName = view.findViewById(R.id.et_name);

        swWifi.setTextOn(""); swWifi.setTextOff("");
        swNoMetered.setTextOn(""); swNoMetered.setTextOff("");
        swCharging.setTextOn(""); swCharging.setTextOff("");
        swScreenOff.setTextOn(""); swScreenOff.setTextOff("");

        swWifi.setChecked(conditions.getRequireWifi());
        swNoMetered.setChecked(conditions.getRequireNoMetered());
        swCharging.setChecked(conditions.getRequireCharging());
        swScreenOff.setChecked(conditions.getRequireScreenOff());
        etName.setText(name);

        btnNext.setOnClickListener(v -> {
            conditions = new AutomationConditions(
                swWifi.isChecked(),
                swNoMetered.isChecked(),
                swCharging.isChecked(),
                swScreenOff.isChecked(),
                conditions.getMinBatteryPercent(),
                conditions.getTimeWindow(),
                conditions.getWeekdayMask()
            );
            name = etName.getText() != null ? etName.getText().toString().trim() : "";
            currentStep = Step.ACTIONS;
            renderStep();
        });

        btnPrev.setOnClickListener(v -> {
            currentStep = Step.TRIGGER;
            renderStep();
        });

        stepContainer.addView(view);
    }

    private void renderActionsStep() {
        View view = LayoutInflater.from(this).inflate(R.layout.wizard_step_actions, stepContainer, false);
        RadioGroup execGroup = view.findViewById(R.id.exec_group);
        LinearLayout actionsLayout = view.findViewById(R.id.actions_layout);
        TextView actionsCount = view.findViewById(R.id.actions_count);
        Button addAction = view.findViewById(R.id.btn_add_action);

        if (executionMode == ExecutionMode.SEQUENTIAL) execGroup.check(R.id.rb_sequential);
        else execGroup.check(R.id.rb_parallel);

        refreshActionsList(actionsLayout, actionsCount);

        addAction.setOnClickListener(v -> showActionPicker());

        execGroup.setOnCheckedChangeListener((g, id) -> {
            executionMode = id == R.id.rb_sequential ? ExecutionMode.SEQUENTIAL : ExecutionMode.PARALLEL;
        });

        btnNext.setOnClickListener(v -> save());
        btnPrev.setOnClickListener(v -> {
            currentStep = Step.CONDITIONS;
            renderStep();
        });

        stepContainer.addView(view);
    }

    private void refreshActionsList(LinearLayout container, TextView countText) {
        container.removeAllViews();
        for (AutomationAction action : actions) {
            TextView tv = new TextView(this);
            tv.setText("- " + action.getDisplayName());
            tv.setTextSize(14);
            tv.setPadding(0, 8, 0, 8);
            tv.setOnLongClickListener(v -> {
                actions.remove(action);
                refreshActionsList(container, countText);
                return true;
            });
            container.addView(tv);
        }
        countText.setText(getString(R.string.automation_actions_count, actions.size()));
    }

    private void showActionPicker() {
        List<TaskRegistry.TaskMetadata> all = TaskRegistry.INSTANCE.getAllTasks();
        String[] names = new String[all.size()];
        final TaskRegistry.TaskMetadata[] metas = all.toArray(new TaskRegistry.TaskMetadata[0]);
        for (int i = 0; i < metas.length; i++) {
            names[i] = getString(metas[i].getDisplayNameResId());
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.automation_wizard_step3_title)
            .setItems(names, (d, which) -> {
                TaskRegistry.TaskMetadata m = metas[which];
                AutomationAction action = new AutomationAction(
                    UUID.randomUUID().toString(),
                    m.getTaskClassName(),
                    getString(m.getDisplayNameResId()),
                    m.getTaskType().name(),
                    null,
                    true
                );
                actions.add(action);
                LinearLayout container = findViewById(R.id.actions_layout);
                TextView countText = findViewById(R.id.actions_count);
                if (container != null && countText != null) refreshActionsList(container, countText);
            })
            .show();
    }

    private void save() {
        if (actions.isEmpty()) {
            new AlertDialog.Builder(this)
                .setMessage(R.string.automation_wizard_step3_title)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        AutomationTask task;
        if (editing != null) {
            task = new AutomationTask(
                editing.getId(),
                name.isEmpty() ? "Automation" : name,
                editing.getEnabled(),
                trigger,
                conditions,
                actions,
                executionMode,
                editing.getRetryConfig(),
                editing.getCooldownMillis(),
                editing.getOrder(),
                editing.getState(),
                editing.getCreatedAt(),
                editing.getLastExecutedAt(),
                editing.getNextScheduledTime(),
                editing.getExecutionCount(),
                editing.getRetryCount()
            );
        } else {
            task = new AutomationTask(
                UUID.randomUUID().toString(),
                name.isEmpty() ? "Automation" : name,
                true,
                trigger,
                conditions,
                actions,
                executionMode,
                new com.hippo.ehviewer.task.automation.RetryConfig(),
                0L,
                manager.getAllTasks().size(),
                com.hippo.ehviewer.task.automation.AutomationState.PENDING,
                System.currentTimeMillis(),
                null,
                null,
                0,
                0
            );
        }
        manager.upsertTask(task);
        finish();
    }
}