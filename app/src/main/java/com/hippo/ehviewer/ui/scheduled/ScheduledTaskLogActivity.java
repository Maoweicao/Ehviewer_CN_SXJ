package com.hippo.ehviewer.ui.scheduled;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.EhActivity;
import com.hippo.ehviewer.task.scheduled.ScheduledTaskLogger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 定时任务执行日志界面
 */
public class ScheduledTaskLogActivity extends EhActivity {

    private ScheduledTaskLogger logger;
    private LogAdapter adapter;
    private RecyclerView logList;
    private TextView emptyText;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public static void start(Context context) {
        Intent intent = new Intent(context, ScheduledTaskLogActivity.class);
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
        setContentView(R.layout.activity_scheduled_task_log);

        logger = new ScheduledTaskLogger(this);

        initToolbar();
        initViews();
        loadLogs();
    }

    private void initToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void initViews() {
        logList = findViewById(R.id.log_list);
        emptyText = findViewById(R.id.empty_text);

        adapter = new LogAdapter();
        logList.setLayoutManager(new LinearLayoutManager(this));
        logList.setAdapter(adapter);
    }

    private void loadLogs() {
        List<ScheduledTaskLogger.LogEntry> logs = logger.getAllLogs();
        adapter.setLogs(logs);

        emptyText.setVisibility(logs.isEmpty() ? View.VISIBLE : View.GONE);
        logList.setVisibility(logs.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_scheduled_task_log, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_clear_logs) {
            showClearConfirmDialog();
            return true;
        } else if (id == R.id.action_export_logs) {
            exportLogs();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showClearConfirmDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.scheduled_task_log_clear)
                .setMessage(R.string.scheduled_task_log_clear_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    logger.clearLogs();
                    loadLogs();
                    Toast.makeText(this, R.string.scheduled_task_log_clear, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void exportLogs() {
        // 创建导出意图
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "scheduled_task_logs_" + System.currentTimeMillis() + ".json");
        startActivityForResult(intent, REQUEST_CODE_EXPORT);
    }

    private static final int REQUEST_CODE_EXPORT = 1001;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_EXPORT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                logger.exportLogs(uri);
                Toast.makeText(this, R.string.scheduled_task_log_export, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 日志适配器
     */
    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {

        private final List<ScheduledTaskLogger.LogEntry> logs = new ArrayList<>();

        public void setLogs(List<ScheduledTaskLogger.LogEntry> logs) {
            this.logs.clear();
            this.logs.addAll(logs);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(ScheduledTaskLogActivity.this)
                    .inflate(R.layout.item_scheduled_task_log, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ScheduledTaskLogger.LogEntry entry = logs.get(position);
            holder.bind(entry);
        }

        @Override
        public int getItemCount() {
            return logs.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final View levelIndicator;
            private final TextView taskName;
            private final TextView timestamp;
            private final TextView message;
            private final TextView details;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                levelIndicator = itemView.findViewById(R.id.level_indicator);
                taskName = itemView.findViewById(R.id.task_name);
                timestamp = itemView.findViewById(R.id.timestamp);
                message = itemView.findViewById(R.id.message);
                details = itemView.findViewById(R.id.details);
            }

            void bind(ScheduledTaskLogger.LogEntry entry) {
                taskName.setText(entry.getTaskName());
                timestamp.setText(dateFormat.format(new Date(entry.getTimestamp())));
                message.setText(entry.getMessage());

                // 设置日志级别颜色
                int color;
                switch (entry.getLevel()) {
                    case INFO:
                        color = Color.parseColor("#4CAF50"); // 绿色
                        break;
                    case WARN:
                        color = Color.parseColor("#FF9800"); // 橙色
                        break;
                    case ERROR:
                        color = Color.parseColor("#F44336"); // 红色
                        break;
                    case DEBUG:
                    default:
                        color = Color.parseColor("#2196F3"); // 蓝色
                        break;
                }
                levelIndicator.setBackgroundColor(color);

                // 设置详情
                String detailText = entry.getDetails();
                if (detailText != null && !detailText.isEmpty()) {
                    details.setVisibility(View.VISIBLE);
                    details.setText(detailText);
                } else {
                    details.setVisibility(View.GONE);
                }
            }
        }
    }
}
