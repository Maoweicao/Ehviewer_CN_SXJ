package com.hippo.ehviewer.preference;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;

import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.lab.storage.StorageAnalysisActivity;

/**
 * 实验室 → 存储感知分析入口。
 */
public class StorageAnalysisPreference extends Preference {

    public StorageAnalysisPreference(Context context) {
        super(context);
        init();
    }

    public StorageAnalysisPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public StorageAnalysisPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setKey("lab_storage_analysis");
        setTitle(R.string.lab_storage_analysis);
        setSummary(R.string.lab_storage_analysis_summary);
    }

    @Override
    protected void onClick() {
        Context context = getContext();
        Intent intent = new Intent(context, StorageAnalysisActivity.class);
        context.startActivity(intent);
    }
}