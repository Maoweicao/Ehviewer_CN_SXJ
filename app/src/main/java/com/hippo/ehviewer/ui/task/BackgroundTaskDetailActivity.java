package com.hippo.ehviewer.ui.task;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.core.content.FileProvider;

import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.EhActivity;
import com.hippo.util.ReadableTime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

/**
 * 后台任务详情Activity
 * 显示单个任务的详细信息、进度和日志
 */
public class BackgroundTaskDetailActivity extends EhActivity {
    
    private static final String KEY_TASK_ID = "task_id";
    private static final long REFRESH_INTERVAL = 1500; // 1.5秒刷新一次
    private static final int MAX_DISPLAY_LOGS = 100;
    
    private TextView mTaskNameText;
    private TextView mTaskDescriptionText;
    private TextView mTaskTypeText;
    private TextView mTaskProgressText;
    private TextView mTaskProgressDetailText;
    private TextView mTaskStatusText;
    private TextView mTaskTimeText;
    private TextView mTaskEtaText;
    private TextView mTaskEtaDetailText;
    private TextView mTaskErrorText;
    private TextView mTaskLogText;
    private TextView mTaskLogPathText;
    private TextView mUniqueBadge;
    private ProgressBar mProgressBar;
    private View mProgressPanel;
    private ScrollView mLogScroll;
    private Button mBtnExportTxt;
    private Button mBtnExportJson;
    private Button mBtnPause;
    private Button mBtnResume;
    private Button mBtnStop;
    private Button mBtnDelete;
    
    private BackgroundTaskStatusManager mTaskManager;
    private String mTaskId;
    private Handler mHandler;
    private int mLastLogTotalCount = -1;
    
    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateTaskInfo();
            mHandler.postDelayed(this, REFRESH_INTERVAL);
        }
    };
    
    public static void start(@NonNull Context context, @NonNull String taskId) {
        Intent intent = new Intent(context, BackgroundTaskDetailActivity.class);
        intent.putExtra(KEY_TASK_ID, taskId);
        context.startActivity(intent);
    }

    @Override
    protected int getThemeResId(int theme) {
        switch (theme) {
            case Settings.THEME_LIGHT:
            default:
                return R.style.AppTheme;
            case Settings.THEME_DARK:
                return R.style.AppTheme_Dark;
            case Settings.THEME_BLACK:
                return R.style.AppTheme_Black;
        }
    }
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_background_task_detail);
        
        mTaskId = getIntent().getStringExtra(KEY_TASK_ID);
        if (mTaskId == null) {
            finish();
            return;
        }
        
        mHandler = new Handler(Looper.getMainLooper());
        setupActionBar();
        initViews();
        initTaskManager();
        updateTaskInfo();
    }
    
    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.background_task_detail);
        }
    }
    
    private void initViews() {
        mTaskNameText = findViewById(R.id.task_name);
        mTaskDescriptionText = findViewById(R.id.task_description);
        mTaskTypeText = findViewById(R.id.task_type);
        mTaskProgressText = findViewById(R.id.task_progress);
        mTaskProgressDetailText = findViewById(R.id.task_progress_detail);
        mTaskStatusText = findViewById(R.id.task_status);
        mTaskTimeText = findViewById(R.id.task_time);
        mTaskEtaText = findViewById(R.id.task_eta);
        mTaskEtaDetailText = findViewById(R.id.task_eta_detail);
        mTaskErrorText = findViewById(R.id.task_error);
        mTaskLogText = findViewById(R.id.task_log);
        mTaskLogPathText = findViewById(R.id.task_log_path);
        mUniqueBadge = findViewById(R.id.task_unique_badge);
        mProgressBar = findViewById(R.id.progress_bar);
        mProgressPanel = findViewById(R.id.progress_panel);
        mLogScroll = findViewById(R.id.log_scroll);
        mBtnExportTxt = findViewById(R.id.btn_export_txt);
        mBtnExportJson = findViewById(R.id.btn_export_json);
        mBtnPause = findViewById(R.id.btn_control_pause);
        mBtnResume = findViewById(R.id.btn_control_resume);
        mBtnStop = findViewById(R.id.btn_control_stop);
        mBtnDelete = findViewById(R.id.btn_control_delete);
        
        if (mBtnExportTxt != null) {
            mBtnExportTxt.setOnClickListener(v -> exportLogAsTxt());
        }
        if (mBtnExportJson != null) {
            mBtnExportJson.setOnClickListener(v -> exportLogAsJson());
        }
        if (mBtnPause != null) {
            mBtnPause.setOnClickListener(v -> handlePause());
        }
        if (mBtnResume != null) {
            mBtnResume.setOnClickListener(v -> handleResume());
        }
        if (mBtnStop != null) {
            mBtnStop.setOnClickListener(v -> handleStop());
        }
        if (mBtnDelete != null) {
            mBtnDelete.setOnClickListener(v -> handleDelete());
        }
    }

    private void handlePause() {
        if (mTaskId == null) return;
        if (BackgroundTaskManager.getInstance().pauseTask(mTaskId)) {
            Toast.makeText(this, R.string.task_paused, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.task_cancelling, Toast.LENGTH_SHORT).show();
        }
    }

    private void handleResume() {
        if (mTaskId == null) return;
        if (BackgroundTaskManager.getInstance().resumeTask(mTaskId)) {
            Toast.makeText(this, R.string.task_resumed, Toast.LENGTH_SHORT).show();
        }
    }

    private void handleStop() {
        if (mTaskId == null) return;
        BackgroundTaskManager.getInstance().cancelTask(mTaskId);
        Toast.makeText(this, R.string.task_cancelling, Toast.LENGTH_SHORT).show();
    }

    private void handleDelete() {
        if (mTaskId == null) return;
        BackgroundTaskInfo taskInfo = mTaskManager != null ? mTaskManager.getTaskInfo(mTaskId) : null;
        if (taskInfo != null && (taskInfo.isCompleted() || taskInfo.isCancelled())) {
            mTaskManager.removeFromCompleted(mTaskId);
        } else {
            BackgroundTaskManager.getInstance().removeTask(mTaskId);
        }
        Toast.makeText(this, R.string.task_cleared_from_completed, Toast.LENGTH_SHORT).show();
        finish();
    }
    
    private void initTaskManager() {
        mTaskManager = BackgroundTaskStatusManager.getInstance();
    }
    
    private void updateTaskInfo() {
        if (mTaskManager == null || mTaskId == null) {
            return;
        }
        
        BackgroundTaskInfo taskInfo = mTaskManager.getTaskInfo(mTaskId);
        if (taskInfo == null) {
            finish();
            return;
        }
        
        // 更新任务名称
        if (mTaskNameText != null) {
            mTaskNameText.setText(taskInfo.getTaskName());
        }
        
        // 更新互斥徽标
        if (mUniqueBadge != null) {
            mUniqueBadge.setVisibility(taskInfo.isUniqueTask() ? View.VISIBLE : View.GONE);
        }
        
        // 更新任务描述
        if (mTaskDescriptionText != null) {
            String description = taskInfo.getTaskDescription();
            mTaskDescriptionText.setText(description != null && !description.isEmpty() 
                ? description : getString(R.string.task_no_description));
        }
        
        // 更新任务类型
        if (mTaskTypeText != null) {
            mTaskTypeText.setText(taskInfo.getTaskType().name());
        }
        
        // 更新进度面板
        int percentage = taskInfo.getProgressPercentage();
        if (percentage >= 0) {
            // 有确定进度
            if (mProgressPanel != null) {
                mProgressPanel.setVisibility(View.VISIBLE);
            }
            if (mProgressBar != null) {
                mProgressBar.setMax(100);
                mProgressBar.setProgress(percentage);
                mProgressBar.setIndeterminate(false);
            }
            if (mTaskProgressText != null) {
                mTaskProgressText.setText(getString(R.string.task_progress_format, 
                    taskInfo.getCurrentProgress(), taskInfo.getTotalProgress(), percentage));
            }
            // ETA
            long eta = taskInfo.getEstimatedRemainingTime();
            if (mTaskEtaDetailText != null) {
                if (eta > 0) {
                    mTaskEtaDetailText.setText(getString(R.string.task_eta_format, 
                        ReadableTime.getShortTimeInterval(eta)));
                    mTaskEtaDetailText.setVisibility(View.VISIBLE);
                } else {
                    mTaskEtaDetailText.setVisibility(View.GONE);
                }
            }
        } else {
            // 无确定进度
            if (mProgressPanel != null) {
                mProgressPanel.setVisibility(View.GONE);
            }
        }
        
        // 更新进度详情
        if (mTaskProgressDetailText != null) {
            String detail = taskInfo.getProgressDetail();
            if (detail != null && !detail.isEmpty()) {
                mTaskProgressDetailText.setText(detail);
                mTaskProgressDetailText.setVisibility(View.VISIBLE);
            } else {
                mTaskProgressDetailText.setVisibility(View.GONE);
            }
        }
        
        // 更新状态
        if (mTaskStatusText != null) {
            String status;
            if (taskInfo.isCancelled()) {
                status = getString(R.string.task_status_cancelled);
            } else if (taskInfo.isCompleted()) {
                if (taskInfo.getErrorMessage() != null) {
                    status = getString(R.string.task_status_failed);
                } else {
                    status = getString(R.string.task_status_completed);
                }
            } else if (taskInfo.isQueued()) {
                status = getString(R.string.task_status_pending);
            } else if (taskInfo.isPaused()) {
                status = getString(R.string.task_status_paused);
            } else {
                status = getString(R.string.task_status_running);
            }
            mTaskStatusText.setText(status);
        }

        // 更新控制按钮显隐
        updateControlButtons(taskInfo);
        
        // 更新运行时间
        if (mTaskTimeText != null) {
            long runningTime = taskInfo.getRunningTime();
            mTaskTimeText.setText(ReadableTime.getShortTimeInterval(runningTime));
        }
        
        // 更新ETA (详情面板中)
        if (mTaskEtaText != null) {
            long eta = taskInfo.getEstimatedRemainingTime();
            if (eta > 0) {
                mTaskEtaText.setText(getString(R.string.task_eta_format, 
                    ReadableTime.getShortTimeInterval(eta)));
                mTaskEtaText.setVisibility(View.VISIBLE);
            } else {
                mTaskEtaText.setVisibility(View.GONE);
            }
        }
        
        // 更新错误信息
        if (mTaskErrorText != null) {
            String errorMessage = taskInfo.getErrorMessage();
            if (errorMessage != null) {
                mTaskErrorText.setText(getString(R.string.task_error_format, errorMessage));
                mTaskErrorText.setVisibility(View.VISIBLE);
            } else {
                mTaskErrorText.setVisibility(View.GONE);
            }
        }

        // 更新日志
        if (mTaskLogText != null && mTaskLogPathText != null) {
            List<String> allLogs = mTaskManager.getTaskLogs(taskInfo.getTaskId());
            int totalCount = allLogs.size();
            if (totalCount != mLastLogTotalCount) {
                List<String> logs = totalCount <= MAX_DISPLAY_LOGS
                        ? allLogs
                        : allLogs.subList(totalCount - MAX_DISPLAY_LOGS, totalCount);
                if (logs.isEmpty()) {
                    mTaskLogText.setText(R.string.task_log_empty);
                } else {
                    StringBuilder builder = new StringBuilder();
                    for (String log : logs) {
                        builder.append(log).append('\n');
                    }
                    if (totalCount > MAX_DISPLAY_LOGS) {
                        builder.insert(0, getString(R.string.task_log_truncated, totalCount - MAX_DISPLAY_LOGS) + "\n\n");
                    }
                    mTaskLogText.setText(builder.toString());
                }
                mLastLogTotalCount = totalCount;

                // 自动滚动到底部
                if (mLogScroll != null) {
                    mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
                }
            }

            java.io.File logFile = taskInfo.getLogFile();
            if (logFile != null) {
                mTaskLogPathText.setText(getString(R.string.task_log_save_to, logFile.getAbsolutePath()));
            } else {
                mTaskLogPathText.setText(R.string.task_log_file_missing);
            }
        }
    }
    
    private void updateControlButtons(@NonNull BackgroundTaskInfo taskInfo) {
        if (mBtnPause == null || mBtnResume == null || mBtnStop == null || mBtnDelete == null) {
            return;
        }
        boolean isActive = !taskInfo.isCompleted() && !taskInfo.isCancelled();
        boolean isPaused = taskInfo.isPaused();
        boolean isQueued = taskInfo.isQueued();
        boolean isFinished = taskInfo.isCompleted() || taskInfo.isCancelled();

        if (isFinished) {
            mBtnPause.setVisibility(View.GONE);
            mBtnResume.setVisibility(View.GONE);
            mBtnStop.setVisibility(View.GONE);
            mBtnDelete.setVisibility(View.VISIBLE);
        } else if (isQueued) {
            // 排队中的互斥任务：只能停止（取消排队）
            mBtnPause.setVisibility(View.GONE);
            mBtnResume.setVisibility(View.GONE);
            mBtnStop.setVisibility(View.VISIBLE);
            mBtnDelete.setVisibility(View.GONE);
        } else if (isPaused) {
            mBtnPause.setVisibility(View.GONE);
            mBtnResume.setVisibility(View.VISIBLE);
            mBtnStop.setVisibility(View.VISIBLE);
            mBtnDelete.setVisibility(View.GONE);
        } else {
            // 运行中
            mBtnPause.setVisibility(taskInfo.isPausable() ? View.VISIBLE : View.GONE);
            mBtnResume.setVisibility(View.GONE);
            mBtnStop.setVisibility(View.VISIBLE);
            mBtnDelete.setVisibility(View.GONE);
        }
    }

    private void exportLogAsTxt() {
        if (mTaskManager == null || mTaskId == null) return;
        
        BackgroundTaskInfo taskInfo = mTaskManager.getTaskInfo(mTaskId);
        if (taskInfo == null) return;
        
        try {
            List<String> logs = taskInfo.getLogMessages();
            if (logs.isEmpty()) {
                Toast.makeText(this, R.string.task_log_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("Task: ").append(taskInfo.getTaskName()).append('\n');
            sb.append("Type: ").append(taskInfo.getTaskType().name()).append('\n');
            sb.append("Start: ").append(new java.util.Date(taskInfo.getStartTime())).append('\n');
            sb.append("Progress: ").append(taskInfo.getCurrentProgress())
              .append('/').append(taskInfo.getTotalProgress()).append('\n');
            sb.append("----------\n");
            for (String log : logs) {
                sb.append(log).append('\n');
            }
            
            File exportFile = new File(getCacheDir(), "task_log_" + mTaskId.substring(0, 8) + ".txt");
            try (FileWriter writer = new FileWriter(exportFile)) {
                writer.write(sb.toString());
            }
            
            shareFile(exportFile, "text/plain", getString(R.string.task_export_share_title));
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void exportLogAsJson() {
        if (mTaskManager == null || mTaskId == null) return;
        
        BackgroundTaskInfo taskInfo = mTaskManager.getTaskInfo(mTaskId);
        if (taskInfo == null) return;
        
        try {
            JSONObject json = new JSONObject();
            json.put("taskId", taskInfo.getTaskId());
            json.put("taskName", taskInfo.getTaskName());
            json.put("taskType", taskInfo.getTaskType().name());
            json.put("startTime", taskInfo.getStartTime());
            json.put("currentProgress", taskInfo.getCurrentProgress());
            json.put("totalProgress", taskInfo.getTotalProgress());
            json.put("progressPercentage", taskInfo.getProgressPercentage());
            json.put("isCompleted", taskInfo.isCompleted());
            json.put("isCancelled", taskInfo.isCancelled());
            json.put("isPaused", taskInfo.isPaused());
            
            String error = taskInfo.getErrorMessage();
            if (error != null) {
                json.put("errorMessage", error);
            }
            
            String detail = taskInfo.getProgressDetail();
            if (detail != null) {
                json.put("progressDetail", detail);
            }
            
            JSONArray logArray = new JSONArray();
            List<String> logs = taskInfo.getLogMessages();
            for (String log : logs) {
                logArray.put(log);
            }
            json.put("logs", logArray);
            
            File exportFile = new File(getCacheDir(), "task_log_" + mTaskId.substring(0, 8) + ".json");
            try (FileWriter writer = new FileWriter(exportFile)) {
                writer.write(json.toString(2));
            }
            
            shareFile(exportFile, "application/json", getString(R.string.task_export_share_title));
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void shareFile(@NonNull File file, @NonNull String mimeType, @NonNull String title) {
        try {
            String authority = getPackageName() + ".fileprovider";
            Uri uri = FileProvider.getUriForFile(this, authority, file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, title));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        mHandler.postDelayed(mRefreshRunnable, REFRESH_INTERVAL);
    }
    
    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mRefreshRunnable);
        super.onPause();
    }
    
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
