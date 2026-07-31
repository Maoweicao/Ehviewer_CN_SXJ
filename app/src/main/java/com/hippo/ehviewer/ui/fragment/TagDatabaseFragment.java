/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhTagDatabase;

import java.io.File;
import java.util.Date;
import java.util.List;

public class TagDatabaseFragment extends BasePreferenceFragmentCompat
        implements Preference.OnPreferenceClickListener {

    private static final String KEY_STATUS = "tag_db_status";
    private static final String KEY_DATA_FILE = "tag_db_data_file";
    private static final String KEY_DATA_FILE_MODIFIED = "tag_db_data_file_modified";
    private static final String KEY_SHA1_EXPECTED = "tag_db_sha1_expected";
    private static final String KEY_SHA1_COMPUTED = "tag_db_sha1_computed";
    private static final String KEY_INTEGRITY = "tag_db_integrity";
    private static final String KEY_ACTION_CHECK = "tag_db_action_check";
    private static final String KEY_ACTION_REDOWNLOAD = "tag_db_action_redownload";
    private static final String KEY_ACTION_CLEAR = "tag_db_action_clear";

    private Preference mStatus;
    private Preference mDataFile;
    private Preference mDataFileModified;
    private Preference mSha1Expected;
    private Preference mSha1Computed;
    private Preference mIntegrity;
    private Preference mActionCheck;
    private Preference mActionRedownload;
    private Preference mActionClear;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.tag_database_settings);

        mStatus = findPreference(KEY_STATUS);
        mDataFile = findPreference(KEY_DATA_FILE);
        mDataFileModified = findPreference(KEY_DATA_FILE_MODIFIED);
        mSha1Expected = findPreference(KEY_SHA1_EXPECTED);
        mSha1Computed = findPreference(KEY_SHA1_COMPUTED);
        mIntegrity = findPreference(KEY_INTEGRITY);
        mActionCheck = findPreference(KEY_ACTION_CHECK);
        mActionRedownload = findPreference(KEY_ACTION_REDOWNLOAD);
        mActionClear = findPreference(KEY_ACTION_CLEAR);

        if (mActionCheck != null) mActionCheck.setOnPreferenceClickListener(this);
        if (mActionRedownload != null) mActionRedownload.setOnPreferenceClickListener(this);
        if (mActionClear != null) mActionClear.setOnPreferenceClickListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        Context ctx = requireContext();
        String key = preference.getKey();
        if (KEY_ACTION_CHECK.equals(key)) {
            boolean ok = EhTagDatabase.verifyIntegrity(ctx);
            if (ok) {
                Toast.makeText(ctx, R.string.tag_database_action_check_done_ok, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(ctx, R.string.tag_database_action_check_done_mismatch, Toast.LENGTH_SHORT).show();
            }
            refresh();
            return true;
        } else if (KEY_ACTION_REDOWNLOAD.equals(key)) {
            EhTagDatabase.clearLocalCache(ctx);
            EhTagDatabase.update(ctx);
            Toast.makeText(ctx, R.string.tag_database_action_redownload_started, Toast.LENGTH_SHORT).show();
            refresh();
            return true;
        } else if (KEY_ACTION_CLEAR.equals(key)) {
            EhTagDatabase.clearLocalCache(ctx);
            Toast.makeText(ctx, R.string.tag_database_action_clear_done, Toast.LENGTH_SHORT).show();
            refresh();
            return true;
        }
        return false;
    }

    /**
     * Re-reads every piece of state from disk / memory and rewrites the
     * preference summaries so they reflect the current state.
     */
    private void refresh() {
        Context ctx = getContext();
        if (ctx == null) return;

        // ---- Status ------------------------------------------------------
        if (mStatus != null) {
            String summary;
            if (!EhTagDatabase.isPossible(ctx)) {
                summary = getString(R.string.tag_database_status_no_metadata);
            } else if (EhTagDatabase.isLoaded()) {
                int count = countTags(EhTagDatabase.getInstance(ctx));
                summary = getString(R.string.tag_database_status_loaded, count);
            } else {
                summary = getString(R.string.tag_database_status_not_loaded);
            }
            mStatus.setSummary(summary);
        }

        // ---- Local data file ---------------------------------------------
        File dataFile = EhTagDatabase.getLocalDataFile(ctx);
        boolean exists = dataFile != null && dataFile.exists();
        if (mDataFile != null) {
            if (exists) {
                String sizeText = Formatter.formatShortFileSize(ctx, dataFile.length());
                mDataFile.setSummary(getString(R.string.tag_database_data_file_size, sizeText));
            } else {
                mDataFile.setSummary(R.string.tag_database_data_file_missing);
            }
        }
        if (mDataFileModified != null) {
            if (exists) {
                CharSequence when = DateFormat.getDateFormat(ctx).format(new Date(dataFile.lastModified()));
                mDataFileModified.setSummary(getString(R.string.tag_database_data_file_modified, when.toString()));
                mDataFileModified.setVisible(true);
            } else {
                mDataFileModified.setVisible(false);
            }
        }

        // ---- SHA-1 (expected / computed) --------------------------------
        String expected = EhTagDatabase.readLocalSha1Hex(ctx);
        if (mSha1Expected != null) {
            if (expected != null) {
                mSha1Expected.setSummary(getString(R.string.tag_database_sha1_expected_format, expected));
            } else {
                mSha1Expected.setSummary(R.string.tag_database_sha1_expected_missing);
            }
        }
        String computed = EhTagDatabase.computeLocalDataSha1Hex(ctx);
        if (mSha1Computed != null) {
            if (computed != null) {
                mSha1Computed.setSummary(getString(R.string.tag_database_sha1_expected_format, computed));
            } else {
                mSha1Computed.setSummary(R.string.tag_database_sha1_computed_missing);
            }
        }

        // ---- Integrity --------------------------------------------------
        if (mIntegrity != null) {
            int res;
            if (!exists || expected == null) {
                res = R.string.tag_database_integrity_incomplete;
            } else if (EhTagDatabase.verifyIntegrity(ctx)) {
                res = R.string.tag_database_integrity_ok;
            } else {
                res = R.string.tag_database_integrity_mismatch;
            }
            mIntegrity.setSummary(res);
        }
    }

    private static int countTags(@Nullable EhTagDatabase db) {
        if (db == null) return 0;
        try {
            List<?> tags = db.getTagList();
            return tags == null ? 0 : tags.size();
        } catch (Throwable t) {
            return 0;
        }
    }
}
