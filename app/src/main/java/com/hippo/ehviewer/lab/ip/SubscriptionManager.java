package com.hippo.ehviewer.lab.ip;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
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

    private SubscriptionManager() {
        subscriptions = new ArrayList<>();
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        loadFromSettings();
    }

    public static synchronized SubscriptionManager getInstance() {
        if (instance == null) {
            instance = new SubscriptionManager();
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
                List<IpInfo> ips = result.ips;

                if (result.trafficUsed > 0) sub.trafficUsed = result.trafficUsed;
                if (result.trafficTotal > 0) sub.trafficTotal = result.trafficTotal;
                if (result.expiry > 0) sub.expiry = result.expiry;

                if (ips != null && !ips.isEmpty()) {
                    for (IpInfo ip : ips) {
                        ip.source = sub.name;
                    }
                    IpPoolManager.getInstance().addIps(ips);
                    sub.nodeCount = IpPoolManager.getInstance().getIpsBySource(sub.name).size();
                }
                sub.markRefreshed();
                sub.updateStatus = Subscription.STATUS_SUCCESS;
                Log.d(TAG, "Refreshed subscription: " + sub.name + " got " + ips.size() + " IPs");

                synchronized (lock) {
                    saveToSettings();
                }

                if (callback != null) {
                    mainHandler.post(() -> callback.onResult(sub, ips));
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
                        List<IpInfo> ips = SubscriptionParser.parse(sub.url, sub.type);
                        if (ips != null && !ips.isEmpty()) {
                            for (IpInfo ip : ips) {
                                ip.source = sub.name;
                            }
                            IpPoolManager.getInstance().addIps(ips);
                            totalNewIps += ips.size();
                            sub.nodeCount = ips.size();
                            sub.markRefreshed();
                            sub.updateStatus = Subscription.STATUS_SUCCESS;
                            Log.d(TAG, "Refreshed subscription: " + sub.name +
                                    " got " + ips.size() + " IPs");
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
