package com.hippo.ehviewer.lab.ip.parser;

import android.util.Base64;
import android.util.Log;

import com.hippo.ehviewer.lab.ip.model.IpInfo;

import java.util.ArrayList;
import java.util.List;

public class SSParser {
    private static final String TAG = "SSParser";

    public static List<IpInfo> parse(String content) throws Exception {
        List<IpInfo> ips = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return ips;
        }

        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("ss://")) {
                continue;
            }
            ips.addAll(parseLine(line));
        }
        return ips;
    }

    public static List<IpInfo> parseLine(String line) throws Exception {
        List<IpInfo> ips = new ArrayList<>();
        if (line == null || !line.startsWith("ss://")) {
            return ips;
        }

        try {
            String encoded = line.substring(5);

            String method = "";
            String password = "";
            String host = "";
            int port = 1080;
            String name = "";

            int atIndex = encoded.indexOf('@');
            if (atIndex > 0) {
                String userInfo = encoded.substring(0, atIndex);
                String serverInfo = encoded.substring(atIndex + 1);

                String decodedUserInfo = tryBase64Decode(userInfo);
                String[] parts = decodedUserInfo.split(":");
                if (parts.length >= 2) {
                    method = parts[0];
                    password = parts[1];
                }

                int hashIndex = serverInfo.indexOf('#');
                if (hashIndex > 0) {
                    name = serverInfo.substring(hashIndex + 1);
                    try {
                        name = java.net.URLDecoder.decode(name, "UTF-8");
                    } catch (Exception ignored) {}
                    serverInfo = serverInfo.substring(0, hashIndex);
                }

                int colonIndex = serverInfo.lastIndexOf(':');
                if (colonIndex > 0) {
                    host = serverInfo.substring(0, colonIndex);
                    try {
                        port = Integer.parseInt(serverInfo.substring(colonIndex + 1));
                    } catch (NumberFormatException e) {
                        port = 1080;
                    }
                }
            } else {
                int hashIndex = encoded.indexOf('#');
                if (hashIndex > 0) {
                    name = encoded.substring(hashIndex + 1);
                    try {
                        name = java.net.URLDecoder.decode(name, "UTF-8");
                    } catch (Exception ignored) {}
                    encoded = encoded.substring(0, hashIndex);
                }

                String decoded = tryBase64Decode(encoded);
                int atIdx = decoded.lastIndexOf('@');
                if (atIdx > 0) {
                    String methodPass = decoded.substring(0, atIdx);
                    String serverPort = decoded.substring(atIdx + 1);

                    String[] methodParts = methodPass.split(":");
                    if (methodParts.length >= 2) {
                        method = methodParts[0];
                        password = methodParts[1];
                    }

                    int colonIdx = serverPort.lastIndexOf(':');
                    if (colonIdx > 0) {
                        host = serverPort.substring(0, colonIdx);
                        try {
                            port = Integer.parseInt(serverPort.substring(colonIdx + 1));
                        } catch (NumberFormatException e) {
                            port = 1080;
                        }
                    }
                }
            }

            if (!host.isEmpty()) {
                IpInfo ip = new IpInfo(host, port, "socks5");
                ip.actualProtocol = "ss";
                ip.source = "SS";
                if (!name.isEmpty()) {
                    ip.name = name;
                }
                ips.add(ip);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse SS line: " + line, e);
        }

        return ips;
    }

    private static String tryBase64Decode(String input) {
        try {
            String padded = input;
            while (padded.length() % 4 != 0) {
                padded += "=";
            }
            byte[] decoded = Base64.decode(padded, Base64.DEFAULT);
            return new String(decoded, "UTF-8");
        } catch (Exception e) {
            return input;
        }
    }
}
