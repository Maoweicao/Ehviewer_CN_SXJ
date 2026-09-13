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
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.lab.log.SnapshotLogger
import com.hippo.ehviewer.lab.union.GalleryUnionResolver
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 接力触发器（页粒度）
 *
 * 协议对应：v3.0 §5.20 + 整体设计文档的「接力触发」部分。
 *
 * 三种触发模式：
 * <ol>
 *   <li>[Mode.AUTO]：由 [AutoRelayJudge] 自动触发</li>
 *   <li>[Mode.MANUAL]：用户在下载详情页手动点「接力」按钮</li>
 *   <li>[Mode.FETCH]：跨设备增量补齐</li>
 * </ol>
 */
class RelayInvoker private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val peerStore: TrustedPeerStore = TrustedPeerStore.getInstance(context)
    private val labManager: LabManager = LabManager.getInstance(context)
    private val unionResolver: GalleryUnionResolver = GalleryUnionResolver.getInstance(context)

    val judge: AutoRelayJudge = AutoRelayJudge(context)

    private val activeSessions = CopyOnWriteArrayList<PageRelaySession>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    enum class Mode { AUTO, MANUAL, FETCH }

    enum class InvokeResult {
        /** 调度成功并启动 PageRelaySession */
        STARTED,
        /** 没有可信 peer / 实验室未启用 → 静默跳过 */
        SKIPPED,
        /** 重试次数已达上限 */
        MAX_RETRIES_REACHED,
        /** 没有可接力的页（已是 union 最新） */
        NO_PAGES,
    }

    fun addListener(l: Listener) {
        if (l != null && !listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun activeSessionCount(): Int = activeSessions.size

    /**
     * 触发接力。
     */
    fun invoke(info: DownloadInfo, mode: Mode): InvokeResult {
        if (mode == Mode.AUTO && !labManager.isLabEnabled("autoRelay")) {
            return InvokeResult.SKIPPED
        }
        if (info.gid <= 0) return InvokeResult.SKIPPED

        val bestPeer = pickBestPeer() ?: run {
            SnapshotLogger.d(
                "RelayInvoker", "No online peer; relay skipped for gid=${info.gid}")
            return InvokeResult.SKIPPED
        }

        val localDownloaded = if (info.finished > 0) info.finished else 0
        val missingPages = computeMissingPages(info.gid, localDownloaded)
        if (missingPages.isEmpty()) {
            SnapshotLogger.d(
                "RelayInvoker", "No pages to relay for gid=${info.gid}")
            return InvokeResult.NO_PAGES
        }

        if (mode == Mode.AUTO) judge.recordRelayInvoked(info.gid)

        val session = PageRelaySession(appContext, bestPeer, info.gid, missingPages)
        activeSessions.add(session)
        session.submit()
        SnapshotLogger.i(
            "RelayInvoker",
            "Relay invoked: gid=${info.gid}, mode=$mode, peer=${bestPeer.deviceName}, " +
                "pages=${missingPages.size}",
        )
        mainNotify(info.gid, bestPeer.deviceName, missingPages.size, mode)
        return InvokeResult.STARTED
    }

    /**
     * 跨设备增量补齐入口。
     */
    fun invokeFetch(gid: Long, localDownloaded: Int): InvokeResult {
        val missingPages = computeMissingPages(gid, localDownloaded)
        if (missingPages.isEmpty()) return InvokeResult.NO_PAGES
        val bestPeer = pickBestPeer() ?: return InvokeResult.SKIPPED
        val session = PageRelaySession(appContext, bestPeer, gid, missingPages)
        activeSessions.add(session)
        session.submit()
        SnapshotLogger.i(
            "RelayInvoker",
            "Relay fetch: gid=$gid, peer=${bestPeer.deviceName}, missingPages=${missingPages.size}",
        )
        mainNotify(gid, bestPeer.deviceName, missingPages.size, Mode.FETCH)
        return InvokeResult.STARTED
    }

    fun cancelAll() {
        for (s in activeSessions) s.shutdown()
        activeSessions.clear()
    }

    // ==================== 内部 ====================

    /**
     * 选择最优 peer：按 RTT 升序的第一个有 host/port 的在线 peer。
     */
    private fun pickBestPeer(): TrustedPeer? {
        for (p in peerStore.listOnline()) {
            if (!p.host.isNullOrEmpty() && p.port > 0) return p
        }
        return null
    }

    private fun computeMissingPages(gid: Long, localDownloaded: Int): List<Int> =
        unionResolver.computeLocalMissing(gid, localDownloaded)

    private fun mainNotify(gid: Long, peerName: String, pageCount: Int, mode: Mode) {
        Handler(Looper.getMainLooper()).post {
            for (l in listeners) l.onRelayInvoked(gid, peerName, pageCount, mode)
        }
    }

    /**
     * 注册 DownloadInfo 监听器。
     */
    fun registerDownloadListener() {
        val dm: DownloadManager = com.hippo.ehviewer.EhApplication.getDownloadManager(appContext)
            ?: return
        dm.addDownloadInfoListener(object : DownloadManager.DownloadInfoListener {
            override fun onUpdate(
                info: DownloadInfo,
                list: List<DownloadInfo>,
                waitList: java.util.LinkedList<DownloadInfo>,
            ) {
                handleUpdate(info)
            }

            override fun onUpdateAll() {}
            override fun onReload() {}
            override fun onAdd(
                info: DownloadInfo, list: List<DownloadInfo>, position: Int,
            ) {}
            override fun onRemove(
                info: DownloadInfo, list: List<DownloadInfo>, position: Int,
            ) {
                judge.clear(info.gid)
            }
            override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {}
            override fun onChange() {}
            override fun onRenameLabel(from: String, to: String) {}
            override fun onUpdateLabels() {}
        })
    }

    private fun handleUpdate(info: DownloadInfo) {
        if (info.state != DownloadInfo.STATE_DOWNLOAD && info.state != DownloadInfo.STATE_WAIT) {
            return
        }
        val verdict = judge.onDownloadInfoUpdated(info)
        if (verdict == AutoRelayJudge.Verdict.STALL) {
            invoke(info, Mode.AUTO)
        }
    }

    interface Listener {
        fun onRelayInvoked(gid: Long, peerName: String, pageCount: Int, mode: Mode)
    }

    companion object {
        @Volatile private var INSTANCE: RelayInvoker? = null

        @JvmStatic
        fun resetForTest() {
            synchronized(RelayInvoker::class.java) { INSTANCE = null }
        }

        @JvmStatic
        fun getInstance(context: Context): RelayInvoker {
            return INSTANCE ?: synchronized(RelayInvoker::class.java) {
                INSTANCE ?: RelayInvoker(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}