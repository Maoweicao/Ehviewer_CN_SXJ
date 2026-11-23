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

package com.hippo.ehviewer.lab.ip.core;

import android.content.Context;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.lab.ip.IpPoolManager;
import com.hippo.ehviewer.limit.LimitProbe;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 内置 Mihomo(Clash.Meta) 内核管理。
 *
 * 内核以 libmihomo.so 的形式随 APK 打包，安装后位于 nativeLibraryDir，
 * 可直接执行。内核只监听本机 127.0.0.1，不创建系统 VPN；应用通过
 * 本地 HTTP 代理访问外网，因此不会使用系统全局 VPN 的代理设置。
 */
public final class MihomoCore {

    private static final String TAG = "MihomoCore";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String DELAY_URL = "https://e-hentai.org/favicon.ico";
    private static final int START_TIMEOUT_MS = 15_000;

    private static volatile MihomoCore instance;
    private final Object lock = new Object();

    private Process process;
    private volatile boolean ready;
    private volatile boolean healthy;
    private String lastError = "";
    private String currentGroup = ClashProfileBuilder.GROUP_SELECT;
    private volatile String currentNodeName;
    private int rrIndex = 0;

    private final OkHttpClient controllerClient = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build();

    private MihomoCore() {
    }

    public static MihomoCore getInstance() {
        if (instance == null) {
            synchronized (MihomoCore.class) {
                if (instance == null) {
                    instance = new MihomoCore();
                }
            }
        }
        return instance;
    }

    public boolean isRunning() {
        if (!ready) return false;
        Process p = process;
        if (p != null) return p.isAlive();
        // 复用上一个进程留下的内核
        return portOpen(ClashProfileStore.MIXED_PORT);
    }

    /** 内核已启动且至少有一个可用节点，才允许应用把流量交给它。 */
    public boolean isProxyReady() {
        return isRunning() && healthy;
    }

    public String getLastError() {
        return lastError;
    }

    public synchronized boolean start(Context context) {
        if (isRunning()) {
            return true;
        }
        ready = false;
        healthy = false;
        currentNodeName = null;
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libmihomo.so");
        if (!binary.isFile()) {
            lastError = "当前设备缺少内置内核（仅支持 arm64 设备）";
            return false;
        }
        if (!binary.canExecute()) {
            //noinspection ResultOfMethodCallIgnored
            binary.setExecutable(true, false);
        }
        if (!ClashProfileStore.hasProfile(context)) {
            lastError = "还没有可用的 Clash 订阅配置";
            return false;
        }
        if (portOpen(ClashProfileStore.MIXED_PORT)
                && portOpen(ClashProfileStore.CONTROLLER_PORT)) {
            // 上一个进程留下的内核仍在运行：直接复用，避免端口冲突。
            ready = true;
            lastError = "";
            healthy = false;
            Log.i(TAG, "Reusing running mihomo core");
            return true;
        }
        if (portOpen(ClashProfileStore.MIXED_PORT)
                || portOpen(ClashProfileStore.CONTROLLER_PORT)) {
            lastError = "本地代理端口被占用（" + ClashProfileStore.MIXED_PORT + "）";
            return false;
        }

        File log = ClashProfileStore.logFile(context);
        appendLogSeparator(log);
        ProcessBuilder builder = new ProcessBuilder(
                binary.getAbsolutePath(),
                "-d", ClashProfileStore.workDir(context).getAbsolutePath(),
                "-f", ClashProfileStore.configFile(context).getAbsolutePath()
        );
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        try {
            process = builder.start();
        } catch (IOException e) {
            lastError = "启动内核失败: " + e.getMessage();
            Log.e(TAG, lastError, e);
            process = null;
            return false;
        }

        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (process == null || !process.isAlive()) {
                lastError = "内核启动后退出: " + tailLog(log, 1200);
                process = null;
                return false;
            }
            if (portOpen(ClashProfileStore.MIXED_PORT)
                    && portOpen(ClashProfileStore.CONTROLLER_PORT)) {
                lastError = "";
                ready = true;
                healthy = false;
                Log.i(TAG, "Mihomo core started");
                return true;
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        lastError = "内核启动超时: " + tailLog(log, 1200);
        stop();
        return false;
    }

    public synchronized void stop() {
        ready = false;
        healthy = false;
        currentNodeName = null;
        Process p = process;
        process = null;
        if (p == null) return;
        p.destroy();
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        Log.i(TAG, "Mihomo core stopped");
    }

    public synchronized void restart(Context context) {
        stop();
        start(context);
    }

    /** 让控制器重新加载 config.yaml（订阅更新后调用）。 */
    public boolean reloadConfig(Context context) {
        if (!isRunning()) return false;
        try {
            JSONObject body = new JSONObject();
            body.put("path", ClashProfileStore.configFile(context).getAbsolutePath());
            String url = "http://127.0.0.1:" + ClashProfileStore.CONTROLLER_PORT
                    + "/configs?force=true";
            Request request = new Request.Builder()
                    .url(url)
                    .put(RequestBody.create(JSON, body.toJSONString()))
                    .build();
            try (Response response = controllerClient.newCall(request).execute()) {
                boolean ok = response.isSuccessful();
                Log.i(TAG, "Reload config: " + ok);
                return ok;
            }
        } catch (Exception e) {
            Log.w(TAG, "Reload config failed: " + e.getMessage());
            return false;
        }
    }

    public void ensureHealthy() {
        if (isRunning()) {
            healthy = ensureWorkingNode(8, 2500);
            refreshCurrentNode();
        }
    }

    /** 选择自动组后由内核自行健康检查，这里直接视为可用。 */
    public void markHealthy() {
        healthy = true;
    }

    // ==================== 控制接口 ====================

    /**
     * 当前节点名（缓存）。
     * 主线程会频繁轮询状态，这里绝不能发起网络请求：Android 主线程网络会抛
     * NetworkOnMainThreadException，表现为“内核运行中但当前节点永远为空”。
     * 缓存由后台线程的 {@link #refreshCurrentNode()} 与切换流程更新。
     */
    public String getCurrentNode() {
        return isRunning() ? currentNodeName : null;
    }

    /** 后台线程：向控制器查询当前节点并刷新缓存。 */
    public String refreshCurrentNode() {
        if (!isRunning()) {
            currentNodeName = null;
            return null;
        }
        JSONObject group = getGroup(currentGroup);
        String name = group == null ? null : resolveCurrent(group);
        if (name == null || name.isEmpty()) {
            name = null;
        } else {
            currentNodeName = name;
        }
        return name;
    }

    private void setCurrentNode(String name) {
        currentNodeName = name == null || name.isEmpty() ? null : name;
    }

    private String resolveCurrent(JSONObject group) {
        String now = group.getString("now");
        if (ClashProfileBuilder.GROUP_AUTO.equals(now)) {
            JSONObject auto = getGroup(ClashProfileBuilder.GROUP_AUTO);
            String real = auto == null ? null : auto.getString("now");
            if (real != null && !real.isEmpty()) {
                return real;
            }
        }
        return now;
    }

    public List<String> getNodes() {
        List<String> nodes = new ArrayList<>();
        JSONObject group = getGroup(currentGroup);
        if (group == null) return nodes;
        JSONArray all = group.getJSONArray("all");
        if (all == null) return nodes;
        for (int i = 0; i < all.size(); i++) {
            String name = all.getString(i);
            if (name == null || name.isEmpty()) continue;
            if (name.equals(ClashProfileBuilder.GROUP_SELECT)
                    || name.equals(ClashProfileBuilder.GROUP_AUTO)
                    || "DIRECT".equals(name) || "REJECT".equals(name)) {
                continue;
            }
            nodes.add(name);
        }
        return nodes;
    }

    /**
     * 按策略切换到下一个真实节点。
     *
     * @return 新节点名；失败返回 null
     */
    public synchronized String switchToNextNode(int strategy) {
        return switchToNextNode(strategy, java.util.Collections.emptySet());
    }

    /**
     * 按策略切换到下一个真实节点。
     *
     * @param excluded 暂时不要选择的节点名（风控冷却中的节点）
     * @return 新节点名；失败返回 null
     */
    public synchronized String switchToNextNode(int strategy, java.util.Set<String> excluded) {
        if (!isRunning()) return null;
        JSONObject group = getGroup(currentGroup);
        if (group == null) return null;
        JSONArray all = group.getJSONArray("all");
        if (all == null || all.isEmpty()) return null;
        String now = resolveCurrent(group);

        List<String> nodes = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            String name = all.getString(i);
            if (name == null || name.isEmpty()) continue;
            if (name.equals(ClashProfileBuilder.GROUP_SELECT)
                    || name.equals(ClashProfileBuilder.GROUP_AUTO)
                    || "DIRECT".equals(name) || "REJECT".equals(name)) {
                continue;
            }
            if (excluded != null && excluded.contains(name)) {
                continue;
            }
            nodes.add(name);
        }
        if (nodes.isEmpty()) return null;

        int current = nodes.indexOf(now);
        int size = nodes.size();

        if (strategy == 3) {
            // 最低延迟：优先采用“自动选择”当前节点。
            JSONObject auto = getGroup(ClashProfileBuilder.GROUP_AUTO);
            String autoNow = auto == null ? null : auto.getString("now");
            if (autoNow != null && !autoNow.equals(now) && nodes.contains(autoNow)
                    && testThroughProxy(autoNow, 2500) && selectNode(autoNow)) {
                healthy = true;
                return autoNow;
            }
        }

        if (strategy == 1) {
            // 随机尝试最多 3 个，必须通过真实 e-hentai 请求才算成功。
            int attempts = Math.min(3, size);
            for (int i = 0; i < attempts; i++) {
                int idx = (int) (Math.random() * size);
                String candidate = nodes.get(idx);
                if (candidate.equals(now)) continue;
                if (testThroughProxy(candidate, 2500) && selectNode(candidate)) {
                    healthy = true;
                    return candidate;
                }
            }
            return null;
        }

        // 轮询：逐个做真实请求验证，不再用不可靠的 /delay 直接选中。
        // 失效节点可能连续出现，多试几个，避免“没有可切换节点”。
        int attempts = Math.min(8, size);
        for (int attempt = 0; attempt < attempts; attempt++) {
            rrIndex = (rrIndex + 1) % size;
            String candidate = nodes.get(rrIndex);
            if (candidate.equals(now)) continue;
            if (testThroughProxy(candidate, 2500) && selectNode(candidate)) {
                healthy = true;
                return candidate;
            }
        }
        return null;
    }

    /** 关闭内核当前所有活动连接，切换节点后让请求立刻走新节点。 */
    public boolean closeAllConnections() {
        if (!isRunning()) return false;
        try {
            String url = "http://127.0.0.1:" + ClashProfileStore.CONTROLLER_PORT
                    + "/connections";
            Request request = new Request.Builder().url(url).delete().build();
            try (Response response = controllerClient.newCall(request).execute()) {
                Log.i(TAG, "Close core connections: " + response.isSuccessful());
                return response.isSuccessful();
            }
        } catch (Exception e) {
            Log.w(TAG, "Close core connections failed: " + e.getMessage());
            return false;
        }
    }

    /** 读取内核活动连接摘要，用于诊断。 */
    public JSONObject getConnectionsSnapshot() {
        if (!isRunning()) return null;
        try {
            String url = "http://127.0.0.1:" + ClashProfileStore.CONTROLLER_PORT
                    + "/connections";
            Request request = new Request.Builder().url(url).build();
            try (Response response = controllerClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                String body = response.body() == null ? "" : response.body().string();
                return JSONObject.parseObject(body);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public boolean selectNode(String name) {
        if (name == null || name.isEmpty() || !isRunning()) return false;
        try {
            JSONObject body = new JSONObject();
            body.put("name", name);
            String url = "http://127.0.0.1:" + ClashProfileStore.CONTROLLER_PORT
                    + "/proxies/" + encode(currentGroup);
            Request request = new Request.Builder()
                    .url(url)
                    .put(RequestBody.create(JSON, body.toJSONString()))
                    .build();
            try (Response response = controllerClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    setCurrentNode(name);
                }
                return response.isSuccessful();
            }
        } catch (Exception e) {
            Log.w(TAG, "selectNode failed: " + e.getMessage());
            return false;
        }
    }

    public long testDelay(String name, int timeoutMs) {
        if (name == null || name.isEmpty() || !isRunning()) return -1;
        try {
            String url = "http://127.0.0.1:" + ClashProfileStore.CONTROLLER_PORT
                    + "/proxies/" + encode(name) + "/delay?timeout=" + timeoutMs
                    + "&url=" + encode(DELAY_URL);
            Request request = new Request.Builder().url(url).build();
            try (Response response = controllerClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return -1;
                String body = response.body() == null ? "" : response.body().string();
                JSONObject json = JSONObject.parseObject(body);
                return json == null ? -1 : json.getLongValue("delay");
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /** 通过内置代理查询当前出口 IP。 */
    public String fetchEgressIp() {
        if (!isRunning()) return null;
        OkHttpClient client = new OkHttpClient.Builder()
                .proxy(new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress("127.0.0.1", ClashProfileStore.MIXED_PORT)))
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();
        String[] urls = {
                "https://api.ipify.org",
                "https://www.cloudflare.com/cdn-cgi/trace",
        };
        for (String url : urls) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .header("Accept", "text/plain, */*")
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    String body = response.body() == null ? null : response.body().string();
                    String ip = LimitProbe.parsePublicIp(body);
                    if (response.isSuccessful() && ip != null) {
                        return ip;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ==================== 内部 ====================

    /** 启动后确认当前节点可用；不可用时依次测试少量节点并选中第一个可用的。 */
    private boolean ensureWorkingNode(int maxTests, int timeoutMs) {
        // 必须向控制器读取真实选择，不能只看缓存：进程重启后缓存为空，
        // 否则会把用户上次选中的节点误判为“无节点”而重新自动选择。
        String now = refreshCurrentNode();
        if (now != null && testThroughProxy(now, timeoutMs)) {
            return true;
        }

        // 自动选择组通常已经通过 url-test 选出一个可用节点，优先试它。
        JSONObject auto = null;
        String autoNow = null;
        for (int i = 0; i < 20; i++) {
            auto = getGroup(ClashProfileBuilder.GROUP_AUTO);
            autoNow = auto == null ? null : auto.getString("now");
            if (autoNow != null && !autoNow.isEmpty()) {
                break;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (autoNow != null && !autoNow.isEmpty() && !autoNow.equals(now)
                && testThroughProxy(autoNow, timeoutMs) && selectNode(autoNow)) {
            Log.i(TAG, "Startup selected auto node: " + autoNow);
            logNodeChange(now, autoNow, "startup-auto");
            return true;
        }

        List<String> nodes = getNodes();
        int tested = 0;
        boolean selected = false;
        for (String node : nodes) {
            if (node.equals(now) || node.equals(autoNow)) continue;
            if (tested++ >= maxTests) break;
            if (testThroughProxy(node, timeoutMs) && selectNode(node)) {
                Log.i(TAG, "Startup selected working node: " + node);
                logNodeChange(now, node, "startup-health");
                selected = true;
                break;
            }
        }
        if (selected && now != null && !now.isEmpty()) {
            // 启动时发现旧节点不可用，按“风控解封时间”冷却它。
            IpPoolManager.getInstance().markNodeBlocked(now,
                    com.hippo.ehviewer.Settings.getIpBlockDuration());
        }
        if (!selected) {
            Log.w(TAG, "No working node found during startup health check");
        }
        return selected;
    }

    /**
     * 通过内核的 /delay 接口验证节点能否访问 e-hentai。
     *
     * 重要：这里绝不能修改全局选择器。旧实现“切到候选节点→请求→失败切回”
     * 会让其他下载线程短暂走到候选节点，并在并发时把 previous 互相覆盖，
     * 表现为节点莫名跳动。
     */
    public boolean testThroughProxy(String node, int timeoutMs) {
        if (node == null || node.isEmpty() || !isRunning()) return false;
        return testDelay(node, timeoutMs) >= 0;
    }

    /** 记录启动/健康检查引起的节点变化，便于事后分析。 */
    private void logNodeChange(String from, String to, String reason) {
        try {
            com.hippo.ehviewer.download.DownloadLogger logger =
                    com.hippo.ehviewer.download.DownloadLogger.peek();
            if (logger != null) {
                logger.logSwitchEvent(null, from, to, reason, 0, -1);
            }
        } catch (Throwable ignored) {
        }
    }

    private JSONObject getGroup(String name) {
        try {
            String url = "http://127.0.0.1:" + ClashProfileStore.CONTROLLER_PORT
                    + "/proxies/" + encode(name);
            Request request = new Request.Builder().url(url).build();
            try (Response response = controllerClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                String body = response.body() == null ? "" : response.body().string();
                return JSONObject.parseObject(body);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void appendLogSeparator(File log) {
        try (RandomAccessFile raf = new RandomAccessFile(log, "a")) {
            raf.seek(raf.length());
            raf.write(("\n\n===== " + new java.util.Date() + " =====\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String tailLog(File log, int maxBytes) {
        if (!log.isFile()) return "";
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            long length = raf.length();
            long start = Math.max(0, length - maxBytes);
            raf.seek(start);
            byte[] buffer = new byte[(int) (length - start)];
            raf.readFully(buffer);
            String text = new String(buffer, StandardCharsets.UTF_8);
            int nl = text.indexOf('\n');
            if (start > 0 && nl >= 0) {
                text = text.substring(nl + 1);
            }
            return text.trim();
        } catch (Exception e) {
            return "";
        }
    }
}
