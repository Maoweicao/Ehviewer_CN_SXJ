package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.hippo.ehviewer.dao.DownloadedFile;

import java.util.List;

/**
 * 已下载文件索引（DOWNLOADED_FILES）。
 *
 * <p>旧实现直接从 DaoSession 取 DAO 绕过 EhDB 全局锁，属于"锁外并发"；
 * 迁移到 Room 后由 DAO 自身保证线程安全。
 */
@Dao
public interface DownloadedFilesDao {

    @Query("SELECT * FROM DOWNLOADED_FILES WHERE TOKEN = :token LIMIT 1")
    DownloadedFile load(String token);

    @Query("SELECT * FROM DOWNLOADED_FILES")
    List<DownloadedFile> loadAll();

    @Query("SELECT * FROM DOWNLOADED_FILES WHERE TOKEN = :token AND STATUS = :status LIMIT 1")
    DownloadedFile findByTokenAndStatus(String token, int status);

    @Query("SELECT * FROM DOWNLOADED_FILES WHERE FILE_TOKEN = :fileToken AND STATUS = :status LIMIT 1")
    DownloadedFile findByFileTokenAndStatus(String fileToken, int status);

    @Query("SELECT * FROM DOWNLOADED_FILES WHERE FILE_TOKEN = :fileToken")
    List<DownloadedFile> findByFileToken(String fileToken);

    @Query("SELECT * FROM DOWNLOADED_FILES WHERE GID = :gid AND STATUS = :status")
    List<DownloadedFile> findByGidAndStatus(long gid, int status);

    @Query("SELECT * FROM DOWNLOADED_FILES WHERE GID = :gid AND STATUS = :status ORDER BY FILENAME ASC")
    List<DownloadedFile> findByGidAndStatusOrderByFilename(long gid, int status);

    @Query("SELECT * FROM DOWNLOADED_FILES WHERE STATUS = :status")
    List<DownloadedFile> findByStatus(int status);

    @Query("SELECT COUNT(*) FROM DOWNLOADED_FILES WHERE STATUS = :status")
    long countByStatus(int status);

    @Query("SELECT COUNT(*) FROM DOWNLOADED_FILES")
    long count();

    @Insert
    void insert(DownloadedFile file);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(DownloadedFile file);

    @Insert
    void insertAll(List<DownloadedFile> files);

    @Update
    void update(DownloadedFile file);

    @Update
    void updateAll(List<DownloadedFile> files);

    @Delete
    void delete(DownloadedFile file);

    @Query("DELETE FROM DOWNLOADED_FILES")
    void deleteAll();
}
