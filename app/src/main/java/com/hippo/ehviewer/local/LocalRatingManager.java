package com.hippo.ehviewer.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * 本地评分管理器。
 *
 * 允许用户为下载的画廊设置本地评分，覆盖 E-Hentai 的在线评分。
 * 评分使用与在线评分相同的 0.0 - 5.0（星星）刻度。
 * 数据存储在 SharedPreferences 中，按 gid 区分。
 */
public class LocalRatingManager {

    private static final String TAG = LocalRatingManager.class.getSimpleName();
    private static final String PREF_NAME = "local_ratings";
    private static final String KEY_PREFIX = "local_rating_";

    private final SharedPreferences mPrefs;

    private static LocalRatingManager sInstance;

    public static synchronized LocalRatingManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new LocalRatingManager(context.getApplicationContext());
        }
        return sInstance;
    }

    public static synchronized LocalRatingManager getInstance() {
        if (sInstance == null) {
            // Fall back to the application context if available
            com.hippo.ehviewer.EhApplication application = com.hippo.ehviewer.EhApplication.getInstance();
            if (application != null) {
                sInstance = new LocalRatingManager(application);
            }
        }
        return sInstance;
    }

    private LocalRatingManager(Context context) {
        mPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 保存本地评分。rating 使用与在线评分相同的 0.0 - 5.0 刻度。
     * 传入 0 等于清除本地评分。
     */
    public void setRating(long gid, float rating) {
        if (rating <= 0f) {
            removeRating(gid);
            return;
        }
        mPrefs.edit().putFloat(KEY_PREFIX + gid, rating).apply();
        Log.d(TAG, "setRating: gid=" + gid + ", rating=" + rating);
    }

    /**
     * 获取本地评分，未设置时返回 -1f。
     */
    public float getRating(long gid) {
        return mPrefs.getFloat(KEY_PREFIX + gid, -1f);
    }

    /**
     * 是否已设置本地评分。
     */
    public boolean hasRating(long gid) {
        return mPrefs.contains(KEY_PREFIX + gid);
    }

    /**
     * 清除本地评分。
     */
    public void removeRating(long gid) {
        mPrefs.edit().remove(KEY_PREFIX + gid).apply();
        Log.d(TAG, "removeRating: gid=" + gid);
    }

    /**
     * 批量清除本地评分。
     */
    public void removeRatings(Iterable<Long> gids) {
        SharedPreferences.Editor editor = mPrefs.edit();
        for (Long gid : gids) {
            editor.remove(KEY_PREFIX + gid);
        }
        editor.apply();
    }

    /**
     * 获取所有本地评分映射。
     */
    public Map<Long, Float> getAllRatings() {
        Map<Long, Float> map = new HashMap<>();
        Map<String, ?> all = mPrefs.getAll();
        if (all == null) {
            return map;
        }
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(KEY_PREFIX)) {
                try {
                    long gid = Long.parseLong(key.substring(KEY_PREFIX.length()));
                    Object value = entry.getValue();
                    if (value instanceof Number) {
                        map.put(gid, ((Number) value).floatValue());
                    }
                } catch (NumberFormatException e) {
                    // Ignore malformed keys
                }
            }
        }
        return map;
    }

    /**
     * 计算有效评分：优先使用本地评分，其次使用在线评分。
     */
    public float getEffectiveRating(long gid, float onlineRating) {
        float local = getRating(gid);
        return local > 0f ? local : onlineRating;
    }
}