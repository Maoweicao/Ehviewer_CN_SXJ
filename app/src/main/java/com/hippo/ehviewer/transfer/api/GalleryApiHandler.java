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
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.unifile.UniFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
        
        return ResponseBuilder.notFound("Endpoint");
    }
    
    /**
     * 获取画廊列表
     */
    private NanoHTTPD.Response handleList(NanoHTTPD.IHTTPSession session) {
        try {
            // 获取分页参数
            int page = RequestParser.getIntQueryParameter(session, "page", 1);
            int limit = RequestParser.getIntQueryParameter(session, "limit", 20);
            String label = RequestParser.getQueryParameter(session, "label");
            String search = RequestParser.getQueryParameter(session, "search");
            
            // 获取下载列表
            List<DownloadInfo> allList;
            if (label != null && !label.isEmpty()) {
                allList = downloadManager.getLabelDownloadInfoList(label);
                if (allList == null) {
                    allList = new ArrayList<>();
                }
            } else {
                allList = downloadManager.getAllDownloadInfoList();
            }
            
            // 搜索过滤
            if (search != null && !search.isEmpty()) {
                String searchLower = search.toLowerCase();
                List<DownloadInfo> filtered = new ArrayList<>();
                for (DownloadInfo info : allList) {
                    if (info.title != null && info.title.toLowerCase().contains(searchLower)) {
                        filtered.add(info);
                    } else if (info.titleJpn != null && info.titleJpn.toLowerCase().contains(searchLower)) {
                        filtered.add(info);
                    }
                }
                allList = filtered;
            }
            
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
            Log.e(TAG, "Error listing galleries", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 获取画廊详情
     */
    private NanoHTTPD.Response handleDetail(NanoHTTPD.IHTTPSession session, long gid) {
        try {
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            
            if (info == null) {
                return ResponseBuilder.notFound("Gallery");
            }
            
            return ResponseBuilder.jsonSuccess(formatGalleryJson(info, true));
            
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
            
            // 从下载管理器移除
            downloadManager.deleteDownload(gid);
            
            // 删除下载目录文件
            if (Settings.isDeleteFilesOnRemoteDelete()) {
                UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
                if (downloadDir != null && downloadDir.isDirectory()) {
                    downloadDir.delete();
                }
            }
            
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
                
                // 从下载管理器移除
                downloadManager.deleteDownload(gid);
                
                // 删除下载目录文件
                if (Settings.isDeleteFilesOnRemoteDelete()) {
                    UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
                    if (downloadDir != null && downloadDir.isDirectory()) {
                        downloadDir.delete();
                    }
                }
                
                successCount++;
            }
            
            String json = "{\"success\":true,\"deleted\":" + successCount + 
                         ",\"failed\":" + failCount + "}";
            return ResponseBuilder.jsonSuccess(json);
            
        } catch (Exception e) {
            Log.e(TAG, "Error batch deleting galleries", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * QUERY方法查询画廊列表
     */
    private NanoHTTPD.Response handleQueryList(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            
            // 解析查询参数
            int page = 1;
            int limit = 20;
            String sort = "downloadTime";
            String order = "desc";
            
            // 简单JSON解析
            if (body.contains("\"page\"")) {
                page = extractJsonInt(body, "page", 1);
            }
            if (body.contains("\"limit\"")) {
                limit = extractJsonInt(body, "limit", 20);
            }
            if (body.contains("\"sort\"")) {
                sort = extractJsonString(body, "sort", "downloadTime");
            }
            if (body.contains("\"order\"")) {
                order = extractJsonString(body, "order", "desc");
            }
            
            // 获取所有下载列表
            List<DownloadInfo> allList = downloadManager.getAllDownloadInfoList();
            
            // 过滤
            if (body.contains("\"filter\"")) {
                allList = applyFilters(allList, body);
            }
            
            // 排序
            allList = sortList(allList, sort, order);
            
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
     * 格式化画廊JSON
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
        sb.append(",\"posted\":\"").append(escapeJson(info.posted)).append("\"");
        sb.append(",\"uploader\":\"").append(escapeJson(info.uploader)).append("\"");
        sb.append(",\"rating\":").append(info.rating);
        
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
        
        if (includeDetails) {
            sb.append(",\"finished\":").append(info.finished);
            sb.append(",\"downloaded\":").append(info.downloaded);
            sb.append(",\"total\":").append(info.total);
            sb.append(",\"fileSize\":").append(info.fileSize);
            
            // 简单标签
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
            
            // 标签列表
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
     * 应用过滤器
     */
    private List<DownloadInfo> applyFilters(List<DownloadInfo> list, String body) {
        List<DownloadInfo> filtered = new ArrayList<>();
        
        for (DownloadInfo info : list) {
            boolean match = true;
            
            // 类别过滤
            if (body.contains("\"category\"")) {
                String category = extractJsonString(body, "category", "");
                if (!category.isEmpty() && !getCategoryName(info.category).equals(category)) {
                    match = false;
                }
            }
            
            // 评分过滤
            if (body.contains("\"rating\"")) {
                // 简单实现
            }
            
            // 标签过滤
            if (body.contains("\"tags\"")) {
                // 简单实现
            }
            
            if (match) {
                filtered.add(info);
            }
        }
        
        return filtered;
    }
    
    /**
     * 排序列表
     */
    private List<DownloadInfo> sortList(List<DownloadInfo> list, String sort, String order) {
        List<DownloadInfo> sorted = new ArrayList<>(list);
        
        boolean ascending = "asc".equalsIgnoreCase(order);
        
        switch (sort) {
            case "title":
                sorted.sort((a, b) -> {
                    int cmp = compareStrings(a.title, b.title);
                    return ascending ? cmp : -cmp;
                });
                break;
            case "rating":
                sorted.sort((a, b) -> {
                    int cmp = Float.compare(a.rating, b.rating);
                    return ascending ? cmp : -cmp;
                });
                break;
            case "pages":
                sorted.sort((a, b) -> {
                    int cmp = Integer.compare(a.pages, b.pages);
                    return ascending ? cmp : -cmp;
                });
                break;
            case "downloadTime":
            default:
                sorted.sort((a, b) -> {
                    int cmp = Long.compare(a.time, b.time);
                    return ascending ? cmp : -cmp;
                });
                break;
        }
        
        return sorted;
    }
    
    private int compareStrings(String a, String b) {
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
}
