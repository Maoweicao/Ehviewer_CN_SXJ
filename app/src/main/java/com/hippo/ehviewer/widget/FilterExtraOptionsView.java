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
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.filter.FilterCondition;
import com.hippo.ehviewer.download.filter.PostedTime;

import java.util.ArrayList;
import java.util.List;

/**
 * 「低分 / 发布时间 / 未收藏 / AI 生成 / 无标签 / 每页体积」这几项新筛选条件。
 *
 * 它们对应 [FilterCondition] 里新增的字段，单独抽成一个 View 是为了：
 * <ul>
 *   <li>搜索面板与高级筛选面板共用一份实现</li>
 *   <li>快捷筛选的「自定义条件」编辑器也能直接复用</li>
 *   <li>所有条件都在界面上可见可改，不存在"隐藏条件"</li>
 * </ul>
 */
public class FilterExtraOptionsView extends LinearLayout {

    /** 发布时间快捷选项。 */
    private static final int[] POSTED_MONTHS = {0, 3, 6, 12, 24, 36};
    private static final int[] POSTED_LABEL_RES = {
            R.string.filter_extra_posted_any,
            R.string.filter_extra_posted_3m,
            R.string.filter_extra_posted_6m,
            R.string.filter_extra_posted_1y,
            R.string.filter_extra_posted_2y,
            R.string.filter_extra_posted_3y,
    };

    private CheckBox mLowRatingCheckBox;
    private Spinner mPostedSpinner;
    private CheckBox mUnfavoritedCheckBox;
    private CheckBox mAiGeneratedCheckBox;
    private CheckBox mTagsMissingCheckBox;
    private EditText mPerPageInput;

    private boolean mUpdating;

    public FilterExtraOptionsView(@NonNull Context context) {
        this(context, null);
    }

    public FilterExtraOptionsView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FilterExtraOptionsView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_filter_extra_options, this, true);
        mLowRatingCheckBox = findViewById(R.id.extra_low_rating_checkbox);
        mPostedSpinner = findViewById(R.id.extra_posted_spinner);
        mUnfavoritedCheckBox = findViewById(R.id.extra_unfavorited_checkbox);
        mAiGeneratedCheckBox = findViewById(R.id.extra_ai_generated_checkbox);
        mTagsMissingCheckBox = findViewById(R.id.extra_tags_missing_checkbox);
        mPerPageInput = findViewById(R.id.extra_per_page_input);

        List<String> labels = new ArrayList<>();
        for (int resId : POSTED_LABEL_RES) {
            labels.add(context.getString(resId));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mPostedSpinner.setAdapter(adapter);
    }

    /** 把条件里的新字段填进界面。 */
    public void setCondition(@Nullable FilterCondition condition) {
        mUpdating = true;
        if (condition == null) {
            mLowRatingCheckBox.setChecked(false);
            mPostedSpinner.setSelection(0);
            mUnfavoritedCheckBox.setChecked(false);
            mAiGeneratedCheckBox.setChecked(false);
            mTagsMissingCheckBox.setChecked(false);
            mPerPageInput.setText("");
        } else {
            mLowRatingCheckBox.setChecked(condition.getLowRatingOnly());
            mPostedSpinner.setSelection(indexOfMonths(condition.getPostedAgoMonths(), condition.getPostedTo()));
            mUnfavoritedCheckBox.setChecked(condition.getUnfavoritedOnly());
            mAiGeneratedCheckBox.setChecked(condition.getAiGeneratedOnly());
            mTagsMissingCheckBox.setChecked(condition.getTagsMissingOnly());
            Long perPage = condition.getPerPageFromBytes();
            if (perPage != null && perPage > 0) {
                double mb = perPage / (1024.0 * 1024.0);
                mPerPageInput.setText(mb == Math.floor(mb) ? String.valueOf((long) mb) : String.valueOf(mb));
            } else {
                mPerPageInput.setText("");
            }
        }
        mUpdating = false;
    }

    /**
     * 读回界面上的新字段。
     *
     * @param base 已有条件（保留分类 / 状态 / 排序 / 搜索等这里不涉及的字段）
     */
    @NonNull
    public FilterCondition applyTo(@Nullable FilterCondition base) {
        FilterCondition source = base != null ? base : FilterCondition.EMPTY;
        int months = POSTED_MONTHS[mPostedSpinner.getSelectedItemPosition()];
        Long perPageBytes = null;
        String perPageText = mPerPageInput.getText().toString().trim();
        if (!perPageText.isEmpty()) {
            try {
                double mb = Double.parseDouble(perPageText);
                if (mb > 0) {
                    perPageBytes = (long) (mb * 1024.0 * 1024.0);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return source.withExtraOptions(
                mLowRatingCheckBox.isChecked(),
                months,
                perPageBytes,
                mUnfavoritedCheckBox.isChecked(),
                mAiGeneratedCheckBox.isChecked(),
                mTagsMissingCheckBox.isChecked());
    }

    /**
     * 把条件里的发布时间设置映射到 spinner 行号。
     *
     * 优先用相对月数；老配置里只有绝对时间戳时，反推一个最接近的档位。
     */
    private static int indexOfMonths(int postedAgoMonths, @Nullable Long postedTo) {
        if (postedAgoMonths > 0) {
            for (int i = 1; i < POSTED_MONTHS.length; i++) {
                if (POSTED_MONTHS[i] == postedAgoMonths) {
                    return i;
                }
            }
            return 0;
        }
        if (postedTo == null || postedTo <= 0) {
            return 0;
        }
        int best = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 1; i < POSTED_MONTHS.length; i++) {
            long diff = Math.abs(PostedTime.monthsAgo(POSTED_MONTHS[i]) - postedTo);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        // 容差 5 天，避免"刚好卡在边界"被误判成其他档位
        return bestDiff <= 5L * 24 * 60 * 60 * 1000 ? best : 0;
    }
}
