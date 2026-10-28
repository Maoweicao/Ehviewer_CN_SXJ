package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.hippo.ehviewer.dao.BlackList;

import java.util.List;

@Dao
public interface BlackListDao {

    @Query("SELECT * FROM Black_List ORDER BY ADD_TIME ASC")
    List<BlackList> loadAllOrderByAddTimeAsc();

    @Query("SELECT COUNT(*) FROM Black_List WHERE BADGAYNAME = :badgayname")
    int countByBadgayname(String badgayname);

    @Insert
    long insert(BlackList blackList);

    @Update
    void update(BlackList blackList);

    @Delete
    void delete(BlackList blackList);

    @Query("DELETE FROM Black_List")
    void deleteAll();
}
