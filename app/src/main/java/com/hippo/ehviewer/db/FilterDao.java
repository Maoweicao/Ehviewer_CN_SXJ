package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.hippo.ehviewer.dao.Filter;

import java.util.List;

@Dao
public interface FilterDao {

    @Query("SELECT * FROM FILTER")
    List<Filter> loadAll();

    @Insert
    long insert(Filter filter);

    @Update
    void update(Filter filter);

    @Delete
    void delete(Filter filter);

    @Query("DELETE FROM FILTER")
    void deleteAll();
}
