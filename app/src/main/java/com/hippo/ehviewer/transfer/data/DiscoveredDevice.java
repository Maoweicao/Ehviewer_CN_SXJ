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
 * 发现的设备数据模型
 */
public class DiscoveredDevice {

    private String name;         // 服务注册名称
    private String host;         // IP地址
    private int port;            // 端口
    private long discoveredTime; // 发现时间

    public DiscoveredDevice() {
        this.discoveredTime = System.currentTimeMillis();
    }

    public DiscoveredDevice(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.discoveredTime = System.currentTimeMillis();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public long getDiscoveredTime() {
        return discoveredTime;
    }

    public void setDiscoveredTime(long discoveredTime) {
        this.discoveredTime = discoveredTime;
    }

    public String getFullAddress() {
        return host + ":" + port;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        DiscoveredDevice that = (DiscoveredDevice) obj;
        return port == that.port && host != null && host.equals(that.host);
    }

    @Override
    public int hashCode() {
        int result = host != null ? host.hashCode() : 0;
        result = 31 * result + port;
        return result;
    }

    @Override
    public String toString() {
        return name + " (" + host + ":" + port + ")";
    }
}
