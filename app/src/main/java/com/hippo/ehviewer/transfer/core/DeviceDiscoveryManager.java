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
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import com.hippo.ehviewer.transfer.data.DiscoveredDevice;
import com.hippo.ehviewer.transfer.log.TransferLogger;

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

    // mDNS发现依赖组播包，Android 12及以下（含部分Android 13）必须持有 MulticastLock 才能收到组播
    private WifiManager.MulticastLock multicastLock;

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
        TransferLogger.getInstance().d(TAG, "DeviceDiscoveryManager 初始化完成");
    }

    /**
     * 初始化监听器
     */
    private void initListeners() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                TransferLogger.getInstance().e(TAG, "Discovery start failed: " + errorCode);
                isDiscovering = false;
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                TransferLogger.getInstance().e(TAG, "Stop discovery failed: " + errorCode);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                TransferLogger.getInstance().d(TAG, "Service found: " + serviceInfo.getServiceName()
                        + " type: " + serviceInfo.getServiceType());
                // 过滤掉非本应用服务，避免解析无关的mDNS服务
                if (serviceInfo.getServiceType() != null
                        && !serviceInfo.getServiceType().startsWith("_ehviewer-transfer.")) {
                    return;
                }
                resolveService(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                TransferLogger.getInstance().d(TAG, "Service lost: " + serviceInfo.getServiceName());
                TransferLogger.getInstance().i(TAG, "设备丢失: " + serviceInfo.getServiceName());
                removeDevice(serviceInfo.getServiceName());
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                TransferLogger.getInstance().d(TAG, "Discovery started");
                isDiscovering = true;
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                TransferLogger.getInstance().d(TAG, "Discovery stopped");
                isDiscovering = false;
            }
        };

        resolveListener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                TransferLogger.getInstance().e(TAG, "Resolve failed: " + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo resolvedInfo) {
                TransferLogger.getInstance().d(TAG, "Service resolved: " + resolvedInfo.getServiceName());
                DiscoveredDevice device = new DiscoveredDevice();
                device.setName(resolvedInfo.getServiceName());
                device.setHost(resolvedInfo.getHost().getHostAddress());
                device.setPort(resolvedInfo.getPort());
                TransferLogger.getInstance().i(TAG, "发现设备: name=" + device.getName()
                        + ", host=" + device.getHost() + ", port=" + device.getPort());
                addDevice(device);
            }
        };
    }

    /**
     * 开始发现设备
     */
    public void startDiscovery() {
        if (isDiscovering) {
            TransferLogger.getInstance().d(TAG, "Already discovering");
            return;
        }

        acquireMulticastLock();

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            TransferLogger.getInstance().d(TAG, "Start discovery");
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to start discovery", e);
        }
    }

    /**
     * 停止发现设备
     */
    public void stopDiscovery() {
        if (!isDiscovering) {
            releaseMulticastLock();
            return;
        }

        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
            TransferLogger.getInstance().d(TAG, "Stop discovery");
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to stop discovery", e);
        }

        releaseMulticastLock();
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
        TransferLogger.getInstance().d(TAG, "Auto refresh started, interval: " + interval + "ms");
    }

    /**
     * 停止自动刷新
     */
    public void stopAutoRefresh() {
        this.autoRefresh = false;
        handler.removeCallbacks(refreshRunnable);
        TransferLogger.getInstance().d(TAG, "Auto refresh stopped");
    }

    /**
     * 刷新设备列表
     */
    public void refreshDevices() {
        TransferLogger.getInstance().d(TAG, "Refreshing devices");
        devices.clear();
        notifyListeners();

        // 重新发现
        if (isDiscovering) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                TransferLogger.getInstance().w(TAG, "Failed to stop discovery before refresh", e);
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to refresh devices", e);
        }
    }

    /**
     * 获取/创建组播锁，mDNS发现需要接收组播包
     */
    private void acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    multicastLock = wifiManager.createMulticastLock("EhViewer:TransferDiscovery");
                    multicastLock.setReferenceCounted(false);
                }
            }
            if (multicastLock != null && !multicastLock.isHeld()) {
                multicastLock.acquire();
                TransferLogger.getInstance().d(TAG, "MulticastLock acquired");
            }
        } catch (Exception e) {
            TransferLogger.getInstance().w(TAG, "Failed to acquire MulticastLock", e);
        }
    }

    /**
     * 释放组播锁
     */
    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
                TransferLogger.getInstance().d(TAG, "MulticastLock released");
            }
        } catch (Exception e) {
            TransferLogger.getInstance().w(TAG, "Failed to release MulticastLock", e);
        }
    }

    /**
     * 解析服务
     */
    private void resolveService(NsdServiceInfo serviceInfo) {
        try {
            nsdManager.resolveService(serviceInfo, resolveListener);
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to resolve service", e);
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
