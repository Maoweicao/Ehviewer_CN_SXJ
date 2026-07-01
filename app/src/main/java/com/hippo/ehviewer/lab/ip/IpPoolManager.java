package com.hippo.ehviewer.lab.ip;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.ip.model.IpInfo;
import com.hippo.ehviewer.lab.ip.model.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IpPoolManager {
    private static final String TAG = "IpPoolManager";
    private static IpPoolManager instance;
    private final List<IpInfo> ipPool;
    private final Object lock = new Object();
    private int currentIndex = 0;

    private IpPoolManager() {
        ipPool = new ArrayList<>();
        loadFromSettings();
    }

    public static synchronized IpPoolManager getInstance() {
        if (instance == null) {
            instance = new IpPoolManager();
        }
        return instance;
    }

    private void loadFromSettings() {
        synchronized (lock) {
            ipPool.clear();
            String json = Settings.getIpSubscriptionList();
            try {
                JSONArray array = JSONArray.parseArray(json);
                if (array != null) {
                    for (int i = 0; i < array.size(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        IpInfo info = IpInfo.fromJson(obj);
                        if (info != null && info.ip != null && !info.ip.isEmpty()) {
                            ipPool.add(info);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load IP pool", e);
            }
        }
    }

    private void saveToSettings() {
        synchronized (lock) {
            JSONArray array = new JSONArray();
            for (IpInfo info : ipPool) {
                array.add(info.toJson());
            }
            Settings.putIpSubscriptionList(array.toJSONString());
        }
    }

    public void addIp(IpInfo ip) {
        synchronized (lock) {
            if (!ipPool.contains(ip)) {
                ipPool.add(ip);
                saveToSettings();
                Log.d(TAG, "Added IP: " + ip.getDisplayName());
            }
        }
    }

    public void addIps(List<IpInfo> ips) {
        synchronized (lock) {
            for (IpInfo ip : ips) {
                if (!ipPool.contains(ip)) {
                    ipPool.add(ip);
                }
            }
            saveToSettings();
            Log.d(TAG, "Added " + ips.size() + " IPs");
        }
    }

    public void removeIp(IpInfo ip) {
        synchronized (lock) {
            ipPool.remove(ip);
            saveToSettings();
        }
    }

    public void clearAll() {
        synchronized (lock) {
            ipPool.clear();
            currentIndex = 0;
            saveToSettings();
            Log.d(TAG, "Cleared all IPs");
        }
    }

    public List<IpInfo> getAllIps() {
        synchronized (lock) {
            return new ArrayList<>(ipPool);
        }
    }

    public List<IpInfo> getIpsBySource(String source) {
        synchronized (lock) {
            List<IpInfo> result = new ArrayList<>();
            for (IpInfo ip : ipPool) {
                if (source != null && source.equals(ip.source)) {
                    result.add(ip);
                }
            }
            return result;
        }
    }

    public List<IpInfo> getAvailableIps() {
        synchronized (lock) {
            List<IpInfo> available = new ArrayList<>();
            for (IpInfo ip : ipPool) {
                if (ip.isAvailable()) {
                    available.add(ip);
                }
            }
            return available;
        }
    }

    public int getTotalCount() {
        synchronized (lock) {
            return ipPool.size();
        }
    }

    public int getAvailableCount() {
        synchronized (lock) {
            int count = 0;
            for (IpInfo ip : ipPool) {
                if (ip.isAvailable()) {
                    count++;
                }
            }
            return count;
        }
    }

    public int getBlockedCount() {
        synchronized (lock) {
            int count = 0;
            for (IpInfo ip : ipPool) {
                if (!ip.isAvailable()) {
                    count++;
                }
            }
            return count;
        }
    }

    public IpInfo getNextAvailableIp() {
        synchronized (lock) {
            int strategy = Settings.getIpSwitchStrategy();
            List<IpInfo> available = getAvailableIps();

            if (available.isEmpty()) {
                Log.w(TAG, "No available IPs");
                return null;
            }

            switch (strategy) {
                case 0: // Round robin
                    return getNextRoundRobin(available);
                case 1: // Random
                    return getRandomIp(available);
                case 2: // Priority
                    return getHighestPriority(available);
                case 3: // Lowest latency
                    return getLowestLatency(available);
                default:
                    return getNextRoundRobin(available);
            }
        }
    }

    private IpInfo getNextRoundRobin(List<IpInfo> available) {
        if (available.isEmpty()) return null;
        currentIndex = currentIndex % available.size();
        IpInfo ip = available.get(currentIndex);
        currentIndex = (currentIndex + 1) % available.size();
        return ip;
    }

    private IpInfo getRandomIp(List<IpInfo> available) {
        if (available.isEmpty()) return null;
        int index = (int) (Math.random() * available.size());
        return available.get(index);
    }

    private IpInfo getHighestPriority(List<IpInfo> available) {
        if (available.isEmpty()) return null;
        IpInfo highest = available.get(0);
        for (IpInfo ip : available) {
            if (ip.priority > highest.priority) {
                highest = ip;
            }
        }
        return highest;
    }

    private IpInfo getLowestLatency(List<IpInfo> available) {
        if (available.isEmpty()) return null;
        IpInfo best = available.get(0);
        for (IpInfo ip : available) {
            int ipLat = ip.latency >= 0 ? ip.latency : Integer.MAX_VALUE;
            int bestLat = best.latency >= 0 ? best.latency : Integer.MAX_VALUE;
            if (ipLat < bestLat) {
                best = ip;
            }
        }
        return best;
    }

    public void toggleIpEnabled(IpInfo ip) {
        synchronized (lock) {
            for (IpInfo poolIp : ipPool) {
                if (poolIp.equals(ip)) {
                    poolIp.enabled = !poolIp.enabled;
                    break;
                }
            }
            saveToSettings();
        }
    }

    public void setIpEnabled(IpInfo ip, boolean enabled) {
        synchronized (lock) {
            for (IpInfo poolIp : ipPool) {
                if (poolIp.equals(ip)) {
                    poolIp.enabled = enabled;
                    break;
                }
            }
            saveToSettings();
        }
    }

    public void updateIpLatency(IpInfo ip, int latency) {
        synchronized (lock) {
            for (IpInfo poolIp : ipPool) {
                if (poolIp.equals(ip)) {
                    poolIp.latency = latency;
                    poolIp.lastTestTime = System.currentTimeMillis();
                    break;
                }
            }
            saveToSettings();
        }
    }

    public void markIpBlocked(IpInfo ip) {
        synchronized (lock) {
            int blockDuration = Settings.getIpBlockDuration();
            ip.markBlocked(blockDuration);
            saveToSettings();
            Log.d(TAG, "Marked IP as blocked: " + ip.getDisplayName() +
                    " for " + blockDuration + " minutes");
        }
    }

    public void incrementIpDownloadCount(IpInfo ip) {
        synchronized (lock) {
            ip.incrementDownloadCount();
            int threshold = Settings.getIpDownloadThreshold();
            if (ip.isThresholdReached(threshold)) {
                markIpBlocked(ip);
            }
            saveToSettings();
        }
    }

    public void resetIpStatus(IpInfo ip) {
        synchronized (lock) {
            ip.resetStatus();
            saveToSettings();
        }
    }

    public IpInfo getCurrentIp() {
        synchronized (lock) {
            if (ipPool.isEmpty()) return null;
            for (IpInfo ip : ipPool) {
                if (ip.lastUsedTime > 0 && ip.isAvailable()) {
                    return ip;
                }
            }
            return getNextAvailableIp();
        }
    }

    public void markIpUsed(IpInfo ip) {
        synchronized (lock) {
            ip.lastUsedTime = System.currentTimeMillis();
            saveToSettings();
        }
    }
}
