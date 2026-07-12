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

package com.hippo.ehviewer.transfer.data;

/**
 * 网络地址数据模型
 */
public class NetworkAddress {

    private String displayName;  // WiFi/热点/以太网
    private String ipAddress;    // IP地址
    private int port;            // 端口

    public NetworkAddress() {
    }

    public NetworkAddress(String displayName, String ipAddress) {
        this.displayName = displayName;
        this.ipAddress = ipAddress;
    }

    public NetworkAddress(String displayName, String ipAddress, int port) {
        this.displayName = displayName;
        this.ipAddress = ipAddress;
        this.port = port;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getFullAddress() {
        return ipAddress + ":" + port;
    }

    @Override
    public String toString() {
        return displayName + ": " + ipAddress + ":" + port;
    }
}
