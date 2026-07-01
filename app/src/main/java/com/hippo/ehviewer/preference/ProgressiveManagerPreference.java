package com.hippo.ehviewer.preference;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.lab.ProgressiveManagerActivity;

public class ProgressiveManagerPreference extends Preference {

    public ProgressiveManagerPreference(Context context) {
        super(context);
        init();
    }

    public ProgressiveManagerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ProgressiveManagerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setKey("lab_progressive_manager");
        setTitle(R.string.lab_progressive_manager);
        setSummary(R.string.lab_progressive_manager_summary);
    }

    @Override
    protected void onClick() {
        Context context = getContext();
        Intent intent = new Intent(context, ProgressiveManagerActivity.class);
        context.startActivity(intent);
    }
}
