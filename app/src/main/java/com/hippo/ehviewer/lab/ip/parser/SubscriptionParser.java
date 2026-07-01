package com.hippo.ehviewer.lab.ip.parser;

import android.util.Base64;
import android.util.Log;

import com.hippo.ehviewer.lab.ip.model.IpInfo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class SubscriptionParser {
    private static final String TAG = "SubscriptionParser";

    public static class ParseResult {
        public List<IpInfo> ips;
        public long trafficUsed;
        public long trafficTotal;
        public long expiry;

        public ParseResult(List<IpInfo> ips) {
            this.ips = ips;
        }
    }

    public static List<IpInfo> parse(String url, String type) throws Exception {
        ParseResult result = parseWithHeaders(url, type);
        return result.ips;
    }

    public static ParseResult parseWithHeaders(String url, String type) throws Exception {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("URL is empty");
        }

        HttpURLConnection conn = null;
        try {
            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "v2rayNG/1.0");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "gzip");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("HTTP " + responseCode);
            }

            String content = readResponseStream(conn);
            if (content == null || content.isEmpty()) {
                throw new Exception("Empty response");
            }

            ParseResult result = new ParseResult(new ArrayList<>());

            String userInfoHeader = conn.getHeaderField("subscription-userinfo");
            if (userInfoHeader != null && !userInfoHeader.isEmpty()) {
                parseUserInfoHeader(userInfoHeader, result);
            }

            String profileUpdateHeader = conn.getHeaderField("profile-update-interval");
            if (profileUpdateHeader == null) {
                profileUpdateHeader = conn.getHeaderField("Subscription-Userinfo");
                if (profileUpdateHeader == null || profileUpdateHeader.isEmpty()) {
                    userInfoHeader = null;
                } else {
                    userInfoHeader = profileUpdateHeader;
                    parseUserInfoHeader(userInfoHeader, result);
                }
            }

            String contentType = conn.getContentType();
            if (contentType != null) {
                String autodetectedType = autoDetectType(contentType, content);
                if (!"mixed".equals(autodetectedType) && ("mixed".equals(type) || "auto".equals(type))) {
                    type = autodetectedType;
                }
            }

            String decoded = tryBase64Decode(content);

            switch (type != null ? type.toLowerCase() : "mixed") {
                case "ss":
                    result.ips = SSParser.parse(decoded);
                    break;
                case "ssr":
                    result.ips = SSRParser.parse(decoded);
                    break;
                case "v2ray":
                case "vmess":
                case "vless":
                    result.ips = V2RayParser.parse(decoded);
                    break;
                case "clash":
                    result.ips = ClashParser.parse(content);
                    break;
                case "trojan":
                    result.ips = TrojanParser.parse(decoded);
                    break;
                case "mixed":
                default:
                    result.ips = parseMixed(decoded);
                    break;
            }

            return result;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String readResponseStream(HttpURLConnection conn) throws Exception {
        InputStream is = null;
        try {
            is = conn.getInputStream();
            String encoding = conn.getContentEncoding();
            if ("gzip".equalsIgnoreCase(encoding)) {
                is = new GZIPInputStream(is);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toString("UTF-8");
        } catch (Exception e) {
            if (conn.getErrorStream() != null) {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = conn.getErrorStream().read(buffer)) != -1) {
                        baos.write(buffer, 0, len);
                    }
                    throw new Exception("Server error: " + baos.toString("UTF-8"));
                } catch (Exception ignored) {
                }
            }
            throw e;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void parseUserInfoHeader(String header, ParseResult result) {
        if (header == null || header.isEmpty()) return;
        try {
            String[] parts = header.split(";");
            for (String part : parts) {
                part = part.trim();
                int eqIndex = part.indexOf('=');
                if (eqIndex < 0) continue;
                String key = part.substring(0, eqIndex).trim();
                String value = part.substring(eqIndex + 1).trim();
                try {
                    switch (key) {
                        case "upload":
                            break;
                        case "download":
                            result.trafficUsed = Long.parseLong(value);
                            break;
                        case "total":
                            result.trafficTotal = Long.parseLong(value);
                            break;
                        case "expire":
                            result.expiry = Long.parseLong(value) * 1000;
                            break;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse userinfo header", e);
        }
    }

    private static String autoDetectType(String contentType, String content) {
        if (content == null) return "mixed";
        if (contentType != null && (contentType.contains("yaml") || contentType.contains("x-yaml"))) {
            return "clash";
        }
        if (content.trim().startsWith("proxies:") || content.trim().startsWith("Proxy")) {
            return "clash";
        }
        if (content.contains("vmess://") || content.contains("vless://")) {
            return "v2ray";
        }
        if (content.contains("ss://") && !content.contains("ssr://")) {
            return "ss";
        }
        if (content.contains("trojan://")) {
            return "trojan";
        }
        return "mixed";
    }

    private static String tryBase64Decode(String content) {
        try {
            String padded = content;
            while (padded.length() % 4 != 0) {
                padded += "=";
            }
            byte[] decoded = Base64.decode(padded, Base64.DEFAULT);
            return new String(decoded, "UTF-8");
        } catch (Exception e) {
            return content;
        }
    }

    private static List<IpInfo> parseMixed(String content) {
        List<IpInfo> allIps = new ArrayList<>();

        try {
            allIps.addAll(SSParser.parse(content));
        } catch (Exception e) {
        }

        try {
            allIps.addAll(SSRParser.parse(content));
        } catch (Exception e) {
        }

        try {
            allIps.addAll(V2RayParser.parse(content));
        } catch (Exception e) {
        }

        try {
            allIps.addAll(TrojanParser.parse(content));
        } catch (Exception e) {
        }

        if (allIps.isEmpty()) {
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("ss://")) {
                    try {
                        allIps.addAll(SSParser.parseLine(line));
                    } catch (Exception e) {
                    }
                } else if (line.startsWith("ssr://")) {
                    try {
                        allIps.addAll(SSRParser.parseLine(line));
                    } catch (Exception e) {
                    }
                } else if (line.startsWith("vmess://") || line.startsWith("vless://")) {
                    try {
                        allIps.addAll(V2RayParser.parseLine(line));
                    } catch (Exception e) {
                    }
                } else if (line.startsWith("trojan://")) {
                    try {
                        allIps.addAll(TrojanParser.parseLine(line));
                    } catch (Exception e) {
                    }
                }
            }
        }

        return allIps;
    }
}
