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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话存储管理器
 * 负责生成、存储和验证会话Token
 */
public class SessionStore {

    private static final String TAG = "SessionStore";

    // 会话有效期：24小时
    private static final long SESSION_EXPIRY_MS = 24 * 60 * 60 * 1000;
    
    // 活跃会话: token -> SessionInfo
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    
    // 已注册的Token（Token认证模式）
    private final Map<String, Boolean> registeredTokens = new ConcurrentHashMap<>();
    
    public SessionStore() {
    }
    
    /**
     * 创建新会话
     * @return 新生成的会话Token
     */
    public String createSession() {
        String token = UUID.randomUUID().toString();
        long expiry = System.currentTimeMillis() + SESSION_EXPIRY_MS;
        sessions.put(token, new SessionInfo(token, expiry));
        TransferLogger.getInstance().d(TAG, "创建会话, 当前活跃会话数: " + sessions.size());
        return token;
    }
    
    /**
     * 验证会话是否有效
     * @param token 会话Token
     * @return 是否有效
     */
    public boolean validateSession(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        
        SessionInfo session = sessions.get(token);
        if (session == null) {
            return false;
        }
        
        // 检查是否过期
        if (System.currentTimeMillis() > session.expiry) {
            sessions.remove(token);
            TransferLogger.getInstance().d(TAG, "会话已过期并移除");
            return false;
        }
        
        return true;
    }
    
    /**
     * 使会话失效
     * @param token 会话Token
     */
    public void invalidateSession(String token) {
        sessions.remove(token);
        TransferLogger.getInstance().d(TAG, "会话已失效, 剩余活跃会话数: " + sessions.size());
    }
    
    /**
     * 注册Token（Token认证模式）
     * @param token 客户端Token
     */
    public void registerToken(String token) {
        if (token != null && !token.isEmpty()) {
            registeredTokens.put(token, true);
            TransferLogger.getInstance().d(TAG, "已注册Token, 注册数: " + registeredTokens.size());
        }
    }
    
    /**
     * 验证已注册的Token
     * @param token 客户端Token
     * @return 是否有效
     */
    public boolean validateRegisteredToken(String token) {
        boolean valid = token != null && registeredTokens.containsKey(token);
        if (!valid && token != null && !token.isEmpty()) {
            TransferLogger.getInstance().w(TAG, "Token验证失败: 未注册");
        }
        return valid;
    }
    
    /**
     * 撤销已注册的Token
     * @param token 客户端Token
     */
    public void revokeToken(String token) {
        registeredTokens.remove(token);
        TransferLogger.getInstance().d(TAG, "已撤销Token, 剩余注册数: " + registeredTokens.size());
    }
    
    /**
     * 清理过期会话
     */
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> now > entry.getValue().expiry);
        int removed = before - sessions.size();
        if (removed > 0) {
            TransferLogger.getInstance().d(TAG, "清理过期会话: " + removed + " 个");
        }
    }
    
    /**
     * 获取活跃会话数量
     */
    public int getActiveSessionCount() {
        cleanupExpiredSessions();
        return sessions.size();
    }
    
    /**
     * 会话信息内部类
     */
    private static class SessionInfo {
        final String token;
        final long expiry;
        
        SessionInfo(String token, long expiry) {
            this.token = token;
            this.expiry = expiry;
        }
    }
}
