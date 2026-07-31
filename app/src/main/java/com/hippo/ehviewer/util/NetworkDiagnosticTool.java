package com.hippo.ehviewer.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class NetworkDiagnosticTool {

    private static final String TAG = "NetworkDiagnostic";
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    public static class SiteInfo {
        public String domain;
        public String resolvedIP;
        public boolean isAccessible;
        public long responseTime;
        public String error;

        public SiteInfo(String domain) {
            this.domain = domain;
            this.isAccessible = false;
            this.responseTime = -1;
        }
    }

    public static class NetworkInfo {
        public String currentIP;
        public String networkType;
        public boolean isConnected;
    }

    public static NetworkInfo getNetworkInfo(Context context) {
        NetworkInfo info = new NetworkInfo();
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null && activeNetwork.isConnectedOrConnecting()) {
                info.isConnected = true;
                int type = activeNetwork.getType();
                if (type == ConnectivityManager.TYPE_WIFI) {
                    info.networkType = "WiFi";
                } else if (type == ConnectivityManager.TYPE_MOBILE) {
                    info.networkType = "Mobile";
                } else {
                    info.networkType = "Other";
                }
            } else {
                info.isConnected = false;
                info.networkType = "Unknown";
            }
        }
        try {
            info.currentIP = getLocalIP();
        } catch (Exception e) {
            info.currentIP = "N/A";
        }
        return info;
    }

    private static String getLocalIP() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                java.util.Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get local IP", e);
        }
        return "N/A";
    }

    public static boolean isInterrupted() {
        return Thread.currentThread().isInterrupted();
    }

    private static final ExecutorService DNS_EXECUTOR = Executors.newCachedThreadPool();

    public static SiteInfo checkSite(String domain, int timeoutSeconds) {
        SiteInfo info = new SiteInfo(domain);
        long startTime = System.currentTimeMillis();
        long deadline = startTime + TimeUnit.SECONDS.toMillis(timeoutSeconds);

        try {
            if (isInterrupted()) {
                info.error = "Cancelled";
                info.responseTime = System.currentTimeMillis() - startTime;
                return info;
            }

            long dnsRemaining = Math.max(1000, deadline - System.currentTimeMillis());
            Future<InetAddress> dnsFuture = DNS_EXECUTOR.submit(
                    (Callable<InetAddress>) () -> InetAddress.getByName(domain));
            InetAddress address;
            try {
                address = dnsFuture.get(dnsRemaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                dnsFuture.cancel(true);
                info.error = "DNS timeout (" + dnsRemaining + "ms)";
                info.responseTime = System.currentTimeMillis() - startTime;
                return info;
            } catch (Exception e) {
                info.resolvedIP = "FAILED";
                info.error = "DNS resolution failed: " + e.getMessage();
                info.responseTime = System.currentTimeMillis() - startTime;
                return info;
            }

            info.resolvedIP = address.getHostAddress();

            long portRemaining = Math.max(1000, deadline - System.currentTimeMillis());
            if (isInterrupted()) {
                info.error = "Cancelled";
                info.responseTime = System.currentTimeMillis() - startTime;
                return info;
            }
            if (checkPort(address, 443, portRemaining) || checkPort(address, 80, portRemaining)) {
                info.isAccessible = true;
            } else {
                info.error = "Connection refused";
            }
        } catch (Exception e) {
            info.error = e.getMessage();
        }
        info.responseTime = System.currentTimeMillis() - startTime;
        return info;
    }

    public static SiteInfo checkSite(String domain) {
        return checkSite(domain, DEFAULT_TIMEOUT_SECONDS);
    }

    private static boolean checkPort(InetAddress address, int port, long remainingMs) {
        try (Socket socket = new Socket()) {
            socket.setReuseAddress(true);
            int timeout = (int) Math.max(1000, remainingMs);
            socket.connect(new InetSocketAddress(address, port), timeout);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<SiteInfo> checkMultipleSites(String[] domains, int timeoutSeconds) {
        List<SiteInfo> results = new ArrayList<>();
        for (String domain : domains) {
            if (isInterrupted()) break;
            results.add(checkSite(domain, timeoutSeconds));
        }
        return results;
    }

    public static List<SiteInfo> checkMultipleSites(String[] domains) {
        return checkMultipleSites(domains, DEFAULT_TIMEOUT_SECONDS);
    }

    public static DiagnosticEndpoint.CheckResult checkHttpEndpoint(
            DiagnosticEndpoint.EndpointItem endpoint, OkHttpClient client) {
        DiagnosticEndpoint.CheckResult result = new DiagnosticEndpoint.CheckResult(endpoint);
        String resolvedUrl = DiagnosticEndpoint.resolveUrl(endpoint.url, endpoint.site);
        long startTime = System.currentTimeMillis();

        if (isInterrupted()) {
            result.error = "Cancelled";
            result.totalTimeMs = 0;
            return result;
        }

        try {
            Request.Builder reqBuilder = new Request.Builder()
                    .url(resolvedUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Referer", endpoint.site.getHost());

            if (endpoint.method == DiagnosticEndpoint.HttpMethod.POST) {
                MediaType JSON = MediaType.get("application/json; charset=utf-8");
                String body = endpoint.postBody != null ? endpoint.postBody : "{}";
                reqBuilder.post(RequestBody.create(JSON, body));
            } else if (endpoint.method == DiagnosticEndpoint.HttpMethod.HEAD) {
                reqBuilder.head();
            }

            Request request = reqBuilder.build();
            Response response = client.newCall(request).execute();
            result.httpCode = response.code();
            result.isReachable = true;

            if (response.body() != null) {
                response.body().close();
            }
        } catch (Exception e) {
            result.isReachable = false;
            result.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, "HTTP check failed for " + resolvedUrl, e);
        }
        result.totalTimeMs = System.currentTimeMillis() - startTime;
        return result;
    }

    public static OkHttpClient buildDiagnosticClient(OkHttpClient base, int timeoutSeconds) {
        long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSeconds);
        return base.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }
}
