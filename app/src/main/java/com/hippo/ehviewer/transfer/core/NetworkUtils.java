/*
 * Copyright 2025 EhViewer Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.transfer.core;

import com.hippo.ehviewer.transfer.data.NetworkAddress;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 网络工具类
 * 获取全部网络接口地址
 */
public class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    /**
     * 获取全部网络接口地址
     */
    public static List<NetworkAddress> getAllNetworkAddresses() {
        TransferLogger logger = TransferLogger.getInstance();
        logger.d(TAG, "getAllNetworkAddresses() 开始");
        
        List<NetworkAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                logger.w(TAG, "NetworkInterface.getNetworkInterfaces() 返回 null");
                return addresses;
            }

            int interfaceCount = 0;
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                interfaceCount++;

                String name = ni.getDisplayName();
                boolean isLoopback = ni.isLoopback();
                boolean isUp = ni.isUp();

                logger.d(TAG, String.format("接口 #%d: %s [loopback=%b, up=%b]",
                        interfaceCount, name, isLoopback, isUp));

                if (isLoopback || !isUp) {
                    logger.d(TAG, "  -> 跳过（loopback或未启用）");
                    continue;
                }

                String displayName = formatDisplayName(name);

                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        logger.d(TAG, String.format("  -> IPv4: %s (%s)", ip, displayName));
                        addresses.add(new NetworkAddress(displayName, ip));
                    }
                }
            }

            logger.i(TAG, String.format("getAllNetworkAddresses() 完成: %d 个接口, %d 个地址",
                    interfaceCount, addresses.size()));

        } catch (Exception e) {
            logger.e(TAG, "getAllNetworkAddresses() 异常", e);
        }
        return addresses;
    }

    /**
     * 格式化显示名称
     */
    private static String formatDisplayName(String rawName) {
        if (rawName == null) return "未知";

        String lower = rawName.toLowerCase();
        if (lower.contains("wlan") || lower.contains("wifi")) {
            return "WiFi";
        } else if (lower.contains("ap") || lower.contains("hotspot")) {
            return "热点";
        } else if (lower.contains("eth") || lower.contains("ether")) {
            return "以太网";
        } else if (lower.contains("rmnet") || lower.contains("pdp")) {
            return "移动数据";
        } else if (lower.contains("usb")) {
            return "USB";
        } else if (lower.contains("tun") || lower.contains("tap")) {
            return "VPN";
        } else {
            return rawName;
        }
    }

    /**
     * 获取主WiFi地址
     */
    public static String getWifiIpAddress() {
        TransferLogger logger = TransferLogger.getInstance();
        logger.d(TAG, "getWifiIpAddress() 开始");
        
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

                String name = ni.getDisplayName().toLowerCase();
                if (name.contains("wlan") || name.contains("wifi")) {
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (addr instanceof Inet4Address) {
                            String ip = addr.getHostAddress();
                            logger.d(TAG, "getWifiIpAddress() 找到: " + ip);
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.e(TAG, "getWifiIpAddress() 异常", e);
        }
        
        logger.w(TAG, "getWifiIpAddress() 未找到WiFi地址，返回 0.0.0.0");
        return "0.0.0.0";
    }

    /**
     * 检查是否为局域网IP
     */
    public static boolean isLocalNetwork(String ip) {
        if (ip == null || ip.isEmpty()) return false;

        boolean result = ip.startsWith("192.168.") ||
               ip.startsWith("10.") ||
               ip.startsWith("172.16.") ||
               ip.startsWith("172.17.") ||
               ip.startsWith("172.18.") ||
               ip.startsWith("172.19.") ||
               ip.startsWith("172.20.") ||
               ip.startsWith("172.21.") ||
               ip.startsWith("172.22.") ||
               ip.startsWith("172.23.") ||
               ip.startsWith("172.24.") ||
               ip.startsWith("172.25.") ||
               ip.startsWith("172.26.") ||
               ip.startsWith("172.27.") ||
               ip.startsWith("172.28.") ||
               ip.startsWith("172.29.") ||
               ip.startsWith("172.30.") ||
               ip.startsWith("172.31.") ||
               ip.startsWith("127.") ||
               ip.equals("0:0:0:0:0:0:0:1") ||
               ip.equals("::1");
        TransferLogger.getInstance().d(TAG, "isLocalNetwork: ip=" + ip + ", 结果=" + result);
        return result;
    }
}
