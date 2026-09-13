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

package com.hippo.ehviewer.lab.relay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.lab.log.SnapshotLogger
import com.hippo.ehviewer.lab.snapshot.DbSnapshotCache
import com.hippo.ehviewer.lab.snapshot.GallerySnapshotEntry
import com.hippo.ehviewer.lab.union.GalleryUnionResolver
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 跨设备续传计划计算器（用户主动入口）
 *
 * 协议对应：v3.0 §5.20.1。
 *
 * 流程：
 * <ol>
 *   <li>调用 §5.20.1 获取 union 缺页 + 建议源</li>
 *   <li>基于 [GalleryUnionResolver] 的本地视图做交叉校验</li>
 *   <li>返回最终的补齐计划 + 风险评估</li>
 *   <li>用户确认后调用 [RelayInvoker.invokeFetch] 启动调度</li>
 * </ol>
 */
class ResumePlanCalculator private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val cache: DbSnapshotCache = DbSnapshotCache.getInstance(context)
    private val resolver: GalleryUnionResolver = GalleryUnionResolver.getInstance(context)
    private val peerStore: TrustedPeerStore = TrustedPeerStore.getInstance(context)
    private val labManager: LabManager = LabManager.getInstance(context)
    private val scheduler: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LabResumePlan").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * 续传计划（UI 友好）
     */
    class Plan(
        val gid: Long,
        val title: String,
        val localDownloadedPages: Int,
        val unionDownloadedPages: Int,
        val totalPages: Int,
        val missingPages: List<Int>,
        val suggestedSourceId: String?,
        val suggestedSourceName: String?,
        val estimatedDurationMs: Long,
        val note: String?,
    ) {
        fun hasMissing(): Boolean = missingPages.isNotEmpty()
        fun missingCount(): Int = missingPages.size
    }

    /**
     * 风险等级
     */
    enum class Risk {
        /** 安全：源设备可信、有覆盖、网速良好 */
        SAFE,
        /** 中等：源设备覆盖<80% 或 RTT > 200ms */
        MEDIUM,
        /** 高：源设备覆盖<50% 或 RTT > 500ms 或无源 */
        HIGH,
    }

    /**
     * 同步计算计划。在线程安全的上下文中调用；通常在 IO 线程。
     */
    fun compute(gid: Long): Plan {
        // 1. 本机信息
        val dm: DownloadManager = EhApplication.getDownloadManager(appContext)
        val info = if (dm != null) dm.getDownloadInfo(gid) else null
        val title = info?.title ?: ""
        val localDownloaded = if (info != null && info.finished > 0) info.finished else 0
        var totalPages = if (info != null && info.pages > 0) info.pages else 0

        // 2. union 信息
        resolver.refresh()
        val unionDownloaded = resolver.unionDownloadedPages(gid)
        val unionTotal = resolver.unionPages(gid)
        if (unionTotal > 0) totalPages = maxOf(totalPages, unionTotal)

        // 3. 缺页列表
        val missing = resolver.computeLocalMissing(gid, localDownloaded)

        // 4. 最佳源
        val sourceId = resolver.pickBestSource(gid)
        var sourceName: String? = null
        var estimatedDurationMs = -1L
        if (sourceId != null) {
            val peer = peerStore.findById(sourceId)
            if (peer != null) {
                sourceName = peer.deviceName
                val avgPageBytes = 500L * 1024L
                val rttMs = if (peer.rttMs > 0) peer.rttMs else 100L
                val bytesPerSec = maxOf(1L, (avgPageBytes * 1000L / rttMs))
                estimatedDurationMs = missing.size * avgPageBytes * 1000L / bytesPerSec
            }
        }

        val note: String? = when {
            missing.isEmpty() -> "本机已是 union 最新，无需补齐"
            sourceId == null -> "没有可信 LAN 源设备，自动接力不可用"
            else -> null
        }

        val plan = Plan(
            gid, title, localDownloaded, unionDownloaded, totalPages,
            missing, sourceId, sourceName, estimatedDurationMs, note,
        )

        mainHandler.post {
            for (l in listeners) l.onPlanComputed(plan)
        }
        SnapshotLogger.d(
            "ResumePlanCalculator",
            "compute gid=$gid: missing=${missing.size}, suggested=$sourceId",
        )
        return plan
    }

    fun submit(gid: Long): Future<Plan> = scheduler.submit(java.util.concurrent.Callable<Plan> { compute(gid) })

    /**
     * 评估风险等级
     */
    fun assessRisk(plan: Plan): Risk {
        if (plan.suggestedSourceId == null) return Risk.HIGH
        val peer = peerStore.findById(plan.suggestedSourceId) ?: return Risk.HIGH
        if (peer.rttMs > 500) return Risk.HIGH

        var sourceDownloaded = -1
        for (e in cache.queryByGid(plan.gid)) {
            if (plan.suggestedSourceId == e.sourceDeviceId) {
                sourceDownloaded = e.downloadedPages
                break
            }
        }
        if (sourceDownloaded < 0) return Risk.MEDIUM
        if (plan.totalPages <= 0) return Risk.MEDIUM
        val coverage = sourceDownloaded.toDouble() / plan.totalPages
        if (coverage < 0.5) return Risk.HIGH
        if (coverage < 0.8) return Risk.MEDIUM
        return Risk.SAFE
    }

    fun estimateTotalBytes(plan: Plan): Long = plan.missingPages.size * 500L * 1024L

    /**
     * 异步调用 §5.20.1 远端 API（用于本地 union 视图缺失时的兜底）。
     */
    fun fetchRemotePlan(gid: Long): RemotePlan? {
        val peer = pickRandomPeer() ?: return null
        val url = "http://${peer.host}:${peer.port}/api/v1/galleries/$gid/resume-plan"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 10_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Device-ID", labManager.getSelfDeviceId())
            }
            val body = "{\"strategy\":\"union\"}"
            conn.setFixedLengthStreamingMode(body.toByteArray().size)
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code != 200) return null
            val respBody = conn.inputStream.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
            }
            RemotePlan.fromJson(JSONObject.parseObject(respBody))
        } catch (e: IOException) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun pickRandomPeer(): TrustedPeer? {
        for (p in peerStore.listOnline()) {
            if (!p.host.isNullOrEmpty() && p.port > 0) return p
        }
        return null
    }

    fun interface Listener {
        fun onPlanComputed(plan: Plan)
    }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /**
     * 远端 plan（从 §5.20.1 响应解析）
     */
    data class RemotePlan(
        val gid: Long,
        val total: Int,
        val unionDownloadedPages: Int,
        val localDownloadedPages: Int,
        val missingPages: List<Int>,
        val suggestedSource: String?,
    ) {
        companion object {
            fun fromJson(json: JSONObject?): RemotePlan? {
                if (json == null) return null
                return try {
                    val gid = json.getLongValue("gid")
                    val total = json.getIntValue("total")
                    val union = json.getIntValue("unionDownloadedPages")
                    val local = json.getIntValue("localDownloadedPages")
                    val missing = ArrayList<Int>()
                    val arr = json.getJSONArray("missingPages")
                    if (arr != null) for (i in 0 until arr.size) missing.add(arr.getIntValue(i))
                    val src = json.getString("suggestedSource")
                    RemotePlan(gid, total, union, local, missing, src)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    companion object {
        @Volatile private var INSTANCE: ResumePlanCalculator? = null

        @JvmStatic
        fun resetForTest() {
            synchronized(ResumePlanCalculator::class.java) { INSTANCE = null }
        }

        @JvmStatic
        fun getInstance(context: Context): ResumePlanCalculator {
            return INSTANCE ?: synchronized(ResumePlanCalculator::class.java) {
                INSTANCE ?: ResumePlanCalculator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}