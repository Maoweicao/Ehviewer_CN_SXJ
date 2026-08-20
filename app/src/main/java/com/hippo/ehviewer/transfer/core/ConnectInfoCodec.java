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

import android.net.Uri;

import com.hippo.ehviewer.transfer.log.TransferLogger;

/**
 * 连接信息编解码
 *
 * 将本机 "IP:端口 + 设备名" 编码为统一 URI（用于二维码 / NFC 共享），
 * 对方解码后即可直接调用 TransferClientManager.connect() 建立连接。
 *
 * 格式: ehviewer-transfer://connect?host=192.168.1.5&port=8080&name=DeviceName
 */
public class ConnectInfoCodec {

    private static final String TAG = "ConnectInfoCodec";
    public static final String SCHEME = "ehviewer-transfer";
    public static final String HOST = "connect";
    private static final String PARAM_HOST = "host";
    private static final String PARAM_PORT = "port";
    private static final String PARAM_NAME = "name";

    /**
     * 编码连接信息为 URI 字符串
     */
    public static String encode(String host, int port, String deviceName) {
        TransferLogger.getInstance().d(TAG, "编码连接信息: host=" + host + ", port=" + port + ", deviceName=" + deviceName);
        Uri.Builder builder = new Uri.Builder()
                .scheme(SCHEME)
                .authority(HOST)
                .appendQueryParameter(PARAM_HOST, host)
                .appendQueryParameter(PARAM_PORT, String.valueOf(port));
        if (deviceName != null) {
            builder.appendQueryParameter(PARAM_NAME, deviceName);
        }
        String uri = builder.build().toString();
        TransferLogger.getInstance().i(TAG, "编码后的连接URI: " + uri);
        return uri;
    }

    /**
     * 解码 URI 字符串为连接参数，内容不合法时返回 null
     */
    public static ConnectParams decode(String content) {
        TransferLogger.getInstance().d(TAG, "解码连接信息: content=" + content);
        if (content == null || content.isEmpty()) {
            TransferLogger.getInstance().w(TAG, "解码失败，内容为空");
            return null;
        }
        try {
            Uri uri = Uri.parse(content);
            if (uri == null || !SCHEME.equals(uri.getScheme()) || !HOST.equals(uri.getHost())) {
                TransferLogger.getInstance().w(TAG, "解码失败，URI格式不合法: " + content);
                return null;
            }
            String host = uri.getQueryParameter(PARAM_HOST);
            String portStr = uri.getQueryParameter(PARAM_PORT);
            if (host == null || host.isEmpty() || portStr == null) {
                TransferLogger.getInstance().w(TAG, "解码失败，缺少host或port参数");
                return null;
            }
            int port = Integer.parseInt(portStr);
            String deviceName = uri.getQueryParameter(PARAM_NAME);
            TransferLogger.getInstance().i(TAG, "解码出的连接参数: host=" + host + ", port=" + port + ", deviceName=" + deviceName);
            return new ConnectParams(host, port, deviceName);
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "解码连接信息异常: " + content, e);
            return null;
        }
    }

    /**
     * 连接参数
     */
    public static class ConnectParams {
        public final String host;
        public final int port;
        public final String deviceName;

        public ConnectParams(String host, int port, String deviceName) {
            this.host = host;
            this.port = port;
            this.deviceName = deviceName;
        }
    }
}
