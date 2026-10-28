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
import com.hippo.ehviewer.lab.EcdhKeyAgreement
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.transfer.log.TransferLogger
import fi.iki.elonen.NanoHTTPD
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 设备间同步请求的配对签名（§5.30.7）。
 *
 * <p>同步请求不依赖传输服务的全局密码/Token，而是用配对阶段协商出的
 * `presharedKey` 做 HMAC-SHA256 签名：只有已配对设备能访问同步数据面，
 * 且不影响普通 Web 客户端的认证方式。
 *
 * <p>签名内容：`METHOD\n/path?query\ntimestampSeconds\nnonce`。
 * 时间窗 ±5 分钟，nonce 在窗口内去重，防止重放。
 */
object LabSyncAuth {

    private const val TAG = "LabSyncAuth"

    private const val HEADER_DEVICE = "x-lab-device-id"
    private const val HEADER_TIMESTAMP = "x-lab-timestamp"
    private const val HEADER_NONCE = "x-lab-nonce"
    private const val HEADER_SIGNATURE = "x-lab-signature"

    private const val MAX_SKEW_SECONDS = 300L
    private const val MAX_NONCES = 1024

    private val seenNonces = ConcurrentHashMap<String, Long>()

    /** 只有同步会话和画廊索引的数据面允许配对签名免 Token。 */
    @JvmStatic
    fun isSyncPath(path: String): Boolean {
        return path.startsWith("/api/v1/lab/sync") ||
                path.startsWith("/api/v1/sync/gallery-index")
    }

    /** 校验请求是否来自已配对设备。 */
    @JvmStatic
    fun verify(context: Context, session: NanoHTTPD.IHTTPSession): Boolean {
        return try {
            val headers = session.headers ?: return false
            val deviceId = headers[HEADER_DEVICE] ?: return false
            val timestamp = headers[HEADER_TIMESTAMP]?.toLongOrNull() ?: return false
            val nonce = headers[HEADER_NONCE] ?: return false
            val signature = headers[HEADER_SIGNATURE] ?: return false

            val now = System.currentTimeMillis() / 1000L
            if (kotlin.math.abs(now - timestamp) > MAX_SKEW_SECONDS) {
                TransferLogger.getInstance().w(TAG, "签名时间窗过期: device=$deviceId")
                return false
            }

            val peer = TrustedPeerStore.getInstance(context).findById(deviceId) ?: return false
            val key = peer.presharedKey ?: return false
            val method = session.method?.name ?: return false
            val payload = buildPayload(method, requestPath(session), timestamp, nonce)
            if (!EcdhKeyAgreement.verify(key, payload, signature)) {
                TransferLogger.getInstance().w(TAG, "签名校验失败: device=$deviceId")
                return false
            }

            acceptNonce("$deviceId:$nonce", now + MAX_SKEW_SECONDS)
        } catch (t: Throwable) {
            TransferLogger.getInstance().w(TAG, "签名校验异常: ${t.message}")
            false
        }
    }

    /** 为发往对端的请求生成签名头。 */
    @JvmStatic
    fun signHeaders(context: Context, peer: TrustedPeer, method: String, pathWithQuery: String): Map<String, String> {
        val key = peer.presharedKey ?: return emptyMap()
        val timestamp = System.currentTimeMillis() / 1000L
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val payload = buildPayload(method, pathWithQuery, timestamp, nonce)
        val signature = EcdhKeyAgreement.sign(key, payload)
        return linkedMapOf(
            HEADER_DEVICE to com.hippo.ehviewer.lab.LabManager.getInstance(context).getSelfDeviceId(),
            HEADER_TIMESTAMP to timestamp.toString(),
            HEADER_NONCE to nonce,
            HEADER_SIGNATURE to signature,
        )
    }

    /** 服务端看到的完整路径（含查询串）。 */
    private fun requestPath(session: NanoHTTPD.IHTTPSession): String {
        val uri = session.uri ?: return "/"
        if (uri.contains('?')) return uri
        val query = session.queryParameterString
        return if (query.isNullOrEmpty()) uri else "$uri?$query"
    }

    private fun buildPayload(method: String, path: String, timestamp: Long, nonce: String): String {
        return "$method\n$path\n$timestamp\n$nonce"
    }

    private fun acceptNonce(key: String, expiresAt: Long): Boolean {
        val now = System.currentTimeMillis() / 1000L
        if (seenNonces.size > MAX_NONCES) {
            seenNonces.entries.removeIf { it.value < now }
        }
        val previous = seenNonces.putIfAbsent(key, expiresAt)
        return previous == null || previous < now
    }
}
