package com.hippo.ehviewer.db;

import android.content.Context;
import android.database.SQLException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.hippo.ehviewer.dao.BlackList;
import com.hippo.ehviewer.dao.BookmarkInfo;
import com.hippo.ehviewer.dao.DownloadDirname;
import com.hippo.ehviewer.dao.DownloadHistory;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.dao.DownloadedFile;
import com.hippo.ehviewer.dao.Filter;
import com.hippo.ehviewer.dao.GalleryAiInfo;
import com.hippo.ehviewer.dao.GalleryTags;
import com.hippo.ehviewer.dao.HistoryInfo;
import com.hippo.ehviewer.dao.LocalFavoriteInfo;
import com.hippo.ehviewer.dao.MilestoneInfo;
import com.hippo.ehviewer.dao.PtokensIndex;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.dao.SystemDownloadTask;

/**
 * eh.db 的 Room 数据库。
 *
 * <p>表结构与 greenDAO schema v14 完全一致（列名、类型、主键、索引），
 * 因此从旧版本升级时只需要补列/建表，不搬迁数据；数据库版本升到 15 用来
 * 写入 Room 的 identity hash（room_master_table）。
 */
@Database(
        entities = {
                BlackList.class,
                BookmarkInfo.class,
                DownloadDirname.class,
                DownloadHistory.class,
                DownloadInfo.class,
                DownloadLabel.class,
                DownloadedFile.class,
                Filter.class,
                GalleryAiInfo.class,
                GalleryTags.class,
                HistoryInfo.class,
                LocalFavoriteInfo.class,
                MilestoneInfo.class,
                PtokensIndex.class,
                QuickSearch.class,
                SystemDownloadTask.class,
        },
        version = EhDatabase.VERSION,
        exportSchema = true
)
@TypeConverters({Converters.class})
public abstract class EhDatabase extends RoomDatabase {

    private static final String TAG = "EhDatabase";

    public static final String NAME = "eh.db";
    public static final int VERSION = 15;


    public abstract BlackListDao blackListDao();

    public abstract BookmarksDao bookmarksDao();

    public abstract DownloadDirnameDao downloadDirnameDao();

    public abstract DownloadHistoryDao downloadHistoryDao();

    public abstract DownloadsDao downloadsDao();

    public abstract DownloadLabelDao downloadLabelDao();

    public abstract DownloadedFilesDao downloadedFilesDao();

    public abstract FilterDao filterDao();

    public abstract GalleryAiInfoDao galleryAiInfoDao();

    public abstract GalleryTagsDao galleryTagsDao();

    public abstract HistoryDao historyDao();

    public abstract LocalFavoritesDao localFavoritesDao();

    public abstract PtokensIndexDao ptokensIndexDao();

    public abstract QuickSearchDao quickSearchDao();

    public abstract SystemDownloadTaskDao systemDownloadTaskDao();

    /**
     * 打开主库（{@code eh.db}）。
     *
     * <p>{@code allowMainThreadQueries()} 是过渡措施：历史上仍有少量点击回调在主线程
     * 做单行读写，先保持行为不变；整表读写已在性能优化中移出主线程。
     */
    public static EhDatabase open(Context context) {
        return open(context, NAME);
    }

    public static EhDatabase open(Context context, String name) {
        return Room.databaseBuilder(context.getApplicationContext(), EhDatabase.class, name)
                // 过渡期保留主线程查询：UI 层仍有少量点击回调做单行读写，
                // 整表读写已在性能优化中移出主线程。
                .allowMainThreadQueries()
                .addMigrations(buildMigrations())
                .build();
    }

    /**
     * 1..14 每个历史版本都注册一条直达当前版本的迁移。
     *
     * <p>旧实现把所有升级块写在同一个 onUpgrade 里，用 {@code oldVersion <= n} 判断；
     * 逐级注册会让判断条件被拆散、语义走样，这里保持「一次跑完」的原始语义。
     */
    private static Migration[] buildMigrations() {
        Migration[] migrations = new Migration[VERSION - 1];
        for (int from = 1; from < VERSION; from++) {
            final int oldVersion = from;
            migrations[from - 1] = new Migration(oldVersion, VERSION) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase database) {
                    EhDbMigrations.migrateToCurrent(new EhDbMigrations.SqlExecutor() {
                        @Override
                        public void execSQL(String sql) {
                            database.execSQL(sql);
                        }

                        @Override
                        public void tryExecSQL(String sql) {
                            try {
                                database.execSQL(sql);
                            } catch (SQLException e) {
                                Log.d(TAG, "Ignored migration step: " + e.getMessage());
                            }
                        }
                    }, oldVersion);
                }
            };
        }
        return migrations;
    }
}
