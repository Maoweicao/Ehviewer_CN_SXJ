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
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.filter.FilterCondition;
import com.hippo.ehviewer.download.filter.FilterSettingsStore;
import com.hippo.ehviewer.download.filter.PanelPreset;

import java.util.ArrayList;
import java.util.List;

/**
 * 筛选面板上的「快速设置」按钮条。
 *
 * 点击某个预设**不会**直接执行筛选，而是把它的条件套用到下方的条件输入框里，
 * 用户可以继续微调再点“应用”。再次点击同一个预设可取消套用。
 *
 * 按钮列表来自设置里配置的 [PanelPreset]（[FilterSettingsStore]），
 * 因此增删改按钮不需要改代码。
 */
public class QuickPresetBar extends LinearLayout {

    private ChipGroup mChipGroup;
    private final List<PanelPreset> mPresets = new ArrayList<>();
    private String mAppliedPresetId;
    private OnPresetAppliedListener mListener;

    public QuickPresetBar(@NonNull Context context) {
        this(context, null);
    }

    public QuickPresetBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QuickPresetBar(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_filter_quick_presets, this, true);
        mChipGroup = findViewById(R.id.preset_chips);
        View manageButton = findViewById(R.id.preset_manage_button);
        manageButton.setOnClickListener(v -> showManageDialog());
    }

    /**
     * 快速设置按钮的管理。
     *
     * 这里只提供启用 / 停用（就地生效）；重命名、改条件、增删要去
     * 「设置 - 下载 - 筛选与快捷筛选 - 快速设置按钮」。
     */
    private void showManageDialog() {
        List<PanelPreset> all = new ArrayList<>();
        try {
            all.addAll(FilterSettingsStore.getInstance(getContext()).get().getPanelPresets());
        } catch (Exception ignored) {
        }
        if (all.isEmpty()) {
            new AlertDialog.Builder(getContext())
                    .setMessage(R.string.filter_section_quick_presets_hint)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        CharSequence[] labels = new CharSequence[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            labels[i] = all.get(i).displayName(getContext());
            checked[i] = all.get(i).getEnabled();
        }
        FilterSettingsStore store = FilterSettingsStore.getInstance(getContext());
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.settings_download_quick_preset_manager)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        store.togglePanelPreset(all.get(which).getId(), isChecked))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> reload())
                .setNeutralButton(R.string.filter_quick_filter_manage_hint, (dialog, which) -> {
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 从配置里读取已启用的预设并渲染。 */
    public void reload() {
        mPresets.clear();
        try {
            mPresets.addAll(FilterSettingsStore.getInstance(getContext()).enabledPanelPresets());
        } catch (Exception e) {
            // 配置不可用时退化为空列表，不影响筛选主流程
        }
        renderChips();
    }

    private void renderChips() {
        mChipGroup.removeAllViews();
        for (PanelPreset preset : mPresets) {
            Chip chip = new Chip(getContext());
            chip.setText(preset.displayName(getContext()));
            chip.setCheckable(true);
            chip.setChecked(preset.getId().equals(mAppliedPresetId));
            chip.setOnClickListener(v -> onChipClicked(preset, chip));
            mChipGroup.addView(chip);
        }
        mChipGroup.setVisibility(mPresets.isEmpty() ? GONE : VISIBLE);
    }

    private void onChipClicked(PanelPreset preset, Chip chip) {
        if (preset.getId().equals(mAppliedPresetId)) {
            // 再次点击同一个预设 = 撤销套用
            mAppliedPresetId = null;
            if (mListener != null) {
                mListener.onPresetCleared();
            }
        } else {
            mAppliedPresetId = preset.getId();
            if (mListener != null) {
                mListener.onPresetApplied(preset.getId(), preset.getCondition());
            }
        }
        renderChips();
    }

    /** 高亮某个预设（例如从设置里改完配置回到面板）。 */
    public void setAppliedPresetId(@Nullable String presetId) {
        mAppliedPresetId = presetId;
        renderChips();
    }

    public void setOnPresetAppliedListener(@Nullable OnPresetAppliedListener listener) {
        mListener = listener;
    }

    public interface OnPresetAppliedListener {
        /** 预设被套用到面板条件上。 */
        void onPresetApplied(@NonNull String presetId, @NonNull FilterCondition condition);

        /** 预设被取消套用。 */
        void onPresetCleared();
    }
}
