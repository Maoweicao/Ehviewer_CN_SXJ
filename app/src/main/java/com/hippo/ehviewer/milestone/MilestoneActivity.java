package com.hippo.ehviewer.milestone;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.EhActivity;

import java.util.Map;

public class MilestoneActivity extends EhActivity {
    private MilestoneManager mManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_milestone);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.milestone_title);
        }

        mManager = MilestoneManager.getInstance(this);
        updateUI();
    }

    private void updateUI() {
        TextView tvTotalUsageTime = findViewById(R.id.tv_total_usage_time);
        TextView tvTotalGalleryCount = findViewById(R.id.tv_total_gallery_count);
        TextView tvTotalDownloadCount = findViewById(R.id.tv_total_download_count);
        TextView tvGalleryViewTime = findViewById(R.id.tv_gallery_view_time);
        TextView tvSearchCount = findViewById(R.id.tv_search_count);
        TextView tvFavoriteGallery = findViewById(R.id.tv_favorite_gallery);
        TextView tvFavoriteTag = findViewById(R.id.tv_favorite_tag);
        TextView tvFavoriteCategory = findViewById(R.id.tv_favorite_category);
        TextView tvDaysSinceFirstUse = findViewById(R.id.tv_days_since_first_use);

        PieChartView pieChart = findViewById(R.id.pie_chart);
        BarChartView barChart = findViewById(R.id.bar_chart);

        tvTotalUsageTime.setText(mManager.formatDuration(mManager.getTotalAppUsageTime()));
        tvTotalGalleryCount.setText(String.valueOf(mManager.getTotalGalleryCount()));
        tvTotalDownloadCount.setText(String.valueOf(mManager.getTotalDownloadCount()));

        tvGalleryViewTime.setText(getString(R.string.milestone_gallery_view_time_label,
                mManager.formatDuration(mManager.getTotalGalleryViewTime())));
        tvSearchCount.setText(getString(R.string.milestone_search_count_label,
                mManager.getTotalSearchCount()));

        String favoriteGallery = mManager.getFavoriteGallery();
        if (favoriteGallery.isEmpty()) {
            favoriteGallery = getString(R.string.milestone_no_data);
        }
        tvFavoriteGallery.setText(getString(R.string.milestone_favorite_gallery_label, favoriteGallery));

        String favoriteTag = mManager.getFavoriteTag();
        if (favoriteTag.isEmpty()) {
            favoriteTag = getString(R.string.milestone_no_data);
        }
        tvFavoriteTag.setText(getString(R.string.milestone_favorite_tag_label, favoriteTag));

        String favoriteCategory = mManager.getFavoriteCategory();
        if (favoriteCategory.isEmpty()) {
            favoriteCategory = getString(R.string.milestone_no_data);
        }
        tvFavoriteCategory.setText(getString(R.string.milestone_favorite_category_label, favoriteCategory));

        Map<String, Long> categoryStats = mManager.getCategoryStats();
        pieChart.setData(categoryStats);

        Map<String, Long> dailyUsage = mManager.getDailyUsageMap();
        barChart.setData(dailyUsage, getString(R.string.milestone_daily_usage));

        int days = mManager.getDaysSinceFirstUse();
        tvDaysSinceFirstUse.setText(getString(R.string.milestone_days_since_first_use_label, days));
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
