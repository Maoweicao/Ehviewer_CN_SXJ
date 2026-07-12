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

/**
 * 认证模式枚举
 */
public enum AuthMode {
    /**
     * 无认证 - 仅限局域网
     */
    NONE("none"),
    
    /**
     * 密码认证 - 启动时生成随机密码
     */
    PASSWORD("password"),
    
    /**
     * Token认证 - 一次性生成，客户端保存
     */
    TOKEN("token");
    
    private final String value;
    
    AuthMode(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    public static AuthMode fromString(String value) {
        if (value != null) {
            for (AuthMode mode : AuthMode.values()) {
                if (mode.value.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
        }
        return NONE; // 默认无认证
    }
}
