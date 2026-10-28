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

package com.hippo.ehviewer.widget;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RatingBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.filter.FilterCondition;
import com.hippo.ehviewer.download.filter.SortKey;
import com.hippo.ehviewer.ui.scene.download.part.DownloadCategoryTable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「筛选条件」编辑器 —— 高级筛选配置的可视化编辑器。
 *
 * 覆盖 [FilterCondition] 的全部字段：分类、状态、排序、下载时间、体积、页数、
 * 评分区间、重复内容，以及新增的低分 / 发布时间 / 未收藏 / AI 生成 / 无标签 /
 * 每页体积 与排除规则。
 *
 * 用途：
 * <ul>
 *   <li>快捷筛选里「自定义条件」类型的编辑</li>
 *   <li>筛选面板「快速设置」按钮的编辑</li>
 * </ul>
 */
public class FilterConditionEditorView extends LinearLayout {

    private DownloadCategoryTable mCategoryTable;
    private final Map<Integer, CheckBox> mStatusBoxes = new LinkedHashMap<>();
    private Spinner mSortSpinner;
    private EditText mTimeFromInput;
    private EditText mTimeToInput;
    private EditText mSizeFromInput;
    private EditText mSizeToInput;
    private EditText mPageFromInput;
    private EditText mPageToInput;
    private CheckBox mDuplicateOnly;
    private CheckBox mRatingEnabled;
    private RatingBar mRatingFrom;
    private RatingBar mRatingTo;
    private FilterExtraOptionsView mExtraOptions;
    private FilterExcludeView mExcludeView;
    private final List<SortKey> mSortKeys = new ArrayList<>();
    private boolean mUpdating;

    public FilterConditionEditorView(@NonNull Context context) {
        this(context, null);
    }

    public FilterConditionEditorView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);

        mCategoryTable = new DownloadCategoryTable(context);
        addSection(context, R.string.dialog_category_filter_label, mCategoryTable);

        // 下载状态
        LinearLayout statusBox = new LinearLayout(context);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        int[] states = {
                DownloadInfo.STATE_NONE, DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD,
                DownloadInfo.STATE_FINISH, DownloadInfo.STATE_FAILED, DownloadInfo.STATE_RELAY_DOWNLOAD,
        };
        int[] stateLabels = {
                R.string.download_state_none, R.string.download_state_wait,
                R.string.download_state_downloading, R.string.download_state_downloaded,
                R.string.download_state_failed, R.string.download_state_relay_download,
        };
        for (int i = 0; i < states.length; i++) {
            CheckBox box = new CheckBox(context);
            box.setText(stateLabels[i]);
            statusBox.addView(box);
            mStatusBoxes.put(states[i], box);
        }
        addSection(context, R.string.dialog_status_filter_label, statusBox);

        // 排序
        mSortSpinner = new Spinner(context);
        List<String> sortLabels = new ArrayList<>();
        for (SortKey key : SortKey.values()) {
            mSortKeys.add(key);
            sortLabels.add(context.getString(key.getLabelRes()));
        }
        ArrayAdapter<String> sortAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, sortLabels);
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSortSpinner.setAdapter(sortAdapter);
        addSection(context, R.string.dialog_sort_method_label, mSortSpinner);

        // 下载时间
        LinearLayout timeRow = new LinearLayout(context);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        mTimeFromInput = createInput(context, InputType.TYPE_CLASS_TEXT);
        mTimeToInput = createInput(context, InputType.TYPE_CLASS_TEXT);
        timeRow.addView(mTimeFromInput, weighted());
        timeRow.addView(mTimeToInput, weighted());
        addSection(context, R.string.dialog_time_range_filter_label, timeRow);

        // 体积
        LinearLayout sizeRow = new LinearLayout(context);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        mSizeFromInput = createInput(context, InputType.TYPE_CLASS_TEXT);
        mSizeToInput = createInput(context, InputType.TYPE_CLASS_TEXT);
        sizeRow.addView(mSizeFromInput, weighted());
        sizeRow.addView(mSizeToInput, weighted());
        addSection(context, R.string.dialog_size_range_filter_label, sizeRow);

        // 页数
        LinearLayout pageRow = new LinearLayout(context);
        pageRow.setOrientation(LinearLayout.HORIZONTAL);
        mPageFromInput = createInput(context, InputType.TYPE_CLASS_NUMBER);
        mPageToInput = createInput(context, InputType.TYPE_CLASS_NUMBER);
        pageRow.addView(mPageFromInput, weighted());
        pageRow.addView(mPageToInput, weighted());
        addSection(context, R.string.dialog_page_range_filter_label, pageRow);

        // 评分区间
        mRatingEnabled = new CheckBox(context);
        mRatingEnabled.setText(R.string.dialog_rating_filter_label);
        mRatingFrom = new RatingBar(context, null, android.R.attr.ratingBarStyleSmall);
        mRatingFrom.setNumStars(5);
        mRatingFrom.setStepSize(0.5f);
        mRatingTo = new RatingBar(context, null, android.R.attr.ratingBarStyleSmall);
        mRatingTo.setNumStars(5);
        mRatingTo.setStepSize(0.5f);
        LinearLayout ratingBox = new LinearLayout(context);
        ratingBox.setOrientation(LinearLayout.VERTICAL);
        ratingBox.addView(mRatingEnabled);
        LinearLayout ratingRow = new LinearLayout(context);
        ratingRow.setGravity(Gravity.CENTER);
        ratingRow.addView(mRatingFrom);
        ratingRow.addView(mRatingTo);
        ratingBox.addView(ratingRow);
        mRatingEnabled.setOnCheckedChangeListener((b, checked) -> {
            mRatingFrom.setEnabled(checked);
            mRatingTo.setEnabled(checked);
        });
        addSection(context, 0, ratingBox);

        mDuplicateOnly = new CheckBox(context);
        mDuplicateOnly.setText(R.string.filter_duplicate_only);
        addSection(context, 0, mDuplicateOnly);

        mExtraOptions = new FilterExtraOptionsView(context);
        addSection(context, R.string.filter_section_quick_presets, mExtraOptions);

        mExcludeView = new FilterExcludeView(context);
        addSection(context, R.string.filter_section_exclude, mExcludeView);
    }

    private void addSection(Context context, int titleRes, View content) {
        if (titleRes != 0) {
            TextView title = new TextView(context);
            title.setText(titleRes);
            title.setTextSize(13f);
            title.setPadding(0, dp(10), 0, dp(2));
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            addView(title);
        }
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        addView(content, lp);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private EditText createInput(Context context, int inputType) {
        EditText input = new EditText(context);
        input.setInputType(inputType);
        input.setMaxLines(1);
        return input;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(4));
        return lp;
    }

    // ---------------- 读写 ----------------

    public void setCondition(@Nullable FilterCondition condition) {
        mUpdating = true;
        FilterCondition c = condition != null ? condition : FilterCondition.EMPTY;

        Set<Integer> categories = c.getCategories();
        if (categories == null || categories.isEmpty() || categories.contains(com.hippo.ehviewer.client.EhUtils.ALL_CATEGORY)) {
            mCategoryTable.setAllSelected();
        } else {
            mCategoryTable.setSelectedCategories(new LinkedHashSet<>(categories));
        }

        Set<Integer> statuses = c.getStatuses();
        boolean restoreAll = statuses == null || statuses.isEmpty();
        for (Map.Entry<Integer, CheckBox> entry : mStatusBoxes.entrySet()) {
            entry.getValue().setChecked(restoreAll || statuses.contains(entry.getKey()));
        }

        mSortSpinner.setSelection(Math.max(0, mSortKeys.indexOf(c.getSort())));

        mTimeFromInput.setText(formatTime(c.getTimeFrom()));
        mTimeToInput.setText(formatTime(c.getTimeTo()));
        mSizeFromInput.setText(formatSize(c.getSizeFrom()));
        mSizeToInput.setText(formatSize(c.getSizeTo()));
        mPageFromInput.setText(c.getPageFrom() != null ? String.valueOf(c.getPageFrom()) : "");
        mPageToInput.setText(c.getPageTo() != null ? String.valueOf(c.getPageTo()) : "");

        mDuplicateOnly.setChecked(c.getDuplicateOnly());

        boolean ratingEnabled = c.getRatingFrom() > 0f || c.getRatingTo() < 5f;
        mRatingEnabled.setChecked(ratingEnabled);
        mRatingFrom.setEnabled(ratingEnabled);
        mRatingTo.setEnabled(ratingEnabled);
        mRatingFrom.setRating(Math.max(0f, Math.min(5f, c.getRatingFrom())));
        mRatingTo.setRating(Math.max(0f, Math.min(5f, c.getRatingTo())));

        mExtraOptions.setCondition(c);
        mExcludeView.setRule(c.getExclude());
        mUpdating = false;
    }

    @NonNull
    public FilterCondition getCondition() {
        Set<Integer> categories = new LinkedHashSet<>(mCategoryTable.getSelectedCategories());
        Set<Integer> statuses = new LinkedHashSet<>();
        for (Map.Entry<Integer, CheckBox> entry : mStatusBoxes.entrySet()) {
            if (entry.getValue().isChecked()) {
                statuses.add(entry.getKey());
            }
        }
        SortKey sort = SortKey.DEFAULT;
        int sortIndex = mSortSpinner.getSelectedItemPosition();
        if (sortIndex >= 0 && sortIndex < mSortKeys.size()) {
            sort = mSortKeys.get(sortIndex);
        }
        float ratingFrom = 0f;
        float ratingTo = 5f;
        if (mRatingEnabled.isChecked()) {
            ratingFrom = mRatingFrom.getRating();
            ratingTo = mRatingTo.getRating();
            if (ratingFrom > ratingTo) {
                float tmp = ratingFrom;
                ratingFrom = ratingTo;
                ratingTo = tmp;
            }
        }
        FilterCondition base = new FilterCondition(
                categories, statuses, sort,
                parseTime(mTimeFromInput.getText().toString()),
                parseTime(mTimeToInput.getText().toString()),
                parseSize(mSizeFromInput.getText().toString()),
                parseSize(mSizeToInput.getText().toString()),
                parseLong(mPageFromInput.getText().toString()),
                parseLong(mPageToInput.getText().toString()),
                mDuplicateOnly.isChecked(),
                ratingFrom,
                ratingTo)
                .withExclude(mExcludeView.getRule());
        return mExtraOptions.applyTo(base);
    }

    public FilterExcludeView getExcludeView() {
        return mExcludeView;
    }

    public boolean isUpdating() {
        return mUpdating;
    }

    private static String formatTime(@Nullable Long time) {
        if (time == null || time <= 0) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(time));
    }

    private static String formatSize(@Nullable Long size) {
        if (size == null || size < 0) {
            return "";
        }
        if (size % (1024L * 1024 * 1024) == 0) {
            return (size / (1024L * 1024 * 1024)) + "GB";
        }
        if (size % (1024L * 1024) == 0) {
            return (size / (1024L * 1024)) + "MB";
        }
        if (size % 1024L == 0) {
            return (size / 1024L) + "KB";
        }
        return String.valueOf(size);
    }

    private static Long parseLong(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseTime(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date date = sdf.parse(text.trim());
            return date != null ? date.getTime() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseSize(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String s = text.trim().toUpperCase(Locale.US);
        try {
            long multiplier = 1;
            if (s.endsWith("GB")) {
                multiplier = 1024L * 1024 * 1024;
                s = s.substring(0, s.length() - 2).trim();
            } else if (s.endsWith("MB")) {
                multiplier = 1024L * 1024;
                s = s.substring(0, s.length() - 2).trim();
            } else if (s.endsWith("KB")) {
                multiplier = 1024L;
                s = s.substring(0, s.length() - 2).trim();
            }
            return (long) (Double.parseDouble(s) * multiplier);
        } catch (Exception e) {
            return null;
        }
    }
}
