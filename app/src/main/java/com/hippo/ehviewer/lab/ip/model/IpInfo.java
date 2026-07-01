package com.hippo.ehviewer.lab.ip.model;

import com.alibaba.fastjson.JSONObject;

public class IpInfo {
    public String ip;
    public int port;
    public String protocol; // http, socks5 (connection protocol)
    public String actualProtocol; // vmess, vless, ss, ssr, trojan, http, socks5 (actual node type)
    public String username;
    public String password;
    public int downloadedCount;
    public int maxDownloadLimit;
    public long blockedUntil;
    public boolean isBlocked;
    public String source;
    public long lastUsedTime;
    public int priority;
    public String name;
    public int latency;
    public boolean enabled;
    public long lastTestTime;

    public IpInfo() {
        this.ip = "";
        this.port = 1080;
        this.protocol = "socks5";
        this.actualProtocol = "socks5";
        this.username = "";
        this.password = "";
        this.downloadedCount = 0;
        this.maxDownloadLimit = 50;
        this.blockedUntil = 0;
        this.isBlocked = false;
        this.source = "";
        this.lastUsedTime = 0;
        this.priority = 0;
        this.name = "";
        this.latency = -1;
        this.enabled = true;
        this.lastTestTime = 0;
    }

    public IpInfo(String ip, int port, String protocol) {
        this();
        this.ip = ip;
        this.port = port;
        this.protocol = protocol;
    }

    public boolean isAvailable() {
        if (!enabled) return false;
        if (isBlocked && System.currentTimeMillis() < blockedUntil) {
            return false;
        }
        if (isBlocked && System.currentTimeMillis() >= blockedUntil) {
            isBlocked = false;
            downloadedCount = 0;
        }
        return true;
    }

    public void markBlocked(int blockDurationMinutes) {
        isBlocked = true;
        blockedUntil = System.currentTimeMillis() + (blockDurationMinutes * 60L * 1000L);
    }

    public void resetStatus() {
        isBlocked = false;
        downloadedCount = 0;
        blockedUntil = 0;
    }

    public void incrementDownloadCount() {
        downloadedCount++;
    }

    public boolean isThresholdReached(int threshold) {
        return downloadedCount >= threshold;
    }

    public String getDisplayName() {
        if (name != null && !name.isEmpty()) return name;
        return protocol + "://" + ip + ":" + port;
    }

    public String getLatencyDisplay() {
        if (latency < 0) return "未测试";
        return latency + "ms";
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("ip", ip);
        json.put("port", port);
        json.put("protocol", protocol);
        json.put("actualProtocol", actualProtocol);
        json.put("username", username);
        json.put("password", password);
        json.put("downloadedCount", downloadedCount);
        json.put("maxDownloadLimit", maxDownloadLimit);
        json.put("blockedUntil", blockedUntil);
        json.put("isBlocked", isBlocked);
        json.put("source", source);
        json.put("lastUsedTime", lastUsedTime);
        json.put("priority", priority);
        json.put("name", name);
        json.put("latency", latency);
        json.put("enabled", enabled);
        json.put("lastTestTime", lastTestTime);
        return json;
    }

    public static IpInfo fromJson(JSONObject json) {
        IpInfo info = new IpInfo();
        if (json == null) return info;
        info.ip = json.getString("ip");
        info.port = json.getIntValue("port");
        info.protocol = json.getString("protocol");
        info.actualProtocol = json.getString("actualProtocol");
        info.username = json.getString("username");
        info.password = json.getString("password");
        info.downloadedCount = json.getIntValue("downloadedCount");
        info.maxDownloadLimit = json.getIntValue("maxDownloadLimit");
        info.blockedUntil = json.getLongValue("blockedUntil");
        info.isBlocked = json.getBooleanValue("isBlocked");
        info.source = json.getString("source");
        info.lastUsedTime = json.getLongValue("lastUsedTime");
        info.priority = json.getIntValue("priority");
        info.name = json.getString("name");
        info.latency = json.getIntValue("latency");
        if (json.containsKey("enabled")) {
            info.enabled = json.getBooleanValue("enabled");
        } else {
            info.enabled = true;
        }
        info.lastTestTime = json.getLongValue("lastTestTime");
        return info;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IpInfo ipInfo = (IpInfo) o;
        return port == ipInfo.port &&
                ip != null && ip.equals(ipInfo.ip) &&
                protocol != null && protocol.equals(ipInfo.protocol);
    }

    @Override
    public int hashCode() {
        int result = ip != null ? ip.hashCode() : 0;
        result = 31 * result + port;
        result = 31 * result + (protocol != null ? protocol.hashCode() : 0);
        return result;
    }
}
