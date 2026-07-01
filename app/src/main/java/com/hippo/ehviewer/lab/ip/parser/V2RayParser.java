package com.hippo.ehviewer.lab.ip.parser;

import android.util.Base64;
import android.util.Log;

import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.lab.ip.model.IpInfo;

import java.util.ArrayList;
import java.util.List;

public class V2RayParser {
    private static final String TAG = "V2RayParser";

    public static List<IpInfo> parse(String content) throws Exception {
        List<IpInfo> ips = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return ips;
        }

        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("vmess://") || line.startsWith("vless://")) {
                ips.addAll(parseLine(line));
            }
        }
        return ips;
    }

    public static List<IpInfo> parseLine(String line) throws Exception {
        List<IpInfo> ips = new ArrayList<>();
        if (line == null) return ips;

        try {
            if (line.startsWith("vmess://")) {
                ips.addAll(parseVMess(line));
            } else if (line.startsWith("vless://")) {
                ips.addAll(parseVLess(line));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse V2Ray line", e);
        }

        return ips;
    }

    private static List<IpInfo> parseVMess(String line) throws Exception {
        List<IpInfo> ips = new ArrayList<>();

        String encoded = line.substring(8);

        int hashIndex = encoded.indexOf('#');
        String name = "";
        if (hashIndex > 0) {
            name = encoded.substring(hashIndex + 1);
            try {
                name = java.net.URLDecoder.decode(name, "UTF-8");
            } catch (Exception ignored) {}
            encoded = encoded.substring(0, hashIndex);
        }

        String padded = encoded;
        while (padded.length() % 4 != 0) {
            padded += "=";
        }

        byte[] decoded = Base64.decode(padded, Base64.DEFAULT);
        String jsonStr = new String(decoded, "UTF-8");

        JSONObject json = JSONObject.parseObject(jsonStr);
        if (json != null) {
            String host = json.getString("add");
            int port = json.getIntValue("port");

            if (host != null && !host.isEmpty()) {
                IpInfo ip = new IpInfo(host, port, "socks5");
                ip.actualProtocol = "vmess";
                ip.source = "VMess";
                if (name.isEmpty()) {
                    name = json.getString("ps");
                }
                if (name != null && !name.isEmpty()) {
                    ip.name = name;
                }
                ips.add(ip);
            }
        }

        return ips;
    }

    private static List<IpInfo> parseVLess(String line) throws Exception {
        List<IpInfo> ips = new ArrayList<>();

        String content = line.substring(8);

        int atIndex = content.indexOf('@');
        int hashIndex = content.indexOf('#');
        int questionIndex = content.indexOf('?');

        String name = "";
        if (hashIndex > 0) {
            name = content.substring(hashIndex + 1);
            try {
                name = java.net.URLDecoder.decode(name, "UTF-8");
            } catch (Exception ignored) {}
        }

        if (atIndex > 0) {
            String serverPort;
            if (questionIndex > 0) {
                serverPort = content.substring(atIndex + 1, questionIndex);
            } else if (hashIndex > 0) {
                serverPort = content.substring(atIndex + 1, hashIndex);
            } else {
                serverPort = content.substring(atIndex + 1);
            }

            int colonIndex = serverPort.lastIndexOf(':');
            if (colonIndex > 0) {
                String host = serverPort.substring(0, colonIndex);
                int port = Integer.parseInt(serverPort.substring(colonIndex + 1));

                IpInfo ip = new IpInfo(host, port, "socks5");
                ip.actualProtocol = "vless";
                ip.source = "VLess";
                if (!name.isEmpty()) {
                    ip.name = name;
                }
                ips.add(ip);
            }
        }

        return ips;
    }
}
