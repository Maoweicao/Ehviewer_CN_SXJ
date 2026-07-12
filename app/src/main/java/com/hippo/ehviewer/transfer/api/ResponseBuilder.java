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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import fi.iki.elonen.NanoHTTPD;

/**
 * HTTP响应构建工具
 */
public class ResponseBuilder {
    
    private static final String MIME_JSON = "application/json";
    private static final String MIME_HTML = "text/html";
    private static final String MIME_PLAIN = "text/plain";
    
    /**
     * 创建JSON成功响应
     */
    public static NanoHTTPD.Response jsonSuccess(String json) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, MIME_JSON, json);
    }
    
    /**
     * 创建JSON成功响应（带自定义状态码）
     */
    public static NanoHTTPD.Response jsonSuccess(NanoHTTPD.Response.Status status, String json) {
        return NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, json);
    }
    
    /**
     * 创建JSON错误响应
     */
    public static NanoHTTPD.Response jsonError(NanoHTTPD.Response.Status status, String message) {
        String json = "{\"success\":false,\"error\":\"" + escapeJson(message) + "\"}";
        return NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, json);
    }
    
    /**
     * 创建JSON错误响应（带错误码）
     */
    public static NanoHTTPD.Response jsonError(NanoHTTPD.Response.Status status, 
                                                String message, int errorCode) {
        String json = "{\"success\":false,\"error\":\"" + escapeJson(message) + 
                     "\",\"code\":" + errorCode + "}";
        return NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, json);
    }
    
    /**
     * 创建401未授权响应
     */
    public static NanoHTTPD.Response unauthorized() {
        String json = "{\"success\":false,\"error\":\"Unauthorized\",\"code\":401}";
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.UNAUTHORIZED, MIME_JSON, json);
    }
    
    /**
     * 创建403禁止响应
     */
    public static NanoHTTPD.Response forbidden(String message) {
        String json = "{\"success\":false,\"error\":\"" + escapeJson(message) + "\",\"code\":403}";
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.FORBIDDEN, MIME_JSON, json);
    }
    
    /**
     * 创建404未找到响应
     */
    public static NanoHTTPD.Response notFound(String resource) {
        String json = "{\"success\":false,\"error\":\"" + escapeJson(resource) + " not found\",\"code\":404}";
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.NOT_FOUND, MIME_JSON, json);
    }
    
    /**
     * 创建405方法不允许响应
     */
    public static NanoHTTPD.Response methodNotAllowed() {
        String json = "{\"success\":false,\"error\":\"Method not allowed\",\"code\":405}";
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, MIME_JSON, json);
    }
    
    /**
     * 创建500内部错误响应
     */
    public static NanoHTTPD.Response internalError(String message) {
        String json = "{\"success\":false,\"error\":\"" + escapeJson(message) + "\",\"code\":500}";
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.INTERNAL_ERROR, MIME_JSON, json);
    }
    
    /**
     * 创建HTML响应
     */
    public static NanoHTTPD.Response html(String html) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, MIME_HTML, html);
    }
    
    /**
     * 创建纯文本响应
     */
    public static NanoHTTPD.Response text(String text) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, MIME_PLAIN, text);
    }
    
    /**
     * 创建二进制流响应
     */
    public static NanoHTTPD.Response binary(String mimeType, InputStream stream, long length) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, mimeType, stream, length);
    }
    
    /**
     * 创建二进制流响应（未知长度）
     */
    public static NanoHTTPD.Response binaryChunked(String mimeType, InputStream stream) {
        return NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK, mimeType, stream);
    }
    
    /**
     * 创建重定向响应
     */
    public static NanoHTTPD.Response redirect(String location) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.REDIRECT, MIME_PLAIN, "");
        response.addHeader("Location", location);
        return response;
    }
    
    /**
     * JSON转义
     */
    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
