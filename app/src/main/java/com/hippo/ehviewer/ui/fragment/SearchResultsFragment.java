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

package com.hippo.ehviewer.ui.fragment;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.SettingsActivity;
import com.hippo.ehviewer.ui.SettingsSearchIndex;

import java.util.List;

public class SearchResultsFragment extends Fragment {

    private static final String ARG_QUERY = "query";

    private RecyclerView mRecyclerView;
    private TextView mEmptyView;
    private SettingsActivity mActivity;

    public SearchResultsFragment() {
    }

    public static SearchResultsFragment newInstance(@NonNull String query) {
        Bundle args = new Bundle();
        args.putString(ARG_QUERY, query);
        SearchResultsFragment fragment = new SearchResultsFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_search_results, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mActivity = (SettingsActivity) getActivity();
        mRecyclerView = view.findViewById(R.id.search_results);
        mEmptyView = view.findViewById(R.id.search_empty);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        String query = getArguments() != null ? getArguments().getString(ARG_QUERY, "") : "";
        render(query);
    }

    public void render(@NonNull String query) {
        if (mRecyclerView == null) {
            return;
        }
        String trimmed = query.trim();
        if (TextUtils.isEmpty(trimmed)) {
            mRecyclerView.setVisibility(View.GONE);
            mEmptyView.setVisibility(View.VISIBLE);
            mEmptyView.setText(R.string.settings_search_hint);
            return;
        }
        List<SettingsSearchIndex.Entry> results = SettingsSearchIndex.search(requireContext(), trimmed);
        if (results.isEmpty()) {
            mRecyclerView.setVisibility(View.GONE);
            mEmptyView.setVisibility(View.VISIBLE);
            mEmptyView.setText(getString(R.string.settings_search_no_results, trimmed));
        } else {
            mRecyclerView.setVisibility(View.VISIBLE);
            mEmptyView.setVisibility(View.GONE);
            mRecyclerView.setAdapter(new SearchResultsAdapter(results));
        }
    }

    private void openSection(@NonNull SettingsSearchIndex.Entry entry) {
        if (mActivity == null) {
            return;
        }
        Fragment fragment;
        try {
            fragment = (Fragment) entry.fragmentClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return;
        }
        FragmentTransaction tx = mActivity.getSupportFragmentManager().beginTransaction();
        tx.replace(R.id.settings, fragment);
        tx.addToBackStack(entry.fragmentClass.getSimpleName());
        tx.commit();
        mActivity.setSettingsTitle(entry.sectionTitle);
    }

    private final class SearchResultsAdapter
            extends RecyclerView.Adapter<SearchResultsAdapter.Holder> {

        private final List<SettingsSearchIndex.Entry> mEntries;

        SearchResultsAdapter(List<SettingsSearchIndex.Entry> entries) {
            mEntries = entries;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_settings_search_result, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            SettingsSearchIndex.Entry entry = mEntries.get(position);
            holder.title.setText(entry.title);
            holder.summary.setVisibility(TextUtils.isEmpty(entry.summary) ? View.GONE : View.VISIBLE);
            holder.summary.setText(entry.summary);
            holder.section.setText(entry.sectionTitle);
            holder.itemView.setOnClickListener(v -> openSection(entry));
        }

        @Override
        public int getItemCount() {
            return mEntries.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView summary;
            final TextView section;

            Holder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.result_title);
                summary = itemView.findViewById(R.id.result_summary);
                section = itemView.findViewById(R.id.result_section);
            }
        }
    }
}