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
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
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

    private static TransferClientManager instance;

    private Context context;
    private OkHttpClient httpClient;
    private Handler mainHandler;

    private ConcurrentHashMap<String, ConnectedDevice> connectedDevices = new ConcurrentHashMap<>();
    private List<ClientConnectionListener> listeners = new CopyOnWriteArrayList<>();

    public TransferClientManager(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 获取单例实例
     */
    public static synchronized TransferClientManager getInstance(Context context) {
        if (instance == null) {
            instance = new TransferClientManager(context);
        }
        return instance;
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
     * 推送数据库文件到目标设备（全部传输）
     * 接收端走 /api/v1/data/import/db，按导入数据进行合并导入
     *
     * @param device  目标设备
     * @param dbFile  导出的数据库文件
     * @param callback 结果回调（主线程）
     */
    public void pushDatabase(ConnectedDevice device, File dbFile, PushDatabaseCallback callback) {
        new Thread(() -> {
            String url = "http://" + device.getHost() + ":" + device.getPort() + "/api/v1/data/import/db";
            TransferLogger.getInstance().i(TAG, "pushDatabase: " + url + ", size=" + dbFile.length());
            try {
                // 数据库文件可能较大，单独加长读写超时
                OkHttpClient client = httpClient.newBuilder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                        .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                        .build();

                RequestBody fileBody = RequestBody.create(
                        MediaType.parse("application/octet-stream"), dbFile);
                MultipartBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", dbFile.getName(), fileBody)
                        .build();

                Request request = new Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        TransferLogger.getInstance().i(TAG, "pushDatabase 成功: " + body);
                        mainHandler.post(() -> callback.onSuccess(body));
                    } else {
                        TransferLogger.getInstance().e(TAG, "pushDatabase 失败: HTTP " + response.code() + " " + body);
                        mainHandler.post(() -> callback.onError("HTTP " + response.code() + ": " + body));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "pushDatabase failed", e);
                TransferLogger.getInstance().e(TAG, "pushDatabase 异常: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    /**
     * 创建接力任务：将画廊委托给目标设备下载
     *
     * @param device   目标设备
     * @param info     画廊信息
     * @param callback 结果回调（主线程）
     */
    public void createRelayTask(ConnectedDevice device, com.hippo.ehviewer.client.data.GalleryInfo info, RelayTaskCallback callback) {
        new Thread(() -> {
            try {
                String url = "http://" + device.getHost() + ":" + device.getPort() + "/api/v1/relay/create";

                JSONObject body = new JSONObject();
                body.put("gid", info.gid);
                body.put("token", info.token);
                body.put("title", info.title);
                body.put("titleJpn", info.titleJpn);
                body.put("thumb", info.thumb);
                body.put("category", info.category);
                body.put("posted", info.posted);
                body.put("uploader", info.uploader);
                body.put("rating", info.rating);
                body.put("pages", info.pages);
                body.put("sourceDevice", android.os.Build.MODEL);
                body.put("sourceDeviceId", getDeviceId());
                body.put("sourcePort", 8080);
                body.put("autoReturn", true);

                RequestBody requestBody = RequestBody.create(JSON_MEDIA, body.toJSONString());
                Request request = new Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        JSONObject resp = JSON.parseObject(respBody);
                        String taskId = resp.getString("taskId");
                        TransferLogger.getInstance().i(TAG, "createRelayTask 成功: taskId=" + taskId);
                        mainHandler.post(() -> callback.onSuccess(taskId));
                    } else {
                        TransferLogger.getInstance().e(TAG, "createRelayTask 失败: HTTP " + response.code() + " " + respBody);
                        mainHandler.post(() -> callback.onError("HTTP " + response.code() + ": " + respBody));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "createRelayTask failed", e);
                TransferLogger.getInstance().e(TAG, "createRelayTask 异常: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    /**
     * 接受接力任务：在目标设备上接受并开始下载
     *
     * @param device   目标设备（执行端）
     * @param taskId   接力任务ID
     * @param callback 结果回调（主线程）
     */
    public void acceptRelayTask(ConnectedDevice device, String taskId, RelayTaskCallback callback) {
        new Thread(() -> {
            try {
                String url = "http://" + device.getHost() + ":" + device.getPort() + "/api/v1/relay/" + taskId + "/accept";

                JSONObject body = new JSONObject();
                body.put("acceptedDevice", android.os.Build.MODEL);
                body.put("acceptedDeviceId", getDeviceId());

                RequestBody requestBody = RequestBody.create(JSON_MEDIA, body.toJSONString());
                Request request = new Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        TransferLogger.getInstance().i(TAG, "acceptRelayTask 成功: " + respBody);
                        mainHandler.post(() -> callback.onSuccess(respBody));
                    } else {
                        TransferLogger.getInstance().e(TAG, "acceptRelayTask 失败: HTTP " + response.code() + " " + respBody);
                        mainHandler.post(() -> callback.onError("HTTP " + response.code() + ": " + respBody));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "acceptRelayTask failed", e);
                TransferLogger.getInstance().e(TAG, "acceptRelayTask 异常: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    /**
     * 推送回传文件到发起端：将完成的接力任务ZIP推送到源设备
     *
     * @param device     目标设备（发起端）
     * @param taskId     接力任务ID
     * @param gid        画廊GID
     * @param zipFile    ZIP文件
     * @param callback   结果回调（主线程）
     */
    public void pushRelayBack(ConnectedDevice device, String taskId, long gid, java.io.File zipFile, RelayTaskCallback callback) {
        new Thread(() -> {
            try {
                String url = "http://" + device.getHost() + ":" + device.getPort() + "/api/v1/relay/return/receive";

                OkHttpClient client = httpClient.newBuilder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                        .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                        .build();

                RequestBody fileBody = RequestBody.create(
                        MediaType.parse("application/zip"), zipFile);
                MultipartBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("taskId", taskId)
                        .addFormDataPart("gid", String.valueOf(gid))
                        .addFormDataPart("file", zipFile.getName(), fileBody)
                        .build();

                Request request = new Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        TransferLogger.getInstance().i(TAG, "pushRelayBack 成功: " + respBody);
                        mainHandler.post(() -> callback.onSuccess(respBody));
                    } else {
                        TransferLogger.getInstance().e(TAG, "pushRelayBack 失败: HTTP " + response.code() + " " + respBody);
                        mainHandler.post(() -> callback.onError("HTTP " + response.code() + ": " + respBody));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "pushRelayBack failed", e);
                TransferLogger.getInstance().e(TAG, "pushRelayBack 异常: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    /**
     * 获取设备ID（持久化）
     */
    private String getDeviceId() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("transfer_device", android.content.Context.MODE_PRIVATE);
        String deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
        }
        return deviceId;
    }

    /**
     * 下载接力任务ZIP文件：当源设备需要从执行端手动取回已完成任务时调用
     *
     * @param device     目标设备（执行端）
     * @param taskId     接力任务ID
     * @param callback   结果回调（主线程, onSuccess传入ZIP文件路径）
     */
    public void downloadRelayZip(ConnectedDevice device, String taskId, RelayZipCallback callback) {
        new Thread(() -> {
            try {
                String url = "http://" + device.getHost() + ":" + device.getPort() + "/api/v1/relay/" + taskId + "/download";

                OkHttpClient client = httpClient.newBuilder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                        .build();

                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        File cacheDir = context.getCacheDir();
                        String fileName = "relay_retrieve_" + taskId + "_" + System.currentTimeMillis() + ".zip";
                        File outputFile = new File(cacheDir, fileName);

                        java.io.InputStream is = response.body().byteStream();
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(outputFile);
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                        fos.close();
                        is.close();

                        TransferLogger.getInstance().i(TAG, "downloadRelayZip 成功: " + outputFile.getAbsolutePath());
                        mainHandler.post(() -> callback.onSuccess(outputFile.getAbsolutePath()));
                    } else {
                        String respBody = response.body() != null ? response.body().string() : "";
                        TransferLogger.getInstance().e(TAG, "downloadRelayZip 失败: HTTP " + response.code() + " " + respBody);
                        mainHandler.post(() -> callback.onError("HTTP " + response.code() + ": " + respBody));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "downloadRelayZip failed", e);
                TransferLogger.getInstance().e(TAG, "downloadRelayZip 异常: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    /**
     * 释放资源
     */
    public void release() {
        connectedDevices.clear();
        listeners.clear();
    }

    /**
     * 数据库推送回调
     */
    public interface PushDatabaseCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    /**
     * 接力任务回调
     */
    public interface RelayTaskCallback {
        void onSuccess(String taskId);
        void onError(String error);
    }

    /**
     * ZIP文件下载回调
     */
    public interface RelayZipCallback {
        void onSuccess(String zipFilePath);
        void onError(String error);
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
