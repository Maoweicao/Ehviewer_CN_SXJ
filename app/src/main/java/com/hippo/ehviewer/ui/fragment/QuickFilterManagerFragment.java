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

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.filter.Catalogs;
import com.hippo.ehviewer.download.filter.FilterCondition;
import com.hippo.ehviewer.download.filter.FilterSettingsStore;
import com.hippo.ehviewer.download.filter.QuickFilterDefinition;
import com.hippo.ehviewer.download.filter.QuickFilterKind;
import com.hippo.ehviewer.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「快捷筛选」管理面板（设置 - 下载 - 筛选与快捷筛选）。
 *
 * - 点击列表项：编辑这条快捷筛选（阈值 / 类型 / 完整条件 / 额外排除）
 * - 长按列表项：复制、删除、上移、下移、启用禁用
 * - 底部：新增一条、恢复内置快捷筛选
 *
 * 改动直接落到 [FilterSettingsStore]，下载列表的菜单会在回到列表时自动重建。
 */
public class QuickFilterManagerFragment extends BasePreferenceFragmentCompat {

    private static final String ARG_TITLE = "arg_title";

    @Nullable
    private PreferenceCategory mListCategory;

    public static QuickFilterManagerFragment newInstance(@StringRes int titleRes) {
        QuickFilterManagerFragment fragment = new QuickFilterManagerFragment();
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
            category.setTitle(R.string.filter_quick_filter_manager_title);
            category.setOrderingAsAdded(true);
            getPreferenceScreen().addPreference(category);
            mListCategory = category;
        }

        List<QuickFilterDefinition> definitions = new ArrayList<>(store().get().getQuickFilters());
        for (QuickFilterDefinition definition : definitions) {
            mListCategory.addPreference(buildItem(definition));
        }

        Preference add = new Preference(requireContext());
        add.setTitle(R.string.filter_quick_filter_add);
        add.setIconSpaceReserved(false);
        add.setOnPreferenceClickListener(preference -> {
            QuickFilterEditDialog.show(requireContext(), null, definition -> {
                store().addQuickFilter(definition);
                rebuild();
            });
            return true;
        });
        mListCategory.addPreference(add);

        Preference reset = new Preference(requireContext());
        reset.setTitle(R.string.filter_quick_filter_reset_builtin);
        reset.setIconSpaceReserved(false);
        reset.setOnPreferenceClickListener(preference -> {
            new AlertDialog.Builder(requireContext())
                    .setMessage(R.string.filter_quick_filter_reset_builtin)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        store().resetQuickFilters();
                        rebuild();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        mListCategory.addPreference(reset);
    }

    private Preference buildItem(QuickFilterDefinition definition) {
        Preference preference = new Preference(requireContext());
        preference.setTitle(definition.displayName(requireContext()));
        preference.setSummary(buildSummary(definition));
        preference.setOnPreferenceClickListener(p -> {
            QuickFilterEditDialog.show(requireContext(), definition, edited -> {
                store().updateQuickFilter(edited);
                rebuild();
            }, id -> {
                store().removeQuickFilter(id);
                rebuild();
            });
            return true;
        });
        return preference;
    }

    private CharSequence buildSummary(QuickFilterDefinition definition) {
        StringBuilder sb = new StringBuilder(kindLabel(definition.getKind()));
        QuickFilterKind kind = definition.getKind();
        if (kind == QuickFilterKind.LOW_RATING || kind == QuickFilterKind.BIG_LOW_RATING) {
            sb.append(" · ").append(definition.getRatingMax() > 0f
                    ? String.format(Locale.US, "%.1f", definition.getRatingMax())
                    : getString(R.string.filter_quick_filter_rating_follow_config));
        }
        if (kind == QuickFilterKind.BIG_LOW_RATING || kind == QuickFilterKind.HUGE_FILE) {
            sb.append(" · ≥").append(definition.getSizeMinMb()).append("MB");
        }
        if (kind == QuickFilterKind.LOW_SPACE_EFFICIENCY) {
            sb.append(" · ≥").append(definition.getPerPageMinKb() / 1024f).append("MB/页");
        }
        if (kind == QuickFilterKind.OLD_UNFAVORITED) {
            sb.append(" · ≥").append(definition.getAgoMonths()).append("个月前");
        }
        if (kind == QuickFilterKind.CUSTOM) {
            appendConditionSummary(sb, definition.getCondition());
        }
        if (!definition.getEnabled()) {
            sb.append(" · ").append(getString(R.string.filter_quick_filter_disabled_tag));
        }
        return sb.toString();
    }

    /** 把“自定义条件”的关键字段拼成一行摘要，方便在列表里区分。 */
    private void appendConditionSummary(StringBuilder sb, FilterCondition condition) {
        List<String> parts = new ArrayList<>();
        if (condition.getLowRatingOnly()) {
            parts.add(getString(R.string.filter_extra_low_rating_only));
        }
        if (condition.getDuplicateOnly()) {
            parts.add(getString(R.string.filter_duplicate_only));
        }
        if (condition.getUnfavoritedOnly()) {
            parts.add(getString(R.string.filter_extra_unfavorited));
        }
        if (condition.getAiGeneratedOnly()) {
            parts.add(getString(R.string.filter_extra_ai_generated));
        }
        if (condition.getTagsMissingOnly()) {
            parts.add(getString(R.string.filter_extra_tags_missing));
        }
        if (condition.getSizeFrom() != null) {
            parts.add("≥" + (condition.getSizeFrom() / (1024L * 1024)) + "MB");
        }
        if (condition.getPageFrom() != null) {
            parts.add("≥" + condition.getPageFrom() + "页");
        }
        if (condition.getPerPageFromBytes() != null) {
            parts.add("≥" + (condition.getPerPageFromBytes() / (1024L * 1024)) + "MB/页");
        }
        Set<Integer> categories = condition.getCategories();
        if (categories != null && categories.size() == 1) {
            for (int category : Catalogs.ORDERED) {
                if (categories.contains(category)) {
                    parts.add(getString(Catalogs.labelRes(category)));
                    break;
                }
            }
        }
        for (String part : parts) {
            sb.append(" · ").append(part);
        }
    }

    private String kindLabel(QuickFilterKind kind) {
        switch (kind) {
            case BIG_LOW_RATING:
                return getString(R.string.filter_kind_big_low_rating);
            case DUPLICATES:
                return getString(R.string.filter_kind_duplicates);
            case LOW_SPACE_EFFICIENCY:
                return getString(R.string.filter_kind_low_space_efficiency);
            case LOW_RATING:
                return getString(R.string.filter_kind_low_rating);
            case HUGE_FILE:
                return getString(R.string.filter_kind_huge_file);
            case OLD_UNFAVORITED:
                return getString(R.string.filter_kind_old_unfavorited);
            case AI_GENERATED:
                return getString(R.string.filter_kind_ai_generated);
            case CUSTOM:
            default:
                return getString(R.string.filter_kind_custom);
        }
    }
}
