package com.hippo.ehviewer.lab.ip;

import android.util.Log;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.ip.model.IpInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IpStatusTracker {
    private static final String TAG = "IpStatusTracker";
    private static IpStatusTracker instance;
    private final Map<String, IpStatus> statusMap;

    public static class IpStatus {
        public int downloadedCount;
        public long lastUsedTime;
        public long blockedUntil;
        public boolean isBlocked;
        public String lastError;

        public IpStatus() {
            this.downloadedCount = 0;
            this.lastUsedTime = 0;
            this.blockedUntil = 0;
            this.isBlocked = false;
            this.lastError = null;
        }
    }

    private IpStatusTracker() {
        statusMap = new ConcurrentHashMap<>();
    }

    public static synchronized IpStatusTracker getInstance() {
        if (instance == null) {
            instance = new IpStatusTracker();
        }
        return instance;
    }

    public void recordDownload(IpInfo ip) {
        if (ip == null) return;

        String key = getKey(ip);
        IpStatus status = statusMap.get(key);
        if (status == null) {
            status = new IpStatus();
            statusMap.put(key, status);
        }

        status.downloadedCount++;
        status.lastUsedTime = System.currentTimeMillis();

        int threshold = Settings.getIpDownloadThreshold();
        if (status.downloadedCount >= threshold) {
            markBlocked(ip, "Threshold reached");
        }

        Log.d(TAG, "Recorded download for " + ip.getDisplayName() +
                " count: " + status.downloadedCount);
    }

    public void markBlocked(IpInfo ip, String reason) {
        if (ip == null) return;

        String key = getKey(ip);
        IpStatus status = statusMap.get(key);
        if (status == null) {
            status = new IpStatus();
            statusMap.put(key, status);
        }

        status.isBlocked = true;
        status.blockedUntil = System.currentTimeMillis() +
                (Settings.getIpBlockDuration() * 60L * 1000L);
        status.lastError = reason;

        ip.markBlocked(Settings.getIpBlockDuration());

        Log.d(TAG, "Marked " + ip.getDisplayName() + " as blocked: " + reason);
    }

    public void markAvailable(IpInfo ip) {
        if (ip == null) return;

        String key = getKey(ip);
        IpStatus status = statusMap.get(key);
        if (status != null) {
            status.isBlocked = false;
            status.downloadedCount = 0;
            status.blockedUntil = 0;
            status.lastError = null;
        }

        ip.resetStatus();

        Log.d(TAG, "Marked " + ip.getDisplayName() + " as available");
    }

    public boolean isBlocked(IpInfo ip) {
        if (ip == null) return true;

        String key = getKey(ip);
        IpStatus status = statusMap.get(key);
        if (status == null) return false;

        if (status.isBlocked && System.currentTimeMillis() >= status.blockedUntil) {
            // Unblock
            status.isBlocked = false;
            status.downloadedCount = 0;
            ip.resetStatus();
            return false;
        }

        return status.isBlocked;
    }

    public int getDownloadCount(IpInfo ip) {
        if (ip == null) return 0;

        String key = getKey(ip);
        IpStatus status = statusMap.get(key);
        return status != null ? status.downloadedCount : 0;
    }

    public IpStatus getStatus(IpInfo ip) {
        if (ip == null) return new IpStatus();

        String key = getKey(ip);
        return statusMap.getOrDefault(key, new IpStatus());
    }

    public void clearAll() {
        statusMap.clear();
        Log.d(TAG, "Cleared all status");
    }

    private String getKey(IpInfo ip) {
        return ip.ip + ":" + ip.port;
    }
}
