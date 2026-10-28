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

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.ResponseCache;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.unifile.UniFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import fi.iki.elonen.NanoHTTPD;

/**
 * 辅助下载API处理器（§5.24）
 *
 * - GET  /api/v1/aux-download/tasks           未完成画廊总列表
 * - GET  /api/v1/aux-download/tasks/{gid}     单画廊缺口与BaseURL/pToken详情
 * - POST /api/v1/aux-download/upload          回传带校验的压缩图片数据（multipart）
 */
public class AuxDownloadApiHandler extends BaseApiHandler {

    private static final String TAG = "AuxDownloadApiHandler";

    public AuxDownloadApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri, session);
        if (!checkEnabled()) {
            return ResponseBuilder.forbidden("Auxiliary download is disabled");
        }
        String path = uri.split("\\?")[0];
        if (path.equals("/api/v1/aux-download/tasks")) {
            return handleTaskList(session);
        }
        if (path.matches("/api/v1/aux-download/tasks/\\d+")) {
            long gid = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            return handleTaskDetail(session, gid);
        }
        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri, session);
        if (!checkEnabled()) {
            return ResponseBuilder.forbidden("Auxiliary download is disabled");
        }
        String path = uri.split("\\?")[0];
        if (path.equals("/api/v1/aux-download/upload")) {
            return handleUpload(session);
        }
        return ResponseBuilder.notFound("Endpoint");
    }

    private boolean checkEnabled() {
        return Settings.isAuxDownloadEnabled();
    }

    // ==================== 5.24.1 总列表 ====================

    private NanoHTTPD.Response handleTaskList(NanoHTTPD.IHTTPSession session) {
        try {
            String stateParam = RequestParser.getQueryParameter(session, "state", "downloading,waiting,paused");
            boolean includeComplete = RequestParser.getBooleanQueryParameter(session, "includeComplete", false);
            int limit = RequestParser.getIntQueryParameter(session, "limit", 50);
            if (limit <= 0) limit = 50;
            if (limit > 200) limit = 200;

            boolean wantDownloading = stateParam.contains("downloading"),
                    wantWaiting = stateParam.contains("waiting"),
                    wantPaused = stateParam.contains("paused"),
                    wantFailed = stateParam.contains("failed"),
                    wantRelay = stateParam.contains("relay_download"),
                    wantNone = stateParam.contains("none");

            List<DownloadInfo> all = downloadManager.getAllDownloadInfoList();
            JSONArray galleries = new JSONArray();
            int total = 0;
            for (DownloadInfo info : all) {
                boolean match = false;
                switch (info.state) {
                    case DownloadInfo.STATE_DOWNLOAD: match = wantDownloading; break;
                    case DownloadInfo.STATE_WAIT: match = wantWaiting; break;
                    case DownloadInfo.STATE_FAILED: match = wantFailed; break;
                    case DownloadInfo.STATE_RELAY_DOWNLOAD: match = wantRelay; break;
                    case DownloadInfo.STATE_NONE: match = wantNone; break;
                    case DownloadInfo.STATE_UPDATE: match = wantWaiting || wantDownloading; break;
                    default: match = false;
                }
                // paused 在本实现中映射为 STATE_WAIT/NONE 之外无单独态，暂按 wantPaused 不过滤
                if (info.state == DownloadInfo.STATE_FINISH && !includeComplete) {
                    match = false;
                }
                if (!match) continue;
                total++;
                if (galleries.size() >= limit) continue;

                int totalPages = info.pages > 0 ? info.pages : info.total;
                List<Integer> missing = computeMissingPages(info, totalPages);
                JSONObject g = new JSONObject();
                g.put("gid", info.gid);
                g.put("token", info.token);
                g.put("title", info.title);
                g.put("category", info.category);
                g.put("totalPages", totalPages);
                g.put("downloadedPages", info.finished);
                JSONArray missingArr = new JSONArray();
                for (Integer p : missing) missingArr.add(p);
                g.put("missingPages", missingArr);
                g.put("state", getStateName(info.state));
                g.put("stateName", getStateName(info.state));
                g.put("galleryUrl", EhUrl.getGalleryDetailUrl(info.gid, info.token));
                g.put("site", Settings.getGallerySite() == EhUrl.SITE_EX ? "ex" : "e");
                g.put("updatedAt", info.time);
                galleries.add(g);
            }

            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("total", total);
            resp.put("hasMore", total > galleries.size());
            resp.put("galleries", galleries);
            return ResponseBuilder.jsonSuccess(resp.toJSONString());
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to list aux tasks", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== 5.24.2 单画廊详情 ====================

    private NanoHTTPD.Response handleTaskDetail(NanoHTTPD.IHTTPSession session, long gid) {
        try {
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (info == null) {
                return ResponseBuilder.notFound("Gallery");
            }
            int totalPages = info.pages > 0 ? info.pages : info.total;
            if (totalPages <= 0) {
                totalPages = readTotalPagesFromSpiderInfo(info);
            }
            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            SpiderInfo spiderInfo = null;
            if (downloadDir != null) {
                try {
                    UniFile f = downloadDir.findFile(SPIDER_INFO_FILENAME);
                    if (f != null) spiderInfo = SpiderInfo.read(f);
                } catch (Exception ignored) {}
            }

            JSONArray pages = new JSONArray();
            for (int i = 0; i < totalPages; i++) {
                boolean downloaded = false;
                if (downloadDir != null && downloadDir.isDirectory()) {
                    downloaded = SpiderDen.findImageFile(downloadDir, i) != null;
                }
                String pToken = null;
                if (spiderInfo != null && spiderInfo.pTokenMap != null) {
                    pToken = spiderInfo.pTokenMap.get(i);
                }
                JSONObject p = new JSONObject();
                p.put("page", i + 1);
                p.put("downloaded", downloaded);
                p.put("pToken", pToken != null ? pToken : "");
                p.put("pageUrl", pToken != null ? EhUrl.getPageUrl(gid, i, pToken) : "");
                pages.add(p);
            }

            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("gid", info.gid);
            resp.put("token", info.token);
            resp.put("title", info.title);
            resp.put("totalPages", totalPages);
            resp.put("downloadedPages", info.finished);
            resp.put("site", Settings.getGallerySite() == EhUrl.SITE_EX ? "ex" : "e");
            resp.put("galleryUrl", EhUrl.getGalleryDetailUrl(info.gid, info.token));
            resp.put("pages", pages);
            return ResponseBuilder.jsonSuccess(resp.toJSONString());
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to get aux task detail", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== 5.24.3 上传回传 ====================

    private NanoHTTPD.Response handleUpload(NanoHTTPD.IHTTPSession session) {
        List<File> tmpFiles = new ArrayList<>();
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);

            String manifestStr = files.get("manifest");
            if (manifestStr == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing manifest");
            }
            // 兼容 manifest 作为文件 part 的情况
            if (!manifestStr.trim().startsWith("{")) {
                File mf = new File(manifestStr);
                if (mf.isFile()) {
                    try (InputStream is = new FileInputStream(mf)) {
                        manifestStr = readAll(is);
                    }
                }
            }
            JSONObject manifest = JSONObject.parseObject(manifestStr);
            if (manifest == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid manifest JSON");
            }

            long gid = manifest.getLongValue("gid");
            String token = manifest.getString("token");
            int fromPage = manifest.getIntValue("fromPage");
            int toPage = manifest.getIntValue("toPage");
            String algorithm = manifest.getString("algorithm");
            if (algorithm == null || algorithm.isEmpty()) algorithm = "sha256";
            String compress = manifest.getString("compress");
            if (compress == null || compress.isEmpty()) compress = "deflate";
            JSONObject pages = manifest.getJSONObject("pages");

            if (gid <= 0 || token == null || fromPage <= 0 || toPage < fromPage
                    || pages == null || pages.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Invalid manifest: gid/token/fromPage/toPage/pages required");
            }

            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (info == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.NOT_FOUND,
                        "Gallery not found: " + gid);
            }

            // 幂等键：同一 requestId 直接返回首次结果
            String requestId = manifest.getString("requestId");
            if (requestId != null && !requestId.isEmpty()) {
                String cached = IDEMPOTENCY_CACHE.get(requestId);
                if (cached != null) {
                    return ResponseBuilder.jsonSuccess(cached);
                }
            }

            // 覆盖策略：默认不覆盖
            boolean overwrite = manifest.getBooleanValue("overwrite");

            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            if (downloadDir == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        "Failed to resolve download directory");
            }
            if (!downloadDir.isDirectory() && !downloadDir.ensureDir()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        "Failed to create download directory");
            }

            JSONArray results = new JSONArray();
            int succeeded = 0, failed = 0;
            for (String pageKey : pages.keySet()) {
                int page;
                try {
                    page = Integer.parseInt(pageKey);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (page < fromPage || page > toPage) {
                    continue;
                }
                JSONObject meta = pages.getJSONObject(pageKey);
                String partName = meta.getString("partName");
                if (partName == null || partName.isEmpty()) partName = "file_" + page;
                String tmpPath = files.get(partName);
                tmpFiles.add(tmpFile(tmpPath));
                JSONObject r = new JSONObject();
                r.put("page", page);
                if (tmpPath == null || !new File(tmpPath).isFile()) {
                    r.put("status", "missing_part");
                    results.add(r);
                    failed++;
                    continue;
                }
                String expectedHash = meta.getString("hash");
                long expectedSize = meta.getLongValue("size");
                String ext = meta.getString("ext");
                if (ext == null || ext.isEmpty()) ext = "jpg";
                ext = sanitizeExt(ext);

                byte[] decoded;
                try {
                    decoded = decompress(new File(tmpPath), compress);
                } catch (Exception e) {
                    r.put("status", "bad_compress");
                    r.put("error", e.getMessage());
                    results.add(r);
                    failed++;
                    continue;
                }
                if (expectedSize > 0 && decoded.length != expectedSize) {
                    r.put("status", "size_mismatch");
                    r.put("expectedSize", expectedSize);
                    r.put("actualSize", decoded.length);
                    results.add(r);
                    failed++;
                    continue;
                }
                String actualHash = shaHex(algorithm, decoded);
                if (expectedHash != null && !expectedHash.isEmpty()
                        && !expectedHash.equalsIgnoreCase(actualHash)) {
                    r.put("status", "hash_mismatch");
                    r.put("expected", expectedHash);
                    r.put("actual", actualHash);
                    results.add(r);
                    failed++;
                    continue;
                }

                // 落盘（覆盖策略同 §5.24.3：overwrite 默认 false，存在且哈希不一致→exists_conflict）
                String filename = SpiderDen.generateImageFilename(page - 1, "." + ext);
                synchronized (pageLock(gid, page)) {
                    UniFile existing = downloadDir.findFile(filename);
                    boolean existed = existing != null;
                    boolean skipped = false;
                    if (existed) {
                        String oldHash = expectedHash != null && !expectedHash.isEmpty()
                                ? computeFileHash(existing, algorithm) : null;
                        if (expectedHash != null && !expectedHash.isEmpty()
                                && oldHash != null && oldHash.equalsIgnoreCase(expectedHash)) {
                            skipped = true;
                        } else if (!overwrite) {
                            r.put("status", "exists_conflict");
                            r.put("filename", filename);
                            results.add(r);
                            failed++;
                            continue;
                        }
                    }
                    if (skipped) {
                        r.put("status", "skipped");
                        r.put("filename", filename);
                        results.add(r);
                        succeeded++;
                        continue;
                    }
                    UniFile target = downloadDir.createFile(filename);
                    if (target == null) {
                        r.put("status", "internal_error");
                        results.add(r);
                        failed++;
                        continue;
                    }
                    try (OutputStream os = target.openOutputStream()) {
                        os.write(decoded);
                    }
                    downloadManager.markPageDownloaded(gid, page - 1, info);
                    ResponseCache.getInstance().invalidateGalleries();

                    r.put("status", existed ? "overwritten" : "ok");
                    r.put("filename", filename);
                    r.put("size", decoded.length);
                    results.add(r);
                    succeeded++;
                }
            }

            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("gid", gid);
            resp.put("results", results);
            resp.put("succeeded", succeeded);
            resp.put("failed", failed);
            info = downloadManager.getDownloadInfo(gid);
            int totalPages = info != null ? Math.max(info.pages, info.total) : 0;
            resp.put("downloadedPages", info != null ? info.finished : 0);
            resp.put("totalPages", totalPages);
            String respJson = resp.toJSONString();
            if (requestId != null && !requestId.isEmpty()) {
                IDEMPOTENCY_CACHE.put(requestId, respJson);
            }
            return ResponseBuilder.jsonSuccess(respJson);
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to handle aux upload", e);
            return ResponseBuilder.internalError(e.getMessage());
        } finally {
            for (File f : tmpFiles) {
                if (f != null) f.delete();
            }
        }
    }

    // ==================== 工具 ====================

    private static final java.util.Map<String, String> IDEMPOTENCY_CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, String>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 256;
                }
            });

    private static final java.util.Map<String, Object> PAGE_LOCKS = new java.util.HashMap<>();

    private static synchronized Object pageLock(long gid, int page) {
        String key = gid + ":" + page;
        Object lock = PAGE_LOCKS.get(key);
        if (lock == null) {
            lock = new Object();
            PAGE_LOCKS.put(key, lock);
        }
        return lock;
    }

    private List<Integer> computeMissingPages(DownloadInfo info, int totalPages) {
        List<Integer> missing = new ArrayList<>();
        if (totalPages <= 0) return missing;
        UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
        for (int i = 0; i < totalPages; i++) {
            boolean have = false;
            if (downloadDir != null && downloadDir.isDirectory()) {
                have = SpiderDen.findImageFile(downloadDir, i) != null;
            }
            if (!have) missing.add(i + 1);
        }
        return missing;
    }

    private int readTotalPagesFromSpiderInfo(DownloadInfo info) {
        try {
            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            if (downloadDir == null) return 0;
            UniFile f = downloadDir.findFile(SPIDER_INFO_FILENAME);
            if (f == null) return 0;
            SpiderInfo si = SpiderInfo.read(f);
            return si != null ? si.pages : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static final String SPIDER_INFO_FILENAME =
            com.hippo.ehviewer.spider.SpiderQueen.SPIDER_INFO_FILENAME;

    private static File tmpFile(String path) {
        return path == null ? null : new File(path);
    }

    private static String sanitizeExt(String ext) {
        ext = ext.trim().toLowerCase();
        if (ext.startsWith(".")) ext = ext.substring(1);
        if (!ext.matches("[a-z0-9]+")) return "jpg";
        return ext;
    }

    private static byte[] decompress(File f, String compress) throws Exception {
        InputStream in;
        switch (compress == null ? "deflate" : compress.toLowerCase()) {
            case "none":
                in = new FileInputStream(f);
                break;
            case "zip": {
                ZipInputStream zin = new ZipInputStream(new FileInputStream(f));
                ZipEntry e = zin.getNextEntry();
                if (e == null) { zin.close(); throw new IOException("Empty zip"); }
                byte[] data = readAllBytes(zin);
                zin.close();
                return data;
            }
            case "deflate":
            default:
                in = new InflaterInputStream(new FileInputStream(f));
                break;
        }
        byte[] data = readAllBytes(in);
        in.close();
        return data;
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) bos.write(buf, 0, len);
        return bos.toByteArray();
    }

    private static String readAll(InputStream in) throws Exception {
        return new String(readAllBytes(in), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String shaHex(String algorithm, byte[] data) throws Exception {
        String a = algorithm == null ? "sha256" : algorithm.toLowerCase();
        switch (a) {
            case "md5": a = "MD5"; break;
            case "sha1": a = "SHA-1"; break;
            default: a = "SHA-256";
        }
        MessageDigest md = MessageDigest.getInstance(a);
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String computeFileHash(UniFile file, String algorithm) {
        try (InputStream is = file.openInputStream()) {
            String a = algorithm == null ? "sha256" : algorithm.toLowerCase();
            switch (a) {
                case "md5": a = "MD5"; break;
                case "sha1": a = "SHA-1"; break;
                default: a = "SHA-256";
            }
            MessageDigest md = MessageDigest.getInstance(a);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) md.update(buf, 0, len);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String getStateName(int state) {
        switch (state) {
            case DownloadInfo.STATE_NONE: return "none";
            case DownloadInfo.STATE_WAIT: return "waiting";
            case DownloadInfo.STATE_DOWNLOAD: return "downloading";
            case DownloadInfo.STATE_FINISH: return "finished";
            case DownloadInfo.STATE_FAILED: return "failed";
            case DownloadInfo.STATE_UPDATE: return "updating";
            case DownloadInfo.STATE_RELAY_DOWNLOAD: return "relay_download";
            default: return "unknown";
        }
    }
}
