/*
 * Copyright 2016 Hippo Seven
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

package com.hippo.ehviewer;

import static com.hippo.ehviewer.ui.fragment.AdvancedFragment.DB_LOADING;
import static com.hippo.ehviewer.ui.fragment.AdvancedFragment.LOADING_PROGRESS;
import static com.hippo.ehviewer.ui.fragment.AdvancedFragment.LOADING_STATUS;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.tag.TagSuggestRepository;
import com.hippo.ehviewer.dao.BlackList;
import com.hippo.ehviewer.dao.DownloadDirname;
import com.hippo.ehviewer.dao.DownloadHistory;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.dao.Filter;
import com.hippo.ehviewer.dao.GalleryAiInfo;
import com.hippo.ehviewer.dao.GalleryTags;
import com.hippo.ehviewer.dao.HistoryInfo;
import com.hippo.ehviewer.dao.LocalFavoriteInfo;
import com.hippo.ehviewer.dao.PtokensIndex;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.dao.SystemDownloadTask;
import com.hippo.ehviewer.db.BlackListDao;
import com.hippo.ehviewer.db.DownloadDirnameDao;
import com.hippo.ehviewer.db.DownloadHistoryDao;
import com.hippo.ehviewer.db.DownloadLabelDao;
import com.hippo.ehviewer.db.DownloadsDao;
import com.hippo.ehviewer.db.EhDatabase;
import com.hippo.ehviewer.db.EhDbMigrations;
import com.hippo.ehviewer.db.FilterDao;
import com.hippo.ehviewer.db.GalleryAiInfoDao;
import com.hippo.ehviewer.db.GalleryTagsDao;
import com.hippo.ehviewer.db.HistoryDao;
import com.hippo.ehviewer.db.LocalFavoritesDao;
import com.hippo.ehviewer.db.PtokensIndexDao;
import com.hippo.ehviewer.db.QuickSearchDao;
import com.hippo.ehviewer.db.SystemDownloadTaskDao;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.progressive.ProgressiveStats;
import com.hippo.ehviewer.lab.semantic.SemanticSearchManager;
import com.hippo.ehviewer.lab.semantic.search.SemanticRanker;
import com.hippo.ehviewer.util.DownloadHistoryStatusResolver;
import com.hippo.util.ExceptionUtils;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.ObjectUtils;
import com.hippo.lib.yorozuya.collect.SparseJLArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 应用数据访问门面。
 *
 * <p>数据层已从 greenDAO 迁移到 Room：DAO 本身线程安全、事务由 SQLite 串行化，
 * 因此这里不再使用 {@code static synchronized} 全局类锁。原来依赖类锁保证的
 * 「读-改-写」复合操作改为 {@link EhDatabase#runInTransaction(Runnable)} 或单条
 * upsert 语句，既保留了原子性，也不会让一次慢查询阻塞所有线程。
 *
 * <p>对外方法签名保持不变，调用方（约 300 处）无需改动。
 */
public class EhDB {

    private static final String TAG = EhDB.class.getSimpleName();

    public static int MAX_HISTORY_COUNT = 100;

    /** Room 数据库实例，由 {@link #initialize(Context)} 填充。 */
    private static volatile EhDatabase sDatabase;

    // 下载目录名缓存：避免高频路径（下载列表、完整性校验）反复查库
    private static final ConcurrentHashMap<Long, String> sDownloadDirnameCache = new ConcurrentHashMap<>();

    private static boolean sHasOldDB;
    private static boolean sNewDB;

    private static class OldDBHelper extends SQLiteOpenHelper {

        private static final String DB_NAME = "data";
        private static final int VERSION = 6;

        private static final String TABLE_GALLERY = "gallery";
        private static final String TABLE_LOCAL_FAVOURITE = "local_favourite";
        private static final String TABLE_TAG = "tag";
        private static final String TABLE_DOWNLOAD = "download";
        private static final String TABLE_HISTORY = "history";

        public OldDBHelper(Context context) {
            super(context, DB_NAME, null, VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // 旧库只读，不做升级
        }
    }

    public static void initialize(Context context) {
        Log.i(TAG, "Initializing EhDB...");
        sHasOldDB = context.getDatabasePath("data").exists();
        // Room 懒打开，这里用文件存在性判断是否首次建库（等价于旧实现的 onCreate 标记）
        sNewDB = !context.getDatabasePath(EhDatabase.NAME).exists();
        Log.i(TAG, "Old database exists: " + sHasOldDB + ", new database: " + sNewDB);

        EhDatabase database = EhDatabase.open(context);
        // 启动时就打开一次，让迁移/校验问题尽早暴露，而不是等到第一次查询
        try {
            database.getOpenHelper().getWritableDatabase();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open database", t);
            Analytics.recordException(t);
        }
        sDatabase = database;

        MAX_HISTORY_COUNT = Settings.getHistoryInfoSize();
        Log.i(TAG, "EhDB initialization completed");
    }

    /**
     * 获取 Room 数据库。
     *
     * <p>取代旧的 {@code getDaoSession()}：Room 的 DAO 线程安全，外部类可以直接
     * 拿 DAO 使用，不再需要额外加锁。
     */
    @NonNull
    public static EhDatabase getDatabase() {
        EhDatabase database = sDatabase;
        if (database == null) {
            throw new IllegalStateException("EhDB has not been initialized");
        }
        return database;
    }

    /**
     * 备份数据库。
     *
     * <p>先把 WAL 内容合并回主库，再复制文件，避免备份出一个缺最新事务的库。
     */
    public static boolean backupDatabase(Context context) {
        try {
            File dbFile = context.getDatabasePath(EhDatabase.NAME);
            if (!dbFile.exists()) {
                return false;
            }

            File backupDir = new File(context.getCacheDir(), "db_backup");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }

            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File backupFile = new File(backupDir, "ehviewer_backup_" + timestamp + ".db");

            // 尽可能把 WAL 落盘；失败也继续（只影响最近若干事务）
            EhDatabase database = sDatabase;
            if (database != null) {
                try {
                    database.getOpenHelper().getWritableDatabase().execSQL("PRAGMA wal_checkpoint(FULL)");
                } catch (Throwable t) {
                    Log.w(TAG, "wal_checkpoint before backup failed", t);
                }
            }

            java.nio.channels.FileChannel source = new java.io.FileInputStream(dbFile).getChannel();
            java.nio.channels.FileChannel destination = new java.io.FileOutputStream(backupFile).getChannel();
            destination.transferFrom(source, 0, source.size());
            source.close();
            destination.close();

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error backing up database", e);
            return false;
        }
    }

    /**
     * 还原数据库。
     *
     * <p>先关闭 Room 连接（关闭时会 checkpoint 并清理 -wal/-shm），再覆盖主库文件。
     * 旧实现在这里无锁把 session 置空，与其它线程并发会 NPE；现在单例整体替换。
     */
    public static boolean restoreDatabase(Context context, String backupFileName) {
        try {
            File backupDir = new File(context.getCacheDir(), "db_backup");
            File backupFile = new File(backupDir, backupFileName);
            if (!backupFile.exists()) {
                return false;
            }

            File dbFile = context.getDatabasePath(EhDatabase.NAME);

            closeDatabase();

            // 清理可能残留的 WAL 附属文件，避免旧数据覆盖还原结果
            new File(dbFile.getPath() + "-wal").delete();
            new File(dbFile.getPath() + "-shm").delete();

            java.nio.channels.FileChannel source = new java.io.FileInputStream(backupFile).getChannel();
            java.nio.channels.FileChannel destination = new java.io.FileOutputStream(dbFile).getChannel();
            destination.transferFrom(source, 0, source.size());
            source.close();
            destination.close();

            initialize(context);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error restoring database", e);
            return false;
        }
    }

    private static void closeDatabase() {
        EhDatabase database = sDatabase;
        sDatabase = null;
        sDownloadDirnameCache.clear();
        if (database != null) {
            try {
                database.close();
            } catch (Throwable t) {
                Log.w(TAG, "Failed to close database", t);
            }
        }
    }

    public static boolean needMerge() {
        return sNewDB && sHasOldDB;
    }

    public static void mergeOldDB(Context context) {
        // 整段迁移放进一个 Room 事务：历史数据量大时逐条提交会做大量磁盘同步。
        getDatabase().runInTransaction(() -> mergeOldDBInner(context));
    }

    private static void mergeOldDBInner(Context context) {
        sNewDB = false;

        OldDBHelper oldDBHelper = new OldDBHelper(context);
        SQLiteDatabase oldDB;
        try {
            oldDB = oldDBHelper.getReadableDatabase();
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            return;
        }

        // Get GalleryInfo list
        SparseJLArray<GalleryInfo> map = new SparseJLArray<>();
        try {
            Cursor cursor = oldDB.rawQuery("select * from " + OldDBHelper.TABLE_GALLERY, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    while (!cursor.isAfterLast()) {
                        GalleryInfo gi = new GalleryInfo();
                        gi.gid = cursor.getInt(0);
                        gi.token = cursor.getString(1);
                        gi.title = cursor.getString(2);
                        gi.posted = cursor.getString(3);
                        gi.category = cursor.getInt(4);
                        gi.thumb = cursor.getString(5);
                        gi.uploader = cursor.getString(6);
                        try {
                            // In 0.6.x version, NaN is stored
                            gi.rating = cursor.getFloat(7);
                        } catch (Throwable e) {
                            ExceptionUtils.throwIfFatal(e);
                            gi.rating = -1.0f;
                        }

                        map.put(gi.gid, gi);

                        cursor.moveToNext();
                    }
                }
                cursor.close();
            }
        } catch (Throwable i) {
            ExceptionUtils.throwIfFatal(i);
        }

        // Merge local favorites
        try {
            Cursor cursor = oldDB.rawQuery("select * from " + OldDBHelper.TABLE_LOCAL_FAVOURITE, null);
            if (cursor != null) {
                LocalFavoritesDao dao = getDatabase().localFavoritesDao();
                if (cursor.moveToFirst()) {
                    long i = 0L;
                    while (!cursor.isAfterLast()) {
                        // Get GalleryInfo first
                        long gid = cursor.getInt(0);
                        GalleryInfo gi = map.get(gid);
                        if (gi == null) {
                            Log.e(TAG, "Can't get GalleryInfo with gid: " + gid);
                            cursor.moveToNext();
                            continue;
                        }

                        LocalFavoriteInfo info = new LocalFavoriteInfo(gi);
                        info.setTime(i);
                        dao.insert(info);
                        cursor.moveToNext();
                        i++;
                    }
                }
                cursor.close();
            }
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            // Ignore
        }


        // Merge quick search
        try {
            Cursor cursor = oldDB.rawQuery("select * from " + OldDBHelper.TABLE_TAG, null);
            if (cursor != null) {
                QuickSearchDao dao = getDatabase().quickSearchDao();
                if (cursor.moveToFirst()) {
                    while (!cursor.isAfterLast()) {
                        QuickSearch quickSearch = new QuickSearch();

                        int mode = cursor.getInt(2);
                        String search = cursor.getString(4);
                        String tag = cursor.getString(7);
                        if (mode == ListUrlBuilder.MODE_UPLOADER && search != null &&
                                search.startsWith("uploader:")) {
                            search = search.substring("uploader:".length());
                        }

                        quickSearch.setTime((long) cursor.getInt(0));
                        quickSearch.setName(cursor.getString(1));
                        quickSearch.setMode(mode);
                        quickSearch.setCategory(cursor.getInt(3));
                        quickSearch.setKeyword(mode == ListUrlBuilder.MODE_TAG ? tag : search);
                        quickSearch.setAdvanceSearch(cursor.getInt(5));
                        quickSearch.setMinRating(cursor.getInt(6));

                        dao.insert(quickSearch);
                        cursor.moveToNext();
                    }
                }
                cursor.close();
            }
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            // Ignore
        }

        // Merge download info
        try {
            Cursor cursor = oldDB.rawQuery("select * from " + OldDBHelper.TABLE_DOWNLOAD, null);
            if (cursor != null) {
                DownloadsDao dao = getDatabase().downloadsDao();
                if (cursor.moveToFirst()) {
                    long i = 0L;
                    while (!cursor.isAfterLast()) {
                        // Get GalleryInfo first
                        long gid = cursor.getInt(0);
                        GalleryInfo gi = map.get(gid);
                        if (gi == null) {
                            Log.e(TAG, "Can't get GalleryInfo with gid: " + gid);
                            cursor.moveToNext();
                            continue;
                        }

                        DownloadInfo info = new DownloadInfo(gi);
                        int state = cursor.getInt(2);
                        int legacy = cursor.getInt(3);
                        if (state == DownloadInfo.STATE_FINISH && legacy > 0) {
                            state = DownloadInfo.STATE_FAILED;
                        }
                        info.setState(state);
                        info.setLegacy(legacy);
                        if (cursor.getColumnCount() == 5) {
                            info.setTime(cursor.getLong(4));
                        } else {
                            info.setTime(i);
                        }
                        dao.insertOrReplace(info);
                        cursor.moveToNext();
                        i++;
                    }
                }
                cursor.close();
            }
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            // Ignore
        }

        try {
            // Merge history info
            Cursor cursor = oldDB.rawQuery("select * from " + OldDBHelper.TABLE_HISTORY, null);
            if (cursor != null) {
                HistoryDao dao = getDatabase().historyDao();
                if (cursor.moveToFirst()) {
                    while (!cursor.isAfterLast()) {
                        // Get GalleryInfo first
                        long gid = cursor.getInt(0);
                        GalleryInfo gi = map.get(gid);
                        if (gi == null) {
                            Log.e(TAG, "Can't get GalleryInfo with gid: " + gid);
                            cursor.moveToNext();
                            continue;
                        }

                        HistoryInfo info = new HistoryInfo(gi);
                        info.setMode(cursor.getInt(1));
                        info.setTime(cursor.getLong(2));
                        dao.insert(info);
                        cursor.moveToNext();
                    }
                }
                cursor.close();
            }
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            // Ignore
        }

        try {
            oldDBHelper.close();
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            // Ignore
        }
    }

    public static List<DownloadInfo> getAllDownloadInfo() {
        List<DownloadInfo> list = getDatabase().downloadsDao().loadAllOrderByTimeDesc();
        if (list == null) {
            list = new ArrayList<>();
        }
        // Fix state: downgrade DOWNLOAD to WAIT so interrupted downloads can resume
        // STATE_WAIT items are preserved so they stay in the download queue
        for (DownloadInfo info : list) {
            if (info.state == DownloadInfo.STATE_DOWNLOAD) {
                info.state = DownloadInfo.STATE_WAIT;
            }
            // STATE_WAIT stays as WAIT — no reset to NONE
        }
        return list;
    }

    @Nullable
    public static DownloadInfo getDownloadInfo(long gid) {
        try {
            return getDatabase().downloadsDao().load(gid);
        } catch (Exception e) {
            Analytics.recordException(e);
            return null;
        }
    }

    // -------- SystemDownloadTask (Android system DownloadManager mapping) --------

    @Nullable
    public static SystemDownloadTask getSystemDownloadTask(long downloadId) {
        try {
            return getDatabase().systemDownloadTaskDao().load(downloadId);
        } catch (Exception e) {
            Analytics.recordException(e);
            return null;
        }
    }

    @Nullable
    public static List<SystemDownloadTask> getSystemDownloadTasksForGid(long gid) {
        try {
            return getDatabase().systemDownloadTaskDao().findByGid(gid);
        } catch (Exception e) {
            Analytics.recordException(e);
            return null;
        }
    }

    @Nullable
    public static List<SystemDownloadTask> getActiveSystemDownloadTasks() {
        try {
            return getDatabase().systemDownloadTaskDao().findByStatuses(
                    Arrays.asList(SystemDownloadTask.STATUS_PENDING, SystemDownloadTask.STATUS_RUNNING));
        } catch (Exception e) {
            Analytics.recordException(e);
            return null;
        }
    }

    public static void putSystemDownloadTask(SystemDownloadTask task) {
        try {
            getDatabase().systemDownloadTaskDao().upsert(task);
        } catch (Exception e) {
            Analytics.recordException(e);
        }
    }

    public static void deleteSystemDownloadTask(long downloadId) {
        try {
            getDatabase().systemDownloadTaskDao().deleteByDownloadId(downloadId);
        } catch (Exception e) {
            Analytics.recordException(e);
        }
    }

    public static void deleteSystemDownloadTasksForGid(long gid) {
        try {
            getDatabase().systemDownloadTaskDao().deleteByGid(gid);
        } catch (Exception e) {
            Analytics.recordException(e);
        }
    }

    // -------- GalleryAiInfo (AI 图片分析，用于下载列表按描述搜索) --------

    public static void putGalleryAiInfo(GalleryAiInfo info) {
        try {
            getDatabase().galleryAiInfoDao().upsert(info);
        } catch (Exception e) {
            Analytics.recordException(e);
        }
    }

    @Nullable
    public static GalleryAiInfo queryGalleryAiInfo(long gid) {
        try {
            return getDatabase().galleryAiInfoDao().load(gid);
        } catch (Exception e) {
            Analytics.recordException(e);
            return null;
        }
    }

    /**
     * 按关键词搜索所有 AI 分析信息（summary / tags / descriptions 模糊匹配）。
     */
    @NonNull
    public static List<GalleryAiInfo> searchGalleryAiInfosByKeyword(String keyword) {
        List<GalleryAiInfo> result = new ArrayList<>();
        if (TextUtils.isEmpty(keyword)) {
            return result;
        }
        try {
            result = getDatabase().galleryAiInfoDao().searchByKeyword("%" + keyword.trim() + "%");
        } catch (Exception e) {
            Analytics.recordException(e);
        }
        return result;
    }

    public static void deleteGalleryAiInfo(long gid) {
        try {
            getDatabase().galleryAiInfoDao().deleteByGid(gid);
        } catch (Exception e) {
            Analytics.recordException(e);
        }
    }

    /**
     * 调整下载顺序（拖拽排序）。
     *
     * <p>时间戳的重排在内存里同步完成，保证 UI 立即响应；数据库写入放到后台，
     * 避免拖动时主线程被数据库事务拖住。
     */
    public static void moveDownloadInfo(List<DownloadInfo> infos, int fromPosition, int toPosition) {
        if (fromPosition == toPosition) {
            return;
        }
        boolean reverse = fromPosition > toPosition;
        int offset = reverse ? toPosition : fromPosition;
        int limit = reverse ? fromPosition - toPosition + 1 : toPosition - fromPosition + 1;

        List<DownloadInfo> list = infos.subList(offset, offset + limit);

        int step = reverse ? 1 : -1;
        int start = reverse ? limit - 1 : 0;
        int end = reverse ? 0 : limit - 1;
        long toTime = list.get(end).time;
        for (int i = end; reverse ? i < start : i > start; i += step) {
            list.get(i).setTime(list.get(i + step).getTime());
        }
        list.get(start).setTime(toTime);

        final List<DownloadInfo> changed = new ArrayList<>(list);
        sAsyncWriteExecutor.execute(() -> {
            try {
                getDatabase().downloadsDao().updateAll(changed);
            } catch (Throwable t) {
                Log.w(TAG, "moveDownloadInfo async write failed", t);
            }
        });
    }

    /**
     * 插入或更新下载记录。
     *
     * <p>旧实现是 load → 判空 → insert/update（依赖全局锁保证原子性）；
     * 拆锁后改用单条 upsert，天然没有竞态。
     */
    public static void putDownloadInfo(DownloadInfo downloadInfo) {
        getDatabase().downloadsDao().upsert(downloadInfo);
    }

    /**
     * 在一个数据库事务内执行批量写入。
     *
     * <p>逐条写入各自开事务会产生大量磁盘同步；设备同步（§5.30）一批最多 500 行，
     * 在主线程逐条写就是一次可感知的卡顿甚至 ANR。批量合并统一包进一次事务。
     */
    public static void runInTransaction(@NonNull Runnable action) {
        getDatabase().runInTransaction(action);
    }

    public static void removeDownloadInfo(long gid) {
        getDatabase().downloadsDao().deleteByGid(gid);
    }

    /**
     * 批量从数据库下载表中移除记录（单事务执行）。
     *
     * @param gids 待删除的画廊 GID 列表
     * @return 提交删除的条数
     */
    public static int removeDownloadInfoBulk(final List<Long> gids) {
        if (gids == null || gids.isEmpty()) {
            return 0;
        }
        DownloadsDao dao = getDatabase().downloadsDao();
        // SQLite 的绑定变量数量有上限（常见 999），分批删避免超限
        final int batchSize = 900;
        for (int start = 0; start < gids.size(); start += batchSize) {
            int end = Math.min(start + batchSize, gids.size());
            dao.deleteByGids(new ArrayList<>(gids.subList(start, end)));
        }
        return gids.size();
    }

    /**
     * 批量确保下载历史存在并标记为已删除（单事务执行）：
     * - 不存在的历史补一条 DELETION_NORMAL 删除记录；
     * - 已存在但仍处于"有效"状态（下载完成时写入的 DELETION_NONE）的记录
     *   同样标记为 DELETION_NORMAL 并写入 deletedAt；
     * - 合并记录（DELETION_DUPLICATE_MERGED / DELETION_PROGRESSIVE_MERGED）与
     *   已删除记录保持不变。
     *
     * @param infoList 被删除的下载信息列表
     * @return 新补充的下载历史条数
     */
    public static int ensureDownloadHistoryDeletedBulk(final List<DownloadInfo> infoList) {
        if (infoList == null || infoList.isEmpty()) {
            return 0;
        }

        // 事务外先取文件信息：这段要读文件系统，可能很慢，放进事务会拖长写锁持有时间
        final Map<Long, FileTokensInfo> fileTokensMap = new HashMap<>();
        try {
            DownloadedFileManager dfm = DownloadedFileManager.getInstance();
            for (DownloadInfo info : infoList) {
                FileTokensInfo tokensInfo = getFileTokensInfo(dfm, info.gid);
                if (tokensInfo != null) {
                    fileTokensMap.put(info.gid, tokensInfo);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get file tokens in bulk", e);
        }

        final DownloadHistoryDao historyDao = getDatabase().downloadHistoryDao();
        final int[] createdCount = new int[1];
        getDatabase().runInTransaction(() -> {
            long now = System.currentTimeMillis();
            List<DownloadHistory> toInsert = new ArrayList<>(infoList.size());
            List<DownloadHistory> toMarkDeleted = new ArrayList<>();
            for (DownloadInfo info : infoList) {
                DownloadHistory existing = historyDao.load(info.gid);
                if (existing != null) {
                    // 已存在的历史：仅把仍处于"有效"状态（DELETION_NONE）的记录标记为已删除，
                    // 合并记录与已删除记录保持不变
                    if (existing.getDeletionType() == DownloadHistory.DELETION_NONE) {
                        existing.setDeletionType(DownloadHistory.DELETION_NORMAL);
                        existing.setDeletedAt(now);
                        toMarkDeleted.add(existing);
                    }
                    continue;
                }
                DownloadHistory history = new DownloadHistory();
                history.setGid(info.gid);
                history.setToken(info.token);
                history.setTitle(info.title);
                history.setTitleJpn(info.titleJpn);
                history.setFilePath(getDownloadDirname(info.gid));
                history.setCompletedAt(now);
                history.setLastDownloadedAt(now);
                history.setDownloadCount(0);
                history.setDeletionType(DownloadHistory.DELETION_NORMAL);
                history.setMergedTargetGid(0);
                history.setDeletedAt(now);
                // 从预获取的 map 中获取文件信息
                FileTokensInfo tokensInfo = fileTokensMap.get(info.gid);
                if (tokensInfo != null) {
                    history.setTotalFiles(tokensInfo.totalFiles);
                    history.setDownloadedFiles(tokensInfo.downloadedFiles);
                    history.setFileTokens(tokensInfo.fileTokens);
                }
                toInsert.add(history);
            }
            if (!toInsert.isEmpty()) {
                historyDao.upsertAll(toInsert);
                createdCount[0] = toInsert.size();
            }
            if (!toMarkDeleted.isEmpty()) {
                historyDao.updateAll(toMarkDeleted);
            }
        });
        DownloadHistoryStatusResolver.invalidateAll();
        return createdCount[0];
    }

    /**
     * 将仍处于"有效"状态（DELETION_NONE）的下载历史标记为已删除（DELETION_NORMAL + deletedAt）。
     * 合并记录与已删除记录保持不变，历史不存在时不创建。
     */
    public static void markDownloadHistoryDeletedIfAlive(long gid) {
        final DownloadHistoryDao dao = getDatabase().downloadHistoryDao();
        final boolean[] changed = new boolean[1];
        getDatabase().runInTransaction(() -> {
            DownloadHistory history = dao.load(gid);
            if (history == null) {
                return;
            }
            if (history.getDeletionType() != DownloadHistory.DELETION_NONE) {
                return;
            }
            history.setDeletionType(DownloadHistory.DELETION_NORMAL);
            history.setDeletedAt(System.currentTimeMillis());
            dao.update(history);
            changed[0] = true;
        });
        if (changed[0]) {
            DownloadHistoryStatusResolver.invalidate(gid);
        }
    }

    /**
     * 文件 token 信息数据类
     */
    private static class FileTokensInfo {
        int totalFiles;
        int downloadedFiles;
        String fileTokens;
    }

    /**
     * 获取画廊的文件 token 信息
     */
    private static FileTokensInfo getFileTokensInfo(DownloadedFileManager dfm, long gid) {
        try {
            List<com.hippo.ehviewer.dao.DownloadedFile> files = dfm.getGalleryFiles(gid);
            if (files == null || files.isEmpty()) {
                return null;
            }
            FileTokensInfo info = new FileTokensInfo();
            info.totalFiles = files.size();
            int downloadedFiles = 0;
            StringBuilder tokensBuilder = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                com.hippo.ehviewer.dao.DownloadedFile file = files.get(i);
                if (file.getStatus() == com.hippo.ehviewer.dao.DownloadedFile.STATUS_NORMAL) {
                    downloadedFiles++;
                }
                if (i > 0) {
                    tokensBuilder.append(",");
                }
                tokensBuilder.append(file.getToken());
            }
            info.downloadedFiles = downloadedFiles;
            info.fileTokens = tokensBuilder.toString();
            return info;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get file tokens for gid=" + gid, e);
            return null;
        }
    }

    @Nullable
    public static DownloadHistory getDownloadHistory(long gid) {
        return getDatabase().downloadHistoryDao().load(gid);
    }

    public static boolean hasDownloadHistory(long gid) {
        return getDatabase().downloadHistoryDao().load(gid) != null;
    }

    public static long getDownloadHistoryCount() {
        return getDatabase().downloadHistoryDao().count();
    }

    public static List<DownloadHistory> getAllDownloadHistory() {
        return getDatabase().downloadHistoryDao().loadAll();
    }

    /**
     * 「递进传递」关系的一条边：{@code source} 的内容被传递/合并进了 {@code target}。
     */
    public static final class MergeEdge {
        public final long sourceGid;
        public final long targetGid;

        MergeEdge(long sourceGid, long targetGid) {
            this.sourceGid = sourceGid;
            this.targetGid = targetGid;
        }
    }

    /**
     * 一次性读出所有「递进/重复合并」关系边，供 {@link com.hippo.ehviewer.download.progressive
     * .ProgressiveStats} 建索引。
     *
     * <p>只统计 {@link DownloadHistory#DELETION_DUPLICATE_MERGED} 与
     * {@link DownloadHistory#DELETION_PROGRESSIVE_MERGED}；{@code DELETION_NORMAL}
     * （用户普通删除）不算递进传递。{@code MERGED_TARGET_GID <= 0} 表示没有合并目标，也跳过。
     *
     * <p>注意：这是全表扫描，调用方必须缓存结果，不能放在下载列表渲染这种热路径上逐条调。
     */
    public static List<MergeEdge> queryProgressiveMergeEdges() {
        List<MergeEdge> edges = new ArrayList<>();
        try {
            List<DownloadHistoryDao.MergeEdgeRow> rows = getDatabase().downloadHistoryDao()
                    .queryMergeEdges(DownloadHistory.DELETION_DUPLICATE_MERGED,
                            DownloadHistory.DELETION_PROGRESSIVE_MERGED);
            for (DownloadHistoryDao.MergeEdgeRow row : rows) {
                edges.add(new MergeEdge(row.sourceGid, row.targetGid));
            }
        } catch (Exception e) {
            Log.w(TAG, "queryProgressiveMergeEdges failed", e);
        }
        return edges;
    }

    public static void recordDownloadCompleted(DownloadInfo info, @Nullable String filePath) {
        final DownloadHistoryDao dao = getDatabase().downloadHistoryDao();
        getDatabase().runInTransaction(() -> {
            DownloadHistory history = dao.load(info.gid);
            long now = System.currentTimeMillis();
            if (history == null) {
                history = new DownloadHistory();
                history.setGid(info.gid);
                history.setCompletedAt(now);
                history.setDownloadCount(0);
            }
            history.setToken(info.token);
            history.setTitle(info.title);
            history.setTitleJpn(info.titleJpn);
            history.setFilePath(filePath);
            history.setLastDownloadedAt(now);
            history.setDownloadCount(history.getDownloadCount() + 1);
            history.setDeletionType(DownloadHistory.DELETION_NONE);
            history.setMergedTargetGid(0);
            history.setDeletedAt(0);
            dao.upsert(history);
        });
        DownloadHistoryStatusResolver.invalidate(info.gid);
    }

    public static void markDownloadHistoryDeleted(long gid, int deletionType, long mergedTargetGid) {
        final DownloadHistoryDao dao = getDatabase().downloadHistoryDao();
        final boolean[] changed = new boolean[1];
        getDatabase().runInTransaction(() -> {
            DownloadHistory history = dao.load(gid);
            if (history == null) {
                return;
            }
            history.setDeletionType(deletionType);
            history.setMergedTargetGid(mergedTargetGid);
            history.setDeletedAt(System.currentTimeMillis());
            dao.update(history);
            changed[0] = true;
        });
        if (changed[0]) {
            ProgressiveStats.invalidate();
            DownloadHistoryStatusResolver.invalidate(gid);
        }
    }

    /**
     * 更新下载历史中的文件信息，用于预下载增量对比
     *
     * @param gid             画廊GID
     * @param totalFiles      总文件数
     * @param downloadedFiles 已下载文件数
     * @param fileTokens      JSON格式的文件token列表
     */
    public static void updateDownloadHistoryFileTokens(long gid, int totalFiles, int downloadedFiles,
                                                       @Nullable String fileTokens) {
        final DownloadHistoryDao dao = getDatabase().downloadHistoryDao();
        getDatabase().runInTransaction(() -> {
            DownloadHistory history = dao.load(gid);
            if (history == null) {
                // 如果历史记录不存在，创建一个新的
                history = new DownloadHistory();
                history.setGid(gid);
                history.setCompletedAt(System.currentTimeMillis());
                history.setLastDownloadedAt(System.currentTimeMillis());
                history.setDownloadCount(0);
                history.setDeletionType(DownloadHistory.DELETION_NORMAL);
                history.setMergedTargetGid(0);
                history.setDeletedAt(System.currentTimeMillis());
            }
            history.setTotalFiles(totalFiles);
            history.setDownloadedFiles(downloadedFiles);
            history.setFileTokens(fileTokens);
            dao.upsert(history);
        });
        DownloadHistoryStatusResolver.invalidate(gid);
    }

    /**
     * 为已删除的画廊保存文件信息到下载历史
     * 从 DownloadedFileManager 获取文件列表并保存到 DownloadHistory
     *
     * @param gid 画廊GID
     */
    public static void saveDeletedGalleryFileTokens(long gid) {
        try {
            // 从 DownloadedFileManager 获取文件列表
            List<com.hippo.ehviewer.dao.DownloadedFile> files =
                    DownloadedFileManager.getInstance().getGalleryFiles(gid);
            if (files == null || files.isEmpty()) {
                return;
            }
            int totalFiles = files.size();
            // 统计已下载的正常文件
            int downloadedFiles = 0;
            StringBuilder tokensBuilder = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                com.hippo.ehviewer.dao.DownloadedFile file = files.get(i);
                if (file.getStatus() == com.hippo.ehviewer.dao.DownloadedFile.STATUS_NORMAL) {
                    downloadedFiles++;
                }
                if (i > 0) {
                    tokensBuilder.append(",");
                }
                tokensBuilder.append(file.getToken());
            }
            updateDownloadHistoryFileTokens(gid, totalFiles, downloadedFiles, tokensBuilder.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save file tokens for gid=" + gid, e);
        }
    }

    /**
     * 将画廊标记为重复/递进关系而被跳过的记录。
     * 与 markDownloadHistoryDeleted 不同，此方法会在历史记录不存在时自动创建一条，
     * 以便旧任务从未下载完成也能在下载历史中标记为重复画廊。
     */
    public static void recordDownloadAsDuplicate(DownloadInfo info, int deletionType, long mergedTargetGid) {
        // 文件信息要读文件系统，先取好再进事务，避免长时间持有 SQLite 写事务
        FileTokensInfo prefetchedTokens = null;
        try {
            prefetchedTokens = getFileTokensInfo(DownloadedFileManager.getInstance(), info.gid);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save file tokens for gid=" + info.gid, e);
        }
        final FileTokensInfo tokensInfo = prefetchedTokens;

        final DownloadHistoryDao dao = getDatabase().downloadHistoryDao();
        getDatabase().runInTransaction(() -> {
            DownloadHistory history = dao.load(info.gid);
            long now = System.currentTimeMillis();
            boolean isNew = false;
            if (history == null) {
                history = new DownloadHistory();
                history.setGid(info.gid);
                history.setCompletedAt(now);
                history.setDownloadCount(0);
                isNew = true;
            }
            history.setToken(info.token);
            history.setTitle(info.title);
            history.setTitleJpn(info.titleJpn);
            history.setFilePath(getDownloadDirname(info.gid));
            history.setLastDownloadedAt(now);
            history.setDownloadCount(history.getDownloadCount() + 1);
            history.setDeletionType(deletionType);
            history.setMergedTargetGid(mergedTargetGid);
            history.setDeletedAt(now);
            // 如果是新记录，保存文件信息用于预下载增量对比
            if (isNew && tokensInfo != null) {
                history.setTotalFiles(tokensInfo.totalFiles);
                history.setDownloadedFiles(tokensInfo.downloadedFiles);
                history.setFileTokens(tokensInfo.fileTokens);
            }
            dao.upsert(history);
        });
        ProgressiveStats.invalidate();
        DownloadHistoryStatusResolver.invalidate(info.gid);
    }

    /**
     * 删除画廊时记录到下载历史（总是创建/更新一条记录，deletionType=DELETION_NORMAL）。
     */
    public static void recordDownloadDeleted(DownloadInfo info, long mergedTargetGid) {
        recordDownloadAsDuplicate(info, DownloadHistory.DELETION_NORMAL, mergedTargetGid);
    }

    /**
     * 扫描下载目录时，为磁盘上仍存在的画廊补建下载历史记录。
     * 仅在 DOWNLOAD_HISTORY 中不存在该 gid 时创建，避免覆盖已有历史。
     *
     * @param gid     画廊 GID
     * @param token   画廊 token（可以为 null）
     * @param title   画廊标题
     * @param dirname 画廊所在下载目录名
     */
    public static void recordDownloadHistoryFromScan(long gid, @Nullable String token,
                                                     @Nullable String title, @Nullable String dirname) {
        final DownloadHistoryDao dao = getDatabase().downloadHistoryDao();
        final boolean[] inserted = new boolean[1];
        getDatabase().runInTransaction(() -> {
            if (dao.load(gid) != null) {
                return;
            }
            long now = System.currentTimeMillis();
            DownloadHistory history = new DownloadHistory();
            history.setGid(gid);
            history.setToken(token);
            history.setTitle(title);
            history.setTitleJpn(null);
            history.setFilePath(dirname);
            history.setCompletedAt(now);
            history.setLastDownloadedAt(now);
            history.setDownloadCount(1);
            history.setDeletionType(DownloadHistory.DELETION_NONE);
            history.setMergedTargetGid(0);
            history.setDeletedAt(0);
            dao.insert(history);
            inserted[0] = true;
        });
        if (inserted[0]) {
            DownloadHistoryStatusResolver.invalidate(gid);
        }
    }

    /**
     * 批量扫描下载目录时，为磁盘上仍存在的画廊补建下载历史记录。
     * 仅在 DOWNLOAD_HISTORY 中不存在该 gid 时创建，避免覆盖已有历史。
     * 此方法要求 token 必须非空，用于确保下载历史的完整性。
     *
     * @param gid     画廊 GID
     * @param token   画廊 token（必须非空）
     * @param title   画廊标题
     * @param dirname 画廊所在下载目录名（可选）
     * @return 是否成功插入或更新
     */
    public static boolean recordDownloadHistoryWithToken(long gid, @NonNull String token,
                                                         @Nullable String title, @Nullable String dirname) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        // 文件信息在事务外预取
        FileTokensInfo prefetchedTokens = null;
        try {
            prefetchedTokens = getFileTokensInfo(DownloadedFileManager.getInstance(), gid);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save file tokens for gid=" + gid, e);
        }
        final FileTokensInfo tokensInfo = prefetchedTokens;

        final DownloadHistoryDao dao = getDatabase().downloadHistoryDao();
        getDatabase().runInTransaction(() -> {
            DownloadHistory history = dao.load(gid);
            boolean isNew = (history == null);
            long now = System.currentTimeMillis();
            if (isNew) {
                history = new DownloadHistory();
                history.setGid(gid);
                history.setCompletedAt(now);
                history.setLastDownloadedAt(now);
                history.setDownloadCount(1);
                history.setDeletionType(DownloadHistory.DELETION_NONE);
                history.setMergedTargetGid(0);
                history.setDeletedAt(0);
                // 新记录：设置 filePath
                if (dirname != null) {
                    history.setFilePath(dirname);
                }
            }
            // 更新基本信息
            history.setToken(token);
            history.setTitle(title);
            history.setTitleJpn(null);
            // 如果有新传入的 dirname 且当前没有，设置 filePath
            if (dirname != null && (history.getFilePath() == null || history.getFilePath().isEmpty())) {
                history.setFilePath(dirname);
            }
            // 文件信息已在事务外取好
            if (tokensInfo != null) {
                history.setTotalFiles(tokensInfo.totalFiles);
                history.setDownloadedFiles(tokensInfo.downloadedFiles);
                history.setFileTokens(tokensInfo.fileTokens);
            }
            if (isNew) {
                dao.insert(history);
            } else {
                dao.update(history);
            }
        });
        DownloadHistoryStatusResolver.invalidate(gid);
        return true;
    }

    /**
     * 批量为缺失 token 的下载历史记录补全 token。
     *
     * @param gid   画廊 GID
     * @param token 画廊 token
     * @return 是否成功更新
     */
    public static boolean updateDownloadHistoryToken(long gid, @NonNull String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        final DownloadHistoryDao dao = getDatabase().downloadHistoryDao();
        final boolean[] updated = new boolean[1];
        getDatabase().runInTransaction(() -> {
            DownloadHistory history = dao.load(gid);
            if (history == null) {
                return;
            }
            history.setToken(token);
            dao.update(history);
            updated[0] = true;
        });
        return updated[0];
    }

    /**
     * 批量检查哪些 GID 已在 DOWNLOAD_HISTORY 中存在。
     *
     * @param gids GID 列表
     * @return 存在的 GID 集合
     */
    public static Set<Long> getExistingDownloadHistoryGids(List<Long> gids) {
        Set<Long> existing = new HashSet<>();
        if (gids == null || gids.isEmpty()) {
            return existing;
        }
        DownloadHistoryDao dao = getDatabase().downloadHistoryDao();
        // 单条 IN 查询取代逐条 load；按 SQLite 绑定变量上限分批
        final int batchSize = 900;
        for (int start = 0; start < gids.size(); start += batchSize) {
            int end = Math.min(start + batchSize, gids.size());
            List<Long> found = dao.findExistingGids(new ArrayList<>(gids.subList(start, end)));
            if (found != null) {
                existing.addAll(found);
            }
        }
        return existing;
    }

    @Nullable
    public static String getDownloadDirname(long gid) {
        // Check cache first
        String cached = sDownloadDirnameCache.get(gid);
        if (cached != null) {
            return cached;
        }

        DownloadDirname raw = getDatabase().downloadDirnameDao().load(gid);
        if (raw != null) {
            String dirname = raw.getDirname();
            sDownloadDirnameCache.put(gid, dirname);
            return dirname;
        }
        return null;
    }

    /**
     * Insert or update
     */
    public static void putDownloadDirname(long gid, String dirname) {
        DownloadDirnameDao dao = getDatabase().downloadDirnameDao();
        DownloadDirname raw = new DownloadDirname(gid);
        raw.setDirname(dirname);
        dao.upsert(raw);
        sDownloadDirnameCache.put(gid, dirname);
    }

    public static void removeDownloadDirname(long gid) {
        getDatabase().downloadDirnameDao().deleteByGid(gid);
        sDownloadDirnameCache.remove(gid);
    }

    public static void updateDownloadDirname(long removeGid, long newGid, String dirname) {
        DownloadDirnameDao dao = getDatabase().downloadDirnameDao();
        getDatabase().runInTransaction(() -> {
            dao.deleteByGid(removeGid);
            DownloadDirname raw = new DownloadDirname(newGid);
            raw.setDirname(dirname);
            dao.upsert(raw);
        });
        sDownloadDirnameCache.remove(removeGid);
        sDownloadDirnameCache.put(newGid, dirname);
    }

    public static void clearDownloadDirname() {
        getDatabase().downloadDirnameDao().deleteAll();
        sDownloadDirnameCache.clear();
    }

    // ==================== PtokensIndex ====================

    public static PtokensIndex getPtokensIndex(long gid) {
        return getDatabase().ptokensIndexDao().load(gid);
    }

    public static List<PtokensIndex> getAllPtokensIndex() {
        return getDatabase().ptokensIndexDao().loadAll();
    }

    /**
     * 获取 ptoken 索引表的总行数。
     * 避免为了判断是否为空而使用 {@link #getAllPtokensIndex()} 全量载入内存。
     */
    public static long getPtokensIndexCount() {
        return getDatabase().ptokensIndexDao().count();
    }

    /**
     * 分批获取 ptoken 索引，避免一次把整张表载入内存导致 OOM。
     *
     * @param offset 起始偏移
     * @param limit  本批最大条数
     * @return 该批次的索引条目（可能少于 limit）
     */
    public static List<PtokensIndex> getPtokensIndexBatch(int offset, int limit) {
        return getDatabase().ptokensIndexDao().loadBatch(offset, limit);
    }

    public static void putPtokensIndex(long gid, String ptokens, int pages) {
        PtokensIndex entry = new PtokensIndex(gid, ptokens, pages, System.currentTimeMillis());
        getDatabase().ptokensIndexDao().upsert(entry);
    }

    public static void removePtokensIndex(long gid) {
        getDatabase().ptokensIndexDao().deleteByGid(gid);
    }

    public static void clearPtokensIndex() {
        getDatabase().ptokensIndexDao().deleteAll();
    }

    @NonNull
    public static List<DownloadLabel> getAllDownloadLabelList() {
        return getDatabase().downloadLabelDao().loadAllOrderByTimeAsc();
    }

    public static DownloadLabel addDownloadLabel(String label) {
        DownloadLabelDao dao = getDatabase().downloadLabelDao();
        DownloadLabel existing = dao.findByLabel(label);
        if (existing != null) {
            return existing;
        }

        DownloadLabel raw = new DownloadLabel();
        raw.setLabel(label);
        raw.setTime(System.currentTimeMillis());
        raw.setId(dao.insert(raw));
        return raw;
    }

    public static DownloadLabel addDownloadLabel(DownloadLabel raw) {
        // Reset id
        raw.setId(null);
        DownloadLabelDao dao = getDatabase().downloadLabelDao();
        raw.setId(dao.insert(raw));
        return raw;
    }

    public static void updateDownloadLabel(DownloadLabel raw) {
        getDatabase().downloadLabelDao().update(raw);
    }

    public static void moveDownloadLabel(int fromPosition, int toPosition) {
        if (fromPosition == toPosition) {
            return;
        }

        boolean reverse = fromPosition > toPosition;
        int offset = reverse ? toPosition : fromPosition;
        int limit = reverse ? fromPosition - toPosition + 1 : toPosition - fromPosition + 1;

        DownloadLabelDao dao = getDatabase().downloadLabelDao();
        getDatabase().runInTransaction(() -> {
            List<DownloadLabel> list = dao.loadRange(offset, limit);

            int step = reverse ? 1 : -1;
            int start = reverse ? limit - 1 : 0;
            int end = reverse ? 0 : limit - 1;
            long toTime = list.get(end).getTime();
            for (int i = end; reverse ? i < start : i > start; i += step) {
                list.get(i).setTime(list.get(i + step).getTime());
            }
            list.get(start).setTime(toTime);

            dao.updateAll(list);
        });
    }

    public static void removeDownloadLabel(DownloadLabel raw) {
        getDatabase().downloadLabelDao().delete(raw);
    }

    public static List<GalleryInfo> getAllLocalFavorites() {
        List<LocalFavoriteInfo> list = getDatabase().localFavoritesDao().loadAllOrderByTimeDesc();
        return new ArrayList<>(list);
    }

    public static List<GalleryInfo> searchLocalFavorites(String query) {
        List<LocalFavoriteInfo> list = getDatabase().localFavoritesDao()
                .searchByTitle("%" + query + "%");
        List<GalleryInfo> result = new ArrayList<>(list);
        appendSemanticFavorites(result, query);
        return result;
    }

    /**
     * 语义搜索补充召回（实验室功能）。
     *
     * <p>原有 LIKE 匹配只能命中标题字面包含关键词的条目；启用语义搜索后，这里会
     * 额外补上「语义相近但字面不匹配」的收藏，例如中文关键词命中日文/英文标题。
     * 功能关闭、索引为空或推理失败时完全不改变原有行为。
     */
    private static void appendSemanticFavorites(List<GalleryInfo> target, String rawQuery) {
        try {
            if (!SemanticSearchManager.isEnabled() || rawQuery == null || rawQuery.trim().isEmpty()) {
                return;
            }
            List<SemanticRanker.ScoredGallery> hits = SemanticRanker.semanticSearch(rawQuery, 60);
            if (hits.isEmpty()) {
                return;
            }
            Set<Long> existing = new HashSet<>();
            for (GalleryInfo info : target) {
                existing.add(info.gid);
            }
            LocalFavoritesDao dao = getDatabase().localFavoritesDao();
            for (SemanticRanker.ScoredGallery hit : hits) {
                if (existing.contains(hit.gid)) {
                    continue;
                }
                // 只补充确实存在于本地收藏中的条目，避免语义索引命中下载列表时串库
                LocalFavoriteInfo info = dao.load(hit.gid);
                if (info != null) {
                    target.add(info);
                    existing.add(hit.gid);
                }
            }
        } catch (Throwable t) {
            // 语义搜索是可选增强，任何异常都不应影响原有搜索
            Log.w(TAG, "appendSemanticFavorites failed: " + t);
        }
    }

    public static GalleryInfo searchLocalFavorites(long query) {
        return getDatabase().localFavoritesDao().findByGidOrderByTimeDesc(query);
    }

    public static void removeLocalFavorites(long gid) {
        getDatabase().localFavoritesDao().deleteByGid(gid);
    }

    public static void removeLocalFavorites(long[] gidArray) {
        List<Long> gids = new ArrayList<>(gidArray.length);
        for (long gid : gidArray) {
            gids.add(gid);
        }
        LocalFavoritesDao dao = getDatabase().localFavoritesDao();
        // 单条 IN 删除，Room 内部使用一个事务；按绑定变量上限分批
        final int batchSize = 900;
        for (int start = 0; start < gids.size(); start += batchSize) {
            int end = Math.min(start + batchSize, gids.size());
            dao.deleteByGids(new ArrayList<>(gids.subList(start, end)));
        }
    }

    public static boolean containLocalFavorites(long gid) {
        return getDatabase().localFavoritesDao().countByGid(gid) > 0;
    }

    public static void putLocalFavorite(GalleryInfo galleryInfo) {
        LocalFavoritesDao dao = getDatabase().localFavoritesDao();
        LocalFavoriteInfo info;
        if (galleryInfo instanceof LocalFavoriteInfo) {
            info = (LocalFavoriteInfo) galleryInfo;
        } else {
            info = new LocalFavoriteInfo(galleryInfo);
            info.time = System.currentTimeMillis();
        }
        // INSERT OR IGNORE：已存在则保留旧记录，等价于旧的 load→判空→insert
        dao.insertIgnore(info);
    }

    public static void putLocalFavorites(List<GalleryInfo> galleryInfoList) {
        LocalFavoritesDao dao = getDatabase().localFavoritesDao();
        List<LocalFavoriteInfo> toInsert = new ArrayList<>(galleryInfoList.size());
        long now = System.currentTimeMillis();
        for (GalleryInfo gi : galleryInfoList) {
            if (gi instanceof LocalFavoriteInfo) {
                toInsert.add((LocalFavoriteInfo) gi);
            } else {
                LocalFavoriteInfo info = new LocalFavoriteInfo(gi);
                info.time = now;
                toInsert.add(info);
            }
        }
        dao.insertAllIgnore(toInsert);
    }

    // ==================== 收藏批量操作的异步入口 ====================
    // 本地收藏多选删除/移动、云端失败回滚等操作可能涉及成百上千条记录，
    // 旧实现直接在对话框点击回调（主线程）里同步写库，容易长时间占用全局锁。

    public static void removeLocalFavoritesAsync(@Nullable long[] gidArray, @Nullable Runnable onComplete) {
        if (gidArray == null || gidArray.length == 0) {
            postMain(onComplete);
            return;
        }
        final long[] copy = gidArray.clone();
        sAsyncWriteExecutor.execute(() -> {
            try {
                removeLocalFavorites(copy);
            } catch (Throwable t) {
                Log.w(TAG, "removeLocalFavoritesAsync failed", t);
            }
            postMain(onComplete);
        });
    }

    public static void putLocalFavoritesAsync(@Nullable List<GalleryInfo> galleryInfoList,
                                              @Nullable Runnable onComplete) {
        if (galleryInfoList == null || galleryInfoList.isEmpty()) {
            postMain(onComplete);
            return;
        }
        final List<GalleryInfo> copy = new ArrayList<>(galleryInfoList);
        sAsyncWriteExecutor.execute(() -> {
            try {
                putLocalFavorites(copy);
            } catch (Throwable t) {
                Log.w(TAG, "putLocalFavoritesAsync failed", t);
            }
            postMain(onComplete);
        });
    }

    private static void postMain(@Nullable Runnable action) {
        if (action != null) {
            com.hippo.lib.yorozuya.SimpleHandler.getInstance().post(action);
        }
    }


    public static List<BlackList> getAllBlackList() {
        return getDatabase().blackListDao().loadAllOrderByAddTimeAsc();
    }

    public static boolean inBlackList(String badgayname) {
        // 参数化查询：旧实现把用户输入直接拼进 SQL，存在注入风险
        return getDatabase().blackListDao().countByBadgayname(badgayname) != 0;
    }

    public static void insertBlackList(BlackList blackList) {
        blackList.id = null;
        if (blackList.badgayname == null) {
            return;
        }
        getDatabase().blackListDao().insert(blackList);
    }

    public static void updateBlackList(BlackList blackList) {
        getDatabase().blackListDao().update(blackList);
    }

    public static void deleteBlackList(BlackList blackList) {
        getDatabase().blackListDao().delete(blackList);
    }

    public static List<GalleryTags> getAllGalleryTags() {
        GalleryTagsDao dao = getDatabase().galleryTagsDao();
        try {
            return dao.loadAllOrderByGidAsc();
        } catch (Exception e) {
            Log.w(TAG, "getAllGalleryTags failed", e);
            return new ArrayList<>();
        }
    }

    public static boolean inGalleryTags(long gid) {
        try {
            return getDatabase().galleryTagsDao().countByGid(gid) != 0;
        } catch (Exception e) {
            Log.w(TAG, "inGalleryTags failed for gid=" + gid, e);
            return false;
        }
    }

    /**
     * 所有已有标签行的 gid。
     *
     * <p>用于「标签规则能看到多少条目」的统计（排除面板的提示），一次查询替代逐条
     * {@link #inGalleryTags(long)}，避免几千次 DB 往返。
     */
    public static Set<Long> getAllGalleryTagGids() {
        Set<Long> gids = new HashSet<>();
        try {
            List<Long> list = getDatabase().galleryTagsDao().loadAllGids();
            if (list != null) {
                gids.addAll(list);
            }
        } catch (Exception e) {
            Log.w(TAG, "getAllGalleryTagGids failed", e);
        }
        return gids;
    }

    public static GalleryTags queryGalleryTags(long gid) {
        try {
            return getDatabase().galleryTagsDao().load(gid);
        } catch (Exception e) {
            Log.w(TAG, "queryGalleryTags failed for gid=" + gid, e);
            return null;
        }
    }

    public static void insertGalleryTags(GalleryTags galleryTags) {
        galleryTags.create_time = new Date();
        galleryTags.update_time = galleryTags.create_time;
        getDatabase().galleryTagsDao().insert(galleryTags);
        TagSuggestRepository.invalidate();
    }

    public static void updateGalleryTags(GalleryTags galleryTags) {
        galleryTags.update_time = new Date();
        getDatabase().galleryTagsDao().update(galleryTags);
        TagSuggestRepository.invalidate();
    }

    public static void deleteGalleryTags(GalleryTags galleryTags) {
        getDatabase().galleryTagsDao().delete(galleryTags);
        TagSuggestRepository.invalidate();
    }

    public static List<QuickSearch> getAllQuickSearch() {
        return getDatabase().quickSearchDao().loadAllOrderByTimeAsc();
    }

    public static void insertQuickSearch(QuickSearch quickSearch) {
        QuickSearchDao dao = getDatabase().quickSearchDao();
        quickSearch.id = null;
        if (quickSearch.time == 0L) {
            quickSearch.time = System.currentTimeMillis();
        }
        quickSearch.id = dao.insert(quickSearch);
    }

    public static void insertQuickSearchList(List<QuickSearch> quickSearchList) {
        QuickSearchDao dao = getDatabase().quickSearchDao();
        for (int i = 0; i < quickSearchList.size(); i++) {
            QuickSearch search = quickSearchList.get(i);
            search.id = null;
            search.time = System.currentTimeMillis();
            search.id = dao.insert(search);
        }
    }

    public static void takeOverQuickSearchList(List<QuickSearch> quickSearchList) {
        QuickSearchDao dao = getDatabase().quickSearchDao();
        getDatabase().runInTransaction(() -> {
            List<QuickSearch> allList = dao.loadAllOrderByTimeAsc();
            for (int i = 0; i < quickSearchList.size(); i++) {
                QuickSearch newSearch = quickSearchList.get(i);
                if (newSearch == null) {
                    continue;
                }
                boolean insert = true;
                for (int j = 0; j < allList.size(); j++) {
                    QuickSearch exist = allList.get(j);
                    if (exist != null && Objects.equals(exist.keyword, newSearch.keyword)) {
                        insert = false;
                        break;
                    }
                }
                if (insert) {
                    newSearch.id = null;
                    newSearch.time = System.currentTimeMillis();
                    newSearch.id = dao.insert(newSearch);
                }
            }
        });
    }

    public static void updateQuickSearch(QuickSearch quickSearch) {
        getDatabase().quickSearchDao().update(quickSearch);
    }

    /**
     * 批量更新快速搜索条目（单事务）。
     * 书签抽屉打开时会重写大量翻译后的名称，逐条 update 在主线程就是 N 次事务。
     */
    public static void updateQuickSearchBulkAsync(@Nullable List<QuickSearch> quickSearchList) {
        if (quickSearchList == null || quickSearchList.isEmpty()) {
            return;
        }
        final List<QuickSearch> copy = new ArrayList<>(quickSearchList);
        sAsyncWriteExecutor.execute(() -> {
            try {
                getDatabase().quickSearchDao().updateAll(copy);
            } catch (Throwable t) {
                Log.w(TAG, "updateQuickSearchBulkAsync failed", t);
            }
        });
    }

    public static void deleteQuickSearch(QuickSearch quickSearch) {
        getDatabase().quickSearchDao().delete(quickSearch);
    }

    /**
     * 快速搜索项移动方法
     * 用于调整快速搜索项的顺序，通过时间戳来实现位置调整
     *
     * @param fromPosition 起始位置
     * @param toPosition   目标位置
     */
    public static void moveQuickSearch(int fromPosition, int toPosition) {
        // 如果起始位置和目标位置相同，则直接返回
        if (fromPosition == toPosition) {
            return;
        }

        // 判断是否需要反向移动
        boolean reverse = fromPosition > toPosition;
        // 计算偏移量，用于查询数据库时的起始位置
        int offset = reverse ? toPosition : fromPosition;
        // 计算需要移动的项目数量
        int limit = reverse ? fromPosition - toPosition + 1 : toPosition - fromPosition + 1;

        QuickSearchDao dao = getDatabase().quickSearchDao();
        // 查询 + 时间戳轮换 + 回写必须是同一个事务，否则并发排序会互相覆盖
        getDatabase().runInTransaction(() -> {
            // 查询需要移动的搜索项列表，按时间升序排列
            List<QuickSearch> list = dao.loadRange(offset, limit);

            // 设置移动方向和起始、结束位置
            int step = reverse ? 1 : -1;
            int start = reverse ? limit - 1 : 0;
            int end = reverse ? 0 : limit - 1;
            // 获取目标位置的时间戳
            long toTime = list.get(end).getTime();
            // 遍历列表，交换相邻项的时间戳，实现位置移动
            for (int i = end; reverse ? i < start : i > start; i += step) {
                list.get(i).setTime(list.get(i + step).getTime());
            }
            // 将起始项的时间设置为目标时间
            list.get(start).setTime(toTime);

            // 在事务中更新所有更改
            dao.updateAll(list);
        });
    }

    /**
     * 一次性读出全部历史记录（按时间倒序）。
     *
     * <p>历史页旧实现使用 greenDAO 的 LazyList，滚动到未缓存区间时会在主线程逐行
     * 查库；条目多时表现为滚动掉帧。整表一次读出（约几百条）后纯内存访问。
     */
    @NonNull
    public static List<HistoryInfo> getHistoryList() {
        return getDatabase().historyDao().loadAllOrderByTimeDesc();
    }

    // 历史写入专用串行执行器：详情页打开、清空历史等 UI 操作不再占用主线程做 DB 事务。
    private static final java.util.concurrent.ExecutorService sAsyncWriteExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "EhDB-Async");
                t.setDaemon(true);
                return t;
            });

    /**
     * 异步写入历史记录。
     *
     * <p>详情页旧实现在 onCreate 里同步 {@link #putHistoryInfo(GalleryInfo)}，
     * 每打开一个画廊就在主线程做一次 DB 读 + 写（必要时还要裁剪旧记录）。
     */
    public static void putHistoryInfoAsync(@Nullable GalleryInfo galleryInfo) {
        if (galleryInfo == null) {
            return;
        }
        final GalleryInfo info = galleryInfo;
        sAsyncWriteExecutor.execute(() -> {
            try {
                putHistoryInfo(info);
            } catch (Throwable t) {
                Log.w(TAG, "putHistoryInfoAsync failed", t);
            }
        });
    }

    /** 异步清空历史记录；[onComplete] 在操作结束后回主线程执行。 */
    public static void clearHistoryInfoAsync(@Nullable Runnable onComplete) {
        sAsyncWriteExecutor.execute(() -> {
            try {
                clearHistoryInfo();
            } catch (Throwable t) {
                Log.w(TAG, "clearHistoryInfoAsync failed", t);
            }
            if (onComplete != null) {
                com.hippo.lib.yorozuya.SimpleHandler.getInstance().post(onComplete);
            }
        });
    }

    public static void putHistoryInfo(GalleryInfo galleryInfo) {
        final HistoryDao dao = getDatabase().historyDao();
        final boolean[] isNew = new boolean[1];
        getDatabase().runInTransaction(() -> {
            HistoryInfo info = dao.load(galleryInfo.gid);
            isNew[0] = (info == null);
            if (!isNew[0]) {
                // 已有记录：只更新时间
                info.time = System.currentTimeMillis();
                dao.update(info);
            } else {
                // 新记录：插入后按保留条数裁剪
                info = new HistoryInfo(galleryInfo);
                info.time = System.currentTimeMillis();
                dao.insert(info);
                int keep = MAX_HISTORY_COUNT < 1 ? 100 : MAX_HISTORY_COUNT;
                dao.deleteBeyond(keep);
            }
        });
        if (isNew[0]) {
            notifyHistoryAdded(galleryInfo.gid);
        }
    }

    private static final List<HistoryListener> sHistoryListeners = new CopyOnWriteArrayList<>();

    public interface HistoryListener {
        void onHistoryAdded(long gid);
    }

    public static void addHistoryListener(HistoryListener listener) {
        if (listener != null && !sHistoryListeners.contains(listener)) {
            sHistoryListeners.add(listener);
        }
    }

    public static void removeHistoryListener(HistoryListener listener) {
        sHistoryListeners.remove(listener);
    }

    private static void notifyHistoryAdded(long gid) {
        for (HistoryListener l : sHistoryListeners) {
            l.onHistoryAdded(gid);
        }
    }

    public static void putHistoryInfo(List<HistoryInfo> historyInfoList) {
        final HistoryDao dao = getDatabase().historyDao();
        getDatabase().runInTransaction(() -> {
            for (HistoryInfo info : historyInfoList) {
                if (null == dao.load(info.gid)) {
                    dao.insert(info);
                }
            }

            dao.deleteBeyond(MAX_HISTORY_COUNT);
        });
    }

    public static void deleteHistoryInfo(HistoryInfo info) {
        getDatabase().historyDao().delete(info);
    }

    public static void clearHistoryInfo() {
        getDatabase().historyDao().deleteAll();
    }

    public static List<Filter> getAllFilter() {
        return getDatabase().filterDao().loadAll();
    }

    public static void addFilter(Filter filter) {
        FilterDao dao = getDatabase().filterDao();
        filter.setId(null);
        filter.setId(dao.insert(filter));
    }

    public static void deleteFilter(Filter filter) {
        getDatabase().filterDao().delete(filter);
    }

    public static void triggerFilter(Filter filter) {
        filter.setEnable(!filter.enable);
        getDatabase().filterDao().update(filter);
    }

    // ==================== 导入 / 导出 ====================

    /** 导出数据库（当前 schema 版本）。 */
    public static boolean exportDB(Context context, File file) {
        return exportDatabase(context, file, "eh.export.db", false);
    }

    /**
     * 导出兼容旧版的数据库。
     *
     * <p>旧 BiLi PC Gamer 版本按 schema v7 读取导出文件，因此写完后把 user_version
     * 置为 7；表集合与旧实现一致（不含后来新增的 DOWNLOAD_HISTORY 等表）。
     */
    public static boolean exportLegacyDB(Context context, File file) {
        return exportDatabase(context, file, "eh.legacy.export.db", true);
    }

    private static boolean exportDatabase(Context context, File file, String exportName, boolean legacyVersion) {
        context.deleteDatabase(exportName);

        EhDatabase exportDb = null;
        try {
            // 用同一套 Room schema 建出导出库，再按实体逐表复制。
            // 不用 SQLite 的 ATTACH：框架的 SQLiteDatabase 连接池不保证后续语句
            // 落在同一个连接上，ATTACH 的 schema 可能随时丢失。
            exportDb = EhDatabase.open(context, exportName);
            final EhDatabase target = exportDb;
            exportDb.runInTransaction(() -> {
                target.downloadsDao().upsertAll(getDatabase().downloadsDao().loadAll());
                target.downloadLabelDao().insertAll(getDatabase().downloadLabelDao().loadAllOrderByTimeAsc());
                for (com.hippo.ehviewer.dao.DownloadDirname dirname
                        : getDatabase().downloadDirnameDao().loadAll()) {
                    target.downloadDirnameDao().upsert(dirname);
                }
                for (HistoryInfo history : getHistoryList()) {
                    target.historyDao().insert(history);
                }
                for (QuickSearch search : getAllQuickSearch()) {
                    target.quickSearchDao().insert(search);
                }
                for (LocalFavoriteInfo favorite : getDatabase().localFavoritesDao().loadAllOrderByTimeDesc()) {
                    target.localFavoritesDao().insert(favorite);
                }
                target.bookmarksDao().insertAll(getDatabase().bookmarksDao().loadAll());
                for (Filter filter : getAllFilter()) {
                    target.filterDao().insert(filter);
                }
            });
            exportDb.close();
            exportDb = null;

            File exportFile = context.getDatabasePath(exportName);
            if (exportFile == null || !exportFile.isFile()) {
                return false;
            }

            if (legacyVersion) {
                // 旧版本按 schema v7 读取导出文件
                SQLiteDatabase legacy = SQLiteDatabase.openDatabase(
                        exportFile.getPath(), null, SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                try {
                    legacy.setVersion(7);
                } finally {
                    legacy.close();
                }
            }

            // 复制到目标文件
            InputStream is = null;
            OutputStream os = null;
            try {
                is = new FileInputStream(exportFile);
                os = new FileOutputStream(file);
                IOUtils.copy(is, os);
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy export database", e);
            } finally {
                IOUtils.closeQuietly(is);
                IOUtils.closeQuietly(os);
            }
            // Delete failed file
            file.delete();
            return false;
        } catch (Throwable e) {
            Log.e(TAG, "exportDatabase failed", e);
            ExceptionUtils.throwIfFatal(e);
            return false;
        } finally {
            if (exportDb != null) {
                try {
                    exportDb.close();
                } catch (Throwable ignored) {
                }
            }
            context.deleteDatabase(exportName);
        }
    }

    /**
     * @param file The db file
     * @return error string, null for no error
     */
    public static String importDB(Context context, File file, Handler handler) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(
                    file.getPath(), null, SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            int newVersion = EhDatabase.VERSION;
            int oldVersion = db.getVersion();
            if (oldVersion > newVersion) {
                return context.getString(R.string.cant_read_the_file);
            }
            if (oldVersion < newVersion) {
                // 把导入文件的 schema 升到当前版本（原实现直接调 DBOpenHelper.onUpgrade）
                final SQLiteDatabase target = db;
                EhDbMigrations.migrateToCurrent(new EhDbMigrations.SqlExecutor() {
                    @Override
                    public void execSQL(String sql) {
                        target.execSQL(sql);
                    }

                    @Override
                    public void tryExecSQL(String sql) {
                        try {
                            target.execSQL(sql);
                        } catch (Throwable ignored) {
                        }
                    }
                }, oldVersion);
                db.setVersion(newVersion);
            }

            sendImportProgress(handler, 10);
            DownloadManager manager = EhApplication.getDownloadManager(context);

            // Download label
            List<DownloadLabel> downloadLabelList = readDownloadLabels(db);
            manager.addDownloadLabel(downloadLabelList);

            // Downloads
            List<DownloadInfo> downloadInfoList = readDownloadInfos(db);
            manager.addDownload(downloadInfoList);

            sendImportProgress(handler, 50);

            // Download dirname
            List<DownloadDirname> downloadDirnameList = readDownloadDirnames(db);
            for (DownloadDirname dirname : downloadDirnameList) {
                putDownloadDirname(dirname.getGid(), dirname.getDirname());
            }

            sendImportProgress(handler, 90);

            // History
            putHistoryInfo(readHistories(db));

            // QuickSearch
            List<QuickSearch> quickSearchList = readQuickSearches(db);
            List<QuickSearch> currentQuickSearchList = getAllQuickSearch();
            for (QuickSearch quickSearch : quickSearchList) {
                String name = quickSearch.name;
                for (QuickSearch q : currentQuickSearchList) {
                    if (ObjectUtils.equal(q.name, name)) {
                        // The same name
                        name = null;
                        break;
                    }
                }
                if (null == name) {
                    continue;
                }
                insertQuickSearch(quickSearch);
            }

            // LocalFavorites
            for (LocalFavoriteInfo info : readLocalFavorites(db)) {
                putLocalFavorite(info);
            }

            // Bookmarks
            // TODO

            // Filter
            List<Filter> currentFilterList = getAllFilter();
            for (Filter filter : readFilters(db)) {
                if (!currentFilterList.contains(filter)) {
                    addFilter(filter);
                }
            }

            List<BlackList> currentBlackList = getAllBlackList();
            for (BlackList black : readBlackLists(db)) {
                if (!currentBlackList.contains(black)) {
                    insertBlackList(black);
                }
            }

            List<GalleryTags> currentGalleryTags = getAllGalleryTags();
            for (GalleryTags tags : readGalleryTags(db)) {
                if (!currentGalleryTags.contains(tags)) {
                    insertGalleryTags(tags);
                }
            }

            return null;
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            Log.e(TAG, "importDB failed", e);
            return context.getString(R.string.cant_read_the_file);
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // -------- 导入用的原始读取（表可能不存在，逐表容错） --------

    private static List<DownloadLabel> readDownloadLabels(SQLiteDatabase db) {
        List<DownloadLabel> list = new ArrayList<>();
        readRows(db, "SELECT * FROM \"DOWNLOAD_LABELS\"", cursor -> {
            DownloadLabel label = new DownloadLabel();
            label.setId(cursor.isNull(cursor.getColumnIndex("_id")) ? null : cursor.getLong(cursor.getColumnIndex("_id")));
            label.setLabel(cursor.getString(cursor.getColumnIndex("LABEL")));
            label.setTime(cursor.getLong(cursor.getColumnIndex("TIME")));
            list.add(label);
        });
        return list;
    }

    private static List<DownloadInfo> readDownloadInfos(SQLiteDatabase db) {
        List<DownloadInfo> list = new ArrayList<>();
        readRows(db, "SELECT * FROM \"DOWNLOADS\"", cursor -> {
            DownloadInfo info = new DownloadInfo();
            info.gid = cursor.getLong(cursor.getColumnIndex("GID"));
            info.token = getStringOrNull(cursor, "TOKEN");
            info.title = getStringOrNull(cursor, "TITLE");
            info.titleJpn = getStringOrNull(cursor, "TITLE_JPN");
            info.thumb = getStringOrNull(cursor, "THUMB");
            info.category = cursor.getInt(cursor.getColumnIndex("CATEGORY"));
            info.posted = getStringOrNull(cursor, "POSTED");
            info.uploader = getStringOrNull(cursor, "UPLOADER");
            info.rating = cursor.getFloat(cursor.getColumnIndex("RATING"));
            info.simpleLanguage = getStringOrNull(cursor, "SIMPLE_LANGUAGE");
            info.state = cursor.getInt(cursor.getColumnIndex("STATE"));
            info.legacy = cursor.getInt(cursor.getColumnIndex("LEGACY"));
            info.time = cursor.getLong(cursor.getColumnIndex("TIME"));
            info.label = getStringOrNull(cursor, "LABEL");
            info.archiveUri = getStringOrNull(cursor, "ARCHIVE_URI");
            list.add(info);
        });
        return list;
    }

    private static List<DownloadDirname> readDownloadDirnames(SQLiteDatabase db) {
        List<DownloadDirname> list = new ArrayList<>();
        readRows(db, "SELECT * FROM \"DOWNLOAD_DIRNAME\"", cursor -> {
            DownloadDirname dirname = new DownloadDirname(cursor.getLong(cursor.getColumnIndex("GID")));
            dirname.setDirname(getStringOrNull(cursor, "DIRNAME"));
            list.add(dirname);
        });
        return list;
    }

    private static List<HistoryInfo> readHistories(SQLiteDatabase db) {
        List<HistoryInfo> list = new ArrayList<>();
        readRows(db, "SELECT * FROM \"HISTORY\"", cursor -> {
            HistoryInfo info = new HistoryInfo();
            info.gid = cursor.getLong(cursor.getColumnIndex("GID"));
            info.token = getStringOrNull(cursor, "TOKEN");
            info.title = getStringOrNull(cursor, "TITLE");
            info.titleJpn = getStringOrNull(cursor, "TITLE_JPN");
            info.thumb = getStringOrNull(cursor, "THUMB");
            info.category = cursor.getInt(cursor.getColumnIndex("CATEGORY"));
            info.posted = getStringOrNull(cursor, "POSTED");
            info.uploader = getStringOrNull(cursor, "UPLOADER");
            info.rating = cursor.getFloat(cursor.getColumnIndex("RATING"));
            info.simpleLanguage = getStringOrNull(cursor, "SIMPLE_LANGUAGE");
            info.mode = cursor.getInt(cursor.getColumnIndex("MODE"));
            info.time = cursor.getLong(cursor.getColumnIndex("TIME"));
            list.add(info);
        });
        return list;
    }

    private static List<QuickSearch> readQuickSearches(SQLiteDatabase db) {
        List<QuickSearch> list = new ArrayList<>();
        readRows(db, "SELECT * FROM \"QUICK_SEARCH\"", cursor -> {
            QuickSearch search = new QuickSearch();
            search.id = cursor.isNull(cursor.getColumnIndex("_id")) ? null : cursor.getLong(cursor.getColumnIndex("_id"));
            search.name = getStringOrNull(cursor, "NAME");
            search.mode = cursor.getInt(cursor.getColumnIndex("MODE"));
            search.category = cursor.getInt(cursor.getColumnIndex("CATEGORY"));
            search.keyword = getStringOrNull(cursor, "KEYWORD");
            search.advanceSearch = cursor.getInt(cursor.getColumnIndex("ADVANCE_SEARCH"));
            search.minRating = cursor.getInt(cursor.getColumnIndex("MIN_RATING"));
            search.pageFrom = cursor.getInt(cursor.getColumnIndex("PAGE_FROM"));
            search.pageTo = cursor.getInt(cursor.getColumnIndex("PAGE_TO"));
            search.time = cursor.getLong(cursor.getColumnIndex("TIME"));
            list.add(search);
        });
        return list;
    }

    private static List<LocalFavoriteInfo> readLocalFavorites(SQLiteDatabase db) {
        List<LocalFavoriteInfo> list = new ArrayList<>();
        readRows(db, "SELECT * FROM \"LOCAL_FAVORITES\"", cursor -> {
            LocalFavoriteInfo info = new LocalFavoriteInfo();
            info.gid = cursor.getLong(cursor.getColumnIndex("GID"));
            info.token = getStringOrNull(cursor, "TOKEN");
            info.title = getStringOrNull(cursor, "TITLE");
            info.titleJpn = getStringOrNull(cursor, "TITLE_JPN");
            info.thumb = getStringOrNull(cursor, "THUMB");
            info.category = cursor.getInt(cursor.getColumnIndex("CATEGORY"));
            info.posted = getStringOrNull(cursor, "POSTED");
            info.uploader = getStringOrNull(cursor, "UPLOADER");
            info.rating = cursor.getFloat(cursor.getColumnIndex("RATING"));
            info.simpleLanguage = getStringOrNull(cursor, "SIMPLE_LANGUAGE");
            info.time = cursor.getLong(cursor.getColumnIndex("TIME"));
            list.add(info);
        });
        return list;
    }

    private static List<Filter> readFilters(SQLiteDatabase db) {
        List<Filter> list = new ArrayList<>();
        readRows(db, "SELECT * FROM \"FILTER\"", cursor -> {
            Filter filter = new Filter();
            filter.setId(cursor.isNull(cursor.getColumnIndex("_id")) ? null : cursor.getLong(cursor.getColumnIndex("_id")));
            filter.setMode(cursor.getInt(cursor.getColumnIndex("MODE")));
            filter.setText(getStringOrNull(cursor, "TEXT"));
            int enableIndex = cursor.getColumnIndex("ENABLE");
            filter.setEnable(cursor.isNull(enableIndex) ? null : cursor.getInt(enableIndex) != 0);
            list.add(filter);
        });
        return list;
    }

    private static List<BlackList> readBlackLists(SQLiteDatabase db) {
        List<BlackList> list = new ArrayList<>();
        readRows(db, "SELECT * FROM \"Black_List\"", cursor -> {
            BlackList black = new BlackList();
            black.id = cursor.isNull(cursor.getColumnIndex("_id")) ? null : cursor.getLong(cursor.getColumnIndex("_id"));
            black.badgayname = getStringOrNull(cursor, "BADGAYNAME");
            black.reason = getStringOrNull(cursor, "REASON");
            black.angrywith = getStringOrNull(cursor, "ANGRYWITH");
            black.add_time = getStringOrNull(cursor, "ADD_TIME");
            int modeIndex = cursor.getColumnIndex("MODE");
            black.mode = cursor.isNull(modeIndex) ? null : cursor.getInt(modeIndex);
            list.add(black);
        });
        return list;
    }

    private static List<GalleryTags> readGalleryTags(SQLiteDatabase db) {
        List<GalleryTags> list = new ArrayList<>();
        readRows(db, "SELECT * FROM \"Gallery_Tags\"", cursor -> {
            GalleryTags tags = new GalleryTags(cursor.getLong(cursor.getColumnIndex("GID")));
            tags.rows = getStringOrNull(cursor, "ROWS");
            tags.artist = getStringOrNull(cursor, "ARTIST");
            tags.cosplayer = getStringOrNull(cursor, "COSPLAYER");
            tags.character = getStringOrNull(cursor, "CHARACTER");
            tags.female = getStringOrNull(cursor, "FEMALE");
            tags.group = getStringOrNull(cursor, "GROUP");
            tags.language = getStringOrNull(cursor, "LANGUAGE");
            tags.male = getStringOrNull(cursor, "MALE");
            tags.misc = getStringOrNull(cursor, "MISC");
            tags.mixed = getStringOrNull(cursor, "MIXED");
            tags.other = getStringOrNull(cursor, "OTHER");
            tags.parody = getStringOrNull(cursor, "PARODY");
            tags.reclass = getStringOrNull(cursor, "RECLASS");
            tags.create_time = getDateOrNull(cursor, "CREATE_TIME");
            tags.update_time = getDateOrNull(cursor, "UPDATE_TIME");
            tags.location = getStringOrNull(cursor, "LOCATION");
            list.add(tags);
        });
        return list;
    }

    private interface RowReader {
        void read(Cursor cursor);
    }

    private static void readRows(SQLiteDatabase db, String sql, RowReader reader) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(sql, null);
            while (cursor != null && cursor.moveToNext()) {
                reader.read(cursor);
            }
        } catch (Throwable t) {
            // 旧导出文件可能缺少某些表，跳过即可
            Log.w(TAG, "Skip reading imported table: " + t.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Nullable
    private static String getStringOrNull(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    @Nullable
    private static Date getDateOrNull(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : new Date(cursor.getLong(index));
    }

    private static void sendImportProgress(Handler handler, int progress) {
        Message message = new Message();
        Bundle bundle = new Bundle();
        bundle.putInt(LOADING_PROGRESS, progress);
        bundle.putInt(LOADING_STATUS, DB_LOADING);
        message.setData(bundle);
        handler.sendMessage(message);
    }
}
