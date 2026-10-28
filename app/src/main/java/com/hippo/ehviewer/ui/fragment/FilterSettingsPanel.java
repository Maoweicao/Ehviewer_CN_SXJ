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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RatingBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.filter.Catalogs;
import com.hippo.ehviewer.download.filter.ExcludeRule;
import com.hippo.ehviewer.download.filter.FilterSettingsStore;
import com.hippo.ehviewer.download.filter.LowRatingConfig;
import com.hippo.ehviewer.widget.FilterExcludeView;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 「低分范围」与「全局排除」的设置对话框。
 *
 * 两者都是纯配置编辑，直接读写 [FilterSettingsStore]，改完立即对所有筛选生效。
 */
public final class FilterSettingsPanel {

    private FilterSettingsPanel() {
    }

    @NonNull
    private static FilterSettingsStore store(@NonNull Context context) {
        return FilterSettingsStore.getInstance(context);
    }

    // ---------------- 默认低分范围 ----------------

    /** 只有一个输入框：全局默认阈值。 */
    public static void showLowRatingDialog(@NonNull Context context) {
        LowRatingConfig current = store(context).lowRatingConfig();
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.1f", current.getGlobalThreshold()));
        input.setHint(context.getString(R.string.settings_download_filter_low_rating_summary));
        input.setSelection(input.getText().length());
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding / 2, padding, 0);
        TextView hint = new TextView(context);
        hint.setText(R.string.filter_low_rating_hint);
        hint.setTextSize(12f);
        container.addView(hint);
        container.addView(input);

        new AlertDialog.Builder(context)
                .setTitle(R.string.filter_low_rating_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Float value = parseRating(input.getText().toString());
                    if (value == null) {
                        Toast.makeText(context, R.string.filter_low_rating_hint, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    store(context).setLowRatingConfig(current.withGlobalThreshold(value));
                    Toast.makeText(context, R.string.filter_low_rating_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 按 catalog 的低分范围。
     *
     * 每个分类一行：名称 + 0~5 星（0.1 步长）的 RatingBar + “跟随默认”按钮。
     */
    public static void showLowRatingPerCategoryDialog(@NonNull Context context) {
        LowRatingConfig current = store(context).lowRatingConfig();
        LowRatingConfig[] working = {current};

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (12 * context.getResources().getDisplayMetrics().density);
        list.setPadding(padding, padding, padding, padding);
        scroll.addView(list);

        Map<Integer, RatingBar> bars = new LinkedHashMap<>();
        Map<Integer, TextView> labels = new LinkedHashMap<>();
        Map<Integer, Button> resets = new LinkedHashMap<>();

        for (int category : Catalogs.ORDERED) {
            View row = buildCategoryRow(context, category, working[0], bars, labels, resets);
            list.addView(row);
        }

        TextView hint = new TextView(context);
        hint.setText(R.string.filter_low_rating_per_category_hint);
        hint.setTextSize(12f);
        hint.setPadding(padding, padding, padding, padding);
        list.addView(hint);

        new AlertDialog.Builder(context)
                .setTitle(R.string.filter_low_rating_per_category_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    LowRatingConfig updated = working[0];
                    for (Map.Entry<Integer, RatingBar> entry : bars.entrySet()) {
                        int category = entry.getKey();
                        float value = entry.getValue().getRating();
                        updated = value <= 0f
                                ? updated.setThreshold(category, null)
                                : updated.setThreshold(category, LowRatingConfig.clamp(value));
                    }
                    store(context).setLowRatingConfig(updated);
                    Toast.makeText(context, R.string.filter_low_rating_saved, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.filter_low_rating_reset, (dialog, which) -> {
                    store(context).setLowRatingConfig(new LowRatingConfig(
                            LowRatingConfig.DEFAULT_GLOBAL_THRESHOLD, new LinkedHashMap<>()));
                    Toast.makeText(context, R.string.filter_low_rating_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static View buildCategoryRow(@NonNull Context context,
                                         int category,
                                         @NonNull LowRatingConfig config,
                                         @NonNull Map<Integer, RatingBar> bars,
                                         @NonNull Map<Integer, TextView> labels,
                                         @NonNull Map<Integer, Button> resets) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 8, 0, 8);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(context);
        name.setText(context.getString(Catalogs.labelRes(category)));
        name.setTextSize(15f);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(name);

        TextView value = new TextView(context);
        value.setTextSize(13f);
        header.addView(value);

        Button reset = new Button(context, null, android.R.attr.borderlessButtonStyle);
        reset.setText(R.string.filter_low_rating_follow_default);
        reset.setTextSize(12f);
        header.addView(reset);

        row.addView(header);

        RatingBar bar = new RatingBar(context, null, android.R.attr.ratingBarStyleSmall);
        bar.setNumStars(5);
        bar.setStepSize(0.1f);
        bar.setIsIndicator(false);
        Float overridden = config.getPerCategory().get(category);
        bar.setRating(overridden != null ? overridden : 0f);

        Runnable refresh = () -> {
            float rating = bar.getRating();
            if (rating <= 0f) {
                value.setText(context.getString(R.string.filter_low_rating_follow_default));
                reset.setVisibility(View.VISIBLE);
            } else {
                value.setText(String.format(Locale.US, "%.1f", rating));
                reset.setVisibility(View.GONE);
            }
        };
        bar.setOnRatingBarChangeListener((ratingBar, rating, fromUser) -> refresh.run());
        reset.setOnClickListener(v -> {
            bar.setRating(0f);
            refresh.run();
        });
        refresh.run();

        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(bar);

        bars.put(category, bar);
        labels.put(category, value);
        resets.put(category, reset);
        return row;
    }

    // ---------------- 全局排除 ----------------

    /** 对所有筛选入口生效的排除规则（分类 / 关键词 / Tag）。 */
    public static void showGlobalExcludeDialog(@NonNull Context context) {
        FilterSettingsStore store = store(context);
        ExcludeRule current = store.globalExclude();
        FilterExcludeView excludeView = new FilterExcludeView(context);
        excludeView.setRule(current);
        if (current.isEmpty()) {
            // 还没有任何排除项时把面板展开，避免看起来“什么都没有”
            excludeView.setEnabledRule(true);
        }

        ScrollView scroll = new ScrollView(context);
        int padding = (int) (12 * context.getResources().getDisplayMetrics().density);
        scroll.setPadding(padding, padding, padding, padding);
        scroll.addView(excludeView);

        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_download_filter_global_exclusions)
                .setMessage(R.string.filter_exclude_global_hint)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        store.setGlobalExclude(excludeView.getRule()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Nullable
    private static Float parseRating(@Nullable String text) {
        if (text == null) {
            return null;
        }
        try {
            float value = Float.parseFloat(text.trim());
            if (Float.isNaN(value)) {
                return null;
            }
            return LowRatingConfig.clamp(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
