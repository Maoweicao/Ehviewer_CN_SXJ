package com.hippo.ehviewer.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.hippo.ehviewer.dao.BookmarkInfo;

import java.util.List;

/**
 * 云端书签缓存表。
 *
 * <p>当前业务代码尚未使用（导入侧仍是 TODO），保留 DAO 是为了导出/导入时
 * 维持与旧版本一致的表集合。
 */
@Dao
public interface BookmarksDao {

    @Query("SELECT * FROM BOOKMARKS")
    List<BookmarkInfo> loadAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<BookmarkInfo> bookmarks);

    @Query("DELETE FROM BOOKMARKS")
    void deleteAll();
}
