/*
 * Copyright 2025 EhViewer Contributors
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
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.filter.FilterSettingsStore;
import com.hippo.ehviewer.download.filter.PanelPreset;
import com.hippo.ehviewer.ui.SettingsActivity;
import com.hippo.ehviewer.widget.FilterConditionEditorView;

import java.util.ArrayList;
import java.util.List;

/**
 * 「快速设置」按钮的管理面板。
 *
 * 这些按钮显示在下载列表的筛选面板上，点击后把条件套用到面板（不直接执行）。
 * 每条按钮保存的就是一份完整的 [com.hippo.ehviewer.download.filter.FilterCondition]，
 * 编辑时复用 [FilterConditionEditorView] —— 也就是高级筛选的那套条件配置。
 */
public class PanelPresetManagerFragment extends BasePreferenceFragmentCompat {

    private static final String ARG_TITLE = "arg_title";

    @Nullable
    private PreferenceCategory mListCategory;

    public static PanelPresetManagerFragment newInstance(@StringRes int titleRes) {
        PanelPresetManagerFragment fragment = new PanelPresetManagerFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_TITLE, titleRes);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    private FilterSettingsStore store() {
        return FilterSettingsStore.getInstance(requireContext());
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.settings_download_filter_manager);
        rebuild();
    }

    @Override
    public void onResume() {
        super.onResume();
        Bundle args = getArguments();
        if (args != null && getActivity() instanceof SettingsActivity) {
            ((SettingsActivity) getActivity()).setSettingsTitle(args.getInt(ARG_TITLE));
        }
        rebuild();
    }

    private void rebuild() {
        if (mListCategory != null) {
            mListCategory.removeAll();
        } else {
            PreferenceCategory category = new PreferenceCategory(requireContext());
            category.setTitle(R.string.settings_download_quick_preset_manager);
            category.setOrderingAsAdded(true);
            getPreferenceScreen().addPreference(category);
            mListCategory = category;
        }

        List<PanelPreset> presets = new ArrayList<>(store().get().getPanelPresets());
        for (PanelPreset preset : presets) {
            mListCategory.addPreference(buildItem(preset));
        }

        Preference add = new Preference(requireContext());
        add.setTitle(R.string.filter_quick_filter_preset_add);
        add.setIconSpaceReserved(false);
        add.setOnPreferenceClickListener(preference -> {
            editPreset(requireContext(), null, preset -> {
                store().addPanelPreset(preset);
                rebuild();
            }, null);
            return true;
        });
        mListCategory.addPreference(add);

        Preference reset = new Preference(requireContext());
        reset.setTitle(R.string.filter_quick_filter_preset_reset);
        reset.setIconSpaceReserved(false);
        reset.setOnPreferenceClickListener(preference -> {
            new AlertDialog.Builder(requireContext())
                    .setMessage(R.string.filter_quick_filter_preset_reset)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        store().resetPanelPresets();
                        rebuild();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        mListCategory.addPreference(reset);
    }

    private Preference buildItem(PanelPreset preset) {
        Preference preference = new Preference(requireContext());
        preference.setTitle(preset.displayName(requireContext()));
        preference.setSummary(getString(R.string.filter_section_quick_presets_hint)
                + (preset.getEnabled() ? "" : " · " + getString(R.string.filter_quick_filter_disabled_tag)));
        preference.setOnPreferenceClickListener(p -> {
            editPreset(requireContext(), preset, edited -> {
                store().updatePanelPreset(edited);
                rebuild();
            }, id -> {
                store().removePanelPreset(id);
                rebuild();
            });
            return true;
        });
        return preference;
    }

    /**
     * 编辑一条快速设置按钮：名称 + 完整筛选条件（复用高级筛选配置）。
     *
     * androidx.preference 的列表项没有长按手势，因此上移 / 下移 / 删除
     * 也放在这个对话框里。
     */
    static void editPreset(@NonNull Context context,
                           @Nullable PanelPreset preset,
                           @NonNull QuickFilterEditDialog.OnPanelPresetSaved onSaved,
                           @Nullable QuickFilterEditDialog.OnDeleted onDeleted) {
        EditText nameInput = new EditText(context);
        nameInput.setHint(R.string.filter_quick_filter_name_hint);
        nameInput.setSingleLine();
        nameInput.setText(preset != null ? preset.getName() : "");

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        root.addView(nameInput);

        FilterConditionEditorView editor = new FilterConditionEditorView(context);
        editor.setCondition(preset != null ? preset.getCondition() : null);
        root.addView(editor);

        boolean isExisting = preset != null && onDeleted != null;
        final int[] order = {isExisting ? orderOf(context, preset.getId()) : 0};
        if (isExisting) {
            LinearLayout manageRow = new LinearLayout(context);
            manageRow.setOrientation(LinearLayout.HORIZONTAL);
            Button up = new Button(context, null, android.R.attr.borderlessButtonStyle);
            up.setText(R.string.filter_quick_filter_move_up);
            Button down = new Button(context, null, android.R.attr.borderlessButtonStyle);
            down.setText(R.string.filter_quick_filter_move_down);
            manageRow.addView(up);
            manageRow.addView(down);
            root.addView(manageRow);

            up.setOnClickListener(v -> {
                if (order[0] > 0) {
                    FilterSettingsStore.getInstance(context).movePanelPreset(order[0], order[0] - 1);
                    order[0]--;
                }
            });
            down.setOnClickListener(v -> {
                List<PanelPreset> all = FilterSettingsStore.getInstance(context).get().getPanelPresets();
                if (order[0] < all.size() - 1) {
                    FilterSettingsStore.getInstance(context).movePanelPreset(order[0], order[0] + 1);
                    order[0]++;
                }
            });
        }
        scroll.addView(root);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(preset != null
                        ? R.string.filter_quick_filter_preset_edit
                        : R.string.filter_quick_filter_preset_add)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = context.getString(R.string.filter_quick_filter_preset_add);
                    }
                    PanelPreset result = preset != null
                            ? preset.withEdits(preset.getId(), name, true, editor.getCondition())
                            : new PanelPreset(PanelPreset.Companion.newId(), name, 0, true,
                            editor.getCondition());
                    onSaved.onSaved(result);
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (isExisting) {
            builder.setNeutralButton(R.string.filter_quick_filter_delete,
                    (dialog, which) -> onDeleted.onDeleted(preset.getId()));
        }
        builder.show();
    }

    private static int orderOf(Context context, String id) {
        List<PanelPreset> list = FilterSettingsStore.getInstance(context).get().getPanelPresets();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(id)) {
                return i;
            }
        }
        return 0;
    }
}
