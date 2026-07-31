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
import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.unifile.UniFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import fi.iki.elonen.NanoHTTPD;

/**
 * 压缩API处理器
 * 支持画廊压缩、压缩包导入
 */
public class CompressApiHandler extends BaseApiHandler {

    private static final String TAG = "CompressApiHandler";

    // 压缩任务存储
    private final ConcurrentHashMap<String, CompressTask> tasks = new ConcurrentHashMap<>();
    // 导入扫描结果缓存
    private final ConcurrentHashMap<String, ImportScanResult> importScans = new ConcurrentHashMap<>();

    public CompressApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);

        if (uri.equals("/api/v1/compress/tasks")) {
            return handleGetTasks(session);
        }
        if (uri.matches("/api/v1/compress/tasks/[^/]+/download")) {
            String taskId = extractTaskId(uri, "/api/v1/compress/tasks/", "/download");
            return handleDownload(session, taskId);
        }
        if (uri.matches("/api/v1/compress/tasks/[^/]+")) {
            String taskId = uri.substring("/api/v1/compress/tasks/".length());
            return handleGetTaskStatus(session, taskId);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri);

        if (uri.equals("/api/v1/compress/create")) {
            return handleCreateTask(session);
        }
        if (uri.equals("/api/v1/compress/import/confirm")) {
            return handleImportConfirm(session);
        }
        if (uri.equals("/api/v1/compress/import")) {
            return handleImportScan(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handleDelete(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("DELETE", uri);

        if (uri.matches("/api/v1/compress/tasks/[^/]+")) {
            String taskId = uri.substring("/api/v1/compress/tasks/".length());
            return handleCancelTask(session, taskId);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    /**
     * 创建压缩任务
     */
    private NanoHTTPD.Response handleCreateTask(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);

            JSONArray gidsArray = json.getJSONArray("gids");
            if (gidsArray == null || gidsArray.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "No GIDs provided");
            }

            List<Long> gids = new ArrayList<>();
            for (int i = 0; i < gidsArray.size(); i++) {
                gids.add(gidsArray.getLongValue(i));
            }

            int splitSizeMB = json.getIntValue("splitSizeMB");
            if (splitSizeMB <= 0) splitSizeMB = 1024;

            boolean includeMetadata = !json.containsKey("includeMetadata") || json.getBooleanValue("includeMetadata");

            String taskId = "compress-" + UUID.randomUUID().toString();
            CompressTask task = new CompressTask();
            task.taskId = taskId;
            task.gids = gids;
            task.splitSizeBytes = (long) splitSizeMB * 1024 * 1024;
            task.includeMetadata = includeMetadata;
            task.status = "pending";
            task.totalGalleries = gids.size();
            task.completedGalleries = 0;
            task.progress = 0;
            task.createdTime = System.currentTimeMillis();
            task.outputFiles = new ArrayList<>();

            tasks.put(taskId, task);

            new Thread(() -> executeCompression(task)).start();

            JSONObject response = new JSONObject();
            response.put("taskId", taskId);
            response.put("status", task.status);
            response.put("totalGalleries", task.totalGalleries);
            response.put("splitSizeMB", splitSizeMB);
            response.put("outputDir", AppConfig.getCompressPlanDir().getAbsolutePath());
            response.put("createdTime", task.createdTime);

            TransferLogger.getInstance().d(TAG, "创建压缩任务: " + taskId + ", " + gids.size() + "个画廊");
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "创建压缩任务失败: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 获取压缩任务列表
     */
    private NanoHTTPD.Response handleGetTasks(NanoHTTPD.IHTTPSession session) {
        try {
            JSONObject response = new JSONObject();
            JSONArray tasksArray = new JSONArray();

            for (CompressTask task : tasks.values()) {
                tasksArray.add(formatTask(task));
            }

            response.put("tasks", tasksArray);
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "获取压缩任务列表失败: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 获取压缩任务状态
     */
    private NanoHTTPD.Response handleGetTaskStatus(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            CompressTask task = tasks.get(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }
            return ResponseBuilder.jsonSuccess(formatTask(task).toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "获取压缩任务状态失败: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 下载压缩包
     */
    private NanoHTTPD.Response handleDownload(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            CompressTask task = tasks.get(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            if (!"completed".equals(task.status)) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Task not completed");
            }

            int part = 1;
            String partParam = session.getParms().get("part");
            if (partParam != null) {
                try { part = Integer.parseInt(partParam); } catch (NumberFormatException e) { /* ignore */ }
            }

            if (part < 1 || part > task.outputFiles.size()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid part number");
            }

            File outputFile = task.outputFiles.get(part - 1);
            if (!outputFile.exists()) {
                return ResponseBuilder.notFound("Output file");
            }

            String rangeHeader = session.getHeaders().get("range");
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                return serveFileWithRange(outputFile, rangeHeader);
            }

            FileInputStream fis = new FileInputStream(outputFile);
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/zip", fis, outputFile.length());
            response.addHeader("Content-Disposition", "attachment; filename=\"" + outputFile.getName() + "\"");
            response.addHeader("Content-Length", String.valueOf(outputFile.length()));
            response.addHeader("Accept-Ranges", "bytes");

            TransferLogger.getInstance().d(TAG, "下载压缩包: " + outputFile.getName());
            return response;

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "下载压缩包失败: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 取消/删除压缩任务
     */
    private NanoHTTPD.Response handleCancelTask(NanoHTTPD.IHTTPSession session, String taskId) {
        try {
            CompressTask task = tasks.get(taskId);
            if (task == null) {
                return ResponseBuilder.notFound("Task");
            }

            if ("in_progress".equals(task.status)) {
                task.status = "cancelled";
            } else {
                tasks.remove(taskId);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Task cancelled");

            TransferLogger.getInstance().d(TAG, "取消压缩任务: " + taskId);
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "取消压缩任务失败: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 上传压缩包扫描
     */
    private NanoHTTPD.Response handleImportScan(NanoHTTPD.IHTTPSession session) {
        try {
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);

            String tempFilePath = files.get("file");
            if (tempFilePath == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "No file uploaded");
            }

            File tempFile = new File(tempFilePath);
            if (!tempFile.exists()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "Upload file not found");
            }

            String importId = "import-" + UUID.randomUUID().toString();
            ImportScanResult scanResult = scanZipFile(tempFile, importId);
            scanResult.tempFile = tempFile;
            importScans.put(importId, scanResult);

            JSONObject response = new JSONObject();
            response.put("importId", importId);
            response.put("fileName", tempFile.getName());
            response.put("fileSize", tempFile.length());
            response.put("totalGalleries", scanResult.galleries.size());

            JSONArray galleriesArray = new JSONArray();
            for (GalleryScanInfo info : scanResult.galleries) {
                JSONObject gallery = new JSONObject();
                gallery.put("folderName", info.folderName);
                gallery.put("gid", info.gid > 0 ? info.gid : null);
                gallery.put("title", info.title);
                gallery.put("hasMetadata", info.hasMetadata);
                gallery.put("fileCount", info.fileCount);
                gallery.put("totalSize", info.totalSize);
                gallery.put("isDuplicate", info.isDuplicate);
                galleriesArray.add(gallery);
            }
            response.put("galleries", galleriesArray);

            JSONArray duplicatesArray = new JSONArray();
            for (GalleryScanInfo info : scanResult.galleries) {
                if (info.isDuplicate) {
                    JSONObject dup = new JSONObject();
                    dup.put("gid", info.gid);
                    dup.put("existingTitle", info.existingTitle);
                    dup.put("newTitle", info.title);
                    duplicatesArray.add(dup);
                }
            }
            response.put("duplicates", duplicatesArray);

            TransferLogger.getInstance().d(TAG, "扫描压缩包: " + scanResult.galleries.size() + "个画廊");
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "扫描压缩包失败: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 确认导入压缩包
     */
    private NanoHTTPD.Response handleImportConfirm(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);

            String importId = json.getString("importId");
            if (importId == null) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "No importId provided");
            }

            ImportScanResult scanResult = importScans.get(importId);
            if (scanResult == null) {
                return ResponseBuilder.notFound("Import session");
            }

            JSONArray skipGidsArray = json.getJSONArray("skipGids");
            JSONArray overwriteGidsArray = json.getJSONArray("overwriteGids");
            String unknownAction = json.getString("unknownAction");
            if (unknownAction == null) unknownAction = "import";

            List<Long> skipGids = new ArrayList<>();
            if (skipGidsArray != null) {
                for (int i = 0; i < skipGidsArray.size(); i++) {
                    skipGids.add(skipGidsArray.getLongValue(i));
                }
            }

            List<Long> overwriteGids = new ArrayList<>();
            if (overwriteGidsArray != null) {
                for (int i = 0; i < overwriteGidsArray.size(); i++) {
                    overwriteGids.add(overwriteGidsArray.getLongValue(i));
                }
            }

            int imported = 0;
            int skipped = 0;
            int failed = 0;
            JSONArray details = new JSONArray();

            UniFile downloadLocation = Settings.getDownloadLocation();
            if (downloadLocation == null) {
                return ResponseBuilder.internalError("Download location not set");
            }

            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(scanResult.tempFile))) {
                ZipEntry entry;
                String currentFolder = null;
                List<FileEntry> currentFiles = new ArrayList<>();

                while ((entry = zis.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    String[] parts = entryName.split("/");

                    if (parts.length >= 2) {
                        String folderName = parts[0];

                        if (currentFolder == null || !currentFolder.equals(folderName)) {
                            if (currentFolder != null) {
                                GalleryScanInfo galleryInfo = findGalleryInfo(scanResult, currentFolder);
                                if (galleryInfo != null) {
                                    JSONObject detail = processGalleryImport(
                                        galleryInfo, currentFiles, downloadLocation,
                                        skipGids, overwriteGids, unknownAction);
                                    details.add(detail);
                                    String status = detail.getString("status");
                                    if ("imported".equals(status)) imported++;
                                    else if ("skipped".equals(status)) skipped++;
                                    else failed++;
                                }
                            }
                            currentFolder = folderName;
                            currentFiles = new ArrayList<>();
                        }

                        if (parts.length > 1 && !entry.isDirectory()) {
                            FileEntry fe = new FileEntry();
                            fe.name = parts[parts.length - 1];
                            fe.size = entry.getSize();
                            fe.data = readZipEntry(zis);
                            currentFiles.add(fe);
                        }
                    }
                    zis.closeEntry();
                }

                if (currentFolder != null) {
                    GalleryScanInfo galleryInfo = findGalleryInfo(scanResult, currentFolder);
                    if (galleryInfo != null) {
                        JSONObject detail = processGalleryImport(
                            galleryInfo, currentFiles, downloadLocation,
                            skipGids, overwriteGids, unknownAction);
                        details.add(detail);
                        String status = detail.getString("status");
                        if ("imported".equals(status)) imported++;
                        else if ("skipped".equals(status)) skipped++;
                        else failed++;
                    }
                }
            }

            scanResult.tempFile.delete();
            importScans.remove(importId);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("imported", imported);
            response.put("skipped", skipped);
            response.put("failed", failed);
            response.put("details", details);

            TransferLogger.getInstance().d(TAG, "导入压缩包: imported=" + imported + ", skipped=" + skipped);
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "导入压缩包失败: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== 压缩执行 ====================

    private void executeCompression(CompressTask task) {
        task.status = "in_progress";
        TransferLogger.getInstance().d(TAG, "开始压缩任务: " + task.taskId);

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String baseName = "ehviewer_" + sdf.format(new Date());

            File outputDir = AppConfig.getCompressPlanDir();
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            List<File> outputFiles = new ArrayList<>();
            ZipOutputStream zos = null;
            long currentPartSize = 0;
            int partIndex = 1;

            for (int i = 0; i < task.gids.size(); i++) {
                if ("cancelled".equals(task.status)) {
                    TransferLogger.getInstance().d(TAG, "压缩任务已取消: " + task.taskId);
                    break;
                }

                long gid = task.gids.get(i);
                DownloadInfo info = downloadManager.getDownloadInfo(gid);
                if (info == null) {
                    TransferLogger.getInstance().w(TAG, "画廊不存在: " + gid);
                    continue;
                }

                UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
                if (downloadDir == null || !downloadDir.isDirectory()) {
                    TransferLogger.getInstance().w(TAG, "下载目录不存在: " + gid);
                    continue;
                }

                if (zos == null || currentPartSize >= task.splitSizeBytes) {
                    if (zos != null) {
                        zos.close();
                    }

                    String fileName;
                    if (task.splitSizeBytes > 0 && task.gids.size() > 1) {
                        fileName = baseName + "-part" + partIndex + ".zip";
                    } else {
                        fileName = baseName + ".zip";
                    }

                    File outputFile = new File(outputDir, fileName);
                    zos = new ZipOutputStream(new FileOutputStream(outputFile));
                    outputFiles.add(outputFile);
                    currentPartSize = 0;
                    partIndex++;
                }

                String galleryFolder = sanitizeFilename(gid + " - " + (info.title != null ? info.title : "Unknown"));
                TransferLogger.getInstance().d(TAG, "压缩画廊: " + galleryFolder);

                if (task.includeMetadata) {
                    addSpiderInfoToZip(zos, downloadDir, galleryFolder);
                }

                UniFile[] files = downloadDir.listFiles();
                if (files != null) {
                    for (UniFile file : files) {
                        if (file.isFile()) {
                            String name = file.getName();
                            if (name != null && !name.startsWith(".")) {
                                String entryName = galleryFolder + "/" + name;
                                addFileToZip(zos, file, entryName);
                                currentPartSize += file.length();
                            }
                        }
                    }
                }

                task.completedGalleries = i + 1;
                task.progress = (double) task.completedGalleries / task.totalGalleries * 100;
            }

            if (zos != null) {
                zos.close();
            }

            if ("cancelled".equals(task.status)) {
                for (File f : outputFiles) {
                    f.delete();
                }
                task.outputFiles = new ArrayList<>();
            } else {
                task.outputFiles = outputFiles;
                task.status = "completed";
                task.completedTime = System.currentTimeMillis();
            }

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "压缩失败: " + e.getMessage());
            task.status = "failed";
        }

        TransferLogger.getInstance().d(TAG, "压缩任务完成: " + task.taskId + ", status=" + task.status);
    }

    private void addSpiderInfoToZip(ZipOutputStream zos, UniFile downloadDir, String galleryFolder) {
        try {
            UniFile spiderInfoFile = downloadDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME);
            if (spiderInfoFile != null && spiderInfoFile.isFile()) {
                String entryName = galleryFolder + "/" + SpiderQueen.SPIDER_INFO_FILENAME;
                zos.putNextEntry(new ZipEntry(entryName));
                InputStream is = spiderInfoFile.openInputStream();
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
                is.close();
                zos.closeEntry();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to add SpiderInfo to zip", e);
        }
    }

    private void addFileToZip(ZipOutputStream zos, UniFile file, String entryName) {
        try {
            zos.putNextEntry(new ZipEntry(entryName));
            InputStream is = file.openInputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
            is.close();
            zos.closeEntry();
        } catch (Exception e) {
            Log.w(TAG, "Failed to add file to zip: " + entryName, e);
        }
    }

    private byte[] readZipEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = zis.read(buffer)) > 0) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    // ==================== 压缩包扫描 ====================

    private ImportScanResult scanZipFile(File zipFile, String importId) throws IOException {
        ImportScanResult result = new ImportScanResult();
        result.importId = importId;
        result.galleries = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            Map<String, GalleryScanInfo> galleryMap = new HashMap<>();

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                String[] parts = entryName.split("/");

                if (parts.length >= 2) {
                    String folderName = parts[0];
                    String fileName = parts[parts.length - 1];

                    GalleryScanInfo info = galleryMap.get(folderName);
                    if (info == null) {
                        info = new GalleryScanInfo();
                        info.folderName = folderName;
                        info.title = extractTitle(folderName);
                        info.gid = extractGid(folderName);
                        info.hasMetadata = false;
                        info.fileCount = 0;
                        info.totalSize = 0;
                        galleryMap.put(folderName, info);
                    }

                    if (!entry.isDirectory()) {
                        info.fileCount++;
                        info.totalSize += entry.getSize();

                        if (SpiderQueen.SPIDER_INFO_FILENAME.equals(fileName)) {
                            info.hasMetadata = true;
                            try {
                                byte[] data = readZipEntry(zis);
                                SpiderInfo spiderInfo = SpiderInfo.read(new ByteArrayInputStream(data));
                                if (spiderInfo != null && spiderInfo.gid > 0) {
                                    info.gid = spiderInfo.gid;
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to read SpiderInfo from zip", e);
                            }
                        }
                    }
                }
                zis.closeEntry();
            }

            for (GalleryScanInfo info : galleryMap.values()) {
                if (info.gid > 0) {
                    DownloadInfo existing = downloadManager.getDownloadInfo(info.gid);
                    if (existing != null) {
                        info.isDuplicate = true;
                        info.existingTitle = existing.title;
                    }
                }
                result.galleries.add(info);
            }
        }

        return result;
    }

    private GalleryScanInfo findGalleryInfo(ImportScanResult scanResult, String folderName) {
        for (GalleryScanInfo info : scanResult.galleries) {
            if (info.folderName.equals(folderName)) {
                return info;
            }
        }
        return null;
    }

    private JSONObject processGalleryImport(
            GalleryScanInfo galleryInfo,
            List<FileEntry> files,
            UniFile downloadLocation,
            List<Long> skipGids,
            List<Long> overwriteGids,
            String unknownAction) {

        JSONObject detail = new JSONObject();
        detail.put("gid", galleryInfo.gid);

        if (galleryInfo.isDuplicate && skipGids.contains(galleryInfo.gid)) {
            detail.put("status", "skipped");
            detail.put("message", "已存在，跳过");
            return detail;
        }

        if (!galleryInfo.hasMetadata || galleryInfo.gid <= 0) {
            if ("skip".equals(unknownAction)) {
                detail.put("status", "skipped");
                detail.put("message", "无元数据，跳过");
                return detail;
            }
            galleryInfo.gid = System.currentTimeMillis();
            detail.put("gid", galleryInfo.gid);
        }

        try {
            String dirName = sanitizeFilename(galleryInfo.gid + "-" + galleryInfo.title);
            UniFile galleryDir = downloadLocation.subFile(dirName);
            if (galleryDir != null) {
                galleryDir.ensureDir();

                for (FileEntry fe : files) {
                    UniFile file = galleryDir.createFile(fe.name);
                    if (file != null) {
                        FileOutputStream fos = (FileOutputStream) file.openOutputStream();
                        fos.write(fe.data);
                        fos.close();
                    }
                }

                DownloadInfo info = new DownloadInfo();
                info.gid = galleryInfo.gid;
                info.title = galleryInfo.title;
                info.pages = galleryInfo.fileCount;
                info.state = DownloadInfo.STATE_FINISH;
                info.time = System.currentTimeMillis();

                if (!galleryInfo.isDuplicate) {
                    EhDB.putDownloadInfo(info);
                }

                EhDB.putDownloadDirname(galleryInfo.gid, dirName);

                detail.put("status", "imported");
                detail.put("message", "导入成功");
            } else {
                detail.put("status", "failed");
                detail.put("message", "无法创建目录");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to import gallery: " + galleryInfo.gid, e);
            detail.put("status", "failed");
            detail.put("message", e.getMessage());
        }

        return detail;
    }

    // ==================== 工具方法 ====================

    private long extractGid(String folderName) {
        int dashIndex = folderName.indexOf(" - ");
        if (dashIndex > 0) {
            try {
                return Long.parseLong(folderName.substring(0, dashIndex));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return -1;
    }

    private String extractTitle(String folderName) {
        int dashIndex = folderName.indexOf(" - ");
        if (dashIndex > 0) {
            return folderName.substring(dashIndex + 3);
        }
        return folderName;
    }

    private String sanitizeFilename(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private String extractTaskId(String uri, String prefix, String suffix) {
        String rest = uri.substring(prefix.length());
        int idx = rest.indexOf('/');
        if (idx > 0) {
            return rest.substring(0, idx);
        }
        return rest;
    }

    private NanoHTTPD.Response serveFileWithRange(File file, String rangeHeader) {
        long fileLength = file.length();
        long start = 0;
        long end = fileLength - 1;

        try {
            String range = rangeHeader.substring(6);
            String[] parts = range.split("-");
            if (!parts[0].isEmpty()) start = Long.parseLong(parts[0]);
            if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);

            if (start >= fileLength) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Range not satisfiable");
            }

            long contentLength = end - start + 1;
            FileInputStream fis = new FileInputStream(file);
            fis.skip(start);

            InputStream rangedStream = new InputStream() {
                private long remaining = contentLength;

                @Override
                public int read() throws IOException {
                    if (remaining <= 0) return -1;
                    int b = fis.read();
                    if (b >= 0) remaining--;
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    int toRead = (int) Math.min(len, remaining);
                    int read = fis.read(b, off, toRead);
                    if (read > 0) remaining -= read;
                    return read;
                }

                @Override
                public void close() throws IOException {
                    fis.close();
                }
            };

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.PARTIAL_CONTENT, "application/zip", rangedStream, contentLength);
            response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Content-Length", String.valueOf(contentLength));

            return response;

        } catch (Exception e) {
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    private JSONObject formatTask(CompressTask task) {
        JSONObject json = new JSONObject();
        json.put("taskId", task.taskId);
        json.put("status", task.status);
        json.put("totalGalleries", task.totalGalleries);
        json.put("completedGalleries", task.completedGalleries);
        json.put("progress", task.progress);
        json.put("createdTime", task.createdTime);
        json.put("completedTime", task.completedTime);

        JSONArray outputFiles = new JSONArray();
        for (File file : task.outputFiles) {
            JSONObject fileInfo = new JSONObject();
            fileInfo.put("name", file.getName());
            fileInfo.put("path", file.getAbsolutePath());
            fileInfo.put("size", file.length());
            fileInfo.put("sizeFormatted", formatSize(file.length()));
            outputFiles.add(fileInfo);
        }
        json.put("outputFiles", outputFiles);

        return json;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ==================== 数据模型 ====================

    private static class CompressTask {
        String taskId;
        List<Long> gids;
        long splitSizeBytes;
        boolean includeMetadata;
        String status;
        int totalGalleries;
        int completedGalleries;
        double progress;
        long createdTime;
        Long completedTime;
        List<File> outputFiles;
    }

    private static class ImportScanResult {
        String importId;
        List<GalleryScanInfo> galleries;
        File tempFile;
    }

    private static class GalleryScanInfo {
        String folderName;
        long gid;
        String title;
        boolean hasMetadata;
        int fileCount;
        long totalSize;
        boolean isDuplicate;
        String existingTitle;
    }

    private static class FileEntry {
        String name;
        long size;
        byte[] data;
    }
}
