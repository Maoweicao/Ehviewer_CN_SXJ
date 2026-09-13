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
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.lab.log.SnapshotLogger
import com.hippo.ehviewer.lab.snapshot.GallerySnapshotEntry
import com.hippo.ehviewer.lab.snapshot.SnapshotService
import com.hippo.ehviewer.transfer.api.BaseApiHandler
import com.hippo.ehviewer.transfer.api.RequestParser
import com.hippo.ehviewer.transfer.api.ResponseBuilder
import com.hippo.ehviewer.transfer.auth.AuthManager
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 实验室 DB 快照 API 处理器
 *
 * 协议对应：v3.0 §5.22。
 *
 * 端点：
 * <ul>
 *   <li>GET  /api/v1/lab/db/snapshot?since=...&includeThumb=...&limit=...&cursor=...</li>
 *   <li>POST /api/v1/lab/db/snapshot/ack</li>
 *   <li>GET  /api/v1/lab/db/thumbnail/{gid}</li>
 * </ul>
 */
class SnapshotApiHandler(
    context: Context,
    authManager: AuthManager,
) : BaseApiHandler(context, authManager) {

    private val labManager: LabManager = LabManager.getInstance(context)
    private val snapshotService: SnapshotService = SnapshotService(context)
    private val downloadManager: DownloadManager = EhApplication.getDownloadManager(context)

    /** 单调递增的快照版本（每次响应后 +1） */
    private val currentVersion = AtomicLong(0)

    override fun handleGet(session: fi.iki.elonen.NanoHTTPD.IHTTPSession, uri: String): fi.iki.elonen.NanoHTTPD.Response {
        logRequest("GET", uri, session)
        val path = uri.split("?")[0]

        if (path == "/api/v1/lab/db/snapshot") return handleGetSnapshot(session)
        if (path.startsWith("/api/v1/lab/db/thumbnail/")) {
            val tail = path.substring("/api/v1/lab/db/thumbnail/".length)
            val realGid = try {
                tail.toLong()
            } catch (e: NumberFormatException) {
                return ResponseBuilder.jsonError(
                    fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid gid")
            }
            return handleGetThumbnail(session, realGid)
        }
        return ResponseBuilder.notFound("Endpoint")
    }

    override fun handlePost(session: fi.iki.elonen.NanoHTTPD.IHTTPSession, uri: String): fi.iki.elonen.NanoHTTPD.Response {
        logRequest("POST", uri, session)
        if (uri == "/api/v1/lab/db/snapshot/ack") return handleAck(session)
        return ResponseBuilder.notFound("Endpoint")
    }

    private fun handleGetSnapshot(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        if (!isLabEnabled()) {
            return ResponseBuilder.forbidden("Lab is disabled")
        }
        if (!isSubEnabled("dbSnapshot")) {
            return ResponseBuilder.forbidden("DB snapshot sub-switch is off")
        }
        return try {
            val since = parseLongParam(session, "since", 0)
            val includeThumb = parseBoolParam(session, "includeThumb", true)
            val limit = parseLongParam(session, "limit", DEFAULT_LIMIT.toLong()).toInt()
                .coerceIn(1, 5000)

            val entries = buildSnapshotEntries(since, includeThumb, limit)

            val version = currentVersion.incrementAndGet()

            val resp = JSONObject()
            resp["success"] = true
            resp["version"] = version
            resp["deviceId"] = labManager.getSelfDeviceId()
            resp["deviceName"] = labManager.getSelfDeviceName()
            resp["deviceType"] = labManager.getSelfDeviceType()
            resp["snapshotTime"] = System.currentTimeMillis()
            resp["hasMore"] = entries.size >= limit

            val galleries = JSONArray()
            for (e in entries) galleries.add(e.toJson())
            resp["galleries"] = galleries

            // M2 阶段：removedGids / favorites 由客户端上报
            resp["removedGids"] = JSONArray()
            resp["favorites"] = JSONArray()

            ResponseBuilder.jsonSuccess(resp.toJSONString())
        } catch (e: Exception) {
            SnapshotLogger.e(TAG, "Failed to build snapshot", e)
            ResponseBuilder.internalError(e.message)
        }
    }

    private fun handleAck(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        return try {
            val body = RequestParser.readBody(session)
            val json = JSONObject.parseObject(body)
            val version = json?.getLongValue("version") ?: 0
            val deviceId = json?.getString("deviceId")
            val applied = json?.getIntValue("appliedCount") ?: 0

            SnapshotLogger.i(
                TAG, "Snapshot ack: version=$version, deviceId=$deviceId, appliedCount=$applied",
            )

            val resp = JSONObject()
            resp["success"] = true
            resp["message"] = "Snapshot ack recorded"
            ResponseBuilder.jsonSuccess(resp.toJSONString())
        } catch (e: Exception) {
            SnapshotLogger.e(TAG, "Failed to ack snapshot", e)
            ResponseBuilder.internalError(e.message)
        }
    }

    private fun handleGetThumbnail(
        session: fi.iki.elonen.NanoHTTPD.IHTTPSession,
        gid: Long,
    ): fi.iki.elonen.NanoHTTPD.Response {
        if (!isLabEnabled()) return ResponseBuilder.forbidden("Lab is disabled")
        return try {
            val data = snapshotService.readThumbnailBytes(gid) ?: return ResponseBuilder.notFound("Thumbnail")
            val response = fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(data),
                data.size.toLong(),
            )
            response.addHeader("Cache-Control", "max-age=3600")
            response
        } catch (e: Exception) {
            SnapshotLogger.e(TAG, "Failed to serve thumbnail for gid=$gid", e)
            ResponseBuilder.internalError(e.message)
        }
    }

    // ==================== 内部：快照构造 ====================

    private fun buildSnapshotEntries(
        @Suppress("UNUSED_PARAMETER") since: Long,
        includeThumb: Boolean,
        limit: Int,
    ): List<GallerySnapshotEntry> {
        val all: List<DownloadInfo> = downloadManager.getAllDownloadInfoList()
        val out = ArrayList<GallerySnapshotEntry>(minOf(all.size, limit))
        val now = System.currentTimeMillis()

        for (info in all) {
            if (info.gid <= 0) continue

            val b = GallerySnapshotEntry.Builder()
                .gid(info.gid)
                .token(info.token)
                .title(info.title)
                .titleJpn(info.titleJpn)
                .category(info.category)
                .posted(info.posted)
                .uploader(info.uploader)
                .rating(info.rating)
                .pages(if (info.pages > 0) info.pages else info.total)
                .downloadedPages(if (info.finished > 0) info.finished else 0)
                .fileCount(if (info.finished > 0) info.finished else 0)
                .state(info.state)
                .label(info.label)
                .sourceDeviceId(labManager.getSelfDeviceId())
                .sourceDeviceName(labManager.getSelfDeviceName())
                .lastUpdated(now)

            if (includeThumb) {
                snapshotService.readThumbnailBase64(info)?.let { b.thumbBase64(it) }
            }

            out.add(b.build())
            if (out.size >= limit) break
        }
        return out
    }

    // ==================== 工具方法 ====================

    private fun parseLongParam(session: fi.iki.elonen.NanoHTTPD.IHTTPSession, key: String, def: Long): Long {
        val v = RequestParser.getQueryParameter(session, key) ?: return def
        return try { v.toLong() } catch (e: NumberFormatException) { def }
    }

    private fun parseBoolParam(session: fi.iki.elonen.NanoHTTPD.IHTTPSession, key: String, def: Boolean): Boolean {
        val v = RequestParser.getQueryParameter(session, key) ?: return def
        return "true".equals(v, ignoreCase = true) || "1" == v
    }

    private fun isLabEnabled(): Boolean = labManager.isLabEnabled(null)

    private fun isSubEnabled(subKey: String): Boolean =
        labManager.configStore.get().isSubEnabled(subKey)

    companion object {
        private const val TAG = "SnapshotApiHandler"
        private const val DEFAULT_LIMIT = 500
    }
}