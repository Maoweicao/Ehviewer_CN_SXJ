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

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.unifile.UniFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

/**
 * 文件API处理器
 * 支持文件浏览、下载、预览、删除
 */
public class FileApiHandler extends BaseApiHandler {

    private static final String TAG = "FileApiHandler";

    // 支持的文件夹映射
    private static final Map<String, String> FOLDER_MAP = new LinkedHashMap<>();
    static {
        FOLDER_MAP.put("Output", "/sdcard/EhViewer/Output");
        FOLDER_MAP.put("logcat", "/sdcard/EhViewer/logcat");
        FOLDER_MAP.put("logs", "/sdcard/EhViewer/logs");
        FOLDER_MAP.put("data", "/sdcard/EhViewer/data");
        FOLDER_MAP.put("crash", "/sdcard/EhViewer/crash");
        FOLDER_MAP.put("parse_error", "/sdcard/EhViewer/parse_error");
        FOLDER_MAP.put("compress_plan", "/sdcard/EhViewer/CompressPlan");
        FOLDER_MAP.put("progressive_scan", "/sdcard/EhViewer/ProgressiveScan");
        FOLDER_MAP.put("progressive_backup", "/sdcard/EhViewer/ProgressiveBackup");
    }

    // 支持预览的文本文件扩展名
    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "txt", "log", "json", "xml", "html", "htm", "css", "js",
            "java", "kt", "py", "sh", "bat", "cmd", "md", "yml", "yaml",
            "csv", "tsv", "ini", "cfg", "conf", "properties"
    ));

    // 预览最大字符数
    private static final int MAX_PREVIEW_CHARS = 10000;

    public FileApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);

        // GET /api/v1/folders
        if (uri.equals("/api/v1/folders")) {
            return handleFolderList(session);
        }

        // GET /api/v1/folders/{folder}/files
        if (uri.matches("/api/v1/folders/[^/]+/files")) {
            String folder = extractFolder(uri, "/api/v1/folders/", "/files");
            return handleFileList(session, folder);
        }

        // GET /api/v1/folders/{folder}/files/{filename}/preview
        if (uri.matches("/api/v1/folders/[^/]+/files/[^/]+/preview")) {
            String folder = extractFolder(uri, "/api/v1/folders/", "/files");
            String filename = extractFilename(uri);
            return handleFilePreview(session, folder, filename);
        }

        // GET /api/v1/folders/{folder}/files/{filename}
        if (uri.matches("/api/v1/folders/[^/]+/files/[^/]+")) {
            String folder = extractFolder(uri, "/api/v1/folders/", "/files");
            String filename = extractFilename(uri);
            return handleFileDownload(session, folder, filename);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handleDelete(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("DELETE", uri);

        // DELETE /api/v1/folders/{folder}/files/batch
        if (uri.matches("/api/v1/folders/[^/]+/files/batch")) {
            String folder = extractFolder(uri, "/api/v1/folders/", "/files");
            return handleBatchDelete(session, folder);
        }

        // DELETE /api/v1/folders/{folder}/files/{filename}
        if (uri.matches("/api/v1/folders/[^/]+/files/[^/]+")) {
            String folder = extractFolder(uri, "/api/v1/folders/", "/files");
            String filename = extractFilename(uri);
            return handleFileDelete(session, folder, filename);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    /**
     * 获取文件夹列表
     */
    private NanoHTTPD.Response handleFolderList(NanoHTTPD.IHTTPSession session) {
        TransferLogger.getInstance().d(TAG, "获取文件夹列表");

        try {
            JSONArray foldersArray = new JSONArray();

            // 添加已下载画廊文件夹
            JSONObject downloadsFolder = new JSONObject();
            downloadsFolder.put("name", "downloads");
            downloadsFolder.put("path", "galleries");
            
            // 统计已下载画廊数量
            try {
                List<DownloadInfo> downloadList = downloadManager.getAllDownloadInfoList();
                int completedCount = 0;
                for (DownloadInfo info : downloadList) {
                    if (info.state == DownloadInfo.STATE_FINISH) {
                        completedCount++;
                    }
                }
                downloadsFolder.put("fileCount", completedCount);
                downloadsFolder.put("totalSize", 0);
                downloadsFolder.put("totalSizeFormatted", completedCount + " 个画廊");
            } catch (Exception e) {
                TransferLogger.getInstance().e(TAG, "获取下载列表失败: " + e.getMessage());
                downloadsFolder.put("fileCount", 0);
                downloadsFolder.put("totalSize", 0);
                downloadsFolder.put("totalSizeFormatted", "0 个画廊");
            }
            foldersArray.add(downloadsFolder);

            // 添加其他固定文件夹
            for (Map.Entry<String, String> entry : FOLDER_MAP.entrySet()) {
                String name = entry.getKey();
                String path = entry.getValue();
                File dir = new File(path);

                JSONObject folder = new JSONObject();
                folder.put("name", name);
                folder.put("path", path);

                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    int fileCount = 0;
                    long totalSize = 0;
                    if (files != null) {
                        for (File file : files) {
                            if (file.isFile()) {
                                fileCount++;
                                totalSize += file.length();
                            }
                        }
                    }
                    folder.put("fileCount", fileCount);
                    folder.put("totalSize", totalSize);
                    folder.put("totalSizeFormatted", formatSize(totalSize));
                } else {
                    folder.put("fileCount", 0);
                    folder.put("totalSize", 0);
                    folder.put("totalSizeFormatted", "0 B");
                }

                foldersArray.add(folder);
            }

            JSONObject response = new JSONObject();
            response.put("folders", foldersArray);

            TransferLogger.getInstance().d(TAG, "文件夹列表: " + foldersArray.size() + "个");
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "获取文件夹列表失败", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 获取已下载画廊列表
     */
    private NanoHTTPD.Response handleDownloadedGalleries(NanoHTTPD.IHTTPSession session) {
        TransferLogger.getInstance().d(TAG, "获取已下载画廊列表");

        try {
            // 获取参数
            int page = RequestParser.getIntQueryParameter(session, "page", 1);
            int limit = RequestParser.getIntQueryParameter(session, "limit", 50);
            String sort = RequestParser.getQueryParameter(session, "sort", "time");
            String order = RequestParser.getQueryParameter(session, "order", "desc");
            String search = RequestParser.getQueryParameter(session, "search", "");

            TransferLogger.getInstance().d(TAG, "参数: page=" + page + ", limit=" + limit + 
                ", sort=" + sort + ", order=" + order + ", search=" + search);

            // 获取下载列表
            List<DownloadInfo> allList = downloadManager.getAllDownloadInfoList();

            // 过滤已完成的画廊
            List<DownloadInfo> completedList = new ArrayList<>();
            for (DownloadInfo info : allList) {
                if (info.state == DownloadInfo.STATE_FINISH) {
                    if (search.isEmpty() || 
                        (info.title != null && info.title.toLowerCase().contains(search.toLowerCase())) ||
                        (info.titleJpn != null && info.titleJpn.toLowerCase().contains(search.toLowerCase()))) {
                        completedList.add(info);
                    }
                }
            }

            // 排序
            sortDownloadInfo(completedList, sort, order);

            // 分页
            int total = completedList.size();
            int startIndex = (page - 1) * limit;
            int endIndex = Math.min(startIndex + limit, total);

            if (startIndex >= total) {
                startIndex = 0;
                endIndex = 0;
            }

            List<DownloadInfo> pageList = completedList.subList(startIndex, endIndex);

            // 构建响应
            JSONObject response = new JSONObject();
            response.put("folder", "downloads");
            response.put("path", "galleries");
            response.put("total", total);
            response.put("page", page);
            response.put("limit", limit);
            response.put("sort", sort);
            response.put("order", order);

            JSONArray filesArray = new JSONArray();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            for (DownloadInfo info : pageList) {
                JSONObject fileObj = new JSONObject();
                
                // 获取下载目录
                UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
                String dirPath = downloadDir != null ? downloadDir.getUri().toString() : "";
                
                // 统计文件数量和大小
                int fileCount = 0;
                long totalSize = 0;
                String lastModifiedStr = "";
                
                if (downloadDir != null && downloadDir.isDirectory()) {
                    try {
                        UniFile[] files = downloadDir.listFiles();
                        if (files != null) {
                            fileCount = files.length;
                            for (UniFile f : files) {
                                if (f.isFile()) {
                                    totalSize += f.length();
                                }
                            }
                        }
                        
                        // 获取最后修改时间
                        long lastModified = downloadDir.lastModified();
                        if (lastModified > 0) {
                            lastModifiedStr = sdf.format(new Date(lastModified));
                        }
                    } catch (Exception e) {
                        TransferLogger.getInstance().e(TAG, "统计文件失败: " + info.gid + " - " + e.getMessage());
                    }
                }

                fileObj.put("name", info.gid + " - " + (info.title != null ? info.title : "Unknown"));
                fileObj.put("path", dirPath);
                fileObj.put("gid", info.gid);
                fileObj.put("title", info.title);
                fileObj.put("titleJpn", info.titleJpn);
                fileObj.put("thumb", info.thumb);
                fileObj.put("category", info.category);
                fileObj.put("pages", info.pages);
                fileObj.put("fileCount", fileCount);
                fileObj.put("size", totalSize);
                fileObj.put("sizeFormatted", formatSize(totalSize));
                fileObj.put("lastModified", info.time);
                fileObj.put("lastModifiedFormatted", lastModifiedStr.isEmpty() ? sdf.format(new Date(info.time)) : lastModifiedStr);
                fileObj.put("extension", "folder");
                fileObj.put("isGallery", true);
                filesArray.add(fileObj);
            }

            response.put("files", filesArray);

            TransferLogger.getInstance().d(TAG, "已下载画廊: " + total + "个, 返回" + pageList.size() + "个");
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "获取已下载画廊列表失败", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 排序DownloadInfo列表
     */
    private void sortDownloadInfo(List<DownloadInfo> list, String sort, String order) {
        if ("name".equals(sort)) {
            list.sort((a, b) -> {
                String titleA = a.title != null ? a.title : "";
                String titleB = b.title != null ? b.title : "";
                int cmp = titleA.compareToIgnoreCase(titleB);
                return "asc".equals(order) ? cmp : -cmp;
            });
        } else if ("size".equals(sort)) {
            list.sort((a, b) -> {
                int cmp = Long.compare(a.fileSize, b.fileSize);
                return "asc".equals(order) ? cmp : -cmp;
            });
        } else {
            // 默认按时间排序
            list.sort((a, b) -> {
                int cmp = Long.compare(a.time, b.time);
                return "asc".equals(order) ? cmp : -cmp;
            });
        }
    }

    /**
     * 获取文件列表
     */
    private NanoHTTPD.Response handleFileList(NanoHTTPD.IHTTPSession session, String folder) {
        TransferLogger.getInstance().d(TAG, "获取文件列表: folder=" + folder);

        // 处理已下载画廊列表
        if ("downloads".equals(folder)) {
            return handleDownloadedGalleries(session);
        }

        String folderPath = FOLDER_MAP.get(folder);
        if (folderPath == null) {
            TransferLogger.getInstance().w(TAG, "文件夹不存在: " + folder + " (可用: " + FOLDER_MAP.keySet() + ")");
            return ResponseBuilder.notFound("Folder");
        }

        File dir = new File(folderPath);
        if (!dir.exists() || !dir.isDirectory()) {
            TransferLogger.getInstance().w(TAG, "文件夹路径不存在: " + folderPath);
            return ResponseBuilder.notFound("Folder");
        }

        try {
            // 获取参数
            int page = RequestParser.getIntQueryParameter(session, "page", 1);
            int limit = RequestParser.getIntQueryParameter(session, "limit", 50);
            String sort = RequestParser.getQueryParameter(session, "sort", "time");
            String order = RequestParser.getQueryParameter(session, "order", "desc");
            String search = RequestParser.getQueryParameter(session, "search", "");

            TransferLogger.getInstance().d(TAG, "参数: page=" + page + ", limit=" + limit + 
                ", sort=" + sort + ", order=" + order + ", search=" + search);

            // 列出文件
            File[] files = dir.listFiles();
            if (files == null) files = new File[0];

            // 过滤（只保留文件）
            List<File> fileList = new ArrayList<>();
            for (File file : files) {
                if (file.isFile()) {
                    if (search.isEmpty() || file.getName().toLowerCase().contains(search.toLowerCase())) {
                        fileList.add(file);
                    }
                }
            }

            // 排序
            sortFiles(fileList, sort, order);

            // 分页
            int total = fileList.size();
            int startIndex = (page - 1) * limit;
            int endIndex = Math.min(startIndex + limit, total);

            if (startIndex >= total) {
                startIndex = 0;
                endIndex = 0;
            }

            List<File> pageFiles = fileList.subList(startIndex, endIndex);

            // 构建响应
            JSONObject response = new JSONObject();
            response.put("folder", folder);
            response.put("path", folderPath);
            response.put("total", total);
            response.put("page", page);
            response.put("limit", limit);
            response.put("sort", sort);
            response.put("order", order);

            JSONArray filesArray = new JSONArray();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            for (File file : pageFiles) {
                JSONObject fileObj = new JSONObject();
                fileObj.put("name", file.getName());
                fileObj.put("path", file.getAbsolutePath());
                fileObj.put("size", file.length());
                fileObj.put("sizeFormatted", formatSize(file.length()));
                fileObj.put("lastModified", file.lastModified());
                fileObj.put("lastModifiedFormatted", sdf.format(new Date(file.lastModified())));
                fileObj.put("extension", getExtension(file.getName()));
                filesArray.add(fileObj);
            }

            response.put("files", filesArray);

            TransferLogger.getInstance().d(TAG, "文件列表: " + total + "个文件, 返回" + pageFiles.size() + "个");
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "获取文件列表失败", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 下载文件（支持断点续传）
     */
    private NanoHTTPD.Response handleFileDownload(NanoHTTPD.IHTTPSession session, String folder, String filename) {
        TransferLogger.getInstance().d(TAG, "下载文件: " + folder + "/" + filename);

        // 处理已下载画廊中的文件
        if ("downloads".equals(folder)) {
            return handleGalleryFileDownload(session, filename);
        }

        String folderPath = FOLDER_MAP.get(folder);
        if (folderPath == null) {
            return ResponseBuilder.notFound("Folder");
        }

        File file = new File(folderPath, filename);
        if (!file.exists() || !file.isFile()) {
            return ResponseBuilder.notFound("File");
        }

        try {
            String rangeHeader = session.getHeaders().get("range");
            return serveFileWithRange(file, rangeHeader);
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "下载文件失败", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 从画廊目录下载文件
     * filename格式: {gid}/{filename}
     */
    private NanoHTTPD.Response handleGalleryFileDownload(NanoHTTPD.IHTTPSession session, String filename) {
        TransferLogger.getInstance().d(TAG, "下载画廊文件: " + filename);

        try {
            // 解析 gid 和实际文件名
            int slashIndex = filename.indexOf('/');
            if (slashIndex < 0) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, 
                    "Invalid filename format. Use: {gid}/{filename}");
            }

            long gid;
            try {
                gid = Long.parseLong(filename.substring(0, slashIndex));
            } catch (NumberFormatException e) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid GID");
            }

            String actualFilename = filename.substring(slashIndex + 1);

            // 获取下载信息
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (info == null) {
                return ResponseBuilder.notFound("Gallery");
            }

            // 获取下载目录
            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            if (downloadDir == null || !downloadDir.isDirectory()) {
                return ResponseBuilder.notFound("Download directory");
            }

            // 查找文件
            UniFile file = downloadDir.findFile(actualFilename);
            if (file == null || !file.isFile()) {
                return ResponseBuilder.notFound("File");
            }

            // 获取文件MIME类型
            String mimeType = getMimeType(actualFilename);
            long length = file.length();

            // 处理Range请求
            String rangeHeader = session.getHeaders().get("range");
            if (rangeHeader != null && !rangeHeader.isEmpty()) {
                return serveUniFileWithRange(file, rangeHeader, mimeType, length);
            }

            // 普通下载
            InputStream stream = file.openInputStream();
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                mimeType,
                stream,
                length
            );

            response.addHeader("Content-Disposition", "attachment; filename=\"" + actualFilename + "\"");
            response.addHeader("Accept-Ranges", "bytes");

            return response;

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "下载画廊文件失败", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 提供UniFile的Range请求支持
     */
    private NanoHTTPD.Response serveUniFileWithRange(UniFile file, String rangeHeader, String mimeType, long totalSize) {
        try {
            // 解析Range: bytes=start-end
            long start = 0;
            long end = totalSize - 1;

            if (rangeHeader.startsWith("bytes=")) {
                String range = rangeHeader.substring(6);
                String[] parts = range.split("-");
                if (parts.length == 2) {
                    if (!parts[0].isEmpty()) start = Long.parseLong(parts[0]);
                    if (!parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                }
            }

            if (start > end || start >= totalSize) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE,
                    "text/plain",
                    "Range not satisfiable"
                );
            }

            long contentLength = end - start + 1;

            // 读取指定范围的数据
            InputStream stream = file.openInputStream();
            if (start > 0) {
                stream.skip(start);
            }

            // 使用有限长度的流
            InputStream rangedStream = new InputStream() {
                private long remaining = contentLength;
                
                @Override
                public int read() throws IOException {
                    if (remaining <= 0) return -1;
                    int b = stream.read();
                    if (b >= 0) remaining--;
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    int toRead = (int) Math.min(len, remaining);
                    int read = stream.read(b, off, toRead);
                    if (read > 0) remaining -= read;
                    return read;
                }

                @Override
                public void close() throws IOException {
                    stream.close();
                }
            };

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                mimeType,
                rangedStream,
                contentLength
            );

            response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + totalSize);
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Content-Length", String.valueOf(contentLength));

            return response;

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Range请求失败", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 预览文件
     */
    private NanoHTTPD.Response handleFilePreview(NanoHTTPD.IHTTPSession session, String folder, String filename) {
        TransferLogger.getInstance().d(TAG, "预览文件: " + folder + "/" + filename);

        String folderPath = FOLDER_MAP.get(folder);
        if (folderPath == null) {
            return ResponseBuilder.notFound("Folder");
        }

        File file = new File(folderPath, filename);
        if (!file.exists() || !file.isFile()) {
            return ResponseBuilder.notFound("File");
        }

        try {
            String ext = getExtension(filename).toLowerCase();
            boolean isText = TEXT_EXTENSIONS.contains(ext);

            JSONObject response = new JSONObject();
            response.put("name", filename);
            response.put("size", file.length());
            response.put("sizeFormatted", formatSize(file.length()));
            response.put("type", isText ? "text" : "binary");

            if (isText) {
                String content = readFileContent(file, MAX_PREVIEW_CHARS);
                response.put("content", content);
                response.put("truncated", file.length() > MAX_PREVIEW_CHARS);
            } else {
                response.put("content", null);
                response.put("truncated", false);
            }

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "预览文件失败", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 删除文件
     */
    private NanoHTTPD.Response handleFileDelete(NanoHTTPD.IHTTPSession session, String folder, String filename) {
        TransferLogger.getInstance().d(TAG, "删除文件: " + folder + "/" + filename);

        String folderPath = FOLDER_MAP.get(folder);
        if (folderPath == null) {
            return ResponseBuilder.notFound("Folder");
        }

        File file = new File(folderPath, filename);
        if (!file.exists() || !file.isFile()) {
            return ResponseBuilder.notFound("File");
        }

        try {
            boolean deleted = file.delete();
            if (deleted) {
                TransferLogger.getInstance().i(TAG, "文件已删除: " + filename);
                return ResponseBuilder.jsonSuccess("{\"success\":true,\"message\":\"File deleted\"}");
            } else {
                return ResponseBuilder.internalError("Failed to delete file");
            }
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "删除文件失败", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 批量删除文件
     */
    private NanoHTTPD.Response handleBatchDelete(NanoHTTPD.IHTTPSession session, String folder) {
        TransferLogger.getInstance().d(TAG, "批量删除文件: " + folder);

        String folderPath = FOLDER_MAP.get(folder);
        if (folderPath == null) {
            return ResponseBuilder.notFound("Folder");
        }

        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);
            JSONArray filesArray = json.getJSONArray("files");

            if (filesArray == null || filesArray.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "No files specified");
            }

            int deleted = 0;
            int failed = 0;

            for (int i = 0; i < filesArray.size(); i++) {
                String filename = filesArray.getString(i);
                File file = new File(folderPath, filename);
                if (file.exists() && file.isFile()) {
                    if (file.delete()) deleted++;
                    else failed++;
                } else {
                    failed++;
                }
            }

            TransferLogger.getInstance().i(TAG, "批量删除: " + deleted + "成功, " + failed + "失败");

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("deleted", deleted);
            response.put("failed", failed);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "批量删除失败", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 带Range的文件服务（断点续传）
     */
    private NanoHTTPD.Response serveFileWithRange(File file, String rangeHeader) {
        long fileLength = file.length();

        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            try {
                InputStream stream = new FileInputStream(file);
                NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK,
                        "application/octet-stream",
                        stream,
                        fileLength
                );
                response.addHeader("Accept-Ranges", "bytes");
                response.addHeader("Content-Disposition",
                        "attachment; filename=\"" + file.getName() + "\"");
                return response;
            } catch (Exception e) {
                return ResponseBuilder.internalError(e.getMessage());
            }
        }

        try {
            String range = rangeHeader.substring(6);
            String[] parts = range.split("-");
            long start = Long.parseLong(parts[0]);
            long end = parts.length > 1 && !parts[1].isEmpty()
                    ? Long.parseLong(parts[1])
                    : fileLength - 1;

            if (start >= fileLength) {
                return NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE,
                        "text/plain",
                        "Range not satisfiable"
                );
            }
            if (end >= fileLength) end = fileLength - 1;

            long length = end - start + 1;

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.seek(start);
            byte[] buffer = new byte[(int) length];
            raf.read(buffer);
            raf.close();

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                    "application/octet-stream",
                    new ByteArrayInputStream(buffer),
                    length
            );
            response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Content-Disposition",
                    "attachment; filename=\"" + file.getName() + "\"");

            return response;

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Range处理失败", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 读取文件内容
     */
    private String readFileContent(File file, int maxChars) {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            int total = 0;

            while ((read = reader.read(buffer)) > 0) {
                if (total + read > maxChars) {
                    sb.append(buffer, 0, maxChars - total);
                    break;
                }
                sb.append(buffer, 0, read);
                total += read;
            }

            reader.close();
            return sb.toString();

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "读取文件失败", e);
            return "";
        }
    }

    /**
     * 排序文件列表
     */
    private void sortFiles(List<File> files, String sort, String order) {
        Comparator<File> comparator;

        switch (sort) {
            case "name":
                comparator = Comparator.comparing(File::getName);
                break;
            case "size":
                comparator = Comparator.comparingLong(File::length);
                break;
            case "time":
            default:
                comparator = Comparator.comparingLong(File::lastModified);
                break;
        }

        if ("asc".equals(order)) {
            files.sort(comparator);
        } else {
            files.sort(comparator.reversed());
        }
    }

    /**
     * 从URI中提取文件夹名
     * /api/v1/folders/{folder}/files -> {folder}
     */
    private String extractFolder(String uri, String prefix, String suffix) {
        // /api/v1/folders/Output/files -> Output
        if (uri.startsWith(prefix)) {
            String rest = uri.substring(prefix.length());
            int idx = rest.indexOf('/');
            if (idx > 0) {
                return rest.substring(0, idx);
            }
            return rest;
        }
        return "";
    }

    /**
     * 从URI中提取文件名
     * /api/v1/folders/{folder}/files/{filename}
     */
    private String extractFilename(String uri) {
        String[] parts = uri.split("/");
        // /api/v1/folders/{folder}/files/{filename}
        // 0  1  2  3        4       5     6
        if (parts.length >= 7) {
            return parts[6];
        }
        return "";
    }

    /**
     * 获取文件扩展名
     */
    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot >= 0) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    /**
     * 格式化文件大小
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /**
     * 根据文件名获取MIME类型
     */
    private String getMimeType(String filename) {
        String ext = getExtension(filename).toLowerCase();
        switch (ext) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "svg":
                return "image/svg+xml";
            case "bmp":
                return "image/bmp";
            case "txt":
                return "text/plain";
            case "html":
            case "htm":
                return "text/html";
            case "css":
                return "text/css";
            case "js":
                return "application/javascript";
            case "json":
                return "application/json";
            case "xml":
                return "application/xml";
            case "pdf":
                return "application/pdf";
            case "zip":
                return "application/zip";
            case "rar":
                return "application/x-rar-compressed";
            case "7z":
                return "application/x-7z-compressed";
            case "mp3":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            case "mp4":
                return "video/mp4";
            case "avi":
                return "video/x-msvideo";
            case "mkv":
                return "video/x-matroska";
            default:
                return "application/octet-stream";
        }
    }
}
