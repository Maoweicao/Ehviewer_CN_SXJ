package com.hippo.ehviewer.milestone;

import android.content.Context;
import android.content.SharedPreferences;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.dao.MilestoneDao;
import com.hippo.ehviewer.dao.MilestoneInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MilestoneManager {
    private static final String PREF_NAME = "milestone_prefs";
    private static final String KEY_TOTAL_APP_USAGE_TIME = "total_app_usage_time";
    private static final String KEY_TOTAL_GALLERY_VIEW_TIME = "total_gallery_view_time";
    private static final String KEY_TOTAL_GALLERY_COUNT = "total_gallery_count";
    private static final String KEY_TOTAL_DOWNLOAD_COUNT = "total_download_count";
    private static final String KEY_TOTAL_SEARCH_COUNT = "total_search_count";
    private static final String KEY_FIRST_USE_TIME = "first_use_time";
    private static final String KEY_FAVORITE_GALLERY = "favorite_gallery";
    private static final String KEY_FAVORITE_TAG = "favorite_tag";
    private static final String KEY_FAVORITE_CATEGORY = "favorite_category";
    private static final String KEY_DAILY_USAGE_PREFIX = "daily_usage_";
    private static final String KEY_CATEGORY_PREFIX = "category_";

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final MilestoneDao mDao;

    private static MilestoneManager sInstance;

    public static synchronized MilestoneManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new MilestoneManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private MilestoneManager(Context context) {
        mContext = context;
        mPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        mDao = EhDB.getDaoSession().getMilestoneDao();
    }

    public void recordAppUsage(long durationMillis) {
        long total = mPrefs.getLong(KEY_TOTAL_APP_USAGE_TIME, 0) + durationMillis;
        mPrefs.edit().putLong(KEY_TOTAL_APP_USAGE_TIME, total).apply();

        String today = getTodayKey();
        long dailyTotal = mPrefs.getLong(KEY_DAILY_USAGE_PREFIX + today, 0) + durationMillis;
        mPrefs.edit().putLong(KEY_DAILY_USAGE_PREFIX + today, dailyTotal).apply();

        if (!mPrefs.contains(KEY_FIRST_USE_TIME)) {
            mPrefs.edit().putLong(KEY_FIRST_USE_TIME, System.currentTimeMillis()).apply();
        }
    }

    public void recordGalleryView(long durationMillis) {
        long total = mPrefs.getLong(KEY_TOTAL_GALLERY_VIEW_TIME, 0) + durationMillis;
        mPrefs.edit().putLong(KEY_TOTAL_GALLERY_VIEW_TIME, total).apply();
    }

    public void recordGalleryViewed(String title, int category) {
        long count = mPrefs.getLong(KEY_TOTAL_GALLERY_COUNT, 0) + 1;
        mPrefs.edit().putLong(KEY_TOTAL_GALLERY_COUNT, count).apply();

        String catKey = KEY_CATEGORY_PREFIX + category;
        long catCount = mPrefs.getLong(catKey, 0) + 1;
        mPrefs.edit().putLong(catKey, catCount).apply();

        saveFavoriteGallery(title);
        saveFavoriteCategory(category);
    }

    public void recordDownload() {
        long count = mPrefs.getLong(KEY_TOTAL_DOWNLOAD_COUNT, 0) + 1;
        mPrefs.edit().putLong(KEY_TOTAL_DOWNLOAD_COUNT, count).apply();
    }

    public void recordSearch(String query) {
        long count = mPrefs.getLong(KEY_TOTAL_SEARCH_COUNT, 0) + 1;
        mPrefs.edit().putLong(KEY_TOTAL_SEARCH_COUNT, count).apply();
    }

    public void recordTag(String tag) {
        saveFavoriteTag(tag);
    }

    private void saveFavoriteGallery(String title) {
        String current = mPrefs.getString(KEY_FAVORITE_GALLERY, "");
        if (current.isEmpty() || !current.equals(title)) {
            mPrefs.edit().putString(KEY_FAVORITE_GALLERY, title).apply();
        }
    }

    private void saveFavoriteCategory(int category) {
        String[] categories = {"Doujinshi", "Manga", "Artist CG", "Game CG", "Western",
                "Non-H", "Image Set", "Cosplay", "Asian Porn", "Misc"};
        if (category >= 0 && category < categories.length) {
            mPrefs.edit().putString(KEY_FAVORITE_CATEGORY, categories[category]).apply();
        }
    }

    private void saveFavoriteTag(String tag) {
        String current = mPrefs.getString(KEY_FAVORITE_TAG, "");
        if (current.isEmpty()) {
            mPrefs.edit().putString(KEY_FAVORITE_TAG, tag).apply();
        }
    }

    public long getTotalAppUsageTime() {
        return mPrefs.getLong(KEY_TOTAL_APP_USAGE_TIME, 0);
    }

    public long getTotalGalleryViewTime() {
        return mPrefs.getLong(KEY_TOTAL_GALLERY_VIEW_TIME, 0);
    }

    public long getTotalGalleryCount() {
        return mPrefs.getLong(KEY_TOTAL_GALLERY_COUNT, 0);
    }

    public long getTotalDownloadCount() {
        return mPrefs.getLong(KEY_TOTAL_DOWNLOAD_COUNT, 0);
    }

    public long getTotalSearchCount() {
        return mPrefs.getLong(KEY_TOTAL_SEARCH_COUNT, 0);
    }

    public long getFirstUseTime() {
        return mPrefs.getLong(KEY_FIRST_USE_TIME, System.currentTimeMillis());
    }

    public String getFavoriteGallery() {
        return mPrefs.getString(KEY_FAVORITE_GALLERY, "");
    }

    public String getFavoriteTag() {
        return mPrefs.getString(KEY_FAVORITE_TAG, "");
    }

    public String getFavoriteCategory() {
        return mPrefs.getString(KEY_FAVORITE_CATEGORY, "");
    }

    public Map<String, Long> getDailyUsageMap() {
        Map<String, Long> map = new HashMap<>();
        Map<String, ?> all = mPrefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (entry.getKey().startsWith(KEY_DAILY_USAGE_PREFIX)) {
                String date = entry.getKey().substring(KEY_DAILY_USAGE_PREFIX.length());
                map.put(date, (Long) entry.getValue());
            }
        }
        return map;
    }

    public Map<String, Long> getCategoryStats() {
        Map<String, Long> map = new HashMap<>();
        String[] categories = {"Doujinshi", "Manga", "Artist CG", "Game CG", "Western",
                "Non-H", "Image Set", "Cosplay", "Asian Porn", "Misc"};
        for (int i = 0; i < categories.length; i++) {
            long count = mPrefs.getLong(KEY_CATEGORY_PREFIX + i, 0);
            if (count > 0) {
                map.put(categories[i], count);
            }
        }
        return map;
    }

    private String getTodayKey() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return sdf.format(new java.util.Date());
    }

    public String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return String.format(java.util.Locale.US, "%d天 %d小时", days, hours % 24);
        } else if (hours > 0) {
            return String.format(java.util.Locale.US, "%d小时 %d分钟", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format(java.util.Locale.US, "%d分钟", minutes);
        } else {
            return String.format(java.util.Locale.US, "%d秒", seconds);
        }
    }

    public int getDaysSinceFirstUse() {
        long firstUse = getFirstUseTime();
        long now = System.currentTimeMillis();
        return (int) ((now - firstUse) / (24 * 60 * 60 * 1000)) + 1;
    }
}
