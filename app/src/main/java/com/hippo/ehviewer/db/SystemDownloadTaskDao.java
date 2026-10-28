package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.hippo.ehviewer.dao.SystemDownloadTask;

import java.util.List;

@Dao
public interface SystemDownloadTaskDao {

    @Query("SELECT * FROM SYSTEM_DOWNLOAD_TASKS WHERE DOWNLOAD_ID = :downloadId")
    SystemDownloadTask load(long downloadId);

    @Query("SELECT * FROM SYSTEM_DOWNLOAD_TASKS WHERE GID = :gid")
    List<SystemDownloadTask> findByGid(long gid);

    @Query("SELECT * FROM SYSTEM_DOWNLOAD_TASKS WHERE STATUS IN (:statuses)")
    List<SystemDownloadTask> findByStatuses(List<String> statuses);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SystemDownloadTask task);

    @Query("DELETE FROM SYSTEM_DOWNLOAD_TASKS WHERE DOWNLOAD_ID = :downloadId")
    void deleteByDownloadId(long downloadId);

    @Query("DELETE FROM SYSTEM_DOWNLOAD_TASKS WHERE GID = :gid")
    void deleteByGid(long gid);

    @Query("DELETE FROM SYSTEM_DOWNLOAD_TASKS")
    void deleteAll();
}
