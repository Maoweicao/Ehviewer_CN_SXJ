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
import android.text.InputType;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;

import java.util.Locale;

/**
 * 通用范围输入组件，支持数字、日期、文件大小等输入类型
 */
public class RangeInputView extends LinearLayout {

    public static final int INPUT_TYPE_TEXT = 0;
    public static final int INPUT_TYPE_NUMBER = 1;
    public static final int INPUT_TYPE_DATE = 2;
    public static final int INPUT_TYPE_SIZE = 3;

    private static final String STATE_KEY_SUPER = "super";
    private static final String STATE_KEY_FROM = "from";
    private static final String STATE_KEY_TO = "to";

    private TextView mLabel;
    private EditText mFromInput;
    private EditText mToInput;
    private TextView mUnitText;
    private TextView mSeparatorText;

    private String mUnit = "";
    private int mInputType = INPUT_TYPE_TEXT;

    private OnRangeChangeListener mListener;

    public RangeInputView(Context context) {
        super(context);
        init(context, null);
    }

    public RangeInputView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public RangeInputView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);

        LayoutInflater.from(context).inflate(R.layout.widget_range_input_view, this, true);

        mLabel = findViewById(R.id.range_label);
        mFromInput = findViewById(R.id.range_from_input);
        mToInput = findViewById(R.id.range_to_input);
        mUnitText = findViewById(R.id.range_unit);
        mSeparatorText = findViewById(R.id.range_separator);

        // 从attrs读取配置
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RangeInputView);
            String label = a.getString(R.styleable.RangeInputView_rangeLabel);
            String hint = a.getString(R.styleable.RangeInputView_rangeHint);
            mUnit = a.getString(R.styleable.RangeInputView_rangeUnit);
            mInputType = a.getInt(R.styleable.RangeInputView_rangeInputType, INPUT_TYPE_TEXT);

            if (label != null) {
                mLabel.setText(label);
            }
            if (hint != null) {
                mFromInput.setHint(hint);
                mToInput.setHint(hint);
            }
            if (mUnit != null) {
                mUnitText.setText(mUnit);
            }

            a.recycle();
        }

        // 设置输入类型
        applyInputType();

        // 监听文本变化
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                notifyChange();
            }
        };
        mFromInput.addTextChangedListener(watcher);
        mToInput.addTextChangedListener(watcher);
    }

    private void applyInputType() {
        int inputType;
        switch (mInputType) {
            default:
            case INPUT_TYPE_TEXT:
                inputType = InputType.TYPE_CLASS_TEXT;
                break;
            case INPUT_TYPE_NUMBER:
                inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
                break;
            case INPUT_TYPE_DATE:
                inputType = InputType.TYPE_CLASS_TEXT;
                mUnitText.setVisibility(GONE);
                break;
            case INPUT_TYPE_SIZE:
                inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
                break;
        }
        mFromInput.setInputType(inputType);
        mToInput.setInputType(inputType);
    }

    private void notifyChange() {
        if (mListener != null) {
            mListener.onRangeChanged(this, getFromValue(), getToValue());
        }
    }

    /**
     * 获取标签文本
     */
    public String getLabel() {
        return mLabel.getText().toString();
    }

    /**
     * 设置标签文本
     */
    public void setLabel(String label) {
        mLabel.setText(label);
    }

    /**
     * 设置标签文本资源
     */
    public void setLabel(int resId) {
        mLabel.setText(resId);
    }

    /**
     * 获取单位文本
     */
    public String getUnit() {
        return mUnit;
    }

    /**
     * 设置单位文本
     */
    public void setUnit(String unit) {
        mUnit = unit;
        mUnitText.setText(unit);
    }

    /**
     * 设置单位文本资源
     */
    public void setUnit(int resId) {
        mUnit = getContext().getString(resId);
        mUnitText.setText(mUnit);
    }

    /**
     * 获取输入类型
     */
    public int getInputType() {
        return mInputType;
    }

    /**
     * 设置输入类型
     */
    public void setInputType(int inputType) {
        mInputType = inputType;
        applyInputType();
    }

    /**
     * 获取"从"输入框的文本
     */
    public String getFromText() {
        return mFromInput.getText().toString();
    }

    /**
     * 设置"从"输入框的文本
     */
    public void setFromText(String text) {
        mFromInput.setText(text);
    }

    /**
     * 获取"到"输入框的文本
     */
    public String getToText() {
        return mToInput.getText().toString();
    }

    /**
     * 设置"到"输入框的文本
     */
    public void setToText(String text) {
        mToInput.setText(text);
    }

    /**
     * 设置范围文本
     */
    public void setRange(String from, String to) {
        mFromInput.setText(from);
        mToInput.setText(to);
    }

    /**
     * 获取"从"值（用于数字类型）
     */
    public Double getFromValue() {
        String text = mFromInput.getText().toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (mInputType == INPUT_TYPE_SIZE) {
                return parseSizeValue(text);
            }
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取"到"值（用于数字类型）
     */
    public Double getToValue() {
        String text = mToInput.getText().toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (mInputType == INPUT_TYPE_SIZE) {
                return parseSizeValue(text);
            }
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析文件大小值（支持 KB, MB, GB 后缀）
     */
    private Double parseSizeValue(String text) {
        text = text.toUpperCase(Locale.US).trim();
        double multiplier = 1;
        if (text.endsWith("GB")) {
            multiplier = 1024 * 1024 * 1024;
            text = text.substring(0, text.length() - 2).trim();
        } else if (text.endsWith("MB")) {
            multiplier = 1024 * 1024;
            text = text.substring(0, text.length() - 2).trim();
        } else if (text.endsWith("KB")) {
            multiplier = 1024;
            text = text.substring(0, text.length() - 2).trim();
        } else if (text.endsWith("B")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return Double.parseDouble(text) * multiplier;
    }

    /**
     * 设置范围值（用于数字类型）
     */
    public void setRangeValue(Double from, Double to) {
        if (from != null) {
            mFromInput.setText(formatValue(from));
        } else {
            mFromInput.setText("");
        }
        if (to != null) {
            mToInput.setText(formatValue(to));
        } else {
            mToInput.setText("");
        }
    }

    /**
     * 格式化数字值
     */
    private String formatValue(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    /**
     * 重置输入
     */
    public void reset() {
        mFromInput.setText("");
        mToInput.setText("");
    }

    /**
     * 设置监听器
     */
    public void setOnRangeChangeListener(OnRangeChangeListener listener) {
        mListener = listener;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        final Bundle state = new Bundle();
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState());
        state.putString(STATE_KEY_FROM, getFromText());
        state.putString(STATE_KEY_TO, getToText());
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            final Bundle savedState = (Bundle) state;
            super.onRestoreInstanceState(savedState.getParcelable(STATE_KEY_SUPER));
            setFromText(savedState.getString(STATE_KEY_FROM, ""));
            setToText(savedState.getString(STATE_KEY_TO, ""));
        } else {
            super.onRestoreInstanceState(state);
        }
    }

    public interface OnRangeChangeListener {
        void onRangeChanged(RangeInputView view, Double from, Double to);
    }
}
