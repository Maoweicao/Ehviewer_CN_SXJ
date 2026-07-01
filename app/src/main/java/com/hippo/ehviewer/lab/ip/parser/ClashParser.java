package com.hippo.ehviewer.lab.ip.parser;

import android.util.Log;

import com.hippo.ehviewer.lab.ip.model.IpInfo;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ClashParser {
    private static final String TAG = "ClashParser";

    public static List<IpInfo> parse(String content) throws Exception {
        List<IpInfo> ips = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return ips;
        }

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(content);
            if (data == null) return ips;

            Object proxiesObj = data.get("proxies");
            if (proxiesObj instanceof List) {
                List<Map<String, Object>> proxies = (List<Map<String, Object>>) proxiesObj;
                for (Map<String, Object> proxy : proxies) {
                    try {
                        IpInfo ip = parseProxy(proxy);
                        if (ip != null) {
                            ips.add(ip);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse proxy", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse Clash config", e);
            ips.addAll(parseLineByLine(content));
        }

        return ips;
    }

    private static IpInfo parseProxy(Map<String, Object> proxy) {
        if (proxy == null) return null;

        String type = (String) proxy.get("type");
        String server = (String) proxy.get("server");
        Object portObj = proxy.get("port");
        String name = (String) proxy.get("name");

        if (server == null || server.isEmpty()) return null;

        int port = 1080;
        if (portObj instanceof Integer) {
            port = (Integer) portObj;
        } else if (portObj instanceof String) {
            try {
                port = Integer.parseInt((String) portObj);
            } catch (NumberFormatException e) {
            }
        }

        String protocol = "socks5";
        String actualProtocol = "socks5";
        if ("http".equals(type)) {
            protocol = "http";
            actualProtocol = "http";
        } else if ("socks5".equals(type)) {
            actualProtocol = "socks5";
        } else if ("ss".equals(type)) {
            actualProtocol = "ss";
        } else if ("ssr".equals(type)) {
            actualProtocol = "ssr";
        } else if ("vmess".equals(type)) {
            actualProtocol = "vmess";
        } else if ("vless".equals(type)) {
            actualProtocol = "vless";
        } else if ("trojan".equals(type)) {
            actualProtocol = "trojan";
        } else {
            actualProtocol = type != null ? type : "unknown";
        }

        IpInfo ip = new IpInfo(server, port, protocol);
        ip.actualProtocol = actualProtocol;
        ip.source = "Clash (" + type + ")";
        if (name != null && !name.isEmpty()) {
            ip.name = name;
        }
        return ip;
    }

    private static List<IpInfo> parseLineByLine(String content) {
        List<IpInfo> ips = new ArrayList<>();
        String[] lines = content.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("- {") || line.startsWith("-{")) {
                try {
                    String server = extractValue(line, "server");
                    String portStr = extractValue(line, "port");
                    if (server != null && !server.isEmpty()) {
                        int port = 1080;
                        if (portStr != null) {
                            try {
                                port = Integer.parseInt(portStr);
                            } catch (NumberFormatException e) {
                            }
                        }
                        IpInfo ip = new IpInfo(server, port, "socks5");
                        ip.actualProtocol = "socks5";
                        ip.source = "Clash";
                        ips.add(ip);
                    }
                } catch (Exception e) {
                }
            }
        }

        return ips;
    }

    private static String extractValue(String line, String key) {
        String search = key + ":";
        int index = line.indexOf(search);
        if (index < 0) return null;

        int start = index + search.length();
        int end = line.indexOf(",", start);
        if (end < 0) {
            end = line.indexOf("}", start);
        }
        if (end < 0) {
            end = line.length();
        }

        return line.substring(start, end).trim();
    }
}
