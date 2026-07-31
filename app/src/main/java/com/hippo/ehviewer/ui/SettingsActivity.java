/*
 * Copyright 2016 Hippo Seven
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

package com.hippo.ehviewer.ui;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.fragment.SearchResultsFragment;
import com.hippo.ehviewer.ui.fragment.SettingsHeaders;
import com.hippo.util.DrawableManager;

public final class SettingsActivity extends EhActivity {

    private static final int REQUEST_CODE_FRAGMENT = 0;

    private TextInputLayout mSearchLayout;
    private TextInputEditText mSearchInput;

    @Override
    protected int getThemeResId(int theme) {
        switch (theme) {
            case Settings.THEME_LIGHT:
            default:
                return R.style.AppTheme_Settings;
            case Settings.THEME_DARK:
                return R.style.AppTheme_Settings_Dark;
            case Settings.THEME_BLACK:
                return R.style.AppTheme_Settings_Black;
        }
    }

    private void setActionBarUpIndicator(Drawable drawable) {
        ActionBarDrawerToggle.Delegate delegate = getDrawerToggleDelegate();
        if (delegate != null) {
            delegate.setActionBarUpIndicator(drawable, 0);
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setTitle(R.string.settings);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setActionBarUpIndicator(DrawableManager.getVectorDrawable(this, R.drawable.v_arrow_left_dark_x24));

        mSearchLayout = findViewById(R.id.search_input_layout);
        mSearchInput = findViewById(R.id.search_input);
        if (mSearchInput != null) {
            mSearchInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    handleSearchQuery(s.toString());
                }
            });
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsHeaders())
                    .commit();
        }
    }

    private void handleSearchQuery(@NonNull String query) {
        String trimmed = query.trim();
        if (mSearchLayout != null) {
            mSearchLayout.setHint(trimmed.isEmpty()
                    ? getString(R.string.settings_search_hint)
                    : getString(R.string.settings_search_hint));
        }
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.settings);
        if (trimmed.isEmpty()) {
            if (current instanceof SearchResultsFragment) {
                getSupportFragmentManager().popBackStack();
            }
            return;
        }
        if (current instanceof SearchResultsFragment) {
            ((SearchResultsFragment) current).render(trimmed);
        } else {
            SearchResultsFragment fragment = SearchResultsFragment.newInstance(trimmed);
            FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
            tx.replace(R.id.settings, fragment);
            tx.addToBackStack(SearchResultsFragment.class.getSimpleName());
            tx.commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                getOnBackPressedDispatcher().onBackPressed();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_FRAGMENT) {
            if (resultCode == RESULT_OK) {
                setResult(RESULT_OK);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    public void setSettingsTitle(int res) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(res);
            return;
        }
        setTitle(res);
    }

    public void setSettingsTitle(CharSequence res) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(res);
            return;
        }
        setTitle(res);
    }

    public void clearSearch() {
        if (mSearchInput != null && mSearchInput.getText() != null
                && mSearchInput.getText().length() > 0) {
            mSearchInput.setText("");
        }
    }

    @Override
    public void onBackPressed() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.settings);
        if (current instanceof SearchResultsFragment) {
            clearSearch();
        }
        super.onBackPressed();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.settings);
        }
    }
}