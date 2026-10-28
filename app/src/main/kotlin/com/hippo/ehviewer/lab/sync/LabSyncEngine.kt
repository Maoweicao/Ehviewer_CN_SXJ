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

package com.hippo.ehviewer.lab.sync

import android.content.Context
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.lab.LabConfigStore
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.TransferPortHelper
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.lab.log.SnapshotLogger
import com.hippo.ehviewer.transfer.core.NetworkUtils
import com.hippo.ehviewer.transfer.index.BucketLayout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 设备间快速同步引擎（协议 §5.30）。
 *
 * <p>数据面完全复用 §5.29 画廊索引：先取 scope/桶位图，再按 GID keyset 拉取变更桶，
 * 最后合并进本地数据库。图片默认不复制，按需经 §5.21 从对端读取。
 *
 * <p>方向语义：
 * <ul>
 *   <li>{@code pull}：本机拉取对端；对端会话自动成为 {@code serve}；</li>
 *   <li>{@code push}：本机请求对端反向拉取本机；本机不写本地数据；</li>
 *   <li>{@code bidirectional}：本地拉取的同时，对端也会反向拉取。</li>
 * </ul>
 */
class LabSyncEngine private constructor(private val context: Context) {

    fun interface ProgressListener {
        fun onProgress(phase: String, current: Int, total: Int, detail: String?)
    }

    data class Outcome(
        val peerName: String,
        val applied: Int,
        val error: String? = null,
    ) {
        val success: Boolean get() = error == null
    }

    private data class Endpoint(val host: String, val port: Int)

    private val appContext = context.applicationContext
    private val peerStore = TrustedPeerStore.getInstance(appContext)
    private val labManager = LabManager.getInstance(appContext)
    private val configStore = LabConfigStore.getInstance(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 一次手动/自动化同步（发起方视角）。 */
    fun requestSync(peer: TrustedPeer, direction: LabSyncDirection, listener: ProgressListener?): Outcome {
        if (!isReady()) {
            return Outcome(peer.deviceName, 0, "实验室同步未启用")
        }
        if (peer.presharedKey.isNullOrEmpty()) {
            return Outcome(peer.deviceName, 0, "设备未完成配对，无法签名")
        }
        val endpoint = endpointOf(peer) ?: return Outcome(peer.deviceName, 0, "设备地址不可用")

        var sessionId: String? = null
        var applied = 0
        return try {
            val session = createSession(peer, endpoint, direction)
            sessionId = session.getString("sessionId")
            if (sessionId.isNullOrEmpty()) {
                return Outcome(peer.deviceName, 0, "对端未返回会话")
            }

            if (direction != LabSyncDirection.PUSH) {
                applied = pullScopes(peer, endpoint, listener)
            }
            if (direction != LabSyncDirection.PULL) {
                // 对端反向拉取本机；推送时等待对端完成，双向时做一次短暂状态同步
                waitForPeerPull(peer, endpoint, sessionId, direction == LabSyncDirection.PUSH, listener)
            }
            Outcome(peer.deviceName, applied, null)
        } catch (t: Throwable) {
            SnapshotLogger.w(TAG, "同步失败: ${peer.deviceName} - ${t.message}", t)
            Outcome(peer.deviceName, applied, t.message ?: "同步失败")
        } finally {
            if (sessionId != null) {
                try {
                    closeSession(peer, endpoint, sessionId)
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    /** 自动化：依次同步所有已配对且在线/有地址的设备。 */
    fun syncAll(direction: LabSyncDirection, listener: ProgressListener?): Int {
        if (!isReady()) return 0
        val peers = peerStore.list().filter {
            !it.presharedKey.isNullOrEmpty() && !it.host.isNullOrEmpty() && it.port > 0
        }
        if (peers.isEmpty()) {
            SnapshotLogger.d(TAG, "没有可同步的已配对设备")
            return 0
        }
        var success = 0
        peers.forEachIndexed { index, peer ->
            listener?.onProgress("peer", index, peers.size, peer.deviceName)
            if (requestSync(peer, direction, listener).success) {
                success++
            }
        }
        listener?.onProgress("done", peers.size, peers.size, null)
        return success
    }

    /**
     * 被对端会话触发的反向拉取（本机作为数据源，主动从对端拉取）。
     *
     * @param endpoint 对端（请求发起方）的可达地址
     */
    fun pullFromPeer(
        peer: TrustedPeer,
        host: String?,
        port: Int,
        sessionId: String?,
        listener: ProgressListener?,
    ): Outcome {
        if (!isReady()) return Outcome(peer.deviceName, 0, "实验室同步未启用")
        val endpoint = if (!host.isNullOrEmpty() && port > 0) Endpoint(host, port) else endpointOf(peer)
        if (endpoint == null) {
            sessionId?.let { LabSyncSessionStore.getInstance().finish(it, "failed", "地址不可用") }
            return Outcome(peer.deviceName, 0, "设备地址不可用")
        }
        return try {
            val applied = pullScopes(peer, endpoint, object : ProgressListener {
                override fun onProgress(phase: String, current: Int, total: Int, detail: String?) {
                    sessionId?.let { LabSyncSessionStore.getInstance().update(it, null, detail ?: phase) }
                    listener?.onProgress(phase, current, total, detail)
                }
            })
            sessionId?.let { LabSyncSessionStore.getInstance().update(it, applied, "已应用 $applied 条") }
            sessionId?.let { LabSyncSessionStore.getInstance().finish(it, "completed", "已应用 $applied 条") }
            Outcome(peer.deviceName, applied, null)
        } catch (t: Throwable) {
            SnapshotLogger.w(TAG, "反向拉取失败: ${peer.deviceName} - ${t.message}", t)
            sessionId?.let { LabSyncSessionStore.getInstance().finish(it, "failed", t.message) }
            Outcome(peer.deviceName, 0, t.message ?: "同步失败")
        }
    }

    fun isReady(): Boolean {
        val cfg = configStore.get()
        return cfg.isEnabled() && cfg.isSubEnabled("dbSnapshot")
    }

    // ==================== 数据面 ====================

    private fun pullScopes(peer: TrustedPeer, endpoint: Endpoint, listener: ProgressListener?): Int {
        var applied = 0
        val scopes = listOf("downloads", "favorites")
        for ((index, scope) in scopes.withIndex()) {
            listener?.onProgress("manifest", index, scopes.size, scope)
            val key = "wm:${peer.deviceId}:$scope"
            var watermark = prefs.getLong(key, 0L)
            var manifest = fetchManifest(peer, endpoint, scope, watermark)

            if (manifest.getBooleanValue("sinceStale")) {
                watermark = 0L
                prefs.edit().putLong(key, 0L).apply()
                manifest = fetchManifest(peer, endpoint, scope, 0L)
            }
            val epoch = manifest.getString("epoch")
            val epochKey = "epoch:${peer.deviceId}"
            val knownEpoch = prefs.getString(epochKey, null)
            if (knownEpoch != null && knownEpoch != epoch) {
                watermark = 0L
                prefs.edit().putLong(key, 0L).apply()
                manifest = fetchManifest(peer, endpoint, scope, 0L)
            }
            if (!epoch.isNullOrEmpty()) {
                prefs.edit().putString(epochKey, epoch).apply()
            }

            val base = manifest.getIntValue("baseBucket")
            val bitCount = manifest.getIntValue("bitCount")
            val buckets = resolveBuckets(manifest, base, bitCount, watermark)
            val bucketSize = manifest.getIntValue("bucketSize").takeIf { it > 0 }
                    ?: BucketLayout.DEFAULT_BUCKET_SIZE
            var scopeComplete = true

            val pool = java.util.concurrent.Executors.newFixedThreadPool(
                minOf(3, buckets.size.coerceAtLeast(1)))
            try {
                val futures = buckets.map { bucket ->
                    pool.submit(java.util.concurrent.Callable {
                        pullBucket(peer, endpoint, scope, bucket, bucketSize, listener)
                    })
                }
                futures.forEachIndexed { bucketIndex, future ->
                    listener?.onProgress("rows", bucketIndex, buckets.size, scope)
                    val result = future.get()
                    applied += result.applied
                    if (!result.complete) {
                        scopeComplete = false
                    }
                }
            } finally {
                pool.shutdownNow()
            }

            // 只有整个 scope 的脏桶都成功应用后才推进水位
            if (scopeComplete) {
                prefs.edit().putLong(key, manifest.getLongValue("version")).apply()
            } else {
                SnapshotLogger.w(TAG, "scope=$scope 未完整应用，保留旧水位")
            }
            SnapshotLogger.i(TAG, "scope=$scope 完成: buckets=${buckets.size}, applied=$applied, complete=$scopeComplete")
        }
        listener?.onProgress("done", scopes.size, scopes.size, null)
        return applied
    }

    private fun resolveBuckets(manifest: JSONObject, base: Int, bitCount: Int, watermark: Long): List<Int> {
        if (bitCount <= 0) return emptyList()
        val expanded = manifest.getJSONArray("changedBuckets")
        if (watermark > 0 && expanded != null) {
            return (0 until expanded.size).mapNotNull { expanded.getInteger(it) }
        }
        val bitmaps = manifest.getJSONObject("bitmaps") ?: return emptyList()
        val encoded = if (watermark > 0) bitmaps.getString("changed") else bitmaps.getString("presence")
        if (encoded.isNullOrEmpty()) return emptyList()
        return try {
            BucketLayout.decodeBitmap(encoded, base, bitCount).toList()
        } catch (t: Throwable) {
            SnapshotLogger.w(TAG, "位图解析失败: ${t.message}")
            emptyList()
        }
    }

    private fun fetchManifest(peer: TrustedPeer, endpoint: Endpoint, scope: String, watermark: Long): JSONObject {
        val query = StringBuilder()
                .append("scope=").append(scope)
                .append("&state=all&bitmaps=presence,changed&counts=0&fingerprints=0&expand=changed")
        if (watermark > 0) {
            query.append("&since=").append(watermark)
        }
        return getJson(peer, endpoint, "/api/v1/sync/gallery-index?$query")
    }

    private data class BucketResult(val applied: Int, val complete: Boolean)

    private fun pullBucket(
        peer: TrustedPeer,
        endpoint: Endpoint,
        scope: String,
        bucket: Int,
        bucketSize: Int,
        listener: ProgressListener?,
    ): BucketResult {
        var cursor = 0L
        var pages = 0
        var restart = 0
        var applied = 0
        var expectedVersion = 0L
        while (pages < MAX_PAGES_PER_BUCKET && restart < 2) {
            val query = StringBuilder()
                    .append("scope=").append(scope)
                    .append("&state=all&fields=full&limit=500&bucketSize=").append(bucketSize)
            if (cursor > 0) {
                query.append("&cursor=").append(cursor)
            }
            if (expectedVersion > 0) {
                query.append("&expectedVersion=").append(expectedVersion)
            }
            val json = getJson(peer, endpoint, "/api/v1/sync/gallery-index/buckets/$bucket?$query")
            if (json.getBooleanValue("versionChanged")) {
                // 桶在翻页期间变化：从第一页重拉
                restart++
                cursor = 0L
                pages = 0
                expectedVersion = 0L
                continue
            }
            if (expectedVersion == 0L) {
                expectedVersion = json.getLongValue("version")
            }
            val rows = json.getJSONArray("galleries")
            if (rows != null && rows.isNotEmpty()) {
                val parsed = ArrayList<JSONObject>(rows.size)
                for (i in 0 until rows.size) {
                    rows.getJSONObject(i)?.let { parsed.add(it) }
                }
                applied += LabSyncApplier.apply(appContext, scope, parsed)
                listener?.onProgress("rows", pages, pages + 1, "$scope #$bucket")
            }
            val next = json.getString("nextCursor")?.toLongOrNull() ?: 0L
            if (next <= 0) {
                return BucketResult(applied, true)
            }
            cursor = next
            pages++
        }
        return BucketResult(applied, false)
    }

    // ==================== 会话 ====================

    private fun createSession(
        peer: TrustedPeer,
        endpoint: Endpoint,
        direction: LabSyncDirection,
    ): JSONObject {
        val body = JSONObject()
        body["direction"] = direction.wire
        body["scopes"] = JSONArray().apply { add("downloads"); add("favorites") }
        body["sourceHost"] = NetworkUtils.getWifiIpAddress()
        body["sourcePort"] = TransferPortHelper.getPort(appContext)
        body["deviceId"] = labManager.getSelfDeviceId()
        body["deviceName"] = labManager.getSelfDeviceName()
        return postJson(peer, endpoint, "/api/v1/lab/sync/sessions", body)
    }

    private fun closeSession(peer: TrustedPeer, endpoint: Endpoint, sessionId: String) {
        val body = JSONObject()
        body["deviceId"] = labManager.getSelfDeviceId()
        postJson(peer, endpoint, "/api/v1/lab/sync/sessions/$sessionId/close", body)
    }

    private fun waitForPeerPull(
        peer: TrustedPeer,
        endpoint: Endpoint,
        sessionId: String,
        waitForCompletion: Boolean,
        listener: ProgressListener?,
    ) {
        val deadline = System.currentTimeMillis() + (if (waitForCompletion) 120_000L else 15_000L)
        var lastApplied = 0
        while (System.currentTimeMillis() < deadline) {
            val status = try {
                getJson(peer, endpoint, "/api/v1/lab/sync/sessions/$sessionId")
            } catch (t: Throwable) {
                return
            }
            val state = status.getString("state") ?: return
            val applied = status.getIntValue("applied")
            if (applied != lastApplied) {
                lastApplied = applied
                listener?.onProgress("peer", 0, 1, "对端已应用 $applied 条")
            }
            if (state != "running") {
                listener?.onProgress("peer", 1, 1, status.getString("detail"))
                return
            }
            if (!waitForCompletion) return
            try {
                Thread.sleep(1000L)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    // ==================== HTTP ====================

    private fun endpointOf(peer: TrustedPeer): Endpoint? {
        val host = peer.host
        if (host.isNullOrEmpty() || peer.port <= 0) return null
        return Endpoint(host, peer.port)
    }

    private fun getJson(peer: TrustedPeer, endpoint: Endpoint, path: String): JSONObject {
        return requestJson(peer, endpoint, "GET", path, null)
    }

    private fun postJson(peer: TrustedPeer, endpoint: Endpoint, path: String, body: JSONObject): JSONObject {
        return requestJson(peer, endpoint, "POST", path, body)
    }

    private fun requestJson(
        peer: TrustedPeer,
        endpoint: Endpoint,
        method: String,
        path: String,
        body: JSONObject?,
    ): JSONObject {
        val url = URL("http://${endpoint.host}:${endpoint.port}$path")
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = method
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Device-ID", labManager.getSelfDeviceId())
                setRequestProperty("X-Device-Name", labManager.getSelfDeviceName())
                LabSyncAuth.signHeaders(appContext, peer, method, path).forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                connection.outputStream.use { output: OutputStream ->
                    output.write(body.toJSONString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${text.take(200)}")
            }
            return JSONObject.parseObject(text) ?: throw IllegalStateException("空响应")
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val TAG = "LabSyncEngine"
        private const val PREFS = "lab_sync"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_PAGES_PER_BUCKET = 400

        @Volatile private var INSTANCE: LabSyncEngine? = null

        @JvmStatic
        fun getInstance(context: Context): LabSyncEngine {
            return INSTANCE ?: synchronized(LabSyncEngine::class.java) {
                INSTANCE ?: LabSyncEngine(context).also { INSTANCE = it }
            }
        }

        @JvmStatic
        fun resetForTest() {
            synchronized(LabSyncEngine::class.java) {
                INSTANCE = null
            }
        }
    }
}
