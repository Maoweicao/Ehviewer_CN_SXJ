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

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.data.ReceiveSettings;

import fi.iki.elonen.NanoHTTPD;

/**
 * 设置API处理器
 */
public class SettingsApiHandler extends BaseApiHandler {

    private static final String TAG = "SettingsApiHandler";

    public SettingsApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);

        // /api/v1/settings/receive - 获取接收设置
        if (uri.equals("/api/v1/settings/receive")) {
            return handleGetReceiveSettings(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePut(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("PUT", uri);

        // /api/v1/settings/receive - 更新接收设置
        if (uri.equals("/api/v1/settings/receive")) {
            return handleUpdateReceiveSettings(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    /**
     * 获取接收设置
     */
    private NanoHTTPD.Response handleGetReceiveSettings(NanoHTTPD.IHTTPSession session) {
        try {
            JSONObject response = new JSONObject();

            JSONObject autoReceive = new JSONObject();
            autoReceive.put("bookmarks", Settings.isAutoReceiveBookmarks());
            autoReceive.put("downloads", Settings.isAutoReceiveDownloads());
            autoReceive.put("favorites", Settings.isAutoReceiveFavorites());

            response.put("autoReceive", autoReceive);
            response.put("pageSize", Settings.getSelectorPageSize());

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to get receive settings", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 更新接收设置
     */
    private NanoHTTPD.Response handleUpdateReceiveSettings(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);

            // 更新自动接收设置
            if (json.containsKey("autoReceive")) {
                JSONObject autoReceive = json.getJSONObject("autoReceive");
                if (autoReceive.containsKey("bookmarks")) {
                    Settings.putAutoReceiveBookmarks(autoReceive.getBoolean("bookmarks"));
                }
                if (autoReceive.containsKey("downloads")) {
                    Settings.putAutoReceiveDownloads(autoReceive.getBoolean("downloads"));
                }
                if (autoReceive.containsKey("favorites")) {
                    Settings.putAutoReceiveFavorites(autoReceive.getBoolean("favorites"));
                }
            }

            // 更新分页大小
            if (json.containsKey("pageSize")) {
                Settings.putSelectorPageSize(json.getIntValue("pageSize"));
            }

            JSONObject response = new JSONObject();
            response.put("success", true);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to update receive settings", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }
}
