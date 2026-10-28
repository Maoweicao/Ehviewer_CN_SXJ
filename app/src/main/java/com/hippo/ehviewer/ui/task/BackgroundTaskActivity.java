package com.hippo.ehviewer.ui.task;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.EhActivity;
import com.hippo.ehviewer.ui.MainActivity;

/**
 * 后台任务管理Activity
 * 显示所有正在运行和已完成的后台任务
 */
public class BackgroundTaskActivity extends EhActivity {
    
    /** 事件驱动刷新：进度事件本身已按 300ms 合并，这里再合并一次避免重复重建列表。 */
    private static final long REFRESH_THROTTLE_MS = 300;
    
    private RecyclerView mRecyclerView;
    private BackgroundTaskAdapter mAdapter;
    private BackgroundTaskStatusManager mTaskManager;
    private Handler mHandler;
    private boolean mRefreshScheduled;
    
    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            mRefreshScheduled = false;
            refreshData();
        }
    };

    /**
     * 任务状态事件：任务增删、进度、状态变化都直接刷新界面，
     * 不再依赖 1.5s 轮询（轮询在任务结束后仍会空转，且进度显示延迟明显）。
     */
    private final BackgroundTaskStatusManager.TaskChangeListener mTaskChangeListener =
            new BackgroundTaskStatusManager.TaskChangeListener() {
                @Override
                public void onTaskAdded(String taskId) {
                    scheduleRefresh();
                }

                @Override
                public void onTaskProgressChanged(String taskId) {
                    scheduleRefresh();
                }

                @Override
                public void onTaskStateChanged(String taskId) {
                    scheduleRefresh();
                }

                @Override
                public void onTaskRemoved(String taskId) {
                    scheduleRefresh();
                }
            };

    private void scheduleRefresh() {
        if (mRefreshScheduled) {
            return;
        }
        mRefreshScheduled = true;
        mHandler.postDelayed(mRefreshRunnable, REFRESH_THROTTLE_MS);
    }
    
    public static void start(@NonNull Context context) {
        Intent intent = new Intent(context, BackgroundTaskActivity.class);
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
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_background_task);
        
        mHandler = new Handler(Looper.getMainLooper());
        setupActionBar();
        initViews();
        initTaskManager();
    }
    
    private void setupActionBar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setHomeButtonEnabled(true);
            }
        }
    }

    private void navigateBackOrHome() {
        if (!isTaskRoot()) {
            finish();
        } else {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            navigateBackOrHome();
            return true;
        } else if (item.getItemId() == R.id.action_clear_completed) {
            if (mTaskManager != null && mAdapter != null) {
                mTaskManager.clearCompletedTasks();
                refreshData();
                Toast.makeText(this, R.string.background_task_cleared_completed, Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (item.getItemId() == R.id.action_clear_all) {
            if (mAdapter != null) {
                BackgroundTaskManager.getInstance().forceStopAllTasks();
                refreshData();
                Toast.makeText(this, R.string.background_task_cleared_all, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    private void initViews() {
        mRecyclerView = findViewById(R.id.recycler_view);
        if (mRecyclerView != null) {
            mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
            mAdapter = new BackgroundTaskAdapter(this);
            mRecyclerView.setAdapter(mAdapter);
            
            // 设置点击监听器
            mAdapter.setOnItemClickListener(taskInfo -> {
                // 点击任务项，显示详细信息
                BackgroundTaskDetailActivity.start(this, taskInfo.getTaskId());
            });
        }
    }
    
    private void initTaskManager() {
        mTaskManager = BackgroundTaskStatusManager.getInstance();
        refreshData();
    }
    
    private void refreshData() {
        if (mAdapter != null && mTaskManager != null) {
            java.util.List<BackgroundTaskInfo> running = mTaskManager.getRunningTasks();
            java.util.List<BackgroundTaskInfo> waiting = mTaskManager.getWaitingTasks();
            java.util.List<BackgroundTaskInfo> completed = mTaskManager.getCompletedTasks();
            // 结构不变时只刷新进度，避免每个进度事件都重建整个列表
            if (!mAdapter.updateProgressInPlace(running, waiting, completed)) {
                mAdapter.updateData(running, waiting, completed);
            }
            updateToolbarTitle();
        }
    }
    
    private void updateToolbarTitle() {
        int activeCount = mTaskManager != null ? mTaskManager.getActiveTasks().size() : 0;
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(getString(R.string.background_task_management_with_count, activeCount));
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (mTaskManager != null) {
            mTaskManager.addTaskChangeListener(mTaskChangeListener);
        }
        // 进入页面时做一次全量对账，覆盖暂停期间可能错过的事件
        refreshData();
    }

    @Override
    protected void onPause() {
        if (mTaskManager != null) {
            mTaskManager.removeTaskChangeListener(mTaskChangeListener);
        }
        mHandler.removeCallbacks(mRefreshRunnable);
        mRefreshScheduled = false;
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_background_task, menu);
        return true;
    }

    @Override
    public void onBackPressed() {
        navigateBackOrHome();
    }
}
