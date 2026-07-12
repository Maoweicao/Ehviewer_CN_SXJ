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
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import com.hippo.ehviewer.BuildConfig;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.auth.AuthMode;
import com.hippo.unifile.UniFile;

import java.io.File;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;

/**
 * 系统信息API处理器
 */
public class SystemApiHandler extends BaseApiHandler {
    
    private static final String TAG = "SystemApiHandler";
    
    private final AuthManager authManager;
    
    public SystemApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
        this.authManager = authManager;
    }
    
    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);
        
        // /api/v1/system/info
        if (uri.equals("/api/v1/system/info")) {
            return handleSystemInfo(session);
        }
        
        // /api/v1/system/stats
        if (uri.equals("/api/v1/system/stats")) {
            return handleSystemStats(session);
        }
        
        return ResponseBuilder.notFound("Endpoint");
    }
    
    /**
     * 获取系统信息
     */
    private NanoHTTPD.Response handleSystemInfo(NanoHTTPD.IHTTPSession session) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            
            // 设备信息
            sb.append("\"deviceName\":\"").append(Build.MODEL).append("\"");
            sb.append(",\"deviceManufacturer\":\"").append(Build.MANUFACTURER).append("\"");
            sb.append(",\"androidVersion\":\"").append(Build.VERSION.RELEASE).append("\"");
            sb.append(",\"sdkVersion\":").append(Build.VERSION.SDK_INT);
            
            // 应用信息
            sb.append(",\"appVersion\":\"").append(BuildConfig.VERSION_NAME).append("\"");
            sb.append(",\"appVersionCode\":").append(BuildConfig.VERSION_CODE);
            
            // 下载位置
            UniFile downloadLocation = Settings.getDownloadLocation();
            if (downloadLocation != null) {
                sb.append(",\"downloadLocation\":\"").append(escapeJson(downloadLocation.getUri().toString())).append("\"");
            }
            
            // 统计信息
            List<DownloadInfo> allList = downloadManager.getAllDownloadInfoList();
            int totalGalleries = allList.size();
            int totalPages = 0;
            int downloadedGalleries = 0;
            
            for (DownloadInfo info : allList) {
                totalPages += info.pages;
                if (info.state == DownloadInfo.STATE_FINISH) {
                    downloadedGalleries++;
                }
            }
            
            sb.append(",\"totalGalleries\":").append(totalGalleries);
            sb.append(",\"downloadedGalleries\":").append(downloadedGalleries);
            sb.append(",\"totalPages\":").append(totalPages);
            
            // 存储信息
            try {
                File downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
                StatFs stat = new StatFs(downloadDir.getPath());
                long totalSpace = stat.getTotalBytes();
                long freeSpace = stat.getAvailableBytes();
                long usedSpace = totalSpace - freeSpace;
                
                sb.append(",\"storageTotal\":").append(totalSpace);
                sb.append(",\"storageUsed\":").append(usedSpace);
                sb.append(",\"storageFree\":").append(freeSpace);
                sb.append(",\"storageTotalFormatted\":\"").append(formatSize(totalSpace)).append("\"");
                sb.append(",\"storageUsedFormatted\":\"").append(formatSize(usedSpace)).append("\"");
                sb.append(",\"storageFreeFormatted\":\"").append(formatSize(freeSpace)).append("\"");
            } catch (Exception e) {
                Log.w(TAG, "Failed to get storage info", e);
            }
            
            // 认证模式
            AuthMode authMode = authManager.getAuthMode();
            sb.append(",\"authMode\":\"").append(authMode.getValue()).append("\"");
            
            // 功能开关
            sb.append(",\"deleteEnabled\":").append(Settings.isRemoteDeleteEnabled());
            sb.append(",\"syncDownloadEnabled\":").append(Settings.getSyncDownloadWhileReading());
            
            sb.append("}");
            
            return ResponseBuilder.jsonSuccess(sb.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting system info", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 获取系统统计信息
     */
    private NanoHTTPD.Response handleSystemStats(NanoHTTPD.IHTTPSession session) {
        try {
            List<DownloadInfo> allList = downloadManager.getAllDownloadInfoList();
            
            int totalGalleries = allList.size();
            int downloading = 0;
            int waiting = 0;
            int finished = 0;
            int failed = 0;
            int none = 0;
            
            for (DownloadInfo info : allList) {
                switch (info.state) {
                    case DownloadInfo.STATE_DOWNLOAD:
                        downloading++;
                        break;
                    case DownloadInfo.STATE_WAIT:
                        waiting++;
                        break;
                    case DownloadInfo.STATE_FINISH:
                        finished++;
                        break;
                    case DownloadInfo.STATE_FAILED:
                        failed++;
                        break;
                    case DownloadInfo.STATE_NONE:
                        none++;
                        break;
                }
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"totalGalleries\":").append(totalGalleries);
            sb.append(",\"downloading\":").append(downloading);
            sb.append(",\"waiting\":").append(waiting);
            sb.append(",\"finished\":").append(finished);
            sb.append(",\"failed\":").append(failed);
            sb.append(",\"none\":").append(none);
            sb.append("}");
            
            return ResponseBuilder.jsonSuccess(sb.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting system stats", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 格式化文件大小
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
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
}
