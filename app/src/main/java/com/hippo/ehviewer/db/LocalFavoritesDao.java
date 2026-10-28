package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.hippo.ehviewer.dao.LocalFavoriteInfo;

import java.util.List;

@Dao
public interface LocalFavoritesDao {

    @Query("SELECT * FROM LOCAL_FAVORITES ORDER BY TIME DESC")
    List<LocalFavoriteInfo> loadAllOrderByTimeDesc();

    @Query("SELECT * FROM LOCAL_FAVORITES WHERE GID = :gid")
    LocalFavoriteInfo load(long gid);

    @Query("SELECT COUNT(*) FROM LOCAL_FAVORITES WHERE GID = :gid")
    int countByGid(long gid);

    @Query("SELECT * FROM LOCAL_FAVORITES WHERE TITLE LIKE :pattern ORDER BY TIME DESC")
    List<LocalFavoriteInfo> searchByTitle(String pattern);

    @Query("SELECT * FROM LOCAL_FAVORITES WHERE GID = :gid ORDER BY TIME DESC LIMIT 1")
    LocalFavoriteInfo findByGidOrderByTimeDesc(long gid);

    @Insert
    void insert(LocalFavoriteInfo info);

    /** 已存在则保留旧记录（等价于旧的 load→判空→insert）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertIgnore(LocalFavoriteInfo info);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAllIgnore(List<LocalFavoriteInfo> infos);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(LocalFavoriteInfo info);

    @Insert
    void insertAll(List<LocalFavoriteInfo> infos);

    @Query("DELETE FROM LOCAL_FAVORITES WHERE GID = :gid")
    void deleteByGid(long gid);

    @Query("DELETE FROM LOCAL_FAVORITES WHERE GID IN (:gids)")
    void deleteByGids(List<Long> gids);

    @Query("DELETE FROM LOCAL_FAVORITES")
    void deleteAll();
}
