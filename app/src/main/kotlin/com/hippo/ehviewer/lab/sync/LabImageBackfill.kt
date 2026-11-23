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
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.log.SnapshotLogger
import com.hippo.ehviewer.lab.relay.PageRelaySession
import com.hippo.ehviewer.spider.GalleryIntegrityChecker

/**
 * 同步后从对端补齐图片（可选，默认关闭）。
 *
 * 同步本身只写下载列表元数据；当用户在同步对话框勾选「同时同步图片文件」时，
 * 对本次同步涉及的画廊逐页检查本地文件，缺失页通过 [PageRelaySession]
 * 从对端拉取并写入本地下载目录。
 *
 * 注意：数据量可能很大，必须在后台线程执行，并允许用户中途停止。
 */
object LabImageBackfill {

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    fun interface Progress {
        fun onGallery(title: String, index: Int, total: Int)
    }

    class Result(
        val downloaded: Int,
        val failed: Int,
        val galleries: Int,
    )

    @JvmStatic
    fun isRunning(): Boolean = running.get()

    /** 请求停止当前（或下一次）图片补齐。 */
    @JvmStatic
    fun requestStop() {
        stopRequested.set(true)
    }

    @JvmStatic
    fun clearStop() {
        stopRequested.set(false)
    }

    @JvmStatic
    fun isStopRequested(): Boolean = stopRequested.get()

    fun backfill(
        context: Context,
        peer: TrustedPeer,
        gids: List<Long>,
        progress: Progress?,
        stop: () -> Boolean,
    ): Result {
        if (gids.isEmpty()) return Result(0, 0, 0)
        if (!running.compareAndSet(false, true)) return Result(0, 0, 0)
        try {
            return backfillInternal(context, peer, gids, progress, stop)
        } finally {
            running.set(false)
        }
    }

    private fun backfillInternal(
        context: Context,
        peer: TrustedPeer,
        gids: List<Long>,
        progress: Progress?,
        stop: () -> Boolean,
    ): Result {
        val appContext = context.applicationContext
        val downloadManager = EhApplication.getDownloadManager(appContext)
            ?: return Result(0, 0, 0)
        var downloaded = 0
        var failed = 0
        var galleries = 0
        LabBackfillStore.add(appContext, gids)

        for ((index, gid) in gids.withIndex()) {
            if (stop()) break
            val info = downloadManager.getDownloadInfo(gid)
            if (info == null) {
                LabBackfillStore.remove(appContext, gid)
                continue
            }
            val expected = maxOf(
                info.pages,
                info.total,
                LabPageStore.get(appContext, gid),
            )
            val dirname = EhDB.getDownloadDirname(gid)
            val dir = if (dirname != null) {
                Settings.getDownloadLocation()?.subFile(dirname)
            } else {
                null
            }
            val integrity = GalleryIntegrityChecker.checkIn(dir, info, expected)
            val pages = integrity.expectedPages
            if (pages <= 0) {
                LabBackfillStore.remove(appContext, gid)
                continue
            }
            val missing = integrity.invalidIndices.map { it + 1 }
            if (missing.isEmpty()) {
                if (markFinishedIfComplete(appContext, downloadManager, gid, pages)) {
                    LabBackfillStore.remove(appContext, gid)
                }
                continue
            }
            if (!peerHasGallery(peer, gid)) {
                SnapshotLogger.d(
                    "LabImageBackfill", "peer ${peer.deviceName} has no gid=$gid; skip")
                continue
            }

            galleries++
            progress?.onGallery(info.title ?: gid.toString(), index, gids.size)
            val session = PageRelaySession(appContext, peer, gid, missing)
            val result = try {
                session.execute()
            } finally {
                session.shutdown()
            }
            downloaded += result.succeededCount()
            failed += result.failedCount()
            SnapshotLogger.i(
                "LabImageBackfill",
                "gid=$gid pages=${missing.size} ok=${result.succeededCount()} fail=${result.failedCount()}",
            )
            if (markFinishedIfComplete(appContext, downloadManager, gid, pages)) {
                LabBackfillStore.remove(appContext, gid)
            }
            downloadManager.notifySyncedExternal()
        }

        return Result(downloaded, failed, galleries)
    }

    /**
     * 先探测对端是否有该画廊的第 1 页，避免对没有图片的画廊逐页重试。
     */
    private fun peerHasGallery(peer: TrustedPeer, gid: Long): Boolean {
        val host = peer.host ?: return false
        if (host.isEmpty() || peer.port <= 0) return false
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL(
                "http://$host:${peer.port}/api/v1/galleries/$gid/pages/1?mode=local")
            conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3_000
                readTimeout = 3_000
                useCaches = false
            }
            val code = conn.responseCode
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.close()
            code == 200
        } catch (t: Throwable) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun markFinishedIfComplete(
        context: Context,
        downloadManager: com.hippo.ehviewer.download.DownloadManager,
        gid: Long,
        expectedPages: Int,
    ): Boolean {
        val updated = downloadManager.getDownloadInfo(gid) ?: return false
        val expected = maxOf(
            expectedPages,
            updated.pages,
            updated.total,
            LabPageStore.get(context, gid),
        )
        val dirname = EhDB.getDownloadDirname(gid)
        val dir = if (dirname != null) {
            Settings.getDownloadLocation()?.subFile(dirname)
        } else {
            null
        }
        val integrity = GalleryIntegrityChecker.checkIn(dir, updated, expected)
        if (!integrity.isComplete) return false
        if (updated.state != DownloadInfo.STATE_FINISH ||
            updated.finished != integrity.expectedPages ||
            updated.total != integrity.expectedPages
        ) {
            updated.finished = integrity.expectedPages
            updated.total = integrity.expectedPages
            updated.state = DownloadInfo.STATE_FINISH
            EhDB.putDownloadInfo(updated)
        }
        return true
    }
}
