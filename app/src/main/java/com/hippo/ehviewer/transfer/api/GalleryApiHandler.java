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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.hippo.beerbelly.BeerBelly;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.dao.LocalFavoriteInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.ResponseCache;
import com.hippo.unifile.UniFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

/**
 * 画廊API处理器
 */
public class GalleryApiHandler extends BaseApiHandler {
    
    private static final String TAG = "GalleryApiHandler";
    
    // 类别映射
    private static final String[] CATEGORY_NAMES = {
        "Misc", "Doujinshi", "Manga", "Artist CG", "Game CG",
        "Image Set", "Cosplay", "Asian Porn", "Non-H", "Western"
    };
    
    public GalleryApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }
    
    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);
        
        // 移除查询参数进行匹配
        String path = uri.split("\\?")[0];
        
        // /api/v1/galleries - 列表
        if (path.equals("/api/v1/galleries") || uri.startsWith("/api/v1/galleries?")) {
            return handleList(session);
        }
        
        // /api/v1/galleries/{gid} - 详情
        if (path.matches("/api/v1/galleries/\\d+")) {
            long gid = RequestParser.extractGid(path);
            return handleDetail(session, gid);
        }
        
        // /api/v1/galleries/{gid}/thumbnail - 缩略图
        if (path.matches("/api/v1/galleries/\\d+/thumbnail")) {
            long gid = RequestParser.extractGid(path);
            return handleThumbnail(session, gid);
        }

        // /api/v1/favorites - 收藏列表
        if (path.equals("/api/v1/favorites") || uri.startsWith("/api/v1/favorites?")) {
            return handleFavoritesList(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }
    
    @Override
    public NanoHTTPD.Response handleDelete(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("DELETE", uri);
        
        // 移除查询参数进行匹配
        String path = uri.split("\\?")[0];
        
        // /api/v1/galleries/{gid} - 删除
        if (path.matches("/api/v1/galleries/\\d+")) {
            long gid = RequestParser.extractGid(path);
            return handleDeleteGallery(session, gid);
        }
        
        // /api/v1/galleries/batch - 批量删除
        if (path.equals("/api/v1/galleries/batch")) {
            return handleBatchDelete(session);
        }
        
        return ResponseBuilder.notFound("Endpoint");
    }
    
    @Override
    public NanoHTTPD.Response handleQuery(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("QUERY", uri);
        
        // /api/v1/galleries - 查询
        if (uri.equals("/api/v1/galleries")) {
            return handleQueryList(session);
        }

        // /api/v1/favorites - 查询收藏
        if (uri.equals("/api/v1/favorites")) {
            return handleFavoritesList(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }
    
    /**
     * 获取画廊列表
     */
    private NanoHTTPD.Response handleList(NanoHTTPD.IHTTPSession session) {
        try {
            // 获取分页参数
            int page = RequestParser.getIntQueryParameter(session, "page", 1);
            int limit = normalizeLimit(RequestParser.getIntQueryParameter(session, "limit", 30));
            String label = urlDecode(RequestParser.getQueryParameter(session, "label"));
            String search = urlDecode(RequestParser.getQueryParameter(session, "search"));
            String sortExpr = urlDecode(RequestParser.getQueryParameter(session, "sort"));
            String filterExpr = urlDecode(RequestParser.getQueryParameter(session, "filter"));

            boolean hasExpression = (sortExpr != null && !sortExpr.isEmpty()) ||
                    (filterExpr != null && !filterExpr.isEmpty()) ||
                    (search != null && !search.isEmpty());

            // Check cache (only for non-search / non-expression requests)
            String cacheKey = ResponseCache.buildKey("galleries", String.valueOf(page), String.valueOf(limit),
                    label != null ? label : "", search != null ? search : "",
                    sortExpr != null ? sortExpr : "", filterExpr != null ? filterExpr : "");
            if (!hasExpression) {
                String cached = ResponseCache.getInstance().get(cacheKey);
                if (cached != null) {
                    Log.d(TAG, "Cache hit for galleries list");
                    return ResponseBuilder.jsonSuccess(cached);
                }
            }

            // 获取下载列表
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

            // 应用高级筛选表达式 + 关键字搜索
            allList = applyAdvancedFilters(allList, filterExpr, search);

            // 应用高级排序表达式（无表达式时按下载时间倒序）
            allList = sortByExpression(allList, sortExpr);

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
            if (sortExpr != null && !sortExpr.isEmpty()) sb.append(",\"sort\":\"").append(escapeJson(sortExpr)).append("\"");
            if (filterExpr != null && !filterExpr.isEmpty()) sb.append(",\"filter\":\"").append(escapeJson(filterExpr)).append("\"");
            sb.append(",\"galleries\":[");

            boolean first = true;
            for (DownloadInfo info : pageList) {
                if (!first) sb.append(",");
                first = false;
                sb.append(formatGalleryJson(info, false));
            }

            sb.append("]}");

            String responseJson = sb.toString();

            // Store in cache (only non-expression requests)
            if (!hasExpression) {
                ResponseCache.getInstance().put(cacheKey, responseJson);
            }

            return ResponseBuilder.jsonSuccess(responseJson);

        } catch (Exception e) {
            Log.e(TAG, "Error listing galleries", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 获取画廊详情
     */
    private NanoHTTPD.Response handleDetail(NanoHTTPD.IHTTPSession session, long gid) {
        try {
            // Check cache
            String cacheKey = ResponseCache.buildKey("gallery_detail", String.valueOf(gid));
            String cached = ResponseCache.getInstance().get(cacheKey);
            if (cached != null) {
                Log.d(TAG, "Cache hit for gallery detail: " + gid);
                return ResponseBuilder.jsonSuccess(cached);
            }

            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            
            if (info == null) {
                return ResponseBuilder.notFound("Gallery");
            }
            
            String json = formatGalleryJson(info, true);
            
            // Store in cache
            ResponseCache.getInstance().put(cacheKey, json);
            
            return ResponseBuilder.jsonSuccess(json);
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting gallery detail", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 获取缩略图
     */
    private NanoHTTPD.Response handleThumbnail(NanoHTTPD.IHTTPSession session, long gid) {
        try {
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            
            if (info == null) {
                return ResponseBuilder.notFound("Gallery");
            }
            
            // 获取压缩参数
            int maxWidth = RequestParser.getIntQueryParameter(session, "maxWidth", 300);
            int maxHeight = RequestParser.getIntQueryParameter(session, "maxHeight", 400);
            int quality = RequestParser.getIntQueryParameter(session, "quality", 80);
            
            // 尝试从缓存获取缩略图
            BeerBelly beerBelly = EhApplication.getConaco(context).getBeerBelly();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            
            String cacheKey = EhCacheKeyFactory.getThumbKey(gid);
            boolean fromCache = beerBelly.pullFromDiskCache(cacheKey, outputStream);
            
            if (fromCache && outputStream.size() > 0) {
                byte[] thumbData = outputStream.toByteArray();
                
                // 压缩缩略图
                byte[] compressed = compressThumbnail(thumbData, maxWidth, maxHeight, quality);
                
                if (compressed != null) {
                    return NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK,
                        "image/jpeg",
                        new java.io.ByteArrayInputStream(compressed),
                        compressed.length
                    );
                }
                
                // 压缩失败，返回原图
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "image/jpeg",
                    new java.io.ByteArrayInputStream(thumbData),
                    thumbData.length
                );
            }
            
            // 缓存中没有，返回默认图片或404
            return ResponseBuilder.notFound("Thumbnail");
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting thumbnail", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 在主线程同步执行任务（阻塞当前线程直到完成）
     */
    private static void runOnUiThreadSync(Runnable task) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
        } else {
            final CountDownLatch latch = new CountDownLatch(1);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
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
    
    /**
     * 删除单个画廊
     */
    private NanoHTTPD.Response handleDeleteGallery(NanoHTTPD.IHTTPSession session, long gid) {
        try {
            // 检查删除开关
            if (!Settings.isRemoteDeleteEnabled()) {
                return ResponseBuilder.forbidden("Remote delete is disabled");
            }
            
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            
            if (info == null) {
                return ResponseBuilder.notFound("Gallery");
            }
            
            // 在主线程执行删除，避免View线程冲突
            runOnUiThreadSync(() -> downloadManager.deleteDownload(gid));
            
            // 删除下载目录文件
            if (Settings.isDeleteFilesOnRemoteDelete()) {
                UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
                if (downloadDir != null && downloadDir.isDirectory()) {
                    downloadDir.delete();
                }
            }
            
            // Invalidate cache
            ResponseCache.getInstance().invalidateGalleries();
            
            return ResponseBuilder.jsonSuccess("{\"success\":true,\"message\":\"Gallery deleted\"}");
            
        } catch (Exception e) {
            Log.e(TAG, "Error deleting gallery", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 批量删除画廊
     */
    private NanoHTTPD.Response handleBatchDelete(NanoHTTPD.IHTTPSession session) {
        try {
            // 检查删除开关
            if (!Settings.isRemoteDeleteEnabled()) {
                return ResponseBuilder.forbidden("Remote delete is disabled");
            }
            
            String body = RequestParser.readBody(session);
            
            // 解析GID列表
            List<Long> gids = parseGidList(body);
            
            if (gids.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, 
                    "No GIDs provided");
            }
            
            int successCount = 0;
            int failCount = 0;
            
            for (long gid : gids) {
                DownloadInfo info = downloadManager.getDownloadInfo(gid);
                
                if (info == null) {
                    failCount++;
                    continue;
                }
                
                // 在主线程执行删除，避免View线程冲突
                long finalGid = gid;
                runOnUiThreadSync(() -> downloadManager.deleteDownload(finalGid));
                
                // 删除下载目录文件
                if (Settings.isDeleteFilesOnRemoteDelete()) {
                    UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
                    if (downloadDir != null && downloadDir.isDirectory()) {
                        downloadDir.delete();
                    }
                }
                
                successCount++;
            }
            
            // Invalidate cache
            ResponseCache.getInstance().invalidateGalleries();
            
            String json = "{\"success\":true,\"deleted\":" + successCount + 
                         ",\"failed\":" + failCount + "}";
            return ResponseBuilder.jsonSuccess(json);
            
        } catch (Exception e) {
            Log.e(TAG, "Error batch deleting galleries", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * QUERY方法查询画廊列表（支持高级排序/筛选表达式）
     */
    private NanoHTTPD.Response handleQueryList(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);

            // 稳健的JSON解析（项目已依赖 fastjson）
            int page = 1;
            int limit = 30;
            String sortExpr = "";
            String filterExpr = "";
            String search = "";
            String label = "";

            try {
                com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(body);
                if (json != null) {
                    if (json.containsKey("page")) page = json.getIntValue("page");
                    if (json.containsKey("limit")) limit = json.getIntValue("limit");
                    if (json.containsKey("sort")) sortExpr = json.getString("sort");
                    if (json.containsKey("filter")) filterExpr = json.getString("filter");
                    if (json.containsKey("search")) search = json.getString("search");
                    if (json.containsKey("label")) label = json.getString("label");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse QUERY body, falling back", e);
            }

            limit = normalizeLimit(limit);

            // 获取下载列表
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

            // 应用高级筛选 + 关键字搜索
            allList = applyAdvancedFilters(allList, filterExpr, search);

            // 排序
            allList = sortByExpression(allList, sortExpr);

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
            if (sortExpr != null && !sortExpr.isEmpty()) sb.append(",\"sort\":\"").append(escapeJson(sortExpr)).append("\"");
            if (filterExpr != null && !filterExpr.isEmpty()) sb.append(",\"filter\":\"").append(escapeJson(filterExpr)).append("\"");
            sb.append(",\"galleries\":[");

            boolean first = true;
            for (DownloadInfo info : pageList) {
                if (!first) sb.append(",");
                first = false;
                sb.append(formatGalleryJson(info, false));
            }

            sb.append("]}");

            return ResponseBuilder.jsonSuccess(sb.toString());

        } catch (Exception e) {
            Log.e(TAG, "Error querying galleries", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 获取收藏列表（本地收藏 + 合并下载信息）
     * GET /api/v1/favorites?page=&limit=&search=&sort=&filter=
     * QUERY /api/v1/favorites  {page, limit, sort, filter, search}
     */
    private NanoHTTPD.Response handleFavoritesList(NanoHTTPD.IHTTPSession session) {
        try {
            int page = 1;
            int limit = 30;
            String sortExpr = "";
            String filterExpr = "";
            String search = "";

            if ("QUERY".equals(session.getMethod().name())) {
                String body = RequestParser.readBody(session);
                try {
                    com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(body);
                    if (json != null) {
                        if (json.containsKey("page")) page = json.getIntValue("page");
                        if (json.containsKey("limit")) limit = json.getIntValue("limit");
                        if (json.containsKey("sort")) sortExpr = json.getString("sort");
                        if (json.containsKey("filter")) filterExpr = json.getString("filter");
                        if (json.containsKey("search")) search = json.getString("search");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse favorites QUERY body", e);
                }
            } else {
                page = RequestParser.getIntQueryParameter(session, "page", 1);
                limit = RequestParser.getIntQueryParameter(session, "limit", 30);
                sortExpr = urlDecode(RequestParser.getQueryParameter(session, "sort", ""));
                filterExpr = urlDecode(RequestParser.getQueryParameter(session, "filter", ""));
                search = urlDecode(RequestParser.getQueryParameter(session, "search", ""));
            }

            limit = normalizeLimit(limit);

            // 获取本地收藏
            List<GalleryInfo> favList = EhDB.getAllLocalFavorites();
            if (favList == null) {
                favList = new ArrayList<>();
            }
            List<LocalFavoriteInfo> favorites = new ArrayList<>(favList.size());
            for (GalleryInfo gi : favList) {
                if (gi instanceof LocalFavoriteInfo) {
                    favorites.add((LocalFavoriteInfo) gi);
                } else {
                    favorites.add(new LocalFavoriteInfo(gi));
                }
            }

            List<FavoriteView> views = new ArrayList<>(favorites.size());
            for (LocalFavoriteInfo fav : favorites) {
                DownloadInfo dl = downloadManager.getDownloadInfo(fav.gid);
                views.add(new FavoriteView(fav, dl));
            }

            // 应用筛选 + 关键字
            views = applyAdvancedFiltersToFavorites(views, filterExpr, search);

            // 排序
            views = sortFavoritesByExpression(views, sortExpr);

            // 分页
            int total = views.size();
            int startIndex = (page - 1) * limit;
            int endIndex = Math.min(startIndex + limit, total);

            if (startIndex >= total) {
                startIndex = 0;
                endIndex = 0;
            }

            List<FavoriteView> pageList = views.subList(startIndex, endIndex);

            StringBuilder sb = new StringBuilder();
            sb.append("{\"total\":").append(total);
            sb.append(",\"page\":").append(page);
            sb.append(",\"limit\":").append(limit);
            if (sortExpr != null && !sortExpr.isEmpty()) sb.append(",\"sort\":\"").append(escapeJson(sortExpr)).append("\"");
            if (filterExpr != null && !filterExpr.isEmpty()) sb.append(",\"filter\":\"").append(escapeJson(filterExpr)).append("\"");
            sb.append(",\"galleries\":[");

            boolean first = true;
            for (FavoriteView v : pageList) {
                if (!first) sb.append(",");
                first = false;
                sb.append(formatFavoriteJson(v));
            }

            sb.append("]}");

            return ResponseBuilder.jsonSuccess(sb.toString());

        } catch (Exception e) {
            Log.e(TAG, "Error listing favorites", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 格式化画廊JSON（列表/详情通用）
     */
    private String formatGalleryJson(DownloadInfo info, boolean includeDetails) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"gid\":").append(info.gid);
        sb.append(",\"token\":\"").append(escapeJson(info.token)).append("\"");
        sb.append(",\"title\":\"").append(escapeJson(info.title)).append("\"");

        if (info.titleJpn != null) {
            sb.append(",\"titleJpn\":\"").append(escapeJson(info.titleJpn)).append("\"");
        }

        // 缩略图（Base64）
        String thumbBase64 = getThumbnailBase64(info.gid);
        if (thumbBase64 != null) {
            sb.append(",\"thumb\":\"data:image/jpeg;base64,").append(thumbBase64).append("\"");
        } else if (info.thumb != null) {
            sb.append(",\"thumb\":\"").append(escapeJson(info.thumb)).append("\"");
        }

        sb.append(",\"category\":\"").append(getCategoryName(info.category)).append("\"");
        sb.append(",\"categoryValue\":").append(info.category);
        sb.append(",\"posted\":\"").append(escapeJson(info.posted)).append("\"");
        sb.append(",\"uploader\":\"").append(escapeJson(info.uploader)).append("\"");
        sb.append(",\"rating\":").append(info.rating);
        sb.append(",\"language\":\"").append(escapeJson(info.simpleLanguage)).append("\"");

        // 获取页面数，如果为0则尝试从SpiderInfo读取
        int pages = info.pages;
        if (pages <= 0) {
            pages = info.total;
        }
        if (pages <= 0) {
            pages = getPagesFromSpiderInfo(info.gid);
        }
        sb.append(",\"pages\":").append(pages);

        sb.append(",\"state\":").append(info.state);
        sb.append(",\"label\":\"").append(escapeJson(info.label)).append("\"");
        sb.append(",\"time\":").append(info.time);

        // 创建日期（与文件API对齐）
        sb.append(",\"createdTime\":").append(info.time);
        sb.append(",\"createdDate\":\"").append(formatTime(info.time)).append("\"");

        // 文件大小 / 文件数（与文件API对齐）
        long size = computeGallerySize(info);
        if (size < 0) size = 0;
        sb.append(",\"size\":").append(size);
        sb.append(",\"sizeFormatted\":\"").append(formatSize(size)).append("\"");
        int fileCount = countGalleryFiles(info);
        sb.append(",\"fileCount\":").append(fileCount);

        // 完整性信息
        boolean isComplete = info.state == DownloadInfo.STATE_FINISH;
        int downloadedPages = info.finished > 0 ? info.finished : info.downloaded;
        sb.append(",\"isComplete\":").append(isComplete);
        sb.append(",\"downloadedPages\":").append(downloadedPages);

        // 简单标签
        if (info.simpleTags != null && info.simpleTags.length > 0) {
            sb.append(",\"simpleTags\":[");
            boolean firstTag = true;
            for (String tag : info.simpleTags) {
                if (!firstTag) sb.append(",");
                firstTag = false;
                sb.append("\"").append(escapeJson(tag)).append("\"");
            }
            sb.append("]");
        }

        // 标签列表（tgList）
        if (info.tgList != null && !info.tgList.isEmpty()) {
            sb.append(",\"tags\":[");
            boolean firstTg = true;
            for (String tag : info.tgList) {
                if (!firstTg) sb.append(",");
                firstTg = false;
                sb.append("\"").append(escapeJson(tag)).append("\"");
            }
            sb.append("]");
        }

        if (includeDetails) {
            sb.append(",\"finished\":").append(info.finished);
            sb.append(",\"downloaded\":").append(info.downloaded);
            sb.append(",\"total\":").append(info.total);
            sb.append(",\"fileSize\":").append(info.fileSize);
        }

        sb.append("}");
        return sb.toString();
    }
    
    /**
     * 获取缩略图Base64
     */
    private String getThumbnailBase64(long gid) {
        try {
            BeerBelly beerBelly = EhApplication.getConaco(context).getBeerBelly();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            
            String cacheKey = EhCacheKeyFactory.getThumbKey(gid);
            if (beerBelly.pullFromDiskCache(cacheKey, outputStream)) {
                byte[] thumbData = outputStream.toByteArray();
                return Base64.encodeToString(thumbData, Base64.NO_WRAP);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get thumbnail for gid: " + gid, e);
        }
        return null;
    }
    
    /**
     * 压缩缩略图
     */
    private byte[] compressThumbnail(byte[] original, int maxWidth, int maxHeight, int quality) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(original, 0, original.length, options);
            
            int width = options.outWidth;
            int height = options.outHeight;
            
            // 计算采样率
            int sampleSize = 1;
            while (width / sampleSize > maxWidth * 2 || height / sampleSize > maxHeight * 2) {
                sampleSize *= 2;
            }
            
            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize;
            
            Bitmap bitmap = BitmapFactory.decodeByteArray(original, 0, original.length, options);
            if (bitmap == null) {
                return null;
            }
            
            // 缩放到目标尺寸
            float scale = Math.min((float) maxWidth / bitmap.getWidth(), 
                                   (float) maxHeight / bitmap.getHeight());
            
            if (scale < 1) {
                int newWidth = (int) (bitmap.getWidth() * scale);
                int newHeight = (int) (bitmap.getHeight() * scale);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }
            
            // 压缩为JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            bitmap.recycle();
            
            return baos.toByteArray();
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to compress thumbnail", e);
            return null;
        }
    }
    
    /**
     * 获取类别名称
     */
    private String getCategoryName(int category) {
        int index = -1;
        int temp = category;
        while (temp > 0) {
            temp >>= 1;
            index++;
        }
        
        if (index >= 0 && index < CATEGORY_NAMES.length) {
            return CATEGORY_NAMES[index];
        }
        return "Unknown";
    }
    
    /**
     * 解析GID列表
     */
    private List<Long> parseGidList(String body) {
        List<Long> gids = new ArrayList<>();
        
        // 简单解析: {"gids":[123,456,789]}
        int start = body.indexOf("[");
        int end = body.indexOf("]", start);
        
        if (start >= 0 && end > start) {
            String array = body.substring(start + 1, end);
            String[] items = array.split(",");
            
            for (String item : items) {
                try {
                    gids.add(Long.parseLong(item.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        
        return gids;
    }
    
    /**
     * 规范化每页数量：仅允许 30/50/100/300/500，其余取最接近的允许值（默认30）
     */
    private static final int[] ALLOWED_LIMITS = { 30, 50, 100, 300, 500 };

    private int normalizeLimit(int limit) {
        if (limit <= 0) return 30;
        int best = ALLOWED_LIMITS[0];
        int bestDiff = Math.abs(limit - best);
        for (int allowed : ALLOWED_LIMITS) {
            int diff = Math.abs(limit - allowed);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = allowed;
            }
        }
        return best;
    }

    /**
     * 应用高级筛选表达式 + 关键字搜索（下载画廊）
     * 表达式以 ';' 分隔多个条件（AND），支持：
     *   category in [Doujinshi,Manga]
     *   state in [3,4]
     *   rating>=4   rating<=5   rating>4
     *   size>=1048576
     *   pages>=20
     *   search:关键字        （标题/日文标题/标签/上传者）
     */
    private List<DownloadInfo> applyAdvancedFilters(List<DownloadInfo> list, String filterExpr, String search) {
        List<DownloadInfo> filtered = new ArrayList<>();
        List<FilterClause> clauses = parseFilterExpression(filterExpr);

        String searchLower = (search != null && !search.isEmpty()) ? search.toLowerCase(Locale.ROOT) : null;

        for (DownloadInfo info : list) {
            boolean match = true;

            if (searchLower != null) {
                boolean hit = false;
                if (info.title != null && info.title.toLowerCase(Locale.ROOT).contains(searchLower)) hit = true;
                else if (info.titleJpn != null && info.titleJpn.toLowerCase(Locale.ROOT).contains(searchLower)) hit = true;
                else if (info.uploader != null && info.uploader.toLowerCase(Locale.ROOT).contains(searchLower)) hit = true;
                else if (info.simpleTags != null) {
                    for (String t : info.simpleTags) {
                        if (t != null && t.toLowerCase(Locale.ROOT).contains(searchLower)) { hit = true; break; }
                    }
                }
                if (!hit) match = false;
            }

            if (match && !clauses.isEmpty()) {
                for (FilterClause c : clauses) {
                    if (!c.match(info)) { match = false; break; }
                }
            }

            if (match) filtered.add(info);
        }
        return filtered;
    }

    /**
     * 应用高级筛选表达式 + 关键字搜索（收藏）
     */
    private List<FavoriteView> applyAdvancedFiltersToFavorites(List<FavoriteView> list, String filterExpr, String search) {
        List<FavoriteView> filtered = new ArrayList<>();
        List<FilterClauseFav> clauses = parseFilterExpressionFav(filterExpr);

        String searchLower = (search != null && !search.isEmpty()) ? search.toLowerCase(Locale.ROOT) : null;

        for (FavoriteView v : list) {
            boolean match = true;

            if (searchLower != null) {
                boolean hit = false;
                if (v.fav.title != null && v.fav.title.toLowerCase(Locale.ROOT).contains(searchLower)) hit = true;
                else if (v.fav.titleJpn != null && v.fav.titleJpn.toLowerCase(Locale.ROOT).contains(searchLower)) hit = true;
                else if (v.fav.uploader != null && v.fav.uploader.toLowerCase(Locale.ROOT).contains(searchLower)) hit = true;
                else if (v.fav.simpleTags != null) {
                    for (String t : v.fav.simpleTags) {
                        if (t != null && t.toLowerCase(Locale.ROOT).contains(searchLower)) { hit = true; break; }
                    }
                }
                if (!hit) match = false;
            }

            if (match && !clauses.isEmpty()) {
                for (FilterClauseFav c : clauses) {
                    if (!c.match(v)) { match = false; break; }
                }
            }

            if (match) filtered.add(v);
        }
        return filtered;
    }

    /**
     * 高级排序（多条件组合）
     * 表达式示例：category:asc,state:desc,createdDate:desc,size:desc
     */
    private List<DownloadInfo> sortByExpression(List<DownloadInfo> list, String sortExpr) {
        List<SortClause> clauses = parseSortExpression(sortExpr);
        if (clauses.isEmpty()) {
            // 默认按下载时间倒序
            List<DownloadInfo> sorted = new ArrayList<>(list);
            sorted.sort((a, b) -> Long.compare(b.time, a.time));
            return sorted;
        }
        List<DownloadInfo> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> {
            for (SortClause c : clauses) {
                int cmp = c.compareDownload(a, b);
                if (cmp != 0) return c.ascending ? cmp : -cmp;
            }
            return 0;
        });
        return sorted;
    }

    private List<FavoriteView> sortFavoritesByExpression(List<FavoriteView> list, String sortExpr) {
        List<SortClause> clauses = parseSortExpression(sortExpr);
        if (clauses.isEmpty()) {
            List<FavoriteView> sorted = new ArrayList<>(list);
            sorted.sort((a, b) -> Long.compare(b.getCreatedTime(), a.getCreatedTime()));
            return sorted;
        }
        List<FavoriteView> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> {
            for (SortClause c : clauses) {
                int cmp = c.compareFavorite(a, b);
                if (cmp != 0) return c.ascending ? cmp : -cmp;
            }
            return 0;
        });
        return sorted;
    }

    /**
     * 解析排序表达式 -> 排序子句列表
     */
    private List<SortClause> parseSortExpression(String expr) {
        List<SortClause> clauses = new ArrayList<>();
        if (expr == null || expr.trim().isEmpty()) return clauses;
        String[] parts = expr.split(",");
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String field;
            boolean asc;
            int idx = p.lastIndexOf(':');
            if (idx > 0) {
                field = p.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                asc = "asc".equalsIgnoreCase(p.substring(idx + 1).trim());
            } else {
                field = p.toLowerCase(Locale.ROOT);
                asc = false;
            }
            clauses.add(new SortClause(field, asc));
        }
        return clauses;
    }

    /**
     * 解析筛选表达式 -> 筛选子句列表（下载画廊）
     */
    private List<FilterClause> parseFilterExpression(String expr) {
        List<FilterClause> clauses = new ArrayList<>();
        if (expr == null || expr.trim().isEmpty()) return clauses;
        String[] parts = expr.split(";");
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            FilterClause c = FilterClause.parse(p);
            if (c != null) clauses.add(c);
        }
        return clauses;
    }

    private List<FilterClauseFav> parseFilterExpressionFav(String expr) {
        List<FilterClauseFav> clauses = new ArrayList<>();
        if (expr == null || expr.trim().isEmpty()) return clauses;
        String[] parts = expr.split(";");
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            FilterClauseFav c = FilterClauseFav.parse(p);
            if (c != null) clauses.add(c);
        }
        return clauses;
    }

    /**
     * 排序子句：支持字段 title, rating, pages, category, state, createdDate, size, uploader, posted
     */
    private static class SortClause {
        final String field;
        final boolean ascending;

        SortClause(String field, boolean ascending) {
            this.field = field;
            this.ascending = ascending;
        }

        int compareDownload(DownloadInfo a, DownloadInfo b) {
            switch (field) {
                case "title":
                    return compareStrings(a.title, b.title);
                case "rating":
                    return Float.compare(a.rating, b.rating);
                case "pages":
                    return Integer.compare(a.pages, b.pages);
                case "category":
                    return Integer.compare(a.category, b.category);
                case "state":
                    return Integer.compare(a.state, b.state);
                case "createddate":
                case "createdtime":
                case "time":
                    return Long.compare(a.time, b.time);
                case "size":
                    return Long.compare(a.fileSize > 0 ? a.fileSize : 0, b.fileSize > 0 ? b.fileSize : 0);
                case "uploader":
                    return compareStrings(a.uploader, b.uploader);
                case "posted":
                    return compareStrings(a.posted, b.posted);
                default:
                    return Long.compare(a.time, b.time);
            }
        }

        int compareFavorite(FavoriteView a, FavoriteView b) {
            switch (field) {
                case "title":
                    return compareStrings(a.fav.title, b.fav.title);
                case "rating":
                    return Float.compare(a.fav.rating, b.fav.rating);
                case "pages":
                    return Integer.compare(a.getPages(), b.getPages());
                case "category":
                    return Integer.compare(a.fav.category, b.fav.category);
                case "state":
                    return Integer.compare(a.getState(), b.getState());
                case "createddate":
                case "createdtime":
                case "time":
                    return Long.compare(a.getCreatedTime(), b.getCreatedTime());
                case "size":
                    return Long.compare(a.getSize(), b.getSize());
                case "uploader":
                    return compareStrings(a.fav.uploader, b.fav.uploader);
                case "posted":
                    return compareStrings(a.fav.posted, b.fav.posted);
                default:
                    return Long.compare(a.getCreatedTime(), b.getCreatedTime());
            }
        }
    }

    /**
     * 筛选子句（下载画廊）
     */
    private static class FilterClause {
        static FilterClause parse(String expr) {
            try {
                if (expr.toLowerCase(Locale.ROOT).startsWith("category in ")) {
                    List<String> vals = parseInList(expr.substring(expr.indexOf('[')));
                    return new CategoryInClause(vals);
                }
                if (expr.toLowerCase(Locale.ROOT).startsWith("state in ")) {
                    List<String> vals = parseInList(expr.substring(expr.indexOf('[')));
                    return new StateInClause(vals);
                }
                if (expr.toLowerCase(Locale.ROOT).startsWith("search:")) {
                    return new SearchClause(expr.substring(expr.indexOf(':') + 1).trim());
                }
                if (expr.contains(">=")) return new NumericClause(expr, ">=");
                if (expr.contains("<=")) return new NumericClause(expr, "<=");
                if (expr.contains(">")) return new NumericClause(expr, ">");
                if (expr.contains("<")) return new NumericClause(expr, "<");
                if (expr.contains("=")) return new NumericClause(expr, "=");
            } catch (Exception e) {
                return null;
            }
            return null;
        }

        boolean match(DownloadInfo info) { return true; }
    }

    private static class CategoryInClause extends FilterClause {
        final List<String> vals;
        CategoryInClause(List<String> vals) { this.vals = vals; }
        @Override boolean match(DownloadInfo info) {
            String name = CATEGORY_NAMES.length > 0 ? getCategoryNameStatic(info.category) : String.valueOf(info.category);
            return vals.contains(name) || vals.contains(String.valueOf(info.category));
        }
    }

    private static class StateInClause extends FilterClause {
        final List<Integer> vals;
        StateInClause(List<String> vals) {
            this.vals = new ArrayList<>();
            for (String v : vals) {
                try { this.vals.add(Integer.parseInt(v.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        @Override boolean match(DownloadInfo info) { return vals.contains(info.state); }
    }

    private static class SearchClause extends FilterClause {
        final String kw;
        SearchClause(String kw) { this.kw = kw.toLowerCase(Locale.ROOT); }
        @Override boolean match(DownloadInfo info) {
            if (info.title != null && info.title.toLowerCase(Locale.ROOT).contains(kw)) return true;
            if (info.titleJpn != null && info.titleJpn.toLowerCase(Locale.ROOT).contains(kw)) return true;
            if (info.uploader != null && info.uploader.toLowerCase(Locale.ROOT).contains(kw)) return true;
            if (info.simpleTags != null) {
                for (String t : info.simpleTags) {
                    if (t != null && t.toLowerCase(Locale.ROOT).contains(kw)) return true;
                }
            }
            return false;
        }
    }

    private static class NumericClause extends FilterClause {
        final String field;
        final String op;
        final double value;
        NumericClause(String expr, String op) {
            this.op = op;
            int idx = expr.indexOf(op);
            this.field = expr.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            this.value = Double.parseDouble(expr.substring(idx + op.length()).trim());
        }
        @Override boolean match(DownloadInfo info) {
            double actual;
            switch (field) {
                case "rating": actual = info.rating; break;
                case "pages": actual = info.pages; break;
                case "size": actual = info.fileSize > 0 ? info.fileSize : 0; break;
                case "state": actual = info.state; break;
                default: return true;
            }
            switch (op) {
                case ">=": return actual >= value;
                case "<=": return actual <= value;
                case ">": return actual > value;
                case "<": return actual < value;
                case "=": return actual == value;
                default: return true;
            }
        }
    }

    /**
     * 筛选子句（收藏）
     */
    private static class FilterClauseFav {
        static FilterClauseFav parse(String expr) {
            try {
                if (expr.toLowerCase(Locale.ROOT).startsWith("category in ")) {
                    List<String> vals = parseInList(expr.substring(expr.indexOf('[')));
                    return new CategoryInClauseFav(vals);
                }
                if (expr.toLowerCase(Locale.ROOT).startsWith("state in ")) {
                    List<String> vals = parseInList(expr.substring(expr.indexOf('[')));
                    return new StateInClauseFav(vals);
                }
                if (expr.toLowerCase(Locale.ROOT).startsWith("search:")) {
                    return new SearchClauseFav(expr.substring(expr.indexOf(':') + 1).trim());
                }
                if (expr.contains(">=")) return new NumericClauseFav(expr, ">=");
                if (expr.contains("<=")) return new NumericClauseFav(expr, "<=");
                if (expr.contains(">")) return new NumericClauseFav(expr, ">");
                if (expr.contains("<")) return new NumericClauseFav(expr, "<");
                if (expr.contains("=")) return new NumericClauseFav(expr, "=");
            } catch (Exception e) {
                return null;
            }
            return null;
        }
        boolean match(FavoriteView v) { return true; }
    }

    private static class CategoryInClauseFav extends FilterClauseFav {
        final List<String> vals;
        CategoryInClauseFav(List<String> vals) { this.vals = vals; }
        @Override boolean match(FavoriteView v) {
            String name = getCategoryNameStatic(v.fav.category);
            return vals.contains(name) || vals.contains(String.valueOf(v.fav.category));
        }
    }

    private static class StateInClauseFav extends FilterClauseFav {
        final List<Integer> vals;
        StateInClauseFav(List<String> vals) {
            this.vals = new ArrayList<>();
            for (String s : vals) {
                try { this.vals.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        @Override boolean match(FavoriteView v) { return vals.contains(v.getState()); }
    }

    private static class SearchClauseFav extends FilterClauseFav {
        final String kw;
        SearchClauseFav(String kw) { this.kw = kw.toLowerCase(Locale.ROOT); }
        @Override boolean match(FavoriteView v) {
            if (v.fav.title != null && v.fav.title.toLowerCase(Locale.ROOT).contains(kw)) return true;
            if (v.fav.titleJpn != null && v.fav.titleJpn.toLowerCase(Locale.ROOT).contains(kw)) return true;
            if (v.fav.uploader != null && v.fav.uploader.toLowerCase(Locale.ROOT).contains(kw)) return true;
            if (v.fav.simpleTags != null) {
                for (String t : v.fav.simpleTags) {
                    if (t != null && t.toLowerCase(Locale.ROOT).contains(kw)) return true;
                }
            }
            return false;
        }
    }

    private static class NumericClauseFav extends FilterClauseFav {
        final String field;
        final String op;
        final double value;
        NumericClauseFav(String expr, String op) {
            this.op = op;
            int idx = expr.indexOf(op);
            this.field = expr.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            this.value = Double.parseDouble(expr.substring(idx + op.length()).trim());
        }
        @Override boolean match(FavoriteView v) {
            double actual;
            switch (field) {
                case "rating": actual = v.fav.rating; break;
                case "pages": actual = v.getPages(); break;
                case "size": actual = v.getSize(); break;
                case "state": actual = v.getState(); break;
                default: return true;
            }
            switch (op) {
                case ">=": return actual >= value;
                case "<=": return actual <= value;
                case ">": return actual > value;
                case "<": return actual < value;
                case "=": return actual == value;
                default: return true;
            }
        }
    }

    /**
     * 解析 in [...] 列表（字符串或数字）
     */
    private static List<String> parseInList(String s) {
        List<String> result = new ArrayList<>();
        int start = s.indexOf('[');
        int end = s.indexOf(']');
        if (start < 0 || end < 0) return result;
        String inner = s.substring(start + 1, end);
        for (String item : inner.split(",")) {
            String v = item.trim();
            if (v.startsWith("\"") || v.startsWith("'")) v = v.substring(1);
            if (v.endsWith("\"") || v.endsWith("'")) v = v.substring(0, v.length() - 1);
            result.add(v);
        }
        return result;
    }

    private static String getCategoryNameStatic(int category) {
        int index = -1;
        int temp = category;
        while (temp > 0) {
            temp >>= 1;
            index++;
        }
        if (index >= 0 && index < CATEGORY_NAMES.length) {
            return CATEGORY_NAMES[index];
        }
        return "Unknown";
    }

    /**
     * 收藏视图：合并本地收藏 + 下载信息
     */
    private static class FavoriteView {
        final LocalFavoriteInfo fav;
        final DownloadInfo download;

        FavoriteView(LocalFavoriteInfo fav, DownloadInfo download) {
            this.fav = fav;
            this.download = download;
        }

        int getState() {
            return download != null ? download.state : -1;
        }

        long getCreatedTime() {
            if (download != null && download.time > 0) return download.time;
            return fav.time;
        }

        long getSize() {
            return download != null && download.fileSize > 0 ? download.fileSize : 0;
        }

        int getPages() {
            if (download != null && download.pages > 0) return download.pages;
            if (download != null && download.total > 0) return download.total;
            return fav.pages;
        }

        int getFileCount() {
            return download != null ? download.downloaded : 0;
        }

        boolean isComplete() {
            return download != null && download.state == DownloadInfo.STATE_FINISH;
        }

        int getDownloadedPages() {
            if (download == null) return 0;
            return download.finished > 0 ? download.finished : download.downloaded;
        }
    }

    /**
     * 格式化收藏为画廊JSON（合并下载信息）
     */
    private String formatFavoriteJson(FavoriteView v) {
        LocalFavoriteInfo fav = v.fav;
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"gid\":").append(fav.gid);
        sb.append(",\"token\":\"").append(escapeJson(fav.token)).append("\"");
        sb.append(",\"title\":\"").append(escapeJson(fav.title)).append("\"");

        if (fav.titleJpn != null) {
            sb.append(",\"titleJpn\":\"").append(escapeJson(fav.titleJpn)).append("\"");
        }

        String thumbBase64 = getThumbnailBase64(fav.gid);
        if (thumbBase64 != null) {
            sb.append(",\"thumb\":\"data:image/jpeg;base64,").append(thumbBase64).append("\"");
        } else if (fav.thumb != null) {
            sb.append(",\"thumb\":\"").append(escapeJson(fav.thumb)).append("\"");
        }

        sb.append(",\"category\":\"").append(getCategoryName(fav.category)).append("\"");
        sb.append(",\"categoryValue\":").append(fav.category);
        sb.append(",\"posted\":\"").append(escapeJson(fav.posted)).append("\"");
        sb.append(",\"uploader\":\"").append(escapeJson(fav.uploader)).append("\"");
        sb.append(",\"rating\":").append(fav.rating);
        sb.append(",\"language\":\"").append(escapeJson(fav.simpleLanguage)).append("\"");

        int pages = v.getPages();
        sb.append(",\"pages\":").append(pages);

        sb.append(",\"state\":").append(v.getState());
        sb.append(",\"time\":").append(fav.time);

        long createdTime = v.getCreatedTime();
        sb.append(",\"createdTime\":").append(createdTime);
        sb.append(",\"createdDate\":\"").append(formatTime(createdTime)).append("\"");

        long size = v.getSize();
        sb.append(",\"size\":").append(size);
        sb.append(",\"sizeFormatted\":\"").append(formatSize(size)).append("\"");
        sb.append(",\"fileCount\":").append(v.getFileCount());

        sb.append(",\"isFavorited\":true");

        // 完整性信息
        sb.append(",\"isComplete\":").append(v.isComplete());
        sb.append(",\"downloadedPages\":").append(v.getDownloadedPages());

        sb.append("}");
        return sb.toString();
    }
    
    private static int compareStrings(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareToIgnoreCase(b);
    }
    
    /**
     * 简单JSON字符串提取
     */
    private String extractJsonString(String json, String key, String defaultValue) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return defaultValue;
        
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return defaultValue;
        
        return json.substring(start, end);
    }
    
    /**
     * 简单JSON整数提取
     */
    private int extractJsonInt(String json, String key, int defaultValue) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return defaultValue;
        
        start += search.length();
        int end = json.indexOf(",", start);
        if (end < 0) end = json.indexOf("}", start);
        if (end < 0) return defaultValue;
        
        try {
            return Integer.parseInt(json.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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
     * URL解码（GET 查询参数未自动解码，需手动处理 %3A 等）
     */
    private static String urlDecode(String str) {
        if (str == null) return null;
        try {
            return java.net.URLDecoder.decode(str, "UTF-8");
        } catch (Exception e) {
            return str;
        }
    }
    
    /**
     * 从SpiderInfo获取页面数
     */
    private int getPagesFromSpiderInfo(long gid) {
        try {
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (info == null) return 0;
            
            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            if (downloadDir == null) return 0;
            
            UniFile spiderInfoFile = downloadDir.findFile(".ehviewer");
            if (spiderInfoFile != null) {
                SpiderInfo spiderInfo = SpiderInfo.read(spiderInfoFile);
                if (spiderInfo != null && spiderInfo.pages > 0) {
                    return spiderInfo.pages;
                }
            }
            
            // 尝试统计图片文件
            if (downloadDir.isDirectory()) {
                UniFile[] files = downloadDir.listFiles();
                if (files != null) {
                    int count = 0;
                    for (UniFile f : files) {
                        if (f.isFile()) {
                            String name = f.getName();
                            if (name != null && (name.endsWith(".jpg") || name.endsWith(".jpeg") || 
                                name.endsWith(".png") || name.endsWith(".webp"))) {
                                count++;
                            }
                        }
                    }
                    return count;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get pages from SpiderInfo", e);
        }
        return 0;
    }

    /**
     * 计算画廊下载目录总大小（字节），未下载返回 -1
     */
    private long computeGallerySize(DownloadInfo info) {
        if (info == null) return -1;
        if (info.fileSize > 0) return info.fileSize;
        try {
            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            if (downloadDir == null || !downloadDir.isDirectory()) return -1;
            UniFile[] files = downloadDir.listFiles();
            if (files == null) return -1;
            long total = 0;
            for (UniFile f : files) {
                if (f.isFile()) total += f.length();
            }
            return total;
        } catch (Exception e) {
            Log.w(TAG, "Failed to compute gallery size: " + info.gid, e);
            return -1;
        }
    }

    /**
     * 统计画廊下载目录文件数
     */
    private int countGalleryFiles(DownloadInfo info) {
        if (info == null) return 0;
        try {
            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            if (downloadDir == null || !downloadDir.isDirectory()) return 0;
            UniFile[] files = downloadDir.listFiles();
            return files != null ? files.length : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /**
     * 格式化时间戳为可读日期
     */
    private String formatTime(long time) {
        if (time <= 0) return "";
        try {
            return DATE_FORMAT.format(new Date(time));
        } catch (Exception e) {
            return String.valueOf(time);
        }
    }

    /**
     * 格式化文件大小（与文件API对齐）
     */
    private String formatSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = { "B", "KB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        return String.format(Locale.ROOT, "%.2f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}
