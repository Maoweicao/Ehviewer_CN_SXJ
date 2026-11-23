package com.hippo.ehviewer.lab.ip;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.ip.core.ClashProfileBuilder;
import com.hippo.ehviewer.lab.ip.core.ClashProfileStore;
import com.hippo.ehviewer.lab.ip.model.IpInfo;
import com.hippo.ehviewer.lab.ip.model.Subscription;
import com.hippo.ehviewer.lab.ip.parser.SubscriptionParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubscriptionManager {
    private static final String TAG = "SubscriptionManager";
    private static SubscriptionManager instance;
    private final Context appContext;
    private final List<Subscription> subscriptions;
    private final Object lock = new Object();
    private Timer autoRefreshTimer;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public interface RefreshCallback {
        void onSuccess(int newIpCount);
        void onError(String error);
    }

    public interface SubscriptionRefreshCallback {
        void onStart(Subscription sub);
        void onResult(Subscription sub, List<IpInfo> newIps);
        void onError(Subscription sub, String error);
    }

    private SubscriptionManager(Context context) {
        appContext = context.getApplicationContext();
        subscriptions = new ArrayList<>();
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        loadFromSettings();
    }

    public static synchronized SubscriptionManager getInstance(Context context) {
        if (instance == null) {
            instance = new SubscriptionManager(context);
        }
        return instance;
    }

    /** 旧调用兼容；没有 Context 时无法构建内核配置。 */
    public static synchronized SubscriptionManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SubscriptionManager not initialized with Context");
        }
        return instance;
    }

    private void loadFromSettings() {
        synchronized (lock) {
            subscriptions.clear();
            String json = Settings.getIpSubscriptionList();
            try {
                JSONArray array = JSONArray.parseArray(json);
                if (array != null) {
                    for (int i = 0; i < array.size(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        Subscription sub = Subscription.fromJson(obj);
                        if (sub != null && sub.url != null && !sub.url.isEmpty()) {
                            subscriptions.add(sub);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load subscriptions", e);
            }
        }
    }

    private void saveToSettings() {
        synchronized (lock) {
            JSONArray array = new JSONArray();
            for (Subscription sub : subscriptions) {
                array.add(sub.toJson());
            }
            Settings.putIpSubscriptionList(array.toJSONString());
        }
    }

    public void addSubscription(Subscription sub) {
        synchronized (lock) {
            subscriptions.add(sub);
            saveToSettings();
            Log.d(TAG, "Added subscription: " + sub.name);
        }
    }

    public void updateSubscription(Subscription sub) {
        synchronized (lock) {
            for (int i = 0; i < subscriptions.size(); i++) {
                if (subscriptions.get(i).id.equals(sub.id)) {
                    subscriptions.set(i, sub);
                    break;
                }
            }
            saveToSettings();
        }
    }

    public void removeSubscription(String id) {
        synchronized (lock) {
            subscriptions.removeIf(sub -> sub.id.equals(id));
            saveToSettings();
            Log.d(TAG, "Removed subscription: " + id);
        }
    }

    public List<Subscription> getAllSubscriptions() {
        synchronized (lock) {
            return new ArrayList<>(subscriptions);
        }
    }

    public void startAutoRefresh() {
        stopAutoRefresh();
        int interval = Settings.getIpRefreshInterval() * 60 * 1000;
        autoRefreshTimer = new Timer();
        autoRefreshTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                refreshAllSubscriptions(null);
            }
        }, interval, interval);
        Log.d(TAG, "Started auto refresh with interval: " + interval + "ms");
    }

    public void stopAutoRefresh() {
        if (autoRefreshTimer != null) {
            autoRefreshTimer.cancel();
            autoRefreshTimer = null;
        }
    }

    public void refreshSubscription(Subscription sub, SubscriptionRefreshCallback callback) {
        if (sub == null || sub.isUpdating()) return;

        executor.execute(() -> {
            sub.markUpdating();
            synchronized (lock) {
                saveToSettings();
            }

            if (callback != null) {
                mainHandler.post(() -> callback.onStart(sub));
            }

            try {
                SubscriptionParser.ParseResult result = SubscriptionParser.parseWithHeaders(sub.url, sub.type);
                List<IpInfo> ips = applyParseResult(sub, result);
                sub.markRefreshed();
                sub.updateStatus = Subscription.STATUS_SUCCESS;
                Log.d(TAG, "Refreshed subscription: " + sub.name + " got "
                        + (ips == null ? 0 : ips.size()) + " nodes");

                synchronized (lock) {
                    saveToSettings();
                }

                final List<IpInfo> resultIps = ips;
                if (callback != null) {
                    mainHandler.post(() -> callback.onResult(sub, resultIps));
                }
            } catch (Exception e) {
                sub.markError(e.getMessage());
                Log.e(TAG, "Failed to refresh: " + sub.name, e);

                synchronized (lock) {
                    saveToSettings();
                }

                if (callback != null) {
                    mainHandler.post(() -> callback.onError(sub, e.getMessage()));
                }
            }
        });
    }

    /**
     * 应用一次订阅解析结果：Clash 订阅会生成内置内核配置并重建节点列表；
     * 其它类型仍按旧的 IP 池处理。
     */
    private List<IpInfo> applyParseResult(Subscription sub, SubscriptionParser.ParseResult result)
            throws Exception {
        if (result.trafficUsed > 0) sub.trafficUsed = result.trafficUsed;
        if (result.trafficTotal > 0) sub.trafficTotal = result.trafficTotal;
        if (result.expiry > 0) sub.expiry = result.expiry;

        if (isClashYaml(sub.type, result.rawContent)) {
            ClashProfileBuilder.Result profile = ClashProfileBuilder.build(
                    result.rawContent,
                    ClashProfileStore.MIXED_PORT,
                    ClashProfileStore.CONTROLLER_PORT);
            ClashProfileStore.save(appContext, profile, sub.name);
            sub.nodeCount = profile.nodes.size();
            Log.i(TAG, "Built Clash profile for " + sub.name + ": "
                    + profile.nodes.size() + " nodes, removed " + profile.removed);
            if (Settings.getIpSwitchEnabled()) {
                IpSwitchController.getInstance().restart(appContext);
            }
            List<IpInfo> ips = new ArrayList<>(profile.nodes.size());
            for (String node : profile.nodes) {
                IpInfo info = new IpInfo("clash", 0, "clash");
                info.name = node;
                info.actualProtocol = "clash";
                info.source = sub.name;
                ips.add(info);
            }
            return ips;
        }

        List<IpInfo> ips = result.ips;
        if (ips != null && !ips.isEmpty()) {
            for (IpInfo ip : ips) {
                ip.source = sub.name;
            }
            IpPoolManager.getInstance().addIps(ips);
            sub.nodeCount = IpPoolManager.getInstance().getIpsBySource(sub.name).size();
        }
        return ips;
    }

    private static boolean isClashYaml(String type, String content) {
        if (content == null || content.isEmpty()) return false;
        if (type != null) {
            String t = type.toLowerCase(java.util.Locale.US);
            if (t.equals("clash") || t.equals("mixed") || t.equals("auto")) {
                return content.contains("proxies:");
            }
        }
        return content.contains("proxies:") && content.contains("- {") ||
                content.contains("\nproxies:") || content.trim().startsWith("proxies:");
    }

    public void refreshAllSubscriptions(RefreshCallback callback) {
        executor.execute(() -> {
            int totalNewIps = 0;
            List<String> errors = new ArrayList<>();

            synchronized (lock) {
                for (Subscription sub : subscriptions) {
                    if (!sub.enabled || !sub.needsRefresh()) {
                        continue;
                    }

                    try {
                        SubscriptionParser.ParseResult result =
                                SubscriptionParser.parseWithHeaders(sub.url, sub.type);
                        List<IpInfo> ips = applyParseResult(sub, result);
                        if (ips != null && !ips.isEmpty()) {
                            totalNewIps += ips.size();
                            sub.markRefreshed();
                            sub.updateStatus = Subscription.STATUS_SUCCESS;
                            Log.d(TAG, "Refreshed subscription: " + sub.name +
                                    " got " + ips.size() + " nodes");
                        }
                    } catch (Exception e) {
                        sub.markError(e.getMessage());
                        errors.add(sub.name + ": " + e.getMessage());
                        Log.e(TAG, "Failed to refresh: " + sub.name, e);
                    }
                }
                saveToSettings();
            }

            final int newIps = totalNewIps;
            final String error = errors.isEmpty() ? null : String.join("\n", errors);

            if (callback != null) {
                mainHandler.post(() -> {
                    if (error != null) {
                        callback.onError(error);
                    } else {
                        callback.onSuccess(newIps);
                    }
                });
            }
        });
    }
}
