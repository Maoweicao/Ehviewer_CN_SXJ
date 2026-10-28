package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.hippo.ehviewer.dao.GalleryTags;

import java.util.List;

@Dao
public interface GalleryTagsDao {

    @Query("SELECT * FROM Gallery_Tags ORDER BY GID ASC")
    List<GalleryTags> loadAllOrderByGidAsc();

    @Query("SELECT * FROM Gallery_Tags WHERE GID = :gid LIMIT 1")
    GalleryTags load(long gid);

    @Query("SELECT COUNT(*) FROM Gallery_Tags WHERE GID = :gid")
    int countByGid(long gid);

    @Query("SELECT GID FROM Gallery_Tags")
    List<Long> loadAllGids();

    @Insert
    void insert(GalleryTags tags);

    @Update
    void update(GalleryTags tags);

    @Delete
    void delete(GalleryTags tags);

    @Query("DELETE FROM Gallery_Tags")
    void deleteAll();
}
