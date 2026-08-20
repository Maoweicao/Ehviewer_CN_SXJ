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

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Unified task model for push and compress tasks
 */
public class UnifiedTask {

    // Task types
    public static final String TYPE_PUSH = "push";
    public static final String TYPE_COMPRESS = "compress";
    public static final String TYPE_DELETE = "delete";

    // Push sub-types
    public static final String SUB_TYPE_BOOKMARKS = "bookmarks";
    public static final String SUB_TYPE_DOWNLOADS = "downloads";
    public static final String SUB_TYPE_FAVORITES = "favorites";
    public static final String SUB_TYPE_EXPORT_DB = "export_db";
    public static final String SUB_TYPE_EXPORT_CSV = "export_csv";

    // Task statuses
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_TRANSFERRING = "transferring";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    // Core fields
    public String taskId;
    public String type;           // push / compress
    public String subType;        // bookmarks / downloads / export_db / ...
    public String status;
    public String sourceDevice;
    public String sourceDeviceId;
    public double progress;
    public int total;
    public int completed;
    public int failed;
    public long createdTime;
    public long updatedTime;
    public long completedTime;
    /** 是否已暂停（供执行循环检查） */
    public volatile boolean paused;

    // Push specific
    public String mode;           // all / selected
    public List<Long> items;      // selected GIDs
    public String fileName;
    public long fileSize;
    public int receivedChunks;
    public int totalChunks;
    public long receivedBytes;
    public String tempFilePath;

    // Compress specific
    public List<Long> gids;
    public long splitSizeMB;
    public boolean includeMetadata;
    public List<String> outputFiles;

    // Delete specific
    public List<String> deletePaths;
    public boolean deleteFiles;
    public String error;

    public UnifiedTask() {
        this.taskId = UUID.randomUUID().toString();
        this.status = STATUS_PENDING;
        this.createdTime = System.currentTimeMillis();
        this.updatedTime = System.currentTimeMillis();
        this.items = new ArrayList<>();
        this.gids = new ArrayList<>();
        this.outputFiles = new ArrayList<>();
        this.deletePaths = new ArrayList<>();
    }

    /**
     * Create a push task
     */
    public static UnifiedTask createPushTask(String subType, String mode, String sourceDevice, String sourceDeviceId) {
        UnifiedTask task = new UnifiedTask();
        task.type = TYPE_PUSH;
        task.subType = subType;
        task.mode = mode;
        task.sourceDevice = sourceDevice;
        task.sourceDeviceId = sourceDeviceId;
        task.taskId = "push-" + task.taskId;
        return task;
    }

    /**
     * Create a compress task
     */
    public static UnifiedTask createCompressTask(List<Long> gids, long splitSizeMB, boolean includeMetadata) {
        UnifiedTask task = new UnifiedTask();
        task.type = TYPE_COMPRESS;
        task.subType = "gallery";
        task.gids = new ArrayList<>(gids);
        task.total = gids.size();
        task.splitSizeMB = splitSizeMB;
        task.includeMetadata = includeMetadata;
        task.taskId = "compress-" + task.taskId;
        return task;
    }

    public static UnifiedTask createDeleteTask(String subType) {
        UnifiedTask task = new UnifiedTask();
        task.type = TYPE_DELETE;
        task.subType = subType;
        task.taskId = "delete-" + task.taskId;
        return task;
    }

    public boolean isPush() {
        return TYPE_PUSH.equals(type);
    }

    public boolean isCompress() {
        return TYPE_COMPRESS.equals(type);
    }

    public boolean isDelete() {
        return TYPE_DELETE.equals(type);
    }

    public boolean isFileTransfer() {
        return isPush() && (SUB_TYPE_EXPORT_DB.equals(subType) || SUB_TYPE_EXPORT_CSV.equals(subType));
    }

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status) ||
                STATUS_FAILED.equals(status) ||
                STATUS_CANCELLED.equals(status) ||
                STATUS_REJECTED.equals(status);
    }

    /**
     * 是否处于可暂停状态（进行中且未暂停）
     */
    public boolean canPause() {
        return !paused && !isTerminal() && !STATUS_PENDING.equals(status);
    }

    /**
     * 是否处于可恢复状态（已暂停）
     */
    public boolean canResume() {
        return paused && !isTerminal();
    }

    /**
     * 暂停时是否应阻塞等待直到恢复（供执行循环使用）
     */
    public void waitWhilePaused() {
        while (paused && !isTerminal()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void updateProgress(double progress) {
        this.progress = progress;
        this.updatedTime = System.currentTimeMillis();
    }

    public void markCompleted() {
        this.status = STATUS_COMPLETED;
        this.progress = 100;
        this.completedTime = System.currentTimeMillis();
        this.updatedTime = this.completedTime;
    }

    public void markFailed() {
        this.status = STATUS_FAILED;
        this.completedTime = System.currentTimeMillis();
        this.updatedTime = this.completedTime;
    }

    /**
     * Serialize to JSON for persistence
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("taskId", taskId);
        json.put("type", type);
        json.put("subType", subType);
        json.put("status", status);
        json.put("sourceDevice", sourceDevice);
        json.put("sourceDeviceId", sourceDeviceId);
        json.put("progress", progress);
        json.put("total", total);
        json.put("completed", completed);
        json.put("failed", failed);
        json.put("createdTime", createdTime);
        json.put("updatedTime", updatedTime);
        json.put("completedTime", completedTime);
        json.put("paused", paused);

        // Push fields
        json.put("mode", mode);
        if (items != null && !items.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Long id : items) arr.add(id);
            json.put("items", arr);
        }
        json.put("fileName", fileName);
        json.put("fileSize", fileSize);
        json.put("receivedChunks", receivedChunks);
        json.put("totalChunks", totalChunks);
        json.put("receivedBytes", receivedBytes);
        json.put("tempFilePath", tempFilePath);

        // Compress fields
        if (gids != null && !gids.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Long id : gids) arr.add(id);
            json.put("gids", arr);
        }
        json.put("splitSizeMB", splitSizeMB);
        json.put("includeMetadata", includeMetadata);
        if (outputFiles != null && !outputFiles.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (String f : outputFiles) arr.add(f);
            json.put("outputFiles", arr);
        }

        if (deletePaths != null && !deletePaths.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (String path : deletePaths) arr.add(path);
            json.put("deletePaths", arr);
        }
        json.put("deleteFiles", deleteFiles);
        json.put("error", error);

        return json;
    }

    /**
     * Deserialize from JSON
     */
    public static UnifiedTask fromJson(JSONObject json) {
        UnifiedTask task = new UnifiedTask();
        task.taskId = json.getString("taskId");
        task.type = json.getString("type");
        task.subType = json.getString("subType");
        task.status = json.getString("status");
        task.sourceDevice = json.getString("sourceDevice");
        task.sourceDeviceId = json.getString("sourceDeviceId");
        task.progress = json.getDoubleValue("progress");
        task.total = json.getIntValue("total");
        task.completed = json.getIntValue("completed");
        task.failed = json.getIntValue("failed");
        task.createdTime = json.getLongValue("createdTime");
        task.updatedTime = json.getLongValue("updatedTime");
        task.completedTime = json.getLongValue("completedTime");
        task.paused = json.getBooleanValue("paused");

        // Push fields
        task.mode = json.getString("mode");
        JSONArray itemsArr = json.getJSONArray("items");
        if (itemsArr != null) {
            task.items = new ArrayList<>();
            for (int i = 0; i < itemsArr.size(); i++) {
                task.items.add(itemsArr.getLongValue(i));
            }
        }
        task.fileName = json.getString("fileName");
        task.fileSize = json.getLongValue("fileSize");
        task.receivedChunks = json.getIntValue("receivedChunks");
        task.totalChunks = json.getIntValue("totalChunks");
        task.receivedBytes = json.getLongValue("receivedBytes");
        task.tempFilePath = json.getString("tempFilePath");

        // Compress fields
        JSONArray gidsArr = json.getJSONArray("gids");
        if (gidsArr != null) {
            task.gids = new ArrayList<>();
            for (int i = 0; i < gidsArr.size(); i++) {
                task.gids.add(gidsArr.getLongValue(i));
            }
        }
        task.splitSizeMB = json.getLongValue("splitSizeMB");
        task.includeMetadata = json.getBooleanValue("includeMetadata");
        JSONArray filesArr = json.getJSONArray("outputFiles");
        if (filesArr != null) {
            task.outputFiles = new ArrayList<>();
            for (int i = 0; i < filesArr.size(); i++) {
                task.outputFiles.add(filesArr.getString(i));
            }
        }

        JSONArray deletePathsArr = json.getJSONArray("deletePaths");
        if (deletePathsArr != null) {
            task.deletePaths = new ArrayList<>();
            for (int i = 0; i < deletePathsArr.size(); i++) {
                task.deletePaths.add(deletePathsArr.getString(i));
            }
        }
        task.deleteFiles = json.getBooleanValue("deleteFiles");
        task.error = json.getString("error");

        return task;
    }

    /**
     * Convert to API response JSON (for /api/v1/tasks)
     */
    public JSONObject toApiJson() {
        JSONObject json = new JSONObject();
        json.put("taskId", taskId);
        json.put("type", type);
        json.put("subType", subType);
        json.put("status", status);
        json.put("sourceDevice", sourceDevice);
        json.put("progress", progress);
        json.put("total", total);
        json.put("completed", completed);
        json.put("failed", failed);
        json.put("createdTime", createdTime);
        json.put("updatedTime", updatedTime);
        json.put("completedTime", completedTime);
        json.put("paused", paused);

        if (isCompress()) {
            json.put("splitSizeMB", splitSizeMB);
            JSONArray filesArr = new JSONArray();
            if (outputFiles != null) {
                for (String f : outputFiles) filesArr.add(f);
            }
            json.put("outputFiles", filesArr);
        }

        if (isFileTransfer()) {
            json.put("fileName", fileName);
            json.put("fileSize", fileSize);
            json.put("totalChunks", totalChunks);
            json.put("receivedChunks", receivedChunks);
            json.put("receivedBytes", receivedBytes);
        }

        if (isDelete()) {
            json.put("deleteFiles", deleteFiles);
            json.put("error", error);
            JSONObject result = new JSONObject();
            result.put("deleted", Math.max(0, completed - failed));
            result.put("failed", failed);
            json.put("result", result);
        }

        return json;
    }
}
