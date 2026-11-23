package com.hippo.ehviewer.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.task.RebuildDownloadRecordsTask;

/**
 * 「重建下载记录」入口。
 *
 * <p>只做本地重建：下载历史（仅最终状态）+ 磁盘目录里的
 * `.ehviewer` / `.ehviewer.extra.json` → 下载列表，不联网、不自动下载。
 */
public class RebuildDownloadRecordsPreference extends Preference {

    public RebuildDownloadRecordsPreference(Context context) {
        super(context);
        init();
    }

    public RebuildDownloadRecordsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RebuildDownloadRecordsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setKey("rebuild_download_records");
        setTitle(R.string.settings_download_rebuild_download_records);
        setSummary(R.string.settings_download_rebuild_download_records_summary);
    }

    @Override
    protected void onClick() {
        final Context context = getContext();

        BackgroundTaskManager taskManager = BackgroundTaskManager.getInstance();
        if (taskManager.getTaskStatusManager().getActiveUniqueNonDownloadTask() != null) {
            Toast.makeText(context, R.string.background_task_unique_running, Toast.LENGTH_SHORT).show();
            return;
        }

        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                context.getResources().getDisplayMetrics());
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding / 2, padding, 0);
        final CheckBox queueMissing = new CheckBox(context);
        queueMissing.setText(R.string.settings_download_rebuild_queue_missing);
        container.addView(queueMissing);

        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_download_rebuild_download_records)
                .setMessage(R.string.settings_download_rebuild_confirm_message)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_download_rebuild_action, (dialog, which) -> {
                    RebuildDownloadRecordsTask task =
                            new RebuildDownloadRecordsTask(context, queueMissing.isChecked());
                    taskManager.submitBackgroundTask(task);
                    Toast.makeText(context,
                            R.string.settings_download_rebuild_download_records_started,
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
