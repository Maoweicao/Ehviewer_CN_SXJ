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
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.lab.log.SnapshotLogger
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

    /** @return 实际新增或发生变化的行数 */
    fun apply(context: Context, scope: String, rows: List<JSONObject>): Int {
        if (rows.isEmpty()) return 0
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return applyOnMain(context, scope, rows)
        }
        val latch = CountDownLatch(1)
        var applied = 0
        Handler(Looper.getMainLooper()).post {
            try {
                applied = applyOnMain(context, scope, rows)
            } catch (t: Throwable) {
                SnapshotLogger.w(TAG, "应用同步行失败: ${t.message}", t)
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(60, TimeUnit.SECONDS)) applied else 0
    }

    private fun applyOnMain(context: Context, scope: String, rows: List<JSONObject>): Int {
        val downloadManager = EhApplication.getDownloadManager(context)
        var applied = 0
        // 整批合并包进一次数据库事务：逐条 put 各自提交会做大量磁盘同步，
        // 500 行一批在主线程上足以触发 ANR。
        EhDB.runInTransaction {
            if (scope == "favorites") {
                for (row in rows) {
                    if (applyFavorite(row)) applied++
                }
            } else {
                for (row in rows) {
                    if (applyDownload(downloadManager, row)) applied++
                }
            }
        }
        if (applied > 0) {
            downloadManager.notifySyncedExternal()
            ResponseCache.getInstance().invalidateGalleries()
            DataChangeBus.publish(DataChangeBus.TYPE_DATA_CHANGED, 0L, null, null)
        }
        return applied
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
        val finished = json.getIntValue("finished")
        created.finished = finished
        created.downloaded = json.getIntValue("downloaded")
        created.total = json.getIntValue("total")
        created.pages = if (pages > 0) pages else created.pages
        created.fileSize = json.getLongValue("fileSize")
        created.time = json.getLongValue("time").takeIf { it > 0 } ?: System.currentTimeMillis()
        created.state = resolveLocalState(json, pages, finished)
        EhDB.putDownloadInfo(created)
        return true
    }

    private fun mergeDownload(local: DownloadInfo, json: JSONObject): Boolean {
        var changed = false
        val pages = json.getIntValue("pages")
        val finished = json.getIntValue("finished")
        val downloaded = json.getIntValue("downloaded")
        val total = json.getIntValue("total")
        val fileSize = json.getLongValue("fileSize")

        if (pages > local.pages) {
            local.pages = pages
            changed = true
        }
        if (finished > local.finished) {
            local.finished = finished
            changed = true
        }
        if (downloaded > local.downloaded) {
            local.downloaded = downloaded
            changed = true
        }
        if (total > local.total) {
            local.total = total
            changed = true
        }
        if (fileSize > local.fileSize) {
            local.fileSize = fileSize
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
        if (local.state != DownloadInfo.STATE_FINISH && pages > 0 && finished >= pages) {
            local.state = DownloadInfo.STATE_FINISH
            changed = true
        }
        if (changed) {
            EhDB.putDownloadInfo(local)
        }
        return changed
    }

    /** 远端状态映射：只有完成的才落为完成，其余落为未下载，避免误创建下载任务。 */
    private fun resolveLocalState(json: JSONObject, pages: Int, finished: Int): Int {
        val remoteState = json.getIntValue("state")
        return if (remoteState == DownloadInfo.STATE_FINISH && pages > 0 && finished >= pages) {
            DownloadInfo.STATE_FINISH
        } else {
            DownloadInfo.STATE_NONE
        }
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
