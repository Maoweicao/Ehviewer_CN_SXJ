/*
 * Copyright 2015 Hippo Seven
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

import static com.hippo.ehviewer.client.EhTagDatabase.NAMESPACE_TO_PREFIX;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.AutoCompleteTextView;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.util.SearchDebugLog;
import com.hippo.view.ViewTransition;
import com.hippo.lib.yorozuya.AnimationUtils;
import com.hippo.lib.yorozuya.MathUtils;
import com.hippo.lib.yorozuya.SimpleAnimatorListener;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.widget.AutoWrapLayout;

import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SearchBar extends CardView implements View.OnClickListener,
        TextView.OnEditorActionListener, TextWatcher,
        SearchEditText.SearchEditTextListener {

    private static final String STATE_KEY_SUPER = "super";
    private static final String STATE_KEY_STATE = "state";

    private static final String STATE_KEY_EDITOR_TEXTS = "keyword_editor_texts";
    private static final String STATE_KEY_EDITOR_GROUPS = "keyword_editor_groups";
    private static final String STATE_KEY_EDITOR_EXCLUDED = "keyword_editor_excluded";
    private static final String STATE_KEY_EDITOR_GROUP_EXCLUDED = "keyword_editor_group_excluded";
    private static final String STATE_KEY_EDITOR_GROUP_OR = "keyword_editor_group_or";
    private static final String STATE_KEY_EDITOR_LAST_QUERY = "keyword_editor_last_query";

    private static final long ANIMATE_TIME = 300L;

    public static final int STATE_NORMAL = 0;
    public static final int STATE_SEARCH = 1;
    public static final int STATE_SEARCH_LIST = 2;

    private int mState = STATE_NORMAL;

    private final Rect mRect = new Rect();
    private int mWidth;
    private int mHeight;
    private int mBaseHeight;
    private float mProgress;

    private ImageView mMenuButton;
    private TextView mTitleTextView;
    private ImageView mActionButton;
    private ImageView mAdvanceButton;
    public SearchEditText mEditText;
    private ListView mListView;
    private View mListContainer;
    private View mListHeader;

    private ViewTransition mViewTransition;

    private SearchDatabase mSearchDatabase;
    private List<Suggestion> mSuggestionList;
    private SuggestionAdapter mSuggestionAdapter;

    private Helper mHelper;
    private OnStateChangeListener mOnStateChangeListener;
    private SuggestionProvider mSuggestionProvider;

    private boolean mAllowEmptySearch = true;

    private boolean mInAnimation;

    private boolean showTranslation;

    private boolean isComeFromDownload = false;
    
    // 标志位：建议列表是否被手动控制
    private boolean mSuggestionsListManuallyControlled = false;

    // 标志位：左键功能固定为“搜索历史”（按钮功能恒定原则）
    private boolean mLeftButtonHistory = false;

    private static final int[] KEYWORD_GROUP_COLORS = {
            0xff3f51b5, 0xff008577, 0xff7b1fa2, 0xffef6c00, 0xff2e7d32
    };

    // Tag chip container and data
    private AutoWrapLayout mTagContainer;
    private final List<SearchTagChip> mTagChips = new ArrayList<>();

    // 关键字编辑器模型：重开编辑器时恢复分组结构（仅当当前文本与上次生成结果一致时启用）
    private List<KeywordItem> mKeywordEditorItems;
    private boolean mKeywordEditorGroupOr;
    private String mKeywordEditorLastQuery;

    public SearchBar(Context context) {
        super(context);
        init(context);
    }

    public SearchBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SearchBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        showTranslation = Settings.getShowTagTranslations();
        mSearchDatabase = SearchDatabase.getInstance(getContext());

        LayoutInflater inflater = LayoutInflater.from(context);
        inflater.inflate(R.layout.widget_search_bar, this);
        mMenuButton = (ImageView) ViewUtils.$$(this, R.id.search_menu);
        mTitleTextView = (TextView) ViewUtils.$$(this, R.id.search_title);
        mActionButton = (ImageView) ViewUtils.$$(this, R.id.search_action);
        mAdvanceButton = (ImageView) ViewUtils.$$(this, R.id.search_advance);
        mEditText = (SearchEditText) ViewUtils.$$(this, R.id.search_edit_text);
        mListContainer = ViewUtils.$$(this, R.id.list_container);
        mListView = (ListView) ViewUtils.$$(mListContainer, R.id.search_bar_list);
        mListHeader = ViewUtils.$$(mListContainer, R.id.list_header);
        mTagContainer = (AutoWrapLayout) ViewUtils.$$(this, R.id.search_tag_container);

        mViewTransition = new ViewTransition(mTitleTextView, mEditText);

        mTitleTextView.setOnClickListener(this);
        mMenuButton.setOnClickListener(this);
        mActionButton.setOnClickListener(this);
        mAdvanceButton.setOnClickListener(this);
        // 长按高级搜索按钮直接打开关键字编辑器
        mAdvanceButton.setOnLongClickListener(v -> {
            showKeywordEditor();
            return true;
        });
        mEditText.setSearchEditTextListener(this);
        mEditText.setOnEditorActionListener(this);
        mEditText.addTextChangedListener(this);
        
        // 添加焦点变化监听器
        mEditText.setOnFocusChangeListener((v, hasFocus) -> {
            // 如果建议列表被手动控制，则不受焦点变化影响
            if (mSuggestionsListManuallyControlled) {
                return;
            }

            if (hasFocus && mState == STATE_SEARCH) {
                // 获得焦点且当前是搜索状态，显示建议列表
                showImeAndSuggestionsList(true);
            } else if (!hasFocus && mState == STATE_SEARCH_LIST) {
                // 失去焦点且当前是搜索列表状态，切换到 SEARCH 状态（隐藏列表，但保留搜索模式）
                setState(STATE_SEARCH, false);
            }
        });

        // Get base height
        ViewUtils.measureView(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mBaseHeight = getMeasuredHeight();

        mSuggestionList = new ArrayList<>();
        mSuggestionAdapter = new SuggestionAdapter(LayoutInflater.from(getContext()));
        mListView.setAdapter(mSuggestionAdapter);
        mListView.setOnItemClickListener((parent, view, position, id) -> mSuggestionList.get(position).onClick());
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            mSuggestionList.get(position).onLongClick();
            return true;
        });
    }

    private void addListHeader() {
        mListHeader.setVisibility(VISIBLE);
    }

    private void updateSuggestionsToggleVisual() {
        if (mMenuButton == null || (mState != STATE_SEARCH && mState != STATE_SEARCH_LIST)) {
            return;
        }
        // 历史模式下左键图标固定为历史图标，不随列表显示状态旋转
        if (mLeftButtonHistory) {
            return;
        }
        boolean visible = mListContainer.getVisibility() == View.VISIBLE;
        mMenuButton.setRotation(visible ? 180f : 0f);
        mMenuButton.setContentDescription(getResources().getString(
                visible ? R.string.search_collapse_suggestions : R.string.search_expand_suggestions));
    }

    private void removeListHeader() {
        mListHeader.setVisibility(GONE);
    }

    private void updateSuggestions() {
        updateSuggestions(true);
    }

    private void updateSuggestions(boolean scrollToTop) {
        mSuggestionList.clear();
        Editable editable = mEditText.getText();
        String text = "";
        if (editable != null) {
            text = editable.toString();
        }


        if (mSuggestionProvider != null) {
            List<Suggestion> suggestions = mSuggestionProvider.providerSuggestions(text);
            if (suggestions != null && !suggestions.isEmpty()) {
                mSuggestionList.addAll(suggestions);
            }
        }

        String[] keywords = mSearchDatabase.getSuggestions(text, 128);
        for (String keyword : keywords) {
            mSuggestionList.add(new HistorySuggestion(keyword));
        }

        EhTagDatabase ehTagDatabase = EhTagDatabase.getInstance(getContext());
        if (!TextUtils.isEmpty(text) && ehTagDatabase != null) {
            String[] s = text.split(" ");
            if (s.length > 0) {
                // String keyword = s[s.length - 1];
                String keyword = "";
                for (int i = s.length - 1; i >= 0; i--) {
                    if (s[i].contains(":") || s[i].contains("$")) {
                        break;
                    } else {
                        if(keyword.isEmpty())
                            keyword = s[i];
                        else
                            keyword = s[i] + " " + keyword;
                    }
                }
                keyword = keyword.trim();

                if(!keyword.isEmpty()) 
                {
                    List<Pair<String, String>> searchHints = ehTagDatabase.suggest(keyword);
                    SearchDebugLog.d("SearchBar", "last-token suggest('" + keyword + "') -> " + searchHints.size()
                            + " (full query: '" + text + "')");

                    for (Pair<String, String> searchHint : searchHints) {
                        if (isTagAlreadyAdded(searchHint.second)) {
                            continue;
                        }
                        if (showTranslation) {
                            mSuggestionList.add(new TagSuggestion(searchHint.first, searchHint.second));
                        } else {
                            mSuggestionList.add(new TagSuggestion(null, searchHint.second));
                        }
                    }
                }
            }
        }

        if (mSuggestionList.size() == 0) {
            removeListHeader();
        } else {
            addListHeader();
        }
        mSuggestionAdapter.notifyDataSetChanged();

        if (scrollToTop) {
            mListView.setSelection(0);
        }
    }

    public void setAllowEmptySearch(boolean allowEmptySearch) {
        mAllowEmptySearch = allowEmptySearch;
    }

    public float getEditTextTextSize() {
        return mEditText.getTextSize();
    }

    public void setEditTextHint(CharSequence hint) {
        mEditText.setHint(hint);
    }

    public void setEditTextHint(int resId) {
        mEditText.setHint(resId);
    }

    public void setHelper(Helper helper) {
        mHelper = helper;
    }

    public void setOnStateChangeListener(OnStateChangeListener listener) {
        mOnStateChangeListener = listener;
    }

    public void setSuggestionProvider(SuggestionProvider suggestionProvider) {
        mSuggestionProvider = suggestionProvider;
    }

    public void setText(String text) {
        mEditText.setText(text);
    }

    public String getText() {
        Editable text = mEditText.getText();
        if (text != null) {
            return text.toString();
        }
        return null;
    }

    public void cursorToEnd() {
        Editable text = mEditText.getText();
        if (text != null) {
            mEditText.setSelection(mEditText.getText().length());
        }
    }

    public void setTitle(String title) {
        mTitleTextView.setText(title);
    }

    public void setTitle(int resId) {
        mTitleTextView.setText(resId);
    }

    public void setSearch(String search) {
        mTitleTextView.setText(search);
        mEditText.setText(search);
    }

    public void setLeftDrawable(Drawable drawable) {
        if (drawable == null) {
            mMenuButton.setVisibility(View.GONE);
        }
        mMenuButton.setImageDrawable(drawable);
    }

    public void setLeftDrawable(ImageView view) {
        if (view == null) {
            mMenuButton.setVisibility(View.GONE);
        }
        mMenuButton = view;
    }

    public void setRightDrawable(Drawable drawable) {
        mActionButton.setImageDrawable(drawable);
    }

    public void setLeftIconVisibility(int visibility) {
        mMenuButton.setVisibility(visibility);
    }

    public void setRightIconVisibility(int visibility) {
        mActionButton.setVisibility(visibility);
    }

    public void setIsComeFromDownload(boolean isComeFromDownload){
        this.isComeFromDownload = isComeFromDownload;
    }

    /**
     * 设置左键是否恒定展示/切换搜索历史列表。
     * 开启后：普通态点击左键进入搜索历史列表，搜索态点击左键切换历史/建议列表的显示与隐藏。
     */
    public void setLeftButtonHistory(boolean leftButtonHistory) {
        mLeftButtonHistory = leftButtonHistory;
    }

    public boolean isLeftButtonHistory() {
        return mLeftButtonHistory;
    }

    /**
     * 切换搜索历史列表的显示/隐藏。入口为左键（恒定功能），不受页面上下文的搜索状态影响。
     */
    public void toggleSearchHistory() {
        if (mState == STATE_NORMAL) {
            // 标题态：先进入搜索列表态再展示历史（编辑框为空时列表即历史）
            setState(STATE_SEARCH_LIST, true);
            showImeAndSuggestionsList(true);
            mSuggestionsListManuallyControlled = true;
        } else if (mState == STATE_SEARCH || mState == STATE_SEARCH_LIST) {
            mSuggestionsListManuallyControlled = true;
            if (mListContainer.getVisibility() == View.VISIBLE) {
                hideImeAndSuggestionsList(false);
            } else {
                showImeAndSuggestionsList(true);
            }
        }
    }

    private void applySearch() {
        String query = buildCombinedQuery();
        if (query.isEmpty()) {
            return;
        }
        if (!mAllowEmptySearch && TextUtils.isEmpty(query)) {
            return;
        }

        // Put it into db
        mSearchDatabase.addQuery(query);
        // Callback
        mHelper.onApplySearch(query);
        // Clear chips after search
        clearTagChips();
    }

    private static class KeywordItem {
        String text;
        int group;
        boolean excluded;
        boolean groupExcluded;

        KeywordItem(String text, int group, boolean excluded) {
            this.text = text;
            this.group = group;
            this.excluded = excluded;
        }
    }

    /**
     * 关键字输入的自动完成适配器。
     * 数据项为英文 tag key（如 {@code other:ai+generated}），显示时按 search_suggestion_item.xml
     * 两行渲染：第一行主文字（英文 key，正常大小加粗），第二行注释（中文翻译，更小更浅），
     * 无中文翻译时隐藏第二行。
     */
    private static class KeywordSuggestionAdapter extends ArrayAdapter<String> {
        private final List<String> mChineseList = new ArrayList<>();
        private final LayoutInflater mInflater;

        KeywordSuggestionAdapter(Context context) {
            super(context, 0);
            mInflater = LayoutInflater.from(context);
        }

        public void setData(List<Pair<String, String>> data) {
            clear();
            mChineseList.clear();
            for (Pair<String, String> pair : data) {
                add(pair.first);
                mChineseList.add(pair.second);
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.search_suggestion_item, parent, false);
            }
            TextView hintView = convertView.findViewById(R.id.hintView);
            TextView textView = convertView.findViewById(R.id.textView);
            hintView.setText(getItem(position));
            String chinese = position < mChineseList.size() ? mChineseList.get(position) : null;
            if (chinese != null && !chinese.isEmpty()) {
                textView.setVisibility(View.VISIBLE);
                textView.setText(chinese);
            } else {
                textView.setVisibility(View.GONE);
            }
            return convertView;
        }
    }

    public void showKeywordEditor() {
        final List<KeywordItem> items;
        String current = buildCombinedQuery();
        if (mKeywordEditorItems != null && mKeywordEditorLastQuery != null
                && mKeywordEditorLastQuery.equals(current)) {
            // 上次由编辑器生成的查询原样保留，恢复其分组结构
            items = copyItems(mKeywordEditorItems);
        } else {
            items = parseKeywords(current);
        }
        // 组间关系（AND / OR）初始值取自上次保存的模型
        final boolean[] groupOr = {mKeywordEditorGroupOr};
        final LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (getResources().getDisplayMetrics().density * 16);
        root.setPadding(padding, padding / 2, padding, 0);

        LinearLayout addRow = new LinearLayout(getContext());
        addRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final AutoCompleteTextView input = createKeywordInput();
        input.setSingleLine(true);
        input.setHint(R.string.search_keyword_input_hint);
        addRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button addButton = new Button(getContext());
        addButton.setText(R.string.search_keyword_add);
        addRow.addView(addButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(addRow);

        // 组与组关系切换 + 表达式预览
        LinearLayout relationRow = new LinearLayout(getContext());
        relationRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        relationRow.setPadding(0, padding / 2, 0, padding / 2);
        final Button relationBtn = new Button(getContext());
        relationBtn.setAllCaps(false);
        updateGroupRelationButton(relationBtn, groupOr);
        relationBtn.setOnClickListener(v -> {
            groupOr[0] = !groupOr[0];
            updateGroupRelationButton(relationBtn, groupOr);
            SearchDebugLog.d("SearchBar", "keyword-editor group relation -> " + (groupOr[0] ? "OR" : "AND"));
        });
        relationRow.addView(relationBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button previewBtn = new Button(getContext());
        previewBtn.setAllCaps(false);
        previewBtn.setText(R.string.search_group_preview);
        previewBtn.setOnClickListener(v -> showKeywordEditorPreview(items, groupOr[0]));
        relationRow.addView(previewBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(relationRow);

        final LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(getContext());
        scroll.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        int editorHeight = (int) (getResources().getDisplayMetrics().density * 480);
        scrollParams.height = editorHeight - padding * 4;
        scrollParams.weight = 0;
        root.addView(scroll, scrollParams);

        // 列表刷新回调（在语言/书签区前声明，供快捷书签点击等复用）
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                if (i == 0 || items.get(i).group != items.get(i - 1).group) {
                    addGroupSeparator(list, items, i, refresh[0]);
                }
                addKeywordRow(list, items, i, refresh[0]);
            }
            if (items.isEmpty()) {
                TextView empty = new TextView(getContext());
                empty.setText(R.string.search_keyword_empty);
                empty.setPadding(0, padding, 0, padding);
                list.addView(empty);
            }
        };

        // --- 语言快捷标签 ---
        final String[] selectedLanguage = {null};
        final Map<String, TextView> langChips = new HashMap<>();
        TextView langLabel = new TextView(getContext());
        langLabel.setText(R.string.search_language_label);
        langLabel.setPadding(0, padding / 2, 0, 2);
        root.addView(langLabel);
        AutoWrapLayout langWrap = new AutoWrapLayout(getContext());
        addLanguageChip(langWrap, getResources().getString(R.string.search_language_clear), null,
                selectedLanguage, langChips);
        for (String[] lang : SearchLayout.LANGUAGE_TAGS) {
            addLanguageChip(langWrap, lang[0], lang[1], selectedLanguage, langChips);
        }
        root.addView(langWrap);

        // --- 快捷搜索书签（全部显示；内容长时由外层 ScrollView 整体滚动，小屏不会溢出） ---
        final List<QuickSearch> quickSearches = EhDB.getAllQuickSearch();
        if (quickSearches != null && !quickSearches.isEmpty()) {
            TextView quickLabel = new TextView(getContext());
            quickLabel.setText(R.string.search_quick_search_label);
            quickLabel.setPadding(0, padding / 2, 0, 2);
            root.addView(quickLabel);
            AutoWrapLayout quickWrap = new AutoWrapLayout(getContext());
            quickWrap.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (final QuickSearch quickSearch : quickSearches) {
                final String name = (quickSearch.getName() != null && !quickSearch.getName().isEmpty())
                        ? quickSearch.getName() : quickSearch.getKeyword();
                TextView qs = makeTagChipText(name);
                qs.setOnClickListener(v -> {
                    String kw = quickSearch.getKeyword();
                    if (kw != null && !kw.isEmpty()) {
                        boolean excluded = kw.startsWith("-");
                        String clean = excluded ? kw.substring(1) : kw;
                        items.add(new KeywordItem(clean,
                                items.isEmpty() ? 0 : items.get(items.size() - 1).group, excluded));
                        refresh[0].run();
                    }
                });
                quickWrap.addView(qs);
            }
            root.addView(quickWrap);
        }

        addButton.setOnClickListener(v -> {
            String text = normalizeKeyword(input.getText().toString());
            if (isValidKeyword(text)) {
                items.add(new KeywordItem(text, items.isEmpty() ? 0 : items.get(items.size() - 1).group, false));
                input.setText("");
                refresh[0].run();
            }
        });
        refresh[0].run();

        // 整个编辑器内容包一层外层 ScrollView：书签/语言标签等超出屏幕时整页可滚动
        ScrollView outerScroll = new ScrollView(getContext());
        outerScroll.setVerticalScrollBarEnabled(false);
        outerScroll.addView(root);
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(R.string.search_keyword_editor)
                .setView(outerScroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String query = buildKeywordQuery(items, groupOr[0]);
            if (selectedLanguage[0] != null && !selectedLanguage[0].isEmpty()) {
                query = query.isEmpty() ? "language:" + selectedLanguage[0]
                        : query + " language:" + selectedLanguage[0];
            }
            if (query.isEmpty()) {
                return;
            }
            // 保存分组模型，重开编辑器时可恢复组结构
            mKeywordEditorItems = copyItems(items);
            mKeywordEditorGroupOr = groupOr[0];
            mKeywordEditorLastQuery = query;
            clearTagChips();
            setText(query);
            dialog.dismiss();
            applySearch(true);
        }));
        dialog.show();
    }

    private void addLanguageChip(AutoWrapLayout parent, String display, String tag,
                                 String[] selected, Map<String, TextView> views) {
        TextView tv = makeTagChipText(display);
        tv.setOnClickListener(v -> {
            if (tag == null) {
                selected[0] = null;
            } else if (tag.equals(selected[0])) {
                selected[0] = null;
            } else {
                selected[0] = tag;
            }
            refreshLanguageChipStyles(views, selected[0]);
        });
        views.put(tag == null ? "" : tag, tv);
        parent.addView(tv);
    }

    private void refreshLanguageChipStyles(Map<String, TextView> views, String selected) {
        int accent = AttrResources.getAttrColor(getContext(), R.attr.widgetColorThemeAccent);
        int density = (int) getResources().getDisplayMetrics().density;
        int strokeColor = AttrResources.getAttrColor(getContext(), R.attr.drawableColorSecondary);
        int unselectedColor = AttrResources.getAttrColor(getContext(), R.attr.drawableColorPrimary);
        for (Map.Entry<String, TextView> entry : views.entrySet()) {
            boolean isSelected = !entry.getKey().isEmpty() && entry.getKey().equals(selected);
            TextView tv = entry.getValue();
            tv.setTextColor(isSelected ? android.graphics.Color.WHITE : unselectedColor);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(density * 4);
            bg.setStroke(density, strokeColor);
            bg.setColor(isSelected ? accent : getUnselectedChipColor());
            tv.setBackground(bg);
        }
    }

    private int getUnselectedChipColor() {
        if (AttrResources.getAttrBoolean(getContext(), androidx.appcompat.R.attr.isLightTheme)) {
            return 0x14000000;
        } else {
            return 0x14FFFFFF;
        }
    }

    private TextView makeTagChipText(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int density = (int) getResources().getDisplayMetrics().density;
        tv.setPadding(density * 8, density * 4, density * 8, density * 4);
        tv.setClickable(true);
        tv.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(density * 4);
        int strokeColor = AttrResources.getAttrColor(getContext(), R.attr.drawableColorSecondary);
        bg.setStroke(density, strokeColor);
        bg.setColor(getUnselectedChipColor());
        tv.setBackground(bg);
        tv.setTextColor(AttrResources.getAttrColor(getContext(), R.attr.drawableColorPrimary));
        return tv;
    }

    private void addKeywordRow(LinearLayout list, List<KeywordItem> items, int index, Runnable refresh) {
        KeywordItem item = items.get(index);
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(8, 4, 0, 4);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x18000000 | (KEYWORD_GROUP_COLORS[item.group % KEYWORD_GROUP_COLORS.length] & 0x00ffffff));
        background.setStroke(4, KEYWORD_GROUP_COLORS[item.group % KEYWORD_GROUP_COLORS.length]);
        row.setBackground(background);

        TextView text = new TextView(getContext());
        text.setText((item.excluded ? getResources().getString(R.string.search_keyword_not)
                : getResources().getString(R.string.search_keyword_and)) + "  " + item.text);
        text.setTextColor(KEYWORD_GROUP_COLORS[item.group % KEYWORD_GROUP_COLORS.length]);
        text.setSingleLine(true);
        text.setOnLongClickListener(v -> {
            showKeywordItemEditor(item, refresh);
            return true;
        });
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button relation = smallButton(item.excluded ? getResources().getString(R.string.search_keyword_not)
                : getResources().getString(R.string.search_keyword_and));
        relation.setContentDescription(getResources().getString(R.string.search_keyword_toggle_relation));
        relation.setOnClickListener(v -> {
            item.excluded = !item.excluded;
            refresh.run();
        });
        row.addView(relation);

        Button up = smallButton("↑");
        up.setEnabled(index > 0);
        up.setOnClickListener(v -> {
            Collections.swap(items, index, index - 1);
            refresh.run();
        });
        row.addView(up);
        Button down = smallButton("↓");
        down.setEnabled(index + 1 < items.size());
        down.setOnClickListener(v -> {
            Collections.swap(items, index, index + 1);
            refresh.run();
        });
        row.addView(down);
        Button group = smallButton("G");
        group.setContentDescription(getResources().getString(R.string.search_keyword_change_group));
        group.setOnClickListener(v -> {
            item.group = (item.group + 1) % KEYWORD_GROUP_COLORS.length;
            refresh.run();
        });
        row.addView(group);
        Button delete = smallButton("×");
        delete.setOnClickListener(v -> {
            items.remove(index);
            refresh.run();
        });
        row.addView(delete);
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addGroupSeparator(LinearLayout list, List<KeywordItem> items, int index, Runnable refresh) {
        KeywordItem item = items.get(index);
        TextView separator = new TextView(getContext());
        separator.setText(getResources().getString(R.string.search_keyword_group_separator,
                item.group + 1, item.groupExcluded ? getResources().getString(R.string.search_keyword_not)
                        : getResources().getString(R.string.search_keyword_and)));
        separator.setTextColor(KEYWORD_GROUP_COLORS[item.group % KEYWORD_GROUP_COLORS.length]);
        separator.setPadding(8, 12, 8, 4);
        separator.setOnClickListener(v -> {
            item.groupExcluded = !item.groupExcluded;
            refresh.run();
        });
        list.addView(separator);
    }

    private Button smallButton(String text) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(8, 0, 8, 0);
        return button;
    }

    private void showKeywordItemEditor(KeywordItem item, Runnable refresh) {
        AutoCompleteTextView input = createKeywordInput();
        input.setSingleLine(true);
        input.setText(item.text);
        input.setSelection(input.length());
        LinearLayout editor = new LinearLayout(getContext());
        editor.setPadding(32, 0, 32, 0);
        editor.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.search_keyword_edit)
                .setView(editor)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = normalizeKeyword(input.getText().toString());
                    if (isValidKeyword(text)) {
                        item.text = text;
                        refresh.run();
                    }
                }).show();
    }

    private AutoCompleteTextView createKeywordInput() {
        AutoCompleteTextView input = new AutoCompleteTextView(getContext());
        KeywordSuggestionAdapter adapter = new KeywordSuggestionAdapter(getContext());
        input.setAdapter(adapter);
        input.setThreshold(1);
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) updateKeywordSuggestions(input, adapter);
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateKeywordSuggestions(input, adapter);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        input.setOnItemClickListener((parent, view, position, id) -> {
            String suggestion = adapter.getItem(position);
            if (suggestion != null) {
                input.setText(suggestion);
                input.setSelection(input.length());
            }
        });
        return input;
    }

    private void updateKeywordSuggestions(AutoCompleteTextView input, KeywordSuggestionAdapter adapter) {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        String lookup = text.startsWith("-") ? text.substring(1) : text;
        List<Pair<String, String>> data = new ArrayList<>();
        EhTagDatabase database = EhTagDatabase.getInstance(getContext());
        if (database != null && !lookup.isEmpty()) {
            String prefix = text.startsWith("-") ? "-" : "";
            List<Pair<String, String>> suggestions = database.suggest(lookup);
            for (Pair<String, String> suggestion : suggestions) {
                // suggest() 返回 Pair(中文翻译, 英文key)
                String key = suggestion.second;
                String chinese = suggestion.first;
                if (key != null && !key.isEmpty() && !key.equals(lookup)) {
                    if (chinese == null || "null".equals(chinese)) {
                        chinese = "";
                    }
                    data.add(new Pair<>(prefix + key, chinese));
                }
            }
        }
        adapter.setData(data);
        SearchDebugLog.d("SearchBar", "keyword-editor suggest('" + lookup + "') -> " + data.size() + " suggestions");
        if (adapter.getCount() > 0 && input.hasFocus()) input.showDropDown();
    }

    private List<KeywordItem> parseKeywords(String query) {
        List<KeywordItem> result = new ArrayList<>();
        if (TextUtils.isEmpty(query)) return result;
        boolean quoted = false;
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '"') quoted = !quoted;
            if (Character.isWhitespace(c) && !quoted) {
                if (token.length() > 0) {
                    String value = normalizeKeyword(token.toString());
                    result.add(new KeywordItem(value.startsWith("-") ? value.substring(1) : value,
                            0, value.startsWith("-")));
                    token.setLength(0);
                }
            } else token.append(c);
        }
        if (token.length() > 0) {
            String value = normalizeKeyword(token.toString());
            result.add(new KeywordItem(value.startsWith("-") ? value.substring(1) : value,
                    0, value.startsWith("-")));
        }
        return result;
    }

    private String normalizeKeyword(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private boolean isValidKeyword(String value) {
        if (TextUtils.isEmpty(value)) return false;
        int quotes = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '"') quotes++;
        return quotes % 2 == 0;
    }

    /**
     * 按组生成最终搜索表达式。
     * 组内：空格=AND，排除词带 - 前缀；含空格的词自动加引号（短语）。
     * 组间：AND 模式直接空格连接；OR 模式每组用括号包裹并用 OR 连接。
     */
    private String buildKeywordQuery(List<KeywordItem> items, boolean groupOr) {
        List<String> groupStrings = groupRender(items);
        if (groupStrings.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        if (groupOr && groupStrings.size() > 1) {
            for (int i = 0; i < groupStrings.size(); i++) {
                if (result.length() > 0) {
                    result.append(" OR ");
                }
                result.append('(').append(groupStrings.get(i)).append(')');
            }
        } else {
            for (int i = 0; i < groupStrings.size(); i++) {
                if (result.length() > 0) {
                    result.append(' ');
                }
                result.append(groupStrings.get(i));
            }
        }
        SearchDebugLog.d("SearchBar", "keyword-editor buildKeywordQuery(groupOr=" + groupOr
                + ") -> '" + result + "'");
        return result.toString();
    }

    /** 把条目按组（保持首次出现顺序）渲染成字符串列表。 */
    private List<String> groupRender(List<KeywordItem> items) {
        List<String> groups = new ArrayList<>();
        Map<Integer, Boolean> excludedGroups = new HashMap<>();
        for (KeywordItem item : items) {
            if (!excludedGroups.containsKey(item.group)) {
                excludedGroups.put(item.group, item.groupExcluded);
            }
        }
        List<Integer> groupOrder = new ArrayList<>();
        for (KeywordItem item : items) {
            if (!groupOrder.contains(item.group)) {
                groupOrder.add(item.group);
            }
        }
        for (int g : groupOrder) {
            StringBuilder gb = new StringBuilder();
            for (KeywordItem item : items) {
                if (item.group != g) {
                    continue;
                }
                String keyword = normalizeKeyword(item.text);
                if (!isValidKeyword(keyword)) {
                    continue;
                }
                String clean = keyword.startsWith("-") ? keyword.substring(1) : keyword;
                clean = translateCjkTag(clean);
                clean = quoteIfNeeded(clean);
                if (gb.length() > 0) {
                    gb.append(' ');
                }
                if ((item.excluded || Boolean.TRUE.equals(excludedGroups.get(g)))) {
                    gb.append('-');
                }
                gb.append(clean);
            }
            if (gb.length() > 0) {
                groups.add(gb.toString());
            }
        }
        return groups;
    }

    /** 中文/日文（CJK 或假名/谚文）词自动翻译成规范英文标签，如 明日方舟 -> parody:arknights。 */
    private String translateCjkTag(String keyword) {
        if (keyword == null || keyword.isEmpty() || !containsCjk(keyword)) {
            return keyword;
        }
        EhTagDatabase db = EhTagDatabase.getInstance(getContext());
        if (db == null) {
            return keyword;
        }
        String resolved = db.resolveTagTerm(keyword);
        if (resolved != null && !resolved.isEmpty()) {
            SearchDebugLog.d("SearchBar", "keyword-editor CJK translate '" + keyword + "' -> '" + resolved + "'");
            return resolved;
        }
        return keyword;
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '\u4e00' && c <= '\u9fff')
                    || (c >= '\u3400' && c <= '\u4dbf')
                    || (c >= '\u3040' && c <= '\u30ff')
                    || (c >= '\uac00' && c <= '\ud7af')) {
                return true;
            }
        }
        return false;
    }

    /** 含空格且未被引号包裹的词补上双引号，保证短语语义两端一致。 */
    private String quoteIfNeeded(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s;
        }
        if (s.contains(" ")) {
            return "\"" + s + "\"";
        }
        return s;
    }

    private List<KeywordItem> copyItems(List<KeywordItem> src) {
        List<KeywordItem> dst = new ArrayList<>();
        if (src != null) {
            for (KeywordItem item : src) {
                KeywordItem copy = new KeywordItem(item.text, item.group, item.excluded);
                copy.groupExcluded = item.groupExcluded;
                dst.add(copy);
            }
        }
        return dst;
    }

    private void updateGroupRelationButton(Button btn, boolean[] groupOr) {
        btn.setText(getResources().getString(R.string.search_group_relation,
                getResources().getString(groupOr[0] ? R.string.search_group_or : R.string.search_group_and)));
    }

    /** 人可读的分组解释：每组列出 + / - 词条并翻译为中文（如可解析）。 */
    private String buildKeywordExplanation(List<KeywordItem> items, boolean groupOr) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        List<Integer> groupOrder = new ArrayList<>();
        for (KeywordItem item : items) {
            if (!groupOrder.contains(item.group)) {
                groupOrder.add(item.group);
            }
        }
        Map<Integer, Boolean> excludedGroups = new HashMap<>();
        for (KeywordItem item : items) {
            if (!excludedGroups.containsKey(item.group)) {
                excludedGroups.put(item.group, item.groupExcluded);
            }
        }
        EhTagDatabase db = EhTagDatabase.getInstance(getContext());
        StringBuilder sb = new StringBuilder();
        for (int g : groupOrder) {
            boolean gExcluded = Boolean.TRUE.equals(excludedGroups.get(g));
            sb.append(getResources().getString(R.string.search_group_label, g + 1)).append(": ")
                    .append(getResources().getString(gExcluded ? R.string.search_keyword_not
                            : R.string.search_keyword_and)).append('\n');
            for (KeywordItem item : items) {
                if (item.group != g) {
                    continue;
                }
                boolean excluded = item.excluded || gExcluded;
                String text = normalizeKeyword(item.text);
                String resolved = db != null ? db.resolveTagTerm(text) : null;
                String chinese = null;
                if (resolved != null && db != null) {
                    chinese = db.getTranslation(resolved);
                    if (chinese == null || "null".equals(chinese)) {
                        chinese = null;
                    }
                }
                sb.append(excluded ? "  - " : "  + ");
                if (chinese != null && !chinese.isEmpty() && resolved != null) {
                    sb.append(chinese).append(" (").append(resolved).append(')');
                } else {
                    sb.append(quoteIfNeeded(text));
                }
                sb.append('\n');
            }
        }
        sb.append(getResources().getString(R.string.search_group_relation)).append(' ')
                .append(getResources().getString(groupOr ? R.string.search_group_or : R.string.search_group_and));
        return sb.toString();
    }

    private void showKeywordEditorPreview(List<KeywordItem> items, boolean groupOr) {
        String expr = buildKeywordQuery(items, groupOr);
        String explanation = buildKeywordExplanation(items, groupOr);
        String message;
        if (items == null || items.isEmpty()) {
            message = getResources().getString(R.string.search_group_none);
        } else {
            message = getResources().getString(R.string.search_group_expression_title) + "\n" + expr
                    + "\n\n" + getResources().getString(R.string.search_group_explanation_title) + "\n" + explanation;
        }
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.search_group_preview_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        SearchDebugLog.d("SearchBar", "keyword-editor preview:\n" + expr + "\n" + explanation);
    }

    public void applySearch(boolean hideKeyboard) {
        if (hideKeyboard) {
            hideKeyBoard();
        }
        applySearch();
    }

    public void hideKeyBoard() {
        InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        manager.hideSoftInputFromWindow(this.getWindowToken(), 0);
    }

    @Override
    public void onClick(View v) {
        if (v == mTitleTextView) {
            mHelper.onClickTitle();
        } else if (v == mMenuButton) {
            if (mLeftButtonHistory) {
                // 恒定功能：左键切换搜索历史列表
                toggleSearchHistory();
            } else if (mState == STATE_SEARCH || mState == STATE_SEARCH_LIST) {
                // 在搜索状态下，点击菜单按钮切换建议列表的显示/隐藏
                toggleSuggestionsList();
            } else {
                mHelper.onClickLeftIcon();
            }
        } else if (v == mActionButton) {
            mHelper.onClickRightIcon();
        } else if (v == mAdvanceButton) {
            // 恒定功能：跳转到高级搜索面板
            mHelper.onClickAdvance();
        }
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (v == mEditText) {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_NULL) {
                applySearch();
                return true;
            }
        }
        return false;
    }


    public int getState() {
        return mState;
    }

    public void setState(int state) {
        setState(state, true);
    }

    public void setState(int state, boolean animation) {
        if (mState != state) {
            int oldState = mState;
            mState = state;
            
            // 状态改变时重置手动控制标志
            mSuggestionsListManuallyControlled = false;

            // Clear chips when leaving search mode
            if (state == STATE_NORMAL) {
                clearTagChips();
            }

            // Show keyword editor while the search field is active.
            if (mAdvanceButton != null) {
                mAdvanceButton.setVisibility(state == STATE_NORMAL ? View.GONE : View.VISIBLE);
            }

            switch (oldState) {
                default:
                case STATE_NORMAL:
                    mViewTransition.showView(1, animation);
                    mEditText.requestFocus();

                    // 移除自动显示建议列表的代码，等待焦点事件触发
                    if (mOnStateChangeListener != null) {
                        mOnStateChangeListener.onStateChange(this, state, oldState, animation);
                    }
                    break;
                case STATE_SEARCH:
                    if (state == STATE_NORMAL) {
                        mViewTransition.showView(0, animation);
                    } else if (state == STATE_SEARCH_LIST) {
                        showImeAndSuggestionsList(animation);
                    }
                    if (mOnStateChangeListener != null) {
                        mOnStateChangeListener.onStateChange(this, state, oldState, animation);
                    }
                    break;
                case STATE_SEARCH_LIST:
                    hideImeAndSuggestionsList(animation);
                    if (state == STATE_NORMAL) {
                        mViewTransition.showView(0, animation);
                    }
                    if (mOnStateChangeListener != null) {
                        mOnStateChangeListener.onStateChange(this, state, oldState, animation);
                    }
                    break;
            }
        }
    }

    public void showImeAndSuggestionsList() {
        showImeAndSuggestionsList(true);
    }


    public void showImeAndSuggestionsList(boolean animation) {
        // Show ime
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(mEditText, 0);
        // update suggestion for show suggestions list
        updateSuggestions();
        // Show suggestions list
        if (animation) {
            ObjectAnimator oa = ObjectAnimator.ofFloat(this, "progress", 1f);
            oa.setDuration(ANIMATE_TIME);
            oa.setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR);
            oa.addListener(new SimpleAnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mListContainer.setVisibility(View.VISIBLE);
                    mInAnimation = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mInAnimation = false;
                }
            });
            oa.setAutoCancel(true);
            oa.start();
        } else {
            mListContainer.setVisibility(View.VISIBLE);
            setProgress(1f);
        }
        updateSuggestionsToggleVisual();
    }

    private void hideImeAndSuggestionsList() {
        hideImeAndSuggestionsList(true);
    }

    private void hideImeAndSuggestionsList(boolean animation) {
        // Hide ime
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(this.getWindowToken(), 0);
        // Hide suggestions list
        if (animation) {
            ObjectAnimator oa = ObjectAnimator.ofFloat(this, "progress", 0f);
            oa.setDuration(ANIMATE_TIME);
            oa.setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR);
            oa.addListener(new SimpleAnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mInAnimation = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mListContainer.setVisibility(View.GONE);
                    mInAnimation = false;
                }
            });
            oa.setAutoCancel(true);
            oa.start();
        } else {
            setProgress(0f);
            mListContainer.setVisibility(View.GONE);
        }
        updateSuggestionsToggleVisual();
    }

    // 公共方法：隐藏建议列表
    public void hideSuggestionsList() {
        hideImeAndSuggestionsList(false);
    }
    
    // 公共方法：切换建议列表的显示/隐藏
    public void toggleSuggestionsList() {
        if (mState != STATE_SEARCH && mState != STATE_SEARCH_LIST) {
            return;
        }
        
        mSuggestionsListManuallyControlled = true;
        
        if (mListContainer.getVisibility() == View.VISIBLE) {
            // 当前显示，则隐藏
            hideImeAndSuggestionsList(false);
        } else {
            // 当前隐藏，则显示
            showImeAndSuggestionsList(true);
        }
    }
    
    // 公共方法：检查建议列表是否可见
    public boolean isSuggestionsListVisible() {
        return mListContainer.getVisibility() == View.VISIBLE;
    }
    
    // 公共方法：重置手动控制状态，恢复自动行为
    public void resetManualControl() {
        mSuggestionsListManuallyControlled = false;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mListContainer.getVisibility() == View.VISIBLE) {
            mWidth = right - left;
            mHeight = bottom - top;
        }
    }

    @SuppressWarnings("unused")
    public void setProgress(float progress) {
        mProgress = progress;
        invalidate();
    }

    @SuppressWarnings("unused")
    public float getProgress() {
        return mProgress;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mInAnimation) {
            final int state = canvas.save();
            int bottom = MathUtils.lerp(mBaseHeight, mHeight, mProgress);
            mRect.set(0, 0, mWidth, bottom);
            canvas.clipRect(mRect);
            super.draw(canvas);
            canvas.restoreToCount(state);
        } else {
            super.draw(canvas);
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // Empty
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // Empty
    }

    @Override
    public void afterTextChanged(Editable s) {
        updateSuggestions();
    }

    @Override
    public void onClick() {
        mHelper.onSearchEditTextClick();
    }

    @Override
    public void onBackPressed() {
        mHelper.onSearchEditTextBackPressed();
    }

    @Override
    public Parcelable onSaveInstanceState() {
        final Bundle state = new Bundle();
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState());
        state.putInt(STATE_KEY_STATE, mState);
        if (mKeywordEditorItems != null) {
            int size = mKeywordEditorItems.size();
            String[] texts = new String[size];
            int[] groups = new int[size];
            boolean[] excluded = new boolean[size];
            boolean[] groupExcluded = new boolean[size];
            for (int i = 0; i < size; i++) {
                KeywordItem item = mKeywordEditorItems.get(i);
                texts[i] = item.text;
                groups[i] = item.group;
                excluded[i] = item.excluded;
                groupExcluded[i] = item.groupExcluded;
            }
            state.putStringArray(STATE_KEY_EDITOR_TEXTS, texts);
            state.putIntArray(STATE_KEY_EDITOR_GROUPS, groups);
            state.putBooleanArray(STATE_KEY_EDITOR_EXCLUDED, excluded);
            state.putBooleanArray(STATE_KEY_EDITOR_GROUP_EXCLUDED, groupExcluded);
            state.putBoolean(STATE_KEY_EDITOR_GROUP_OR, mKeywordEditorGroupOr);
            state.putString(STATE_KEY_EDITOR_LAST_QUERY, mKeywordEditorLastQuery);
        }
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            final Bundle savedState = (Bundle) state;
            super.onRestoreInstanceState(savedState.getParcelable(STATE_KEY_SUPER));
            setState(savedState.getInt(STATE_KEY_STATE), false);
            if (savedState.containsKey(STATE_KEY_EDITOR_TEXTS)) {
                String[] texts = savedState.getStringArray(STATE_KEY_EDITOR_TEXTS);
                int[] groups = savedState.getIntArray(STATE_KEY_EDITOR_GROUPS);
                boolean[] excluded = savedState.getBooleanArray(STATE_KEY_EDITOR_EXCLUDED);
                boolean[] groupExcluded = savedState.getBooleanArray(STATE_KEY_EDITOR_GROUP_EXCLUDED);
                if (texts != null) {
                    mKeywordEditorItems = new ArrayList<>();
                    for (int i = 0; i < texts.length; i++) {
                        KeywordItem item = new KeywordItem(texts[i],
                                groups != null && i < groups.length ? groups[i] : 0,
                                excluded != null && i < excluded.length && excluded[i]);
                        item.groupExcluded = groupExcluded != null && i < groupExcluded.length
                                && groupExcluded[i];
                        mKeywordEditorItems.add(item);
                    }
                    mKeywordEditorGroupOr = savedState.getBoolean(STATE_KEY_EDITOR_GROUP_OR, false);
                    mKeywordEditorLastQuery = savedState.getString(STATE_KEY_EDITOR_LAST_QUERY);
                }
            }
        }
    }

    public interface Helper {
        void onClickTitle();

        void onClickLeftIcon();

        void onClickRightIcon();

        void onClickAdvance();

        void onSearchEditTextClick();

        void onApplySearch(String query);

        void onSearchEditTextBackPressed();
    }

    public interface OnStateChangeListener {

        void onStateChange(SearchBar searchBar, int newState, int oldState, boolean animation);
    }

    public interface SuggestionProvider {

        List<Suggestion> providerSuggestions(String text);
    }

    public abstract static class Suggestion {

        public abstract CharSequence getText(float textSize);

        public abstract CharSequence getText(TextView textView);

        public abstract void onClick();

        public abstract void onLongClick();
    }

    private class SuggestionAdapter extends BaseAdapter {
        private final LayoutInflater inflater;

        public SuggestionAdapter(LayoutInflater inflater) {
            this.inflater = inflater;
        }

        @Override
        public int getCount() {
            return mSuggestionList.size();
        }

        @Override
        public Object getItem(int position) {
            return mSuggestionList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout linearLayout;
            if (convertView == null) {
                linearLayout = (LinearLayout) inflater.inflate(R.layout.search_suggestion_item, parent, false);
            } else {
//                return convertView;
                linearLayout = (LinearLayout) convertView;
            }
            TextView hintView = linearLayout.findViewById(R.id.hintView);
            TextView textView = linearLayout.findViewById(R.id.textView);

            Suggestion suggestion = mSuggestionList.get(position);

            String hint = (String) suggestion.getText(hintView);
            String text = (String) suggestion.getText(textView);

            hintView.setText(hint);

            if (text == null || text.isEmpty()) {
                textView.setVisibility(GONE);
            } else {
                textView.setVisibility(View.VISIBLE);
                textView.setText(text);
            }

            return linearLayout;
        }
    }

    private static class SearchTagChip {
        String displayName;
        String searchKey;
        View chipView;

        SearchTagChip(String displayName, String searchKey) {
            this.displayName = displayName;
            this.searchKey = searchKey;
        }
    }

    private void addTagChip(String displayName, String searchKey) {
        // Deduplicate
        for (SearchTagChip chip : mTagChips) {
            if (chip.searchKey.equals(searchKey)) {
                return;
            }
        }
        int colorTag = AttrResources.getAttrColor(getContext(), R.attr.tagBackgroundColor);
        Chip chip = (Chip) LayoutInflater.from(getContext()).inflate(R.layout.item_chip_tag, mTagContainer, false);
        chip.setText(displayName);
        chip.setChipBackgroundColor(ColorStateList.valueOf(colorTag));
        chip.setTextColor(android.graphics.Color.WHITE);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> removeTagChip(chip));
        mTagContainer.addView(chip);
        SearchTagChip tagChip = new SearchTagChip(displayName, searchKey);
        tagChip.chipView = chip;
        mTagChips.add(tagChip);
        mTagContainer.setVisibility(View.VISIBLE);
    }

    private void removeTagChip(Chip chipView) {
        mTagContainer.removeView(chipView);
        SearchTagChip toRemove = null;
        for (SearchTagChip tc : mTagChips) {
            if (tc.chipView == chipView) {
                toRemove = tc;
                break;
            }
        }
        if (toRemove != null) {
            mTagChips.remove(toRemove);
        }
        if (mTagChips.isEmpty()) {
            mTagContainer.setVisibility(View.GONE);
        }
    }

    public void clearTagChips() {
        mTagContainer.removeAllViews();
        mTagChips.clear();
        mTagContainer.setVisibility(View.GONE);
    }

    private String buildCombinedQuery() {
        StringBuilder sb = new StringBuilder();
        for (SearchTagChip chip : mTagChips) {
            sb.append(chip.searchKey).append(" ");
        }
        Editable editable = mEditText.getText();
        if (editable != null && editable.length() > 0) {
            sb.append(editable.toString().trim());
        }
        return sb.toString().trim();
    }

    /**
     * Returns a set of search keys currently in chips to exclude from suggestions
     */
    private boolean isTagAlreadyAdded(String englishKey) {
        for (SearchTagChip chip : mTagChips) {
            if (chip.searchKey.equals(englishKey)) {
                return true;
            }
        }
        return false;
    }

    private class TagSuggestion extends Suggestion {
        public String show, mKeyword;

        public TagSuggestion(String show, String mKeyword) {
            this.show = show;
            this.mKeyword = mKeyword;
        }

        @Override
        public CharSequence getText(float textSize) {
            return null;
        }

        @Override
        public CharSequence getText(TextView textView) {
            if (textView.getId() == R.id.hintView) {
                return mKeyword;
            }
            return show;
        }

        /**
         * 无法替换中文
         * @param text1
         * @param text2
         * @return
         */
        public String removeCommonSubstring(String text1, String text2) {
            int m = text1.length();
            int n = text2.length();
            String match = "";

            for (int i = m - 1; i >= 0; i--) {
                String tmp = text1.substring(i, m);
                if (!text2.contains(tmp)) {
                    break;
                } else {
                    match = tmp;
                }
            }

            String result = text1.substring(0, m - match.length());
            return result;
        }

        public String replaceCommonSubstring(String tagKey, Editable editable) {
            String key = tagKey;
            if (editable.toString().contains(" ")) {
                StringBuilder builder = new StringBuilder(editable);
                char c = ' ';
                while (builder.charAt(builder.length() - 1) != c) {
                    builder.deleteCharAt(builder.length() - 1);
                }

                while (builder.length() != 0 && builder.charAt(builder.length() - 1) == c) {
                    builder.deleteCharAt(builder.length() - 1);
                }

                builder.append("  ").append(tagKey);
                key = builder.toString();
            }
            return key;
        }

        @Override
        public void onClick() {
            String searchKey = rebuildKeyword(mKeyword);
            String displayName = show != null && !show.isEmpty() ? show : mKeyword;
            addTagChip(displayName, searchKey);
            mEditText.setText("");
            mEditText.requestFocus();
            updateSuggestions();
        }

        private String rebuildKeyword(String key) {
            String[] strings = key.split(":");
            if (strings.length != 2) {
                return key;
            }
            String groupName;
            String tagName = strings[1];
            if (isComeFromDownload){
                groupName = strings[0];
                return groupName+":"+tagName;
            }
            if (NAMESPACE_TO_PREFIX.containsKey(strings[0])) {
                groupName = NAMESPACE_TO_PREFIX.get(strings[0]);
                return groupName + "\"" + tagName + "$\"";
            } else {
                return key;
            }
        }



        @Override
        public void onLongClick() {
            mSearchDatabase.deleteQuery(mKeyword);
            updateSuggestions(false);
        }
    }

    private class HistorySuggestion extends Suggestion {

        private final String mQuery;

        public HistorySuggestion(String query) {
            mQuery = query;
        }

        @Override
        public CharSequence getText(float textSize) {
            return null;
        }

        @Override
        public CharSequence getText(TextView textView) {
            if (textView.getId() == R.id.hintView) {
                return mQuery;
            }
            return null;
        }

        @Override
        public void onClick() {
            mEditText.setText(mQuery);
            mEditText.setSelection(mEditText.getText().length());
        }

        @Override
        public void onLongClick() {
            mSearchDatabase.deleteQuery(mQuery);
            updateSuggestions(false);
        }
    }

}
