/*
 * Copyright 2019 Hippo Seven
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

package com.hippo.ehviewer;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ProxyInfo;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import com.hippo.ehviewer.lab.ip.IpSwitchController;
import com.hippo.ehviewer.network.NetworkLogger;
import com.hippo.network.InetValidator;
import com.hippo.util.ExceptionUtils;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;

public class EhProxySelector extends ProxySelector {

  private static final String TAG = "EhProxySelector";

  public static final int TYPE_DIRECT = 0;
  public static final int TYPE_SYSTEM = 1;
  public static final int TYPE_HTTP = 2;
  public static final int TYPE_SOCKS = 3;
  public static final int TYPE_AUTO = 4;

  private ProxySelector delegation;
  private ProxySelector alternative;
  private Context appContext;

  EhProxySelector() {
    alternative = ProxySelector.getDefault();
    if (alternative == null) {
      alternative = new NullProxySelector();
    }
    updateProxy();
  }

  /**
   * Initialize with application context for Android system proxy detection.
   */
  public void initContext(Context context) {
    this.appContext = context.getApplicationContext();
  }

  public void updateProxy() {
    switch (Settings.getProxyType()) {
      case TYPE_DIRECT:
        delegation = new NullProxySelector();
        break;
      case TYPE_AUTO:
        // Auto mode: detect system proxy first, then fallback to direct
        delegation = new AutoProxySelector();
        break;
      default:
      case TYPE_SYSTEM:
        delegation = alternative;
        break;
      case TYPE_HTTP:
      case TYPE_SOCKS:
        delegation = null;
        break;
    }
  }

  @Override
  public List<Proxy> select(URI uri) {
    // Priority 1: IpSwitchController proxy (lab feature)
    if (Settings.getIpSwitchEnabled()) {
      Proxy ipSwitchProxy = IpSwitchController.getInstance().getCurrentProxy();
      if (ipSwitchProxy != null) {
        return Collections.singletonList(ipSwitchProxy);
      }
    }

    // Priority 2: User-configured HTTP/SOCKS proxy
    int type = Settings.getProxyType();
    if (type == TYPE_HTTP || type == TYPE_SOCKS) {
      try {
        String ip = Settings.getProxyIp();
        int port = Settings.getProxyPort();
        if (!TextUtils.isEmpty(ip) && InetValidator.isValidInetPort(port)) {
          InetAddress inetAddress = InetAddress.getByName(ip);
          SocketAddress socketAddress = new InetSocketAddress(inetAddress, port);
          return Collections.singletonList(new Proxy(type == TYPE_HTTP ? Proxy.Type.HTTP : Proxy.Type.SOCKS, socketAddress));
        }
      } catch (Throwable t) {
        ExceptionUtils.throwIfFatal(t);
      }
    }

    // Priority 3: Delegation (system/auto/direct)
    if (delegation != null) {
      return delegation.select(uri);
    }

    return alternative.select(uri);
  }

  @Override
  public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
    if (delegation != null) {
      delegation.connectFailed(uri, sa, ioe);
    }
  }

  /**
   * Auto proxy selector that detects Android system proxy (from Wi-Fi settings
   * or VPN) and falls back to DIRECT when no proxy is configured.
   */
  private class AutoProxySelector extends ProxySelector {
    @Override
    public List<Proxy> select(URI uri) {
      // Try Android system proxy from LinkProperties (API 21+)
      Proxy androidProxy = getAndroidSystemProxy();
      if (androidProxy != null) {
        return Collections.singletonList(androidProxy);
      }

      // Try JVM system proxy (http.proxyHost/http.proxyPort)
      Proxy jvmProxy = getJvmSystemProxy();
      if (jvmProxy != null) {
        return Collections.singletonList(jvmProxy);
      }

      return Collections.singletonList(Proxy.NO_PROXY);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
      Log.w(TAG, "Auto proxy connect failed for " + uri + ": " + ioe.getMessage());
    }
  }

  /**
   * Detect Android system proxy configured via Wi-Fi settings or device policy.
   * Uses LinkProperties.getHttpProxy() which is the proper API on Android 21+.
   */
  private Proxy getAndroidSystemProxy() {
    if (appContext == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      return null;
    }
    try {
      ConnectivityManager cm = appContext.getSystemService(ConnectivityManager.class);
      if (cm == null) return null;

      Network activeNetwork = cm.getActiveNetwork();
      if (activeNetwork == null) return null;

      LinkProperties linkProperties = cm.getLinkProperties(activeNetwork);
      if (linkProperties == null) return null;

      ProxyInfo proxyInfo = linkProperties.getHttpProxy();
      if (proxyInfo == null) return null;

      String host = proxyInfo.getHost();
      int port = proxyInfo.getPort();

      if (TextUtils.isEmpty(host) || port <= 0) {
        return null;
      }

      // PAC/exclusion-based proxies: if the proxy has a PAC URL, we can't
      // resolve it ourselves, so fall through to system default
      if (!TextUtils.isEmpty((CharSequence) proxyInfo.getPacFileUrl())
          && !"DIRECT".equals(proxyInfo.getPacFileUrl())) {
        Log.d(TAG, "System proxy uses PAC URL, falling through to system selector");
        return null;
      }

      InetAddress inetAddress = InetAddress.getByName(host);
      SocketAddress socketAddress = new InetSocketAddress(inetAddress, port);
      Log.i(TAG, "Detected Android system proxy: " + host + ":" + port);
      NetworkLogger.INSTANCE.logBackground("EhProxySelector: Android system proxy detected: " + host + ":" + port);
      return new Proxy(Proxy.Type.HTTP, socketAddress);
    } catch (Exception e) {
      Log.d(TAG, "Failed to detect Android system proxy: " + e.getMessage());
      return null;
    }
  }

  /**
   * Detect JVM-level system proxy from http.proxyHost / http.proxyPort properties.
   */
  private Proxy getJvmSystemProxy() {
    try {
      String host = System.getProperty("http.proxyHost");
      String portStr = System.getProperty("http.proxyPort");
      if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(portStr)) {
        int port = Integer.parseInt(portStr);
        if (port > 0 && port <= 65535) {
          InetAddress inetAddress = InetAddress.getByName(host);
          SocketAddress socketAddress = new InetSocketAddress(inetAddress, port);
          Log.i(TAG, "Detected JVM system proxy: " + host + ":" + port);
          return new Proxy(Proxy.Type.HTTP, socketAddress);
        }
      }
    } catch (Exception e) {
      // Ignore
    }
    return null;
  }

  private static class NullProxySelector extends ProxySelector {
    @Override
    public List<Proxy> select(URI uri) {
      return Collections.singletonList(Proxy.NO_PROXY);
    }
    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
  }
}
