package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.hippo.ehviewer.dao.QuickSearch;

import java.util.List;

@Dao
public interface QuickSearchDao {

    @Query("SELECT * FROM QUICK_SEARCH ORDER BY TIME ASC")
    List<QuickSearch> loadAllOrderByTimeAsc();

    @Query("SELECT * FROM QUICK_SEARCH WHERE _id = :id")
    QuickSearch load(Long id);

    @Insert
    long insert(QuickSearch search);

    @Insert
    void insertAll(List<QuickSearch> searches);

    @Update
    void update(QuickSearch search);

    @Update
    void updateAll(List<QuickSearch> searches);

    @Delete
    void delete(QuickSearch search);

    @Query("SELECT * FROM QUICK_SEARCH ORDER BY TIME ASC LIMIT :limit OFFSET :offset")
    List<QuickSearch> loadRange(int offset, int limit);

    @Query("DELETE FROM QUICK_SEARCH")
    void deleteAll();
}
