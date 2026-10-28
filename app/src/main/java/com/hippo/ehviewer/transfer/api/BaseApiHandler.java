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

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * API处理器基类
 */
public abstract class BaseApiHandler {
    
    protected static final String TAG = "ApiHandler";
    
    protected final Context context;
    protected final DownloadManager downloadManager;
    protected final AuthManager authManager;
    
    public BaseApiHandler(Context context, AuthManager authManager) {
        this.context = context;
        this.downloadManager = EhApplication.getDownloadManager(context);
        this.authManager = authManager;
    }
    
    /**
     * 处理GET请求
     */
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        return ResponseBuilder.methodNotAllowed();
    }
    
    /**
     * 处理POST请求
     */
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        return ResponseBuilder.methodNotAllowed();
    }
    
    /**
     * 处理PUT请求
     */
    public NanoHTTPD.Response handlePut(NanoHTTPD.IHTTPSession session, String uri) {
        return ResponseBuilder.methodNotAllowed();
    }
    
    /**
     * 处理DELETE请求
     */
    public NanoHTTPD.Response handleDelete(NanoHTTPD.IHTTPSession session, String uri) {
        return ResponseBuilder.methodNotAllowed();
    }
    
    /**
     * 处理PATCH请求
     */
    public NanoHTTPD.Response handlePatch(NanoHTTPD.IHTTPSession session, String uri) {
        return ResponseBuilder.methodNotAllowed();
    }
    
    /**
     * 处理QUERY请求（RFC 10008）
     */
    public NanoHTTPD.Response handleQuery(NanoHTTPD.IHTTPSession session, String uri) {
        return ResponseBuilder.methodNotAllowed();
    }
    
    /**
     * 判断当前请求是否以“QUERY 语义”提交（承载 JSON 查询体）。
     *
     * <p>内嵌 NanoHTTPD 2.3.1 的 Method 枚举不含 QUERY，真实 QUERY 请求会在 HTTP
     * 解析阶段被拒（400）。因此客户端改用 {@code POST + X-HTTP-Method-Override: QUERY}
     * 承载查询体，这里统一识别。
     */
    protected boolean isQueryBody(NanoHTTPD.IHTTPSession session) {
        NanoHTTPD.Method method = session.getMethod();
        if (method != null && "QUERY".equals(method.name())) {
            return true;
        }
        Map<String, String> headers = session.getHeaders();
        String override = headers != null ? headers.get("x-http-method-override") : null;
        return override != null && "QUERY".equalsIgnoreCase(override.trim());
    }

    /**
     * 记录请求日志
     */
    protected void logRequest(String method, String uri) {
        logRequest(method, uri, null);
    }

    /**
     * 记录请求日志（带远程IP和查询参数）
     */
    protected void logRequest(String method, String uri, NanoHTTPD.IHTTPSession session) {
        if (session != null) {
            String remoteIp = session.getRemoteIpAddress();
            Map<String, String> parms = null;
            try {
                parms = session.getParms();
            } catch (Exception e) {
                // ignore
            }
            String query = (parms == null || parms.isEmpty()) ? "" : " params=" + parms;
            TransferLogger.getInstance().d(TAG, "[" + remoteIp + "] " + method + " " + uri + query);
        } else {
            TransferLogger.getInstance().d(TAG, method + " " + uri);
        }
    }

    /**
     * 记录成功日志
     */
    protected void logSuccess(String action, String detail) {
        TransferLogger.getInstance().i(TAG, action + " 成功: " + detail);
    }

    /**
     * 记录失败日志
     */
    protected void logFailure(String action, String detail) {
        TransferLogger.getInstance().w(TAG, action + " 失败: " + detail);
    }
}
