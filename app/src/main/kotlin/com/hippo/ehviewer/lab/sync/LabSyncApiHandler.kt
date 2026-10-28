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
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.lab.log.SnapshotLogger
import com.hippo.ehviewer.transfer.api.BaseApiHandler
import com.hippo.ehviewer.transfer.api.RequestParser
import com.hippo.ehviewer.transfer.api.ResponseBuilder
import com.hippo.ehviewer.transfer.auth.AuthManager
import fi.iki.elonen.NanoHTTPD

/**
 * 设备间快速同步的会话端点（协议 §5.30）。
 *
 * <ul>
 *   <li>POST /api/v1/lab/sync/sessions              创建会话（方向 + 范围）</li>
 *   <li>GET  /api/v1/lab/sync/sessions/{id}         查询状态/进度</li>
 *   <li>POST /api/v1/lab/sync/sessions/{id}/close   关闭会话</li>
 * </ul>
 *
 * <p>数据面复用 §5.29（scopes / manifest / buckets）。请求由
 * [LabSyncAuth] 的配对签名放行，不需要传输服务密码。
 */
class LabSyncApiHandler(
    context: Context,
    authManager: AuthManager,
) : BaseApiHandler(context, authManager) {

    private val sessionStore = LabSyncSessionStore.getInstance()
    private val engine = LabSyncEngine.getInstance(context)
    private val peerStore = TrustedPeerStore.getInstance(context)
    private val configStore = LabConfigStore.getInstance(context)

    override fun handleGet(session: NanoHTTPD.IHTTPSession, uri: String): NanoHTTPD.Response {
        logRequest("GET", uri, session)
        val path = uri.split("?")[0]
        if (path.startsWith(SESSIONS_PATH + "/")) {
            val id = path.substring((SESSIONS_PATH + "/").length)
            return handleStatus(id)
        }
        return ResponseBuilder.notFound("Endpoint")
    }

    override fun handlePost(session: NanoHTTPD.IHTTPSession, uri: String): NanoHTTPD.Response {
        logRequest("POST", uri, session)
        val path = uri.split("?")[0]
        if (path == SESSIONS_PATH) {
            return handleCreate(session)
        }
        if (path.startsWith(SESSIONS_PATH + "/") && path.endsWith("/close")) {
            val id = path.substring((SESSIONS_PATH + "/").length, path.length - "/close".length)
            return handleClose(id)
        }
        return ResponseBuilder.notFound("Endpoint")
    }

    private fun handleCreate(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!isLabReady()) {
            return ResponseBuilder.forbidden("Lab sync is disabled")
        }
        return try {
            val body = JSONObject.parseObject(RequestParser.readBody(session))
                ?: return ResponseBuilder.jsonError(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid JSON body", 400)

            val deviceId = body.getString("deviceId")
                ?: session.headers["x-lab-device-id"]
                ?: return ResponseBuilder.jsonError(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "deviceId is required", 400)
            val peer = peerStore.findById(deviceId)
                ?: return ResponseBuilder.forbidden("Device is not paired")
            if (peer.presharedKey.isNullOrEmpty()) {
                return ResponseBuilder.forbidden("Device has no pairing key")
            }

            val direction = LabSyncDirection.fromWire(body.getString("direction"))
                ?: return ResponseBuilder.jsonError(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Invalid direction", 400)
            val scopes = mutableListOf("downloads", "favorites")
            body.getJSONArray("scopes")?.let { array ->
                val requested = ArrayList<String>(array.size)
                for (i in 0 until array.size) {
                    array.getString(i)?.takeIf { it == "downloads" || it == "favorites" }
                        ?.let { requested.add(it) }
                }
                if (requested.isNotEmpty()) {
                    scopes.clear()
                    scopes.addAll(requested)
                }
            }

            val syncSession = sessionStore.create(peer.deviceId, peer.deviceName, direction, scopes)
            SnapshotLogger.i(TAG, "创建同步会话: peer=${peer.deviceName}, direction=${direction.wire}, role=${syncSession.role}")

            if (direction != LabSyncDirection.PULL) {
                // 对端选择推送/双向：本机自动反向拉取，无需对端再点一次
                val remoteHost = session.remoteIpAddress
                val remotePort = body.getIntValue("sourcePort")
                Thread({
                    engine.pullFromPeer(
                        peer,
                        remoteHost,
                        remotePort,
                        syncSession.id,
                        null,
                    )
                }, "LabSyncReversePull").apply { isDaemon = true }.start()
            }

            val resp = sessionToJson(syncSession)
            ResponseBuilder.jsonSuccess(resp.toJSONString())
        } catch (t: Throwable) {
            SnapshotLogger.w(TAG, "创建同步会话失败: ${t.message}", t)
            ResponseBuilder.internalError(t.message)
        }
    }

    private fun handleStatus(id: String): NanoHTTPD.Response {
        val syncSession = sessionStore.find(id)
            ?: return ResponseBuilder.notFound("Session")
        return ResponseBuilder.jsonSuccess(sessionToJson(syncSession).toJSONString())
    }

    private fun handleClose(id: String): NanoHTTPD.Response {
        sessionStore.finish(id, "closed", "client closed")
        val resp = JSONObject()
        resp["success"] = true
        resp["sessionId"] = id
        resp["state"] = "closed"
        return ResponseBuilder.jsonSuccess(resp.toJSONString())
    }

    private fun sessionToJson(session: LabSyncSession): JSONObject {
        val json = JSONObject()
        json["success"] = true
        json["sessionId"] = session.id
        json["state"] = session.state
        json["direction"] = session.direction
        json["role"] = session.role
        json["applied"] = session.applied
        json["detail"] = session.detail
        json["createdAt"] = session.createdAt
        json["expiresAt"] = session.expiresAt
        json["finishedAt"] = session.finishedAt
        json["scopes"] = JSONArray().apply { addAll(session.scopes) }
        val peer = JSONObject()
        peer["deviceId"] = session.peerDeviceId
        peer["deviceName"] = session.peerName
        json["peer"] = peer
        return json
    }

    private fun isLabReady(): Boolean {
        val cfg = configStore.get()
        return cfg.isEnabled() && cfg.isSubEnabled("dbSnapshot")
    }

    companion object {
        private const val TAG = "LabSyncApi"
        private const val SESSIONS_PATH = "/api/v1/lab/sync/sessions"
    }
}
