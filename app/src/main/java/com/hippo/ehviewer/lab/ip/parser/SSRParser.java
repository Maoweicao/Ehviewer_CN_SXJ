package com.hippo.ehviewer.lab.ip.parser;

import android.util.Base64;
import android.util.Log;

import com.hippo.ehviewer.lab.ip.model.IpInfo;

import java.util.ArrayList;
import java.util.List;

public class SSRParser {
    private static final String TAG = "SSRParser";

    public static List<IpInfo> parse(String content) throws Exception {
        List<IpInfo> ips = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return ips;
        }

        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("ssr://")) {
                continue;
            }
            ips.addAll(parseLine(line));
        }
        return ips;
    }

    public static List<IpInfo> parseLine(String line) throws Exception {
        List<IpInfo> ips = new ArrayList<>();
        if (line == null || !line.startsWith("ssr://")) {
            return ips;
        }

        try {
            String encoded = line.substring(6);
            String name = "";

            int hashIndex = encoded.indexOf('#');
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
            String decodedStr = new String(decoded, "UTF-8");

            String[] parts = decodedStr.split(":");
            if (parts.length >= 5) {
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);

                IpInfo ip = new IpInfo(host, port, "socks5");
                ip.actualProtocol = "ssr";
                ip.source = "SSR";
                if (!name.isEmpty()) {
                    ip.name = name;
                }
                ips.add(ip);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse SSR line", e);
        }

        return ips;
    }
}
