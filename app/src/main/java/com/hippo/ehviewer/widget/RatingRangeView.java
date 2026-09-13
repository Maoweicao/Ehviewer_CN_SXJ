/*
 * Copyright 2025 Hippo Seven
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
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RatingBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;

import java.util.Locale;

/**
 * 星级范围选择组件，支持设置最低和最高星级
 * 范围: 0-5星，步进0.5
 */
public class RatingRangeView extends LinearLayout {

    private static final String STATE_KEY_SUPER = "super";
    private static final String STATE_KEY_ENABLED = "enabled";
    private static final String STATE_KEY_RATING_FROM = "rating_from";
    private static final String STATE_KEY_RATING_TO = "rating_to";

    private CheckBox mEnableCheckBox;
    private RatingBar mRatingFromBar;
    private RatingBar mRatingToBar;
    private TextView mFromLabel;
    private TextView mToLabel;
    private LinearLayout mContentLayout;

    private float mRatingFrom = 0f;
    private float mRatingTo = 5f;

    private OnRatingRangeChangeListener mListener;

    public RatingRangeView(Context context) {
        super(context);
        init(context, null);
    }

    public RatingRangeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public RatingRangeView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        setOrientation(VERTICAL);

        LayoutInflater.from(context).inflate(R.layout.widget_rating_range_view, this, true);

        mEnableCheckBox = findViewById(R.id.enable_rating_range);
        mRatingFromBar = findViewById(R.id.rating_from_bar);
        mRatingToBar = findViewById(R.id.rating_to_bar);
        mFromLabel = findViewById(R.id.from_label);
        mToLabel = findViewById(R.id.to_label);
        mContentLayout = findViewById(R.id.rating_content_layout);

        // 设置RatingBar参数
        mRatingFromBar.setNumStars(5);
        mRatingFromBar.setMax(10); // 5星 * 2 = 10步进
        mRatingFromBar.setStepSize(1); // 0.5星为一步
        mRatingFromBar.setRating(0);

        mRatingToBar.setNumStars(5);
        mRatingToBar.setMax(10);
        mRatingToBar.setStepSize(1);
        mRatingToBar.setRating(10); // 默认5星

        updateLabels();

        // 从attrs读取初始值
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RatingRangeView);
            boolean enabled = a.getBoolean(R.styleable.RatingRangeView_ratingRangeEnabled, false);
            float from = a.getFloat(R.styleable.RatingRangeView_ratingFrom, 0f);
            float to = a.getFloat(R.styleable.RatingRangeView_ratingTo, 5f);
            a.recycle();

            mEnableCheckBox.setChecked(enabled);
            setRatingFrom(from);
            setRatingTo(to);
            updateContentEnabled();
        }

        // 设置监听器
        mEnableCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateContentEnabled();
        });

        RatingBar.OnRatingBarChangeListener listener = (bar, rating, fromUser) -> {
            if (fromUser) {
                if (bar == mRatingFromBar) {
                    mRatingFrom = rating / 2f;
                    if (mRatingFrom > mRatingTo) {
                        mRatingTo = mRatingFrom;
                        mRatingToBar.setRating(mRatingTo * 2);
                    }
                } else if (bar == mRatingToBar) {
                    mRatingTo = rating / 2f;
                    if (mRatingTo < mRatingFrom) {
                        mRatingFrom = mRatingTo;
                        mRatingFromBar.setRating(mRatingFrom * 2);
                    }
                }
                updateLabels();
                notifyChange();
            }
        };

        mRatingFromBar.setOnRatingBarChangeListener(listener);
        mRatingToBar.setOnRatingBarChangeListener(listener);
    }

    private void updateContentEnabled() {
        boolean enabled = mEnableCheckBox.isChecked();
        mContentLayout.setEnabled(enabled);
        mRatingFromBar.setEnabled(enabled);
        mRatingToBar.setEnabled(enabled);
        mFromLabel.setEnabled(enabled);
        mToLabel.setEnabled(enabled);
        mContentLayout.setAlpha(enabled ? 1.0f : 0.5f);
    }

    private void updateLabels() {
        mFromLabel.setText(String.format(Locale.US, "%.1f", mRatingFrom));
        mToLabel.setText(String.format(Locale.US, "%.1f", mRatingTo));
    }

    private void notifyChange() {
        if (mListener != null) {
            mListener.onRatingRangeChanged(this, mRatingFrom, mRatingTo);
        }
    }

    /**
     * 是否启用评分范围筛选
     */
    public boolean isEnabled() {
        return mEnableCheckBox.isChecked();
    }

    /**
     * 设置是否启用评分范围筛选
     */
    public void setRangeEnabled(boolean enabled) {
        mEnableCheckBox.setChecked(enabled);
        updateContentEnabled();
    }

    /**
     * 获取最低评分
     */
    public float getRatingFrom() {
        return mRatingFrom;
    }

    /**
     * 设置最低评分
     */
    public void setRatingFrom(float rating) {
        mRatingFrom = Math.max(0f, Math.min(5f, rating));
        mRatingFromBar.setRating(mRatingFrom * 2);
        updateLabels();
    }

    /**
     * 获取最高评分
     */
    public float getRatingTo() {
        return mRatingTo;
    }

    /**
     * 设置最高评分
     */
    public void setRatingTo(float rating) {
        mRatingTo = Math.max(0f, Math.min(5f, rating));
        mRatingToBar.setRating(mRatingTo * 2);
        updateLabels();
    }

    /**
     * 设置评分范围
     */
    public void setRatingRange(float from, float to) {
        setRatingFrom(from);
        setRatingTo(to);
    }

    /**
     * 重置为默认值
     */
    public void reset() {
        setRangeEnabled(false);
        setRatingFrom(0f);
        setRatingTo(5f);
    }

    /**
     * 设置监听器
     */
    public void setOnRatingRangeChangeListener(OnRatingRangeChangeListener listener) {
        mListener = listener;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        final Bundle state = new Bundle();
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState());
        state.putBoolean(STATE_KEY_ENABLED, isEnabled());
        state.putFloat(STATE_KEY_RATING_FROM, mRatingFrom);
        state.putFloat(STATE_KEY_RATING_TO, mRatingTo);
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            final Bundle savedState = (Bundle) state;
            super.onRestoreInstanceState(savedState.getParcelable(STATE_KEY_SUPER));
            setRangeEnabled(savedState.getBoolean(STATE_KEY_ENABLED, false));
            setRatingFrom(savedState.getFloat(STATE_KEY_RATING_FROM, 0f));
            setRatingTo(savedState.getFloat(STATE_KEY_RATING_TO, 5f));
        } else {
            super.onRestoreInstanceState(state);
        }
    }

    public interface OnRatingRangeChangeListener {
        void onRatingRangeChanged(RatingRangeView view, float from, float to);
    }
}
