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
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.ResponseCache;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.unifile.UniFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;

/**
 * Data Export/Import API Handler
 * Supports bookmarks, favorites, downloads, database, CSV export/import
 */
public class DataApiHandler extends BaseApiHandler {

    private static final String TAG = "DataApiHandler";

    public DataApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);

        if (uri.equals("/api/v1/data/export/files")) {
            return handleGetExportFiles(session);
        }
        if (uri.equals("/api/v1/data/export/bookmarks")) {
            return handleExportBookmarks(session);
        }
        if (uri.equals("/api/v1/data/export/favorites")) {
            return handleExportFavorites(session);
        }
        if (uri.equals("/api/v1/data/export/downloads")) {
            return handleExportDownloads(session);
        }
        if (uri.equals("/api/v1/data/export/db")) {
            return handleExportDB(session);
        }
        if (uri.equals("/api/v1/data/export/csv")) {
            return handleExportCSV(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri);

        if (uri.equals("/api/v1/data/import/bookmarks")) {
            return handleImportBookmarks(session);
        }
        if (uri.equals("/api/v1/data/import/favorites")) {
            return handleImportFavorites(session);
        }
        if (uri.equals("/api/v1/data/import/downloads")) {
            return handleImportDownloads(session);
        }
        if (uri.equals("/api/v1/data/import/db")) {
            return handleImportDB(session);
        }
        if (uri.equals("/api/v1/data/import/csv")) {
            return handleImportCSV(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    /**
     * Get exportable file list
     */
    private NanoHTTPD.Response handleGetExportFiles(NanoHTTPD.IHTTPSession session) {
        try {
            // Check cache
            String cacheKey = "export_files";
            String cached = ResponseCache.getInstance().get(cacheKey);
            if (cached != null) {
                TransferLogger.getInstance().d(TAG, "Cache hit for export files");
                return ResponseBuilder.jsonSuccess(cached);
            }

            JSONObject response = new JSONObject();
            JSONArray dbFiles = new JSONArray();
            JSONArray csvFiles = new JSONArray();

            File dataDir = AppConfig.getExternalDataDir();
            if (dataDir != null && dataDir.exists() && dataDir.isDirectory()) {
                File[] files = dataDir.listFiles();
                if (files != null) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    for (File file : files) {
                        JSONObject fileInfo = new JSONObject();
                        fileInfo.put("name", file.getName());
                        fileInfo.put("path", file.getAbsolutePath());
                        fileInfo.put("size", file.length());
                        fileInfo.put("sizeFormatted", formatSize(file.length()));
                        fileInfo.put("lastModified", file.lastModified());
                        fileInfo.put("lastModifiedFormatted", sdf.format(new Date(file.lastModified())));

                        if (file.getName().endsWith(".db")) {
                            fileInfo.put("type", "db");
                            dbFiles.add(fileInfo);
                        } else if (file.getName().endsWith(".csv")) {
                            fileInfo.put("type", "csv");
                            csvFiles.add(fileInfo);
                        }
                    }
                }
            }

            response.put("dbFiles", dbFiles);
            response.put("csvFiles", csvFiles);

            String responseJson = response.toJSONString();
            
            // Store in cache
            ResponseCache.getInstance().put(cacheKey, responseJson);

            TransferLogger.getInstance().d(TAG, "Export files: db=" + dbFiles.size() + ", csv=" + csvFiles.size());
            return ResponseBuilder.jsonSuccess(responseJson);

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Get export files failed: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * Export bookmarks data
     */
    private NanoHTTPD.Response handleExportBookmarks(NanoHTTPD.IHTTPSession session) {
        try {
            // Check cache
            String cacheKey = "export_bookmarks";
            String cached = ResponseCache.getInstance().get(cacheKey);
            if (cached != null) {
                TransferLogger.getInstance().d(TAG, "Cache hit for export bookmarks");
                return ResponseBuilder.jsonSuccess(cached);
            }

            List<com.hippo.ehviewer.client.data.GalleryInfo> bookmarks = EhDB.getAllLocalFavorites();

            JSONObject response = new JSONObject();
            response.put("type", "bookmarks");
            response.put("exportTime", System.currentTimeMillis());
            response.put("total", bookmarks.size());

            JSONArray items = new JSONArray();
            for (com.hippo.ehviewer.client.data.GalleryInfo info : bookmarks) {
                JSONObject item = new JSONObject();
                item.put("gid", info.gid);
                item.put("token", info.token);
                item.put("title", info.title);
                item.put("titleJpn", info.titleJpn);
                item.put("thumb", info.thumb);
                item.put("category", getCategoryName(info.category));
                item.put("posted", info.posted != null ? info.posted : "");
                item.put("uploader", info.uploader != null ? info.uploader : "");
                item.put("rating", info.rating);
                item.put("pages", info.pages);
                if (info.simpleLanguage != null) {
                    item.put("simpleLanguage", info.simpleLanguage);
                }
                items.add(item);
            }
            response.put("items", items);

            String responseJson = response.toJSONString();
            
            // Store in cache
            ResponseCache.getInstance().put(cacheKey, responseJson);

            TransferLogger.getInstance().d(TAG, "Export bookmarks: " + bookmarks.size());
            return ResponseBuilder.jsonSuccess(responseJson);

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Export bookmarks failed: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * Export favorites data (local favorites)
     */
    private NanoHTTPD.Response handleExportFavorites(NanoHTTPD.IHTTPSession session) {
        try {
            List<com.hippo.ehviewer.client.data.GalleryInfo> favorites = EhDB.getAllLocalFavorites();

            JSONObject response = new JSONObject();
            response.put("type", "favorites");
            response.put("exportTime", System.currentTimeMillis());
            response.put("total", favorites.size());

            JSONArray catNames = new JSONArray();
            JSONArray catCounts = new JSONArray();
            // Local favorites don't have categories, use a single "默认" category
            catNames.add("默认");
            catCounts.add(favorites.size());
            response.put("catNames", catNames);
            response.put("catCounts", catCounts);

            JSONArray items = new JSONArray();
            for (com.hippo.ehviewer.client.data.GalleryInfo info : favorites) {
                JSONObject item = new JSONObject();
                item.put("gid", info.gid);
                item.put("token", info.token);
                item.put("title", info.title);
                item.put("titleJpn", info.titleJpn);
                item.put("thumb", info.thumb);
                item.put("favCat", "默认");
                item.put("favNote", "");
                items.add(item);
            }
            response.put("items", items);

            TransferLogger.getInstance().d(TAG, "Export favorites: " + favorites.size());
            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Export favorites failed: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * Export downloads data
     */
    private NanoHTTPD.Response handleExportDownloads(NanoHTTPD.IHTTPSession session) {
        try {
            // Check cache
            String cacheKey = "export_downloads";
            String cached = ResponseCache.getInstance().get(cacheKey);
            if (cached != null) {
                TransferLogger.getInstance().d(TAG, "Cache hit for export downloads");
                return ResponseBuilder.jsonSuccess(cached);
            }

            List<DownloadInfo> allDownloads = downloadManager.getAllDownloadInfoList();

            JSONObject response = new JSONObject();
            response.put("type", "downloads");
            response.put("exportTime", System.currentTimeMillis());
            response.put("total", allDownloads.size());

            JSONArray items = new JSONArray();
            for (DownloadInfo info : allDownloads) {
                JSONObject item = new JSONObject();
                item.put("gid", info.gid);
                item.put("token", info.token);
                item.put("title", info.title);
                item.put("titleJpn", info.titleJpn);
                item.put("thumb", info.thumb);
                item.put("category", info.category);
                item.put("posted", info.posted);
                item.put("uploader", info.uploader);
                item.put("rating", info.rating);
                item.put("simpleLanguage", info.simpleLanguage);
                if (info.simpleTags != null) {
                    item.put("simpleTags", JSONArray.parseArray(JSON.toJSONString(info.simpleTags)));
                }
                item.put("pages", info.pages);
                item.put("state", info.state);
                item.put("legacy", info.legacy);
                item.put("time", info.time);
                item.put("label", info.label);
                items.add(item);
            }
            response.put("items", items);

            String responseJson = response.toJSONString();
            
            // Store in cache
            ResponseCache.getInstance().put(cacheKey, responseJson);

            TransferLogger.getInstance().d(TAG, "Export downloads: " + allDownloads.size());
            return ResponseBuilder.jsonSuccess(responseJson);

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Export downloads failed: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * Export database file
     */
    private NanoHTTPD.Response handleExportDB(NanoHTTPD.IHTTPSession session) {
        try {
            File exportDir = AppConfig.getExternalDataDir();
            if (exportDir != null && !exportDir.exists()) {
                exportDir.mkdirs();
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String fileName = "ehviewer_export_" + sdf.format(new Date()) + ".db";
            File exportFile = new File(exportDir, fileName);

            boolean success = EhDB.exportDB(context, exportFile);
            if (!success || !exportFile.exists()) {
                return ResponseBuilder.internalError("Failed to export database");
            }

            FileInputStream fis = new FileInputStream(exportFile);
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/octet-stream", fis, exportFile.length());
            response.addHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            response.addHeader("Content-Length", String.valueOf(exportFile.length()));

            TransferLogger.getInstance().d(TAG, "Export DB: " + fileName + " (" + formatSize(exportFile.length()) + ")");
            return response;

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Export DB failed: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * Export CSV file
     */
    private NanoHTTPD.Response handleExportCSV(NanoHTTPD.IHTTPSession session) {
        try {
            List<DownloadInfo> allDownloads = downloadManager.getAllDownloadInfoList();

            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String fileName = "ehviewer-download-" + sdf.format(new Date()) + ".csv";

            StringBuilder csv = new StringBuilder();
            csv.append("GID,Token,Title,TitleJpn,Category,Posted,Uploader,Rating,Pages,State,Label,Time\n");

            for (DownloadInfo info : allDownloads) {
                csv.append(info.gid).append(",");
                csv.append(escapeCsv(info.token)).append(",");
                csv.append(escapeCsv(info.title)).append(",");
                csv.append(escapeCsv(info.titleJpn)).append(",");
                csv.append(info.category).append(",");
                csv.append(escapeCsv(info.posted)).append(",");
                csv.append(escapeCsv(info.uploader)).append(",");
                csv.append(info.rating).append(",");
                csv.append(info.pages).append(",");
                csv.append(info.state).append(",");
                csv.append(escapeCsv(info.label)).append(",");
                csv.append(info.time).append("\n");
            }

            byte[] data = csv.toString().getBytes("UTF-8");
            ByteArrayInputStream bais = new ByteArrayInputStream(data);

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "text/csv; charset=utf-8", bais, data.length);
            response.addHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            response.addHeader("Content-Length", String.valueOf(data.length));

            TransferLogger.getInstance().d(TAG, "Export CSV: " + fileName + " (" + allDownloads.size() + " items)");
            return response;

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Export CSV failed: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * Import bookmarks data
     */
    private NanoHTTPD.Response handleImportBookmarks(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);
            String mergeMode = json.getString("mergeMode");
            if (mergeMode == null) mergeMode = "skip";

            JSONArray items = json.getJSONArray("items");
            if (items == null || items.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "No items provided");
            }

            int imported = 0;
            int skipped = 0;
            int failed = 0;
            JSONArray conflicts = new JSONArray();

            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                long gid = item.getLongValue("gid");

                boolean exists = EhDB.containLocalFavorites(gid);
                if (exists) {
                    if ("skip".equals(mergeMode)) {
                        skipped++;
                        continue;
                    } else if ("ask".equals(mergeMode)) {
                        JSONObject conflict = new JSONObject();
                        conflict.put("gid", gid);
                        conflict.put("existingTitle", "Existing favorite");
                        conflict.put("newTitle", item.getString("title"));
                        conflicts.add(conflict);
                        continue;
                    }
                }

                com.hippo.ehviewer.client.data.GalleryInfo info = new com.hippo.ehviewer.client.data.GalleryInfo();
                info.gid = gid;
                info.token = item.getString("token");
                info.title = item.getString("title");
                info.titleJpn = item.getString("titleJpn");
                info.thumb = item.getString("thumb");
                info.category = parseCategory(item.getString("category"));
                info.posted = item.getString("posted");
                info.uploader = item.getString("uploader");
                info.rating = item.getFloatValue("rating");
                info.pages = item.getIntValue("pages");

                try {
                    EhDB.putLocalFavorite(info);
                    imported++;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to import bookmark: " + gid, e);
                    failed++;
                }
            }

            if ("ask".equals(mergeMode) && !conflicts.isEmpty()) {
                JSONObject result = new JSONObject();
                result.put("success", false);
                result.put("conflicts", conflicts);
                return ResponseBuilder.jsonSuccess(result.toJSONString());
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("imported", imported);
            result.put("skipped", skipped);
            result.put("failed", failed);

            // Invalidate export caches after import
            ResponseCache.getInstance().invalidateByPrefix("export_");
            ResponseCache.getInstance().invalidateGalleries();

            TransferLogger.getInstance().d(TAG, "Import bookmarks: imported=" + imported + ", skipped=" + skipped);
            return ResponseBuilder.jsonSuccess(result.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Import bookmarks failed: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * Import favorites data
     */
    private NanoHTTPD.Response handleImportFavorites(NanoHTTPD.IHTTPSession session) {
        return handleImportBookmarks(session);
    }

    /**
     * Import downloads data
     */
    private NanoHTTPD.Response handleImportDownloads(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);
            String mergeMode = json.getString("mergeMode");
            if (mergeMode == null) mergeMode = "skip";

            JSONArray items = json.getJSONArray("items");
            if (items == null || items.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "No items provided");
            }

            int imported = 0;
            int skipped = 0;
            int failed = 0;

            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                long gid = item.getLongValue("gid");

                DownloadInfo existing = downloadManager.getDownloadInfo(gid);
                if (existing != null && "skip".equals(mergeMode)) {
                    skipped++;
                    continue;
                }

                DownloadInfo info = new DownloadInfo();
                info.gid = gid;
                info.token = item.getString("token");
                info.title = item.getString("title");
                info.titleJpn = item.getString("titleJpn");
                info.thumb = item.getString("thumb");
                info.category = item.getIntValue("category");
                info.posted = item.getString("posted");
                info.uploader = item.getString("uploader");
                info.rating = item.getFloatValue("rating");
                info.simpleLanguage = item.getString("simpleLanguage");
                info.pages = item.getIntValue("pages");
                info.state = item.getIntValue("state");
                info.legacy = item.getIntValue("legacy");
                info.time = item.getLongValue("time");
                info.label = item.getString("label");

                try {
                    EhDB.putDownloadInfo(info);
                    imported++;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to import download: " + gid, e);
                    failed++;
                }
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("imported", imported);
            result.put("skipped", skipped);
            result.put("failed", failed);

            // Invalidate export caches after import
            ResponseCache.getInstance().invalidateByPrefix("export_");
            ResponseCache.getInstance().invalidateGalleries();

            TransferLogger.getInstance().d(TAG, "Import downloads: imported=" + imported + ", skipped=" + skipped);
            return ResponseBuilder.jsonSuccess(result.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Import downloads failed: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * Import database file
     */
    private NanoHTTPD.Response handleImportDB(NanoHTTPD.IHTTPSession session) {
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

            String error = EhDB.importDB(context, tempFile, null);

            tempFile.delete();

            if (error != null) {
                return ResponseBuilder.internalError("Import failed: " + error);
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Database imported successfully");

            TransferLogger.getInstance().d(TAG, "Import DB success");
            return ResponseBuilder.jsonSuccess(result.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Import DB failed: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * Import CSV file
     */
    private NanoHTTPD.Response handleImportCSV(NanoHTTPD.IHTTPSession session) {
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

            String content = new String(java.nio.file.Files.readAllBytes(tempFile.toPath()), "UTF-8");
            String[] lines = content.split("\n");

            String mergeMode = "skip";
            String mergeModeParam = session.getParms().get("mergeMode");
            if (mergeModeParam != null) {
                mergeMode = mergeModeParam;
            }

            int imported = 0;
            int skipped = 0;
            int failed = 0;

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;

                try {
                    String[] parts = parseCsvLine(line);
                    if (parts.length < 12) continue;

                    long gid = Long.parseLong(parts[0]);

                    DownloadInfo existing = downloadManager.getDownloadInfo(gid);
                    if (existing != null && "skip".equals(mergeMode)) {
                        skipped++;
                        continue;
                    }

                    DownloadInfo info = new DownloadInfo();
                    info.gid = gid;
                    info.token = parts[1];
                    info.title = parts[2];
                    info.titleJpn = parts[3];
                    info.category = Integer.parseInt(parts[4]);
                    info.posted = parts[5];
                    info.uploader = parts[6];
                    info.rating = Float.parseFloat(parts[7]);
                    info.pages = Integer.parseInt(parts[8]);
                    info.state = Integer.parseInt(parts[9]);
                    info.label = parts[10];
                    info.time = Long.parseLong(parts[11]);

                    EhDB.putDownloadInfo(info);
                    imported++;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to import CSV line: " + lines[i], e);
                    failed++;
                }
            }

            tempFile.delete();

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("imported", imported);
            result.put("skipped", skipped);
            result.put("failed", failed);

            TransferLogger.getInstance().d(TAG, "Import CSV: imported=" + imported + ", skipped=" + skipped);
            return ResponseBuilder.jsonSuccess(result.toJSONString());

        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Import CSV failed: " + e.getMessage());
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== Utility Methods ====================

    private String getCategoryName(int category) {
        String[] names = {"Misc", "Doujinshi", "Manga", "Artist CG", "Game CG",
            "Image Set", "Cosplay", "Asian Porn", "Non-H", "Western"};
        int index = -1;
        int temp = category;
        while (temp > 0) {
            temp >>= 1;
            index++;
        }
        if (index >= 0 && index < names.length) {
            return names[index];
        }
        return "Unknown";
    }

    private int parseCategory(String name) {
        if (name == null) return 0;
        switch (name) {
            case "Doujinshi": return 0x2;
            case "Manga": return 0x4;
            case "Artist CG": return 0x8;
            case "Game CG": return 0x10;
            case "Image Set": return 0x20;
            case "Cosplay": return 0x40;
            case "Asian Porn": return 0x80;
            case "Non-H": return 0x100;
            case "Western": return 0x200;
            default: return 0x1;
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());

        return fields.toArray(new String[0]);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
