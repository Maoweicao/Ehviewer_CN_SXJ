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

package com.hippo.ehviewer.transfer.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.transfer.data.ConnectedDevice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 客户端连接管理器
 * 管理连接到其他设备
 */
public class TransferClientManager {

    private static final String TAG = "ClientManager";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private Context context;
    private OkHttpClient httpClient;
    private Handler mainHandler;

    private ConcurrentHashMap<String, ConnectedDevice> connectedDevices = new ConcurrentHashMap<>();
    private List<ClientConnectionListener> listeners = new CopyOnWriteArrayList<>();

    public TransferClientManager(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 连接到设备
     */
    public void connect(String host, int port, String deviceName, String deviceId) {
        new Thread(() -> {
            try {
                // 验证连接
                String url = "http://" + host + ":" + port + "/api/v1/device/info";
                Request request = new Request.Builder().url(url).get().build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String body = response.body().string();
                        JSONObject info = JSON.parseObject(body);

                        ConnectedDevice device = new ConnectedDevice();
                        device.setHost(host);
                        device.setPort(port);
                        device.setName(info.getString("device_name"));
                        device.setDeviceId(deviceId);
                        device.setConnectedTime(System.currentTimeMillis());

                        connectedDevices.put(host + ":" + port, device);

                        // 注册到目标设备
                        registerToDevice(host, port, deviceName, deviceId);

                        mainHandler.post(() -> notifyConnected(device));
                    } else {
                        mainHandler.post(() -> notifyConnectionFailed(host, port, "连接失败"));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Connection failed", e);
                mainHandler.post(() -> notifyConnectionFailed(host, port, e.getMessage()));
            }
        }).start();
    }

    /**
     * 注册到目标设备
     */
    private void registerToDevice(String host, int port, String deviceName, String deviceId) {
        try {
            String url = "http://" + host + ":" + port + "/api/v1/connect";
            JSONObject body = new JSONObject();
            body.put("deviceId", deviceId);
            body.put("deviceName", deviceName);
            body.put("port", port);

            RequestBody requestBody = RequestBody.create(JSON_MEDIA, body.toJSONString());
            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();

            httpClient.newCall(request).execute();
        } catch (Exception e) {
            Log.w(TAG, "Failed to register to device", e);
        }
    }

    /**
     * 断开连接
     */
    public void disconnect(String host, int port) {
        String key = host + ":" + port;
        ConnectedDevice device = connectedDevices.remove(key);
        if (device != null) {
            // 从目标设备注销
            new Thread(() -> {
                try {
                    String url = "http://" + host + ":" + port + "/api/v1/connect";
                    Request request = new Request.Builder().url(url).delete().build();
                    httpClient.newCall(request).execute();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to unregister from device", e);
                }
            }).start();

            mainHandler.post(() -> notifyDisconnected(device));
        }
    }

    /**
     * 获取已连接设备列表
     */
    public List<ConnectedDevice> getConnectedDevices() {
        return new ArrayList<>(connectedDevices.values());
    }

    /**
     * 检查是否已连接
     */
    public boolean isConnected(String host, int port) {
        return connectedDevices.containsKey(host + ":" + port);
    }

    /**
     * 添加监听器
     */
    public void addListener(ClientConnectionListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 移除监听器
     */
    public void removeListener(ClientConnectionListener listener) {
        listeners.remove(listener);
    }

    private void notifyConnected(ConnectedDevice device) {
        for (ClientConnectionListener listener : listeners) {
            listener.onConnected(device);
        }
    }

    private void notifyDisconnected(ConnectedDevice device) {
        for (ClientConnectionListener listener : listeners) {
            listener.onDisconnected(device);
        }
    }

    private void notifyConnectionFailed(String host, int port, String error) {
        for (ClientConnectionListener listener : listeners) {
            listener.onConnectionFailed(host, port, error);
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        connectedDevices.clear();
        listeners.clear();
    }

    /**
     * 客户端连接监听器
     */
    public interface ClientConnectionListener {
        void onConnected(ConnectedDevice device);
        void onDisconnected(ConnectedDevice device);
        void onConnectionFailed(String host, int port, String error);
    }
}
