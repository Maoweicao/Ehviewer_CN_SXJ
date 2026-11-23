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
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.RelayPageBitmap;
import com.hippo.ehviewer.transfer.core.RelayPoolManager;
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
    private RelayPoolApiHandler poolApiHandler;

    public RelayApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
        this.relayTaskManager = RelayTaskManager.getInstance(context);
        this.poolApiHandler = new RelayPoolApiHandler(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);
        if (!Settings.isRelayAnyEnabled()) {
            return ResponseBuilder.forbidden("Relay is disabled");
        }

        String path = uri.split("\\?")[0];

        // GET /api/v1/relay/pool - 候选池
        if (path.equals("/api/v1/relay/pool")) {
            if (!Settings.isRelayPoolEnabled()) {
                return ResponseBuilder.forbidden("Relay pool is disabled");
            }
            return poolApiHandler.handlePoolList(session, false);
        }

        // GET /api/v1/relay/pool/summary - 池摘要
        if (path.equals("/api/v1/relay/pool/summary")) {
            if (!Settings.isRelayPoolEnabled()) {
                return ResponseBuilder.forbidden("Relay pool is disabled");
            }
            return poolApiHandler.handlePoolSummary(session, false);
        }

        // GET /api/v1/relay/{taskId}/plan - 执行计划
        if (path.matches("/api/v1/relay/[^/]+/plan")) {
            String taskId = extractTaskId(path, "plan");
            return poolApiHandler.handlePlan(session, taskId, null, false);
        }

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
        if (!Settings.isRelayAnyEnabled()) {
            return ResponseBuilder.forbidden("Relay is disabled");
        }

        String path = uri.split("\\?")[0];

        // POST /api/v1/relay/pool/claim - 原子认领
        if (path.equals("/api/v1/relay/pool/claim")) {
            if (!Settings.isRelayPoolEnabled()) {
                return ResponseBuilder.forbidden("Relay pool is disabled");
            }
            return poolApiHandler.handleClaim(session, false);
        }

        // POST /api/v1/relay/return/partial - 逐页回传
        if (path.equals("/api/v1/relay/return/partial")) {
            if (!Settings.isRelayPoolEnabled() && !Settings.isAuxDownloadEnabled()) {
                return ResponseBuilder.forbidden("Relay return is disabled");
            }
            return poolApiHandler.handlePartialReturn(session, false);
        }

        // POST /api/v1/relay/{taskId}/lease - 续租
        if (path.matches("/api/v1/relay/[^/]+/lease")) {
            String taskId = extractTaskId(path, "lease");
            return poolApiHandler.handleLease(session, taskId);
        }

        // POST /api/v1/relay/{taskId}/release - 释放回池
        if (path.matches("/api/v1/relay/[^/]+/release")) {
            String taskId = extractTaskId(path, "release");
            return poolApiHandler.handleRelease(session, taskId);
        }

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
        if (!Settings.isRelayAnyEnabled()) {
            return ResponseBuilder.forbidden("Relay is disabled");
        }

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
            boolean missingOnly = !json.containsKey("missingOnly")
                    || json.getBooleanValue("missingOnly");
            String basePagesBitmap = json.getString("basePagesBitmap");
            int expectedTotalPages = json.getIntValue("expectedTotalPages");
            int leaseSeconds = RelayPoolManager.clampLeaseSeconds(json.getIntValue("leaseSeconds"));

            String remoteIp = session.getRemoteIpAddress();
            int sourcePort = json.getIntValue("sourcePort");
            if (sourcePort <= 0) sourcePort = 8080;

            if (priority == null) priority = RelayTask.PRIORITY_NORMAL;

            // §5.17.7 断言：页数/位图不一致直接 412，禁止写入
            DownloadManager createDm = EhApplication.getDownloadManager(context);
            RelayPoolManager createPool = RelayPoolManager.getInstance(context);
            RelayPoolManager.PageFacts createFacts = createPool.scan(createDm.getDownloadInfo(gid));
            if (expectedTotalPages > 0 && createFacts.totalPages > 0
                    && expectedTotalPages != createFacts.totalPages) {
                JSONObject err = RelayPoolManager.error("page_count_mismatch", 412);
                err.put("expectedTotalPages", expectedTotalPages);
                err.put("actualTotalPages", createFacts.totalPages);
                return SafeResponse.json(412, err.toJSONString());
            }
            if (basePagesBitmap != null && !basePagesBitmap.isEmpty()
                    && createFacts.totalPages > 0) {
                String actual = RelayPageBitmap.encode(createFacts.downloaded,
                        createFacts.totalPages);
                if (!RelayPoolManager.bitmapEquals(basePagesBitmap, actual)) {
                    JSONObject err = RelayPoolManager.error("bitmap_mismatch", 412);
                    err.put("expectedBitmap", actual);
                    return SafeResponse.json(412, err.toJSONString());
                }
            }

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
            } else if (shouldAutoAccept(autoReturn, isAlreadyCompleted)) {
                // 发起端（手机）明确要求“自动回传”，说明是用户主动点“接力到设备”，
                // 接收端直接接手并开始下载，避免再去接力中心手动点“接受”。
                String selfName = com.hippo.ehviewer.lab.LabManager.getInstance(context)
                        .getSelfDeviceName();
                String selfId = com.hippo.ehviewer.lab.LabManager.getInstance(context)
                        .getSelfDeviceId();
                relayTaskManager.acceptTask(task.getTaskId(), selfName, selfId);
                relayTaskManager.startDownload(task.getTaskId());
                TransferLogger.getInstance().i(TAG,
                        "Auto-accepted relay task " + task.getTaskId() + " for gid " + gid);
                final String acceptedTitle = title != null ? title : String.valueOf(gid);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.relay_auto_accepted, acceptedTitle),
                                android.widget.Toast.LENGTH_LONG).show());
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("taskId", task.getTaskId());
            response.put("gid", gid);
            response.put("status", task.getStatus());
            response.put("createdTime", task.getCreatedTime());
            int totalPages = createFacts.totalPages > 0 ? createFacts.totalPages : pages;
            String bitmap = totalPages > 0
                    ? RelayPageBitmap.encode(createFacts.downloaded, totalPages) : null;
            task.setMissingOnly(missingOnly);
            task.setBasePagesBitmap(basePagesBitmap);
            task.setPagesBitmap(bitmap);
            task.setMissingCount(totalPages > 0
                    ? Math.max(0, totalPages - createFacts.downloadedCount) : 0);
            long leaseExpiresAt = System.currentTimeMillis() + leaseSeconds * 1000L;
            task.setLeaseExpiresAt(leaseExpiresAt);
            if (targetDeviceId != null && !targetDeviceId.isEmpty()) {
                task.setLeaseDeviceId(targetDeviceId);
                task.setLeaseDeviceName(targetDevice);
            } else if (task.getAcceptedDeviceId() != null) {
                task.setLeaseDeviceId(task.getAcceptedDeviceId());
                task.setLeaseDeviceName(task.getAcceptedDevice());
            }
            relayTaskManager.persist();
            response.put("missingCount", task.getMissingCount());
            response.put("pagesBitmap", bitmap);
            response.put("leaseExpiresAt", leaseExpiresAt);
            if (isAlreadyCompleted) {
                response.put("alreadyCompleted", true);
                response.put("message", "Gallery already completed on this device, packaging for return...");
            } else if (autoReturn) {
                response.put("autoAccepted", true);
                response.put("message", "Relay task accepted; download started");
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
            boolean missingOnly = !json.containsKey("missingOnly")
                    || json.getBooleanValue("missingOnly");
            int leaseSeconds = RelayPoolManager.clampLeaseSeconds(json.getIntValue("leaseSeconds"));

            if (priority == null) priority = RelayTask.PRIORITY_NORMAL;

            String remoteIp = session.getRemoteIpAddress();
            int sourcePort = json.getIntValue("sourcePort");
            if (sourcePort <= 0) sourcePort = 8080;

            DownloadManager downloadManager = EhApplication.getDownloadManager(context);

            JSONArray tasksArray = new JSONArray();
            JSONArray errorsArray = new JSONArray();
            RelayPoolManager pool = RelayPoolManager.getInstance(context);
            for (int i = 0; i < gidsArray.size(); i++) {
                long gid = gidsArray.getLong(i);

                // 检查是否已有相同的接力任务
                RelayTask existingTask = relayTaskManager.getTaskByGid(gid);
                if (existingTask != null && !existingTask.isTerminal()) {
                    JSONObject err = new JSONObject();
                    err.put("gid", gid);
                    err.put("error", "already_exists");
                    err.put("code", 409);
                    errorsArray.add(err);
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

                RelayPoolManager.PageFacts facts = pool.scan(downloadManager.getDownloadInfo(gid));
                String bitmap = facts.totalPages > 0
                        ? RelayPageBitmap.encode(facts.downloaded, facts.totalPages) : null;
                long leaseExpiresAt = System.currentTimeMillis() + leaseSeconds * 1000L;
                task.setMissingOnly(missingOnly);
                task.setMissingCount(facts.missingPages.size());
                task.setPagesBitmap(bitmap);
                task.setLeaseExpiresAt(leaseExpiresAt);
                relayTaskManager.persist();

                JSONObject taskJson = new JSONObject();
                taskJson.put("taskId", task.getTaskId());
                taskJson.put("gid", gid);
                taskJson.put("status", task.getStatus());
                taskJson.put("missingCount", task.getMissingCount());
                taskJson.put("pagesBitmap", bitmap);
                taskJson.put("leaseExpiresAt", leaseExpiresAt);
                tasksArray.add(taskJson);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("tasks", tasksArray);
            if (!errorsArray.isEmpty()) {
                response.put("errors", errorsArray);
            }
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
            boolean missingOnly = !json.containsKey("missingOnly")
                    || json.getBooleanValue("missingOnly");
            String basePagesBitmap = json.getString("basePagesBitmap");
            int leaseSeconds = RelayPoolManager.clampLeaseSeconds(json.getIntValue("leaseSeconds"));

            String remoteIp = session.getRemoteIpAddress();
            int sourcePort = json.getIntValue("sourcePort");
            if (sourcePort <= 0) sourcePort = 8080;

            if (priority == null) priority = RelayTask.PRIORITY_NORMAL;

            // 位图断言（§5.17.9）
            RelayPoolManager requestPool = RelayPoolManager.getInstance(context);
            RelayPoolManager.PageFacts requestFacts = requestPool.scan(downloadInfo);
            if (basePagesBitmap != null && !basePagesBitmap.isEmpty()
                    && requestFacts.totalPages > 0) {
                String actual = RelayPageBitmap.encode(requestFacts.downloaded,
                        requestFacts.totalPages);
                if (!RelayPoolManager.bitmapEquals(basePagesBitmap, actual)) {
                    JSONObject err = RelayPoolManager.error("bitmap_mismatch", 412);
                    err.put("expectedBitmap", actual);
                    return SafeResponse.json(412, err.toJSONString());
                }
            }

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
            long leaseExpiresAt = System.currentTimeMillis() + leaseSeconds * 1000L;
            JSONArray missingArr = new JSONArray();
            for (Integer page : requestFacts.missingPages) missingArr.add(page);
            task.setMissingOnly(missingOnly);
            task.setMissingCount(requestFacts.missingPages.size());
            task.setPagesBitmap(RelayPageBitmap.encode(requestFacts.downloaded,
                    requestFacts.totalPages));
            task.setLeaseExpiresAt(leaseExpiresAt);
            task.setLeaseDeviceId(sourceDeviceId);
            task.setLeaseDeviceName(sourceDevice);
            relayTaskManager.persist();
            response.put("missingPages", missingArr);
            response.put("missingCount", task.getMissingCount());
            response.put("pagesBitmap", task.getPagesBitmap());
            response.put("leaseExpiresAt", leaseExpiresAt);
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
            String mode = files.get("mode");
            if (mode == null || mode.isEmpty()) mode = "merge";

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
            boolean replace = "replace".equalsIgnoreCase(mode);
            File backupDir = null;
            if (replace) {
                backupDir = new File(context.getCacheDir(),
                        "relay_backup_" + gid + "_" + System.currentTimeMillis());
                backupDir.mkdirs();
                backupExistingFiles(downloadDir, backupDir);
            }
            int fileCount = copyFilesToUniDir(extractTmpDir, downloadDir, replace);
            deleteRecursively(extractTmpDir);
            if (replace && backupDir != null) {
                // 替换成功即可丢弃备份；失败时保留以便人工恢复
                deleteRecursively(backupDir);
            }

            // 更新下载状态：交由统一收尾流水线（完整校验/元数据/事件）
            RelayPoolManager poolManager = RelayPoolManager.getInstance(context);
            RelayPoolManager.PageFacts facts = poolManager.scan(downloadInfo);
            poolManager.reconcile(downloadInfo, facts);
            JSONObject finish = null;
            if (facts.totalPages > 0 && facts.missingPages.isEmpty()) {
                finish = poolManager.finalizeGallery(gid, taskId, facts);
            } else {
                downloadInfo.state = DownloadInfo.STATE_RELAY_DOWNLOAD;
                EhDB.putDownloadInfo(downloadInfo);
            }

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
            response.put("mode", mode);
            response.put("state", finish != null ? DownloadInfo.STATE_FINISH
                    : downloadInfo.state);
            response.put("stateName", finish != null ? "finished" : "relay_download");
            response.put("message", "Gallery imported successfully");
            response.put("fileCount", fileCount);
            response.put("finish", finish);

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
        return copyFilesToUniDir(srcDir, destDir, true);
    }

    /**
     * 将源目录中的文件复制到UniFile目标目录。
     *
     * @param overwrite false 时（merge 默认）不覆盖主机已有页
     * @return 实际写入的文件数
     */
    private int copyFilesToUniDir(File srcDir, com.hippo.unifile.UniFile destDir,
                                  boolean overwrite) throws IOException {
        int count = 0;
        File[] files = srcDir.listFiles();
        if (files == null) return 0;

        for (File srcFile : files) {
            if (srcFile.isFile()) {
                // 必须保留原始文件名（页码命名），否则回传的图片无法被画廊识别。
                // 同名旧文件先删除，避免 SAF 生成 “page (1).jpg” 这类重复文件。
                com.hippo.unifile.UniFile existing = destDir.findFile(srcFile.getName());
                if (existing != null) {
                    if (!overwrite) {
                        continue;
                    }
                    existing.delete();
                }
                com.hippo.unifile.UniFile destFile = destDir.createFile(srcFile.getName());
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

    /** replace 模式先把现有页复制到缓存备份目录。 */
    private void backupExistingFiles(com.hippo.unifile.UniFile dir, File backupDir) {
        try {
            com.hippo.unifile.UniFile[] files = dir.listFiles();
            if (files == null) return;
            for (com.hippo.unifile.UniFile file : files) {
                if (file == null || !file.isFile()) continue;
                File target = new File(backupDir, file.getName());
                try (InputStream is = file.openInputStream();
                     java.io.OutputStream os = new java.io.FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        os.write(buffer, 0, len);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 获取任务列表
     */
    private NanoHTTPD.Response handleGetTasks(NanoHTTPD.IHTTPSession session) {
        try {
            String status = RequestParser.getQueryParameter(session, "status", "all");
            String direction = RequestParser.getQueryParameter(session, "direction", "all");
            String includePages = RequestParser.getQueryParameter(session, "includePages", "bitmap");
            long gidFilter = RequestParser.getLongQueryParameter(session, "gid", 0L);
            int limit = RequestParser.getIntQueryParameter(session, "limit", 50);
            if (limit <= 0) limit = 50;
            if (limit > 200) limit = 200;

            List<RelayTask> tasks = relayTaskManager.getTasks(status, direction);
            JSONArray tasksArray = new JSONArray();
            for (RelayTask task : tasks) {
                if (gidFilter > 0 && task.getGid() != gidFilter) {
                    continue;
                }
                if (tasksArray.size() >= limit) {
                    break;
                }
                tasksArray.add(taskToJson(task, includePages));
            }

            JSONObject response = new JSONObject();
            response.put("tasks", tasksArray);
            response.put("total", tasksArray.size());
            response.put("hasMore", tasks.size() > tasksArray.size());
            response.put("nextCursor", null);

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
            String includePages = RequestParser.getQueryParameter(session, "includePages", "bitmap");
            return ResponseBuilder.jsonSuccess(taskToJson(task, includePages).toJSONString());

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
            int leaseSeconds = 0;
            try {
                String body = RequestParser.readBody(session);
                if (body != null && !body.isEmpty()) {
                    JSONObject json = JSON.parseObject(body);
                    if (json != null) {
                        acceptedDevice = json.getString("acceptedDevice");
                        acceptedDeviceId = json.getString("acceptedDeviceId");
                        leaseSeconds = json.getIntValue("leaseSeconds");
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

            int lease = RelayPoolManager.clampLeaseSeconds(leaseSeconds);
            long leaseExpiresAt = System.currentTimeMillis() + lease * 1000L;
            task.setLeaseExpiresAt(leaseExpiresAt);
            task.setLeaseDeviceId(acceptedDeviceId);
            task.setLeaseDeviceName(acceptedDevice);
            RelayPoolManager.getInstance(context).bumpPoolVersion(task.getGid(), "accepted");

            // 开始下载
            relayTaskManager.startDownload(taskId);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Task accepted");
            response.put("taskId", taskId);
            if (acceptedDevice != null) {
                response.put("acceptedDevice", acceptedDevice);
            }
            response.put("leaseExpiresAt", leaseExpiresAt);
            response.put("missingCount", task.getMissingCount());

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
     * 接收端是否应自动接手：只有发起端主动点“接力到设备”（autoReturn=true）
     * 且画廊尚未完成时才自动接受并开始下载。
     */
    static boolean shouldAutoAccept(boolean autoReturn, boolean alreadyCompleted) {
        return autoReturn && !alreadyCompleted;
    }

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
        return taskToJson(task, "bitmap");
    }

    private JSONObject taskToJson(RelayTask task, String includePages) {
        return RelayPoolApiHandler.taskJson(context, task,
                "all".equals(includePages) || "list".equals(includePages));
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
