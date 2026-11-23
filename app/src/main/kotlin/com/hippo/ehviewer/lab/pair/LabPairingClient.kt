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
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.lab.EcdhKeyAgreement
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.TransferPortHelper
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.transfer.log.TransferLogger
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 实验室设备配对客户端
 *
 * 复用服务端已有的 `/api/v1/lab/trusted-peers/pair`：
 * 扫描方生成本次 ECDH 密钥对并上报公钥，对方用配对码校验后返回预共享密钥，
 * 双方各自落库，之后同步/接力的 HMAC 签名才可用。
 *
 * 所有网络调用都在后台单线程执行，回调统一投递到主线程。
 */
class LabPairingClient private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val labManager = LabManager.getInstance(appContext)
    private val peerStore = TrustedPeerStore.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LabPairing").apply { isDaemon = true }
    }

    /**
     * 查询对端身份（不配对）：GET /api/v1/lab/topology，读取其中的 self。
     */
    fun discover(host: String, port: Int, callback: DiscoverCallback) {
        executor.execute {
            var device: RemoteDevice? = null
            var error: String? = null
            try {
                val (status, body) = httpGet(host, port, "/api/v1/lab/topology")
                if (status in 200..299) {
                    val self = JSON.parseObject(body)?.getJSONObject("self")
                    if (self != null) {
                        val id = self.getString("deviceId")
                        val name = self.getString("deviceName")
                        if (id.isNullOrEmpty() || name.isNullOrEmpty()) {
                            error = "响应缺少设备身份"
                        } else {
                            device = RemoteDevice(
                                deviceId = id,
                                deviceName = name,
                                deviceType = self.getString("deviceType") ?: "android",
                                host = host,
                                port = port,
                                capabilities = parseCapabilities(self.getJSONArray("capabilities")),
                            )
                        }
                    } else {
                        error = "响应缺少 self 字段"
                    }
                } else {
                    error = "HTTP $status"
                }
            } catch (e: Exception) {
                TransferLogger.getInstance().d(TAG, "discover($host:$port) failed: ${e.message}")
                error = e.message ?: "连接失败"
            }
            val resultDevice = device
            val resultError = error
            post { callback.onResult(resultDevice, resultError) }
        }
    }

    /**
     * 使用一次性配对码完成配对，成功后写入 [TrustedPeerStore]。
     */
    fun pair(host: String, port: Int, code: String, callback: PairCallback) {
        executor.execute {
            val result = try {
                doPair(host, port, code)
            } catch (e: Exception) {
                TransferLogger.getInstance().w(TAG, "pair($host:$port) failed: ${e.message}")
                PairResult.failure(PairError.UNREACHABLE, e.message ?: "连接失败")
            }
            post { callback.onResult(result) }
        }
    }

    private fun doPair(host: String, port: Int, code: String): PairResult {
        val keyPair = EcdhKeyAgreement.generateKeyPair()
        val myDevice = JSONObject().apply {
            put("deviceId", labManager.getSelfDeviceId())
            put("deviceName", labManager.getSelfDeviceName())
            put("deviceType", labManager.getSelfDeviceType())
            put("port", TransferPortHelper.getPort(appContext))
            put("pubkey", EcdhKeyAgreement.encodePublicKey(keyPair.public))
            val caps = JSONArray()
            labManager.getSelfCapabilities().forEach { caps.add(it) }
            put("capabilities", caps)
        }
        val payload = JSONObject().apply {
            put("pairingCode", code)
            put("myDevice", myDevice)
        }

        val (status, body) = httpPost(
            host, port, "/api/v1/lab/trusted-peers/pair", payload.toJSONString(),
        )
        val root = try {
            JSON.parseObject(body)
        } catch (e: Exception) {
            null
        }

        if (status !in 200..299) {
            val serverMessage = root?.getString("error")
            val error = when (status) {
                429 -> PairError.RATE_LIMITED
                403 -> PairError.REJECTED
                400 -> when {
                    serverMessage?.contains("self", ignoreCase = true) == true -> PairError.SELF
                    else -> PairError.INVALID_CODE
                }
                else -> PairError.SERVER_ERROR
            }
            return PairResult.failure(error, serverMessage ?: "HTTP $status")
        }

        if (root == null) {
            return PairResult.failure(PairError.BAD_RESPONSE, "无法解析响应")
        }
        val presharedKey = root.getString("presharedKey")
        if (presharedKey.isNullOrEmpty()) {
            return PairResult.failure(PairError.BAD_RESPONSE, "响应缺少 presharedKey")
        }
        // 兼容旧版对端：没有 self 时再查一次拓扑
        val self = root.getJSONObject("self") ?: discoverSelf(host, port)
        if (self == null) {
            return PairResult.failure(PairError.BAD_RESPONSE, "响应缺少 self 字段")
        }

        val remoteId = self.getString("deviceId")
        val remoteName = self.getString("deviceName")
        if (remoteId.isNullOrEmpty() || remoteName.isNullOrEmpty()) {
            return PairResult.failure(PairError.BAD_RESPONSE, "响应缺少对端身份")
        }
        if (remoteId == labManager.getSelfDeviceId()) {
            return PairResult.failure(PairError.SELF, "不能与本机配对")
        }

        val remotePort = self.getIntValue("port").let { if (it in 1..65535) it else port }
        val capabilities = parseCapabilities(self.getJSONArray("capabilities"))
        val existing = peerStore.findById(remoteId)
        val builder = TrustedPeer.Builder(
            remoteId,
            remoteName,
            self.getString("deviceType") ?: "android",
        )
            .host(host)
            .port(remotePort)
            .lastSeen(System.currentTimeMillis())
            .online(true)
            .capabilities(capabilities)
            .presharedKey(presharedKey)
            .rttMs(existing?.rttMs ?: -1L)
            .role(existing?.role ?: "auto")
            .trustedAt(existing?.trustedAt ?: System.currentTimeMillis())

        val peer = builder.build()
        peerStore.upsert(peer)
        removeStaleUnpaired(peer, host, remotePort)
        TransferLogger.getInstance().i(
            TAG,
            "Paired with ${peer.deviceName} ($remoteId) at $host:$remotePort",
        )
        return PairResult.success(peer)
    }

    /**
     * 清理旧版「手动添加」留下的假记录：同地址、无密钥、deviceId 不同。
     * 这类记录来自随机 UUID，永远无法完成签名，只会造成重复条目。
     */
    private fun removeStaleUnpaired(keep: TrustedPeer, host: String, port: Int) {
        val stale = peerStore.list().filter {
            it.deviceId != keep.deviceId &&
                it.presharedKey.isNullOrEmpty() &&
                it.host == host &&
                (it.port == port || it.port <= 0)
        }
        for (record in stale) {
            peerStore.remove(record.deviceId)
            TransferLogger.getInstance().i(
                TAG,
                "Removed stale unpaired peer ${record.deviceName} (${record.deviceId}) at $host",
            )
        }
    }

    /**
     * 旧版对端在 `/pair` 响应里可能没有 `self`，回退查询拓扑。
     */
    private fun discoverSelf(host: String, port: Int): JSONObject? {
        return try {
            val (status, body) = httpGet(host, port, "/api/v1/lab/topology")
            if (status in 200..299) JSON.parseObject(body)?.getJSONObject("self") else null
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(host: String, port: Int, path: String): Pair<Int, String> {
        val conn = openConnection(host, port, path, "GET")
        return try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            status to body
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(host: String, port: Int, path: String, payload: String): Pair<Int, String> {
        val conn = openConnection(host, port, path, "POST")
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            status to body
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(host: String, port: Int, path: String, method: String): HttpURLConnection {
        val url = URL("http://$host:$port$path")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Device-ID", labManager.getSelfDeviceId())
            setRequestProperty("X-Device-Name", labManager.getSelfDeviceName())
        }
    }

    private fun parseCapabilities(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = ArrayList<String>()
        for (i in 0 until arr.size) {
            val value = arr.getString(i)
            if (!value.isNullOrEmpty() && TrustedPeer.ALL_CAPABILITIES.contains(value) && !list.contains(value)) {
                list.add(value)
            }
        }
        return list
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    /**
     * 对端身份（不含密钥）。
     */
    data class RemoteDevice(
        val deviceId: String,
        val deviceName: String,
        val deviceType: String,
        val host: String,
        val port: Int,
        val capabilities: List<String>,
    )

    enum class PairError {
        UNREACHABLE,
        INVALID_CODE,
        EXPIRED,
        REJECTED,
        RATE_LIMITED,
        BAD_RESPONSE,
        SERVER_ERROR,
        SELF,
    }

    /**
     * 配对结果。
     */
    data class PairResult(
        val success: Boolean,
        val peer: TrustedPeer?,
        val error: PairError?,
        val message: String,
    ) {
        companion object {
            @JvmStatic
            fun success(peer: TrustedPeer): PairResult =
                PairResult(true, peer, null, "")

            @JvmStatic
            fun failure(error: PairError, message: String): PairResult =
                PairResult(false, null, error, message)
        }
    }

    interface DiscoverCallback {
        fun onResult(device: RemoteDevice?, error: String?)
    }

    interface PairCallback {
        fun onResult(result: PairResult)
    }

    companion object {
        private const val TAG = "LabPairingClient"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 6_000

        @Volatile
        private var INSTANCE: LabPairingClient? = null

        /** 测试钩子：仅用于单元测试重置单例。 */
        @JvmStatic
        fun resetForTest() {
            synchronized(LabPairingClient::class.java) {
                INSTANCE?.executor?.shutdownNow()
                INSTANCE = null
            }
        }

        @JvmStatic
        fun getInstance(context: Context): LabPairingClient {
            return INSTANCE ?: synchronized(LabPairingClient::class.java) {
                INSTANCE ?: LabPairingClient(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
