package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.hippo.ehviewer.dao.GalleryAiInfo;

import java.util.List;

@Dao
public interface GalleryAiInfoDao {

    @Query("SELECT * FROM GALLERY_AI_INFO WHERE GID = :gid")
    GalleryAiInfo load(long gid);

    @Query("SELECT * FROM GALLERY_AI_INFO WHERE SUMMARY LIKE :pattern"
            + " OR TAGS LIKE :pattern OR DESCRIPTIONS LIKE :pattern")
    List<GalleryAiInfo> searchByKeyword(String pattern);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(GalleryAiInfo info);

    @Query("DELETE FROM GALLERY_AI_INFO WHERE GID = :gid")
    void deleteByGid(long gid);

    @Query("DELETE FROM GALLERY_AI_INFO")
    void deleteAll();
}
