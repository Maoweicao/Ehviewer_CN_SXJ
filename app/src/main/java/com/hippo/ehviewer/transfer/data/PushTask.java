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

import java.util.List;
import java.util.UUID;

/**
 * 推送任务数据模型
 */
public class PushTask {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_TRANSFERRING = "transferring";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    public static final String TYPE_BOOKMARKS = "bookmarks";
    public static final String TYPE_DOWNLOADS = "downloads";
    public static final String TYPE_FAVORITES = "favorites";
    public static final String TYPE_EXPORT_DB = "export_db";
    public static final String TYPE_EXPORT_CSV = "export_csv";

    public static final String MODE_ALL = "all";
    public static final String MODE_SELECTED = "selected";

    private String taskId;
    private String type;           // bookmarks/downloads/favorites/export_db/export_csv
    private String mode;           // all/selected
    private List<Long> items;      // mode=selected时的GID列表
    private int totalCount;        // 总数据条数
    private String sourceDevice;   // 来源设备名称
    private String sourceDeviceId; // 来源设备ID
    private String status;         // 状态
    private long createdAt;        // 创建时间
    private long updatedAt;        // 更新时间

    // 传输进度
    private int transferredCount;  // 已传输数量
    private int lastOffset;        // 断点续传偏移量

    // 文件传输相关
    private String fileName;       // 文件名（export_db/export_csv）
    private long fileSize;         // 文件大小
    private int totalChunks;       // 总块数
    private int receivedChunks;    // 已接收块数
    private long receivedBytes;    // 已接收字节数
    private String tempFilePath;   // 临时文件路径

    public PushTask() {
        this.taskId = UUID.randomUUID().toString();
        this.status = STATUS_PENDING;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public PushTask(String type, String mode, String sourceDevice, String sourceDeviceId) {
        this();
        this.type = type;
        this.mode = mode;
        this.sourceDevice = sourceDevice;
        this.sourceDeviceId = sourceDeviceId;
    }

    // Getters and Setters

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<Long> getItems() {
        return items;
    }

    public void setItems(List<Long> items) {
        this.items = items;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = System.currentTimeMillis();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getTransferredCount() {
        return transferredCount;
    }

    public void setTransferredCount(int transferredCount) {
        this.transferredCount = transferredCount;
    }

    public int getLastOffset() {
        return lastOffset;
    }

    public void setLastOffset(int lastOffset) {
        this.lastOffset = lastOffset;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public int getReceivedChunks() {
        return receivedChunks;
    }

    public void setReceivedChunks(int receivedChunks) {
        this.receivedChunks = receivedChunks;
    }

    public long getReceivedBytes() {
        return receivedBytes;
    }

    public void setReceivedBytes(long receivedBytes) {
        this.receivedBytes = receivedBytes;
    }

    public String getTempFilePath() {
        return tempFilePath;
    }

    public void setTempFilePath(String tempFilePath) {
        this.tempFilePath = tempFilePath;
    }

    /**
     * 检查是否为文件传输类型
     */
    public boolean isFileTransfer() {
        return TYPE_EXPORT_DB.equals(type) || TYPE_EXPORT_CSV.equals(type);
    }

    /**
     * 获取进度百分比
     */
    public int getProgressPercent() {
        if (totalCount <= 0) return 0;
        return (int) ((float) transferredCount / totalCount * 100);
    }

    /**
     * 获取类型显示名称
     */
    public String getTypeDisplayName() {
        switch (type) {
            case TYPE_BOOKMARKS:
                return "书签";
            case TYPE_DOWNLOADS:
                return "下载";
            case TYPE_FAVORITES:
                return "收藏";
            case TYPE_EXPORT_DB:
                return "数据库导出";
            case TYPE_EXPORT_CSV:
                return "CSV导出";
            default:
                return type;
        }
    }
}
