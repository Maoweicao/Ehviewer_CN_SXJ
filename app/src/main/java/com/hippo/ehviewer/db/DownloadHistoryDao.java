package com.hippo.ehviewer.db;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.hippo.ehviewer.dao.DownloadHistory;

import java.util.List;

@Dao
public interface DownloadHistoryDao {

    @Query("SELECT * FROM DOWNLOAD_HISTORY WHERE GID = :gid")
    DownloadHistory load(long gid);

    @Query("SELECT * FROM DOWNLOAD_HISTORY")
    List<DownloadHistory> loadAll();

    @Query("SELECT COUNT(*) FROM DOWNLOAD_HISTORY")
    long count();

    @Query("SELECT GID FROM DOWNLOAD_HISTORY WHERE GID IN (:gids)")
    List<Long> findExistingGids(List<Long> gids);

    /**
     * 递进/重复合并关系边（全表过滤，调用方必须自行缓存）。
     */
    @Query("SELECT GID AS sourceGid, MERGED_TARGET_GID AS targetGid FROM DOWNLOAD_HISTORY"
            + " WHERE DELETION_TYPE IN (:duplicateType, :progressiveType) AND MERGED_TARGET_GID > 0")
    List<MergeEdgeRow> queryMergeEdges(int duplicateType, int progressiveType);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(DownloadHistory history);

    @Insert
    void insert(DownloadHistory history);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<DownloadHistory> histories);

    @Update
    void update(DownloadHistory history);

    @Update
    void updateAll(List<DownloadHistory> histories);

    @Query("DELETE FROM DOWNLOAD_HISTORY WHERE GID = :gid")
    void deleteByGid(long gid);

    @Query("DELETE FROM DOWNLOAD_HISTORY")
    void deleteAll();

    /**
     * 投影：一张「递进传递」关系边。
     */
    class MergeEdgeRow {
        public long sourceGid;
        public long targetGid;
    }
}
