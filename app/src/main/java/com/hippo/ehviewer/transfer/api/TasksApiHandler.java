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

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.FileTreeTaskExecutor;
import com.hippo.ehviewer.transfer.core.TaskManager;
import com.hippo.ehviewer.transfer.data.UnifiedTask;
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
        logRequest("GET", uri);

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
        logRequest("DELETE", uri);

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

    // ==================== Transfer Tasks ====================

    /**
     * GET /api/v1/tasks?type=all&status=all
     */
    private NanoHTTPD.Response handleGetAllTasks(NanoHTTPD.IHTTPSession session) {
        try {
            String type = RequestParser.getQueryParameter(session, "type", "all");
            String status = RequestParser.getQueryParameter(session, "status", "all");

            List<UnifiedTask> tasks = taskManager.getTasks(type, status);

            JSONObject response = new JSONObject();
            JSONArray tasksArray = new JSONArray();
            for (UnifiedTask task : tasks) {
                tasksArray.add(task.toApiJson());
            }
            response.put("tasks", tasksArray);
            response.put("total", tasks.size());

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
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
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * GET /api/v1/tasks/{taskId}
     */
    private NanoHTTPD.Response handleGetTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            UnifiedTask task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }
            return ResponseBuilder.jsonSuccess(task.toApiJson().toJSONString());
        } catch (Exception e) {
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * DELETE /api/v1/tasks/{taskId}
     */
    private NanoHTTPD.Response handleDeleteTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
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

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * GET /api/v1/background-tasks/{taskId}
     */
    private NanoHTTPD.Response handleGetBackgroundTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
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

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * DELETE /api/v1/background-tasks/{taskId}
     */
    private NanoHTTPD.Response handleDeleteBackgroundTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
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
        json.put("startTime", info.getStartTime());
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
