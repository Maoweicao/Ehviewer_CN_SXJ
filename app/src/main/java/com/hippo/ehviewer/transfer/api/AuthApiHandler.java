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

import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.auth.AuthMode;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;

/**
 * 认证API处理器
 */
public class AuthApiHandler extends BaseApiHandler {
    
    private static final String TAG = "AuthApiHandler";
    
    private final AuthManager authManager;
    
    public AuthApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
        this.authManager = authManager;
    }
    
    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri, session);
        
        // /api/v1/auth/status
        if (uri.equals("/api/v1/auth/status")) {
            return handleAuthStatus(session);
        }
        
        return ResponseBuilder.notFound("Endpoint");
    }
    
    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri, session);
        
        // /api/v1/auth/login
        if (uri.equals("/api/v1/auth/login")) {
            return handleLogin(session);
        }
        
        // /api/v1/auth/logout
        if (uri.equals("/api/v1/auth/logout")) {
            return handleLogout(session);
        }
        
        return ResponseBuilder.notFound("Endpoint");
    }
    
    /**
     * 处理登录请求
     */
    private NanoHTTPD.Response handleLogin(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            
            AuthMode authMode = authManager.getAuthMode();
            TransferLogger.getInstance().i(TAG, "登录请求: mode=" + authMode);
            
            if (authMode == AuthMode.NONE) {
                // 免认证模式，直接返回成功
                String json = "{\"success\":true,\"message\":\"No authentication required\",\"mode\":\"none\"}";
                return ResponseBuilder.jsonSuccess(json);
            }
            
            if (authMode == AuthMode.PASSWORD) {
                // 密码认证
                String password = extractJsonString(body, "password", "");
                
                if (password.isEmpty()) {
                    return ResponseBuilder.jsonError(
                        NanoHTTPD.Response.Status.BAD_REQUEST, "Password is required");
                }
                
                String sessionToken = authManager.login(password);
                
                if (sessionToken != null) {
                    TransferLogger.getInstance().i(TAG, "密码登录成功");
                    String json = "{\"success\":true,\"token\":\"" + sessionToken + 
                                 "\",\"expires\":86400,\"mode\":\"password\"}";
                    return ResponseBuilder.jsonSuccess(json);
                } else {
                    TransferLogger.getInstance().w(TAG, "密码登录失败: 密码无效");
                    return ResponseBuilder.jsonError(
                        NanoHTTPD.Response.Status.UNAUTHORIZED, "Invalid password");
                }
            }
            
            if (authMode == AuthMode.TOKEN) {
                // Token认证
                String clientToken = extractJsonString(body, "token", "");
                
                if (clientToken.isEmpty()) {
                    return ResponseBuilder.jsonError(
                        NanoHTTPD.Response.Status.BAD_REQUEST, "Token is required");
                }
                
                String sessionToken = authManager.loginWithToken(clientToken);
                
                if (sessionToken != null) {
                    TransferLogger.getInstance().i(TAG, "Token登录成功");
                    String json = "{\"success\":true,\"token\":\"" + sessionToken + 
                                 "\",\"expires\":86400,\"mode\":\"token\"}";
                    return ResponseBuilder.jsonSuccess(json);
                } else {
                    TransferLogger.getInstance().w(TAG, "Token登录失败: token无效");
                    return ResponseBuilder.jsonError(
                        NanoHTTPD.Response.Status.UNAUTHORIZED, "Invalid token");
                }
            }
            
            return ResponseBuilder.internalError("Unknown auth mode");
            
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Error handling login", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 处理登出请求
     */
    private NanoHTTPD.Response handleLogout(NanoHTTPD.IHTTPSession session) {
        try {
            String token = RequestParser.extractAuthToken(session);
            TransferLogger.getInstance().i(TAG, "登出请求: " + (token != null ? "token已提供" : "无token"));
            
            if (token != null) {
                authManager.logout(token);
            }
            
            String json = "{\"success\":true,\"message\":\"Logged out\"}";
            return ResponseBuilder.jsonSuccess(json);
            
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Error handling logout", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 处理认证状态查询
     */
    private NanoHTTPD.Response handleAuthStatus(NanoHTTPD.IHTTPSession session) {
        try {
            AuthMode authMode = authManager.getAuthMode();
            TransferLogger.getInstance().d(TAG, "查询认证状态: mode=" + authMode);
            
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"mode\":\"").append(authMode.getValue()).append("\"");
            
            // 检查当前token是否有效
            String token = RequestParser.extractAuthToken(session);
            boolean authenticated = false;
            
            if (authMode == AuthMode.NONE) {
                authenticated = AuthManager.isLocalNetwork(session.getRemoteIpAddress());
            } else if (token != null) {
                // 简单检查token格式
                authenticated = token.length() > 10;
            }
            
            sb.append(",\"authenticated\":").append(authenticated);
            
            // 免认证模式下显示是否为局域网
            if (authMode == AuthMode.NONE) {
                boolean isLocal = AuthManager.isLocalNetwork(session.getRemoteIpAddress());
                sb.append(",\"isLocalNetwork\":").append(isLocal);
                sb.append(",\"remoteIp\":\"").append(session.getRemoteIpAddress()).append("\"");
            }
            
            sb.append("}");
            
            return ResponseBuilder.jsonSuccess(sb.toString());
            
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Error getting auth status", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
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
}
