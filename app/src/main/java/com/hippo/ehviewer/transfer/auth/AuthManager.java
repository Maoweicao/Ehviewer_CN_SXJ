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

package com.hippo.ehviewer.transfer.auth;

import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

/**
 * 认证管理器
 * 负责处理远程连接的认证逻辑
 */
public class AuthManager {
    
    private static final String TAG = "AuthManager";
    
    // 局域网IP前缀
    private static final Set<String> LOCAL_IP_PREFIXES = new HashSet<>();
    static {
        LOCAL_IP_PREFIXES.add("192.168.");
        LOCAL_IP_PREFIXES.add("10.");
        LOCAL_IP_PREFIXES.add("172.16.");
        LOCAL_IP_PREFIXES.add("172.17.");
        LOCAL_IP_PREFIXES.add("172.18.");
        LOCAL_IP_PREFIXES.add("172.19.");
        LOCAL_IP_PREFIXES.add("172.20.");
        LOCAL_IP_PREFIXES.add("172.21.");
        LOCAL_IP_PREFIXES.add("172.22.");
        LOCAL_IP_PREFIXES.add("172.23.");
        LOCAL_IP_PREFIXES.add("172.24.");
        LOCAL_IP_PREFIXES.add("172.25.");
        LOCAL_IP_PREFIXES.add("172.26.");
        LOCAL_IP_PREFIXES.add("172.27.");
        LOCAL_IP_PREFIXES.add("172.28.");
        LOCAL_IP_PREFIXES.add("172.29.");
        LOCAL_IP_PREFIXES.add("172.30.");
        LOCAL_IP_PREFIXES.add("172.31.");
        LOCAL_IP_PREFIXES.add("127.");
        LOCAL_IP_PREFIXES.add("0:0:0:0:0:0:0:1"); // IPv6 localhost
        LOCAL_IP_PREFIXES.add("::1"); // IPv6 localhost shorthand
    }
    
    // 白名单端点（不需要认证）
    private static final Set<String> WHITELIST_PATHS = new HashSet<>();
    static {
        WHITELIST_PATHS.add("/api/v1/auth/login");
        WHITELIST_PATHS.add("/api/v1/auth/status");
        WHITELIST_PATHS.add("/api/v1/device/info");
        WHITELIST_PATHS.add("/docs");
        WHITELIST_PATHS.add("/docs/");
        WHITELIST_PATHS.add("/swagger");
        WHITELIST_PATHS.add("/openapi.yaml");
        WHITELIST_PATHS.add("/openapi");
    }
    
    private AuthMode authMode;
    private String generatedPassword;
    private String generatedToken;
    private final SessionStore sessionStore;
    
    public AuthManager() {
        this.sessionStore = new SessionStore();
        this.authMode = AuthMode.NONE;
    }
    
    /**
     * 初始化认证管理器
     * @param authMode 认证模式
     */
    public void initialize(AuthMode authMode) {
        this.authMode = authMode;
        TransferLogger logger = TransferLogger.getInstance();

        switch (authMode) {
            case PASSWORD:
                this.generatedPassword = generateRandomPassword(6);
                logger.i(TAG, "已启用密码认证, 密码: " + generatedPassword);
                break;
            case TOKEN:
                this.generatedToken = generateRandomToken();
                sessionStore.registerToken(generatedToken);
                logger.i(TAG, "已启用Token认证, Token: " + generatedToken);
                break;
            case NONE:
            default:
                logger.i(TAG, "未启用认证（仅限局域网）");
                break;
        }
    }
    
    /**
     * 认证请求
     * @param session HTTP会话
     * @return 是否认证通过
     */
    public boolean authenticate(NanoHTTPD.IHTTPSession session) {
        String uri = session.getUri();
        String remoteIp = session.getRemoteIpAddress();
        TransferLogger logger = TransferLogger.getInstance();

        // 白名单路径直接放行
        if (WHITELIST_PATHS.contains(uri)) {
            logger.d(TAG, "白名单放行: " + uri + " [来源: " + remoteIp + "]");
            return true;
        }

        boolean result;
        // 根据认证模式进行验证
        switch (authMode) {
            case NONE:
                result = isLocalNetwork(remoteIp);
                break;

            case PASSWORD:
                result = authenticateWithPassword(session);
                break;

            case TOKEN:
                result = authenticateWithToken(session);
                break;

            default:
                result = false;
                break;
        }

        if (result) {
            logger.d(TAG, "认证通过: " + uri + " [来源: " + remoteIp + ", 模式: " + authMode + "]");
        } else {
            logger.w(TAG, "认证失败: " + uri + " [来源: " + remoteIp + ", 模式: " + authMode + "]");
        }
        return result;
    }
    
    /**
     * 密码认证
     */
    private boolean authenticateWithPassword(NanoHTTPD.IHTTPSession session) {
        // 从Header获取Authorization
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return sessionStore.validateSession(token);
        }
        
        // 从查询参数获取token
        String queryToken = getQueryParameter(session, "token");
        if (queryToken != null) {
            return sessionStore.validateSession(queryToken);
        }
        
        return false;
    }
    
    /**
     * Token认证
     */
    private boolean authenticateWithToken(NanoHTTPD.IHTTPSession session) {
        // 从Header获取Authorization
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return sessionStore.validateRegisteredToken(token) || 
                   sessionStore.validateSession(token);
        }
        
        // 从查询参数获取token
        String queryToken = getQueryParameter(session, "token");
        if (queryToken != null) {
            return sessionStore.validateRegisteredToken(queryToken) || 
                   sessionStore.validateSession(queryToken);
        }
        
        return false;
    }
    
    /**
     * 验证密码并创建会话
     * @param password 输入的密码
     * @return 会话Token，失败返回null
     */
    public String login(String password) {
        if (authMode != AuthMode.PASSWORD) {
            TransferLogger.getInstance().w(TAG, "登录被拒绝: 当前认证模式不是密码模式");
            return null;
        }
        
        if (generatedPassword.equals(password)) {
            String token = sessionStore.createSession();
            TransferLogger.getInstance().i(TAG, "密码登录成功, 会话已创建");
            return token;
        }
        
        TransferLogger.getInstance().w(TAG, "密码登录失败: 密码错误");
        return null;
    }
    
    /**
     * 验证客户端Token并创建会话
     * @param clientToken 客户端Token
     * @return 会话Token，失败返回null
     */
    public String loginWithToken(String clientToken) {
        if (authMode != AuthMode.TOKEN) {
            TransferLogger.getInstance().w(TAG, "Token登录被拒绝: 当前认证模式不是Token模式");
            return null;
        }
        
        if (sessionStore.validateRegisteredToken(clientToken)) {
            String token = sessionStore.createSession();
            TransferLogger.getInstance().i(TAG, "Token登录成功, 会话已创建");
            return token;
        }
        
        TransferLogger.getInstance().w(TAG, "Token登录失败: Token无效");
        return null;
    }
    
    /**
     * 登出
     * @param sessionToken 会话Token
     */
    public void logout(String sessionToken) {
        sessionStore.invalidateSession(sessionToken);
        TransferLogger.getInstance().i(TAG, "会话已注销");
    }
    
    /**
     * 获取生成的密码
     */
    public String getGeneratedPassword() {
        return generatedPassword;
    }
    
    /**
     * 获取生成的Token
     */
    public String getGeneratedToken() {
        return generatedToken;
    }
    
    /**
     * 获取当前认证模式
     */
    public AuthMode getAuthMode() {
        return authMode;
    }
    
    /**
     * 检查是否是局域网IP
     */
    public static boolean isLocalNetwork(String ip) {
        if (ip == null) {
            return false;
        }
        
        for (String prefix : LOCAL_IP_PREFIXES) {
            if (ip.startsWith(prefix)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检查路径是否在白名单中
     */
    public static boolean isWhitelisted(String path) {
        return WHITELIST_PATHS.contains(path);
    }
    
    /**
     * 从查询参数中获取指定参数值
     */
    private String getQueryParameter(NanoHTTPD.IHTTPSession session, String name) {
        String queryString = session.getQueryParameterString();
        if (queryString == null) {
            return null;
        }
        
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2 && keyValue[0].equals(name)) {
                return keyValue[1];
            }
        }
        
        return null;
    }
    
    /**
     * 生成随机密码
     */
    private String generateRandomPassword(int length) {
        String chars = "0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return sb.toString();
    }
    
    /**
     * 生成随机Token
     */
    private String generateRandomToken() {
        return java.util.UUID.randomUUID().toString();
    }
}
