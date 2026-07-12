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
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.transfer.data.DiscoveredDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 增强版mDNS设备发现管理器
 * 支持定时刷新和手动刷新
 */
public class DeviceDiscoveryManager {

    private static final String TAG = "DeviceDiscovery";
    private static final String SERVICE_TYPE = "_ehviewer-transfer._tcp.local.";

    private Context context;
    private NsdManager nsdManager;
    private Handler handler;

    private List<DiscoveredDevice> devices = new CopyOnWriteArrayList<>();
    private List<DiscoveryListener> listeners = new ArrayList<>();

    private boolean isDiscovering = false;
    private boolean autoRefresh = false;
    private int refreshInterval = 60000; // 默认60秒

    private NsdManager.DiscoveryListener discoveryListener;
    private NsdManager.ResolveListener resolveListener;

    private Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (autoRefresh && isDiscovering) {
                refreshDevices();
                handler.postDelayed(this, refreshInterval);
            }
        }
    };

    public DeviceDiscoveryManager(Context context) {
        this.context = context;
        this.nsdManager = context.getSystemService(NsdManager.class);
        this.handler = new Handler(Looper.getMainLooper());
        initListeners();
    }

    /**
     * 初始化监听器
     */
    private void initListeners() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: " + errorCode);
                isDiscovering = false;
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Stop discovery failed: " + errorCode);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Service found: " + serviceInfo.getServiceName());
                resolveService(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Service lost: " + serviceInfo.getServiceName());
                removeDevice(serviceInfo.getServiceName());
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Discovery started");
                isDiscovering = true;
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Discovery stopped");
                isDiscovering = false;
            }
        };

        resolveListener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "Resolve failed: " + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo resolvedInfo) {
                Log.d(TAG, "Service resolved: " + resolvedInfo.getServiceName());
                DiscoveredDevice device = new DiscoveredDevice();
                device.setName(resolvedInfo.getServiceName());
                device.setHost(resolvedInfo.getHost().getHostAddress());
                device.setPort(resolvedInfo.getPort());
                addDevice(device);
            }
        };
    }

    /**
     * 开始发现设备
     */
    public void startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Already discovering");
            return;
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            Log.d(TAG, "Start discovery");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start discovery", e);
        }
    }

    /**
     * 停止发现设备
     */
    public void stopDiscovery() {
        if (!isDiscovering) {
            return;
        }

        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
            Log.d(TAG, "Stop discovery");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop discovery", e);
        }

        stopAutoRefresh();
    }

    /**
     * 开始自动刷新
     */
    public void startAutoRefresh(int interval) {
        this.refreshInterval = interval;
        this.autoRefresh = true;
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, interval);
        Log.d(TAG, "Auto refresh started, interval: " + interval + "ms");
    }

    /**
     * 停止自动刷新
     */
    public void stopAutoRefresh() {
        this.autoRefresh = false;
        handler.removeCallbacks(refreshRunnable);
        Log.d(TAG, "Auto refresh stopped");
    }

    /**
     * 刷新设备列表
     */
    public void refreshDevices() {
        Log.d(TAG, "Refreshing devices");
        devices.clear();
        notifyListeners();

        // 重新发现
        if (isDiscovering) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                Log.w(TAG, "Failed to stop discovery before refresh", e);
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to refresh devices", e);
        }
    }

    /**
     * 解析服务
     */
    private void resolveService(NsdServiceInfo serviceInfo) {
        try {
            nsdManager.resolveService(serviceInfo, resolveListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to resolve service", e);
        }
    }

    /**
     * 添加设备
     */
    private void addDevice(DiscoveredDevice device) {
        // 检查是否已存在
        for (DiscoveredDevice existing : devices) {
            if (existing.equals(device)) {
                return;
            }
        }
        devices.add(device);
        handler.post(this::notifyListeners);
    }

    /**
     * 移除设备
     */
    private void removeDevice(String serviceName) {
        devices.removeIf(device -> serviceName.equals(device.getName()));
        handler.post(this::notifyListeners);
    }

    /**
     * 获取发现的设备列表
     */
    public List<DiscoveredDevice> getDevices() {
        return new ArrayList<>(devices);
    }

    /**
     * 是否正在发现
     */
    public boolean isDiscovering() {
        return isDiscovering;
    }

    /**
     * 添加监听器
     */
    public void addListener(DiscoveryListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 移除监听器
     */
    public void removeListener(DiscoveryListener listener) {
        listeners.remove(listener);
    }

    /**
     * 通知监听器
     */
    private void notifyListeners() {
        List<DiscoveredDevice> currentDevices = getDevices();
        for (DiscoveryListener listener : listeners) {
            listener.onDevicesUpdated(currentDevices);
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        stopDiscovery();
        devices.clear();
        listeners.clear();
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * 设备发现监听器接口
     */
    public interface DiscoveryListener {
        void onDevicesUpdated(List<DiscoveredDevice> devices);
    }
}
