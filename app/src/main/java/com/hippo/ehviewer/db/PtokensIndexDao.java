package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.hippo.ehviewer.dao.PtokensIndex;

import java.util.List;

@Dao
public interface PtokensIndexDao {

    @Query("SELECT * FROM PTOKENS_INDEX WHERE GID = :gid")
    PtokensIndex load(long gid);

    @Query("SELECT * FROM PTOKENS_INDEX")
    List<PtokensIndex> loadAll();

    @Query("SELECT COUNT(*) FROM PTOKENS_INDEX")
    long count();

    /**
     * 按 GID 键集分页读取，避免 OFFSET 在大表上越翻越慢。
     * 调用方从 {@link Long#MIN_VALUE} 开始，随后把上一批最后一行的 GID 作为
     * {@code lastGid} 继续，直到返回空列表。
     *
     * <p>{@code excludeGid} 用于跳过当前画廊。这里不在 SQL 里按 PAGES 过滤：
     * 历史数据里 PAGES 可能是「画廊声明页数」而不是「索引里的 hash 个数」，
     * 用它过滤会漏掉真正的子集关系。
     */
    @Query("SELECT * FROM PTOKENS_INDEX WHERE GID > :lastGid AND GID != :excludeGid ORDER BY GID ASC LIMIT :limit")
    List<PtokensIndex> loadBatchAfterGid(long lastGid, long excludeGid, int limit);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(PtokensIndex index);

    @Query("DELETE FROM PTOKENS_INDEX WHERE GID = :gid")
    void deleteByGid(long gid);

    @Query("DELETE FROM PTOKENS_INDEX")
    void deleteAll();
}
