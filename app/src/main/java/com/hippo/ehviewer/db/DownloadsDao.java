package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.hippo.ehviewer.dao.DownloadInfo;

import java.util.List;

/**
 * 下载列表（DOWNLOADS）访问接口。
 *
 * <p>Room 的 DAO 本身线程安全，事务由 SQLite 连接层串行化，
 * 因此这里不再需要 EhDB 时代的 {@code static synchronized} 全局锁。
 */
@Dao
public interface DownloadsDao {

    @Query("SELECT * FROM DOWNLOADS ORDER BY TIME DESC")
    List<DownloadInfo> loadAllOrderByTimeDesc();

    @Query("SELECT * FROM DOWNLOADS")
    List<DownloadInfo> loadAll();

    @Query("SELECT * FROM DOWNLOADS WHERE GID = :gid")
    DownloadInfo load(long gid);

    /** 存在则整行替换、不存在则插入：单条 SQL 完成，避免 load→insert/update 的竞态。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(DownloadInfo info);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplace(DownloadInfo info);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<DownloadInfo> infos);

    @Update
    void update(DownloadInfo info);

    @Update
    void updateAll(List<DownloadInfo> infos);

    @Delete
    void delete(DownloadInfo info);

    @Query("DELETE FROM DOWNLOADS WHERE GID = :gid")
    void deleteByGid(long gid);

    @Query("DELETE FROM DOWNLOADS WHERE GID IN (:gids)")
    void deleteByGids(List<Long> gids);

    @Query("DELETE FROM DOWNLOADS")
    void deleteAll();
}
