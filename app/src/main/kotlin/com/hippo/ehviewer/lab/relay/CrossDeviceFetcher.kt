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
import com.hippo.ehviewer.lab.TransferPortHelper
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.lab.log.SnapshotLogger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 跨设备批量拉取器（用户主动续传入口）
 */
class CrossDeviceFetcher private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val peerStore: TrustedPeerStore = TrustedPeerStore.getInstance(context)
    private val scheduler: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LabCrossDeviceFetcher").apply { isDaemon = true }
    }
    private val activeCount = AtomicInteger(0)

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * 启动批量续传
     */
    fun start(plan: ResumePlanCalculator.Plan): java.util.concurrent.Future<PageRelaySession.BatchResult> {
        activeCount.incrementAndGet()
        return scheduler.submit(
            java.util.concurrent.Callable<PageRelaySession.BatchResult> {
                try {
                    execute(plan)
                } finally {
                    activeCount.decrementAndGet()
                }
            },
        )
    }

    /**
     * 同步执行批量续传
     */
    fun execute(plan: ResumePlanCalculator.Plan): PageRelaySession.BatchResult {
        if (plan.missingPages.isEmpty()) {
            SnapshotLogger.d(
                "CrossDeviceFetcher", "plan has no missing pages; skipping")
            return PageRelaySession.BatchResult(emptyList())
        }
        if (plan.suggestedSourceId == null) {
            SnapshotLogger.w(
                "CrossDeviceFetcher", "No suggested source for gid=${plan.gid}")
            return PageRelaySession.BatchResult(emptyList())
        }

        val source = peerStore.findById(plan.suggestedSourceId)
        if (source == null || source.host.isNullOrEmpty()) {
            SnapshotLogger.w(
                "CrossDeviceFetcher",
                "Source peer unavailable: ${plan.suggestedSourceId}",
            )
            return PageRelaySession.BatchResult(emptyList())
        }

        val session = PageRelaySession(appContext, source, plan.gid, plan.missingPages)
        return try {
            SnapshotLogger.i(
                "CrossDeviceFetcher",
                "Cross-device fetch: gid=${plan.gid}, pages=${plan.missingPages.size}, " +
                    "source=${source.deviceName}",
            )
            val result = session.execute()
            mainNotifyCompleted(plan, result)
            result
        } finally {
            session.shutdown()
        }
    }

    /**
     * 直接触发：选最优 peer 并按指定页号列表拉取
     */
    fun fetchPages(gid: Long, pages: List<Int>): PageRelaySession.BatchResult {
        if (pages.isEmpty()) return PageRelaySession.BatchResult(emptyList())
        for (p in peerStore.listOnline()) {
            if (!p.host.isNullOrEmpty() && p.port > 0) {
                val s = PageRelaySession(appContext, p, gid, pages)
                return try {
                    s.execute()
                } finally {
                    s.shutdown()
                }
            }
        }
        return PageRelaySession.BatchResult(emptyList())
    }

    /**
     * 远程 API 触发
     */
    fun triggerRemoteFetch(gid: Long, page: Int, fromPeer: String?): Boolean {
        val port = TransferPortHelper.getPort(appContext)
        val url = "http://127.0.0.1:$port/api/v1/galleries/$gid/pages/$page/lab/fetch"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 10_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val body = StringBuilder("{\"mode\":\"upload_back\",\"pages\":[").append(page).append("]")
            if (fromPeer != null) body.append(",\"fromPeer\":\"").append(fromPeer).append("\"")
            body.append("}")
            val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bodyBytes.size)
            conn.outputStream.use { os: OutputStream -> os.write(bodyBytes) }
            val code = conn.responseCode
            if (code != 200) {
                SnapshotLogger.d(
                    "CrossDeviceFetcher", "triggerRemoteFetch: HTTP $code")
                return false
            }
            conn.inputStream.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                    SnapshotLogger.d(
                        "CrossDeviceFetcher", "triggerRemoteFetch ok: ${reader.readText()}")
                }
            }
            true
        } catch (e: IOException) {
            SnapshotLogger.w(
                "CrossDeviceFetcher", "triggerRemoteFetch failed: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun activeCount(): Int = activeCount.get()

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun mainNotifyCompleted(plan: ResumePlanCalculator.Plan,
                                     result: PageRelaySession.BatchResult) {
        Handler(Looper.getMainLooper()).post {
            for (l in listeners) l.onFetchCompleted(plan, result)
        }
    }

    fun interface Listener {
        fun onFetchCompleted(plan: ResumePlanCalculator.Plan,
                              result: PageRelaySession.BatchResult)
    }

    companion object {
        @Volatile private var INSTANCE: CrossDeviceFetcher? = null

        @JvmStatic
        fun resetForTest() {
            synchronized(CrossDeviceFetcher::class.java) { INSTANCE = null }
        }

        @JvmStatic
        fun getInstance(context: Context): CrossDeviceFetcher {
            return INSTANCE ?: synchronized(CrossDeviceFetcher::class.java) {
                INSTANCE ?: CrossDeviceFetcher(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}