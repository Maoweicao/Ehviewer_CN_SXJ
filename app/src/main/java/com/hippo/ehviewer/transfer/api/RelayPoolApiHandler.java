/*
 * Copyright 2026 EhViewer Contributors
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

import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.RelayPageBitmap;
import com.hippo.ehviewer.transfer.core.RelayPoolManager;
import com.hippo.ehviewer.transfer.data.RelayTask;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.unifile.UniFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

import fi.iki.elonen.NanoHTTPD;

/**
 * 接力下载 v2 池/计划/逐页回传处理器（§5.17.3 ~ §5.17.13）。
 *
 * <p>由 {@link RelayApiHandler} 与 {@link AuxDownloadApiHandler} 共用，保证新旧路径
 * 使用同一套实现。
 */
public class RelayPoolApiHandler extends BaseApiHandler {

    private static final String TAG = "RelayPoolApiHandler";
    private static final int MIN_COMPRESS_BYTES = 4096;

    /** ETag -> gzip 快照（池版本变化后会自然失效）。 */
    private static final Map<String, byte[]> GZIP_CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, byte[]>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 4;
                }
            });

    private final RelayPoolManager poolManager;

    public RelayPoolApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
        this.poolManager = RelayPoolManager.getInstance(context);
    }

    // ==================== GET ====================

    public NanoHTTPD.Response handlePoolList(NanoHTTPD.IHTTPSession session, boolean alias) {
        try {
            RelayPoolManager.PoolQuery q = parsePoolQuery(session, alias);
            String etag = poolManager.etag(q);
            String ifNoneMatch = session.getHeaders().get("if-none-match");
            if (ifNoneMatch != null && ifNoneMatch.trim().equals(etag)) {
                NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.NOT_MODIFIED, "application/json", "");
                response.addHeader("ETag", etag);
                response.addHeader("Vary", "Accept-Encoding");
                return response;
            }

            String compress = q.compress == null ? "none" : q.compress.toLowerCase(Locale.ROOT);
            String accept = session.getHeaders().get("accept-encoding");
            boolean acceptGzip = accept != null && accept.toLowerCase(Locale.ROOT).contains("gzip");
            boolean httpGzip = ("gzip".equals(compress) || acceptGzip)
                    && !"none".equals(compress) && !"gzip-b64".equals(compress);
            if (alias) {
                // 别名层必须携带 deprecated/aliasOf；HTTP 层压缩体无法再追加字段
                httpGzip = false;
            }

            if ("gzip-b64".equals(compress)) {
                return buildGzipBase64Response(q, etag, alias);
            }
            if (httpGzip) {
                byte[] gzip = buildGzip(q, etag);
                // 小于 4096 字节不压缩，仍按明文返回
                if (gzip != null && gzip.length >= MIN_COMPRESS_BYTES) {
                    NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.OK, "application/json",
                            new java.io.ByteArrayInputStream(gzip), gzip.length);
                    response.addHeader("Content-Encoding", "gzip");
                    response.addHeader("Vary", "Accept-Encoding");
                    response.addHeader("ETag", etag);
                    return response;
                }
            }
            StringBuilder sb = new StringBuilder(4096);
            poolManager.renderPool(q, sb::append);
            String json = appendAlias(sb.toString(), alias, "relay/pool");
            NanoHTTPD.Response response = ResponseBuilder.jsonSuccess(json);
            response.addHeader("ETag", etag);
            response.addHeader("Vary", "Accept-Encoding");
            return response;
        } catch (OutOfMemoryError oom) {
            TransferLogger.getInstance().e(TAG, "Pool render OOM", oom);
            return ResponseBuilder.serviceUnavailable();
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "Failed to render pool", t);
            return ResponseBuilder.internalError(t.getMessage());
        }
    }

    private RelayPoolManager.PoolQuery parsePoolQuery(NanoHTTPD.IHTTPSession session, boolean alias) {
        String state = RequestParser.getQueryParameter(session, "state",
                alias ? "downloading,waiting,paused" : null);
        int limit = RequestParser.getIntQueryParameter(session, "limit", 50);
        String cursor = RequestParser.getQueryParameter(session, "cursor");
        boolean includeComplete = RequestParser.getBooleanQueryParameter(session,
                "includeComplete", false);
        String includePages = RequestParser.getQueryParameter(session, "includePages", "bitmap");
        boolean includeMeta = "pack".equalsIgnoreCase(
                RequestParser.getQueryParameter(session, "includeMeta", ""));
        long updatedSince = RequestParser.getLongQueryParameter(session, "updatedSince", 0L);
        String compress = RequestParser.getQueryParameter(session, "compress", "none");
        String sort = RequestParser.getQueryParameter(session, "sort", "updatedAt:desc");
        return RelayPoolManager.parseQuery(state, limit, cursor, includeComplete,
                includePages, includeMeta, updatedSince, compress, sort);
    }

    private byte[] buildGzip(RelayPoolManager.PoolQuery q, String etag) {
        byte[] cached = GZIP_CACHE.get(etag);
        if (cached != null) {
            return cached;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            try (GZIPOutputStream gz = new GZIPOutputStream(bos, 8192)) {
                poolManager.renderPool(q, chunk -> gz.write(chunk.getBytes(StandardCharsets.UTF_8)));
            }
            byte[] bytes = bos.toByteArray();
            GZIP_CACHE.put(etag, bytes);
            return bytes;
        } catch (Throwable t) {
            TransferLogger.getInstance().w(TAG, "Pool gzip failed, fallback plain: " + t.getMessage());
            return null;
        }
    }

    private NanoHTTPD.Response buildGzipBase64Response(RelayPoolManager.PoolQuery q,
                                                       String etag, boolean alias) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        final long[] size = {0L};
        try (GZIPOutputStream gz = new GZIPOutputStream(bos, 8192)) {
            poolManager.renderPool(q, chunk -> {
                byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
                digest.update(bytes);
                size[0] += bytes.length;
                gz.write(bytes);
            });
        }
        byte[] gzip = bos.toByteArray();
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("poolVersion", poolManager.getPoolVersion());
        resp.put("etag", etag);
        resp.put("generatedAt", System.currentTimeMillis());
        resp.put("compress", "gzip-b64");
        resp.put("payloadEncoding", "gzip");
        resp.put("payload", RelayPoolManager.base64(gzip));
        resp.put("payloadSha256", RelayPoolManager.hex(digest.digest()));
        resp.put("uncompressedSize", size[0]);
        if (alias) {
            resp.put("deprecated", true);
            resp.put("aliasOf", "relay/pool");
        }
        NanoHTTPD.Response response = ResponseBuilder.jsonSuccess(resp.toJSONString());
        response.addHeader("ETag", etag);
        response.addHeader("Vary", "Accept-Encoding");
        return response;
    }

    public NanoHTTPD.Response handlePoolSummary(NanoHTTPD.IHTTPSession session, boolean alias) {
        JSONObject json = poolManager.summary();
        if (alias) {
            json.put("deprecated", true);
            json.put("aliasOf", "relay/pool/summary");
        }
        return ResponseBuilder.jsonSuccess(json.toJSONString());
    }

    public NanoHTTPD.Response handlePlan(NanoHTTPD.IHTTPSession session, @Nullable String taskId,
                                         @Nullable Long gidFallback, boolean alias) {
        String only = RequestParser.getQueryParameter(session, "only", "missing");
        int limit = RequestParser.getIntQueryParameter(session, "limit", 200);
        String cursor = RequestParser.getQueryParameter(session, "cursor");
        if (gidFallback == null) {
            long gid = RequestParser.getLongQueryParameter(session, "gid", 0L);
            if (gid > 0) gidFallback = gid;
        }
        JSONObject json = poolManager.plan(taskId, gidFallback, only, limit, cursor);
        if (json == null) {
            return ResponseBuilder.notFound("Task");
        }
        if (json.containsKey("code") && Boolean.FALSE.equals(json.get("success"))) {
            int code = json.getIntValue("code");
            return SafeResponse.json(statusFor(code), json.toJSONString());
        }
        if (alias) {
            json.put("deprecated", true);
            json.put("aliasOf", taskId != null
                    ? "relay/" + taskId + "/plan" : "relay/{taskId}/plan");
        }
        return ResponseBuilder.jsonSuccess(json.toJSONString());
    }

    // ==================== 认领 / 租约 ====================

    public NanoHTTPD.Response handleClaim(NanoHTTPD.IHTTPSession session, boolean alias) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);
            if (json == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Invalid JSON body");
            }
            List<Long> gids = new ArrayList<>();
            JSONArray gidsArray = json.getJSONArray("gids");
            if (gidsArray != null) {
                for (int i = 0; i < gidsArray.size() && gids.size() < 50; i++) {
                    gids.add(gidsArray.getLongValue(i));
                }
            } else {
                long gid = json.getLongValue("gid");
                if (gid > 0) gids.add(gid);
            }
            if (gids.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "gid or gids is required");
            }
            int leaseSeconds = json.getIntValue("leaseSeconds");
            boolean missingOnly = !json.containsKey("missingOnly")
                    || json.getBooleanValue("missingOnly");
            String deviceId = json.getString("deviceId");
            String deviceName = json.getString("deviceName");
            String priority = json.getString("priority");
            String baseBitmap = json.getString("basePagesBitmap");
            if (deviceId == null) {
                deviceId = session.getHeaders().get("x-device-id");
            }
            if (deviceName == null) {
                deviceName = session.getHeaders().get("x-device-name");
            }

            RelayPoolManager.ClaimResult claimResult = poolManager.claim(gids, deviceId, deviceName,
                    leaseSeconds, missingOnly, priority, baseBitmap,
                    session.getRemoteIpAddress(), 0);

            boolean single = gidsArray == null;
            if (single && claimResult.tasks.isEmpty() && !claimResult.errors.isEmpty()) {
                RelayPoolManager.ClaimItem item = claimResult.errors.get(0);
                JSONObject err = RelayPoolManager.error(item.error, item.code);
                err.put("gid", item.gid);
                if (item.currentLease != null) {
                    err.put("currentLease", item.currentLease);
                }
                if (alias) {
                    err.put("deprecated", true);
                    err.put("aliasOf", "relay/pool/claim");
                }
                return SafeResponse.json(statusFor(item.code), err.toJSONString());
            }

            JSONArray tasks = new JSONArray();
            for (RelayPoolManager.ClaimItem item : claimResult.tasks) {
                tasks.add(claimTaskJson(item.task));
            }
            JSONArray errors = new JSONArray();
            for (RelayPoolManager.ClaimItem item : claimResult.errors) {
                JSONObject err = new JSONObject();
                err.put("gid", item.gid);
                err.put("error", item.error);
                err.put("code", item.code);
                if (item.currentLease != null) {
                    err.put("currentLease", item.currentLease);
                }
                errors.add(err);
            }
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("poolVersion", claimResult.poolVersion);
            resp.put("tasks", tasks);
            if (!errors.isEmpty()) {
                resp.put("errors", errors);
            }
            resp.put("total", tasks.size());
            if (alias) {
                resp.put("deprecated", true);
                resp.put("aliasOf", "relay/pool/claim");
            }
            return ResponseBuilder.jsonSuccess(resp.toJSONString());
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "Failed to claim", t);
            return ResponseBuilder.internalError(t.getMessage());
        }
    }

    public NanoHTTPD.Response handleLease(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            JSONObject json = JSON.parseObject(RequestParser.readBody(session));
            if (json == null) json = new JSONObject();
            int leaseSeconds = json.getIntValue("leaseSeconds");
            String deviceId = json.getString("deviceId");
            if (deviceId == null) deviceId = session.getHeaders().get("x-device-id");
            JSONObject error = poolManager.renewLease(taskId, deviceId, leaseSeconds);
            if (error != null) {
                return SafeResponse.json(statusFor(error.getIntValue("code")),
                        error.toJSONString());
            }
            RelayTask task = com.hippo.ehviewer.transfer.core.RelayTaskManager
                    .getInstance(context).getTask(taskId);
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("taskId", taskId);
            resp.put("leaseExpiresAt", task != null ? task.getLeaseExpiresAt() : 0L);
            return ResponseBuilder.jsonSuccess(resp.toJSONString());
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "Failed to renew lease", t);
            return ResponseBuilder.internalError(t.getMessage());
        }
    }

    public NanoHTTPD.Response handleRelease(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            JSONObject json = JSON.parseObject(RequestParser.readBody(session));
            if (json == null) json = new JSONObject();
            String deviceId = json.getString("deviceId");
            String reason = json.getString("reason");
            JSONObject resp = poolManager.release(taskId, deviceId, reason);
            if (resp == null) {
                return ResponseBuilder.notFound("Task");
            }
            if (Boolean.FALSE.equals(resp.get("success"))) {
                return SafeResponse.json(statusFor(resp.getIntValue("code")),
                        resp.toJSONString());
            }
            return ResponseBuilder.jsonSuccess(resp.toJSONString());
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "Failed to release lease", t);
            return ResponseBuilder.internalError(t.getMessage());
        }
    }

    // ==================== 逐页回传 ====================

    public NanoHTTPD.Response handlePartialReturn(NanoHTTPD.IHTTPSession session, boolean alias) {
        List<File> tempFiles = new ArrayList<>();
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            Map<String, String> parmsSnapshot = session.getParms();
            TransferLogger.getInstance().i(TAG, "partial return parts: files="
                    + files.keySet() + " parms="
                    + (parmsSnapshot == null ? "null" : parmsSnapshot.keySet()));
            String manifestStr = files.get("manifest");
            if (manifestStr == null) {
                // NanoHTTPD 2.3.1 在 multipart 中把普通文本字段放进 session.getParms()
                Map<String, String> parms = session.getParms();
                if (parms != null) {
                    manifestStr = parms.get("manifest");
                }
            }
            if (manifestStr == null) {
                manifestStr = files.get("postData");
            }
            if (manifestStr == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Missing manifest");
            }
            if (!manifestStr.trim().startsWith("{")) {
                File mf = new File(manifestStr);
                if (mf.isFile()) {
                    try (InputStream is = new FileInputStream(mf)) {
                        manifestStr = readAll(is);
                    }
                }
            }
            JSONObject manifest = JSON.parseObject(manifestStr);
            if (manifest == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Invalid manifest JSON");
            }
            // NanoHTTPD 2.3.1 会把同一请求中第 2 个及之后的文件 part 重命名：
            // file_9 -> file_91、file_12 -> file_122 ...（pcount 后缀）。
            // 这里把 manifest 的 partName 映射回实际 part 名，避免 missing_part。
            JSONObject manifestPages = manifest.getJSONObject("pages");
            if (manifestPages != null) {
                for (String pageKey : manifestPages.keySet()) {
                    JSONObject meta = manifestPages.getJSONObject(pageKey);
                    if (meta == null) continue;
                    String partName = meta.getString("partName");
                    if (partName == null || partName.isEmpty()) partName = "file_" + pageKey;
                    if (!files.containsKey(partName)) {
                        for (int suffix = 1; suffix <= files.size() + 1; suffix++) {
                            String candidate = partName + suffix;
                            if (files.containsKey(candidate)) {
                                meta.put("partName", candidate);
                                break;
                            }
                        }
                    }
                }
            }
            for (String value : files.values()) {
                if (value != null && !value.startsWith("{")) {
                    File f = new File(value);
                    if (f.isFile()) tempFiles.add(f);
                }
            }
            RelayPoolManager.PartialResult result = poolManager.returnPartial(
                    manifest, files, session.getRemoteIpAddress());
            if (alias && result.json != null) {
                result.json.put("deprecated", true);
                result.json.put("aliasOf", "relay/return/partial");
            }
            return SafeResponse.json(statusFor(result.httpStatus),
                    result.json != null ? result.json.toJSONString() : "{}");
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "Failed to handle partial return", t);
            return ResponseBuilder.internalError(t.getMessage());
        } finally {
            for (File f : tempFiles) {
                try {
                    f.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================== 任务 JSON ====================

    public static JSONObject claimTaskJson(@Nullable RelayTask task) {
        JSONObject json = new JSONObject();
        if (task == null) return json;
        json.put("taskId", task.getTaskId());
        json.put("gid", task.getGid());
        json.put("status", task.getStatus());
        json.put("direction", RelayTask.DIRECTION_INCOMING);
        json.put("leaseExpiresAt", task.getLeaseExpiresAt());
        json.put("totalPages", task.getTotal());
        json.put("downloadedPages", task.getFinished());
        json.put("missingCount", task.getMissingCount());
        if (task.getMissingCount() > 0) {
            // missingPages 明细由 plan 拉取；认领响应只保留位图与计数
        }
        json.put("pagesBitmap", task.getPagesBitmap());
        json.put("planUrl", "/api/v1/relay/" + task.getTaskId() + "/plan?only=missing");
        return json;
    }

    public static JSONObject taskJson(Context context, @Nullable RelayTask task, boolean includePages) {
        JSONObject json = new JSONObject();
        if (task == null) return json;
        DownloadInfo info = com.hippo.ehviewer.EhApplication.getDownloadManager(context)
                .getDownloadInfo(task.getGid());
        RelayPoolManager manager = RelayPoolManager.getInstance(context);
        RelayPoolManager.PageFacts facts = manager.scan(info);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        json.put("taskId", task.getTaskId());
        json.put("gid", task.getGid());
        json.put("token", task.getToken());
        json.put("title", info != null && info.title != null ? info.title : task.getTitle());
        json.put("titleJpn", info != null && info.titleJpn != null ? info.titleJpn : task.getTitleJpn());
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
        int total = facts.totalPages > 0 ? facts.totalPages : Math.max(task.getTotal(), task.getPages());
        int downloaded = facts.totalPages > 0 ? facts.downloadedCount : task.getFinished();
        int missing = facts.totalPages > 0 ? facts.missingPages.size()
                : Math.max(0, total - downloaded);
        json.put("finished", downloaded);
        json.put("total", total);
        json.put("downloadedPages", downloaded);
        json.put("missingCount", missing);
        json.put("pagesBitmap", facts.totalPages > 0
                ? RelayPageBitmap.encode(facts.downloaded, facts.totalPages)
                : task.getPagesBitmap());
        JSONArray missingArray = new JSONArray();
        for (Integer page : facts.missingPages) missingArray.add(page);
        json.put("missingPages", missingArray);
        json.put("receivedPages", task.getReceivedPages());
        json.put("failedPages", task.getFailedPages());
        json.put("speed", task.getSpeed());
        json.put("speedFormatted", formatSpeed(task.getSpeed()));
        json.put("downloadedSize", task.getDownloadedSize());
        json.put("downloadedSizeFormatted", formatSize(task.getDownloadedSize()));
        json.put("totalSize", task.getTotalSize());
        json.put("totalSizeFormatted", formatSize(task.getTotalSize()));
        float progress = total > 0 ? downloaded * 100f / total : 0f;
        json.put("progress", Math.round(progress * 10f) / 10f);
        json.put("lease", RelayPoolManager.leaseJson(task));
        json.put("planUrl", "/api/v1/relay/" + task.getTaskId() + "/plan");
        json.put("createdTime", task.getCreatedTime());
        json.put("updatedTime", task.getUpdatedTime());
        json.put("acceptedTime", task.getAcceptedTime());
        json.put("completedTime", task.getCompletedTime() > 0
                ? Long.valueOf(task.getCompletedTime()) : null);
        json.put("returnedTime", task.getReturnedTime() > 0
                ? Long.valueOf(task.getReturnedTime()) : null);
        json.put("createdDate", formatDate(dateFormat, task.getCreatedTime()));
        json.put("updatedDate", formatDate(dateFormat, task.getUpdatedTime()));
        json.put("acceptedDate", formatDate(dateFormat, task.getAcceptedTime()));

        if (includePages) {
            JSONObject plan = manager.plan(task.getTaskId(), null, "all", 500, null);
            if (plan != null && plan.getJSONArray("pages") != null) {
                json.put("pages", plan.getJSONArray("pages"));
            }
        }
        return json;
    }

    private static String formatDate(SimpleDateFormat format, long time) {
        if (time <= 0) return null;
        return format.format(new Date(time));
    }

    private static String appendAlias(String json, boolean alias, String aliasOf) {
        if (!alias || json == null || !json.endsWith("}")) {
            return json;
        }
        return json.substring(0, json.length() - 1)
                + ",\"deprecated\":true,\"aliasOf\":\"" + aliasOf + "\"}";
    }

    public static NanoHTTPD.Response.IStatus statusFor(int code) {
        switch (code) {
            case RelayPoolManager.CODE_BAD_REQUEST:
                return SafeResponse.status(400);
            case RelayPoolManager.CODE_FORBIDDEN:
                return SafeResponse.status(403);
            case RelayPoolManager.CODE_NOT_FOUND:
                return SafeResponse.status(404);
            case RelayPoolManager.CODE_CONFLICT:
                return SafeResponse.status(409);
            case RelayPoolManager.CODE_PRECONDITION:
                return SafeResponse.status(412);
            case RelayPoolManager.CODE_LEASE_INVALID:
                return SafeResponse.status(428);
            case RelayPoolManager.CODE_OVERLOAD:
                return SafeResponse.status(503);
            default:
                return SafeResponse.status(code >= 200 && code < 600 ? code : 200);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = in.read(buffer)) > 0) {
            bos.write(buffer, 0, len);
        }
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    private static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond <= 0) return "0 B/s";
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        if (bytesPerSecond < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0);
        return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024));
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
