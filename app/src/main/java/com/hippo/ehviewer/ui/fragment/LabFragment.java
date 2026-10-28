package com.hippo.ehviewer.ui.fragment;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

public class LabFragment extends BasePreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.lab_settings);

        // 自定义 DialogPreference 的 onSetInitialValue 不会应用 XML 默认值，
        // 首次进入时显式落一次默认方向（双向）。
        Preference direction = findPreference("lab_sync_direction");
        if (direction instanceof com.hippo.preference.ListPreference) {
            com.hippo.preference.ListPreference listPreference =
                    (com.hippo.preference.ListPreference) direction;
            if (listPreference.getValue() == null) {
                listPreference.setValue(Settings.getLabSyncDirection());
            }
        }
    }
}
