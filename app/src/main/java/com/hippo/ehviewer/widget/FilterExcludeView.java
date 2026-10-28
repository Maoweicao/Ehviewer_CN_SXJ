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
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.tag.TagSuggestRepository;
import com.hippo.ehviewer.download.filter.ExcludeRule;
import com.hippo.ehviewer.download.filter.KeywordScope;
import com.hippo.ehviewer.download.filter.ScopedKeyword;
import com.hippo.ehviewer.ui.scene.download.part.DownloadCategoryTable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「排除内容」面板。
 *
 * 可用在三处：
 * <ul>
 *   <li>下载列表的筛选 / 搜索面板（单次排除）</li>
 *   <li>设置 - 下载 - 全局排除（对所有筛选生效）</li>
 *   <li>快捷筛选编辑面板的「额外排除」</li>
 * </ul>
 *
 * 三个地方共用同一份 [ExcludeRule] 数据结构，所以行为完全一致。
 */
public class FilterExcludeView extends LinearLayout {

    private CheckBox mEnableCheckBox;
    private LinearLayout mContentLayout;
    private DownloadCategoryTable mCategoryTable;
    private Spinner mScopeSpinner;
    private TagInputView mKeywordInput;
    private ChipGroup mKeywordChips;
    private TagInputView mTagInput;
    private ChipGroup mTagChips;
    @Nullable
    private TextView mTagCoverageView;

    private final List<KeywordScope> mScopes = new ArrayList<>();
    private final List<ScopedKeyword> mKeywords = new ArrayList<>();
    private final Set<String> mTags = new LinkedHashSet<>();

    private OnExcludeChangedListener mListener;

    public FilterExcludeView(@NonNull Context context) {
        this(context, null);
    }

    public FilterExcludeView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public FilterExcludeView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_filter_exclude, this, true);

        // 提前建本地标签索引，避免第一次敲字时才开始扫库
        TagSuggestRepository.warmUpAsync();

        mEnableCheckBox = findViewById(R.id.exclude_enable_checkbox);
        mCategoryTable = findViewById(R.id.exclude_category_table);
        mScopeSpinner = findViewById(R.id.exclude_scope_spinner);
        mKeywordInput = findViewById(R.id.exclude_keyword_input);
        mKeywordChips = findViewById(R.id.exclude_keyword_chips);
        mTagInput = findViewById(R.id.exclude_tag_input);
        mTagChips = findViewById(R.id.exclude_tag_chips);
        mTagCoverageView = findViewById(R.id.exclude_tag_coverage);
        mContentLayout = findViewById(R.id.exclude_content);

        setupScopeSpinner();
        mCategoryTable.setAllSelected();

        Button keywordAdd = findViewById(R.id.exclude_keyword_add);
        keywordAdd.setOnClickListener(v -> addKeywordFromInput());
        mKeywordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addKeywordFromInput();
                return true;
            }
            return false;
        });

        Button tagAdd = findViewById(R.id.exclude_tag_add);
        tagAdd.setOnClickListener(v -> addTagFromInput());
        mTagInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addTagFromInput();
                return true;
            }
            return false;
        });

        mEnableCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            applyEnabledState();
            notifyChanged();
        });

        Button clearAll = findViewById(R.id.exclude_clear_all);
        clearAll.setOnClickListener(v -> {
            mKeywords.clear();
            mTags.clear();
            mCategoryTable.setAllSelected();
            mKeywordInput.setText("");
            mTagInput.setText("");
            renderChips();
            notifyChanged();
        });

        applyEnabledState();
    }

    private void setupScopeSpinner() {
        List<String> labels = new ArrayList<>();
        for (KeywordScope scope : KeywordScope.values()) {
            mScopes.add(scope);
            labels.add(getContext().getString(scope.getLabelRes()));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mScopeSpinner.setAdapter(adapter);
        // 作用域决定关键词输入框给什么建议：TAG 给 ns:value，其余给裸词
        mScopeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                KeywordScope scope = position >= 0 && position < mScopes.size()
                        ? mScopes.get(position) : KeywordScope.ANY;
                mKeywordInput.setSuggestScope(scope == KeywordScope.TAG
                        ? TagSuggestRepository.SuggestScope.TAG
                        : TagSuggestRepository.SuggestScope.TEXT);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void addKeywordFromInput() {
        for (String text : splitInput(mKeywordInput.getText().toString())) {
            int scopeIndex = mScopeSpinner.getSelectedItemPosition();
            KeywordScope scope = scopeIndex >= 0 && scopeIndex < mScopes.size()
                    ? mScopes.get(scopeIndex) : KeywordScope.ANY;
            addKeyword(new ScopedKeyword(text, scope, true, false, false));
        }
        mKeywordInput.setText("");
    }

    /**
     * 支持逗号批量输入。
     *
     * 选中建议后 [TagInputView] 会追加 `, `，用户也可以连着打多个，
     * 这里统一按逗号拆成多条，一次加进去。
     */
    private static List<String> splitInput(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(",")) {
            String text = part.trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private void addKeyword(ScopedKeyword keyword) {
        for (ScopedKeyword existing : mKeywords) {
            if (existing.getText().equals(keyword.getText()) && existing.getScope() == keyword.getScope()) {
                return;
            }
        }
        mKeywords.add(keyword);
        renderChips();
        notifyChanged();
    }

    private void addTagFromInput() {
        for (String text : splitInput(mTagInput.getText().toString())) {
            if (mTags.add(text.toLowerCase(Locale.ROOT))) {
                renderChips();
                notifyChanged();
            }
        }
        mTagInput.setText("");
    }

    private void applyEnabledState() {
        boolean enabled = mEnableCheckBox.isChecked();
        // 只折叠开关下面的内容，开关本身必须一直可见
        mContentLayout.setVisibility(enabled ? VISIBLE : GONE);
        View hint = findViewById(R.id.exclude_hint);
        if (hint != null) {
            hint.setVisibility(enabled ? VISIBLE : GONE);
        }
    }

    private void renderChips() {
        mKeywordChips.removeAllViews();
        for (ScopedKeyword keyword : new ArrayList<>(mKeywords)) {
            mKeywordChips.addView(buildKeywordChip(keyword));
        }
        mTagChips.removeAllViews();
        for (String tag : new ArrayList<>(mTags)) {
            mTagChips.addView(buildTagChip(tag));
        }
    }

    private Chip buildKeywordChip(ScopedKeyword keyword) {
        Chip chip = new Chip(getContext());
        String scopeLabel = getContext().getString(keyword.getScope().getLabelRes());
        chip.setText(scopeLabel + ": " + keyword.getText());
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> {
            mKeywords.remove(keyword);
            renderChips();
            notifyChanged();
        });
        chip.setOnLongClickListener(v -> {
            showKeywordOptions(keyword, chip);
            return true;
        });
        return chip;
    }

    /** 长按 chip 切换“忽略大小写 / 整词 / 正则”。 */
    private void showKeywordOptions(ScopedKeyword keyword, Chip anchor) {
        boolean[] checked = new boolean[]{
            keyword.getIgnoreCase(), keyword.getWholeWord(), keyword.getRegex()
        };
        CharSequence[] items = new CharSequence[]{
            getContext().getString(R.string.filter_exclude_ignore_case),
            getContext().getString(R.string.filter_exclude_whole_word),
            getContext().getString(R.string.filter_exclude_regex),
        };
        final int index = mKeywords.indexOf(keyword);
        new androidx.appcompat.app.AlertDialog.Builder(getContext())
                .setTitle(keyword.getText())
                .setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (index >= 0 && index < mKeywords.size()) {
                        mKeywords.set(index, keyword.withOptions(checked[0], checked[1], checked[2]));
                        renderChips();
                        notifyChanged();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private Chip buildTagChip(String tag) {
        Chip chip = new Chip(getContext());
        chip.setText(tag);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> {
            if (mTags.remove(tag)) {
                renderChips();
                notifyChanged();
            }
        });
        return chip;
    }

    public void setRule(@Nullable ExcludeRule rule) {
        ExcludeRule effective = rule != null ? rule : ExcludeRule.EMPTY;
        mKeywords.clear();
        mKeywords.addAll(effective.getKeywords());
        mTags.clear();
        mTags.addAll(effective.getExcludedTags());

        mEnableCheckBox.setChecked(!effective.isEmpty());
        if (effective.getExcludedCategories().isEmpty()) {
            mCategoryTable.setAllSelected();
        } else {
            mCategoryTable.setSelectedCategories(new LinkedHashSet<>(effective.getExcludedCategories()));
        }
        mKeywordInput.setText("");
        mTagInput.setText("");
        renderChips();
    }

    public ExcludeRule getRule() {
        Set<Integer> excludedCategories = new LinkedHashSet<>();
        for (Integer category : mCategoryTable.getSelectedCategories()) {
            // “全部勾选”表示不排除任何分类
            if (category != com.hippo.ehviewer.client.EhUtils.ALL_CATEGORY) {
                excludedCategories.add(category);
            }
        }
        ExcludeRule rule = new ExcludeRule(excludedCategories, new ArrayList<>(mKeywords), new LinkedHashSet<>(mTags));
        if (!mEnableCheckBox.isChecked()) {
            return ExcludeRule.EMPTY;
        }
        return rule;
    }

    public boolean isEnabled() {
        return mEnableCheckBox.isChecked();
    }

    /**
     * 显示「标签规则能看到多少条目」的提示。
     *
     * <p>标签数据只来自条目内存里的 simpleTags/tgList 和 Gallery_Tags 表；
     * 两者都为空的条目既不会被标签规则排除、也不会被「无标签」以外的标签条件命中，
     * 这里把覆盖率显式告诉用户，避免规则静默失效。传 total &lt;= 0 隐藏提示。
     *
     * @param tagged 有标签数据的条目数
     * @param total  参与筛选的条目总数
     */
    public void setTagCoverage(int tagged, int total) {
        if (mTagCoverageView == null) {
            return;
        }
        if (total <= 0) {
            mTagCoverageView.setVisibility(GONE);
            mTagCoverageView.setText("");
            return;
        }
        mTagCoverageView.setVisibility(VISIBLE);
        mTagCoverageView.setText(getContext().getString(
                R.string.filter_exclude_tag_coverage, tagged, total));
    }

    public void setEnabledRule(boolean enabled) {
        mEnableCheckBox.setChecked(enabled);
    }

    public void setOnExcludeChangedListener(OnExcludeChangedListener listener) {
        mListener = listener;
    }

    private void notifyChanged() {
        if (mListener != null) {
            mListener.onExcludeChanged(getRule());
        }
    }

    public interface OnExcludeChangedListener {
        void onExcludeChanged(@NonNull ExcludeRule rule);
    }
}
