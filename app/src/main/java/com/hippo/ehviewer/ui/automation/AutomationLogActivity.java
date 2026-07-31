package com.hippo.ehviewer.ui.automation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.task.automation.AutomationLogger;
import com.hippo.ehviewer.ui.EhActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 自动化任务执行日志查看界面 */
public class AutomationLogActivity extends EhActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, AutomationLogActivity.class));
    }

    private AutomationLogger logger;

    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

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
        setContentView(R.layout.activity_automation_log);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.scheduled_task_log_title);
        }

        logger = new AutomationLogger(this);

        RecyclerView list = findViewById(R.id.log_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new LogAdapter(logger.getAllLogs()));
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {

        private final List<AutomationLogger.LogEntry> entries;

        LogAdapter(List<AutomationLogger.LogEntry> entries) {
            this.entries = entries;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_automation_log, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(entries.get(position));
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView textTime;
            final TextView textLevel;
            final TextView textMessage;
            final TextView textDetails;

            VH(@NonNull View itemView) {
                super(itemView);
                textTime = itemView.findViewById(R.id.text_time);
                textLevel = itemView.findViewById(R.id.text_level);
                textMessage = itemView.findViewById(R.id.text_message);
                textDetails = itemView.findViewById(R.id.text_details);
            }

            void bind(AutomationLogger.LogEntry e) {
                textTime.setText(fmt.format(new Date(e.getTimestamp())));
                textLevel.setText(e.getLevel().name());
                textMessage.setText(e.getMessage());
                textDetails.setText(e.getDetails() != null ? e.getDetails() : "");
                textDetails.setVisibility(e.getDetails() != null ? View.VISIBLE : View.GONE);
            }
        }
    }
}