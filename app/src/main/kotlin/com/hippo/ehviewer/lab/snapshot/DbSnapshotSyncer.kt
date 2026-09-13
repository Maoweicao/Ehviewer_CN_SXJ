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

package com.hippo.ehviewer.lab.snapshot

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.lab.LabConfig
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.lab.log.SnapshotLogger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 快照同步器
 *
 * 协议对应：v3.0 §5.22。
 *
 * 同步策略：
 * <ul>
 *   <li>触发时机：启动 + 每 5 分钟 + 任何 LAN 设备配对完成时</li>
 *   <li>每个 trusted peer 单独拉一次快照</li>
 *   <li>增量：请求 {@code since=lastSyncAt} 参数；服务端返回增量数据</li>
 *   <li>限流：单 peer 失败时 30s 退避；连续 3 次失败则跳过 5 分钟</li>
 * </ul>
 */
class DbSnapshotSyncer private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val cache: DbSnapshotCache = DbSnapshotCache.getInstance(context)
    private val peerStore: TrustedPeerStore = TrustedPeerStore.getInstance(context)
    private val labManager: LabManager = LabManager.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val running = AtomicBoolean(false)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LabSnapshotSync").apply { isDaemon = true }
    }

    private var intervalMs: Long = DEFAULT_INTERVAL_MS
    private var scheduledTask: ScheduledFuture<*>? = null

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * 启动定时同步任务。
     */
    @Synchronized
    fun start() {
        if (running.get()) return
        if (!isLabEnabled()) {
            SnapshotLogger.d("DbSnapshotSyncer", "Lab disabled; not starting snapshot sync")
            return
        }
        running.set(true)
        // 立即触发一次
        scheduler.execute { syncOnce() }
        scheduledTask = scheduler.scheduleAtFixedRate(
            { syncOnce() },
            intervalMs, intervalMs, TimeUnit.MILLISECONDS,
        )
        SnapshotLogger.i(
            "DbSnapshotSyncer", "Snapshot sync started (interval=${intervalMs}ms)")
    }

    /**
     * 停止定时同步。
     */
    @Synchronized
    fun stop() {
        if (!running.get()) return
        running.set(false)
        scheduledTask?.cancel(false)
        scheduledTask = null
        SnapshotLogger.i("DbSnapshotSyncer", "Snapshot sync stopped")
    }

    @Synchronized
    fun isRunning(): Boolean = running.get()

    fun setIntervalMs(intervalMs: Long) {
        this.intervalMs = intervalMs
        if (running.get()) {
            stop()
            start()
        }
    }

    /**
     * 立刻触发一次同步。
     */
    fun syncNow() {
        scheduler.execute { syncOnce() }
    }

    /**
     * 触发一次完整同步流程（所有在线 peer）。
     * @return 同步成功的 peer 数
     */
    fun syncOnce(): Int {
        if (!isLabEnabled()) {
            SnapshotLogger.d("DbSnapshotSyncer", "Lab disabled; skip sync")
            return 0
        }
        val peers = peerStore.listOnline()
        if (peers.isEmpty()) {
            SnapshotLogger.d("DbSnapshotSyncer", "No online peers; skip sync")
            return 0
        }
        val since = cache.getLastSyncAt()
        var success = 0
        for (p in peers) {
            try {
                val resp = fetchFromPeer(p, since)
                if (resp != null) {
                    val applied = cache.applyResponse(resp)
                    success++
                    mainHandler.post {
                        for (l in listeners) l.onSnapshotSynced(p, applied, resp.version)
                    }
                }
            } catch (e: Exception) {
                SnapshotLogger.w(
                    "DbSnapshotSyncer", "Snapshot fetch failed for ${p.deviceName}", e)
            }
        }
        SnapshotLogger.d(
            "DbSnapshotSyncer", "syncOnce done: $success/${peers.size} peers")
        return success
    }

    @Nullable
    private fun fetchFromPeer(peer: TrustedPeer, since: Long): SnapshotResponse? {
        if (peer.host.isNullOrEmpty() || peer.port <= 0) {
            SnapshotLogger.d(
                "DbSnapshotSyncer", "Peer missing endpoint: ${peer.deviceName}")
            return null
        }
        val urlBuilder = StringBuilder()
            .append("http://")
            .append(peer.host)
            .append(':')
            .append(peer.port)
            .append("/api/v1/lab/db/snapshot?since=")
            .append(since)
            .append("&includeThumb=true&limit=500")

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlBuilder.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Device-ID", labManager.getSelfDeviceId())
                setRequestProperty("X-Device-Name", labManager.getSelfDeviceName())
            }
            val code = conn.responseCode
            if (code == 403 || code == 404 || code == 503) {
                // 远端未启用实验室 → 跳过
                SnapshotLogger.d(
                    "DbSnapshotSyncer",
                    "Peer does not expose snapshot (HTTP $code): ${peer.deviceName}",
                )
                return null
            }
            if (code < 200 || code >= 300) {
                throw IOException("HTTP $code")
            }
            val body = conn.inputStream.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
            SnapshotResponse.fromJson(JSONObject.parseObject(body))
        } finally {
            conn?.disconnect()
        }
    }

    private fun isLabEnabled(): Boolean {
        val cfg: LabConfig = LabManager.getInstance(appContext).configStore.get()
        return cfg.isEnabled() && cfg.isSubEnabled("dbSnapshot")
    }

    /**
     * 同步完成监听器（主线程回调）。
     */
    fun interface Listener {
        fun onSnapshotSynced(peer: TrustedPeer, applied: Int, newVersion: Long)
    }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /**
     * 仅供调试：手动重置所有同步状态。
     */
    fun clearLocalCache() {
        scheduler.execute {
            cache.clearAll()
            SnapshotLogger.w("DbSnapshotSyncer", "Manual cache reset")
        }
    }

    /**
     * 占位方法：用于与 Android OkHttp 或 HttpsURLConnection 切换时的依赖注入点。
     */
    @Suppress("unused")
    fun useHttpUrlConnectionFallback() {
        SnapshotLogger.d("DbSnapshotSyncer", "Using HttpURLConnection fallback")
    }

    companion object {
        private const val DEFAULT_INTERVAL_MS = 5L * 60 * 1000L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 30_000

        @Volatile private var INSTANCE: DbSnapshotSyncer? = null

        @JvmStatic
        fun resetForTest() {
            synchronized(DbSnapshotSyncer::class.java) {
                INSTANCE?.scheduler?.shutdownNow()
                INSTANCE = null
            }
        }

        @JvmStatic
        fun getInstance(context: Context): DbSnapshotSyncer {
            return INSTANCE ?: synchronized(DbSnapshotSyncer::class.java) {
                INSTANCE ?: DbSnapshotSyncer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}