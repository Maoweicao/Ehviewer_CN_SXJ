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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.DeleteTaskExecutor;
import com.hippo.ehviewer.transfer.data.UnifiedTask;
import com.hippo.ehviewer.transfer.core.ResponseCache;
import com.hippo.lib.yorozuya.collect.LongList;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

/**
 * 下载任务管理API处理器
 *
 * 端点:
 * - GET    /api/v1/downloads              获取下载任务列表
 * - GET    /api/v1/downloads/{gid}        获取单个下载任务详情
 * - POST   /api/v1/downloads              创建新下载任务
 * - POST   /api/v1/downloads/{gid}/start  开始/恢复下载
 * - POST   /api/v1/downloads/{gid}/pause  暂停下载
 * - DELETE /api/v1/downloads/{gid}        删除下载任务
 * - POST   /api/v1/downloads/batch/start  批量开始下载
 * - POST   /api/v1/downloads/batch/pause  批量暂停下载
 * - DELETE /api/v1/downloads/batch        批量删除下载任务
 */
public class DownloadApiHandler extends BaseApiHandler {

    private static final String TAG = "DownloadApiHandler";

    // 状态名称映射
    private static final String[] STATE_NAMES = {
        "none",      // STATE_NONE = 0
        "wait",      // STATE_WAIT = 1
        "downloading", // STATE_DOWNLOAD = 2
        "finished",  // STATE_FINISH = 3
        "failed",    // STATE_FAILED = 4
        "update"     // STATE_UPDATE = 5
    };

    public DownloadApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);

        String path = uri.split("\\?")[0];

        // GET /api/v1/downloads - 列表
        if (path.equals("/api/v1/downloads") || uri.startsWith("/api/v1/downloads?")) {
            return handleGetDownloads(session);
        }

        // GET /api/v1/downloads/{gid} - 单个详情
        if (path.matches("/api/v1/downloads/\\d+")) {
            long gid = extractGidFromPath(path);
            return handleGetDownload(session, gid);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri);

        String path = uri.split("\\?")[0];

        // POST /api/v1/downloads - 创建下载任务
        if (path.equals("/api/v1/downloads")) {
            return handleCreateDownload(session);
        }

        // POST /api/v1/downloads/{gid}/start - 开始下载
        if (path.matches("/api/v1/downloads/\\d+/start")) {
            long gid = extractGidFromAction(path, "start");
            return handleStartDownload(session, gid);
        }

        // POST /api/v1/downloads/{gid}/pause - 暂停下载
        if (path.matches("/api/v1/downloads/\\d+/pause")) {
            long gid = extractGidFromAction(path, "pause");
            return handlePauseDownload(session, gid);
        }

        // POST /api/v1/downloads/batch/start - 批量开始
        if (path.equals("/api/v1/downloads/batch/start")) {
            return handleBatchStart(session);
        }

        // POST /api/v1/downloads/batch/pause - 批量暂停
        if (path.equals("/api/v1/downloads/batch/pause")) {
            return handleBatchPause(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handleDelete(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("DELETE", uri);

        String path = uri.split("\\?")[0];

        // DELETE /api/v1/downloads/{gid} - 删除单个
        if (path.matches("/api/v1/downloads/\\d+")) {
            long gid = extractGidFromPath(path);
            return handleDeleteDownload(session, gid);
        }

        // DELETE /api/v1/downloads/batch - 批量删除
        if (path.equals("/api/v1/downloads/batch")) {
            return handleBatchDelete(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    // ==================== 业务逻辑 ====================

    /**
     * 获取下载任务列表
     */
    private NanoHTTPD.Response handleGetDownloads(NanoHTTPD.IHTTPSession session) {
        try {
            int page = RequestParser.getIntQueryParameter(session, "page", 1);
            int limit = normalizeLimit(RequestParser.getIntQueryParameter(session, "limit", 30));
            String stateFilter = RequestParser.getQueryParameter(session, "state", "all");
            String label = RequestParser.getQueryParameter(session, "label");
            String search = RequestParser.getQueryParameter(session, "search");

            List<DownloadInfo> allList;
            if ("默认".equals(label) || "default".equals(label)) {
                allList = downloadManager.getDefaultDownloadInfoList();
            } else if (label != null && !label.isEmpty()) {
                allList = downloadManager.getLabelDownloadInfoList(label);
                if (allList == null) {
                    allList = new ArrayList<>();
                }
            } else {
                allList = downloadManager.getAllDownloadInfoList();
            }

            // 状态筛选
            if (!"all".equals(stateFilter)) {
                int targetState = parseState(stateFilter);
                if (targetState >= 0) {
                    List<DownloadInfo> filtered = new ArrayList<>();
                    for (DownloadInfo info : allList) {
                        if (info.state == targetState) {
                            filtered.add(info);
                        }
                    }
                    allList = filtered;
                }
            }

            // 搜索筛选
            if (search != null && !search.isEmpty()) {
                String searchLower = search.toLowerCase();
                List<DownloadInfo> filtered = new ArrayList<>();
                for (DownloadInfo info : allList) {
                    if ((info.title != null && info.title.toLowerCase().contains(searchLower)) ||
                        (info.titleJpn != null && info.titleJpn.toLowerCase().contains(searchLower)) ||
                        (info.uploader != null && info.uploader.toLowerCase().contains(searchLower))) {
                        filtered.add(info);
                    }
                }
                allList = filtered;
            }

            // 按时间倒序排序
            allList.sort((a, b) -> Long.compare(b.time, a.time));

            // 分页
            int total = allList.size();
            int startIndex = (page - 1) * limit;
            int endIndex = Math.min(startIndex + limit, total);

            if (startIndex >= total) {
                startIndex = 0;
                endIndex = 0;
            }

            List<DownloadInfo> pageList = allList.subList(startIndex, endIndex);

            // 构建响应
            StringBuilder sb = new StringBuilder();
            sb.append("{\"total\":").append(total);
            sb.append(",\"page\":").append(page);
            sb.append(",\"limit\":").append(limit);
            sb.append(",\"downloads\":[");

            boolean first = true;
            for (DownloadInfo info : pageList) {
                if (!first) sb.append(",");
                first = false;
                sb.append(formatDownloadJson(info, false));
            }

            sb.append("]}");

            return ResponseBuilder.jsonSuccess(sb.toString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to get downloads", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 获取单个下载任务详情
     */
    private NanoHTTPD.Response handleGetDownload(NanoHTTPD.IHTTPSession session, long gid) {
        try {
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (info == null) {
                return ResponseBuilder.notFound("Download");
            }

            return ResponseBuilder.jsonSuccess(formatDownloadJson(info, true));

        } catch (Exception e) {
            Log.e(TAG, "Failed to get download", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 创建新下载任务
     */
    private NanoHTTPD.Response handleCreateDownload(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);

            long gid = json.getLongValue("gid");
            if (gid <= 0) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid gid");
            }

            // 检查是否已存在
            if (downloadManager.containDownloadInfo(gid)) {
                JSONObject errorResponse = new JSONObject();
                errorResponse.put("success", false);
                errorResponse.put("error", "Download already exists");
                errorResponse.put("code", 409);
                return ResponseBuilder.jsonSuccess(errorResponse.toJSONString());
            }

            // 构建 GalleryInfo
            GalleryInfo info = new GalleryInfo();
            info.gid = gid;
            info.token = json.getString("token");
            info.title = json.getString("title");
            info.titleJpn = json.getString("titleJpn");
            info.thumb = json.getString("thumb");
            info.category = json.getIntValue("category");
            info.posted = json.getString("posted");
            info.uploader = json.getString("uploader");
            info.rating = json.getFloatValue("rating");
            info.pages = json.getIntValue("pages");

            String label = json.getString("label");
            boolean startImmediately = json.getBooleanValue("startImmediately");
            int state = startImmediately ? DownloadInfo.STATE_WAIT : DownloadInfo.STATE_NONE;

            // 在主线程执行添加
            runOnUiThreadSync(() -> {
                downloadManager.addDownload(info, label, state);
                // 如果需要立即开始，通过DownloadService启动
                if (startImmediately) {
                    LongList gidList = new LongList(1);
                    gidList.add(gid);
                    DownloadService.startRangeDownload(context, gidList);
                }
            });

            // 清除缓存
            ResponseCache.getInstance().invalidateGalleries();

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Download added");
            response.put("gid", gid);
            response.put("state", state == DownloadInfo.STATE_WAIT ? "wait" : "none");

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to create download", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 开始/恢复下载
     */
    private NanoHTTPD.Response handleStartDownload(NanoHTTPD.IHTTPSession session, long gid) {
        try {
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (info == null) {
                return ResponseBuilder.notFound("Download");
            }

            // 只有 NONE, FAILED, FINISH 状态可以开始
            if (info.state != DownloadInfo.STATE_NONE &&
                info.state != DownloadInfo.STATE_FAILED &&
                info.state != DownloadInfo.STATE_FINISH) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Download is in state " + getStateName(info.state) + ", cannot start");
            }

            LongList gidList = new LongList(1);
            gidList.add(gid);
            runOnUiThreadSync(() -> DownloadService.startRangeDownload(context, gidList));

            // 重新获取状态
            info = downloadManager.getDownloadInfo(gid);
            String stateName = info != null ? getStateName(info.state) : "unknown";

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Download started");
            response.put("gid", gid);
            response.put("state", stateName);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to start download", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 暂停下载
     */
    private NanoHTTPD.Response handlePauseDownload(NanoHTTPD.IHTTPSession session, long gid) {
        try {
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (info == null) {
                return ResponseBuilder.notFound("Download");
            }

            // 只有 WAIT, DOWNLOAD 状态可以暂停
            if (info.state != DownloadInfo.STATE_WAIT &&
                info.state != DownloadInfo.STATE_DOWNLOAD) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Download is in state " + getStateName(info.state) + ", cannot pause");
            }

            runOnUiThreadSync(() -> downloadManager.stopDownload(gid));

            // 重新获取状态
            info = downloadManager.getDownloadInfo(gid);
            String stateName = info != null ? getStateName(info.state) : "unknown";

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Download paused");
            response.put("gid", gid);
            response.put("state", stateName);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to pause download", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 删除下载任务
     */
    private NanoHTTPD.Response handleDeleteDownload(NanoHTTPD.IHTTPSession session, long gid) {
        try {
            if (!Settings.isRemoteDeleteEnabled()) {
                return ResponseBuilder.forbidden("Remote delete is disabled");
            }

            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (info == null) {
                return ResponseBuilder.notFound("Download");
            }

            UnifiedTask task = DeleteTaskExecutor.getInstance().submitDownloadDelete(
                    context, java.util.Collections.singletonList(gid));
            return ResponseBuilder.accepted(deleteTaskJson(task));

        } catch (Exception e) {
            Log.e(TAG, "Failed to delete download", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 批量开始下载
     */
    private NanoHTTPD.Response handleBatchStart(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);
            JSONArray gidsArray = json.getJSONArray("gids");

            if (gidsArray == null || gidsArray.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "No GIDs provided");
            }

            LongList gidList = new LongList(gidsArray.size());
            for (int i = 0; i < gidsArray.size(); i++) {
                gidList.add(gidsArray.getLong(i));
            }

            runOnUiThreadSync(() -> DownloadService.startRangeDownload(context, gidList));

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Batch start completed");
            response.put("count", gidsArray.size());

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to batch start downloads", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 批量暂停下载
     */
    private NanoHTTPD.Response handleBatchPause(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);
            JSONArray gidsArray = json.getJSONArray("gids");

            if (gidsArray == null || gidsArray.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "No GIDs provided");
            }

            LongList gidList = new LongList(gidsArray.size());
            for (int i = 0; i < gidsArray.size(); i++) {
                gidList.add(gidsArray.getLong(i));
            }

            runOnUiThreadSync(() -> downloadManager.stopRangeDownload(gidList));

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Batch pause completed");
            response.put("count", gidsArray.size());

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to batch pause downloads", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 批量删除下载任务
     */
    private NanoHTTPD.Response handleBatchDelete(NanoHTTPD.IHTTPSession session) {
        try {
            if (!Settings.isRemoteDeleteEnabled()) {
                return ResponseBuilder.forbidden("Remote delete is disabled");
            }

            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);
            JSONArray gidsArray = json.getJSONArray("gids");

            if (gidsArray == null || gidsArray.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "No GIDs provided");
            }

            java.util.List<Long> gids = new java.util.ArrayList<>();
            for (int i = 0; i < gidsArray.size(); i++) {
                gids.add(gidsArray.getLong(i));
            }
            UnifiedTask task = DeleteTaskExecutor.getInstance().submitDownloadDelete(context, gids);
            return ResponseBuilder.accepted(deleteTaskJson(task));

        } catch (Exception e) {
            Log.e(TAG, "Failed to batch delete downloads", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    private String deleteTaskJson(UnifiedTask task) {
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("accepted", true);
        response.put("taskId", task.taskId);
        response.put("status", task.status);
        response.put("total", task.total);
        return response.toJSONString();
    }

    // ==================== 工具方法 ====================

    /**
     * 格式化下载任务JSON
     */
    private String formatDownloadJson(DownloadInfo info, boolean includeDetails) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"gid\":").append(info.gid);
        sb.append(",\"token\":\"").append(escapeJson(info.token)).append("\"");
        sb.append(",\"title\":\"").append(escapeJson(info.title)).append("\"");

        if (info.titleJpn != null) {
            sb.append(",\"titleJpn\":\"").append(escapeJson(info.titleJpn)).append("\"");
        }

        if (info.thumb != null) {
            sb.append(",\"thumb\":\"").append(escapeJson(info.thumb)).append("\"");
        }

        sb.append(",\"category\":").append(info.category);

        if (info.posted != null) {
            sb.append(",\"posted\":\"").append(escapeJson(info.posted)).append("\"");
        }
        if (info.uploader != null) {
            sb.append(",\"uploader\":\"").append(escapeJson(info.uploader)).append("\"");
        }

        sb.append(",\"rating\":").append(info.rating);

        if (info.simpleLanguage != null) {
            sb.append(",\"language\":\"").append(escapeJson(info.simpleLanguage)).append("\"");
        }

        sb.append(",\"pages\":").append(info.pages);
        sb.append(",\"state\":").append(info.state);
        sb.append(",\"stateName\":\"").append(getStateName(info.state)).append("\"");

        if (info.label != null) {
            sb.append(",\"label\":\"").append(escapeJson(info.label)).append("\"");
        }

        sb.append(",\"time\":").append(info.time);
        sb.append(",\"createdTime\":").append(info.time);
        sb.append(",\"createdDate\":\"").append(formatTime(info.time)).append("\"");

        // 进度信息
        sb.append(",\"finished\":").append(info.finished);
        sb.append(",\"total\":").append(info.total);
        sb.append(",\"downloaded\":").append(info.downloaded);

        // 速度和剩余时间（仅下载中有效）
        if (info.state == DownloadInfo.STATE_DOWNLOAD) {
            sb.append(",\"speed\":").append(info.speed);
            sb.append(",\"speedFormatted\":\"").append(formatSpeed(info.speed)).append("\"");
            sb.append(",\"remaining\":").append(info.remaining);
        }

        // 进度百分比
        float progress = 0;
        if (info.total > 0) {
            progress = (float) info.finished / info.total * 100;
        } else if (info.pages > 0) {
            progress = (float) info.finished / info.pages * 100;
        }
        sb.append(",\"progress\":").append(String.format("%.1f", progress));

        if (includeDetails) {
            sb.append(",\"legacy\":").append(info.legacy);
            sb.append(",\"fileSize\":").append(info.fileSize);

            if (info.simpleTags != null) {
                sb.append(",\"simpleTags\":[");
                boolean firstTag = true;
                for (String tag : info.simpleTags) {
                    if (!firstTag) sb.append(",");
                    firstTag = false;
                    sb.append("\"").append(escapeJson(tag)).append("\"");
                }
                sb.append("]");
            }
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * 获取状态名称
     */
    private String getStateName(int state) {
        if (state >= 0 && state < STATE_NAMES.length) {
            return STATE_NAMES[state];
        }
        return "unknown";
    }

    /**
     * 解析状态名称
     */
    private int parseState(String stateName) {
        if (stateName == null) return -1;
        switch (stateName.toLowerCase()) {
            case "none": return DownloadInfo.STATE_NONE;
            case "wait": return DownloadInfo.STATE_WAIT;
            case "downloading": return DownloadInfo.STATE_DOWNLOAD;
            case "finished": return DownloadInfo.STATE_FINISH;
            case "failed": return DownloadInfo.STATE_FAILED;
            case "update": return DownloadInfo.STATE_UPDATE;
            default: return -1;
        }
    }

    /**
     * 从路径中提取GID
     */
    private long extractGidFromPath(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("downloads".equals(parts[i]) && i + 1 < parts.length) {
                try {
                    return Long.parseLong(parts[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    /**
     * 从带动作的路径中提取GID
     * 格式: /api/v1/downloads/{gid}/start
     */
    private long extractGidFromAction(String path, String action) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 2; i++) {
            if ("downloads".equals(parts[i]) && action.equals(parts[i + 2])) {
                try {
                    return Long.parseLong(parts[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    /**
     * 标准化分页大小
     */
    private int normalizeLimit(int limit) {
        if (limit <= 0) return 30;
        if (limit > 500) return 500;
        return limit;
    }

    /**
     * 格式化时间
     */
    private String formatTime(long time) {
        if (time <= 0) return "";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new Date(time));
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
     * JSON转义
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    /**
     * 在主线程同步执行任务（阻塞当前线程直到完成）
     */
    private static void runOnUiThreadSync(Runnable task) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
        } else {
            final CountDownLatch latch = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
