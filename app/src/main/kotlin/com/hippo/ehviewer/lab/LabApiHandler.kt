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

package com.hippo.ehviewer.lab

import android.content.Context
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.transfer.api.BaseApiHandler
import com.hippo.ehviewer.transfer.api.RequestParser
import com.hippo.ehviewer.transfer.api.ResponseBuilder
import com.hippo.ehviewer.transfer.auth.AuthManager
import com.hippo.ehviewer.transfer.log.TransferLogger
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 实验室 / 多机联动 API 处理器
 *
 * 协议对应：v3.0 §5.19（5.19.1 信任设备 / 5.19.2 配置 / 5.19.3 拓扑与统计）。
 *
 * M1 阶段实现的端点：
 * <ul>
 *   <li>GET    /api/v1/lab/config</li>
 *   <li>PUT    /api/v1/lab/config</li>
 *   <li>GET    /api/v1/lab/topology</li>
 *   <li>GET    /api/v1/lab/stats</li>
 *   <li>GET    /api/v1/lab/trusted-peers</li>
 *   <li>POST   /api/v1/lab/trusted-peers</li>
 *   <li>POST   /api/v1/lab/trusted-peers/pair</li>
 *   <li>DELETE /api/v1/lab/trusted-peers/{id}</li>
 *   <li>GET    /api/v1/lab/trusted-peers/pair-info?code=...</li>
 * </ul>
 *
 * 后续 M2~M6 端点（/api/v1/lab/db/snapshot、跨设备增量等）将在各自 milestone 添加。
 */
class LabApiHandler(
    context: Context,
    authManager: AuthManager,
) : BaseApiHandler(context, authManager) {

    private val labManager: LabManager = LabManager.getInstance(context)
    private val configStore: LabConfigStore = labManager.configStore
    private val peerStore: TrustedPeerStore = labManager.peerStore
    private val pairingManager: PairingCodeManager = labManager.pairingManager

    private val random = SecureRandom()

    override fun handleGet(session: fi.iki.elonen.NanoHTTPD.IHTTPSession, uri: String): fi.iki.elonen.NanoHTTPD.Response {
        logRequest("GET", uri, session)

        if (uri == "/api/v1/lab/config") return handleGetConfig(session)
        if (uri == "/api/v1/lab/topology") return handleGetTopology(session)
        if (uri == "/api/v1/lab/stats") return handleGetStats(session)
        if (uri == "/api/v1/lab/trusted-peers") return handleListPeers(session)
        if (uri.startsWith("/api/v1/lab/trusted-peers/pair-info")) return handleProbePairing(session)

        return ResponseBuilder.notFound("Endpoint")
    }

    override fun handlePost(session: fi.iki.elonen.NanoHTTPD.IHTTPSession, uri: String): fi.iki.elonen.NanoHTTPD.Response {
        logRequest("POST", uri, session)

        if (uri == "/api/v1/lab/config") return ResponseBuilder.methodNotAllowed()
        if (uri == "/api/v1/lab/trusted-peers") return handleIssuePairing(session)
        if (uri == "/api/v1/lab/trusted-peers/pair") return handlePair(session)

        return ResponseBuilder.notFound("Endpoint")
    }

    override fun handleDelete(session: fi.iki.elonen.NanoHTTPD.IHTTPSession, uri: String): fi.iki.elonen.NanoHTTPD.Response {
        logRequest("DELETE", uri, session)

        if (uri.startsWith("/api/v1/lab/trusted-peers/")) {
            val id = uri.substring("/api/v1/lab/trusted-peers/".length)
            if (id.isEmpty() || id.contains("/")) {
                return ResponseBuilder.jsonError(
                    fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Invalid deviceId in path",
                )
            }
            return handleRemovePeer(session, id)
        }
        return ResponseBuilder.notFound("Endpoint")
    }

    // ----- §5.19.2 配置 -----

    private fun handleGetConfig(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val cfg = configStore.get()
        return ResponseBuilder.jsonSuccess(cfg.toJson().toJSONString())
    }

    private fun handleGetTopology(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        try {
            val self = JSONObject()
            self["deviceId"] = labManager.getSelfDeviceId()
            self["deviceName"] = labManager.getSelfDeviceName()
            self["deviceType"] = labManager.getSelfDeviceType()
            self["role"] = configStore.get().getRole()
            val caps = JSONArray()
            caps.addAll(labManager.getSelfCapabilities().toList())
            self["capabilities"] = caps

            val peers = JSONArray()
            for (p in peerStore.list()) {
                peers.add(peerToTopologyJson(p))
            }

            val resp = JSONObject()
            resp["self"] = self
            resp["peers"] = peers
            resp["virtualDeviceCount"] = 1 + peers.size
            resp["lastRefreshAt"] = System.currentTimeMillis()
            return ResponseBuilder.jsonSuccess(resp.toJSONString())
        } catch (e: Exception) {
            TransferLogger.getInstance().e(TAG, "Failed to get topology", e)
            return ResponseBuilder.internalError(e.message)
        }
    }

    private fun peerToTopologyJson(p: TrustedPeer): JSONObject {
        val obj = JSONObject()
        obj["deviceId"] = p.deviceId
        obj["deviceName"] = p.deviceName
        obj["deviceType"] = p.deviceType
        p.host?.let { obj["host"] = it }
        obj["port"] = p.port
        obj["online"] = p.online
        obj["rttMs"] = p.rttMs
        val caps = JSONArray()
        caps.addAll(p.capabilities)
        obj["capabilities"] = caps
        obj["role"] = p.role
        return obj
    }

    private fun handleGetStats(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        // M1 stub：M2 接入 DB 快照后填充真实统计
        val resp = JSONObject()
        resp["totalGalleries"] = 0
        resp["uniqueGalleries"] = 0
        resp["totalDownloadedPages"] = 0
        resp["uniqueDownloadedPages"] = 0
        val byDevice = JSONArray()
        val selfEntry = JSONObject()
        selfEntry["deviceId"] = labManager.getSelfDeviceId()
        selfEntry["galleries"] = 0
        selfEntry["pages"] = 0
        byDevice.add(selfEntry)
        for (p in peerStore.list()) {
            val e = JSONObject()
            e["deviceId"] = p.deviceId
            e["galleries"] = 0
            e["pages"] = 0
            byDevice.add(e)
        }
        resp["byDevice"] = byDevice
        resp["duplicates"] = 0
        resp["lastUpdated"] = System.currentTimeMillis()
        resp["note"] = "M1 stub; real stats available after M2 snapshot sync"
        return ResponseBuilder.jsonSuccess(resp.toJSONString())
    }

    // ----- §5.19.1 信任设备 -----

    private fun handleListPeers(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val resp = JSONObject()
        val peers = JSONArray()
        for (p in peerStore.list()) peers.add(p.toJson())
        resp["peers"] = peers
        resp["total"] = peers.size
        return ResponseBuilder.jsonSuccess(resp.toJSONString())
    }

    private fun handleIssuePairing(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        try {
            val body = RequestParser.readBody(session)
            // 静默忽略 client 上报的 name/type，统一使用本机身份
            val issue = pairingManager.issue(labManager.getSelfDeviceName())

            val resp = JSONObject()
            resp["success"] = true
            resp["pairingCode"] = issue.code
            resp["expiresAt"] = issue.expiresAt
            resp["ttlSeconds"] = issue.ttlSeconds
            resp["selfDeviceId"] = labManager.getSelfDeviceId()
            resp["selfDeviceName"] = labManager.getSelfDeviceName()
            resp["selfDeviceType"] = labManager.getSelfDeviceType()
            resp["selfPort"] = TransferPortHelper.getPort(context)
            resp["selfPubkeyHint"] = labManager.getSelfPubkeyHint()
            return ResponseBuilder.jsonSuccess(resp.toJSONString())
        } catch (e: Exception) {
            TransferLogger.getInstance().e(TAG, "Failed to issue pairing code", e)
            return ResponseBuilder.internalError(e.message)
        }
    }

    private fun handleProbePairing(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val code = RequestParser.getQueryParameter(session, "code")
        if (code.isNullOrEmpty()) {
            return ResponseBuilder.jsonError(
                fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                "Missing code parameter",
            )
        }
        val info = pairingManager.probe(code)
        val resp = JSONObject()
        resp["exists"] = info.exists
        if (info.exists) {
            resp["expiresAt"] = info.expiresAt
            resp["deviceNameHint"] = info.deviceNameHint
        }
        return ResponseBuilder.jsonSuccess(resp.toJSONString())
    }

    private fun handlePair(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        try {
            val body = RequestParser.readBody(session)
            val json = JSON.parseObject(body)
            if (json == null) {
                return ResponseBuilder.jsonError(
                    fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Empty request body",
                )
            }
            val code = json.getString("pairingCode")
            if (code.isNullOrEmpty()) {
                return ResponseBuilder.jsonError(
                    fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Missing pairingCode",
                )
            }
            val myDeviceJson = json.getJSONObject("myDevice")
            if (myDeviceJson == null) {
                return ResponseBuilder.jsonError(
                    fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Missing myDevice",
                )
            }
            val remoteId = myDeviceJson.getString("deviceId")
            val remoteName = myDeviceJson.getString("deviceName")
            val remoteType = myDeviceJson.getString("deviceType")
            val remotePort = myDeviceJson.getIntValue("port")
            val remotePubkey = myDeviceJson.getString("pubkey")

            if (remoteId.isNullOrEmpty() || remoteName.isNullOrEmpty() || remoteType.isNullOrEmpty()) {
                return ResponseBuilder.jsonError(
                    fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                    "myDevice must include deviceId, deviceName, deviceType",
                )
            }

            // 消费配对码（60s TTL + 一次性）
            val selfNameHint = pairingManager.consume(code)
            if (selfNameHint == null) {
                return ResponseBuilder.jsonError(
                    fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Invalid or expired pairing code",
                )
            }

            // 校验：不能跟自己配对
            if (remoteId == labManager.getSelfDeviceId()) {
                return ResponseBuilder.jsonError(
                    fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Cannot pair with self",
                )
            }

            // M6：ECDH 预共享密钥协商
            val selfPresharedKey: String = try {
                if (!remotePubkey.isNullOrEmpty()) {
                    val kp = EcdhKeyAgreement.generateKeyPair()
                    EcdhKeyAgreement.deriveSharedKey(kp, remotePubkey)
                } else {
                    generatePresharedKey()
                }
            } catch (e: Exception) {
                TransferLogger.getInstance().w(
                    TAG, "ECDH key agreement failed, fallback: ${e.message}")
                generatePresharedKey()
            }

            val peer = TrustedPeer.Builder(remoteId, remoteName, remoteType)
                .port(remotePort)
                .lastSeen(System.currentTimeMillis())
                .online(true)
                .capabilities(parseCapabilities(myDeviceJson.getJSONArray("capabilities")))
                .role(LabConfig.ROLE_AUTO)
                .pubkeyHint(derivePubkeyHint(remotePubkey))
                .presharedKey(selfPresharedKey)
                .build()

            peerStore.upsert(peer)

            val resp = JSONObject()
            resp["success"] = true
            resp["message"] = "Pairing complete. Preshared key returned; store locally."
            resp["peer"] = peer.toJson()
            resp["presharedKey"] = selfPresharedKey
            return ResponseBuilder.jsonSuccess(resp.toJSONString())
        } catch (e: Exception) {
            TransferLogger.getInstance().e(TAG, "Failed to pair", e)
            return ResponseBuilder.internalError(e.message)
        }
    }

    private fun handleRemovePeer(session: fi.iki.elonen.NanoHTTPD.IHTTPSession, deviceId: String): fi.iki.elonen.NanoHTTPD.Response {
        val removed = peerStore.remove(deviceId)
        if (removed == null) {
            return ResponseBuilder.notFound("Device")
        }
        val resp = JSONObject()
        resp["success"] = true
        resp["message"] = "Peer removed"
        resp["deviceId"] = deviceId
        return ResponseBuilder.jsonSuccess(resp.toJSONString())
    }

    // ==================== 工具方法 ====================

    private fun parseCapabilities(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = ArrayList<String>()
        for (i in 0 until arr.size) {
            val s = arr.getString(i)
            if (!s.isNullOrEmpty()) list.add(s)
        }
        return list
    }

    private fun derivePubkeyHint(pubkey: String?): String? {
        if (pubkey.isNullOrEmpty()) return null
        val len = minOf(16, pubkey.length)
        val prefix = pubkey.substring(0, len).replace(Regex("[^a-zA-Z0-9:/+=\\-]"), "")
        return prefix.ifEmpty { null }
    }

    private fun generatePresharedKey(): String {
        val buf = ByteArray(32)
        random.nextBytes(buf)
        return android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP)
    }

    // 抑制未使用警告（保留扩展点）
    @Suppress("unused")
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    @Suppress("unused")
    private fun formatDate(timestamp: Long): String = dateFormat.format(Date(timestamp))

    companion object {
        private const val TAG = "LabApiHandler"
    }
}