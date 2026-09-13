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

package com.hippo.ehviewer.lab.source

import android.content.Context
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.lab.log.SnapshotLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 局域网图片抓取器
 *
 * 协议对应：v3.0 §5.21。
 *
 * 职责：
 * <ul>
 *   <li>按 RTT 升序排列所有可信 LAN peer</li>
 *   <li>探测某 peer 是否有指定 gid/page 图片（GET {@code /api/v1/galleries/{gid}/pages/{page}}）</li>
 *   <li>从指定 peer 拉取图片二进制</li>
 *   <li>定期刷新 RTT（每 30s 一次 ping）</li>
 *   <li>per-peer 失败冷却：连续失败指数退避（5s/10s/20s/30s 封顶）</li>
 * </ul>
 */
class LanImageFetcher private constructor(context: Context) {

    private val labManager: LabManager = LabManager.getInstance(context)
    private val peerStore: TrustedPeerStore = TrustedPeerStore.getInstance(context)

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "LabLanFetcher").apply { isDaemon = true }
    }

    private val peerHealth = ConcurrentHashMap<String, PeerHealth>()

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var probeTask: ScheduledFuture<*>? = null

    fun startRttProbe() {
        if (probeTask != null && probeTask?.isCancelled == false) return
        probeTask = scheduler.scheduleAtFixedRate(
            { probeAllRtt() },
            0L, RTT_PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS,
        )
        SnapshotLogger.d(
            "LanImageFetcher", "RTT probe started (interval=${RTT_PROBE_INTERVAL_MS}ms)")
    }

    fun stopRttProbe() {
        probeTask?.cancel(false)
        probeTask = null
        SnapshotLogger.d("LanImageFetcher", "RTT probe stopped")
    }

    fun addListener(l: Listener) {
        if (l != null && !listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /**
     * 按 RTT 升序返回所有可信 LAN peer。
     * 同时过滤掉当前处于失败冷却期的 peer。
     */
    fun listByRtt(): List<TrustedPeer> {
        val now = System.currentTimeMillis()
        val peers = ArrayList(peerStore.listOnline())
        peers.removeIf { p ->
            p.host.isNullOrEmpty() || p.port <= 0 ||
                peerHealth[p.deviceId]?.isInCooldown(now) == true
        }
        Collections.sort(peers) { a, b ->
            val ra = a.rttMs
            val rb = b.rttMs
            // -1 (未探测) 排末尾
            when {
                ra < 0 && rb >= 0 -> 1
                rb < 0 && ra >= 0 -> -1
                else -> ra.compareTo(rb)
            }
        }
        return peers
    }

    /**
     * 当前 peer 连续失败次数（用于显示「该 peer 当前不可达」徽标）
     */
    fun getConsecutiveFailures(deviceId: String): Int =
        peerHealth[deviceId]?.consecutiveFailures ?: 0

    /**
     * 探测某 peer 是否有指定页。
     */
    fun probeAvailability(peer: TrustedPeer, gid: Long, page: Int): Availability? {
        return try {
            val conn = openConn(peer, "/api/v1/galleries/$gid/pages/$page?mode=local")
            conn.requestMethod = "GET"
            when (conn.responseCode) {
                200 -> Availability.HAVE
                404 -> Availability.MISSING
                403 -> Availability.UNKNOWN
                else -> Availability.UNKNOWN
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从指定 peer 拉取图片（带重试 + 健康度记录）。
     */
    fun fetchImage(peer: TrustedPeer, gid: Long, page: Int): ImageResult? {
        val health = peerHealth.computeIfAbsent(peer.deviceId) { PeerHealth() }
        val now = System.currentTimeMillis()
        if (health.isInCooldown(now)) {
            SnapshotLogger.d(
                "LanImageFetcher",
                "skip peer ${peer.deviceName} (in cooldown for ${health.cooldownUntilMs - now}ms)",
            )
            return null
        }

        val maxAttempts = 2
        for (attempt in 1..maxAttempts) {
            val r = tryFetchOnce(peer, gid, page)
            if (r != null) {
                recordPeerSuccess(peer.deviceId)
                return r
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(500L)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        recordPeerFailure(peer.deviceId)
        return null
    }

    private fun tryFetchOnce(peer: TrustedPeer, gid: Long, page: Int): ImageResult? {
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = openConn(peer, "/api/v1/galleries/$gid/pages/$page?mode=local")
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Lab-Source", "1")
            val code = conn.responseCode
            if (code != 200) {
                SnapshotLogger.d(
                    "LanImageFetcher",
                    "Peer ${peer.deviceName} returned HTTP $code for gid=$gid, page=$page",
                )
                return null
            }
            val mime = conn.contentType ?: "image/jpeg"
            val data = conn.inputStream.use { input -> readAll(input) }
            val latency = System.currentTimeMillis() - start

            val src = ImageSource.Builder()
                .kind(ImageSource.Kind.LAN)
                .deviceId(peer.deviceId)
                .deviceName(peer.deviceName)
                .latencyMs(latency)
                .build()
            ImageResult.of(data, mime, src)
        } catch (e: IOException) {
            SnapshotLogger.d(
                "LanImageFetcher",
                "fetchImage(${peer.deviceName}) failed: ${e.message}",
            )
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 立即探测所有 peer 的 RTT，更新到 [TrustedPeer]。
     */
    fun probeAllRtt() {
        for (peer in peerStore.listOnline()) {
            scheduler.execute { probeRtt(peer) }
        }
    }

    private fun probeRtt(peer: TrustedPeer) {
        if (peer.host.isNullOrEmpty() || peer.port <= 0) return
        val start = System.currentTimeMillis()
        try {
            val conn = openConn(peer, "/api/v1/device/info")
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = CONNECT_TIMEOUT_MS
            val code = conn.responseCode
            if (code == 200) {
                val rtt = System.currentTimeMillis() - start
                peerStore.touch(peer.deviceId, null, 0, rtt)
                recordPeerSuccess(peer.deviceId)
            } else {
                recordPeerFailure(peer.deviceId)
            }
        } catch (e: Exception) {
            recordPeerFailure(peer.deviceId)
        }
    }

    private fun recordPeerSuccess(deviceId: String) {
        peerHealth.computeIfAbsent(deviceId) { PeerHealth() }.recordSuccess()
    }

    private fun recordPeerFailure(deviceId: String) {
        val now = System.currentTimeMillis()
        val h = peerHealth.computeIfAbsent(deviceId) { PeerHealth() }
        h.recordFailure(now)
        SnapshotLogger.d(
            "LanImageFetcher",
            "peer $deviceId failure ${h.consecutiveFailures}, cooldown until ${h.cooldownUntilMs}",
        )
    }

    private fun openConn(peer: TrustedPeer, path: String): HttpURLConnection {
        val url = URL("http://${peer.host}:${peer.port}$path")
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "image/*")
            setRequestProperty("X-Device-ID", labManager.getSelfDeviceId())
            setRequestProperty("X-Device-Name", labManager.getSelfDeviceName())
        }
    }

    private fun readAll(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    enum class Availability { HAVE, MISSING, UNKNOWN }

    /**
     * 监听器：RTT 更新时回调（主线程）。
     */
    interface Listener {
        fun onRttUpdated(peer: TrustedPeer, rttMs: Long)
    }

    /**
     * per-peer 健康度
     */
    private class PeerHealth {
        var consecutiveFailures: Int = 0
            internal set
        var lastFailureMs: Long = 0
            private set
        var cooldownUntilMs: Long = 0
            private set

        fun isInCooldown(now: Long): Boolean = now < cooldownUntilMs

        fun recordFailure(now: Long) {
            consecutiveFailures++
            lastFailureMs = now
            // 退避：5s / 10s / 20s / 30s（封顶）
            val cooldown = (5_000L * (1L shl minOf(consecutiveFailures - 1, 4)))
                .coerceAtMost(30_000L)
            cooldownUntilMs = now + cooldown
        }

        fun recordSuccess() {
            consecutiveFailures = 0
            lastFailureMs = 0
            cooldownUntilMs = 0
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val RTT_PROBE_INTERVAL_MS = 30_000L
        private const val RTT_TTL_MS = 60_000L

        @Volatile private var INSTANCE: LanImageFetcher? = null

        @JvmStatic
        fun resetForTest() {
            synchronized(LanImageFetcher::class.java) {
                INSTANCE?.scheduler?.shutdownNow()
                INSTANCE = null
            }
        }

        @JvmStatic
        fun getInstance(context: Context): LanImageFetcher {
            return INSTANCE ?: synchronized(LanImageFetcher::class.java) {
                INSTANCE ?: LanImageFetcher(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}