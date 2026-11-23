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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

/**
 * 启用内置内核时，把本应用（含子进程）绑定到系统 VPN 下面的真实网络，
 * 避免内置代理的上游连接又被系统全局 VPN 接管。
 *
 * Android 的 bindProcessToNetwork() 会作用于当前进程及其子进程；内核是
 * 通过 ProcessBuilder 启动的子进程，因此能继承这个绑定。
 */
public final class VpnBypass {

    private static final String TAG = "VpnBypass";
    private static Network boundNetwork;

    private VpnBypass() {
    }

    /** @return true 表示已绑定到非 VPN 网络。 */
    public static boolean bindToUnderlyingNetwork(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        NetworkCapabilities activeCaps = active == null
                ? null : cm.getNetworkCapabilities(active);
        boolean vpnActive = activeCaps != null
                && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        if (!vpnActive) {
            return false;
        }

        Network best = null;
        int bestScore = -1;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            int score = 0;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 2;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 1;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 4;
            if (score > bestScore) {
                bestScore = score;
                best = network;
            }
        }
        if (best == null) {
            Log.w(TAG, "System VPN active but no underlying network found");
            return false;
        }
        boolean ok = cm.bindProcessToNetwork(best);
        if (ok) {
            boundNetwork = best;
            Log.i(TAG, "Bound app process to underlying non-VPN network");
        } else {
            Log.w(TAG, "bindProcessToNetwork failed");
        }
        return ok;
    }

    public static void unbind(Context context) {
        if (boundNetwork == null) return;
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            cm.bindProcessToNetwork(null);
        }
        boundNetwork = null;
    }

    /** 网络切换后重新绑定：VPN 关闭时恢复默认网络。 */
    public static void refresh(Context context) {
        unbind(context);
        bindToUnderlyingNetwork(context);
    }

    public static boolean isBound() {
        return boundNetwork != null;
    }
}
