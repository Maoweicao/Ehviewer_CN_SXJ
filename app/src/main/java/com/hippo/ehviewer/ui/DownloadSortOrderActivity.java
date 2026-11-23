/*
 * Copyright 2026 EhViewer Contributors
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

package com.hippo.ehviewer.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.sort.DownloadSortField;
import com.hippo.ehviewer.download.sort.DownloadSortOrder;
import com.hippo.ehviewer.download.sort.DownloadSortRule;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载队列多级排序的二级编辑页。
 *
 * <p>每条规则包含排序字段与方向，规则从上到下依次比较；没有规则时按任务添加顺序下载。
 * 分类规则引用主设置页里「画廊分类详细排序」的顺序。
 */
public class DownloadSortOrderActivity extends ToolbarActivity {

    private final List<DownloadSortRule> rules = new ArrayList<>();

    private RecyclerView recyclerView;
    private TextView emptyView;
    private RuleAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_sort_order);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.download_sort_rules_title);
        }
        setNavigationIcon(R.drawable.ic_back);

        recyclerView = findViewById(R.id.recycler_view);
        emptyView = findViewById(R.id.empty_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RuleAdapter();
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btn_add_rule).setOnClickListener(v -> showRuleDialog(-1));
        findViewById(R.id.btn_reset_rules).setOnClickListener(v -> confirmReset());

        reload();
    }

    private void reload() {
        rules.clear();
        rules.addAll(DownloadSortOrder.load(this));
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    private void persist() {
        DownloadSortOrder.save(this, rules);
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    private void updateEmptyView() {
        boolean empty = rules.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void confirmReset() {
        if (rules.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_sort_reset)
                .setMessage(R.string.download_sort_reset_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    rules.clear();
                    persist();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showRuleDialog(int index) {
        final DownloadSortRule editing = index >= 0 && index < rules.size()
                ? rules.get(index) : null;

        final List<DownloadSortField> available = new ArrayList<>();
        for (DownloadSortField field : DownloadSortField.values()) {
            boolean used = false;
            for (int i = 0; i < rules.size(); i++) {
                if (i == index) {
                    continue;
                }
                if (rules.get(i).getField() == field) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                available.add(field);
            }
        }
        if (available.isEmpty()) {
            Toast.makeText(this, R.string.download_sort_all_fields_used, Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(8), dp(24), 0);

        TextView fieldLabel = new TextView(this);
        fieldLabel.setText(R.string.download_sort_choose_field);
        container.addView(fieldLabel);

        final Spinner fieldSpinner = new Spinner(this);
        List<String> fieldNames = new ArrayList<>(available.size());
        for (DownloadSortField field : available) {
            fieldNames.add(getString(field.getLabelRes()));
        }
        ArrayAdapter<String> fieldAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, fieldNames);
        fieldAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fieldSpinner.setAdapter(fieldAdapter);
        container.addView(fieldSpinner);

        TextView directionLabel = new TextView(this);
        directionLabel.setText(R.string.download_sort_direction);
        directionLabel.setPadding(0, dp(16), 0, 0);
        container.addView(directionLabel);

        final Spinner directionSpinner = new Spinner(this);
        ArrayAdapter<String> directionAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{
                        getString(R.string.download_sort_direction_asc),
                        getString(R.string.download_sort_direction_desc)});
        directionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        directionSpinner.setAdapter(directionAdapter);
        container.addView(directionSpinner);

        final TextView hintView = new TextView(this);
        hintView.setTextSize(12);
        hintView.setPadding(0, dp(12), 0, 0);
        container.addView(hintView);

        int fieldSelection = editing != null ? available.indexOf(editing.getField()) : 0;
        if (fieldSelection < 0) {
            fieldSelection = 0;
        }
        fieldSpinner.setSelection(fieldSelection);
        directionSpinner.setSelection(editing != null && !editing.getAscending() ? 1 : 0);

        fieldSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                hintView.setText(available.get(position).getHintRes());
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        hintView.setText(available.get(fieldSelection).getHintRes());

        new AlertDialog.Builder(this)
                .setTitle(editing != null
                        ? R.string.download_sort_edit_rule
                        : R.string.download_sort_add_rule)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    DownloadSortField field = available.get(fieldSpinner.getSelectedItemPosition());
                    boolean ascending = directionSpinner.getSelectedItemPosition() == 0;
                    DownloadSortRule rule = new DownloadSortRule(field, ascending);
                    if (editing != null) {
                        rules.set(index, rule);
                    } else {
                        rules.add(rule);
                    }
                    persist();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void moveRule(int from, int to) {
        if (from < 0 || to < 0 || from >= rules.size() || to >= rules.size()) {
            return;
        }
        DownloadSortRule rule = rules.remove(from);
        rules.add(to, rule);
        persist();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class RuleAdapter extends RecyclerView.Adapter<RuleViewHolder> {

        @NonNull
        @Override
        public RuleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_download_sort_rule, parent, false);
            return new RuleViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RuleViewHolder holder, int position) {
            DownloadSortRule rule = rules.get(position);
            holder.index.setText(String.valueOf(position + 1));
            holder.field.setText(rule.getField().getLabelRes());
            holder.direction.setText(rule.getAscending()
                    ? R.string.download_sort_direction_asc
                    : R.string.download_sort_direction_desc);
            holder.hint.setText(rule.getField().getHintRes());

            holder.content.setOnClickListener(v -> {
                int current = holder.getBindingAdapterPosition();
                if (current != RecyclerView.NO_POSITION) {
                    showRuleDialog(current);
                }
            });
            holder.up.setEnabled(position > 0);
            holder.up.setAlpha(position > 0 ? 1f : 0.3f);
            holder.up.setOnClickListener(v -> {
                int current = holder.getBindingAdapterPosition();
                if (current != RecyclerView.NO_POSITION) {
                    moveRule(current, current - 1);
                }
            });
            holder.down.setEnabled(position < rules.size() - 1);
            holder.down.setAlpha(position < rules.size() - 1 ? 1f : 0.3f);
            holder.down.setOnClickListener(v -> {
                int current = holder.getBindingAdapterPosition();
                if (current != RecyclerView.NO_POSITION) {
                    moveRule(current, current + 1);
                }
            });
            holder.delete.setOnClickListener(v -> {
                int current = holder.getBindingAdapterPosition();
                if (current != RecyclerView.NO_POSITION && current < rules.size()) {
                    rules.remove(current);
                    persist();
                }
            });
        }

        @Override
        public int getItemCount() {
            return rules.size();
        }
    }

    private static class RuleViewHolder extends RecyclerView.ViewHolder {

        final View content;
        final TextView index;
        final TextView field;
        final TextView direction;
        final TextView hint;
        final View up;
        final View down;
        final View delete;

        RuleViewHolder(@NonNull View itemView) {
            super(itemView);
            content = itemView.findViewById(R.id.rule_content);
            index = itemView.findViewById(R.id.tv_index);
            field = itemView.findViewById(R.id.tv_field);
            direction = itemView.findViewById(R.id.tv_direction);
            hint = itemView.findViewById(R.id.tv_hint);
            up = itemView.findViewById(R.id.btn_up);
            down = itemView.findViewById(R.id.btn_down);
            delete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
