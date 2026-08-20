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

package com.hippo.ehviewer.transfer;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.transfer.core.TransferServerManager;
import com.hippo.ehviewer.transfer.data.ClientInfo;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.ehviewer.ui.transfer.TransferActivity;
import com.hippo.ehviewer.util.MiuiOptimizationHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

/**
 * WIFI 数据传输服务
 * 支持设备发现、文件传输、任务管理等功能
 */
public class TransferService extends Service implements Observer {

    private static final String TAG = "TransferService";
    private static final int NOTIFICATION_ID = 10086;
    // 渠道ID带版本号：渠道重要性创建后不可变，升级ID才能让新重要性对老用户生效
    private static final String NOTIFICATION_CHANNEL_ID = "transfer_service_v2";
    private static final String ACTION_STOP_SERVICE = "STOP_SERVICE";
    private static final long LOCK_REFRESH_INTERVAL = 5 * 60 * 1000L;

    private final IBinder binder = new TransferBinder();
    private Handler mainHandler;
    private TransferServerManager serverManager;
    private List<ClientInfo> connectedClients = new ArrayList<>();
    private ServiceCallback callback;
    private boolean isRunning = false;

    // WakeLock 防止CPU休眠（退后台/息屏后HTTP服务器才能继续响应）
    private PowerManager.WakeLock wakeLock;
    // WifiLock 保持WiFi高性能模式（HyperOS/Android 14+ 后台WiFi会被降速）
    private WifiManager.WifiLock wifiLock;
    // 每5分钟刷新一次锁，防止超时或被系统回收（模式参考 DownloadService）
    private final Runnable lockRefreshRunnable = this::refreshLocks;
    private boolean lockRefreshActive = false;

    public interface ServiceCallback {
        void onClientsChanged(List<ClientInfo> clients);
        void onTransferStatusChanged(String message);
        void onError(String error);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        TransferLogger.getInstance().d(TAG, "TransferService created");
        mainHandler = new Handler(Looper.getMainLooper());
        serverManager = new TransferServerManager(this);
        serverManager.addObserver(this);
        createNotificationChannel();
        initWakeLock();
        initWifiLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        TransferLogger.getInstance().d(TAG, "TransferService started");

        // 处理通知栏"停止服务"按钮
        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            stopTransferServer();
            return START_NOT_STICKY;
        }

        if (!isRunning) {
            startServer();
            showForegroundNotification();
            isRunning = true;
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        TransferLogger.getInstance().d(TAG, "TransferService destroyed");
        stopLockRefresh();
        releaseWakeLock();
        releaseWifiLock();
        stopServer();
        isRunning = false;
    }

    /**
     * 启动传输服务器
     */
    private void startServer() {
        TransferLogger.getInstance().i(TAG, "startServer() 开始");
        acquireWakeLock();
        acquireWifiLock();
        startLockRefresh();
        mainHandler.post(() -> {
            try {
                serverManager.start();
                TransferLogger.getInstance().i(TAG, "服务器启动成功");
                notifyStatus("传输服务已启动");
            } catch (Exception e) {
                TransferLogger.getInstance().e(TAG, "服务器启动失败", e);
                notifyError("启动服务失败: " + e.getMessage());
            }
        });
    }

    /**
     * 停止传输服务器
     */
    private void stopServer() {
        TransferLogger.getInstance().i(TAG, "stopServer() 开始");
        try {
            serverManager.stop();
            TransferLogger.getInstance().i(TAG, "服务器停止成功");
            notifyStatus("传输服务已停止");
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "服务器停止失败", e);
        }
    }

    /**
     * 停止传输服务（供Activity/通知栏调用）
     * 直接停止HTTP服务器并退出前台，无需等待服务销毁，UI可立即恢复
     */
    public void stopTransferServer() {
        TransferLogger.getInstance().i(TAG, "stopTransferServer() 开始");
        stopLockRefresh();
        releaseWakeLock();
        releaseWifiLock();
        stopServer();
        isRunning = false;
        stopForeground(true);
        stopSelf();
    }

    /**
     * 显示前台服务通知
     */
    private void showForegroundNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("EhViewer 传输服务")
                .setContentText("传输服务正在运行")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
                .setAutoCancel(false)
                .setOngoing(true);

        // 添加停止按钮
        builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止服务",
                createStopIntent()
        );

        // 添加打开应用按钮
        Intent openIntent = new Intent(this, TransferActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        builder.setContentIntent(pendingIntent);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    /**
     * 创建停止服务的Intent
     */
    private PendingIntent createStopIntent() {
        Intent stopIntent = new Intent(this, TransferService.class);
        stopIntent.setAction(ACTION_STOP_SERVICE);
        
        return PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    /**
     * 创建通知频道（Android 8.0+）
     */
    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationManager notificationManager =
                    getSystemService(android.app.NotificationManager.class);
            if (notificationManager != null) {
                // MIUI/HyperOS 使用 HIGH 重要性，降低前台服务被系统回收的概率
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "传输服务",
                        MiuiOptimizationHelper.INSTANCE.getRecommendedNotificationImportance()
                );
                channel.setDescription("EhViewer 数据传输服务");
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    // ==================== WakeLock / WifiLock 管理 ====================
    // 模式参考 DownloadService：防止退后台/息屏后CPU休眠、WiFi降速导致HTTP服务器无响应

    private void initWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "EhViewer:TransferWakeLock"
                );
                wakeLock.setReferenceCounted(false);
                TransferLogger.getInstance().i(TAG, "WakeLock initialized");
            }
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to initialize WakeLock", e);
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                initWakeLock();
            }
            if (wakeLock != null) {
                if (wakeLock.isHeld()) {
                    wakeLock.release(); // 先释放再重新获取，起刷新作用
                }
                wakeLock.acquire();
                TransferLogger.getInstance().d(TAG, "WakeLock acquired");
            }
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to acquire WakeLock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                TransferLogger.getInstance().d(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to release WakeLock", e);
        }
    }

    private void initWifiLock() {
        // Android 10+ 后台WiFi会被系统降速，需要WifiLock保持高性能
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return;
        }
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "EhViewer:TransferWifiLock"
                );
                wifiLock.setReferenceCounted(false);
                TransferLogger.getInstance().i(TAG, "WifiLock initialized (WIFI_MODE_FULL_HIGH_PERF)");
            }
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to initialize WifiLock", e);
        }
    }

    private void acquireWifiLock() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return;
        }
        try {
            if (wifiLock == null) {
                initWifiLock();
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
                TransferLogger.getInstance().d(TAG, "WifiLock acquired");
            }
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to acquire WifiLock", e);
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                TransferLogger.getInstance().d(TAG, "WifiLock released");
            }
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to release WifiLock", e);
        }
    }

    private void startLockRefresh() {
        if (lockRefreshActive) {
            return;
        }
        lockRefreshActive = true;
        mainHandler.postDelayed(lockRefreshRunnable, LOCK_REFRESH_INTERVAL);
    }

    private void stopLockRefresh() {
        lockRefreshActive = false;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(lockRefreshRunnable);
        }
    }

    private void refreshLocks() {
        if (!lockRefreshActive || !isRunning) {
            return;
        }
        acquireWakeLock();
        acquireWifiLock();
        if (lockRefreshActive) {
            mainHandler.postDelayed(lockRefreshRunnable, LOCK_REFRESH_INTERVAL);
        }
    }

    /**
     * 更新连接的客户端列表
     */
    public void updateClients(List<ClientInfo> clients) {
        this.connectedClients = new ArrayList<>(clients);
        if (callback != null) {
            mainHandler.post(() -> callback.onClientsChanged(connectedClients));
        }
    }

    /**
     * 获取连接的客户端列表
     */
    public List<ClientInfo> getConnectedClients() {
        return new ArrayList<>(connectedClients);
    }

    /**
     * 注册服务回调
     */
    public void registerCallback(ServiceCallback callback) {
        this.callback = callback;
    }

    /**
     * 取消注册服务回调
     */
    public void unregisterCallback() {
        this.callback = null;
    }

    /**
     * 获取服务器管理器
     */
    public TransferServerManager getServerManager() {
        return serverManager;
    }

    /**
     * 通知状态变化
     */
    private void notifyStatus(String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onTransferStatusChanged(message));
        }
    }

    /**
     * 通知错误
     */
    private void notifyError(String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(error));
        }
    }

    @Override
    public void update(Observable o, Object arg) {
        if (arg instanceof String) {
            notifyStatus((String) arg);
        }
    }

    /**
     * 服务Binder，用于Activity与Service的通信
     */
    public class TransferBinder extends Binder {
        public TransferService getService() {
            return TransferService.this;
        }
    }
}
