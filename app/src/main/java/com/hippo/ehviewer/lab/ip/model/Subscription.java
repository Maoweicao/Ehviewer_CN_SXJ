package com.hippo.ehviewer.lab.ip.model;

import com.alibaba.fastjson.JSONObject;

public class Subscription {
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_UPDATING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_ERROR = 3;

    public String id;
    public String name;
    public String url;
    public String type; // ss, ssr, v2ray, clash, trojan, mixed
    public long lastRefreshTime;
    public int refreshInterval; // minutes
    public boolean enabled;
    public String remarks;
    public int nodeCount;
    public long trafficUsed;
    public long trafficTotal;
    public long expiry;
    public int updateStatus;
    public String errorMessage;

    public Subscription() {
        this.id = String.valueOf(System.currentTimeMillis());
        this.name = "";
        this.url = "";
        this.type = "mixed";
        this.lastRefreshTime = 0;
        this.refreshInterval = 60;
        this.enabled = true;
        this.remarks = "";
        this.nodeCount = 0;
        this.trafficUsed = 0;
        this.trafficTotal = 0;
        this.expiry = 0;
        this.updateStatus = STATUS_IDLE;
        this.errorMessage = "";
    }

    public Subscription(String name, String url, String type) {
        this();
        this.name = name;
        this.url = url;
        this.type = type;
    }

    public boolean needsRefresh() {
        if (!enabled) return false;
        long now = System.currentTimeMillis();
        long intervalMs = refreshInterval * 60L * 1000L;
        return (now - lastRefreshTime) >= intervalMs;
    }

    public void markRefreshed() {
        lastRefreshTime = System.currentTimeMillis();
        updateStatus = STATUS_IDLE;
        errorMessage = "";
    }

    public void markUpdating() {
        updateStatus = STATUS_UPDATING;
        errorMessage = "";
    }

    public void markError(String error) {
        updateStatus = STATUS_ERROR;
        errorMessage = error != null ? error : "";
    }

    public boolean isUpdating() {
        return updateStatus == STATUS_UPDATING;
    }

    public String getStatusText() {
        switch (updateStatus) {
            case STATUS_IDLE: return "空闲";
            case STATUS_UPDATING: return "更新中...";
            case STATUS_SUCCESS: return "更新成功";
            case STATUS_ERROR: return "更新失败";
            default: return "未知";
        }
    }

    public String getTrafficDisplay() {
        if (trafficTotal <= 0) return "";
        return formatBytes(trafficUsed) + " / " + formatBytes(trafficTotal);
    }

    public String getExpiryDisplay() {
        if (expiry <= 0) return "";
        long remain = expiry - System.currentTimeMillis();
        if (remain <= 0) return "已过期";
        long days = remain / (24 * 60 * 60 * 1000);
        if (days > 30) return ">30天";
        return days + "天";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1048576) return (bytes / 1024) + "KB";
        if (bytes < 1073741824) return String.format("%.1fMB", bytes / 1048576.0);
        return String.format("%.2fGB", bytes / 1073741824.0);
    }

    public String getTypeDisplayName() {
        if (type == null) return "Unknown";
        switch (type.toLowerCase()) {
            case "ss": return "Shadowsocks";
            case "ssr": return "ShadowsocksR";
            case "v2ray": return "V2Ray";
            case "vmess": return "VMess";
            case "vless": return "VLess";
            case "clash": return "Clash";
            case "trojan": return "Trojan";
            case "mixed": return "Mixed";
            default: return type.toUpperCase();
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("url", url);
        json.put("type", type);
        json.put("lastRefreshTime", lastRefreshTime);
        json.put("refreshInterval", refreshInterval);
        json.put("enabled", enabled);
        json.put("remarks", remarks);
        json.put("nodeCount", nodeCount);
        json.put("trafficUsed", trafficUsed);
        json.put("trafficTotal", trafficTotal);
        json.put("expiry", expiry);
        json.put("updateStatus", updateStatus);
        json.put("errorMessage", errorMessage);
        return json;
    }

    public static Subscription fromJson(JSONObject json) {
        Subscription sub = new Subscription();
        if (json == null) return sub;
        sub.id = json.getString("id");
        sub.name = json.getString("name");
        sub.url = json.getString("url");
        sub.type = json.getString("type");
        sub.lastRefreshTime = json.getLongValue("lastRefreshTime");
        sub.refreshInterval = json.getIntValue("refreshInterval");
        sub.enabled = json.getBooleanValue("enabled");
        sub.remarks = json.getString("remarks");
        sub.nodeCount = json.getIntValue("nodeCount");
        sub.trafficUsed = json.getLongValue("trafficUsed");
        sub.trafficTotal = json.getLongValue("trafficTotal");
        sub.expiry = json.getLongValue("expiry");
        sub.updateStatus = json.getIntValue("updateStatus");
        sub.errorMessage = json.getString("errorMessage");
        return sub;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Subscription that = (Subscription) o;
        return id != null ? id.equals(that.id) : that.id == null;
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
