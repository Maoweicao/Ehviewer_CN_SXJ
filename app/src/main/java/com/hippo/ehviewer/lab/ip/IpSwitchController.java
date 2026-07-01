package com.hippo.ehviewer.lab.ip;

import android.util.Log;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.ip.model.IpInfo;

import java.net.InetSocketAddress;
import java.net.Proxy;

public class IpSwitchController {
    private static final String TAG = "IpSwitchController";
    private static IpSwitchController instance;
    private IpInfo currentIp;
    private final IpPoolManager poolManager;
    private final IpStatusTracker statusTracker;

    public interface IpSwitchListener {
        void onIpSwitched(IpInfo newIp);
        void onNoAvailableIp();
    }

    private IpSwitchController() {
        poolManager = IpPoolManager.getInstance();
        statusTracker = IpStatusTracker.getInstance();
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

    public IpInfo getCurrentIp() {
        if (!isEnabled()) return null;
        return currentIp;
    }

    public Proxy getCurrentProxy() {
        if (!isEnabled() || currentIp == null) {
            return null;
        }

        try {
            InetSocketAddress addr = new InetSocketAddress(currentIp.ip, currentIp.port);
            if ("http".equals(currentIp.protocol)) {
                return new Proxy(Proxy.Type.HTTP, addr);
            } else {
                return new Proxy(Proxy.Type.SOCKS, addr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create proxy", e);
            return null;
        }
    }

    public IpInfo switchToNextIp(IpSwitchListener listener) {
        if (!isEnabled()) {
            if (listener != null) {
                listener.onNoAvailableIp();
            }
            return null;
        }

        IpInfo nextIp = poolManager.getNextAvailableIp();
        if (nextIp == null) {
            Log.w(TAG, "No available IPs");
            if (listener != null) {
                listener.onNoAvailableIp();
            }
            return null;
        }

        currentIp = nextIp;
        poolManager.markIpUsed(nextIp);

        Log.d(TAG, "Switched to IP: " + nextIp.getDisplayName());
        if (listener != null) {
            listener.onIpSwitched(nextIp);
        }

        return nextIp;
    }

    public void reportDownloadSuccess() {
        if (!isEnabled() || currentIp == null) return;

        statusTracker.recordDownload(currentIp);
        poolManager.incrementIpDownloadCount(currentIp);
    }

    public void reportDownloadFailure(String error) {
        if (!isEnabled() || currentIp == null) return;

        if (isRateLimitError(error)) {
            Log.w(TAG, "Rate limit detected for " + currentIp.getDisplayName());
            statusTracker.markBlocked(currentIp, error);
            poolManager.markIpBlocked(currentIp);
        }
    }

    private boolean isRateLimitError(String error) {
        if (error == null) return false;

        String lowerError = error.toLowerCase();
        return lowerError.contains("exceeds the limit") ||
                lowerError.contains("too many requests") ||
                lowerError.contains("rate limit") ||
                lowerError.contains("429") ||
                lowerError.contains("509") ||
                lowerError.contains("bandwidth limit exceeded");
    }

    public boolean shouldSwitch() {
        if (!isEnabled() || currentIp == null) return false;

        return statusTracker.isBlocked(currentIp);
    }

    public int getAvailableIpCount() {
        return poolManager.getAvailableCount();
    }

    public int getTotalIpCount() {
        return poolManager.getTotalCount();
    }

    public void refreshCurrentIp() {
        if (!isEnabled()) return;

        if (currentIp == null || statusTracker.isBlocked(currentIp)) {
            switchToNextIp(null);
        }
    }
}
