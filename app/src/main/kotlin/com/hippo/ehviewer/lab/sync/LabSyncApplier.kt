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
import android.os.Handler
import android.os.Looper
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.lab.log.SnapshotLogger
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.transfer.core.DataChangeBus
import com.hippo.ehviewer.transfer.core.ResponseCache
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 把对端 §5.29 桶行合并进本地数据库（§5.30.5）。
 *
 * <p>v1 采用安全的并集合并，不传播删除：
 * <ul>
 *   <li>本地没有的画廊：新增；</li>
 *   <li>两端都有：页数/已下载页/文件大小取较大值，进度只增不减；</li>
 *   <li>标题/token 等只在本地为空时补齐；标签保持本地设置；</li>
 *   <li>远端处于下载中/等待/接力状态时，本地落为"未下载"，避免创建空任务。</li>
 * </ul>
 *
 * <p>合并必须在主线程执行，以便与 DownloadManager 的内存列表保持一致。
 */
object LabSyncApplier {

    private const val TAG = "LabSyncApplier"

    /**
     * 同步应用结果：实际变化的行数 + 本次涉及的 gid（用于可选的图片补齐）。
     */
    class ApplyResult(
        val applied: Int,
        val gids: List<Long>,
    )

    /** @return 实际新增或发生变化的行数，以及涉及的 gid */
    fun apply(context: Context, scope: String, rows: List<JSONObject>): ApplyResult {
        if (rows.isEmpty()) return ApplyResult(0, emptyList())
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return applyOnMain(context, scope, rows)
        }
        val latch = CountDownLatch(1)
        var result = ApplyResult(0, emptyList())
        Handler(Looper.getMainLooper()).post {
            try {
                result = applyOnMain(context, scope, rows)
            } catch (t: Throwable) {
                SnapshotLogger.w(TAG, "应用同步行失败: ${t.message}", t)
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(60, TimeUnit.SECONDS)) result else ApplyResult(0, emptyList())
    }

    private fun applyOnMain(context: Context, scope: String, rows: List<JSONObject>): ApplyResult {
        val downloadManager = EhApplication.getDownloadManager(context)
        var applied = 0
        val touchedGids = LinkedHashSet<Long>()
        val pageMap = HashMap<Long, Int>()
        // 整批合并包进一次数据库事务：逐条 put 各自提交会做大量磁盘同步，
        // 500 行一批在主线程上足以触发 ANR。
        EhDB.runInTransaction {
            if (scope == "favorites") {
                for (row in rows) {
                    if (applyFavorite(row)) applied++
                }
            } else {
                for (row in rows) {
                    val gid = row.getLongValue("gid")
                    val pages = row.getIntValue("pages")
                    if (gid > 0 && pages > 0) pageMap[gid] = pages
                    if (gid > 0 && applyDownload(downloadManager, row)) {
                        applied++
                        touchedGids.add(gid)
                    }
                }
            }
        }
        if (pageMap.isNotEmpty()) {
            LabPageStore.putAll(context, pageMap)
        }
        if (applied > 0) {
            downloadManager.notifySyncedExternal()
            ResponseCache.getInstance().invalidateGalleries()
            DataChangeBus.publish(DataChangeBus.TYPE_DATA_CHANGED, 0L, null, null)
        }
        return ApplyResult(applied, touchedGids.toList())
    }

    // ==================== 下载列表 ====================

    private fun applyDownload(downloadManager: DownloadManager, json: JSONObject): Boolean {
        val gid = json.getLongValue("gid")
        if (gid <= 0) return false
        val label = json.getString("label")?.takeIf { it.isNotEmpty() && it != "默认" }
        ensureLabel(downloadManager, label)

        val local = downloadManager.getDownloadInfo(gid)
        if (local != null) {
            return mergeDownload(local, json)
        }

        val info = buildGalleryInfo(json)
        downloadManager.addDownloadInfo(info, label)
        val created = downloadManager.getDownloadInfo(gid) ?: return false
        val pages = json.getIntValue("pages")
        // 同步只带来索引，不带来图片文件：本地进度必须从 0 开始，
        // 不能把对端的 finished/downloaded 当成自己的，否则会显示已完成却没有图。
        created.finished = 0
        created.downloaded = 0
        created.total = if (pages > 0) pages else 0
        created.pages = if (pages > 0) pages else created.pages
        created.fileSize = 0
        created.time = json.getLongValue("time").takeIf { it > 0 } ?: System.currentTimeMillis()
        created.state = DownloadInfo.STATE_NONE
        EhDB.putDownloadInfo(created)
        return true
    }

    private fun mergeDownload(local: DownloadInfo, json: JSONObject): Boolean {
        var changed = false
        val pages = json.getIntValue("pages")

        if (pages > local.pages) {
            local.pages = pages
            // 对端有更多页：本地已完成的记录需要重新补齐新增页
            if (local.state == DownloadInfo.STATE_FINISH) {
                local.state = DownloadInfo.STATE_NONE
            }
            changed = true
        }
        if (local.total <= 0 && local.pages > 0) {
            local.total = local.pages
            changed = true
        }
        // 旧版本同步会把对端进度直接写成“已完成”，但本地没有文件。
        // 这里检测下载目录不存在/为空的情况并修正，避免列表显示完成却没有图。
        if (local.state == DownloadInfo.STATE_FINISH && local.pages > 0 && !hasLocalImage(local)) {
            local.finished = 0
            local.downloaded = 0
            local.fileSize = 0
            local.state = DownloadInfo.STATE_NONE
            changed = true
        }
        if (local.title.isNullOrEmpty()) {
            json.getString("title")?.takeIf { it.isNotEmpty() }?.let { local.title = it; changed = true }
        }
        if (local.titleJpn.isNullOrEmpty()) {
            json.getString("titleJpn")?.takeIf { it.isNotEmpty() }?.let { local.titleJpn = it; changed = true }
        }
        if (local.token.isNullOrEmpty()) {
            json.getString("token")?.takeIf { it.isNotEmpty() }?.let { local.token = it; changed = true }
        }
        if (local.simpleLanguage.isNullOrEmpty()) {
            json.getString("language")?.takeIf { it.isNotEmpty() }?.let { local.simpleLanguage = it; changed = true }
        }
        if (local.posted.isNullOrEmpty()) {
            json.getString("posted")?.takeIf { it.isNotEmpty() }?.let { local.posted = it; changed = true }
        }
        if (local.uploader.isNullOrEmpty()) {
            json.getString("uploader")?.takeIf { it.isNotEmpty() }?.let { local.uploader = it; changed = true }
        }
        if (changed) {
            EhDB.putDownloadInfo(local)
        }
        return changed
    }

    private fun hasLocalImage(info: DownloadInfo): Boolean {
        return try {
            val dirname = EhDB.getDownloadDirname(info.gid) ?: return false
            val dir = Settings.getDownloadLocation()?.subFile(dirname) ?: return false
            dir.isDirectory && SpiderDen.findImageFile(dir, 0) != null
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 修复旧版本同步留下的「已完成但没有本地图片」记录。
     * 可在后台线程调用；返回被重置的 gid，供可选的图片补齐使用。
     */
    fun repairMissingFiles(context: Context): List<Long> {
        val downloadManager = EhApplication.getDownloadManager(context) ?: return emptyList()
        // 在主线程取一份快照，避免后台遍历时与下载队列并发修改冲突。
        val latch = CountDownLatch(1)
        var snapshot: List<DownloadInfo> = emptyList()
        Handler(Looper.getMainLooper()).post {
            try {
                snapshot = ArrayList(downloadManager.getAllDownloadInfoList())
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) return emptyList()

        val candidates = ArrayList<Long>()
        for (info in snapshot) {
            if (info.state == DownloadInfo.STATE_FINISH && info.pages > 0 && !hasLocalImage(info)) {
                candidates.add(info.gid)
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val applyLatch = CountDownLatch(1)
        var changed: List<Long> = emptyList()
        Handler(Looper.getMainLooper()).post {
            try {
                changed = applyRepair(downloadManager, candidates)
            } catch (t: Throwable) {
                SnapshotLogger.w(TAG, "修复同步记录失败: ${t.message}", t)
            } finally {
                applyLatch.countDown()
            }
        }
        return if (applyLatch.await(60, TimeUnit.SECONDS)) changed else emptyList()
    }

    private fun applyRepair(
        downloadManager: DownloadManager,
        gids: List<Long>,
    ): List<Long> {
        val changed = ArrayList<Long>()
        EhDB.runInTransaction {
            for (gid in gids) {
                val info = downloadManager.getDownloadInfo(gid) ?: continue
                if (info.state != DownloadInfo.STATE_FINISH) continue
                info.finished = 0
                info.downloaded = 0
                info.fileSize = 0
                info.state = DownloadInfo.STATE_NONE
                EhDB.putDownloadInfo(info)
                changed.add(gid)
            }
        }
        if (changed.isNotEmpty()) {
            downloadManager.notifySyncedExternal()
            ResponseCache.getInstance().invalidateGalleries()
            DataChangeBus.publish(DataChangeBus.TYPE_DATA_CHANGED, 0L, null, null)
        }
        return changed
    }

    private fun ensureLabel(downloadManager: DownloadManager, label: String?) {
        if (label == null || downloadManager.containLabel(label)) return
        try {
            val raw = EhDB.addDownloadLabel(label)
            downloadManager.addDownloadLabel(Collections.singletonList(raw))
        } catch (t: Throwable) {
            SnapshotLogger.w(TAG, "创建标签失败: $label", t)
        }
    }

    // ==================== 收藏 ====================

    private fun applyFavorite(json: JSONObject): Boolean {
        val gid = json.getLongValue("gid")
        if (gid <= 0) return false
        if (EhDB.containLocalFavorites(gid)) return false
        try {
            EhDB.putLocalFavorite(buildGalleryInfo(json))
            return true
        } catch (t: Throwable) {
            SnapshotLogger.w(TAG, "写入收藏失败: gid=$gid", t)
            return false
        }
    }

    private fun buildGalleryInfo(json: JSONObject): GalleryInfo {
        val info = GalleryInfo()
        info.gid = json.getLongValue("gid")
        info.token = json.getString("token")
        info.title = json.getString("title")
        info.titleJpn = json.getString("titleJpn")
        info.category = json.getIntValue("categoryValue")
        info.posted = json.getString("posted")
        info.uploader = json.getString("uploader")
        info.rating = json.getFloatValue("rating")
        info.pages = json.getIntValue("pages")
        info.simpleLanguage = json.getString("language")
        json.getJSONArray("tags")?.let { tags ->
            val list = ArrayList<String>(tags.size)
            for (i in 0 until tags.size) {
                tags.getString(i)?.takeIf { it.isNotEmpty() }?.let { list.add(it) }
            }
            if (list.isNotEmpty()) {
                info.tgList = list
                info.simpleTags = list.toTypedArray()
            }
        }
        return info
    }
}
