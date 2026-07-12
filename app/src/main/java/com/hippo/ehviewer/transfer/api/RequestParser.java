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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * HTTP请求解析工具
 */
public class RequestParser {
    
    /**
     * 读取请求体为字符串
     */
    public static String readBody(NanoHTTPD.IHTTPSession session) throws IOException {
        Map<String, String> bodyMap = new HashMap<>();
        try {
            session.parseBody(bodyMap);
        } catch (NanoHTTPD.ResponseException e) {
            throw new IOException(e);
        }
        
        String body = bodyMap.get("postData");
        if (body != null) {
            return body;
        }
        
        // 尝试从输入流读取
        InputStream is = session.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        
        String contentLength = session.getHeaders().get("content-length");
        long expectedLength = 0;
        if (contentLength != null) {
            try {
                expectedLength = Long.parseLong(contentLength);
            } catch (NumberFormatException ignored) {
            }
        }
        
        while ((len = is.read(buf)) > 0) {
            bos.write(buf, 0, len);
            if (expectedLength > 0 && bos.size() >= expectedLength) {
                break;
            }
        }
        
        return bos.toString(StandardCharsets.UTF_8.name());
    }
    
    /**
     * 获取查询参数
     */
    public static String getQueryParameter(NanoHTTPD.IHTTPSession session, String name) {
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
     * 获取查询参数（带默认值）
     */
    public static String getQueryParameter(NanoHTTPD.IHTTPSession session, 
                                           String name, String defaultValue) {
        String value = getQueryParameter(session, name);
        return value != null ? value : defaultValue;
    }
    
    /**
     * 获取整数查询参数
     */
    public static int getIntQueryParameter(NanoHTTPD.IHTTPSession session, 
                                           String name, int defaultValue) {
        String value = getQueryParameter(session, name);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
    
    /**
     * 获取长整数查询参数
     */
    public static long getLongQueryParameter(NanoHTTPD.IHTTPSession session, 
                                             String name, long defaultValue) {
        String value = getQueryParameter(session, name);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
    
    /**
     * 获取布尔查询参数
     */
    public static boolean getBooleanQueryParameter(NanoHTTPD.IHTTPSession session, 
                                                   String name, boolean defaultValue) {
        String value = getQueryParameter(session, name);
        if (value != null) {
            return "true".equalsIgnoreCase(value) || "1".equals(value);
        }
        return defaultValue;
    }
    
    /**
     * 从URL路径中提取GID
     * 格式: /api/v1/galleries/{gid} 或 /api/v1/galleries/{gid}/pages
     */
    public static long extractGid(String uri) {
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("galleries".equals(parts[i]) && i + 1 < parts.length) {
                try {
                    return Long.parseLong(parts[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }
    
    /**
     * 从URL路径中提取页码
     * 格式: /api/v1/galleries/{gid}/pages/{page}
     */
    public static int extractPage(String uri) {
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("pages".equals(parts[i]) && i + 1 < parts.length) {
                try {
                    return Integer.parseInt(parts[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }
    
    /**
     * 从Authorization header提取Token
     */
    public static String extractAuthToken(NanoHTTPD.IHTTPSession session) {
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return getQueryParameter(session, "token");
    }
    
    /**
     * 解析Content-Range header
     * 格式: bytes start-end/total
     */
    public static long[] parseContentRange(String contentRange) {
        if (contentRange == null || !contentRange.startsWith("bytes ")) {
            return null;
        }
        
        try {
            String range = contentRange.substring(6);
            String[] parts = range.split("[-/]");
            if (parts.length == 3) {
                long start = Long.parseLong(parts[0]);
                long end = Long.parseLong(parts[1]);
                long total = Long.parseLong(parts[2]);
                return new long[]{start, end, total};
            }
        } catch (NumberFormatException ignored) {
        }
        
        return null;
    }
}
