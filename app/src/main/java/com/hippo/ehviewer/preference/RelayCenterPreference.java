package com.hippo.ehviewer.preference;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;

import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.lab.MultiDeviceRelayActivity;

public class RelayCenterPreference extends Preference {

    public RelayCenterPreference(Context context) {
        super(context);
        init();
    }

    public RelayCenterPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RelayCenterPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setKey("lab_relay_center");
        setTitle(R.string.lab_relay_center);
        setSummary(R.string.lab_relay_center_summary);
    }

    @Override
    protected void onClick() {
        Context context = getContext();
        Intent intent = new Intent(context, MultiDeviceRelayActivity.class);
        context.startActivity(intent);
    }
}
