package com.hippo.ehviewer.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;
import androidx.preference.Preference;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.BackgroundTaskManager;

public class CleanRedundancyPreference3 extends Preference {

    public CleanRedundancyPreference3(Context context) {
        super(context);
        init();
    }

    public CleanRedundancyPreference3(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CleanRedundancyPreference3(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setKey("clean_redundancy");
        setTitle(R.string.settings_download_clean_redundancy);
        setSummary(R.string.settings_download_clean_redundancy_summary);
    }

    @Override
    protected void onClick() {
        Context context = getContext();
        com.hippo.ehviewer.task.CleanRedundancyTask task =
                new com.hippo.ehviewer.task.CleanRedundancyTask(context);
        BackgroundTaskManager.getInstance().submitBackgroundTask(task);
        Toast.makeText(context,
                R.string.settings_download_clean_redundancy_started,
                Toast.LENGTH_SHORT).show();
    }
}
