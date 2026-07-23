package com.hippo.ehviewer.ui.scheduled;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.EhActivity;
import com.hippo.ehviewer.task.scheduled.ScheduledTask;
import com.hippo.ehviewer.task.scheduled.ScheduledTaskManager;
import com.hippo.ehviewer.task.scheduled.TaskGroup;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 定时任务管理界面
 */
public class ScheduledTaskActivity extends EhActivity implements ScheduledTaskManager.ScheduledTaskListener {

    private ScheduledTaskManager taskManager;
    private ScheduledTaskAdapter adapter;
    private RecyclerView taskList;
    private TextView noTasksText;
    private TextView holidayLastUpdate;
    private TextView holidayExpiredWarning;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public static void start(Context context) {
        Intent intent = new Intent(context, ScheduledTaskActivity.class);
        context.startActivity(intent);
    }

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
        setContentView(R.layout.activity_scheduled_task);

        taskManager = ScheduledTaskManager.getInstance(this);
        taskManager.addListener(this);

        initToolbar();
        initViews();
        initTaskList();
        loadTasks();
        updateHolidayInfo();
    }

    @Override
    protected void onDestroy() {
        taskManager.removeListener(this);
        super.onDestroy();
    }

    private void initToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void initViews() {
        taskList = findViewById(R.id.task_list);
        noTasksText = findViewById(R.id.no_tasks_text);
        holidayLastUpdate = findViewById(R.id.holiday_last_update);
        holidayExpiredWarning = findViewById(R.id.holiday_expired_warning);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showAddTaskDialog());

        // 提示文字可点击添加任务
        noTasksText.setOnClickListener(v -> showAddTaskDialog());

        findViewById(R.id.add_group_button).setOnClickListener(v -> showAddGroupDialog());

        findViewById(R.id.refresh_holiday_button).setOnClickListener(v -> {
            Toast.makeText(this, R.string.holiday_data_refreshing, Toast.LENGTH_SHORT).show();
            taskManager.refreshHolidayData();
        });

        findViewById(R.id.log_card).setOnClickListener(v -> {
            ScheduledTaskLogActivity.start(this);
        });
    }

    private void initTaskList() {
        adapter = new ScheduledTaskAdapter(this);
        adapter.setOnTaskActionListener(new ScheduledTaskAdapter.OnTaskActionListener() {
            @Override
            public void onEdit(ScheduledTask task) {
                showEditTaskDialog(task);
            }

            @Override
            public void onDelete(ScheduledTask task) {
                showDeleteConfirmDialog(task);
            }

            @Override
            public void onMoveToGroup(ScheduledTask task) {
                showMoveToGroupDialog(task);
            }
        });

        taskList.setLayoutManager(new LinearLayoutManager(this));
        taskList.setAdapter(adapter);

        // 设置拖拽
        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return adapter.onItemMove(viewHolder.getAdapterPosition(), target.getAdapterPosition());
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // 不处理滑动
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                // 保存新的顺序
                String groupId = adapter.getCurrentGroupId();
                List<String> taskIds = adapter.getTaskOrder();
                if (groupId != null && taskIds != null) {
                    taskManager.updateTaskOrder(groupId, taskIds);
                }
            }
        };
        new ItemTouchHelper(callback).attachToRecyclerView(taskList);
    }

    private void loadTasks() {
        List<ScheduledTask> tasks = taskManager.getAllTasks();
        adapter.setTasks(tasks);

        noTasksText.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);
        taskList.setVisibility(tasks.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateHolidayInfo() {
        Long lastUpdate = taskManager.getHolidayProvider().getLastUpdateTime();
        if (lastUpdate != null) {
            holidayLastUpdate.setText(getString(R.string.holiday_data_last_update, dateFormat.format(new Date(lastUpdate))));
        }

        boolean expired = taskManager.getHolidayProvider().isDataExpired();
        holidayExpiredWarning.setVisibility(expired ? View.VISIBLE : View.GONE);
    }

    private void showAddTaskDialog() {
        AddScheduledTaskDialog dialog = new AddScheduledTaskDialog();
        dialog.setOnTaskCreatedListener(task -> {
            taskManager.addScheduledTask(task);
            loadTasks();
        });
        dialog.show(getSupportFragmentManager(), "add_task");
    }

    private void showEditTaskDialog(ScheduledTask task) {
        AddScheduledTaskDialog dialog = AddScheduledTaskDialog.newInstance(task);
        dialog.setOnTaskCreatedListener(updatedTask -> {
            taskManager.removeScheduledTask(task.getId());
            taskManager.addScheduledTask(updatedTask);
            loadTasks();
        });
        dialog.show(getSupportFragmentManager(), "edit_task");
    }

    private void showDeleteConfirmDialog(ScheduledTask task) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.scheduled_task_delete)
                .setMessage(R.string.scheduled_task_delete_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    taskManager.removeScheduledTask(task.getId());
                    loadTasks();
                    Toast.makeText(this, R.string.scheduled_task_delete, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMoveToGroupDialog(ScheduledTask task) {
        List<TaskGroup> groups = taskManager.getTaskGroups();
        String[] groupNames = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            groupNames[i] = groups.get(i).getGroupName();
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.task_group_title)
                .setItems(groupNames, (dialog, which) -> {
                    TaskGroup selectedGroup = groups.get(which);
                    taskManager.moveTaskToGroup(task.getId(), selectedGroup.getGroupId());
                    loadTasks();
                })
                .show();
    }

    private void showAddGroupDialog() {
        TaskGroupManagerDialog dialog = new TaskGroupManagerDialog();
        dialog.setOnGroupCreatedListener(group -> {
            taskManager.createGroup(group.getGroupName(), group.getExecutionMode());
            loadTasks();
        });
        dialog.show(getSupportFragmentManager(), "add_group");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_scheduled_task, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_view_logs) {
            ScheduledTaskLogActivity.start(this);
            return true;
        } else if (id == R.id.action_manage_groups) {
            showManageGroupsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showManageGroupsDialog() {
        TaskGroupManagerDialog dialog = new TaskGroupManagerDialog();
        dialog.setOnGroupCreatedListener(group -> {
            taskManager.createGroup(group.getGroupName(), group.getExecutionMode());
            loadTasks();
        });
        dialog.show(getSupportFragmentManager(), "manage_groups");
    }

    @Override
    public void onTaskAdded(ScheduledTask task) {
        runOnUiThread(this::loadTasks);
    }

    @Override
    public void onTaskRemoved(String taskId) {
        runOnUiThread(this::loadTasks);
    }

    @Override
    public void onTaskStateChanged(String taskId) {
        runOnUiThread(this::loadTasks);
    }

    @Override
    public void onTaskGroupChanged() {
        runOnUiThread(this::loadTasks);
    }
}
