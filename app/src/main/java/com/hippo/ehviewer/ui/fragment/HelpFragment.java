package com.hippo.ehviewer.ui.fragment;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.MarkdownViewerActivity;

import java.util.Locale;

public class HelpFragment extends BasePreferenceFragmentCompat
        implements Preference.OnPreferenceClickListener {

    private static final String KEY_SEARCH_HELP = "search_help";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.help_settings);

        Preference searchHelp = findPreference(KEY_SEARCH_HELP);
        if (searchHelp != null) {
            searchHelp.setOnPreferenceClickListener(this);
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (KEY_SEARCH_HELP.equals(preference.getKey())) {
            String lang = Locale.getDefault().getLanguage();
            String fileName = "help/" + lang + "/search_help.md";
            Intent intent = new Intent(getActivity(), MarkdownViewerActivity.class);
            intent.putExtra("markdown_file", fileName);
            intent.putExtra("markdown_title", getString(R.string.search_help_title));
            startActivity(intent);
            return true;
        }
        return false;
    }
}
