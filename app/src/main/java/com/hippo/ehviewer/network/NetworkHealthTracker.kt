package com.hippo.ehviewer.network

import android.util.Log
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 常驻、极低开销的网络健康采集器。
 *
 * 通过 OkHttp 的 [EventListener.Factory] 安装到所有 OkHttp 客户端上，
 * 统计在飞请求数、按 host 的分布、慢请求/挂死、连接池水位等指标。
 * 数据保存在有界内存结构中，供 [HealthWatchdog] 与 [PerformanceMonitor] 在
 * 应用无响应前快照落盘使用。
 */
object NetworkHealthTracker {

    private const val TAG = "NetworkHealthTracker"
    private const val MAX_RECENT = 200
    private const val SLOW_REQUEST_MS = 15_000L

    /** 慢请求 / 挂死判定阈值（毫秒） */
    @Volatile
    var slowRequestThresholdMs: Long = SLOW_REQUEST_MS

    private val inflight = AtomicInteger(0)
    private val perHostInflight = ConcurrentHashMap<String, AtomicInteger>()

    private val totalCalls = AtomicLong(0)
    private val failedCalls = AtomicLong(0)
    private val timeoutCalls = AtomicLong(0)
    private val slowCalls = AtomicLong(0)

    private val recent = ArrayDequeFixed(MAX_RECENT)
    private val stalled = ArrayDequeFixed(MAX_RECENT)

    @Volatile
    private var connectionPool: ConnectionPool? = null

    fun setConnectionPool(pool: ConnectionPool?) {
        connectionPool = pool
    }

    fun getInflight(): Int = inflight.get()

    fun getPerHostInflight(): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for ((host, counter) in perHostInflight) {
            val v = counter.get()
            if (v > 0) result[host] = v
        }
        return result
    }

    fun getConnectionCount(): Int = runCatching { connectionPool?.connectionCount() ?: -1 }.getOrDefault(-1)
    fun getIdleConnectionCount(): Int = runCatching { connectionPool?.idleConnectionCount() ?: -1 }.getOrDefault(-1)

    fun createEventListenerFactory(): EventListener.Factory {
        return object : EventListener.Factory {
            override fun create(call: Call): EventListener {
                return Tracker(call)
            }
        }
    }

    private class Tracker(private val call: Call) : EventListener() {
        private val startNanos = System.nanoTime()
        private var host: String = call.request().url().host()
        private var dnsMs = 0L
        private var connectMs = 0L
        private var sslMs = 0L
        private var startMs = 0L
        private var dnsStart = 0L
        private var connectStart = 0L
        private var sslStart = 0L
        private var firstByteMs = 0L
        private var endMs = 0L
        private var failed = false
        private var failure: String? = null

        init {
            inflight.incrementAndGet()
            perHostInflight.computeIfAbsent(host) { AtomicInteger(0) }.incrementAndGet()
            totalCalls.incrementAndGet()
        }

        override fun dnsStart(call: Call, domainName: String) {
            dnsStart = System.currentTimeMillis()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            if (dnsStart > 0) dnsMs = System.currentTimeMillis() - dnsStart
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStart = System.currentTimeMillis()
        }

        override fun secureConnectStart(call: Call) {
            sslStart = System.currentTimeMillis()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            if (sslStart > 0) sslMs = System.currentTimeMillis() - sslStart
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            if (connectStart > 0) connectMs = System.currentTimeMillis() - connectStart
        }

        override fun responseHeadersStart(call: Call) {
            if (startMs == 0L) startMs = System.currentTimeMillis()
        }

        override fun responseBodyStart(call: Call) {
            if (firstByteMs == 0L) firstByteMs = System.currentTimeMillis()
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            endMs = System.currentTimeMillis()
        }

        override fun callEnd(call: Call) {
            finish(null)
        }

        override fun callFailed(call: Call, ioe: java.io.IOException) {
            failed = true
            failure = ioe.javaClass.simpleName
            finish(ioe)
        }

        private fun finish(ioe: java.io.IOException?) {
            inflight.decrementAndGet()
            perHostInflight[host]?.decrementAndGet()

            val now = System.currentTimeMillis()
            if (endMs == 0L) endMs = now
            if (startMs == 0L) startMs = now
            val waitMs = (firstByteMs - startMs).coerceAtLeast(0)
            val totalMs = (endMs - startMs).coerceAtLeast(0)

            if (failed) {
                failedCalls.incrementAndGet()
                val msg = failure ?: "IOException"
                if (msg.contains("Timeout", ignoreCase = true) ||
                    msg.contains("SocketTimeout", ignoreCase = true) ||
                    msg.contains("TimeoutException", ignoreCase = true)
                ) {
                    timeoutCalls.incrementAndGet()
                }
            }

            val url = runCatching { call.request().url().toString() }.getOrDefault("")
            val entry = RecentEntry(
                host = host,
                url = url,
                totalMs = totalMs,
                dnsMs = dnsMs,
                connectMs = connectMs,
                sslMs = sslMs,
                waitMs = waitMs,
                failed = failed,
                failure = failure
            )
            recent.add(entry)

            if (totalMs >= slowRequestThresholdMs) {
                slowCalls.incrementAndGet()
                stalled.add(entry)
            }

            if (failed) {
                Log.w(TAG, "call failed: $failure host=$host total=${totalMs}ms")
            }
        }
    }

    data class RecentEntry(
        val host: String,
        val url: String,
        val totalMs: Long,
        val dnsMs: Long,
        val connectMs: Long,
        val sslMs: Long,
        val waitMs: Long,
        val failed: Boolean,
        val failure: String?
    )

    data class Snapshot(
        val inflight: Int,
        val perHostInflight: Map<String, Int>,
        val connectionCount: Int,
        val idleConnectionCount: Int,
        val totalCalls: Long,
        val failedCalls: Long,
        val timeoutCalls: Long,
        val slowCalls: Long,
        val recent: List<RecentEntry>,
        val stalled: List<RecentEntry>
    )

    fun getSnapshot(): Snapshot = Snapshot(
        inflight = inflight.get(),
        perHostInflight = getPerHostInflight(),
        connectionCount = getConnectionCount(),
        idleConnectionCount = getIdleConnectionCount(),
        totalCalls = totalCalls.get(),
        failedCalls = failedCalls.get(),
        timeoutCalls = timeoutCalls.get(),
        slowCalls = slowCalls.get(),
        recent = recent.toList(),
        stalled = stalled.toList()
    )

    fun format(sb: StringBuilder) {
        val s = getSnapshot()
        sb.append("== Network Health ==\n")
        sb.append("inflight=${s.inflight}  connectionPool=${s.connectionCount}(idle=${s.idleConnectionCount})\n")
        sb.append("totalCalls=${s.totalCalls}  failed=${s.failedCalls}  timeouts=${s.timeoutCalls}  slow(>=${slowRequestThresholdMs}ms)=${s.slowCalls}\n")
        if (s.perHostInflight.isNotEmpty()) {
            sb.append("per-host inflight: ")
            sb.append(s.perHostInflight.entries.joinToString { "${it.key}=${it.value}" })
            sb.append('\n')
        }
        if (s.stalled.isNotEmpty()) {
            sb.append("stalled/slow requests (last ${s.stalled.size}):\n")
            for (e in s.stalled.takeLast(10)) {
                sb.append("  ${e.totalMs}ms host=${e.host} failed=${e.failed}${if (e.failure != null) "(${e.failure})" else ""} ${e.url}\n")
            }
        }
    }
}

/**
 * 固定容量的线程安全双端队列（FIFO 淘汰），避免无界增长。
 */
private class ArrayDequeFixed(capacity: Int) {
    private val cap = capacity.coerceAtLeast(1)
    private val deque = java.util.ArrayDeque<NetworkHealthTracker.RecentEntry>()

    @Synchronized
    fun add(entry: NetworkHealthTracker.RecentEntry) {
        while (deque.size >= cap) deque.pollFirst()
        deque.addLast(entry)
    }

    @Synchronized
    fun toList(): List<NetworkHealthTracker.RecentEntry> = ArrayList(deque)
}
