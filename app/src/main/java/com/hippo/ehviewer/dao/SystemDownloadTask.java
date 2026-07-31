package com.hippo.ehviewer.dao;

import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.Generated;

/**
 * 系统 DownloadManager 任务映射：
 * 记录每张图片在 Android 系统 DownloadManager 中的 downloadId，
 * 以便：
 *  - 完成广播通过 gid + pageIndex 找到对应记录
 *  - 应用重启 / Worker 调度丢失时能 query 系统 DM 找回状态
 *  - 隐私清理时按 gid 批量 remove
 */
@Entity(nameInDb = "SYSTEM_DOWNLOAD_TASKS")
public class SystemDownloadTask {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    private long downloadId;

    private long gid;

    private int pageIndex;

    /** 解析后的图片 URL（pToken/showKey 可能在数小时内失效；用于 retry） */
    private String resolvedUrl;

    /** PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    /** 已 retry 次数，0..N；>=3 视为放弃 */
    private int retryCount;

    private long createdAt;

    @Generated
    public SystemDownloadTask() {
    }

    @Generated
    public SystemDownloadTask(long downloadId, long gid, int pageIndex, String resolvedUrl, String status, int retryCount, long createdAt) {
        this.downloadId = downloadId;
        this.gid = gid;
        this.pageIndex = pageIndex;
        this.resolvedUrl = resolvedUrl;
        this.status = status;
        this.retryCount = retryCount;
        this.createdAt = createdAt;
    }

    public long getDownloadId() {
        return downloadId;
    }

    public void setDownloadId(long downloadId) {
        this.downloadId = downloadId;
    }

    public long getGid() {
        return gid;
    }

    public void setGid(long gid) {
        this.gid = gid;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }

    public String getResolvedUrl() {
        return resolvedUrl;
    }

    public void setResolvedUrl(String resolvedUrl) {
        this.resolvedUrl = resolvedUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    @Generated
    @Override
    public String toString() {
        return "SystemDownloadTask{" +
                "downloadId=" + downloadId +
                ", gid=" + gid +
                ", pageIndex=" + pageIndex +
                ", status='" + status + '\'' +
                ", retryCount=" + retryCount +
                '}';
    }
}