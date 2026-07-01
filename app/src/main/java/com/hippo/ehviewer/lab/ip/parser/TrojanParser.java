package com.hippo.ehviewer.lab.ip.parser;

import android.util.Log;

import com.hippo.ehviewer.lab.ip.model.IpInfo;

import java.util.ArrayList;
import java.util.List;

public class TrojanParser {
    private static final String TAG = "TrojanParser";

    public static List<IpInfo> parse(String content) throws Exception {
        List<IpInfo> ips = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return ips;
        }

        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("trojan://")) {
                continue;
            }
            ips.addAll(parseLine(line));
        }
        return ips;
    }

    public static List<IpInfo> parseLine(String line) throws Exception {
        List<IpInfo> ips = new ArrayList<>();
        if (line == null || !line.startsWith("trojan://")) {
            return ips;
        }

        try {
            String content = line.substring(9);

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
                    ip.actualProtocol = "trojan";
                    ip.source = "Trojan";
                    if (!name.isEmpty()) {
                        ip.name = name;
                    }
                    ips.add(ip);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse Trojan line", e);
        }

        return ips;
    }
}
