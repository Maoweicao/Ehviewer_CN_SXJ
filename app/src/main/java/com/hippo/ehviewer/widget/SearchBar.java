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
import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.client.EhTagDatabase;
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

    private static final int[] KEYWORD_GROUP_COLORS = {
            0xff3f51b5, 0xff008577, 0xff7b1fa2, 0xffef6c00, 0xff2e7d32
    };

    // Tag chip container and data
    private AutoWrapLayout mTagContainer;
    private final List<SearchTagChip> mTagChips = new ArrayList<>();

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

    private static class KeywordSuggestionAdapter extends ArrayAdapter<String> {
        KeywordSuggestionAdapter(Context context) {
            super(context, android.R.layout.simple_dropdown_item_1line);
        }
    }

    private void showKeywordEditor() {
        final List<KeywordItem> items = parseKeywords(buildCombinedQuery());
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

        TextView relationHint = new TextView(getContext());
        relationHint.setText(R.string.search_keyword_relation_hint);
        relationHint.setPadding(0, padding / 2, 0, padding / 2);
        root.addView(relationHint);

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
        addButton.setOnClickListener(v -> {
            String text = normalizeKeyword(input.getText().toString());
            if (isValidKeyword(text)) {
                items.add(new KeywordItem(text, items.isEmpty() ? 0 : items.get(items.size() - 1).group, false));
                input.setText("");
                refresh[0].run();
            }
        });
        refresh[0].run();

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(R.string.search_keyword_editor)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String query = buildKeywordQuery(items);
            if (query.isEmpty()) {
                return;
            }
            clearTagChips();
            setText(query);
            dialog.dismiss();
            applySearch(true);
        }));
        dialog.show();
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
        adapter.clear();
        EhTagDatabase database = EhTagDatabase.getInstance(getContext());
        if (database != null && !lookup.isEmpty()) {
            List<Pair<String, String>> suggestions = database.suggest(lookup);
            for (Pair<String, String> suggestion : suggestions) {
                if (suggestion.second != null && !suggestion.second.equals(lookup)) {
                    adapter.add((text.startsWith("-") ? "-" : "") + suggestion.second);
                }
            }
        }
        adapter.notifyDataSetChanged();
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

    private String buildKeywordQuery(List<KeywordItem> items) {
        StringBuilder result = new StringBuilder();
        Map<Integer, Boolean> excludedGroups = new HashMap<>();
        for (KeywordItem item : items) {
            if (!excludedGroups.containsKey(item.group)) {
                excludedGroups.put(item.group, item.groupExcluded);
            }
        }
        for (KeywordItem item : items) {
            String keyword = normalizeKeyword(item.text);
            if (!isValidKeyword(keyword)) continue;
            if (result.length() > 0) result.append(' ');
            if ((item.excluded || Boolean.TRUE.equals(excludedGroups.get(item.group)))
                    && !keyword.startsWith("-")) result.append('-');
            result.append(keyword.startsWith("-") ? keyword.substring(1) : keyword);
        }
        return result.toString();
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
            // 在搜索状态下，点击菜单按钮切换建议列表的显示/隐藏
            if (mState == STATE_SEARCH || mState == STATE_SEARCH_LIST) {
                toggleSuggestionsList();
            } else {
                mHelper.onClickLeftIcon();
            }
        } else if (v == mActionButton) {
            mHelper.onClickRightIcon();
        } else if (v == mAdvanceButton) {
            showKeywordEditor();
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
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            final Bundle savedState = (Bundle) state;
            super.onRestoreInstanceState(savedState.getParcelable(STATE_KEY_SUPER));
            setState(savedState.getInt(STATE_KEY_STATE), false);
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
