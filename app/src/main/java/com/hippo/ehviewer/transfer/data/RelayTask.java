/*
 * Copyright 2025 EhViewer Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.transfer.data;

import java.util.UUID;

/**
 * 接力下载任务数据模型
 *
 * 状态机：
 * pending → accepted → downloading → completed → returned
 *     ↓        ↓           ↓
 *   rejected  cancelled  failed
 */
public class RelayTask {

    // 任务状态
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_DOWNLOADING = "downloading";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_RETURNED = "returned";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_FAILED = "failed";

    // 任务方向
    public static final String DIRECTION_INCOMING = "incoming";  // 别人委托我的
    public static final String DIRECTION_OUTGOING = "outgoing";  // 我委托别人的

    // 优先级
    public static final String PRIORITY_LOW = "low";
    public static final String PRIORITY_NORMAL = "normal";
    public static final String PRIORITY_HIGH = "high";

    // 基本信息
    private String taskId;
    private long gid;
    private String token;
    private String title;
    private String titleJpn;
    private String thumb;
    private int category;
    private String posted;
    private String uploader;
    private float rating;
    private int pages;

    // 任务信息
    private String status;
    private String direction;
    private String sourceDevice;
    private String sourceDeviceId;
    private String sourceDeviceHost;    // 源设备IP地址，用于发起回连
    private int sourceDevicePort;       // 源设备端口，用于发起回连
    private String targetDevice;
    private String targetDeviceId;
    private String acceptedDevice;      // 接受任务的设备名称
    private String acceptedDeviceId;    // 接受任务的设备ID
    private long acceptedTime;          // 接受任务的时间
    private String priority;
    private boolean autoReturn;

    // 进度信息
    private int finished;
    private int total;
    private long speed;
    private long downloadedSize;
    private long totalSize;

    // 时间戳
    private long createdTime;
    private long updatedTime;
    private long completedTime;
    private long returnedTime;

    // 文件信息
    private String zipFilePath;
    private long zipFileSize;

    // 错误信息
    private String errorMessage;

    public RelayTask() {
        this.taskId = "relay-" + UUID.randomUUID().toString();
        this.status = STATUS_PENDING;
        this.priority = PRIORITY_NORMAL;
        this.autoReturn = true;
        this.createdTime = System.currentTimeMillis();
        this.updatedTime = this.createdTime;
    }

    public RelayTask(long gid, String token, String title) {
        this();
        this.gid = gid;
        this.token = token;
        this.title = title;
    }

    // ==================== Getters and Setters ====================

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public long getGid() {
        return gid;
    }

    public void setGid(long gid) {
        this.gid = gid;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTitleJpn() {
        return titleJpn;
    }

    public void setTitleJpn(String titleJpn) {
        this.titleJpn = titleJpn;
    }

    public String getThumb() {
        return thumb;
    }

    public void setThumb(String thumb) {
        this.thumb = thumb;
    }

    public int getCategory() {
        return category;
    }

    public void setCategory(int category) {
        this.category = category;
    }

    public String getPosted() {
        return posted;
    }

    public void setPosted(String posted) {
        this.posted = posted;
    }

    public String getUploader() {
        return uploader;
    }

    public void setUploader(String uploader) {
        this.uploader = uploader;
    }

    public float getRating() {
        return rating;
    }

    public void setRating(float rating) {
        this.rating = rating;
    }

    public int getPages() {
        return pages;
    }

    public void setPages(int pages) {
        this.pages = pages;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedTime = System.currentTimeMillis();
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getSourceDevice() {
        return sourceDevice;
    }

    public void setSourceDevice(String sourceDevice) {
        this.sourceDevice = sourceDevice;
    }

    public String getSourceDeviceId() {
        return sourceDeviceId;
    }

    public void setSourceDeviceId(String sourceDeviceId) {
        this.sourceDeviceId = sourceDeviceId;
    }

    public String getSourceDeviceHost() {
        return sourceDeviceHost;
    }

    public void setSourceDeviceHost(String sourceDeviceHost) {
        this.sourceDeviceHost = sourceDeviceHost;
    }

    public int getSourceDevicePort() {
        return sourceDevicePort;
    }

    public void setSourceDevicePort(int sourceDevicePort) {
        this.sourceDevicePort = sourceDevicePort;
    }

    public String getTargetDevice() {
        return targetDevice;
    }

    public void setTargetDevice(String targetDevice) {
        this.targetDevice = targetDevice;
    }

    public String getTargetDeviceId() {
        return targetDeviceId;
    }

    public void setTargetDeviceId(String targetDeviceId) {
        this.targetDeviceId = targetDeviceId;
    }

    public String getAcceptedDevice() {
        return acceptedDevice;
    }

    public void setAcceptedDevice(String acceptedDevice) {
        this.acceptedDevice = acceptedDevice;
    }

    public String getAcceptedDeviceId() {
        return acceptedDeviceId;
    }

    public void setAcceptedDeviceId(String acceptedDeviceId) {
        this.acceptedDeviceId = acceptedDeviceId;
    }

    public long getAcceptedTime() {
        return acceptedTime;
    }

    public void setAcceptedTime(long acceptedTime) {
        this.acceptedTime = acceptedTime;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public boolean isAutoReturn() {
        return autoReturn;
    }

    public void setAutoReturn(boolean autoReturn) {
        this.autoReturn = autoReturn;
    }

    public int getFinished() {
        return finished;
    }

    public void setFinished(int finished) {
        this.finished = finished;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public long getSpeed() {
        return speed;
    }

    public void setSpeed(long speed) {
        this.speed = speed;
    }

    public long getDownloadedSize() {
        return downloadedSize;
    }

    public void setDownloadedSize(long downloadedSize) {
        this.downloadedSize = downloadedSize;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public long getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(long updatedTime) {
        this.updatedTime = updatedTime;
    }

    public long getCompletedTime() {
        return completedTime;
    }

    public void setCompletedTime(long completedTime) {
        this.completedTime = completedTime;
    }

    public long getReturnedTime() {
        return returnedTime;
    }

    public void setReturnedTime(long returnedTime) {
        this.returnedTime = returnedTime;
    }

    public String getZipFilePath() {
        return zipFilePath;
    }

    public void setZipFilePath(String zipFilePath) {
        this.zipFilePath = zipFilePath;
    }

    public long getZipFileSize() {
        return zipFileSize;
    }

    public void setZipFileSize(long zipFileSize) {
        this.zipFileSize = zipFileSize;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // ==================== 状态检查方法 ====================

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    public boolean isAccepted() {
        return STATUS_ACCEPTED.equals(status);
    }

    public boolean isDownloading() {
        return STATUS_DOWNLOADING.equals(status);
    }

    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(status);
    }

    public boolean isReturned() {
        return STATUS_RETURNED.equals(status);
    }

    public boolean isRejected() {
        return STATUS_REJECTED.equals(status);
    }

    public boolean isCancelled() {
        return STATUS_CANCELLED.equals(status);
    }

    public boolean isFailed() {
        return STATUS_FAILED.equals(status);
    }

    public boolean isIncoming() {
        return DIRECTION_INCOMING.equals(direction);
    }

    public boolean isOutgoing() {
        return DIRECTION_OUTGOING.equals(direction);
    }

    /**
     * 检查任务是否处于终态（不可再变更）
     */
    public boolean isTerminal() {
        return isReturned() || isRejected() || isCancelled() || isFailed();
    }

    /**
     * 检查任务是否可以取消
     */
    public boolean isCancellable() {
        return isPending() || isAccepted() || isDownloading();
    }

    /**
     * 获取进度百分比
     */
    public float getProgressPercent() {
        if (total <= 0) return 0;
        return (float) finished / total * 100;
    }

    /**
     * 获取状态显示名称
     */
    public String getStatusDisplayName() {
        switch (status) {
            case STATUS_PENDING: return "等待接受";
            case STATUS_ACCEPTED: return "已接受";
            case STATUS_DOWNLOADING: return "下载中";
            case STATUS_COMPLETED: return "已完成";
            case STATUS_RETURNED: return "已取回";
            case STATUS_REJECTED: return "已拒绝";
            case STATUS_CANCELLED: return "已取消";
            case STATUS_FAILED: return "失败";
            default: return status;
        }
    }

    /**
     * 获取方向显示名称
     */
    public String getDirectionDisplayName() {
        if (isIncoming()) {
            return "收到的";
        } else if (isOutgoing()) {
            return "发出的";
        }
        return direction;
    }
}
