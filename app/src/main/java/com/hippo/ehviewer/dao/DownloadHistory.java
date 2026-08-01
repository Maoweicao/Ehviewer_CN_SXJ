package com.hippo.ehviewer.dao;

import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Generated;
import org.greenrobot.greendao.annotation.Id;

/** Permanent, gallery-level record of completed downloads. */
@Entity(nameInDb = "DOWNLOAD_HISTORY")
public class DownloadHistory {

    public static final int DELETION_NONE = 0;
    public static final int DELETION_NORMAL = 1;
    public static final int DELETION_DUPLICATE_MERGED = 2;
    public static final int DELETION_PROGRESSIVE_MERGED = 3;

    @Id
    private long gid;
    private String token;
    private String title;
    private String titleJpn;
    private String filePath;
    private long completedAt;
    private long lastDownloadedAt;
    private int downloadCount;
    private int deletionType;
    private long mergedTargetGid;
    private long deletedAt;

    @Generated
    public DownloadHistory() {
    }

    @Generated
    public DownloadHistory(long gid, String token, String title, String titleJpn, String filePath,
            long completedAt, long lastDownloadedAt, int downloadCount, int deletionType,
            long mergedTargetGid, long deletedAt) {
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
}
