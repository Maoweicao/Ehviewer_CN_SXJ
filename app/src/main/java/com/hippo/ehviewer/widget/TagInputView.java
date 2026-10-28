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
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.tag.TagSuggestRepository;
import com.hippo.ehviewer.util.SearchDebugLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 带标签智能提示的输入框。
 *
 * 用于所有「输入标签」的地方：下载排除面板、屏蔽过滤器、订阅标签、标签选择器等。
 * 建议来源是 [TagSuggestRepository]（本地已下载画廊标签优先 + 在线词库补全）。
 *
 * 宿主像用普通 {@link android.widget.EditText} 一样用：读 {@link #getText()}、
 * 在「添加」按钮 / IME done 时调 {@link #commitFromInput()}。
 *
 * 选中建议时只替换「当前正在输入的那段」（按逗号分段），已输的段保留，
 * 所以可以连着打 `a, b, ar` 然后点 `artist:xxx` 得到 `a, b, artist:xxx`。
 */
public class TagInputView extends AppCompatAutoCompleteTextView {

    private static final int MAX_SUGGESTIONS = 20;

    private TagSuggestRepository.SuggestScope mScope = TagSuggestRepository.SuggestScope.TAG;

    @Nullable
    private OnTagCommittedListener mListener;

    @NonNull
    private final SuggestAdapter mAdapter;

    public TagInputView(@NonNull Context context) {
        this(context, null);
    }

    public TagInputView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mAdapter = new SuggestAdapter(context);
        mAdapter.mHost = this;
        setAdapter(mAdapter);
        setThreshold(1);
    }

    /** 切换建议源（TAG 只给 `ns:value`，TEXT 给裸词）。 */
    public void setSuggestScope(@NonNull TagSuggestRepository.SuggestScope scope) {
        if (mScope == scope) {
            return;
        }
        mScope = scope;
        mAdapter.setScope(scope);
        mAdapter.setData(new ArrayList<>());
    }

    @NonNull
    public TagSuggestRepository.SuggestScope getSuggestScope() {
        return mScope;
    }

    public void setOnTagCommittedListener(@Nullable OnTagCommittedListener listener) {
        mListener = listener;
    }

    /** 宿主在「添加」按钮 / IME done 时调用：把当前文本交给监听者并清空。 */
    public void commitFromInput() {
        String text = getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (mListener != null) {
            mListener.onTagCommitted(text);
        }
        setText("");
    }

    /**
     * 选中建议时只替换当前正在输入的那段（逗号分段）。
     * 注意 {@link AutoCompleteTextView} 走的是**适配器**的 Filter，
     * 这里只负责「落笔」的形态。
     */
    @Override
    protected void replaceText(CharSequence text) {
        String current = getText().toString();
        int lastSep = current.lastIndexOf(',');
        String prefix = lastSep >= 0 ? current.substring(0, lastSep + 1) + " " : "";
        setText(prefix + text + ", ");
        setSelection(getText().length());
    }

    @Override
    protected CharSequence convertSelectionToString(Object selectedItem) {
        if (selectedItem instanceof TagSuggestRepository.TagSuggestion) {
            return ((TagSuggestRepository.TagSuggestion) selectedItem).getTag();
        }
        return String.valueOf(selectedItem);
    }

    /** 取当前正在输入的那一段（逗号分段）。 */
    @NonNull
    public String currentSegment() {
        String current = getText().toString();
        int lastSep = current.lastIndexOf(',');
        return lastSep >= 0 ? current.substring(lastSep + 1).trim() : current.trim();
    }

    /**
     * 建议适配器。真实的过滤逻辑在这里 —— {@link AutoCompleteTextView} 会
     * 调 {@code ((Filterable) getAdapter()).getFilter()}，而不是 View 自己的 getFilter()。
     */
    private static class SuggestAdapter extends BaseAdapter implements Filterable {

        private final LayoutInflater mInflater;
        private final SuggestFilter mFilter = new SuggestFilter(this);

        /** 过滤完成后要控制下拉的输入框（Filter 在后台线程跑，不能从外层类捕获非静态引用）。 */
        @Nullable
        TagInputView mHost;

        private List<TagSuggestRepository.TagSuggestion> mData = new ArrayList<>();
        private TagSuggestRepository.SuggestScope mScope = TagSuggestRepository.SuggestScope.TAG;

        SuggestAdapter(Context context) {
            mInflater = LayoutInflater.from(context);
        }

        void setScope(TagSuggestRepository.SuggestScope scope) {
            mScope = scope;
        }

        void setData(List<TagSuggestRepository.TagSuggestion> data) {
            mData = data == null ? new ArrayList<>() : data;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mData.size();
        }

        @Override
        public TagSuggestRepository.TagSuggestion getItem(int position) {
            return mData.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int position, @Nullable View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.tag_suggestion_item, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            TagSuggestRepository.TagSuggestion item = getItem(position);
            holder.name.setText(item.getTag());
            String translation = item.getTranslation();
            if (translation == null || translation.isEmpty()) {
                translation = TagSuggestRepository.translationOf(item.getTag());
            }
            if (translation == null || translation.isEmpty()) {
                holder.translation.setVisibility(View.GONE);
            } else {
                holder.translation.setVisibility(View.VISIBLE);
                holder.translation.setText(translation);
            }
            if (item.getLocalCount() > 0) {
                holder.count.setVisibility(View.VISIBLE);
                holder.count.setText("×" + item.getLocalCount());
            } else {
                holder.count.setVisibility(View.GONE);
            }
            return convertView;
        }

        @Override
        public Filter getFilter() {
            return mFilter;
        }

        private static class ViewHolder {
            final TextView name;
            final TextView translation;
            final TextView count;

            ViewHolder(View root) {
                name = root.findViewById(R.id.tag_suggestion_name);
                translation = root.findViewById(R.id.tag_suggestion_translation);
                count = root.findViewById(R.id.tag_suggestion_count);
            }
        }
    }

    /** 过滤器：在工作线程查 [TagSuggestRepository]，回主线程刷新下拉。 */
    private static class SuggestFilter extends Filter {

        @NonNull
        private final SuggestAdapter mAdapter;

        SuggestFilter(SuggestAdapter adapter) {
            mAdapter = adapter;
        }

        /** 只取逗号之后正在输入的那一段，避免把已输的 tag 当成查询词。 */
        @NonNull
        private static String segmentOf(@Nullable CharSequence constraint) {
            if (constraint == null) {
                return "";
            }
            String s = constraint.toString();
            int lastSep = s.lastIndexOf(',');
            return lastSep >= 0 ? s.substring(lastSep + 1).trim() : s.trim();
        }

        @Override
        protected FilterResults performFiltering(@Nullable CharSequence constraint) {
            FilterResults results = new FilterResults();
            String segment = segmentOf(constraint);
            if (segment.isEmpty()) {
                results.values = new ArrayList<TagSuggestRepository.TagSuggestion>();
                results.count = 0;
                return results;
            }
            List<TagSuggestRepository.TagSuggestion> list =
                    TagSuggestRepository.suggest(segment, mAdapter.mScope, MAX_SUGGESTIONS);
            results.values = list;
            results.count = list.size();
            return results;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(@Nullable CharSequence constraint, @NonNull FilterResults results) {
            List<TagSuggestRepository.TagSuggestion> list =
                    (List<TagSuggestRepository.TagSuggestion>) results.values;
            mAdapter.setData(list == null ? new ArrayList<>() : list);
            SearchDebugLog.d("TagInputView", "publishResults: "
                    + (list == null ? 0 : list.size()) + " suggestions, scope=" + mAdapter.mScope);
            // 框架的自动弹出依赖 hasFocus()/hasWindowFocus()，在对话框里不可靠，
            // 所以这里显式控制（与 SearchBar.createKeywordInput 的做法一致）。
            if (list != null && !list.isEmpty() && mAdapter.mHost != null) {
                TagInputView host = mAdapter.mHost;
                if (host.isFocused() && host.getWindowVisibility() == View.VISIBLE) {
                    host.showDropDown();
                    return;
                }
            }
            if (mAdapter.mHost != null) {
                mAdapter.mHost.dismissDropDown();
            }
        }
    }

    /** 「添加」回调：宿主拿到整段文本后自己决定怎么拆分。 */
    public interface OnTagCommittedListener {
        void onTagCommitted(@NonNull String text);
    }

    /**
     * 让宿主可以监听 IME 的 done 动作，行为与旧 {@link android.widget.EditText} 一致：
     * 触发时调 {@link #commitFromInput()}。
     */
    public void setOnDoneListener(@Nullable OnTagCommittedListener listener) {
        setOnTagCommittedListener(listener);
        setOnEditorActionListener((v, actionId, event) -> {
            switch (actionId) {
                case android.view.inputmethod.EditorInfo.IME_ACTION_DONE:
                case android.view.inputmethod.EditorInfo.IME_ACTION_NEXT:
                case android.view.inputmethod.EditorInfo.IME_ACTION_GO:
                case android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH:
                    commitFromInput();
                    return true;
                default:
                    return false;
            }
        });
    }

    /** 关掉自动下拉（宿主只想手动控制时用）。 */
    public void setSuggestEnabled(boolean enabled) {
        setAdapter(enabled ? mAdapter : null);
    }
}
