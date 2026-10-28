package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.hippo.ehviewer.dao.DownloadDirname;

@Dao
public interface DownloadDirnameDao {

    @Query("SELECT * FROM DOWNLOAD_DIRNAME WHERE GID = :gid")
    DownloadDirname load(long gid);

    @Query("SELECT * FROM DOWNLOAD_DIRNAME")
    java.util.List<DownloadDirname> loadAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(DownloadDirname dirname);

    @Query("DELETE FROM DOWNLOAD_DIRNAME WHERE GID = :gid")
    void deleteByGid(long gid);

    @Query("DELETE FROM DOWNLOAD_DIRNAME")
    void deleteAll();
}
