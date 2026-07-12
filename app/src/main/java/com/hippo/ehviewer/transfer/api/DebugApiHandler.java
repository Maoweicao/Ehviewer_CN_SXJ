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

import com.hippo.ehviewer.transfer.auth.AuthManager;

import java.io.InputStream;

import fi.iki.elonen.NanoHTTPD;

/**
 * 调试页面API处理器
 */
public class DebugApiHandler extends BaseApiHandler {
    
    private static final String TAG = "DebugApiHandler";
    
    public DebugApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }
    
    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);
        
        // /api/v1/debug - 调试页面
        if (uri.equals("/api/v1/debug")) {
            return handleDebugPage(session);
        }
        
        return ResponseBuilder.notFound("Endpoint");
    }
    
    /**
     * 返回调试页面
     */
    private NanoHTTPD.Response handleDebugPage(NanoHTTPD.IHTTPSession session) {
        try {
            String html = getDebugHtml();
            return ResponseBuilder.html(html);
        } catch (Exception e) {
            Log.e(TAG, "Error serving debug page", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
    
    /**
     * 获取调试页面HTML
     */
    private String getDebugHtml() {
        return "<!DOCTYPE html>\n" +
            "<html lang=\"zh-CN\">\n" +
            "<head>\n" +
            "    <meta charset=\"UTF-8\">\n" +
            "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "    <title>EhViewer Remote API Debug</title>\n" +
            "    <style>\n" +
            "        * { box-sizing: border-box; margin: 0; padding: 0; }\n" +
            "        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 20px; background: #f5f5f5; }\n" +
            "        h1 { margin-bottom: 20px; color: #333; }\n" +
            "        .endpoint { background: white; padding: 15px; margin-bottom: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n" +
            "        .endpoint h3 { color: #2196F3; margin-bottom: 10px; font-size: 14px; }\n" +
            "        .method { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; margin-right: 8px; }\n" +
            "        .method-get { background: #4CAF50; color: white; }\n" +
            "        .method-post { background: #2196F3; color: white; }\n" +
            "        .method-delete { background: #f44336; color: white; }\n" +
            "        .method-query { background: #9C27B0; color: white; }\n" +
            "        .url { font-family: monospace; color: #666; }\n" +
            "        .form-group { margin: 10px 0; }\n" +
            "        label { display: block; margin-bottom: 5px; font-size: 13px; color: #666; }\n" +
            "        input, textarea, select { width: 100%; padding: 8px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px; }\n" +
            "        textarea { height: 80px; font-family: monospace; }\n" +
            "        button { background: #2196F3; color: white; border: none; padding: 10px 20px; border-radius: 4px; cursor: pointer; font-size: 14px; }\n" +
            "        button:hover { background: #1976D2; }\n" +
            "        pre { background: #f8f9fa; padding: 10px; border-radius: 4px; overflow-x: auto; font-size: 13px; max-height: 300px; overflow-y: auto; }\n" +
            "        .result { margin-top: 10px; }\n" +
            "        .status { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; }\n" +
            "        .status-ok { background: #4CAF50; color: white; }\n" +
            "        .status-error { background: #f44336; color: white; }\n" +
            "    </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "    <h1>EhViewer Remote API Debug</h1>\n" +
            "    \n" +
            "    <div class=\"endpoint\">\n" +
            "        <h3><span class=\"method method-get\">GET</span> <span class=\"url\">/api/v1/auth/status</span></h3>\n" +
            "        <button onclick=\"testGet('/api/v1/auth/status', 'result-auth-status')\">Test</button>\n" +
            "        <div class=\"result\"><pre id=\"result-auth-status\"></pre></div>\n" +
            "    </div>\n" +
            "    \n" +
            "    <div class=\"endpoint\">\n" +
            "        <h3><span class=\"method method-post\">POST</span> <span class=\"url\">/api/v1/auth/login</span></h3>\n" +
            "        <div class=\"form-group\">\n" +
            "            <label>Password / Token:</label>\n" +
            "            <input type=\"text\" id=\"login-password\" placeholder=\"Enter password or token\">\n" +
            "        </div>\n" +
            "        <button onclick=\"testLogin()\">Test Login</button>\n" +
            "        <div class=\"result\"><pre id=\"result-auth-login\"></pre></div>\n" +
            "    </div>\n" +
            "    \n" +
            "    <div class=\"endpoint\">\n" +
            "        <h3><span class=\"method method-get\">GET</span> <span class=\"url\">/api/v1/galleries</span></h3>\n" +
            "        <div class=\"form-group\">\n" +
            "            <label>Page:</label>\n" +
            "            <input type=\"number\" id=\"galleries-page\" value=\"1\">\n" +
            "        </div>\n" +
            "        <div class=\"form-group\">\n" +
            "            <label>Limit:</label>\n" +
            "            <input type=\"number\" id=\"galleries-limit\" value=\"10\">\n" +
            "        </div>\n" +
            "        <button onclick=\"testGetGalleries()\">Test</button>\n" +
            "        <div class=\"result\"><pre id=\"result-galleries\"></pre></div>\n" +
            "    </div>\n" +
            "    \n" +
            "    <div class=\"endpoint\">\n" +
            "        <h3><span class=\"method method-query\">QUERY</span> <span class=\"url\">/api/v1/galleries</span></h3>\n" +
            "        <div class=\"form-group\">\n" +
            "            <label>Query Body (JSON):</label>\n" +
            "            <textarea id=\"query-body\">{\n" +
            "  \"page\": 1,\n" +
            "  \"limit\": 10,\n" +
            "  \"sort\": \"downloadTime\",\n" +
            "  \"order\": \"desc\"\n" +
            "}</textarea>\n" +
            "        </div>\n" +
            "        <button onclick=\"testQuery()\">Test</button>\n" +
            "        <div class=\"result\"><pre id=\"result-query\"></pre></div>\n" +
            "    </div>\n" +
            "    \n" +
            "    <div class=\"endpoint\">\n" +
            "        <h3><span class=\"method method-get\">GET</span> <span class=\"url\">/api/v1/galleries/{gid}</span></h3>\n" +
            "        <div class=\"form-group\">\n" +
            "            <label>GID:</label>\n" +
            "            <input type=\"number\" id=\"gallery-gid\" placeholder=\"Enter gallery GID\">\n" +
            "        </div>\n" +
            "        <button onclick=\"testGetGallery()\">Test</button>\n" +
            "        <div class=\"result\"><pre id=\"result-gallery\"></pre></div>\n" +
            "    </div>\n" +
            "    \n" +
            "    <div class=\"endpoint\">\n" +
            "        <h3><span class=\"method method-get\">GET</span> <span class=\"url\">/api/v1/galleries/{gid}/pages</span></h3>\n" +
            "        <div class=\"form-group\">\n" +
            "            <label>GID:</label>\n" +
            "            <input type=\"number\" id=\"pages-gid\" placeholder=\"Enter gallery GID\">\n" +
            "        </div>\n" +
            "        <button onclick=\"testGetPages()\">Test</button>\n" +
            "        <div class=\"result\"><pre id=\"result-pages\"></pre></div>\n" +
            "    </div>\n" +
            "    \n" +
            "    <div class=\"endpoint\">\n" +
            "        <h3><span class=\"method method-get\">GET</span> <span class=\"url\">/api/v1/galleries/{gid}/pages/{page}</span></h3>\n" +
            "        <div class=\"form-group\">\n" +
            "            <label>GID:</label>\n" +
            "            <input type=\"number\" id=\"page-gid\" placeholder=\"Enter gallery GID\">\n" +
            "        </div>\n" +
            "        <div class=\"form-group\">\n" +
            "            <label>Page:</label>\n" +
            "            <input type=\"number\" id=\"page-index\" placeholder=\"Enter page index\">\n" +
            "        </div>\n" +
            "        <button onclick=\"testGetPage()\">Test</button>\n" +
            "        <div class=\"result\"><pre id=\"result-page\"></pre></div>\n" +
            "    </div>\n" +
            "    \n" +
            "    <div class=\"endpoint\">\n" +
            "        <h3><span class=\"method method-delete\">DELETE</span> <span class=\"url\">/api/v1/galleries/{gid}</span></h3>\n" +
            "        <div class=\"form-group\">\n" +
            "            <label>GID:</label>\n" +
            "            <input type=\"number\" id=\"delete-gid\" placeholder=\"Enter gallery GID\">\n" +
            "        </div>\n" +
            "        <button onclick=\"testDelete()\">Test Delete</button>\n" +
            "        <div class=\"result\"><pre id=\"result-delete\"></pre></div>\n" +
            "    </div>\n" +
            "    \n" +
            "    <div class=\"endpoint\">\n" +
            "        <h3><span class=\"method method-get\">GET</span> <span class=\"url\">/api/v1/labels</span></h3>\n" +
            "        <button onclick=\"testGet('/api/v1/labels', 'result-labels')\">Test</button>\n" +
            "        <div class=\"result\"><pre id=\"result-labels\"></pre></div>\n" +
            "    </div>\n" +
            "    \n" +
            "    <div class=\"endpoint\">\n" +
            "        <h3><span class=\"method method-get\">GET</span> <span class=\"url\">/api/v1/system/info</span></h3>\n" +
            "        <button onclick=\"testGet('/api/v1/system/info', 'result-system')\">Test</button>\n" +
            "        <div class=\"result\"><pre id=\"result-system\"></pre></div>\n" +
            "    </div>\n" +
            "    \n" +
            "    <script>\n" +
            "        let authToken = localStorage.getItem('authToken') || '';\n" +
            "        \n" +
            "        function getHeaders() {\n" +
            "            const headers = {'Content-Type': 'application/json'};\n" +
            "            if (authToken) {\n" +
            "                headers['Authorization'] = 'Bearer ' + authToken;\n" +
            "            }\n" +
            "            return headers;\n" +
            "        }\n" +
            "        \n" +
            "        async function testGet(url, resultId) {\n" +
            "            try {\n" +
            "                const res = await fetch(url, {headers: getHeaders()});\n" +
            "                const data = await res.json();\n" +
            "                document.getElementById(resultId).textContent = JSON.stringify(data, null, 2);\n" +
            "            } catch (e) {\n" +
            "                document.getElementById(resultId).textContent = 'Error: ' + e.message;\n" +
            "            }\n" +
            "        }\n" +
            "        \n" +
            "        async function testLogin() {\n" +
            "            const password = document.getElementById('login-password').value;\n" +
            "            try {\n" +
            "                const res = await fetch('/api/v1/auth/login', {\n" +
            "                    method: 'POST',\n" +
            "                    headers: {'Content-Type': 'application/json'},\n" +
            "                    body: JSON.stringify({password: password, token: password})\n" +
            "                });\n" +
            "                const data = await res.json();\n" +
            "                if (data.token) {\n" +
            "                    authToken = data.token;\n" +
            "                    localStorage.setItem('authToken', authToken);\n" +
            "                }\n" +
            "                document.getElementById('result-auth-login').textContent = JSON.stringify(data, null, 2);\n" +
            "            } catch (e) {\n" +
            "                document.getElementById('result-auth-login').textContent = 'Error: ' + e.message;\n" +
            "            }\n" +
            "        }\n" +
            "        \n" +
            "        async function testGetGalleries() {\n" +
            "            const page = document.getElementById('galleries-page').value;\n" +
            "            const limit = document.getElementById('galleries-limit').value;\n" +
            "            await testGet(`/api/v1/galleries?page=${page}&limit=${limit}`, 'result-galleries');\n" +
            "        }\n" +
            "        \n" +
            "        async function testQuery() {\n" +
            "            const body = document.getElementById('query-body').value;\n" +
            "            try {\n" +
            "                const res = await fetch('/api/v1/galleries', {\n" +
            "                    method: 'QUERY',\n" +
            "                    headers: getHeaders(),\n" +
            "                    body: body\n" +
            "                });\n" +
            "                const data = await res.json();\n" +
            "                document.getElementById('result-query').textContent = JSON.stringify(data, null, 2);\n" +
            "            } catch (e) {\n" +
            "                document.getElementById('result-query').textContent = 'Error: ' + e.message;\n" +
            "            }\n" +
            "        }\n" +
            "        \n" +
            "        async function testGetGallery() {\n" +
            "            const gid = document.getElementById('gallery-gid').value;\n" +
            "            if (!gid) { alert('Please enter GID'); return; }\n" +
            "            await testGet(`/api/v1/galleries/${gid}`, 'result-gallery');\n" +
            "        }\n" +
            "        \n" +
            "        async function testGetPages() {\n" +
            "            const gid = document.getElementById('pages-gid').value;\n" +
            "            if (!gid) { alert('Please enter GID'); return; }\n" +
            "            await testGet(`/api/v1/galleries/${gid}/pages`, 'result-pages');\n" +
            "        }\n" +
            "        \n" +
            "        async function testGetPage() {\n" +
            "            const gid = document.getElementById('page-gid').value;\n" +
            "            const page = document.getElementById('page-index').value;\n" +
            "            if (!gid || page === '') { alert('Please enter GID and Page'); return; }\n" +
            "            await testGet(`/api/v1/galleries/${gid}/pages/${page}`, 'result-page');\n" +
            "        }\n" +
            "        \n" +
            "        async function testDelete() {\n" +
            "            const gid = document.getElementById('delete-gid').value;\n" +
            "            if (!gid) { alert('Please enter GID'); return; }\n" +
            "            if (!confirm('Are you sure you want to delete gallery ' + gid + '?')) return;\n" +
            "            try {\n" +
            "                const res = await fetch(`/api/v1/galleries/${gid}`, {\n" +
            "                    method: 'DELETE',\n" +
            "                    headers: getHeaders()\n" +
            "                });\n" +
            "                const data = await res.json();\n" +
            "                document.getElementById('result-delete').textContent = JSON.stringify(data, null, 2);\n" +
            "            } catch (e) {\n" +
            "                document.getElementById('result-delete').textContent = 'Error: ' + e.message;\n" +
            "            }\n" +
            "        }\n" +
            "    </script>\n" +
            "</body>\n" +
            "</html>";
    }
}
