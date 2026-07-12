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

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.transfer.auth.AuthManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 标签API处理器
 */
public class LabelApiHandler extends BaseApiHandler {
    
    private static final String TAG = "LabelApiHandler";
    
    public LabelApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }
    
    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);
        
        // /api/v1/labels - 标签列表
        if (uri.equals("/api/v1/labels")) {
            return handleLabelList(session);
        }
        
        // /api/v1/labels/{label}/galleries - 标签下的画廊
        if (uri.matches("/api/v1/labels/[^/]+/galleries")) {
            String label = extractLabel(uri);
            return handleLabelGalleries(session, label);
        }
        
        return ResponseBuilder.notFound("Endpoint");
    }
    
    /**
     * 获取标签列表
     */
    private NanoHTTPD.Response handleLabelList(NanoHTTPD.IHTTPSession session) {
        try {
            List<DownloadLabel> labels = EhDB.getAllDownloadLabelList();
            
            StringBuilder sb = new StringBuilder();
            sb.append("{\"labels\":[");
            
            boolean first = true;
            
            // 添加默认标签（无标签的画廊）
            long defaultCount = downloadManager.getDefaultDownloadInfoList().size();
            sb.append("{\"name\":\"默认\",\"count\":").append(defaultCount).append("}");
            first = false;
            
            // 添加自定义标签
            for (DownloadLabel label : labels) {
                if (!first) sb.append(",");
                first = false;
                
                String labelName = label.getLabel();
                long count = 0;
                
                List<DownloadInfo> labelList = downloadManager.getLabelDownloadInfoList(labelName);
                if (labelList != null) {
                    count = labelList.size();
                }
                
                sb.append("{\"name\":\"").append(escapeJson(labelName));
                sb.append("\",\"count\":").append(count).append("}");
            }
            
            sb.append("]}");
            
            return ResponseBuilder.jsonSuccess(sb.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Error listing labels", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 获取标签下的画廊列表
     */
    private NanoHTTPD.Response handleLabelGalleries(NanoHTTPD.IHTTPSession session, String label) {
        try {
            int page = RequestParser.getIntQueryParameter(session, "page", 1);
            int limit = RequestParser.getIntQueryParameter(session, "limit", 20);
            
            List<DownloadInfo> allList;
            
            if ("默认".equals(label) || "default".equals(label)) {
                allList = downloadManager.getDefaultDownloadInfoList();
            } else {
                allList = downloadManager.getLabelDownloadInfoList(label);
                if (allList == null) {
                    allList = new ArrayList<>();
                }
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
            sb.append(",\"label\":\"").append(escapeJson(label)).append("\"");
            sb.append(",\"galleries\":[");
            
            boolean first = true;
            for (DownloadInfo info : pageList) {
                if (!first) sb.append(",");
                first = false;
                sb.append(formatGalleryJson(info));
            }
            
            sb.append("]}");
            
            return ResponseBuilder.jsonSuccess(sb.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Error listing label galleries", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 从URI中提取标签名
     */
    private String extractLabel(String uri) {
        // /api/v1/labels/{label}/galleries
        String[] parts = uri.split("/");
        if (parts.length >= 4) {
            return parts[3]; // 标签名在第4个位置
        }
        return "";
    }
    
    /**
     * 格式化画廊JSON
     */
    private String formatGalleryJson(DownloadInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"gid\":").append(info.gid);
        sb.append(",\"token\":\"").append(escapeJson(info.token)).append("\"");
        sb.append(",\"title\":\"").append(escapeJson(info.title)).append("\"");
        
        if (info.titleJpn != null) {
            sb.append(",\"titleJpn\":\"").append(escapeJson(info.titleJpn)).append("\"");
        }
        
        if (info.thumb != null) {
            sb.append(",\"thumb\":\"").append(escapeJson(info.thumb)).append("\"");
        }
        
        sb.append(",\"category\":").append(info.category);
        sb.append(",\"posted\":\"").append(escapeJson(info.posted)).append("\"");
        sb.append(",\"uploader\":\"").append(escapeJson(info.uploader)).append("\"");
        sb.append(",\"rating\":").append(info.rating);
        sb.append(",\"pages\":").append(info.pages);
        sb.append(",\"state\":").append(info.state);
        sb.append(",\"time\":").append(info.time);
        sb.append("}");
        
        return sb.toString();
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
