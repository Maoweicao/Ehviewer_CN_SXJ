/*
 * Copyright 2026 EhViewer Contributors
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

package com.hippo.ehviewer.lab.ip;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.ip.core.ClashProfileBuilder;
import com.hippo.ehviewer.lab.ip.core.ClashProfileStore;
import com.hippo.ehviewer.lab.ip.core.MihomoCore;
import com.hippo.ehviewer.lab.ip.model.IpInfo;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动换 IP 控制器。
 *
 * 实际代理由内置 Mihomo 内核提供（127.0.0.1:17890），不再把订阅节点
 * 误当成 SOCKS5 服务器。切换节点通过内核的控制接口完成。
 */
public class IpSwitchController {
    private static final String TAG = "IpSwitchController";
    private static IpSwitchController instance;

    private final MihomoCore core = MihomoCore.getInstance();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IpSwitchCore");
        t.setDaemon(true);
        return t;
    });
    private Context appContext;
    private final AtomicInteger switchGeneration = new AtomicInteger();
    private volatile String lastError = "";

    public interface IpSwitchListener {
        void onIpSwitched(IpInfo newIp);
        void onNoAvailableIp();
    }

    private IpSwitchController() {
    }

    public static synchronized IpSwitchController getInstance() {
        if (instance == null) {
            instance = new IpSwitchController();
        }
        return instance;
    }

    public boolean isEnabled() {
        return Settings.getIpSwitchEnabled();
    }

    public boolean isCoreRunning() {
        return core.isRunning();
    }

    public String getLastError() {
        return lastError != null && !lastError.isEmpty() ? lastError : core.getLastError();
    }

    /** 启动内核（异步，不阻塞调用线程）。 */
    public void ensureStarted(Context context) {
        if (isEnabled()) {
            applyDownloadPolicyIfNeeded();
        } else {
            restoreDownloadPolicy();
        }
        if (!isEnabled() || isCoreRunning()) return;
        final Context app = context.getApplicationContext();
        appContext = app;
        executor.execute(() -> {
            if (!isEnabled() || isCoreRunning()) return;
            if (!ClashProfileStore.hasProfile(app)) {
                lastError = "还没有可用的 Clash 订阅配置";
                Log.w(TAG, "IP switch enabled but no profile yet");
                return;
            }
            lastError = "";
            ClashProfileStore.syncPool(app);
            com.hippo.ehviewer.lab.ip.core.VpnBypass.bindToUnderlyingNetwork(app);
            boolean ok = core.start(app);
            if (ok) {
                // 实际访问一次 e-hentai，确认当前节点可用；自动组优先。
                core.ensureHealthy();
            } else {
                com.hippo.ehviewer.lab.ip.core.VpnBypass.unbind(app);
            }
            lastError = ok ? "" : core.getLastError();
            Log.i(TAG, "Core start: " + ok + (ok ? "" : " " + core.getLastError()));
        });
    }

    /**
     * 自动换 IP 期间强制关闭下载里的“限流时暂停下载”。
     *
     * 自动换 IP 本身就是限流的处理方式：如果冷却开关仍然生效，
     * RemoteLimitGuard 会先进入 30 分钟冷却、跳过节点切换，下载也会被暂停。
     * 因此启用自动换 IP 时快照用户原值并关掉它，关闭自动换 IP 时再恢复。
     */
    public static void applyDownloadPolicyIfNeeded() {
        if (!Settings.getIpSwitchEnabled()) return;
        if (Settings.getIpSwitchPrevLimitDetection() < 0) {
            Settings.putIpSwitchPrevLimitDetection(Settings.isLimitDetectionEnabled() ? 1 : 0);
        }
        if (!Settings.isLimitDetectionEnabled()) {
            Settings.putLimitDetectionEnabled(true);
            Log.i(TAG, "Auto IP enabled: limit detection forced on");
        }
        if (Settings.getIpSwitchPrevLimitCooldown() < 0) {
            Settings.putIpSwitchPrevLimitCooldown(Settings.isLimitCooldownEnabled() ? 1 : 0);
        }
        if (Settings.isLimitCooldownEnabled()) {
            Settings.putLimitCooldownEnabled(false);
            Log.i(TAG, "Auto IP enabled: download rate-limit pause disabled");
        }
    }

    /** 关闭自动换 IP 时恢复用户原来的“限流时暂停下载”设置。 */
    public static void restoreDownloadPolicy() {
        int prevDetection = Settings.getIpSwitchPrevLimitDetection();
        if (prevDetection >= 0) {
            Settings.putLimitDetectionEnabled(prevDetection == 1);
        }
        Settings.putIpSwitchPrevLimitDetection(-1);
        int prev = Settings.getIpSwitchPrevLimitCooldown();
        if (prev >= 0) {
            Settings.putLimitCooldownEnabled(prev == 1);
        }
        Settings.putIpSwitchPrevLimitCooldown(-1);
    }

    /** 内核重启（订阅更新后调用，异步）。 */
    public void restart(Context context) {
        final Context app = context.getApplicationContext();
        appContext = app;
        executor.execute(() -> {
            if (!isEnabled()) return;
            applyDownloadPolicyIfNeeded();
            core.stop();
            ClashProfileStore.syncPool(app);
            com.hippo.ehviewer.lab.ip.core.VpnBypass.bindToUnderlyingNetwork(app);
            boolean ok = core.start(app);
            if (ok) {
                // 不要在复用已运行内核时 reloadConfig：重载会把手动选择的节点重置为自动组。
                // 订阅更新走 restart()（先 stop 再 start），配置会重新加载。
                core.ensureHealthy();
            } else {
                com.hippo.ehviewer.lab.ip.core.VpnBypass.unbind(app);
            }
            lastError = ok ? "" : core.getLastError();
        });
    }

    public void stop() {
        final Context ctx = appContext;
        lastError = "";
        restoreDownloadPolicy();
        executor.execute(() -> {
            core.stop();
            if (ctx != null) {
                com.hippo.ehviewer.lab.ip.core.VpnBypass.unbind(ctx);
            }
        });
    }

    /** 供 EhProxySelector 使用：本机 HTTP 代理（内核和节点都就绪时才生效）。 */
    public Proxy getCurrentProxy() {
        if (!isEnabled() || !isCoreRunning() || !core.isProxyReady()) {
            return null;
        }
        InetSocketAddress addr = new InetSocketAddress(
                "127.0.0.1", ClashProfileStore.MIXED_PORT);
        return new Proxy(Proxy.Type.HTTP, addr);
    }

    public IpInfo getCurrentIp() {
        if (!isEnabled() || !isCoreRunning()) return null;
        String node = core.getCurrentNode();
        if (node == null) return null;
        IpInfo info = new IpInfo("127.0.0.1", ClashProfileStore.MIXED_PORT, "http");
        info.name = node;
        info.actualProtocol = "clash";
        info.source = "内置内核";
        return info;
    }

    public String getCurrentNodeName() {
        return isCoreRunning() ? core.getCurrentNode() : null;
    }

    private volatile long lastNodeRefreshAt;

    /**
     * 后台刷新“当前节点”缓存。状态栏每秒轮询，必须避免主线程做网络请求。
     */
    public void refreshCurrentNodeAsync() {
        if (!isEnabled() || !isCoreRunning()) return;
        long now = System.currentTimeMillis();
        if (now - lastNodeRefreshAt < 2000) return;
        lastNodeRefreshAt = now;
        executor.execute(() -> {
            if (isEnabled() && isCoreRunning()) {
                core.refreshCurrentNode();
            }
        });
    }

    public int getSwitchGeneration() {
        return switchGeneration.get();
    }

    /**
     * 点选指定节点：真实请求验证 → 选择 → 确认 → 关闭旧连接。
     * 由后台线程调用。
     */
    public synchronized String selectNodeBlocking(String name) {
        if (!isEnabled() || !isCoreRunning() || name == null || name.isEmpty()) {
            return null;
        }
        String previous = core.refreshCurrentNode();
        final long switchStartMs = System.currentTimeMillis();
        // 点选也必须实际访问一次 e-hentai，避免选到“握手能通但页面不可用”的节点。
        if (!core.testThroughProxy(name, 3000)) {
            return null;
        }
        core.markHealthy();
        // /delay 只验证不改全局选择器，这里再显式选中一次。
        if (!core.selectNode(name)) {
            return null;
        }
        // 确认控制器已经切换到目标节点（auto 组会返回实际节点）。
        for (int i = 0; i < 12; i++) {
            String now = core.refreshCurrentNode();
            if (name.equals(now)) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        finishSwitch("manual", previous, switchStartMs);
        return name;
    }

    private void finishSwitch(@Nullable String reason, @Nullable String from, long startMs) {
        core.closeAllConnections();
        EhApplication.flushConnections();
        EhApplication.cancelAllCalls();
        switchGeneration.incrementAndGet();
        String to = core.getCurrentNode();
        logSwitch(reason, from, to, System.currentTimeMillis() - startMs);
        if (appContext != null) {
            com.hippo.ehviewer.download.DownloadManager dm =
                    EhApplication.getDownloadManager(appContext);
            if (dm != null) {
                dm.onIpSwitched();
            }
            // 自动换 IP 成功后，重试此前因限流失败的预下载扫描任务
            com.hippo.ehviewer.task.PreDownloadScanRetryCoordinator.INSTANCE
                    .retryPending(appContext);
        }
        Log.i(TAG, "Switch transaction done, generation=" + switchGeneration.get());
    }

    /** 把换节点写进下载日志，便于分析“失败前后是否换过 IP”。 */
    private void logSwitch(@Nullable String reason, @Nullable String from,
                           @Nullable String to, long elapsedMs) {
        try {
            com.hippo.ehviewer.download.DownloadLogger logger =
                    com.hippo.ehviewer.download.DownloadLogger.peek();
            if (logger != null) {
                logger.logSwitchEvent(null, from, to, reason,
                        switchGeneration.get(), elapsedMs);
            }
        } catch (Throwable ignored) {
        }
    }

    public IpInfo switchToNextIp(IpSwitchListener listener) {
        if (!isEnabled() || !isCoreRunning()) {
            if (listener != null) listener.onNoAvailableIp();
            return null;
        }
        String node = switchToNextNodeBlocking();
        if (node == null) {
            Log.w(TAG, "No available node to switch to");
            if (listener != null) listener.onNoAvailableIp();
            return null;
        }
        IpInfo info = new IpInfo("127.0.0.1", ClashProfileStore.MIXED_PORT, "http");
        info.name = node;
        info.actualProtocol = "clash";
        info.source = "内置内核";
        Log.d(TAG, "Switched to node: " + node);
        if (listener != null) listener.onIpSwitched(info);
        return info;
    }

    /** 同步切换，限流时由后台线程调用。 */
    public synchronized String switchToNextNodeBlocking() {
        return switchToNextNodeBlocking("auto-limit");
    }

    /** 同步切换，reason 用于下载日志（manual-next / auto-limit）。 */
    public synchronized String switchToNextNodeBlocking(String reason) {
        if (!isEnabled() || !isCoreRunning()) return null;
        String previous = core.getCurrentNode();
        final long switchStartMs = System.currentTimeMillis();
        java.util.Set<String> excluded = new java.util.HashSet<>();
        for (IpInfo ip : IpPoolManager.getInstance().getAllIps()) {
            if (!ip.isAvailable() && ip.name != null && !ip.name.isEmpty()) {
                excluded.add(ip.name);
            }
        }
        String node = core.switchToNextNode(Settings.getIpSwitchStrategy(), excluded);
        if (node != null) {
            finishSwitch(reason, previous, switchStartMs);
            if (previous != null) {
                // 风控解封时间：0 表示立即，允许马上再次选到旧节点。
                IpPoolManager.getInstance().markNodeBlocked(previous, Settings.getIpBlockDuration());
            }
        }
        return node;
    }

    public String fetchEgressIp() {
        return core.fetchEgressIp();
    }

    public boolean isProxyReady() {
        return isEnabled() && core.isProxyReady();
    }

    public int getAvailableIpCount() {
        return getTotalIpCount();
    }

    public int getTotalIpCount() {
        return ClashProfileStore.getNodes().size();
    }

    public void refreshCurrentIp() {
        if (!isEnabled()) return;
        if (!isCoreRunning()) {
            // 需要 Context，由调用方通过 ensureStarted 启动。
            return;
        }
    }

    /** 兼容旧调用：订阅节点不再单独标记被封，切换直接交给内核。 */
    public void reportDownloadSuccess() {
    }

    public void reportDownloadFailure(String error) {
    }

    public boolean shouldSwitch() {
        return isEnabled() && isCoreRunning();
    }
}
