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
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.transfer.auth.AuthManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

/**
 * 设备连接管理API处理器
 *
 * 端点:
 * - POST   /api/v1/connect          注册设备连接
 * - DELETE /api/v1/connect          注销设备连接
 * - GET    /api/v1/connect/peers    获取已连接设备列表
 */
public class ConnectApiHandler extends BaseApiHandler {

    private static final String TAG = "ConnectApiHandler";

    // 已连接设备存储 (key: deviceId)
    private final ConcurrentHashMap<String, ConnectedPeer> peers = new ConcurrentHashMap<>();

    // 设备连接超时时间（毫秒）
    private static final long PEER_TIMEOUT_MS = 5 * 60 * 1000; // 5分钟

    public ConnectApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri);

        // GET /api/v1/connect/peers - 获取已连接设备列表
        if (uri.equals("/api/v1/connect/peers")) {
            return handleGetPeers(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri);

        // POST /api/v1/connect - 注册设备连接
        if (uri.equals("/api/v1/connect")) {
            return handleConnect(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handleDelete(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("DELETE", uri);

        // DELETE /api/v1/connect - 注销设备连接
        if (uri.equals("/api/v1/connect")) {
            return handleDisconnect(session);
        }

        return ResponseBuilder.notFound("Endpoint");
    }

    // ==================== 业务逻辑 ====================

    /**
     * 注册设备连接
     */
    private NanoHTTPD.Response handleConnect(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);

            String deviceId = json.getString("deviceId");
            String deviceName = json.getString("deviceName");
            String deviceType = json.getString("deviceType");
            int port = json.getIntValue("port");

            if (deviceId == null || deviceId.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "deviceId is required");
            }

            // 获取远程IP
            String remoteIp = session.getRemoteIpAddress();

            // 创建或更新设备记录
            ConnectedPeer peer = peers.get(deviceId);
            if (peer == null) {
                peer = new ConnectedPeer();
                peer.deviceId = deviceId;
                peer.connectedAt = System.currentTimeMillis();
            }
            peer.deviceName = deviceName;
            peer.deviceType = deviceType;
            peer.remoteIp = remoteIp;
            peer.port = port;
            peer.lastSeen = System.currentTimeMillis();

            peers.put(deviceId, peer);

            Log.d(TAG, "Device connected: " + deviceName + " (" + deviceId + ") from " + remoteIp);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Connected");
            response.put("deviceId", deviceId);
            response.put("serverTime", System.currentTimeMillis());

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to connect", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 注销设备连接
     */
    private NanoHTTPD.Response handleDisconnect(NanoHTTPD.IHTTPSession session) {
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSON.parseObject(body);

            String deviceId = json.getString("deviceId");
            if (deviceId == null || deviceId.isEmpty()) {
                // 尝试从查询参数获取
                deviceId = RequestParser.getQueryParameter(session, "deviceId");
            }

            if (deviceId == null || deviceId.isEmpty()) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST, "deviceId is required");
            }

            ConnectedPeer removed = peers.remove(deviceId);
            if (removed == null) {
                return ResponseBuilder.notFound("Device");
            }

            Log.d(TAG, "Device disconnected: " + removed.deviceName + " (" + deviceId + ")");

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Disconnected");
            response.put("deviceId", deviceId);

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to disconnect", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 获取已连接设备列表
     */
    private NanoHTTPD.Response handleGetPeers(NanoHTTPD.IHTTPSession session) {
        try {
            // 清理过期设备
            cleanExpiredPeers();

            JSONArray peersArray = new JSONArray();
            for (ConnectedPeer peer : peers.values()) {
                JSONObject peerJson = new JSONObject();
                peerJson.put("deviceId", peer.deviceId);
                peerJson.put("deviceName", peer.deviceName);
                peerJson.put("deviceType", peer.deviceType);
                peerJson.put("remoteIp", peer.remoteIp);
                peerJson.put("port", peer.port);
                peerJson.put("connectedAt", peer.connectedAt);
                peerJson.put("lastSeen", peer.lastSeen);
                peersArray.add(peerJson);
            }

            JSONObject response = new JSONObject();
            response.put("peers", peersArray);
            response.put("total", peersArray.size());

            return ResponseBuilder.jsonSuccess(response.toJSONString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to get peers", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 清理过期设备
     */
    private void cleanExpiredPeers() {
        long now = System.currentTimeMillis();
        peers.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue().lastSeen) > PEER_TIMEOUT_MS;
            if (expired) {
                Log.d(TAG, "Removing expired peer: " + entry.getValue().deviceName);
            }
            return expired;
        });
    }

    /**
     * 获取已连接设备数量
     */
    public int getPeerCount() {
        cleanExpiredPeers();
        return peers.size();
    }

    /**
     * 检查设备是否已连接
     */
    public boolean isPeerConnected(String deviceId) {
        ConnectedPeer peer = peers.get(deviceId);
        if (peer == null) return false;
        return (System.currentTimeMillis() - peer.lastSeen) <= PEER_TIMEOUT_MS;
    }

    /**
     * 更新设备最后活跃时间
     */
    public void updatePeerLastSeen(String deviceId) {
        ConnectedPeer peer = peers.get(deviceId);
        if (peer != null) {
            peer.lastSeen = System.currentTimeMillis();
        }
    }

    // ==================== 内部数据类 ====================

    /**
     * 已连接设备信息
     */
    private static class ConnectedPeer {
        String deviceId;
        String deviceName;
        String deviceType;
        String remoteIp;
        int port;
        long connectedAt;
        long lastSeen;
    }
}
