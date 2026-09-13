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

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.view.ViewCompat;

import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.R;

/**
 * 可折叠的筛选分区卡片组件
 */
public class FilterSectionCard extends CardView {

    private static final String STATE_KEY_SUPER = "super";
    private static final String STATE_KEY_EXPANDED = "expanded";

    private static final long ANIMATION_DURATION = 200L;

    private TextView mTitleView;
    private ImageView mArrowView;
    private LinearLayout mContentContainer;
    private LinearLayout mHeaderLayout;

    private String mTitle = "";
    private boolean mExpanded = true;
    private boolean mCollapsible = true;

    private OnExpandChangeListener mListener;

    public FilterSectionCard(@NonNull Context context) {
        super(context);
        init(context, null);
    }

    public FilterSectionCard(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public FilterSectionCard(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        setRadius(context.getResources().getDimension(R.dimen.card_corner_radius));
        setCardElevation(context.getResources().getDimension(R.dimen.card_elevation));
        // 卡片背景跟随主题（浅色/深色/黑色主题使用各自的内容面板颜色）
        setCardBackgroundColor(AttrResources.getAttrColor(context, R.attr.contentColorPrimary));
        setUseCompatPadding(true);

        LayoutInflater.from(context).inflate(R.layout.widget_filter_section_card, this, true);

        mTitleView = findViewById(R.id.section_title);
        mArrowView = findViewById(R.id.section_arrow);
        mContentContainer = findViewById(R.id.section_content_container);
        mHeaderLayout = findViewById(R.id.section_header);

        // 从attrs读取配置
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.FilterSectionCard);
            mTitle = a.getString(R.styleable.FilterSectionCard_sectionTitle);
            mExpanded = a.getBoolean(R.styleable.FilterSectionCard_sectionExpanded, true);
            mCollapsible = a.getBoolean(R.styleable.FilterSectionCard_sectionCollapsible, true);
            a.recycle();
        }

        if (mTitle != null) {
            mTitleView.setText(mTitle);
        }

        // 设置箭头初始状态
        updateArrowRotation(false);
        updateContentVisibility(false);

        // 设置点击监听
        mHeaderLayout.setOnClickListener(v -> {
            if (mCollapsible) {
                toggle();
            }
        });

        // 设置点击效果
        ViewCompat.setBackground(mHeaderLayout, createHeaderBackground());
    }

    private android.graphics.drawable.Drawable createHeaderBackground() {
        // 标题栏底色与按压水波纹跟随主题
        boolean light = AttrResources.getAttrBoolean(getContext(), androidx.appcompat.R.attr.isLightTheme);
        int baseColor = light ? 0x14000000 : 0x14FFFFFF;
        int rippleColor = light ? 0x1F000000 : 0x1FFFFFFF;
        android.graphics.drawable.GradientDrawable content = new android.graphics.drawable.GradientDrawable();
        content.setColor(baseColor);
        content.setCornerRadius(getContext().getResources().getDimension(R.dimen.card_corner_radius) / 2f);
        android.content.res.ColorStateList colorStateList = new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_pressed}, {}},
                new int[]{rippleColor, android.graphics.Color.TRANSPARENT}
        );
        return new android.graphics.drawable.RippleDrawable(colorStateList, content, null);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // FilterSectionCard 本身继承自 CardView（FrameLayout）。
        // 如果在 XML 中把子视图直接写在 <FilterSectionCard> 标签内部，
        // 它们会被添加为 CardView 内容层的多个平级子 View，彼此堆叠重叠。
        // 这里在布局加载完成后把所有子视图移入内容容器（LinearLayout）中，
        // 使标题栏与内容区正确上下排列。
        moveContentChildren();
    }

    private void moveContentChildren() {
        if (mContentContainer == null) {
            return;
        }
        // 索引 0 是 init() 中 inflate 进来的标题栏+内容容器布局，保留；
        // 其余子视图是 XML 中声明的分区内容，全部移入内容容器。
        while (getChildCount() > 1) {
            View child = getChildAt(1);
            removeViewAt(1);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            LinearLayout.LayoutParams lp = params instanceof LinearLayout.LayoutParams
                    ? (LinearLayout.LayoutParams) params
                    : new LinearLayout.LayoutParams(params);
            mContentContainer.addView(child, lp);
        }
    }

    private void toggle() {
        setExpanded(!mExpanded);
    }

    /**
     * 设置是否展开
     */
    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) {
            return;
        }
        mExpanded = expanded;
        animateContent();
        updateArrowRotation(true);
        if (mListener != null) {
            mListener.onExpandChanged(this, mExpanded);
        }
    }

    /**
     * 是否处于展开状态
     */
    public boolean isExpanded() {
        return mExpanded;
    }

    private void updateArrowRotation(boolean animate) {
        float rotation = mExpanded ? 180f : 0f;
        if (animate) {
            ValueAnimator animator = ValueAnimator.ofFloat(mArrowView.getRotation(), rotation);
            animator.setDuration(ANIMATION_DURATION);
            animator.addUpdateListener(animation -> mArrowView.setRotation((float) animation.getAnimatedValue()));
            animator.start();
        } else {
            mArrowView.setRotation(rotation);
        }
    }

    private void updateContentVisibility(boolean animate) {
        if (animate) {
            if (mExpanded) {
                mContentContainer.setVisibility(View.VISIBLE);
                ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(ANIMATION_DURATION);
                animator.addUpdateListener(animation -> {
                    float alpha = (float) animation.getAnimatedValue();
                    mContentContainer.setAlpha(alpha);
                });
                animator.start();
            } else {
                ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
                animator.setDuration(ANIMATION_DURATION);
                animator.addUpdateListener(animation -> {
                    float alpha = (float) animation.getAnimatedValue();
                    mContentContainer.setAlpha(alpha);
                    if (alpha == 0f) {
                        mContentContainer.setVisibility(View.GONE);
                    }
                });
                animator.start();
            }
        } else {
            mContentContainer.setAlpha(mExpanded ? 1f : 0f);
            mContentContainer.setVisibility(mExpanded ? View.VISIBLE : View.GONE);
        }
    }

    private void animateContent() {
        if (mExpanded) {
            mContentContainer.setVisibility(View.VISIBLE);
            mContentContainer.setAlpha(0f);
            mContentContainer.animate()
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION)
                    .start();
        } else {
            mContentContainer.animate()
                    .alpha(0f)
                    .setDuration(ANIMATION_DURATION)
                    .withEndAction(() -> mContentContainer.setVisibility(View.GONE))
                    .start();
        }
        updateArrowRotation(true);
    }

    /**
     * 获取标题文本
     */
    public String getTitle() {
        return mTitle;
    }

    /**
     * 设置标题文本
     */
    public void setTitle(String title) {
        mTitle = title;
        mTitleView.setText(title);
    }

    /**
     * 设置标题文本资源
     */
    public void setTitle(int resId) {
        mTitle = getContext().getString(resId);
        mTitleView.setText(resId);
    }

    /**
     * 设置是否可折叠
     */
    public void setCollapsible(boolean collapsible) {
        mCollapsible = collapsible;
        mArrowView.setVisibility(collapsible ? View.VISIBLE : View.GONE);
    }

    /**
     * 获取内容容器，可用于添加子视图
     */
    public ViewGroup getContentContainer() {
        return (ViewGroup) mContentContainer;
    }

    /**
     * 添加内容视图
     */
    public void addContentView(View child) {
        mContentContainer.addView(child);
    }

    /**
     * 添加内容视图
     */
    public void addContentView(View child, ViewGroup.LayoutParams params) {
        mContentContainer.addView(child, params);
    }

    /**
     * 清除所有内容视图
     */
    public void clearContent() {
        mContentContainer.removeAllViews();
    }

    /**
     * 设置展开状态变化监听器
     */
    public void setOnExpandChangeListener(OnExpandChangeListener listener) {
        mListener = listener;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        final Bundle state = new Bundle();
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState());
        state.putBoolean(STATE_KEY_EXPANDED, mExpanded);
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            final Bundle savedState = (Bundle) state;
            super.onRestoreInstanceState(savedState.getParcelable(STATE_KEY_SUPER));
            mExpanded = savedState.getBoolean(STATE_KEY_EXPANDED, true);
            updateArrowRotation(false);
            updateContentVisibility(false);
        } else {
            super.onRestoreInstanceState(state);
        }
    }

    public interface OnExpandChangeListener {
        void onExpandChanged(FilterSectionCard card, boolean expanded);
    }
}
