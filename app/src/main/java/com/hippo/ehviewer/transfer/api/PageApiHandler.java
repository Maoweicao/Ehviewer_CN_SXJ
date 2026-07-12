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

import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.lib.image.Image;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.unifile.UniFile;

import java.io.InputStream;
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
        logRequest("GET", uri);
        
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
    
    /**
     * 获取页面列表
     */
    private NanoHTTPD.Response handlePageList(NanoHTTPD.IHTTPSession session, long gid) {
        try {
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            
            if (info == null) {
                Log.w(TAG, "Gallery not found: gid=" + gid);
                return ResponseBuilder.notFound("Gallery");
            }
            
            Log.d(TAG, "DownloadInfo: gid=" + gid + ", pages=" + info.pages + ", total=" + info.total);
            
            // 获取下载目录
            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            Log.d(TAG, "DownloadDir: " + (downloadDir != null ? downloadDir.getUri() : "null"));
            
            int totalPages = info.pages;
            if (totalPages <= 0) {
                totalPages = info.total;
            }
            
            // 如果仍然为0，尝试从SpiderInfo读取
            if (totalPages <= 0 && downloadDir != null) {
                try {
                    UniFile spiderInfoFile = downloadDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME);
                    Log.d(TAG, "SpiderInfo file: " + (spiderInfoFile != null ? "found" : "not found"));
                    if (spiderInfoFile != null) {
                        SpiderInfo spiderInfo = SpiderInfo.read(spiderInfoFile);
                        if (spiderInfo != null) {
                            Log.d(TAG, "SpiderInfo: pages=" + spiderInfo.pages + ", gid=" + spiderInfo.gid);
                            if (spiderInfo.pages > 0) {
                                totalPages = spiderInfo.pages;
                                Log.d(TAG, "Got page count from SpiderInfo: " + totalPages);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to read SpiderInfo", e);
                }
            }
            
            // 如果仍然为0，尝试统计下载目录中的图片文件
            if (totalPages <= 0 && downloadDir != null && downloadDir.isDirectory()) {
                try {
                    UniFile[] files = downloadDir.listFiles();
                    Log.d(TAG, "Files in download dir: " + (files != null ? files.length : 0));
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
                            Log.d(TAG, "Got page count from file count: " + totalPages);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to count files", e);
                }
            }
            
            Log.d(TAG, "Final page count: " + totalPages + " for gid=" + gid);
            
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
            
            return ResponseBuilder.jsonSuccess(sb.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting page list", e);
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
                        Log.d(TAG, "Serving from local file: gid=" + gid + ", page=" + page);
                        return serveLocalFile(imageFile, "local");
                    }
                }
            }
            
            // 2. 检查缓存
            SimpleDiskCache cache = SpiderDen.getCache();
            if (cache != null) {
                String key = EhCacheKeyFactory.getImageKey(gid, pageIndex);
                if (cache.contain(key)) {
                    Log.d(TAG, "Serving from cache: gid=" + gid + ", page=" + page);
                    InputStreamPipe pipe = cache.getInputStreamPipe(key);
                    if (pipe != null) {
                        return serveFromCache(pipe, "cache");
                    }
                }
            }
            
            // 3. 检查是否需要代理下载
            if ("proxy".equals(mode) || Settings.getSyncDownloadWhileReading()) {
                Log.d(TAG, "Proxy downloading: gid=" + gid + ", page=" + page);
                return proxyDownload(gid, pageIndex, info);
            }
            
            // 4. 返回404
            return ResponseBuilder.notFound("Image");
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting page", e);
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
            Log.e(TAG, "Error serving local file", e);
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
            Log.e(TAG, "Error serving from cache", e);
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
            Log.e(TAG, "Error in proxy download", e);
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
            Log.e(TAG, "Error creating temp download info", e);
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
}
