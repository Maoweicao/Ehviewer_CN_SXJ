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

import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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

    private static final String TAG = "RequestParser";
    
    /**
     * 读取请求体为字符串
     *
     * 注意：NanoHTTPD 2.3.1 的 {@link NanoHTTPD.IHTTPSession#parseBody(java.util.Map)}
     * 只会为 POST/PUT 方法提取请求体，DELETE/PATCH 等方法的请求体会被读取后直接丢弃，
     * 导致 bodyMap 中拿不到任何内容。因此这里：
     * - POST/PUT 继续走 parseBody（支持 multipart/form-data、urlencoded、原始 JSON）；
     * - 其余方法直接从未消费的原始输入流读取请求体。
     */
    public static String readBody(NanoHTTPD.IHTTPSession session) throws IOException {
        NanoHTTPD.Method method = session.getMethod();
        if (method == NanoHTTPD.Method.POST || method == NanoHTTPD.Method.PUT) {
            Map<String, String> bodyMap = new HashMap<>();
            try {
                session.parseBody(bodyMap);
            } catch (NanoHTTPD.ResponseException e) {
                throw new IOException(e);
            }

            String body = bodyMap.get("postData");
            if (body != null) {
                TransferLogger.getInstance().d(TAG, "读取请求体: " + body.length() + " 字符 (postData)");
                return body;
            }

            // PUT 的 parseBody 会把内容保存到临时文件并放入 "content"
            String contentPath = bodyMap.get("content");
            if (contentPath != null) {
                File file = new File(contentPath);
                if (file.isFile()) {
                    try (InputStream is = new FileInputStream(file)) {
                        String content = readAll(is, -1);
                        TransferLogger.getInstance().d(TAG, "读取请求体: " + content.length() + " 字符 (PUT content)");
                        return content;
                    }
                }
            }
        }

        // 直接从原始输入流读取（未调用 parseBody，请求体仍未被消费）
        // 仅在能确定 Content-Length 时才读取，避免 keep-alive 连接上无请求体时阻塞等待 EOF。
        String contentLength = session.getHeaders().get("content-length");
        long expectedLength = 0;
        if (contentLength != null) {
            try {
                expectedLength = Long.parseLong(contentLength);
            } catch (NumberFormatException ignored) {
            }
        }
        if (expectedLength <= 0) {
            return "";
        }

        String body = readAll(session.getInputStream(), expectedLength);
        TransferLogger.getInstance().d(TAG, "读取请求体: " + body.length() + " 字符 (原始流)");
        return body;
    }

    /**
     * 从输入流读取全部内容为字符串，expectedLength &gt; 0 时最多读取指定字节数。
     */
    private static String readAll(InputStream is, long expectedLength) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        long totalRead = 0;
        while ((len = is.read(buf)) > 0) {
            bos.write(buf, 0, len);
            totalRead += len;
            if (expectedLength > 0 && totalRead >= expectedLength) {
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
