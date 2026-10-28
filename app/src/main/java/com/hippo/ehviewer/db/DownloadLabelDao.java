package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.hippo.ehviewer.dao.DownloadLabel;

import java.util.List;

@Dao
public interface DownloadLabelDao {

    @Query("SELECT * FROM DOWNLOAD_LABELS ORDER BY TIME ASC")
    List<DownloadLabel> loadAllOrderByTimeAsc();

    @Query("SELECT * FROM DOWNLOAD_LABELS WHERE _id = :id")
    DownloadLabel load(Long id);

    @Query("SELECT * FROM DOWNLOAD_LABELS WHERE LABEL = :label LIMIT 1")
    DownloadLabel findByLabel(String label);

    @Query("SELECT * FROM DOWNLOAD_LABELS ORDER BY TIME ASC LIMIT :limit OFFSET :offset")
    List<DownloadLabel> loadRange(int offset, int limit);

    @Insert
    long insert(DownloadLabel label);

    @Insert
    void insertAll(List<DownloadLabel> labels);

    @Update
    void update(DownloadLabel label);

    @Update
    void updateAll(List<DownloadLabel> labels);

    @Delete
    void delete(DownloadLabel label);

    @Query("DELETE FROM DOWNLOAD_LABELS")
    void deleteAll();
}
