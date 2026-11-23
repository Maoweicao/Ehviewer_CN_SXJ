package com.hippo.ehviewer.preference;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.hippo.ehviewer.download.sort.DownloadSortOrder;
import com.hippo.ehviewer.ui.DownloadSortOrderActivity;

/**
 * 下载队列排序入口：显示当前多级排序规则摘要，点击进入二级排序页。
 */
public class DownloadSortOrderPreference extends Preference {

    public DownloadSortOrderPreference(@NonNull Context context) {
        super(context);
    }

    public DownloadSortOrderPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DownloadSortOrderPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                       int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DownloadSortOrderPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                       int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    private void refreshSummary() {
        setSummary(DownloadSortOrder.describe(getContext()));
    }

    /**
     * 立即刷新摘要。[enabled] 可用于在开关值写入磁盘前预览新状态。
     */
    public void refresh(boolean enabled) {
        setSummary(DownloadSortOrder.describe(getContext(), enabled));
    }

    public void refresh() {
        refreshSummary();
    }

    @Override
    public void onAttached() {
        super.onAttached();
        refreshSummary();
    }

    @Override
    protected void onClick() {
        refreshSummary();
        Context context = getContext();
        context.startActivity(new Intent(context, DownloadSortOrderActivity.class));
    }
}
