/*
 * Copyright 2026 EhViewer Contributors
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

package com.hippo.ehviewer.lab.ip.core;

import android.content.Context;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhProxySelector;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.ip.IpSwitchController;

import java.util.LinkedHashSet;
import java.util.Set;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/** 诊断 OkHttp 连接池、内核活动连接和当前代理节点。 */
public final class HttpPoolDiagnostics {

    private HttpPoolDiagnostics() {
    }

    public static String snapshot(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            OkHttpClient main = EhApplication.getOkHttpClient(context);
            OkHttpClient image = EhApplication.getImageOkHttpClient(context);
            appendClient(sb, "主 OkHttp", main);
            appendClient(sb, "图片 OkHttp", image);

            IpSwitchController controller = IpSwitchController.getInstance();
            sb.append("自动换IP: ").append(controller.isEnabled() ? "开" : "关").append('\n');
            sb.append("内核: ").append(controller.isCoreRunning() ? "运行中" : "未运行").append('\n');
            sb.append("代理就绪: ").append(controller.isProxyReady() ? "是" : "否").append('\n');
            sb.append("当前节点: ")
                    .append(controller.getCurrentNodeName() == null
                            ? "无/自动选择中" : controller.getCurrentNodeName())
                    .append('\n');
            sb.append("proxy_type: ").append(Settings.getProxyType()).append('\n');
            if (controller.getLastError() != null && !controller.getLastError().isEmpty()) {
                sb.append("内核错误: ").append(controller.getLastError()).append('\n');
            }

            MihomoCore core = MihomoCore.getInstance();
            JSONObject connections = core.getConnectionsSnapshot();
            if (connections == null) {
                sb.append("内核活动连接: 不可用\n");
            } else {
                JSONArray array = connections.getJSONArray("connections");
                int count = array == null ? 0 : array.size();
                Set<String> chains = new LinkedHashSet<>();
                if (array != null) {
                    for (int i = 0; i < array.size(); i++) {
                        JSONObject item = array.getJSONObject(i);
                        if (item == null) continue;
                        JSONArray chain = item.getJSONArray("chains");
                        if (chain != null && !chain.isEmpty()) {
                            chains.add(chain.getString(chain.size() - 1));
                        }
                    }
                }
                sb.append("内核活动连接: ").append(count).append('\n');
                if (!chains.isEmpty()) {
                    sb.append("出站节点: ").append(chains).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("诊断失败: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static void appendClient(StringBuilder sb, String name, OkHttpClient client) {
        if (client == null) {
            sb.append(name).append(": 未初始化\n");
            return;
        }
        ConnectionPool pool = client.connectionPool();
        Dispatcher dispatcher = client.dispatcher();
        sb.append(name).append(": 总连接=").append(pool.connectionCount())
                .append(", 空闲=").append(pool.idleConnectionCount())
                .append(", 执行中=").append(dispatcher.runningCallsCount())
                .append(", 排队=").append(dispatcher.queuedCallsCount())
                .append('\n');
    }

    public static String proxyTypeName(int type) {
        switch (type) {
            case EhProxySelector.TYPE_DIRECT: return "直连";
            case EhProxySelector.TYPE_SYSTEM: return "系统";
            case EhProxySelector.TYPE_HTTP: return "HTTP";
            case EhProxySelector.TYPE_SOCKS: return "SOCKS";
            case EhProxySelector.TYPE_AUTO: return "自动";
            default: return String.valueOf(type);
        }
    }
}
