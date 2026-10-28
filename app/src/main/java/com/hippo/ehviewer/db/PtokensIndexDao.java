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

    @Query("SELECT * FROM PTOKENS_INDEX ORDER BY GID ASC LIMIT :limit OFFSET :offset")
    List<PtokensIndex> loadBatch(int offset, int limit);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(PtokensIndex index);

    @Query("DELETE FROM PTOKENS_INDEX WHERE GID = :gid")
    void deleteByGid(long gid);

    @Query("DELETE FROM PTOKENS_INDEX")
    void deleteAll();
}
