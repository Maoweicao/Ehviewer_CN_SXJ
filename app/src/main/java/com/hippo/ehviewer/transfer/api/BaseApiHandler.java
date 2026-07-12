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

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.transfer.auth.AuthManager;

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
     * 记录请求日志
     */
    protected void logRequest(String method, String uri) {
        Log.d(TAG, method + " " + uri);
    }
}
