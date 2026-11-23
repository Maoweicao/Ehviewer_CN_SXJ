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

package com.hippo.ehviewer.lab.pair

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.alibaba.fastjson.JSON
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.transfer.data.DiscoveredDevice
import com.hippo.ehviewer.transfer.log.TransferLogger
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 局域网设备扫描器（mDNS 的兜底方案）
 *
 * 部分路由器/ROM（例如开启 AP 隔离、或 MIUI 限制组播）会让 mDNS 收不到任何设备。
 * 这里直接扫描本机所在的 /24 网段，探测 8080 端口并读取未认证的
 * `/api/v1/lab/topology`，从而在普通共享局域网（如 192.168.1.x）中稳定发现对端。
 *
 * 只扫描私有 IPv4 网段，跳过蜂窝、VPN、loopback 接口；最多同时 32 个连接。
 */
class LanPeerScanner private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val labManager = LabManager.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LabLanScan").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    interface Callback {
        fun onResult(devices: List<DiscoveredDevice>)
    }

    fun isRunning(): Boolean = running

    fun scan(callback: Callback) {
        if (running) return
        running = true
        executor.execute {
            val found = ConcurrentHashMap<String, DiscoveredDevice>()
            val pool = Executors.newFixedThreadPool(SCAN_THREADS)
            try {
                val ownIps = HashSet<String>()
                val prefixes = LinkedHashSet<String>()
                collectLocal(prefixes, ownIps)
                val selfId = labManager.getSelfDeviceId()

                for (prefix in prefixes) {
                    val futures = ArrayList<java.util.concurrent.Future<*>>()
                    for (last in 1..254) {
                        val host = "$prefix.$last"
                        if (ownIps.contains(host)) continue
                        futures.add(pool.submit {
                            probe(host, found, ownIps, selfId)
                        })
                    }
                    for (future in futures) {
                        try {
                            future.get()
                        } catch (ignored: Exception) {
                        }
                    }
                }
            } catch (t: Throwable) {
                TransferLogger.getInstance().w(TAG, "LAN scan failed: ${t.message}")
            } finally {
                pool.shutdownNow()
                running = false
            }
            val result = found.values.sortedBy { it.name?.lowercase() ?: "" }
            TransferLogger.getInstance().i(TAG, "LAN scan finished, found=${result.size}")
            mainHandler.post { callback.onResult(result) }
        }
    }

    private fun probe(
        host: String,
        out: MutableMap<String, DiscoveredDevice>,
        ownIps: Set<String>,
        selfId: String,
    ) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, DEFAULT_PORT), CONNECT_TIMEOUT_MS)
        } catch (ignored: Exception) {
            return
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {
            }
        }

        val self = fetchSelf(host) ?: return
        val id = self.getString("deviceId")
        if (id.isNullOrEmpty() || id == selfId || ownIps.contains(host)) return
        val name = self.getString("deviceName") ?: "Android"
        out["$host:$DEFAULT_PORT"] = DiscoveredDevice(name, host, DEFAULT_PORT)
    }

    private fun fetchSelf(host: String): com.alibaba.fastjson.JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("http://$host:$DEFAULT_PORT/api/v1/lab/topology").openConnection()
                    as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            JSON.parseObject(body)?.getJSONObject("self")
        } catch (ignored: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun collectLocal(
        prefixes: MutableSet<String>,
        ownIps: MutableSet<String>,
    ) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (name.startsWith("ccmni") || name.startsWith("rmnet") ||
                    name.startsWith("tun") || name.startsWith("ppp") ||
                    name.startsWith("dummy") || name.contains("vmnet")
                ) {
                    continue
                }
                for (address in ni.inetAddresses) {
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    val ip = address.hostAddress ?: continue
                    if (!isPrivateIpv4(ip)) continue
                    ownIps.add(ip)
                    prefixes.add(ip.substringBeforeLast('.'))
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    companion object {
        private const val TAG = "LanPeerScanner"
        private const val DEFAULT_PORT = 8080
        private const val CONNECT_TIMEOUT_MS = 350
        private const val HTTP_TIMEOUT_MS = 1_200
        private const val SCAN_THREADS = 32

        @Volatile
        private var INSTANCE: LanPeerScanner? = null

        /** 仅供单元测试重置单例。 */
        @JvmStatic
        fun resetForTest() {
            synchronized(LanPeerScanner::class.java) {
                INSTANCE?.executor?.shutdownNow()
                INSTANCE = null
            }
        }

        @JvmStatic
        fun getInstance(context: Context): LanPeerScanner {
            return INSTANCE ?: synchronized(LanPeerScanner::class.java) {
                INSTANCE ?: LanPeerScanner(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
