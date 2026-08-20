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

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.task.TaskRegistry;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.FileTreeTaskExecutor;
import com.hippo.ehviewer.transfer.core.TaskManager;
import com.hippo.ehviewer.transfer.data.UnifiedTask;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.ehviewer.ui.task.BackgroundTaskInfo;
import com.hippo.ehviewer.ui.task.BackgroundTaskStatusManager;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;

/**
 * Unified tasks API handler
 * Provides endpoints to query both transfer tasks and background tasks
 */
public class TasksApiHandler extends BaseApiHandler {

    private static final String TAG = "TasksApiHandler";
    private final TaskManager taskManager;

    public TasksApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
        this.taskManager = TaskManager.getInstance();
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri, session);

        // GET /api/v1/tasks - list all transfer tasks
        if (uri.equals("/api/v1/tasks")) {
            return handleGetAllTasks(session);
        }

        // GET /api/v1/tasks/{taskId}/download - 下载归档ZIP
        if (uri.matches("/api/v1/tasks/[^/]+/download")) {
            String taskId = uri.substring("/api/v1/tasks/".length(), uri.length() - "/download".length());
            return handleTaskDownload(session, taskId);
        }

        // GET /api/v1/tasks/{taskId} - get transfer task status
        if (uri.matches("/api/v1/tasks/[^/]+$")) {
            String taskId = uri.substring("/api/v1/tasks/".length());
            return handleGetTask(session, taskId);
        }

        // GET /api/v1/background-tasks - list all background tasks
        if (uri.equals("/api/v1/background-tasks")) {
            return handleGetBackgroundTasks(session);
        }

        // GET /api/v1/background-tasks/task-types - list creatable task types
        if (uri.equals("/api/v1/background-tasks/task-types")) {
            return handleGetTaskTypes(session);
        }

        // GET /api/v1/background-tasks/{taskId} - get background task detail
        if (uri.matches("/api/v1/background-tasks/[^/]+$")) {
            String taskId = uri.substring("/api/v1/background-tasks/".length());
            return handleGetBackgroundTask(session, taskId);
        }

        // GET /api/v1/background-tasks/{taskId}/logs - get background task logs
        if (uri.matches("/api/v1/background-tasks/[^/]+/logs$")) {
            String path = uri.substring("/api/v1/background-tasks/".length());
            String taskId = path.substring(0, path.length() - 5); // remove "/logs"
            return handleGetBackgroundTaskLogs(session, taskId);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handleDelete(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("DELETE", uri, session);

        // DELETE /api/v1/tasks/{taskId} - cancel/remove transfer task
        if (uri.matches("/api/v1/tasks/[^/]+$")) {
            String taskId = uri.substring("/api/v1/tasks/".length());
            return handleDeleteTask(session, taskId);
        }

        // DELETE /api/v1/background-tasks/{taskId} - cancel/remove background task
        if (uri.matches("/api/v1/background-tasks/[^/]+$")) {
            String taskId = uri.substring("/api/v1/background-tasks/".length());
            return handleDeleteBackgroundTask(session, taskId);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri, session);

        // POST /api/v1/background-tasks - create a background task
        if (uri.equals("/api/v1/background-tasks")) {
            return handleCreateBackgroundTask(session);
        }

        // POST /api/v1/background-tasks/{taskId}/start - start/resume a background task
        if (uri.matches("/api/v1/background-tasks/[^/]+/start")) {
            String taskId = uri.substring("/api/v1/background-tasks/".length(), uri.length() - "/start".length());
            return handleStartBackgroundTask(session, taskId);
        }

        // POST /api/v1/background-tasks/{taskId}/pause - pause a background task
        if (uri.matches("/api/v1/background-tasks/[^/]+/pause")) {
            String taskId = uri.substring("/api/v1/background-tasks/".length(), uri.length() - "/pause".length());
            return handlePauseBackgroundTask(session, taskId);
        }

        // POST /api/v1/background-tasks/{taskId}/resume - resume a background task
        if (uri.matches("/api/v1/background-tasks/[^/]+/resume")) {
            String taskId = uri.substring("/api/v1/background-tasks/".length(), uri.length() - "/resume".length());
            return handleResumeBackgroundTask(session, taskId);
        }

        // POST /api/v1/background-tasks/{taskId}/stop|cancel - stop a background task
        if (uri.matches("/api/v1/background-tasks/[^/]+/(stop|cancel)")) {
            String path = uri.substring("/api/v1/background-tasks/".length());
            String taskId = path.substring(0, path.lastIndexOf('/'));
            return handleStopBackgroundTask(session, taskId);
        }

        // POST /api/v1/tasks/{taskId}/pause - pause a transfer task
        if (uri.matches("/api/v1/tasks/[^/]+/pause")) {
            String taskId = uri.substring("/api/v1/tasks/".length(), uri.length() - "/pause".length());
            return handlePauseTransferTask(session, taskId);
        }

        // POST /api/v1/tasks/{taskId}/resume - resume a transfer task
        if (uri.matches("/api/v1/tasks/[^/]+/resume")) {
            String taskId = uri.substring("/api/v1/tasks/".length(), uri.length() - "/resume".length());
            return handleResumeTransferTask(session, taskId);
        }

        // POST /api/v1/tasks/{taskId}/stop - stop a transfer task
        if (uri.matches("/api/v1/tasks/[^/]+/stop")) {
            String taskId = uri.substring("/api/v1/tasks/".length(), uri.length() - "/stop".length());
            return handleStopTransferTask(session, taskId);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    // ==================== Transfer Tasks ====================

    /**
     * GET /api/v1/tasks?type=all&status=all
     */
    private NanoHTTPD.Response handleGetAllTasks(NanoHTTPD.IHTTPSession session) {
        try {
            String type = RequestParser.getQueryParameter(session, "type", "all");
            String status = RequestParser.getQueryParameter(session, "status", "all");

            List<UnifiedTask> tasks = taskManager.getTasks(type, status);
            TransferLogger.getInstance().d(TAG, "获取统一任务列表: type=" + type + ", status=" + status);

            JSONObject response = new JSONObject();
            JSONArray tasksArray = new JSONArray();
            for (UnifiedTask task : tasks) {
                tasksArray.add(task.toApiJson());
            }
            response.put("tasks", tasksArray);
            response.put("total", tasks.size());

            TransferLogger.getInstance().i(TAG, "统一任务列表: 返回 " + tasks.size() + " 个");
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to get all tasks", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * GET /api/v1/tasks/{taskId}/download
     */
    private NanoHTTPD.Response handleTaskDownload(NanoHTTPD.IHTTPSession session, String taskId) {
        UnifiedTask task = taskManager.getTask(taskId);
        if (task == null) {
            return ResponseBuilder.notFound("Task");
        }
        if (!UnifiedTask.STATUS_COMPLETED.equals(task.status)) {
            return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task not completed");
        }
        File file = FileTreeTaskExecutor.getInstance().getArchive(taskId);
        if (file == null || !file.exists()) {
            return ResponseBuilder.notFound("File");
        }
        try {
            long fileLength = file.length();
            TransferLogger.getInstance().d(TAG, "下载任务归档: taskId=" + taskId + ", file=" + file.getName() + ", size=" + fileLength);
            String rangeHeader = session.getHeaders().get("range");
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String range = rangeHeader.substring(6);
                String[] parts = range.split("-");
                long start = Long.parseLong(parts[0]);
                long end = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : fileLength - 1;
                if (start >= fileLength || end < start) {
                    return NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Range not satisfiable");
                }
                if (end >= fileLength) end = fileLength - 1;
                long length = end - start + 1;
                FileInputStream fis = new FileInputStream(file);
                fis.skip(start);
                NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.PARTIAL_CONTENT, "application/zip", fis, length);
                response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
                response.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
                return response;
            }
            FileInputStream fis = new FileInputStream(file);
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, "application/zip", fis, fileLength);
            response.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            response.addHeader("Accept-Ranges", "bytes");
            return response;
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to download task archive", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * GET /api/v1/tasks/{taskId}
     */
    private NanoHTTPD.Response handleGetTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            TransferLogger.getInstance().d(TAG, "获取统一任务状态: taskId=" + taskId);
            UnifiedTask task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }
            return ResponseBuilder.jsonSuccess(task.toApiJson().toJSONString());
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to get task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * DELETE /api/v1/tasks/{taskId}
     */
    private NanoHTTPD.Response handleDeleteTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            TransferLogger.getInstance().i(TAG, "删除统一任务: taskId=" + taskId);
            UnifiedTask task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            if (!task.isTerminal()) {
                // Cancel active tasks
                task.status = UnifiedTask.STATUS_CANCELLED;
                task.completedTime = System.currentTimeMillis();
                task.updatedTime = task.completedTime;
                taskManager.updateTask(task);
            } else {
                // Remove completed tasks
                taskManager.removeTask(taskId);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Task cancelled/removed");

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to delete task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== Background Tasks ====================

    /**
     * GET /api/v1/background-tasks?status=all
     * status: all, active, completed
     */
    private NanoHTTPD.Response handleGetBackgroundTasks(NanoHTTPD.IHTTPSession session) {
        try {
            String status = RequestParser.getQueryParameter(session, "status", "all");
            BackgroundTaskStatusManager manager = BackgroundTaskStatusManager.getInstance();

            JSONObject response = new JSONObject();
            JSONArray activeArray = new JSONArray();
            JSONArray completedArray = new JSONArray();

            if ("all".equals(status) || "active".equals(status)) {
                List<BackgroundTaskInfo> activeTasks = manager.getActiveTasks();
                for (BackgroundTaskInfo info : activeTasks) {
                    activeArray.add(backgroundTaskToJson(info));
                }
            }

            if ("all".equals(status) || "completed".equals(status)) {
                List<BackgroundTaskInfo> completedTasks = manager.getCompletedTasks();
                for (BackgroundTaskInfo info : completedTasks) {
                    completedArray.add(backgroundTaskToJson(info));
                }
            }

            response.put("active", activeArray);
            response.put("completed", completedArray);
            response.put("total", activeArray.size() + completedArray.size());

            TransferLogger.getInstance().d(TAG, "后台任务列表: status=" + status +
                    ", active=" + activeArray.size() + ", completed=" + completedArray.size());
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to get background tasks", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * GET /api/v1/background-tasks/{taskId}
     */
    private NanoHTTPD.Response handleGetBackgroundTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            TransferLogger.getInstance().d(TAG, "获取后台任务详情: taskId=" + taskId);
            BackgroundTaskStatusManager manager = BackgroundTaskStatusManager.getInstance();
            BackgroundTaskInfo info = manager.getTaskInfo(taskId);
            if (info == null) {
                return ResponseBuilder.notFound("BackgroundTask");
            }

            JSONObject json = backgroundTaskToJson(info);

            // Include log summary
            List<String> logs = info.getLogMessages();
            json.put("logCount", logs.size());
            if (!logs.isEmpty()) {
                json.put("lastLog", logs.get(logs.size() - 1));
            }

            return ResponseBuilder.jsonSuccess(json.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to get background task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * GET /api/v1/background-tasks/{taskId}/logs
     */
    private NanoHTTPD.Response handleGetBackgroundTaskLogs(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            BackgroundTaskStatusManager manager = BackgroundTaskStatusManager.getInstance();
            BackgroundTaskInfo info = manager.getTaskInfo(taskId);
            if (info == null) {
                return ResponseBuilder.notFound("BackgroundTask");
            }

            JSONObject response = new JSONObject();
            response.put("taskId", taskId);
            response.put("taskName", info.getTaskName());

            JSONArray logArray = new JSONArray();
            List<String> logs = info.getLogMessages();
            for (String log : logs) {
                logArray.add(log);
            }
            response.put("logs", logArray);
            response.put("total", logs.size());

            TransferLogger.getInstance().d(TAG, "后台任务日志: taskId=" + taskId + ", " + logs.size() + " 条");
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to get background task logs", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * DELETE /api/v1/background-tasks/{taskId}
     */
    private NanoHTTPD.Response handleDeleteBackgroundTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            TransferLogger.getInstance().i(TAG, "删除后台任务: taskId=" + taskId);
            BackgroundTaskStatusManager manager = BackgroundTaskStatusManager.getInstance();
            BackgroundTaskInfo info = manager.getTaskInfo(taskId);
            if (info == null) {
                return ResponseBuilder.notFound("BackgroundTask");
            }

            if (!info.isCompleted() && !info.isCancelled()) {
                // Cancel active tasks
                manager.cancelTask(taskId);
            } else {
                // Remove completed tasks
                manager.removeTask(taskId);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Background task cancelled/removed");

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to delete background task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== Background Task Control ====================

    /**
     * POST /api/v1/background-tasks
     * Body: {"className": "...", "params": {...}}
     */
    private NanoHTTPD.Response handleCreateBackgroundTask(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);
            String className = json != null ? json.getString("className") : null;
            if (className == null || className.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "className is required");
            }
            JSONObject params = json.getJSONObject("params");
            BackgroundTaskManager manager = BackgroundTaskManager.getInstance();
            BackgroundTaskManager.TaskHandle handle = manager.createTaskByClassName(className, params);
            if (handle == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Unsupported task or invalid params: " + className);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("accepted", true);
            response.put("taskId", handle.taskId);
            response.put("status", "pending");
            response.put("taskClassName", className);
            return ResponseBuilder.accepted(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to create background task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * POST /api/v1/background-tasks/{taskId}/start
     */
    private NanoHTTPD.Response handleStartBackgroundTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            BackgroundTaskStatusManager manager = BackgroundTaskStatusManager.getInstance();
            BackgroundTaskInfo info = manager.getTaskInfo(taskId);
            if (info == null) {
                return ResponseBuilder.notFound("BackgroundTask");
            }
            if (info.isCompleted() || info.isCancelled()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task already finished");
            }
            if (info.isPaused()) {
                BackgroundTaskManager.getInstance().resumeTask(taskId);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("taskId", taskId);
            response.put("state", info.isQueued() ? "PENDING" : "RUNNING");
            response.put("message", info.isQueued()
                    ? "Task is queued and will start automatically"
                    : "Task started");
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to start background task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * POST /api/v1/background-tasks/{taskId}/pause
     */
    private NanoHTTPD.Response handlePauseBackgroundTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            BackgroundTaskStatusManager manager = BackgroundTaskStatusManager.getInstance();
            BackgroundTaskInfo info = manager.getTaskInfo(taskId);
            if (info == null) {
                return ResponseBuilder.notFound("BackgroundTask");
            }
            if (info.isCompleted() || info.isCancelled()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task already finished");
            }
            if (info.isPaused()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task already paused");
            }
            if (!info.isPausable()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task does not support pause");
            }
            boolean ok = BackgroundTaskManager.getInstance().pauseTask(taskId);
            if (!ok) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task cannot be paused");
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("taskId", taskId);
            response.put("paused", true);
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to pause background task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * POST /api/v1/background-tasks/{taskId}/resume
     */
    private NanoHTTPD.Response handleResumeBackgroundTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            BackgroundTaskStatusManager manager = BackgroundTaskStatusManager.getInstance();
            BackgroundTaskInfo info = manager.getTaskInfo(taskId);
            if (info == null) {
                return ResponseBuilder.notFound("BackgroundTask");
            }
            if (info.isCompleted() || info.isCancelled()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task already finished");
            }
            if (!info.isPaused()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task is not paused");
            }
            boolean ok = BackgroundTaskManager.getInstance().resumeTask(taskId);
            if (!ok) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task cannot be resumed");
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("taskId", taskId);
            response.put("paused", false);
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to resume background task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * POST /api/v1/background-tasks/{taskId}/stop (or /cancel)
     */
    private NanoHTTPD.Response handleStopBackgroundTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            BackgroundTaskStatusManager manager = BackgroundTaskStatusManager.getInstance();
            BackgroundTaskInfo info = manager.getTaskInfo(taskId);
            if (info == null) {
                return ResponseBuilder.notFound("BackgroundTask");
            }
            boolean ok = BackgroundTaskManager.getInstance().cancelTask(taskId);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("taskId", taskId);
            response.put("message", ok ? "Task stopped" : "Task already finished");
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to stop background task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * GET /api/v1/background-tasks/task-types
     */
    private NanoHTTPD.Response handleGetTaskTypes(NanoHTTPD.IHTTPSession session) {
        try {
            List<TaskRegistry.TaskMetadata> metas = TaskRegistry.INSTANCE.getAllTasks();
            JSONArray array = new JSONArray();
            for (TaskRegistry.TaskMetadata meta : metas) {
                JSONObject json = new JSONObject();
                json.put("taskClassName", meta.getTaskClassName());
                json.put("taskType", meta.getTaskType().name());
                json.put("requiresParams", meta.getRequiresParams());
                json.put("paramType", meta.getParamType().name());
                try {
                    json.put("displayName", context.getString(meta.getDisplayNameResId()));
                } catch (Exception ignore) {
                    json.put("displayName", meta.getTaskClassName());
                }
                try {
                    json.put("description", context.getString(meta.getDescriptionResId()));
                } catch (Exception ignore) {
                    json.put("description", "");
                }
                array.add(json);
            }
            JSONObject response = new JSONObject();
            response.put("taskTypes", array);
            response.put("total", array.size());
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to get task types", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== Transfer Task Control ====================

    /**
     * POST /api/v1/tasks/{taskId}/pause
     */
    private NanoHTTPD.Response handlePauseTransferTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            UnifiedTask task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }
            if (!taskManager.pauseTask(taskId)) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Task cannot be paused (not active or already paused)");
            }
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("taskId", taskId);
            response.put("paused", true);
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to pause transfer task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * POST /api/v1/tasks/{taskId}/resume
     */
    private NanoHTTPD.Response handleResumeTransferTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            UnifiedTask task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }
            if (!taskManager.resumeTask(taskId)) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Task cannot be resumed (not paused)");
            }
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("taskId", taskId);
            response.put("paused", false);
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to resume transfer task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * POST /api/v1/tasks/{taskId}/stop
     */
    private NanoHTTPD.Response handleStopTransferTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            UnifiedTask task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }
            if (task.isTerminal()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task already finished");
            }
            task.status = UnifiedTask.STATUS_CANCELLED;
            task.paused = false;
            task.completedTime = System.currentTimeMillis();
            task.updatedTime = task.completedTime;
            taskManager.updateTask(task);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("taskId", taskId);
            response.put("message", "Task stopped");
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to stop transfer task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private JSONObject backgroundTaskToJson(BackgroundTaskInfo info) {
        JSONObject json = new JSONObject();
        json.put("taskId", info.getTaskId());
        json.put("taskName", info.getTaskName());
        json.put("taskDescription", info.getTaskDescription());
        json.put("taskType", info.getTaskType().name());
        json.put("taskClassName", info.getTaskClassName());
        json.put("startTime", info.getStartTime());
        json.put("isPausable", info.isPausable());
        json.put("isQueued", info.isQueued());
        json.put("canStart", !info.isCompleted() && !info.isCancelled());
        json.put("currentProgress", info.getCurrentProgress());
        json.put("totalProgress", info.getTotalProgress());
        json.put("progressPercentage", info.getProgressPercentage());

        String detail = info.getProgressDetail();
        if (detail != null) {
            json.put("progressDetail", detail);
        }

        // State
        if (info.isCancelled()) {
            json.put("state", "CANCELLED");
        } else if (info.isCompleted()) {
            json.put("state", info.getErrorMessage() != null ? "FAILED" : "COMPLETED");
        } else if (info.isQueued()) {
            json.put("state", "PENDING");
        } else if (info.isPaused()) {
            json.put("state", "PAUSED");
        } else {
            json.put("state", "RUNNING");
        }

        json.put("isPaused", info.isPaused());
        json.put("isCompleted", info.isCompleted());
        json.put("isCancelled", info.isCancelled());

        // Running time
        long runningTime = info.getRunningTime();
        json.put("runningTimeMs", runningTime);

        // ETA
        long eta = info.getEstimatedRemainingTime();
        json.put("estimatedRemainingMs", eta);

        // Error
        String error = info.getErrorMessage();
        if (error != null) {
            json.put("errorMessage", error);
        }

        return json;
    }
}
