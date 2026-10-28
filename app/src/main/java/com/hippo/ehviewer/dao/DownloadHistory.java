package com.hippo.ehviewer.dao;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Permanent, gallery-level record of completed downloads. */
@Entity(tableName = "DOWNLOAD_HISTORY")
public class DownloadHistory {

    public static final int DELETION_NONE = 0;
    public static final int DELETION_NORMAL = 1;
    public static final int DELETION_DUPLICATE_MERGED = 2;
    public static final int DELETION_PROGRESSIVE_MERGED = 3;

    @PrimaryKey
    @ColumnInfo(name = "GID")
    private long gid;
    @ColumnInfo(name = "TOKEN")
    private String token;
    @ColumnInfo(name = "TITLE")
    private String title;
    @ColumnInfo(name = "TITLE_JPN")
    private String titleJpn;
    @ColumnInfo(name = "FILE_PATH")
    private String filePath;
    @ColumnInfo(name = "COMPLETED_AT")
    private long completedAt;
    @ColumnInfo(name = "LAST_DOWNLOADED_AT")
    private long lastDownloadedAt;
    @ColumnInfo(name = "DOWNLOAD_COUNT")
    private int downloadCount;
    @ColumnInfo(name = "DELETION_TYPE")
    private int deletionType;
    @ColumnInfo(name = "MERGED_TARGET_GID")
    private long mergedTargetGid;
    @ColumnInfo(name = "DELETED_AT")
    private long deletedAt;
    // 新增字段：用于预下载对比
    @ColumnInfo(name = "TOTAL_FILES")
    private int totalFiles; // 总文件数（来自 .ehviewer）
    @ColumnInfo(name = "DOWNLOADED_FILES")
    private int downloadedFiles; // 已下载文件数
    @ColumnInfo(name = "FILE_TOKENS")
    private String fileTokens; // JSON 格式存储文件 token 列表，用于后续增量对比

    public DownloadHistory() {
    }

    @Ignore
    public DownloadHistory(long gid, String token, String title, String titleJpn, String filePath,
            long completedAt, long lastDownloadedAt, int downloadCount, int deletionType,
            long mergedTargetGid, long deletedAt, int totalFiles, int downloadedFiles, String fileTokens) {
        this.gid = gid;
        this.token = token;
        this.title = title;
        this.titleJpn = titleJpn;
        this.filePath = filePath;
        this.completedAt = completedAt;
        this.lastDownloadedAt = lastDownloadedAt;
        this.downloadCount = downloadCount;
        this.deletionType = deletionType;
        this.mergedTargetGid = mergedTargetGid;
        this.deletedAt = deletedAt;
        this.totalFiles = totalFiles;
        this.downloadedFiles = downloadedFiles;
        this.fileTokens = fileTokens;
    }

    public long getGid() { return gid; }
    public void setGid(long gid) { this.gid = gid; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTitleJpn() { return titleJpn; }
    public void setTitleJpn(String titleJpn) { this.titleJpn = titleJpn; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
    public long getLastDownloadedAt() { return lastDownloadedAt; }
    public void setLastDownloadedAt(long lastDownloadedAt) { this.lastDownloadedAt = lastDownloadedAt; }
    public int getDownloadCount() { return downloadCount; }
    public void setDownloadCount(int downloadCount) { this.downloadCount = downloadCount; }
    public int getDeletionType() { return deletionType; }
    public void setDeletionType(int deletionType) { this.deletionType = deletionType; }
    public long getMergedTargetGid() { return mergedTargetGid; }
    public void setMergedTargetGid(long mergedTargetGid) { this.mergedTargetGid = mergedTargetGid; }
    public long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(long deletedAt) { this.deletedAt = deletedAt; }
    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
    public int getDownloadedFiles() { return downloadedFiles; }
    public void setDownloadedFiles(int downloadedFiles) { this.downloadedFiles = downloadedFiles; }
    public String getFileTokens() { return fileTokens; }
    public void setFileTokens(String fileTokens) { this.fileTokens = fileTokens; }
}
