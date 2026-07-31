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

package com.hippo.ehviewer.transfer.api;

import android.content.Context;
import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.BookmarkInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.data.PushTask;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

/**
 * 推送API处理器
 */
public class PushApiHandler extends BaseApiHandler {

    private static final String TAG = "PushApiHandler";

    private ConcurrentHashMap<String, PushTask> pendingTasks = new ConcurrentHashMap<>();
    private List<PushTaskListener> listeners = new ArrayList<>();

    public PushApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);

        // /api/v1/push/tasks - 获取待处理任务列表
        if (uri.equals("/api/v1/push/tasks")) {
            return handleGetTasks(session);
        }

        // /api/v1/push/tasks/{taskId} - 获取任务状态
        if (uri.matches("/api/v1/push/tasks/[^/]+$")) {
            String taskId = extractTaskId(uri);
            return handleGetTaskStatus(session, taskId);
        }

        // /api/v1/push/tasks/{taskId}/data - 获取任务数据
        if (uri.matches("/api/v1/push/tasks/[^/]+/data")) {
            String taskId = extractTaskId(uri);
            return handleGetTaskData(session, taskId);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri);

        // /api/v1/push/create - 创建推送任务
        if (uri.equals("/api/v1/push/create")) {
            return handleCreateTask(session);
        }

        // /api/v1/push/tasks/{taskId}/accept - 接受任务
        if (uri.matches("/api/v1/push/tasks/[^/]+/accept")) {
            String taskId = extractTaskId(uri);
            return handleAcceptTask(session, taskId);
        }

        // /api/v1/push/tasks/{taskId}/reject - 拒绝任务
        if (uri.matches("/api/v1/push/tasks/[^/]+/reject")) {
            String taskId = extractTaskId(uri);
            return handleRejectTask(session, taskId);
        }

        // /api/v1/push/tasks/{taskId}/data - 传输数据/文件块
        if (uri.matches("/api/v1/push/tasks/[^/]+/data")) {
            String taskId = extractTaskId(uri);
            return handlePostTaskData(session, taskId);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    /**
     * 创建推送任务
     */
    private NanoHTTPD.Response handleCreateTask(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);

            String type = json.getString("type");
            String mode = json.getString("mode");
            String sourceDevice = json.getString("sourceDevice");
            String sourceDeviceId = json.getString("sourceDeviceId");
            int totalCount = json.getIntValue("totalCount");

            PushTask task = new PushTask(type, mode, sourceDevice, sourceDeviceId);
            task.setTotalCount(totalCount);

            // 文件传输类型参数
            if (PushTask.TYPE_EXPORT_DB.equals(type) || PushTask.TYPE_EXPORT_CSV.equals(type)) {
                task.setFileName(json.getString("fileName"));
                task.setFileSize(json.getLongValue("fileSize"));
                task.setMode(PushTask.MODE_ALL);
            }

            if (PushTask.MODE_SELECTED.equals(mode) && json.containsKey("items")) {
                JSONArray itemsArray = json.getJSONArray("items");
                List<Long> items = new ArrayList<>();
                for (int i = 0; i < itemsArray.size(); i++) {
                    items.add(itemsArray.getLong(i));
                }
                task.setItems(items);
            }

            // 检查是否自动接收
            boolean autoReceive = isAutoReceive(type);
            if (autoReceive) {
                task.setStatus(PushTask.STATUS_ACCEPTED);
            } else {
                task.setStatus(PushTask.STATUS_PENDING);
                notifyTaskReceived(task);
            }

            pendingTasks.put(task.getTaskId(), task);

            JSONObject response = new JSONObject();
            response.put("taskId", task.getTaskId());
            response.put("type", task.getType());
            response.put("status", task.getStatus());
            response.put("mode", task.getMode());
            response.put("createdTime", task.getCreatedAt());
            response.put("fromDevice", task.getSourceDevice());
            response.put("progress", 0);
            response.put("total", task.getTotalCount());
            response.put("transferred", 0);

            if (task.isFileTransfer()) {
                response.put("fileName", task.getFileName());
                response.put("fileSize", task.getFileSize());
            }

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to create task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 获取待处理任务列表
     */
    private NanoHTTPD.Response handleGetTasks(NanoHTTPD.IHTTPSession session) {
        try {
            JSONArray tasksArray = new JSONArray();
            for (PushTask task : pendingTasks.values()) {
                tasksArray.add(taskToJson(task));
            }

            JSONObject response = new JSONObject();
            response.put("tasks", tasksArray);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to get tasks", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 获取任务状态
     */
    private NanoHTTPD.Response handleGetTaskStatus(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            PushTask task = pendingTasks.get(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            return ResponseBuilder.jsonSuccess(taskToJson(task).toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to get task status", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 接受任务
     */
    private NanoHTTPD.Response handleAcceptTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            PushTask task = pendingTasks.get(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            task.setStatus(PushTask.STATUS_ACCEPTED);
            notifyTaskAccepted(task);

            JSONObject response = new JSONObject();
            response.put("success", true);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to accept task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 拒绝任务
     */
    private NanoHTTPD.Response handleRejectTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            PushTask task = pendingTasks.get(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            task.setStatus(PushTask.STATUS_REJECTED);
            pendingTasks.remove(taskId);
            notifyTaskRejected(task);

            JSONObject response = new JSONObject();
            response.put("success", true);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to reject task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 传输数据/文件块（POST /api/v1/push/tasks/{taskId}/data）
     */
    private NanoHTTPD.Response handlePostTaskData(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            PushTask task = pendingTasks.get(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            if (!PushTask.STATUS_ACCEPTED.equals(task.getStatus())) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task not accepted");
            }

            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);

            // 文件块传输
            if (task.isFileTransfer()) {
                return handleFileChunk(task, json);
            }

            // 普通数据传输
            task.setStatus(PushTask.STATUS_TRANSFERRING);
            task.setTransferredCount(task.getTransferredCount() + 1);
            task.setUpdatedAt(System.currentTimeMillis());

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("received", 1);
            response.put("totalReceived", task.getTransferredCount());

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to post task data", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 处理文件块传输
     */
    private NanoHTTPD.Response handleFileChunk(PushTask task, JSONObject json) {
        try {
            int chunk = json.getIntValue("chunk");
            int totalChunks = json.getIntValue("totalChunks");
            long offset = json.getLongValue("offset");
            int length = json.getIntValue("length");
            String data = json.getString("data");
            boolean isLast = json.getBooleanValue("isLast");

            // 首次接收，创建临时文件
            if (task.getTempFilePath() == null) {
                File tempDir = context.getCacheDir();
                File tempFile = new File(tempDir, "push_" + task.getTaskId() + "_" + task.getFileName());
                task.setTempFilePath(tempFile.getAbsolutePath());
                task.setTotalChunks(totalChunks);
                task.setReceivedChunks(0);
                task.setReceivedBytes(0);
            }

            // 解码并写入文件
            byte[] chunkData = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
            File tempFile = new File(task.getTempFilePath());

            try (FileOutputStream fos = new FileOutputStream(tempFile, true)) {
                fos.write(chunkData);
            }

            task.setReceivedChunks(task.getReceivedChunks() + 1);
            task.setReceivedBytes(task.getReceivedBytes() + chunkData.length);
            task.setStatus(PushTask.STATUS_TRANSFERRING);
            task.setUpdatedAt(System.currentTimeMillis());

            // 检查是否完成
            if (isLast || task.getReceivedChunks() >= task.getTotalChunks()) {
                task.setStatus(PushTask.STATUS_COMPLETED);
                TransferLogger.getInstance().d(TAG, "File transfer completed: " + task.getFileName());

                // 触发导入
                importTransferredFile(task);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("received", chunkData.length);
            response.put("totalReceived", task.getReceivedBytes());

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to handle file chunk", e);
            task.setStatus(PushTask.STATUS_FAILED);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 导入传输完成的文件
     */
    private void importTransferredFile(PushTask task) {
        try {
            File tempFile = new File(task.getTempFilePath());
            if (!tempFile.exists()) {
                TransferLogger.getInstance().e(TAG, "Temp file not found: " + task.getTempFilePath());
                return;
            }

            String type = task.getType();
            if (PushTask.TYPE_EXPORT_DB.equals(type)) {
                // 导入数据库
                String error = EhDB.importDB(context, tempFile, null);
                if (error != null) {
                    TransferLogger.getInstance().e(TAG, "Import DB failed: " + error);
                    task.setStatus(PushTask.STATUS_FAILED);
                } else {
                    TransferLogger.getInstance().d(TAG, "Import DB success");
                }
            } else if (PushTask.TYPE_EXPORT_CSV.equals(type)) {
                // CSV导入需要解析，这里标记为完成，由用户手动处理
                TransferLogger.getInstance().d(TAG, "CSV file received: " + task.getFileName());
            }

            // 不删除临时文件，供用户查看

        } catch (Exception e) {
            Log.e(TAG, "Failed to import transferred file", e);
            task.setStatus(PushTask.STATUS_FAILED);
        }
    }

    /**
     * 获取任务数据（分页）
     */
    private NanoHTTPD.Response handleGetTaskData(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            PushTask task = pendingTasks.get(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            int offset = RequestParser.getIntQueryParameter(session, "offset", 0);
            int limit = RequestParser.getIntQueryParameter(session, "limit", 50);

            JSONArray data = new JSONArray();
            // 这里需要从发送端获取数据，暂时返回空
            // 实际实现中，发送端会在创建任务时缓存数据

            JSONObject response = new JSONObject();
            response.put("type", task.getType());
            response.put("offset", offset);
            response.put("limit", limit);
            response.put("total", task.getTotalCount());
            response.put("data", data);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to get task data", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 检查是否自动接收
     */
    private boolean isAutoReceive(String type) {
        switch (type) {
            case PushTask.TYPE_BOOKMARKS:
                return Settings.isAutoReceiveBookmarks();
            case PushTask.TYPE_DOWNLOADS:
                return Settings.isAutoReceiveDownloads();
            case PushTask.TYPE_FAVORITES:
                return Settings.isAutoReceiveFavorites();
            default:
                return false;
        }
    }

    /**
     * 从URI中提取taskId
     */
    private String extractTaskId(String uri) {
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("tasks".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return "";
    }

    /**
     * 任务转JSON
     */
    private JSONObject taskToJson(PushTask task) {
        JSONObject json = new JSONObject();
        json.put("taskId", task.getTaskId());
        json.put("type", task.getType());
        json.put("mode", task.getMode());
        json.put("sourceDevice", task.getSourceDevice());
        json.put("status", task.getStatus());
        json.put("totalCount", task.getTotalCount());
        json.put("transferredCount", task.getTransferredCount());
        json.put("progress", task.getProgressPercent());
        json.put("createdAt", task.getCreatedAt());
        json.put("updatedAt", task.getUpdatedAt());

        if (task.isFileTransfer()) {
            json.put("fileName", task.getFileName());
            json.put("fileSize", task.getFileSize());
            json.put("totalChunks", task.getTotalChunks());
            json.put("receivedChunks", task.getReceivedChunks());
            json.put("receivedBytes", task.getReceivedBytes());
        }

        return json;
    }

    /**
     * 添加监听器
     */
    public void addListener(PushTaskListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 移除监听器
     */
    public void removeListener(PushTaskListener listener) {
        listeners.remove(listener);
    }

    private void notifyTaskReceived(PushTask task) {
        for (PushTaskListener listener : listeners) {
            listener.onTaskReceived(task);
        }
    }

    private void notifyTaskAccepted(PushTask task) {
        for (PushTaskListener listener : listeners) {
            listener.onTaskAccepted(task);
        }
    }

    private void notifyTaskRejected(PushTask task) {
        for (PushTaskListener listener : listeners) {
            listener.onTaskRejected(task);
        }
    }

    /**
     * 推送任务监听器
     */
    public interface PushTaskListener {
        void onTaskReceived(PushTask task);
        void onTaskAccepted(PushTask task);
        void onTaskRejected(PushTask task);
    }
}
