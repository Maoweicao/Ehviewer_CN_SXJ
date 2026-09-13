/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.hippo.android.resource.AttrResources;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.exception.EhException;
import com.hippo.ehviewer.util.SearchDebugLog;
import com.hippo.ripple.Ripple;
import com.hippo.widget.AutoWrapLayout;
import com.hippo.widget.RadioGridGroup;
import com.hippo.lib.yorozuya.ViewUtils;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedHashMap;
import java.util.Map;

public class SearchLayout extends EasyRecyclerView implements CompoundButton.OnCheckedChangeListener,
        View.OnClickListener, ImageSearchLayout.Helper {

    @IntDef({SEARCH_MODE_NORMAL, SEARCH_MODE_IMAGE})
    @Retention(RetentionPolicy.SOURCE)
    private @interface SearchMode {}

    private static final String STATE_KEY_SUPER = "super";
    private static final String STATE_KEY_SEARCH_MODE = "search_mode";
    private static final String STATE_KEY_ENABLE_ADVANCE = "enable_advance";
    private static final String STATE_KEY_LANGUAGE = "language";

    public static final int SEARCH_MODE_NORMAL = 0;
    public static final int SEARCH_MODE_IMAGE = 1;

    // 卡片分区（右侧高级搜索面板按卡片细分）
    private static final int ITEM_TYPE_NORMAL = 0;        // 分类与模式
    private static final int ITEM_TYPE_LANGUAGE = 1;      // 语言快捷标签
    private static final int ITEM_TYPE_NORMAL_ADVANCE = 2; // 高级搜索选项
    private static final int ITEM_TYPE_IMAGE = 3;         // 图像搜索
    private static final int ITEM_TYPE_ACTION = 4;        // 搜索动作

    // 语言快捷标签：显示名 -> e-hentai 语言值
    public static final String[][] LANGUAGE_TAGS = {
            {"中文", "chinese"},
            {"英文", "english"},
            {"日文", "japanese"},
            {"韩文", "korean"},
            {"翻译", "translated"},
            {"法文", "french"},
            {"德文", "german"},
            {"西文", "spanish"},
            {"俄文", "russian"},
    };

    private LayoutInflater mInflater;

    private int mSearchMode = SEARCH_MODE_NORMAL;
    private boolean mEnableAdvance = false;
    private String mLanguageTag = null;

    private View mNormalView;
    private CategoryTable mCategoryTable;
    private RadioGridGroup mNormalSearchMode;
    private ImageView mNormalSearchModeHelp;
    private SwitchCompat mEnableAdvanceSwitch;

    private View mAdvanceView;
    private AdvanceSearchTable mTableAdvanceSearch;

    // 语言卡片视图（AutoWrapLayout + 语言 Chip 文本）
    private AutoWrapLayout mLanguageList;
    private final Map<String, TextView> mLangChipViews = new LinkedHashMap<>();

    private ImageSearchLayout mImageView;

    private View mActionView;
    private TextView mTagSearchEntrance;
    private TextView mImageSearchToggle;

    private LinearLayoutManager mLayoutManager;
    private SearchAdapter mAdapter;

    private Helper mHelper;

    public SearchLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SearchLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    @SuppressLint("InflateParams")
    private void init(Context context) {
        Resources resources = context.getResources();
        mInflater = LayoutInflater.from(context);

        mLayoutManager = new LinearLayoutManager(context);
        mAdapter = new SearchAdapter();
        setLayoutManager(mLayoutManager);
        setAdapter(mAdapter);
        setHasFixedSize(true);
        setClipToPadding(false);
        int interval = resources.getDimensionPixelOffset(R.dimen.search_layout_interval);
        int paddingH = resources.getDimensionPixelOffset(R.dimen.search_layout_margin_h);
        int paddingV = resources.getDimensionPixelOffset(R.dimen.search_layout_margin_v);
        MarginItemDecoration decoration = new MarginItemDecoration(
                interval, paddingH, paddingV, paddingH, paddingV);
        addItemDecoration(decoration);
        decoration.applyPaddings(this);

        // Create normal view
        View normalView = mInflater.inflate(R.layout.search_normal, null);
        mNormalView = normalView;
        mCategoryTable = normalView.findViewById(R.id.search_category_table);
        mNormalSearchMode = normalView.findViewById(R.id.normal_search_mode);
        mNormalSearchModeHelp = normalView.findViewById(R.id.normal_search_mode_help);
        mEnableAdvanceSwitch = normalView.findViewById(R.id.search_enable_advance);
        mNormalSearchModeHelp.setOnClickListener(this);
        Ripple.addRipple(mNormalSearchModeHelp, !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme));
        mEnableAdvanceSwitch.setOnCheckedChangeListener(SearchLayout.this);
        mEnableAdvanceSwitch.setSwitchPadding(resources.getDimensionPixelSize(R.dimen.switch_padding));

        // Create advance view
        mAdvanceView = mInflater.inflate(R.layout.search_advance, null);
        mTableAdvanceSearch = mAdvanceView.findViewById(R.id.search_advance_search_table);

        // Create language view
        mLanguageList = (AutoWrapLayout) mInflater.inflate(R.layout.search_language, null);
        buildLanguageChips();

        // Create image view
        mImageView = (ImageSearchLayout) mInflater.inflate(R.layout.search_image, null);
        mImageView.setHelper(this);

        // Create action view (New Structure)
        mActionView = mInflater.inflate(R.layout.search_action, null);

        // 1. 绑定“图片搜索”切换
        mImageSearchToggle = mActionView.findViewById(R.id.search_image_toggle);
        mImageSearchToggle.setOnClickListener(v -> toggleSearchMode());

        // 2. 绑定“标签检索”跳转
        mTagSearchEntrance = mActionView.findViewById(R.id.tv_tag_search_entrance);
        mTagSearchEntrance.setOnClickListener(v -> {
            if (mHelper != null) {
                mHelper.onOpenTagSelector();
            }
        });
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }

    private void buildLanguageChips() {
        mLangChipViews.clear();
        // 清除项
        TextView clear = makeLanguageChip(getResources().getString(R.string.search_language_clear), null);
        mLanguageList.addView(clear);
        for (String[] lang : LANGUAGE_TAGS) {
            mLanguageList.addView(makeLanguageChip(lang[0], lang[1]));
        }
    }

    private TextView makeLanguageChip(String display, String tag) {
        TextView tv = new TextView(getContext());
        tv.setText(display);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(v -> selectLanguage(tag, tv));
        mLangChipViews.put(tag == null ? "" : tag, tv);
        return tv;
    }

    private void selectLanguage(String tag, TextView tv) {
        if (tag == null) {
            // 清除
            mLanguageTag = null;
        } else if (tag.equals(mLanguageTag)) {
            // 再次点击取消选择
            mLanguageTag = null;
        } else {
            mLanguageTag = tag;
        }
        refreshLanguageChipStyles();
    }

    private void refreshLanguageChipStyles() {
        int accent = AttrResources.getAttrColor(getContext(), R.attr.widgetColorThemeAccent);
        for (Map.Entry<String, TextView> entry : mLangChipViews.entrySet()) {
            boolean selected = !entry.getKey().isEmpty() && entry.getKey().equals(mLanguageTag);
            TextView tv = entry.getValue();
            tv.setBackground(createChipBackground(selected, accent));
            tv.setTextColor(selected ? android.graphics.Color.WHITE
                    : AttrResources.getAttrColor(getContext(), R.attr.drawableColorPrimary));
        }
    }

    private int getUnselectedChipColor() {
        if (AttrResources.getAttrBoolean(getContext(), androidx.appcompat.R.attr.isLightTheme)) {
            return 0x14000000;
        } else {
            return 0x14FFFFFF;
        }
    }

    private GradientDrawable createChipBackground(boolean selected, int accent) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(4));
        int strokeColor = AttrResources.getAttrColor(getContext(), R.attr.drawableColorSecondary);
        bg.setStroke(dp(1), strokeColor);
        bg.setColor(selected ? accent : getUnselectedChipColor());
        return bg;
    }

    public void setHelper(Helper helper) {
        mHelper = helper;
    }

    public void scrollSearchContainerToTop() {
        mLayoutManager.scrollToPositionWithOffset(0, 0);
    }

    public void setImageUri(Uri imageUri) {
        mImageView.setImageUri(imageUri);
    }

    public void setNormalSearchMode(int id) {
        mNormalSearchMode.check(id);
    }

    @Override
    public void onSelectImage() {
        if (mHelper != null) {
            mHelper.onSelectImage();
        }
    }

    @Override
    protected void dispatchSaveInstanceState(@NonNull SparseArray<Parcelable> container) {
        super.dispatchSaveInstanceState(container);

        mNormalView.saveHierarchyState(container);
        mAdvanceView.saveHierarchyState(container);
        mLanguageList.saveHierarchyState(container);
        mImageView.saveHierarchyState(container);
        mActionView.saveHierarchyState(container);
    }

    @Override
    protected void dispatchRestoreInstanceState(@NonNull SparseArray<Parcelable> container) {
        super.dispatchRestoreInstanceState(container);

        mNormalView.restoreHierarchyState(container);
        mAdvanceView.restoreHierarchyState(container);
        mLanguageList.restoreHierarchyState(container);
        mImageView.restoreHierarchyState(container);
        mActionView.restoreHierarchyState(container);
    }

    @Override
    public Parcelable onSaveInstanceState() {
        final Bundle state = new Bundle();
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState());
        state.putInt(STATE_KEY_SEARCH_MODE, mSearchMode);
        state.putBoolean(STATE_KEY_ENABLE_ADVANCE, mEnableAdvance);
        state.putString(STATE_KEY_LANGUAGE, mLanguageTag);
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            final Bundle savedState = (Bundle) state;
            super.onRestoreInstanceState(savedState.getParcelable(STATE_KEY_SUPER));
            mSearchMode = savedState.getInt(STATE_KEY_SEARCH_MODE);
            mEnableAdvance = savedState.getBoolean(STATE_KEY_ENABLE_ADVANCE);
            mLanguageTag = savedState.getString(STATE_KEY_LANGUAGE);
            refreshLanguageChipStyles();
        } else {
            super.onRestoreInstanceState(state);
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView == mEnableAdvanceSwitch) {
            mEnableAdvance = isChecked;
            if (mSearchMode == SEARCH_MODE_NORMAL) {
                // 高级搜索选项卡片位于第 3 项（索引 2）。
                // post 延迟到当前布局/状态恢复流程结束，避免通知在布局过程中被吞掉导致卡片不出现。
                // 用实时字段 mSearchMode/mEnableAdvance 判断，防止期间切换了搜索模式导致过期插入破坏列表。
                post(() -> {
                    if (mSearchMode != SEARCH_MODE_NORMAL) {
                        return;
                    }
                    if (mEnableAdvance) {
                        mAdapter.notifyItemInserted(2);
                    } else {
                        mAdapter.notifyItemRemoved(2);
                    }
                });

                if (mHelper != null) {
                    mHelper.onChangeSearchMode();
                }
            }
        }
    }

    public void formatListUrlBuilder(ListUrlBuilder urlBuilder, String query) throws EhException {
        urlBuilder.reset();
        SearchDebugLog.d("GallerySearch", "formatListUrlBuilder() searchMode=" + mSearchMode + " rawQuery='" + query + "'");

        switch (mSearchMode) {
            case SEARCH_MODE_NORMAL:
                int nsMode = mNormalSearchMode.getCheckedRadioButtonId();
                switch (nsMode) {
                    default:
                    case R.id.search_normal_search:
                        urlBuilder.setMode(ListUrlBuilder.MODE_NORMAL);
                        break;
                    case R.id.search_subscription_search:
                        urlBuilder.setMode(ListUrlBuilder.MODE_SUBSCRIPTION);
                        break;
                    case R.id.search_specify_uploader:
                        urlBuilder.setMode(ListUrlBuilder.MODE_UPLOADER);
                        break;
                    case R.id.search_specify_tag:
                        urlBuilder.setMode(ListUrlBuilder.MODE_TAG);
                        break;
                }
                // 追加语言快捷标签
                String finalQuery = query == null ? "" : query;
                if (mLanguageTag != null && !mLanguageTag.isEmpty()) {
                    finalQuery = TextUtils.isEmpty(finalQuery) ? "language:" + mLanguageTag
                            : finalQuery + " language:" + mLanguageTag;
                    SearchDebugLog.d("GallerySearch", "append language -> '" + finalQuery + "'");
                }
                if (mEnableAdvance) {
                    int advanceSearch = mTableAdvanceSearch.getAdvanceSearch();
                    int minRating = mTableAdvanceSearch.getMinRating();
                    int pageFrom = mTableAdvanceSearch.getPageFrom();
                    int pageTo = mTableAdvanceSearch.getPageTo();
                    urlBuilder.setAdvanceSearch(advanceSearch);
                    urlBuilder.setMinRating(minRating);
                    urlBuilder.setPageFrom(pageFrom);
                    urlBuilder.setPageTo(pageTo);
                }
                urlBuilder.setKeyword(finalQuery);
                urlBuilder.setCategory(mCategoryTable.getCategory());
                SearchDebugLog.d("GallerySearch", "final f_search = '" + finalQuery
                        + "' category=0x" + Integer.toHexString(mCategoryTable.getCategory()));
                break;
            case SEARCH_MODE_IMAGE:
                urlBuilder.setMode(ListUrlBuilder.MODE_IMAGE_SEARCH);
                mImageView.formatListUrlBuilder(urlBuilder);
                break;
        }
    }

    public void setSearchMode(@SearchMode int searchMode, boolean animation) {
        if (mSearchMode != searchMode) {
            int oldItemCount = mAdapter.getItemCount();
            mSearchMode = searchMode;
            int newItemCount = mAdapter.getItemCount();

            if (animation) {
                mAdapter.notifyItemRangeRemoved(0, oldItemCount - 1);
                mAdapter.notifyItemRangeInserted(0, newItemCount - 1);
            } else {
                mAdapter.notifyDataSetChanged();
            }

            if (mHelper != null) {
                mHelper.onChangeSearchMode();
            }
        }
    }

    public void toggleSearchMode() {
        int oldItemCount = mAdapter.getItemCount();

        mSearchMode++;
        if (mSearchMode > SEARCH_MODE_IMAGE) {
            mSearchMode = SEARCH_MODE_NORMAL;
        }

        int newItemCount = mAdapter.getItemCount();

        mAdapter.notifyItemRangeRemoved(0, oldItemCount - 1);
        mAdapter.notifyItemRangeInserted(0, newItemCount - 1);

        // Update action text
        int resId;
        switch (mSearchMode) {
            default:
            case SEARCH_MODE_NORMAL:
                resId = R.string.image_search;
                break;
            case SEARCH_MODE_IMAGE:
                resId = R.string.keyword_search;
                break;
        }

        // --- [修正] 确保将文字设置给 mImageSearchToggle ---
        if (mImageSearchToggle != null) {
            mImageSearchToggle.setText(resId);
        }

        if (mHelper != null) {
            mHelper.onChangeSearchMode();
        }
    }

    @Override
    public void onClick(View v) {
        if (mNormalSearchModeHelp == v) {
            new AlertDialog.Builder(getContext())
                    .setMessage(R.string.search_tip)
                    .show();
        }
    }

    private class SimpleHolder extends ViewHolder {
        private final FilterSectionCard mCard;

        SimpleHolder(View itemView) {
            super(itemView);
            mCard = (FilterSectionCard) itemView;
        }

        /**
         * 把分区卡片绑定到指定内容视图。循环复用（recycle/rebind）时先清空再挂载，
         * 避免上一次的内容残留导致卡片显示错乱或空白。
         */
        void bind(int titleResId, View content, boolean collapsible) {
            mCard.setTitle(titleResId);
            mCard.setCollapsible(collapsible);
            mCard.clearContent();
            if (content != null) {
                ViewUtils.removeFromParent(content);
                mCard.addContentView(content, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
    }

    private FilterSectionCard createCard(ViewGroup parent) {
        return new FilterSectionCard(parent.getContext());
    }

    private void configActionView(int searchMode) {
        int resId;
        switch (searchMode) {
            default:
            case SEARCH_MODE_NORMAL:
                resId = R.string.image_search;
                break;
            case SEARCH_MODE_IMAGE:
                resId = R.string.keyword_search;
                break;
        }
        // --- 防止空指针异常 ---
        if (mImageSearchToggle != null) {
            mImageSearchToggle.setText(resId);
        }
    }

    private class SearchAdapter extends Adapter<ViewHolder> {

        @Override
        public int getItemCount() {
            if (mSearchMode == SEARCH_MODE_NORMAL) {
                // 分类与模式 + 语言 + (高级搜索选项) + 动作
                return mEnableAdvance ? 4 : 3;
            }
            // 图像搜索 + 动作
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            if (mSearchMode == SEARCH_MODE_NORMAL) {
                if (position == 0) {
                    return ITEM_TYPE_NORMAL;
                } else if (position == 1) {
                    return ITEM_TYPE_LANGUAGE;
                } else if (position == 2) {
                    return mEnableAdvance ? ITEM_TYPE_NORMAL_ADVANCE : ITEM_TYPE_ACTION;
                } else {
                    return ITEM_TYPE_ACTION;
                }
            } else {
                if (position == 0) {
                    return ITEM_TYPE_IMAGE;
                } else {
                    return ITEM_TYPE_ACTION;
                }
            }
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new SimpleHolder(createCard(parent));
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            SimpleHolder sHolder = (SimpleHolder) holder;
            switch (getItemViewType(position)) {
                case ITEM_TYPE_NORMAL:
                    sHolder.bind(R.string.search_card_category, mNormalView, true);
                    break;
                case ITEM_TYPE_LANGUAGE:
                    sHolder.bind(R.string.search_card_language, mLanguageList, true);
                    break;
                case ITEM_TYPE_NORMAL_ADVANCE:
                    sHolder.bind(R.string.search_card_advance, mAdvanceView, true);
                    break;
                case ITEM_TYPE_IMAGE:
                    sHolder.bind(R.string.search_image, mImageView, true);
                    break;
                default:
                    configActionView(mSearchMode);
                    sHolder.bind(R.string.search_card_action, mActionView, false);
                    break;
            }
        }
    }

    public interface Helper {
        void onChangeSearchMode();
        void onSelectImage();
        void onOpenTagSelector();
    }
}