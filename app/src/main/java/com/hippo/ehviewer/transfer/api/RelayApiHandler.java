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
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.RelayTaskManager;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.ehviewer.transfer.data.RelayTask;
import com.hippo.ehviewer.util.GZIPUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 接力下载API处理器
 *
 * 端点:
 * - POST   /api/v1/relay/create              创建接力任务
 * - POST   /api/v1/relay/batch               批量创建接力任务
 * - GET    /api/v1/relay/tasks               获取任务列表
 * - GET    /api/v1/relay/{taskId}/status      获取任务状态
 * - POST   /api/v1/relay/{taskId}/accept      接受任务
 * - POST   /api/v1/relay/{taskId}/reject      拒绝任务
 * - POST   /api/v1/relay/{taskId}/cancel      取消任务
 * - GET    /api/v1/relay/{taskId}/download     下载完成的文件
 * - DELETE /api/v1/relay/{taskId}             删除任务
 */
public class RelayApiHandler extends BaseApiHandler {

    private static final String TAG = "RelayApiHandler";

    private RelayTaskManager relayTaskManager;

    public RelayApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
        this.relayTaskManager = RelayTaskManager.getInstance(context);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);

        String path = uri.split("\\?")[0];

        // GET /api/v1/relay/tasks - 获取任务列表
        if (path.equals("/api/v1/relay/tasks") || uri.startsWith("/api/v1/relay/tasks?")) {
            return handleGetTasks(session);
        }

        // GET /api/v1/relay/{taskId}/status - 获取任务状态
        if (path.matches("/api/v1/relay/[^/]+/status")) {
            String taskId = extractTaskId(path, "status");
            return handleGetTaskStatus(session, taskId);
        }

        // GET /api/v1/relay/{taskId}/download - 下载完成的文件
        if (path.matches("/api/v1/relay/[^/]+/download")) {
            String taskId = extractTaskId(path, "download");
            return handleDownload(session, taskId);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri);

        String path = uri.split("\\?")[0];

        // POST /api/v1/relay/create - 创建接力任务
        if (path.equals("/api/v1/relay/create")) {
            return handleCreateTask(session);
        }

        // POST /api/v1/relay/batch - 批量创建接力任务
        if (path.equals("/api/v1/relay/batch")) {
            return handleBatchCreate(session);
        }

        // POST /api/v1/relay/request - 请求接力下载（将正在下载的画廊转为接力状态）
        if (path.equals("/api/v1/relay/request")) {
            return handleRequestRelay(session);
        }

        // POST /api/v1/relay/return/receive - 接收推送回传的ZIP文件
        if (path.equals("/api/v1/relay/return/receive")) {
            return handleReturnReceive(session);
        }

        // POST /api/v1/relay/{taskId}/accept - 接受任务
        if (path.matches("/api/v1/relay/[^/]+/accept")) {
            String taskId = extractTaskId(path, "accept");
            return handleAcceptTask(session, taskId);
        }

        // POST /api/v1/relay/{taskId}/reject - 拒绝任务
        if (path.matches("/api/v1/relay/[^/]+/reject")) {
            String taskId = extractTaskId(path, "reject");
            return handleRejectTask(session, taskId);
        }

        // POST /api/v1/relay/{taskId}/cancel - 取消任务
        if (path.matches("/api/v1/relay/[^/]+/cancel")) {
            String taskId = extractTaskId(path, "cancel");
            return handleCancelTask(session, taskId);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handleDelete(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("DELETE", uri);

        String path = uri.split("\\?")[0];

        // DELETE /api/v1/relay/{taskId} - 删除任务
        if (path.matches("/api/v1/relay/[^/]+$")) {
            String taskId = extractTaskId(path, null);
            return handleDeleteTask(session, taskId);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    // ==================== 业务逻辑 ====================

    /**
     * 创建接力任务
     */
    private NanoHTTPD.Response handleCreateTask(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);

            long gid = json.getLongValue("gid");
            if (gid <= 0) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid gid");
            }

            // 检查是否已有相同的接力任务
            RelayTask existingTask = relayTaskManager.getTaskByGid(gid);
            if (existingTask != null && !existingTask.isTerminal()) {
                JSONObject errorResponse = new JSONObject();
                errorResponse.put("success", false);
                errorResponse.put("error", "Relay task already exists for gid: " + gid);
                errorResponse.put("code", 409);
                return ResponseBuilder.jsonSuccess(errorResponse.toJSONString());
            }

            String token = json.getString("token");
            String title = json.getString("title");
            String titleJpn = json.getString("titleJpn");
            String thumb = json.getString("thumb");
            int category = json.getIntValue("category");
            String posted = json.getString("posted");
            String uploader = json.getString("uploader");
            float rating = json.getFloatValue("rating");
            int pages = json.getIntValue("pages");
            String sourceDevice = json.getString("sourceDevice");
            String sourceDeviceId = json.getString("sourceDeviceId");
            String targetDevice = json.getString("targetDevice");
            String targetDeviceId = json.getString("targetDeviceId");
            String priority = json.getString("priority");
            boolean autoReturn = json.getBooleanValue("autoReturn");

            String remoteIp = session.getRemoteIpAddress();
            int sourcePort = json.getIntValue("sourcePort");
            if (sourcePort <= 0) sourcePort = 8080;

            if (priority == null) priority = RelayTask.PRIORITY_NORMAL;

            DownloadManager downloadManager = EhApplication.getDownloadManager(context);
            boolean isAlreadyCompleted = false;

            if (downloadManager.containDownloadInfo(gid)) {
                DownloadInfo existingDownload = downloadManager.getDownloadInfo(gid);
                if (existingDownload != null && existingDownload.state == DownloadInfo.STATE_FINISH) {
                    isAlreadyCompleted = true;
                }
            } else {
                GalleryInfo galleryInfo = new GalleryInfo();
                galleryInfo.gid = gid;
                galleryInfo.token = token;
                galleryInfo.title = title;
                galleryInfo.titleJpn = titleJpn;
                galleryInfo.thumb = thumb;
                galleryInfo.category = category;
                galleryInfo.posted = posted;
                galleryInfo.uploader = uploader;
                galleryInfo.rating = rating;
                galleryInfo.pages = pages;
                downloadManager.addDownload(galleryInfo, null, DownloadInfo.STATE_NONE);
                TransferLogger.getInstance().d(TAG, "Created DownloadInfo for relay gid: " + gid);
            }

            RelayTask task = relayTaskManager.createIncomingTask(gid, token, title, titleJpn,
                    thumb, category, posted, uploader, rating, pages,
                    sourceDevice, sourceDeviceId, remoteIp, sourcePort,
                    priority, autoReturn);

            if (isAlreadyCompleted) {
                TransferLogger.getInstance().i(TAG, "Gallery already completed for gid: " + gid + ", directly packaging and pushing back");
                relayTaskManager.acceptTask(task.getTaskId(), null, null);
                relayTaskManager.markCompleted(task.getTaskId());
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("taskId", task.getTaskId());
            response.put("gid", gid);
            response.put("status", task.getStatus());
            response.put("createdTime", task.getCreatedTime());
            if (isAlreadyCompleted) {
                response.put("alreadyCompleted", true);
                response.put("message", "Gallery already completed on this device, packaging for return...");
            }

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to create relay task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 批量创建接力任务
     */
    private NanoHTTPD.Response handleBatchCreate(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);

            JSONArray gidsArray = json.getJSONArray("gids");
            if (gidsArray == null || gidsArray.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "No GIDs provided");
            }

            String sourceDevice = json.getString("sourceDevice");
            String sourceDeviceId = json.getString("sourceDeviceId");
            String priority = json.getString("priority");
            boolean autoReturn = json.getBooleanValue("autoReturn");

            if (priority == null) priority = RelayTask.PRIORITY_NORMAL;

            String remoteIp = session.getRemoteIpAddress();
            int sourcePort = json.getIntValue("sourcePort");
            if (sourcePort <= 0) sourcePort = 8080;

            DownloadManager downloadManager = EhApplication.getDownloadManager(context);

            JSONArray tasksArray = new JSONArray();
            for (int i = 0; i < gidsArray.size(); i++) {
                long gid = gidsArray.getLong(i);

                // 检查是否已有相同的接力任务
                RelayTask existingTask = relayTaskManager.getTaskByGid(gid);
                if (existingTask != null && !existingTask.isTerminal()) {
                    continue;
                }

                if (!downloadManager.containDownloadInfo(gid)) {
                    GalleryInfo galleryInfo = new GalleryInfo();
                    galleryInfo.gid = gid;
                    downloadManager.addDownload(galleryInfo, null, DownloadInfo.STATE_NONE);
                    TransferLogger.getInstance().d(TAG, "Batch: created DownloadInfo for relay gid: " + gid);
                }

                // 这里需要从下载信息中获取详细信息
                // 简化处理：只使用gid，其他信息后续补充
                RelayTask task = relayTaskManager.createTask(gid, null, null, null,
                        null, 0, null, null, 0, 0,
                        sourceDevice, sourceDeviceId, remoteIp, sourcePort, null, null,
                        priority, autoReturn);

                JSONObject taskJson = new JSONObject();
                taskJson.put("taskId", task.getTaskId());
                taskJson.put("gid", gid);
                taskJson.put("status", task.getStatus());
                tasksArray.add(taskJson);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("tasks", tasksArray);
            response.put("total", tasksArray.size());

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to batch create relay tasks", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 请求接力下载：将正在下载中或等待中的画廊转为接力下载状态
     * 其他客户端主动调用此端点，请求将某个画廊交给自己来下载
     */
    private NanoHTTPD.Response handleRequestRelay(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);

            long gid = json.getLongValue("gid");
            if (gid <= 0) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid gid");
            }

            // 查找下载信息
            DownloadManager downloadManager = EhApplication.getDownloadManager(context);
            DownloadInfo downloadInfo = downloadManager.getDownloadInfo(gid);
            if (downloadInfo == null) {
                JSONObject errorResponse = new JSONObject();
                errorResponse.put("success", false);
                errorResponse.put("error", "Gallery not found in download list");
                errorResponse.put("code", 404);
                return ResponseBuilder.jsonSuccess(errorResponse.toJSONString());
            }

            // 检查是否已有活跃的接力任务
            RelayTask existingTask = relayTaskManager.getTaskByGid(gid);
            if (existingTask != null && !existingTask.isTerminal()) {
                JSONObject errorResponse = new JSONObject();
                errorResponse.put("success", false);
                errorResponse.put("error", "Relay task already exists for gid: " + gid);
                errorResponse.put("code", 409);
                return ResponseBuilder.jsonSuccess(errorResponse.toJSONString());
            }

            // 获取请求参数
            String token = json.getString("token");
            String title = json.getString("title");
            String titleJpn = json.getString("titleJpn");
            String thumb = json.getString("thumb");
            int category = json.getIntValue("category");
            String posted = json.getString("posted");
            String uploader = json.getString("uploader");
            float rating = json.getFloatValue("rating");
            int pages = json.getIntValue("pages");
            String sourceDevice = json.getString("sourceDevice");
            String sourceDeviceId = json.getString("sourceDeviceId");
            String priority = json.getString("priority");
            boolean autoReturn = json.getBooleanValue("autoReturn");

            String remoteIp = session.getRemoteIpAddress();
            int sourcePort = json.getIntValue("sourcePort");
            if (sourcePort <= 0) sourcePort = 8080;

            if (priority == null) priority = RelayTask.PRIORITY_NORMAL;

            // 将画廊设为接力下载状态（停止本机下载）
            downloadManager.setRelayDownload(gid);

            // 创建入站接力任务
            RelayTask task = relayTaskManager.createIncomingTask(
                    gid, token != null ? token : downloadInfo.token,
                    title != null ? title : downloadInfo.title,
                    titleJpn != null ? titleJpn : downloadInfo.titleJpn,
                    thumb != null ? thumb : downloadInfo.thumb,
                    category != 0 ? category : downloadInfo.category,
                    posted, uploader, rating, pages,
                    sourceDevice, sourceDeviceId, remoteIp, sourcePort,
                    priority, autoReturn);

            // 请求方即执行端：直接接受任务，无需等待手动接受，避免出现待接受死等
            relayTaskManager.acceptTask(task.getTaskId(), sourceDevice, sourceDeviceId);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("taskId", task.getTaskId());
            response.put("gid", gid);
            response.put("state", DownloadInfo.STATE_RELAY_DOWNLOAD);
            response.put("stateName", "relay_download");
            response.put("status", task.getStatus());
            response.put("accepted", true);
            response.put("createdTime", task.getCreatedTime());

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to request relay", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 接收推送回传的ZIP文件：执行端将完成的画廊推送到发起端
     * 发起端接收ZIP后解压到下载目录，更新状态为完成
     */
    private NanoHTTPD.Response handleReturnReceive(NanoHTTPD.IHTTPSession session) {
        try {
            // 解析multipart body
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);

            // 获取表单字段
            String taskId = files.get("taskId");
            String gidStr = files.get("gid");

            if (gidStr == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing gid");
            }

            long gid = Long.parseLong(gidStr);

            // 获取上传的文件
            String tmpFilePath = files.get("file");
            if (tmpFilePath == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing file");
            }

            File tmpFile = new File(tmpFilePath);
            if (!tmpFile.exists()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "File not received");
            }

            // 查找下载信息
            DownloadManager downloadManager = EhApplication.getDownloadManager(context);
            DownloadInfo downloadInfo = downloadManager.getDownloadInfo(gid);
            if (downloadInfo == null) {
                tmpFile.delete();
                JSONObject errorResponse = new JSONObject();
                errorResponse.put("success", false);
                errorResponse.put("error", "Gallery not found in download list");
                errorResponse.put("code", 404);
                return ResponseBuilder.jsonSuccess(errorResponse.toJSONString());
            }

            // 获取画廊下载目录
            com.hippo.unifile.UniFile downloadDir = com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir(downloadInfo);
            if (downloadDir == null || !downloadDir.isDirectory()) {
                tmpFile.delete();
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Download directory not found");
            }

            // 解压ZIP到临时目录，然后复制到下载目录
            File extractTmpDir = new File(context.getCacheDir(), "relay_extract_" + gid + "_" + System.currentTimeMillis());
            extractTmpDir.mkdirs();

            boolean extracted = GZIPUtils.UnZipFolder(tmpFile.getAbsolutePath(), extractTmpDir.getAbsolutePath());
            tmpFile.delete();

            if (!extracted) {
                deleteRecursively(extractTmpDir);
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Failed to extract ZIP");
            }

            // 将解压的文件复制到下载目录
            int fileCount = copyFilesToUniDir(extractTmpDir, downloadDir);
            deleteRecursively(extractTmpDir);

            // 更新下载状态为完成
            downloadInfo.state = DownloadInfo.STATE_FINISH;
            downloadInfo.speed = 0;
            downloadInfo.remaining = 0;
            EhDB.putDownloadInfo(downloadInfo);

            // 删除接力任务（如果存在）
            if (taskId != null) {
                relayTaskManager.deleteTask(taskId);
            } else {
                RelayTask relayTask = relayTaskManager.getTaskByGid(gid);
                if (relayTask != null) {
                    relayTaskManager.deleteTask(relayTask.getTaskId());
                }
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("gid", gid);
            response.put("state", DownloadInfo.STATE_FINISH);
            response.put("stateName", "finished");
            response.put("message", "Gallery imported successfully");
            response.put("fileCount", fileCount);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to receive relay return", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 递归删除文件/目录
     */
    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    /**
     * 将源目录中的文件复制到UniFile目标目录
     * 返回复制的文件数
     */
    private int copyFilesToUniDir(File srcDir, com.hippo.unifile.UniFile destDir) throws IOException {
        int count = 0;
        File[] files = srcDir.listFiles();
        if (files == null) return 0;

        for (File srcFile : files) {
            if (srcFile.isFile()) {
                com.hippo.unifile.UniFile destFile = destDir.createFile("application/octet-stream");
                if (destFile != null) {
                    try (InputStream is = new FileInputStream(srcFile);
                         java.io.OutputStream os = destFile.openOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 获取任务列表
     */
    private NanoHTTPD.Response handleGetTasks(NanoHTTPD.IHTTPSession session) {
        try {
            String status = RequestParser.getQueryParameter(session, "status", "all");
            String direction = RequestParser.getQueryParameter(session, "direction", "all");

            List<RelayTask> tasks = relayTaskManager.getTasks(status, direction);

            JSONArray tasksArray = new JSONArray();
            for (RelayTask task : tasks) {
                tasksArray.add(taskToJson(task));
            }

            JSONObject response = new JSONObject();
            response.put("tasks", tasksArray);
            response.put("total", tasksArray.size());

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to get relay tasks", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 获取任务状态
     */
    private NanoHTTPD.Response handleGetTaskStatus(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            RelayTask task = relayTaskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            return ResponseBuilder.jsonSuccess(taskToJson(task).toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to get relay task status", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 接受任务
     */
    private NanoHTTPD.Response handleAcceptTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            RelayTask task = relayTaskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            if (!task.isPending()) {
                // 请求端已通过 /relay/request 时自动接受，重复接受视为幂等成功
                if (task.isAccepted()) {
                    JSONObject alreadyResponse = new JSONObject();
                    alreadyResponse.put("success", true);
                    alreadyResponse.put("message", "Task already accepted");
                    alreadyResponse.put("taskId", taskId);
                    return ResponseBuilder.jsonSuccess(alreadyResponse.toJSONString());
                }
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Task is in state " + task.getStatus() + ", cannot accept");
            }

            // 从请求体读取接受方设备信息
            String acceptedDevice = null;
            String acceptedDeviceId = null;
            try {
                String body = RequestParser.readBody(session);
                if (body != null && !body.isEmpty()) {
                    JSONObject json = JSON.parseObject(body);
                    if (json != null) {
                        acceptedDevice = json.getString("acceptedDevice");
                        acceptedDeviceId = json.getString("acceptedDeviceId");
                    }
                }
            } catch (Exception e) {
                // 请求体为空或解析失败，使用默认值
                TransferLogger.getInstance().d(TAG, "No device info in accept request body");
            }

            // 如果请求体中没有设备信息，尝试从Header获取
            if (acceptedDevice == null) {
                Map<String, String> headers = session.getHeaders();
                acceptedDevice = headers.get("x-device-name");
                acceptedDeviceId = headers.get("x-device-id");
            }

            // 接受任务（带设备信息）
            relayTaskManager.acceptTask(taskId, acceptedDevice, acceptedDeviceId);

            // 开始下载
            relayTaskManager.startDownload(taskId);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Task accepted");
            response.put("taskId", taskId);
            if (acceptedDevice != null) {
                response.put("acceptedDevice", acceptedDevice);
            }

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to accept relay task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 拒绝任务
     */
    private NanoHTTPD.Response handleRejectTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            RelayTask task = relayTaskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            if (!task.isPending()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Task is in state " + task.getStatus() + ", cannot reject");
            }

            relayTaskManager.rejectTask(taskId);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Task rejected");
            response.put("taskId", taskId);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to reject relay task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 取消任务
     */
    private NanoHTTPD.Response handleCancelTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            RelayTask task = relayTaskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            if (!task.isCancellable()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Task is in state " + task.getStatus() + ", cannot cancel");
            }

            relayTaskManager.cancelTask(taskId);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Task cancelled");
            response.put("taskId", taskId);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to cancel relay task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 下载完成的文件
     */
    private NanoHTTPD.Response handleDownload(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            RelayTask task = relayTaskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            if (!task.isReturned() || task.getZipFilePath() == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Task file not ready");
            }

            File zipFile = new File(task.getZipFilePath());
            if (!zipFile.exists()) {
                return ResponseBuilder.notFound("File");
            }

            // 检查断点续传
            String rangeHeader = session.getHeaders().get("range");
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                // 解析Range头
                String range = rangeHeader.substring(6);
                String[] parts = range.split("-");
                long start = Long.parseLong(parts[0]);
                long end = parts.length > 1 && !parts[1].isEmpty() ?
                        Long.parseLong(parts[1]) : zipFile.length() - 1;
                long length = end - start + 1;

                FileInputStream fis = new FileInputStream(zipFile);
                fis.skip(start);

                NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                        "application/zip",
                        fis,
                        length
                );
                response.addHeader("Content-Range",
                        "bytes " + start + "-" + end + "/" + zipFile.length());
                response.addHeader("Content-Disposition",
                        "attachment; filename=\"relay_" + task.getGid() + ".zip\"");

                return response;
            }

            // 完整下载
            FileInputStream fis = new FileInputStream(zipFile);

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/zip",
                    fis,
                    zipFile.length()
            );
            response.addHeader("Content-Disposition",
                    "attachment; filename=\"relay_" + task.getGid() + ".zip\"");

            return response;

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to download relay task file", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 删除任务
     */
    private NanoHTTPD.Response handleDeleteTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            RelayTask task = relayTaskManager.getTask(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            relayTaskManager.deleteTask(taskId);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Task deleted");
            response.put("taskId", taskId);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to delete relay task", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 从路径中提取taskId
     */
    private String extractTaskId(String path, String action) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("relay".equals(parts[i]) && i + 1 < parts.length) {
                if (action == null) {
                    // 没有后续动作，返回下一个部分
                    return parts[i + 1];
                } else if (i + 2 < parts.length && action.equals(parts[i + 2])) {
                    // 有后续动作
                    return parts[i + 1];
                }
            }
        }
        return null;
    }

    /**
     * 任务转JSON
     */
    private JSONObject taskToJson(RelayTask task) {
        JSONObject json = new JSONObject();
        json.put("taskId", task.getTaskId());
        json.put("gid", task.getGid());
        json.put("token", task.getToken());
        json.put("title", task.getTitle());
        json.put("titleJpn", task.getTitleJpn());
        json.put("thumb", task.getThumb());
        json.put("category", task.getCategory());
        json.put("posted", task.getPosted());
        json.put("uploader", task.getUploader());
        json.put("rating", task.getRating());
        json.put("pages", task.getPages());
        json.put("status", task.getStatus());
        json.put("statusName", task.getStatusDisplayName());
        json.put("direction", task.getDirection());
        json.put("directionName", task.getDirectionDisplayName());
        json.put("sourceDevice", task.getSourceDevice());
        json.put("sourceDeviceId", task.getSourceDeviceId());
        json.put("targetDevice", task.getTargetDevice());
        json.put("targetDeviceId", task.getTargetDeviceId());
        json.put("acceptedDevice", task.getAcceptedDevice());
        json.put("acceptedDeviceId", task.getAcceptedDeviceId());
        json.put("priority", task.getPriority());
        json.put("autoReturn", task.isAutoReturn());

        // 进度信息
        json.put("finished", task.getFinished());
        json.put("total", task.getTotal());
        json.put("speed", task.getSpeed());
        json.put("downloadedSize", task.getDownloadedSize());
        json.put("totalSize", task.getTotalSize());
        json.put("progress", task.getProgressPercent());

        // 速度格式化
        json.put("speedFormatted", formatSpeed(task.getSpeed()));

        // 大小格式化
        json.put("downloadedSizeFormatted", formatSize(task.getDownloadedSize()));
        json.put("totalSizeFormatted", formatSize(task.getTotalSize()));

        // 时间戳
        json.put("createdTime", task.getCreatedTime());
        json.put("updatedTime", task.getUpdatedTime());
        json.put("acceptedTime", task.getAcceptedTime());
        json.put("completedTime", task.getCompletedTime());
        json.put("returnedTime", task.getReturnedTime());

        // 时间格式化
        json.put("createdDate", formatTime(task.getCreatedTime()));
        json.put("updatedDate", formatTime(task.getUpdatedTime()));
        json.put("acceptedDate", formatTime(task.getAcceptedTime()));
        json.put("completedDate", formatTime(task.getCompletedTime()));
        json.put("returnedDate", formatTime(task.getReturnedTime()));

        // 文件信息
        json.put("zipFileSize", task.getZipFileSize());
        json.put("zipFileSizeFormatted", formatSize(task.getZipFileSize()));

        // 错误信息
        if (task.getErrorMessage() != null) {
            json.put("errorMessage", task.getErrorMessage());
        }

        return json;
    }

    /**
     * 格式化速度
     */
    private String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond <= 0) return "0 B/s";
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        if (bytesPerSecond < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        if (bytesPerSecond < 1024 * 1024 * 1024) return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024));
        return String.format("%.2f GB/s", bytesPerSecond / (1024.0 * 1024 * 1024));
    }

    /**
     * 格式化大小
     */
    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 格式化时间
     */
    private String formatTime(long time) {
        if (time <= 0) return null;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new Date(time));
    }
}
