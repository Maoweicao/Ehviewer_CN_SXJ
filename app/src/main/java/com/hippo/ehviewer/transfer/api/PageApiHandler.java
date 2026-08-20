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

import com.alibaba.fastjson.JSONObject;
import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.ResponseCache;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.lib.image.Image;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.unifile.UniFile;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;

/**
 * 图片API处理器
 */
public class PageApiHandler extends BaseApiHandler {

    private static final String TAG = "PageApiHandler";

    // 代理下载超时时间（秒）
    private static final int PROXY_DOWNLOAD_TIMEOUT = 30;

    public PageApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri, session);

        // 移除查询参数进行匹配
        String path = uri.split("\\?")[0];

        // /api/v1/galleries/{gid}/pages - 页面列表
        if (path.matches("/api/v1/galleries/\\d+/pages")) {
            long gid = RequestParser.extractGid(path);
            return handlePageList(session, gid);
        }

        // /api/v1/galleries/{gid}/pages/{page} - 获取图片
        if (path.matches("/api/v1/galleries/\\d+/pages/\\d+")) {
            long gid = RequestParser.extractGid(path);
            int page = RequestParser.extractPage(path);
            return handleGetPage(session, gid, page);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri, session);

        String path = uri.split("\\?")[0];

        // /api/v1/galleries/{gid}/pages/{page}/upload - 上传页面图片
        if (path.matches("/api/v1/galleries/\\d+/pages/\\d+/upload")) {
            long gid = RequestParser.extractGid(path);
            int page = RequestParser.extractPage(path);
            return handleUploadPage(session, gid, page);
        }

        return ResponseBuilder.notFound("Endpoint");
    }
    
    /**
     * 获取页面列表
     */
    private NanoHTTPD.Response handlePageList(NanoHTTPD.IHTTPSession session, long gid) {
        try {
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            
            if (info == null) {
                TransferLogger.getInstance().w(TAG, "Gallery not found: gid=" + gid);
                return ResponseBuilder.notFound("Gallery");
            }
            
            TransferLogger.getInstance().d(TAG, "DownloadInfo: gid=" + gid + ", pages=" + info.pages + ", total=" + info.total);
            
            // 获取下载目录
            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            TransferLogger.getInstance().d(TAG, "DownloadDir: " + (downloadDir != null ? downloadDir.getUri() : "null"));
            
            int totalPages = info.pages;
            if (totalPages <= 0) {
                totalPages = info.total;
            }
            
            // 如果仍然为0，尝试从SpiderInfo读取
            if (totalPages <= 0 && downloadDir != null) {
                try {
                    UniFile spiderInfoFile = downloadDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME);
                    TransferLogger.getInstance().d(TAG, "SpiderInfo file: " + (spiderInfoFile != null ? "found" : "not found"));
                    if (spiderInfoFile != null) {
                        SpiderInfo spiderInfo = SpiderInfo.read(spiderInfoFile);
                        if (spiderInfo != null) {
                            TransferLogger.getInstance().d(TAG, "SpiderInfo: pages=" + spiderInfo.pages + ", gid=" + spiderInfo.gid);
                            if (spiderInfo.pages > 0) {
                                totalPages = spiderInfo.pages;
                                TransferLogger.getInstance().d(TAG, "Got page count from SpiderInfo: " + totalPages);
                            }
                        }
                    }
                } catch (Exception e) {
                    TransferLogger.getInstance().w(TAG, "Failed to read SpiderInfo", e);
                }
            }
            
            // 如果仍然为0，尝试统计下载目录中的图片文件
            if (totalPages <= 0 && downloadDir != null && downloadDir.isDirectory()) {
                try {
                    UniFile[] files = downloadDir.listFiles();
                    TransferLogger.getInstance().d(TAG, "Files in download dir: " + (files != null ? files.length : 0));
                    if (files != null) {
                        int imageCount = 0;
                        for (UniFile file : files) {
                            if (file.isFile()) {
                                String name = file.getName();
                                if (name != null && (name.endsWith(".jpg") || name.endsWith(".jpeg") || 
                                    name.endsWith(".png") || name.endsWith(".webp") ||
                                    name.endsWith(".gif"))) {
                                    imageCount++;
                                }
                            }
                        }
                        if (imageCount > 0) {
                            totalPages = imageCount;
                            TransferLogger.getInstance().d(TAG, "Got page count from file count: " + totalPages);
                        }
                    }
                } catch (Exception e) {
                    TransferLogger.getInstance().w(TAG, "Failed to count files", e);
                }
            }
            
            TransferLogger.getInstance().d(TAG, "Final page count: " + totalPages + " for gid=" + gid);
            
            // Check cache
            String cacheKey = ResponseCache.buildKey("pages", String.valueOf(gid));
            String cached = ResponseCache.getInstance().get(cacheKey);
            if (cached != null) {
                TransferLogger.getInstance().d(TAG, "Cache hit for page list: gid=" + gid);
                return ResponseBuilder.jsonSuccess(cached);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"gid\":").append(gid);
            sb.append(",\"pages\":").append(totalPages);
            sb.append(",\"pageList\":[");
            
            boolean first = true;
            for (int i = 0; i < totalPages; i++) {
                if (!first) sb.append(",");
                first = false;
                
                String state = "pending";
                
                // 检查本地文件
                if (downloadDir != null && downloadDir.isDirectory()) {
                    UniFile imageFile = SpiderDen.findImageFile(downloadDir, i);
                    if (imageFile != null) {
                        state = "downloaded";
                    }
                }
                
                // 检查缓存
                if (!"downloaded".equals(state)) {
                    SimpleDiskCache cache = SpiderDen.getCache();
                    if (cache != null) {
                        String key = EhCacheKeyFactory.getImageKey(gid, i);
                        if (cache.contain(key)) {
                            state = "cached";
                        }
                    }
                }
                
                sb.append("{\"page\":").append(i + 1);
                sb.append(",\"state\":\"").append(state).append("\"");
                sb.append(",\"pToken\":\"\"}");
            }
            
            sb.append("]}");
            
            String responseJson = sb.toString();
            
            // Store in cache
            ResponseCache.getInstance().put(cacheKey, responseJson);
            
            return ResponseBuilder.jsonSuccess(responseJson);
            
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Error getting page list", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 获取图片
     */
    private NanoHTTPD.Response handleGetPage(NanoHTTPD.IHTTPSession session, long gid, int page) {
        try {
            String mode = RequestParser.getQueryParameter(session, "mode");
            
            // 将1-indexed页码转换为0-indexed（内部使用0-indexed）
            int pageIndex = page - 1;
            if (pageIndex < 0) pageIndex = 0;
            
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            
            // 1. 检查本地下载目录
            if (info != null) {
                UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
                if (downloadDir != null && downloadDir.isDirectory()) {
                    UniFile imageFile = SpiderDen.findImageFile(downloadDir, pageIndex);
                    if (imageFile != null) {
                        TransferLogger.getInstance().d(TAG, "Serving from local file: gid=" + gid + ", page=" + page);
                        return serveLocalFile(imageFile, "local");
                    }
                }
            }
            
            // 2. 检查缓存
            SimpleDiskCache cache = SpiderDen.getCache();
            if (cache != null) {
                String key = EhCacheKeyFactory.getImageKey(gid, pageIndex);
                if (cache.contain(key)) {
                    TransferLogger.getInstance().d(TAG, "Serving from cache: gid=" + gid + ", page=" + page);
                    InputStreamPipe pipe = cache.getInputStreamPipe(key);
                    if (pipe != null) {
                        return serveFromCache(pipe, "cache");
                    }
                }
            }
            
            // 3. 检查是否需要代理下载
            if ("proxy".equals(mode) || Settings.getSyncDownloadWhileReading()) {
                TransferLogger.getInstance().d(TAG, "Proxy downloading: gid=" + gid + ", page=" + page);
                return proxyDownload(gid, pageIndex, info);
            }
            
            // 4. 返回404
            return ResponseBuilder.notFound("Image");
            
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Error getting page", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 从本地文件提供服务
     */
    private NanoHTTPD.Response serveLocalFile(UniFile file, String source) {
        try {
            String mimeType = getMimeType(file.getName());
            long length = file.length();
            
            InputStream stream = file.openInputStream();
            
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                mimeType,
                stream,
                length
            );
            
            response.addHeader("X-Image-Source", source);
            response.addHeader("Cache-Control", "max-age=3600");
            
            return response;
            
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Error serving local file", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 从缓存提供服务
     */
    private NanoHTTPD.Response serveFromCache(InputStreamPipe pipe, String source) {
        try {
            pipe.obtain();
            InputStream stream = pipe.open();
            
            NanoHTTPD.Response response = NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.OK,
                "image/jpeg",
                stream
            );
            
            response.addHeader("X-Image-Source", source);
            response.addHeader("Cache-Control", "max-age=3600");
            
            return response;
            
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Error serving from cache", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 代理下载图片
     */
    private NanoHTTPD.Response proxyDownload(long gid, int page, DownloadInfo existingInfo) {
        try {
            // 如果不存在DownloadInfo，创建临时的
            DownloadInfo info = existingInfo;
            if (info == null) {
                info = createTempDownloadInfo(gid);
                if (info == null) {
                    return ResponseBuilder.internalError("Cannot create download info");
                }
            }
            
            // 使用SpiderQueen下载
            final DownloadInfo downloadInfo = info;
            SpiderQueen queen = SpiderQueen.obtainSpiderQueen(
                context, info, SpiderQueen.MODE_DOWNLOAD);
            
            try {
                // 设置下载完成回调
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicBoolean success = new AtomicBoolean(false);
                
                queen.addOnSpiderListener(new SpiderQueen.OnSpiderListener() {
                    @Override
                    public void onGetPages(int pages) {}
                    
                    @Override
                    public void onGet509(int index) {}
                    
                    @Override
                    public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {}
                    
                    @Override
                    public void onPageSuccess(int index, int finished, int downloaded, int total) {
                        if (index == page) {
                            success.set(true);
                            latch.countDown();
                        }
                    }
                    
                    @Override
                    public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
                        if (index == page) {
                            latch.countDown();
                        }
                    }
                    
                    @Override
                    public void onFinish(int finished, int downloaded, int total) {}
                    
                    @Override
                    public void onGetImageSuccess(int index, Image image) {}
                    
                    @Override
                    public void onGetImageFailure(int index, String error) {
                        if (index == page) {
                            latch.countDown();
                        }
                    }
                });
                
                // 请求下载该页面
                queen.request(page);
                
                // 等待下载完成
                boolean completed = latch.await(PROXY_DOWNLOAD_TIMEOUT, TimeUnit.SECONDS);
                
                if (!completed || !success.get()) {
                    return ResponseBuilder.internalError("Download timeout or failed");
                }
                
                // 读取下载的图片
                UniFile downloadDir = SpiderDen.getGalleryDownloadDir(downloadInfo);
                if (downloadDir != null && downloadDir.isDirectory()) {
                    UniFile imageFile = SpiderDen.findImageFile(downloadDir, page);
                    if (imageFile != null) {
                        return serveLocalFile(imageFile, "proxy");
                    }
                }
                
                return ResponseBuilder.internalError("Download completed but file not found");
                
            } finally {
                SpiderQueen.releaseSpiderQueen(queen, SpiderQueen.MODE_DOWNLOAD);
            }
            
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Error in proxy download", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 创建临时DownloadInfo
     */
    private DownloadInfo createTempDownloadInfo(long gid) {
        try {
            // 从GalleryCacheManager获取缓存信息
            // 如果没有缓存，创建最小化的DownloadInfo
            DownloadInfo info = new DownloadInfo();
            info.gid = gid;
            info.token = ""; // 空token，后续会从网络获取
            info.title = "Gallery " + gid;
            info.state = DownloadInfo.STATE_NONE;
            
            return info;
            
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Error creating temp download info", e);
            return null;
        }
    }
    
    /**
     * 获取MIME类型
     */
    private String getMimeType(String filename) {
        if (filename == null) {
            return "image/jpeg";
        }

        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        } else if (lower.endsWith(".gif")) {
            return "image/gif";
        } else if (lower.endsWith(".webp")) {
            return "image/webp";
        } else {
            return "image/jpeg";
        }
    }

    // ==================== 上传页面图片 ====================

    /**
     * 处理上传页面图片请求
     *
     * 流程：
     * 1. 检查总开关 Settings.isRemotePageUploadEnabled()
     * 2. 校验 gid
     * 3. 解析 multipart/form-data：file, extension, hash, algorithm
     * 4. 若 DownloadInfo 不存在 → 自动创建（state=NONE）
     * 5. 计算目标文件名 = %08d.<ext>
     * 6. 检查目标文件是否已存在
     *    - 已存在 + 带 hash → 计算本地 hash 比对：
     *      · 一致 → 返回 skipped:true 不写入
     *      · 不一致 → 覆盖写入并返回 overwritten:true
     *    - 已存在 + 未带 hash → 直接覆盖
     *    - 不存在 → 直接写入
     * 7. 更新 DownloadInfo.finished 与监听器
     * 8. 返回结果
     */
    private NanoHTTPD.Response handleUploadPage(NanoHTTPD.IHTTPSession session, long gid, int page) {
        try {
            // 1. 总开关
            if (!Settings.isRemotePageUploadEnabled()) {
                return ResponseBuilder.forbidden("Remote page upload is disabled");
            }

            // 2. 校验
            if (gid <= 0) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid gid");
            }
            if (page <= 0) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid page");
            }
            int pageIndex = page - 1;

            // 3. 解析 multipart body
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);

            String tmpFilePath = files.get("file");
            if (tmpFilePath == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing file");
            }

            java.io.File tmpFile = new java.io.File(tmpFilePath);
            if (!tmpFile.exists()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "File not received");
            }
            long fileSize = tmpFile.length();

            // 可选参数
            String extension = files.get("extension");
            if (extension == null || extension.isEmpty()) {
                // 从 Content-Type 或文件名推断
                extension = inferExtensionFromContentType(session, tmpFile.getName());
            }
            extension = sanitizeExtension(extension);

            String providedHash = files.get("hash");
            String algorithm = files.get("algorithm");
            if (algorithm == null || algorithm.isEmpty()) {
                algorithm = "md5";
            }
            String normalizedAlgorithm = normalizeAlgorithm(algorithm);
            if (normalizedAlgorithm == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Unsupported hash algorithm: " + algorithm);
            }

            // 4. 获取/创建 DownloadInfo
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            boolean autoCreated = false;
            if (info == null) {
                GalleryInfo galleryInfo = buildGalleryInfoFromForm(files, gid);
                if (galleryInfo == null) {
                    tmpFile.delete();
                    return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                            "Gallery not found and missing GalleryInfo for auto-creation");
                }
                downloadManager.addDownload(galleryInfo, null, DownloadInfo.STATE_NONE);
                info = downloadManager.getDownloadInfo(gid);
                autoCreated = true;
                TransferLogger.getInstance().i(TAG, "Auto-created DownloadInfo for uploaded page: gid=" + gid);
            }

            // 5. 获取下载目录
            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            if (downloadDir == null || !downloadDir.isDirectory()) {
                if (downloadDir == null) {
                    downloadDir = SpiderDen.getGalleryDownloadDir(info);
                }
                if (downloadDir == null) {
                    tmpFile.delete();
                    return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                            "Failed to resolve download directory");
                }
                if (!downloadDir.ensureDir()) {
                    tmpFile.delete();
                    return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                            "Failed to create download directory");
                }
            }

            // 6. 计算目标文件名
            String filename = SpiderDen.generateImageFilename(pageIndex, "." + extension);

            // 7. 覆盖校验
            UniFile existing = downloadDir.findFile(filename);
            boolean existed = (existing != null);
            boolean skipped = false;
            boolean overwritten = false;
            String oldHash = null;

            if (existed && providedHash != null && !providedHash.isEmpty()) {
                oldHash = computeFileHash(existing, normalizedAlgorithm);
                if (oldHash != null && oldHash.equalsIgnoreCase(providedHash)) {
                    // 哈希一致 → 跳过
                    tmpFile.delete();
                    skipped = true;
                    TransferLogger.getInstance().i(TAG, "Upload skipped (hash matches): gid=" + gid + ", page=" + page);
                }
            }

            long writtenSize = fileSize;
            String newHash = null;

            if (!skipped) {
                // 写入新文件
                UniFile targetFile = downloadDir.createFile(filename);
                if (targetFile == null) {
                    tmpFile.delete();
                    return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                            "Failed to create target file: " + filename);
                }

                try (InputStream is = new FileInputStream(tmpFile);
                     java.io.OutputStream os = targetFile.openOutputStream()) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        os.write(buf, 0, len);
                    }
                } catch (Exception e) {
                    tmpFile.delete();
                    TransferLogger.getInstance().e(TAG, "Failed to write uploaded page", e);
                    return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                            "Write failed: " + e.getMessage());
                }
                tmpFile.delete();

                writtenSize = targetFile.length();
                overwritten = existed;

                // 计算新文件 hash（无论是否提供 providedHash 都计算一次，便于返回）
                newHash = computeFileHash(targetFile, normalizedAlgorithm);

                // 更新下载计数
                downloadManager.markPageDownloaded(gid, pageIndex, info);

                // 触发画廊列表缓存失效（状态/页数可能改变）
                ResponseCache.getInstance().invalidateGalleries();

                TransferLogger.getInstance().i(TAG, "Page uploaded: gid=" + gid + ", page=" + page
                        + ", file=" + filename + ", size=" + writtenSize
                        + ", existed=" + existed + ", overwritten=" + overwritten);
            }

            // 8. 构建响应
            info = downloadManager.getDownloadInfo(gid);
            int downloadedPages = info != null ? info.finished : 0;
            int totalPages = info != null ? Math.max(info.pages, info.total) : 0;
            float progress = totalPages > 0 ? (float) downloadedPages / totalPages * 100 : 0;
            String stateName = info != null ? getStateName(info.state) : "unknown";

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("existed", existed);
            response.put("skipped", skipped);
            response.put("overwritten", overwritten);
            response.put("autoCreated", autoCreated);
            response.put("gid", gid);
            response.put("page", page);
            response.put("filename", filename);
            response.put("size", writtenSize);
            response.put("sizeFormatted", formatSize(writtenSize));
            response.put("downloadedPages", downloadedPages);
            response.put("total", totalPages);
            response.put("progress", String.format(Locale.US, "%.1f", progress));
            response.put("state", info != null ? info.state : -1);
            response.put("stateName", stateName);
            response.put("hash", newHash);
            response.put("algorithm", algorithm);
            if (skipped) {
                response.put("message", "File already exists with the same hash");
            } else if (overwritten) {
                response.put("message", "File overwritten");
                response.put("oldHash", oldHash);
            } else {
                response.put("message", "File written");
            }

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Error handling page upload", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 从 multipart 字段构建 GalleryInfo（仅当 DownloadInfo 不存在时使用）
     */
    private GalleryInfo buildGalleryInfoFromForm(Map<String, String> form, long gid) {
        // 至少需要 title 才能创建有意义的信息
        String title = form.get("title");
        if (title == null || title.isEmpty()) {
            return null;
        }
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.token = form.get("token");
        info.title = title;
        info.titleJpn = form.get("titleJpn");
        info.thumb = form.get("thumb");
        try {
            info.category = Integer.parseInt(form.getOrDefault("category", "0"));
        } catch (NumberFormatException ignored) {
            info.category = 0;
        }
        info.posted = form.get("posted");
        info.uploader = form.get("uploader");
        try {
            info.rating = Float.parseFloat(form.getOrDefault("rating", "0"));
        } catch (NumberFormatException ignored) {
            info.rating = 0;
        }
        try {
            info.pages = Integer.parseInt(form.getOrDefault("pages", "0"));
        } catch (NumberFormatException ignored) {
            info.pages = 0;
        }
        return info;
    }

    /**
     * 从 Content-Type 或文件名推断扩展名
     */
    private String inferExtensionFromContentType(NanoHTTPD.IHTTPSession session, String filename) {
        String ct = session.getHeaders().get("content-type");
        if (ct != null) {
            ct = ct.toLowerCase();
            if (ct.contains("png")) return "png";
            if (ct.contains("gif")) return "gif";
            if (ct.contains("webp")) return "webp";
            if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg";
        }
        if (filename != null) {
            String lower = filename.toLowerCase();
            int dot = lower.lastIndexOf('.');
            if (dot >= 0 && dot < lower.length() - 1) {
                return lower.substring(dot + 1);
            }
        }
        return "jpg";
    }

    /**
     * 规范化扩展名：去点、转小写、校验有效性
     */
    private String sanitizeExtension(String ext) {
        if (ext == null || ext.isEmpty()) return "jpg";
        String lower = ext.toLowerCase().trim();
        if (lower.startsWith(".")) lower = lower.substring(1);
        for (String supported : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            if (supported.substring(1).equals(lower)) {
                return lower;
            }
        }
        return "jpg";
    }

    /**
     * 规范化哈希算法名称
     */
    private String normalizeAlgorithm(String algorithm) {
        if (algorithm == null) return "MD5";
        String upper = algorithm.toUpperCase().replace("-", "");
        switch (upper) {
            case "MD5": return "MD5";
            case "SHA1": return "SHA-1";
            case "SHA256": return "SHA-256";
            default: return null;
        }
    }

    /**
     * 计算文件哈希
     */
    private String computeFileHash(UniFile file, String algorithm) {
        if (file == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream is = file.openInputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) {
                    digest.update(buf, 0, len);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            TransferLogger.getInstance().w(TAG, "Failed to compute hash", e);
            return null;
        }
    }

    /**
     * 获取状态名称
     */
    private String getStateName(int state) {
        switch (state) {
            case DownloadInfo.STATE_NONE: return "none";
            case DownloadInfo.STATE_WAIT: return "wait";
            case DownloadInfo.STATE_DOWNLOAD: return "downloading";
            case DownloadInfo.STATE_FINISH: return "finished";
            case DownloadInfo.STATE_FAILED: return "failed";
            case DownloadInfo.STATE_UPDATE: return "update";
            case DownloadInfo.STATE_RELAY_DOWNLOAD: return "relay_download";
            default: return "unknown";
        }
    }

    /**
     * 格式化字节大小
     */
    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
