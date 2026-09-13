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
import com.hippo.ehviewer.lab.TransferPortHelper
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.log.SnapshotLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 单页推送会话
 *
 * 协议对应：v3.0 §5.20 + §5.4.3（POST /api/v1/galleries/{gid}/pages/{page}/upload）。
 *
 * 流程：
 * <ol>
 *   <li>让源设备下载该页（调用源设备的 §5.4.2 端点））</li>
 *   <li>拿到字节后立即 push 给本机的 §5.4.3 upload 端点</li>
 *   <li>失败重试（最多 3 次）</li>
 * </ol>
 */
class PageRelaySession(
    context: Context,
    private val sourcePeer: TrustedPeer,
    private val gid: Long,
    private val pages: List<Int>,
) {

    private val appContext: Context = context.applicationContext

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LabPageRelay-$gid").apply { isDaemon = true }
    }

    /**
     * 单页推送结果
     */
    class PageResult(
        val page: Int,
        val size: Long,
        val ok: Boolean,
        val error: String?,
    ) {
        companion object {
            fun success(page: Int, size: Long) = PageResult(page, size, true, null)
            fun failure(page: Int, error: String?) = PageResult(page, 0, false, error)
        }
    }

    /**
     * 一批页（一个 Session）的整体结果
     */
    class BatchResult(val results: List<PageResult>) {
        fun succeededCount(): Int = results.count { it.ok }
        fun failedCount(): Int = results.size - succeededCount()
    }

    /**
     * 同步执行全部页面推送。
     */
    fun execute(): BatchResult {
        val results = ArrayList<PageResult>(pages.size)
        SnapshotLogger.i(
            "PageRelaySession",
            "execute: gid=$gid, pages=${pages.size}, source=${sourcePeer.deviceName}",
        )
        for (page in pages) {
            pushPage(page, results)
        }
        return BatchResult(results)
    }

    /**
     * 异步执行；调用方持有 Future 取消。
     */
    fun submit(): Future<BatchResult> = executor.submit(Callable { execute() })

    /**
     * 释放 executor 资源。
     */
    fun shutdown() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun pushPage(page: Int, results: MutableList<PageResult>) {
        for (attempt in 1..MAX_ATTEMPTS_PER_PAGE) {
            val data = fetchFromPeer(page)
            if (data != null) {
                if (pushToLocal(page, data)) {
                    results.add(PageResult.success(page, data.size.toLong()))
                    return
                }
                SnapshotLogger.w(
                    "PageRelaySession",
                    "upload failed for page $page (attempt $attempt/$MAX_ATTEMPTS_PER_PAGE)",
                )
            } else {
                SnapshotLogger.w(
                    "PageRelaySession",
                    "fetch failed for page $page (attempt $attempt/$MAX_ATTEMPTS_PER_PAGE)",
                )
            }
            try {
                Thread.sleep((BACKOFF_MS * attempt).toLong())
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                results.add(PageResult.failure(page, "interrupted"))
                return
            }
        }
        results.add(PageResult.failure(page, "max retries exceeded"))
    }

    // ==================== 网络层 ====================

    /**
     * 从源设备拉取指定页（调用源设备 §5.4.2 端点）
     */
    private fun fetchFromPeer(page: Int): ByteArray? {
        if (sourcePeer.host.isNullOrEmpty()) {
            SnapshotLogger.w("PageRelaySession", "Peer host missing for fetch")
            return null
        }
        val url = "http://${sourcePeer.host}:${sourcePeer.port}/api/v1/galleries/$gid/pages/$page?mode=local"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "image/*")
            }
            if (conn.responseCode != 200) {
                SnapshotLogger.d(
                    "PageRelaySession",
                    "Peer returned HTTP ${conn.responseCode} for gid=$gid, page=$page",
                )
                return null
            }
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            SnapshotLogger.d(
                "PageRelaySession", "fetchFromPeer($page) failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 推送到本机的 §5.4.3 单页上传端点
     */
    private fun pushToLocal(page: Int, data: ByteArray): Boolean {
        val ext = guessExtension(data)
        val boundary = "----LabPageRelay${System.currentTimeMillis()}"
        var conn: HttpURLConnection? = null
        return try {
            val body = buildMultipart(boundary, gid, page, ext, data)
            val url = "http://127.0.0.1:${TransferPortHelper.getPort(appContext)}" +
                "/api/v1/galleries/$gid/pages/$page/upload"
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            conn.outputStream.use { os: OutputStream -> os.write(body) }
            val code = conn.responseCode
            if (code == 200) return true
            SnapshotLogger.w(
                "PageRelaySession", "pushToLocal page $page failed with HTTP $code")
            false
        } catch (e: IOException) {
            SnapshotLogger.w(
                "PageRelaySession", "pushToLocal page $page error: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun guessExtension(data: ByteArray): String {
        if (data.size < 4) return "jpg"
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        return when {
            b0 == 0x89 && b1 == 0x50 -> "png"
            b0 == 0x47 && b1 == 0x49 -> "gif"
            b0 == 0x52 && b1 == 0x49 -> "webp"
            else -> "jpg"
        }
    }

    private fun buildMultipart(
        boundary: String,
        gid: Long,
        page: Int,
        ext: String,
        fileData: ByteArray,
    ): ByteArray {
        val sb = StringBuilder()
        // file part
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"page_")
            .append(page).append('.').append(ext).append("\"\r\n")
        sb.append("Content-Type: image/").append(ext).append("\r\n\r\n")
        // extension
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Disposition: form-data; name=\"extension\"\r\n\r\n")
        sb.append(ext).append("\r\n")
        // gid
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Disposition: form-data; name=\"gid\"\r\n\r\n")
        sb.append(gid).append("\r\n")
        val head = sb.toString().toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val body = ByteArray(head.size + fileData.size + tail.size)
        System.arraycopy(head, 0, body, 0, head.size)
        System.arraycopy(fileData, 0, body, head.size, fileData.size)
        System.arraycopy(tail, 0, body, head.size + fileData.size, tail.size)
        return body
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_ATTEMPTS_PER_PAGE = 3
        private const val BACKOFF_MS = 2_000
    }
}