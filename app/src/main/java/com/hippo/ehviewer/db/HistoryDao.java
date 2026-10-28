package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.hippo.ehviewer.dao.HistoryInfo;

import java.util.List;

@Dao
public interface HistoryDao {

    @Query("SELECT * FROM HISTORY WHERE GID = :gid")
    HistoryInfo load(long gid);

    @Query("SELECT * FROM HISTORY ORDER BY TIME DESC")
    List<HistoryInfo> loadAllOrderByTimeDesc();

    /**
     * 超出保留条数的旧记录（用于插入后裁剪）。
     */
    @Query("SELECT * FROM HISTORY ORDER BY TIME DESC LIMIT -1 OFFSET :offset")
    List<HistoryInfo> loadBeyond(int offset);

    /** 删除超出保留条数的旧记录（单条 SQL，避免把整段数据读回内存）。 */
    @Query("DELETE FROM HISTORY WHERE GID IN"
            + " (SELECT GID FROM HISTORY ORDER BY TIME DESC LIMIT -1 OFFSET :offset)")
    void deleteBeyond(int offset);

    @Insert
    void insert(HistoryInfo info);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(HistoryInfo info);

    @Update
    void update(HistoryInfo info);

    @Delete
    void delete(HistoryInfo info);

    @Query("DELETE FROM HISTORY WHERE GID = :gid")
    void deleteByGid(long gid);

    @Query("DELETE FROM HISTORY")
    void deleteAll();
}
