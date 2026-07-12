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
                // 通知监听器等待确认
                notifyTaskReceived(task);
            }

            pendingTasks.put(task.getTaskId(), task);

            JSONObject response = new JSONObject();
            response.put("taskId", task.getTaskId());
            response.put("status", task.getStatus());

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
