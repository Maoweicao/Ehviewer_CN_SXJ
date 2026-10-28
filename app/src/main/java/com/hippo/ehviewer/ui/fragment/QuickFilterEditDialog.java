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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.filter.ExcludeRule;
import com.hippo.ehviewer.download.filter.FilterCondition;
import com.hippo.ehviewer.download.filter.FilterSettingsStore;
import com.hippo.ehviewer.download.filter.QuickFilterDefinition;
import com.hippo.ehviewer.download.filter.QuickFilterKind;
import com.hippo.ehviewer.widget.FilterConditionEditorView;
import com.hippo.ehviewer.widget.FilterExcludeView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 编辑单条快捷筛选。
 *
 * - 选“自定义条件”时展开 [FilterConditionEditorView]（复用高级筛选的全部条件）
 * - 选内置类型时展开对应的阈值参数
 * - 所有类型都可以附加自己的“额外排除”，与设置里的全局排除叠加
 */
public final class QuickFilterEditDialog {

    private QuickFilterEditDialog() {
    }

    /** 保存回调，把编辑后的定义交回给调用方。 */
    public interface OnSaved {
        void onSaved(@NonNull QuickFilterDefinition definition);
    }

    /** 删除回调。 */
    public interface OnDeleted {
        void onDeleted(@NonNull String definitionId);
    }

    /** 快速设置按钮的保存回调。 */
    public interface OnPanelPresetSaved {
        void onSaved(@NonNull com.hippo.ehviewer.download.filter.PanelPreset preset);
    }

    /**
     * 编辑已有快捷筛选；[definition] 为 null 表示新建。
     *
     * androidx.preference 的列表项没有长按手势，
     * 所以「上移 / 下移 / 删除」放在这个对话框底部。
     */
    public static void show(@NonNull Context context,
                            @Nullable QuickFilterDefinition definition,
                            @NonNull OnSaved onSaved) {
        show(context, definition, onSaved, null);
    }

    public static void show(@NonNull Context context,
                            @Nullable QuickFilterDefinition definition,
                            @NonNull OnSaved onSaved,
                            @Nullable OnDeleted onDeleted) {
        QuickFilterDefinition base = definition != null ? definition : defaultNewDefinition();
        QuickFilterDefinition[] working = {base};

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 16);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        EditText nameInput = new EditText(context);
        nameInput.setHint(R.string.filter_quick_filter_name_hint);
        nameInput.setSingleLine();
        nameInput.setText(base.getName());
        addSection(context, root, R.string.filter_quick_filter_name, nameInput);

        CheckBox enabledBox = new CheckBox(context);
        enabledBox.setText(R.string.filter_quick_filter_enabled);
        enabledBox.setChecked(base.getEnabled());
        root.addView(enabledBox);

        // 类型选择
        Spinner kindSpinner = new Spinner(context);
        List<QuickFilterKind> kinds = new ArrayList<>();
        List<String> kindLabels = new ArrayList<>();
        for (QuickFilterKind kind : QuickFilterKind.values()) {
            kinds.add(kind);
            kindLabels.add(context.getString(kindLabelRes(kind)));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, kindLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        kindSpinner.setAdapter(adapter);
        kindSpinner.setSelection(Math.max(0, kinds.indexOf(base.getKind())));
        addSection(context, root, R.string.filter_kind_summary, kindSpinner);

        // 参数区
        LinearLayout paramBox = new LinearLayout(context);
        paramBox.setOrientation(LinearLayout.VERTICAL);
        addSection(context, root, R.string.filter_quick_filter_section_params, paramBox);

        CheckBox followConfig = new CheckBox(context);
        followConfig.setText(R.string.filter_quick_filter_rating_follow_config);
        followConfig.setChecked(base.getRatingMax() <= 0f);
        paramBox.addView(followConfig);

        EditText ratingInput = numberInput(context);
        ratingInput.setText(base.getRatingMax() > 0f
                ? String.format(Locale.US, "%.1f", base.getRatingMax()) : "");
        paramBox.addView(labelled(context, R.string.filter_quick_filter_rating_max, ratingInput));

        EditText sizeInput = numberInput(context);
        sizeInput.setText(String.valueOf(base.getSizeMinMb()));
        paramBox.addView(labelled(context, R.string.filter_quick_filter_size_min, sizeInput));

        EditText perPageInput = numberInput(context);
        perPageInput.setText(String.valueOf(base.getPerPageMinKb()));
        paramBox.addView(labelled(context, R.string.filter_quick_filter_per_page_min, perPageInput));

        EditText agoInput = numberInput(context);
        agoInput.setText(String.valueOf(base.getAgoMonths()));
        paramBox.addView(labelled(context, R.string.filter_quick_filter_ago_months, agoInput));

        CheckBox requireFinished = new CheckBox(context);
        requireFinished.setText(R.string.filter_quick_filter_enabled);
        requireFinished.setChecked(base.getRequireFinished());
        paramBox.addView(requireFinished);

        // 自定义条件编辑器
        LinearLayout customBox = new LinearLayout(context);
        customBox.setOrientation(LinearLayout.VERTICAL);
        FilterConditionEditorView conditionEditor = new FilterConditionEditorView(context);
        conditionEditor.setCondition(base.getCondition());
        customBox.addView(new TextView(context) {
            {
                setText(R.string.filter_quick_filter_custom_hint);
                setTextSize(12f);
            }
        });
        customBox.addView(conditionEditor);
        addSection(context, root, R.string.filter_quick_filter_custom_condition, customBox);

        // 额外排除
        FilterExcludeView excludeView = new FilterExcludeView(context);
        excludeView.setRule(base.getExclude());
        addSection(context, root, R.string.filter_quick_filter_section_exclude, excludeView);

        Runnable syncVisibility = () -> {
            QuickFilterKind kind = kinds.get(Math.max(0, kindSpinner.getSelectedItemPosition()));
            boolean isCustom = kind == QuickFilterKind.CUSTOM;
            customBox.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            paramBox.setVisibility(isCustom ? View.GONE : View.VISIBLE);
            followConfig.setVisibility(
                    isCustom || (kind != QuickFilterKind.LOW_RATING && kind != QuickFilterKind.BIG_LOW_RATING)
                            ? View.GONE : View.VISIBLE);
            ratingInput.setVisibility(followConfig.isChecked() ? View.GONE : View.VISIBLE);
        };
        kindSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                syncVisibility.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        followConfig.setOnCheckedChangeListener((b, checked) -> ratingInput.setVisibility(checked ? View.GONE : View.VISIBLE));
        syncVisibility.run();

        // 列表管理操作（androidx.preference 没有长按，只能放在这里）
        boolean isExisting = definition != null && onDeleted != null;
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

            final int[] order = {orderOf(context, definition.getId())};
            up.setOnClickListener(v -> {
                if (order[0] > 0) {
                    onReorder(context, order[0], order[0] - 1);
                    order[0]--;
                }
            });
            down.setOnClickListener(v -> {
                List<QuickFilterDefinition> all =
                        FilterSettingsStore.getInstance(context).get().getQuickFilters();
                if (order[0] < all.size() - 1) {
                    onReorder(context, order[0], order[0] + 1);
                    order[0]++;
                }
            });
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(definition != null ? R.string.filter_quick_filter_edit : R.string.filter_quick_filter_add)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = context.getString(R.string.filter_quick_filter_add);
                    }
                    QuickFilterKind kind = kinds.get(Math.max(0, kindSpinner.getSelectedItemPosition()));
                    float ratingMax = followConfig.isChecked()
                            ? 0f : parseFloatOrZero(ratingInput.getText().toString());
                    working[0] = working[0].withEdits(
                            base.getId(), name, enabledBox.isChecked(), kind, ratingMax,
                            parseLongOr(sizeInput.getText().toString(), base.getSizeMinMb()),
                            parseLongOr(perPageInput.getText().toString(), base.getPerPageMinKb()),
                            (int) parseLongOr(agoInput.getText().toString(), base.getAgoMonths()),
                            requireFinished.isChecked(),
                            conditionEditor.getCondition(),
                            excludeView.getRule());
                    onSaved.onSaved(working[0]);
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (isExisting) {
            builder.setNeutralButton(R.string.filter_quick_filter_delete,
                    (dialog, which) -> onDeleted.onDeleted(definition.getId()));
        }
        builder.show();
    }

    private static int orderOf(Context context, String id) {
        List<QuickFilterDefinition> list =
                FilterSettingsStore.getInstance(context).get().getQuickFilters();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(id)) {
                return i;
            }
        }
        return 0;
    }

    /** 交给调用方实际落库（由管理器决定写哪个列表）。 */
    private static void onReorder(Context context, int from, int to) {
        FilterSettingsStore store = FilterSettingsStore.getInstance(context);
        store.moveQuickFilter(from, to);
        Toast.makeText(context, R.string.filter_quick_filter_manager_title, Toast.LENGTH_SHORT).show();
    }
    @NonNull
    private static QuickFilterDefinition defaultNewDefinition() {
        return new QuickFilterDefinition(QuickFilterDefinition.Companion.newId(), "",
                0, true, QuickFilterKind.CUSTOM, 0f, 100L, 4096L, 12, true,
                FilterCondition.Companion.defaultAllStatuses(), ExcludeRule.EMPTY);
    }

    private static int kindLabelRes(QuickFilterKind kind) {
        switch (kind) {
            case CUSTOM:
                return R.string.filter_kind_custom;
            case BIG_LOW_RATING:
                return R.string.filter_kind_big_low_rating;
            case DUPLICATES:
                return R.string.filter_kind_duplicates;
            case LOW_SPACE_EFFICIENCY:
                return R.string.filter_kind_low_space_efficiency;
            case LOW_RATING:
                return R.string.filter_kind_low_rating;
            case HUGE_FILE:
                return R.string.filter_kind_huge_file;
            case OLD_UNFAVORITED:
                return R.string.filter_kind_old_unfavorited;
            case AI_GENERATED:
                return R.string.filter_kind_ai_generated;
            default:
                return R.string.filter_kind_custom;
        }
    }

    private static View labelled(Context context, int labelRes, View input) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(context);
        label.setText(labelRes);
        label.setTextSize(13f);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);
        row.addView(input);
        return row;
    }

    private static void addSection(Context context, LinearLayout root, int titleRes, View content) {
        if (titleRes != 0) {
            TextView title = new TextView(context);
            title.setText(titleRes);
            title.setTextSize(14f);
            title.setPadding(0, dp(context, 12), 0, dp(context, 4));
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            root.addView(title);
        }
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static EditText numberInput(Context context) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setMaxLines(1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (int) (120 * context.getResources().getDisplayMetrics().density),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        input.setLayoutParams(lp);
        return input;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    private static float parseFloatOrZero(String text) {
        try {
            return text.trim().isEmpty() ? 0f : Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static long parseLongOr(String text, long fallback) {
        try {
            return text.trim().isEmpty() ? fallback : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
