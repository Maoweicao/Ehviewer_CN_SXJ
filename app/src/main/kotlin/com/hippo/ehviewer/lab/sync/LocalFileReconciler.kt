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
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.lab.log.SnapshotLogger
import com.hippo.ehviewer.spider.GalleryIntegrityChecker
import com.hippo.ehviewer.transfer.core.DataChangeBus
import com.hippo.ehviewer.transfer.core.ResponseCache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 本地下载文件完整性检查。
 *
 * 同步只带来索引；这里用 [GalleryIntegrityChecker] 按下载目录里的真实图片
 * 重新计算每个画廊的已完成页数和状态，避免「列表显示已完成但本地没有图」
 * 或反过来「本地有图却一直显示待下载」。
 */
object LocalFileReconciler {

    class Result(
        val total: Int,
        val complete: Int,
        val missing: Int,
        val changed: Int,
        val missingGids: List<Long>,
    )

    private class Update(
        val gid: Long,
        val finished: Int,
        val total: Int,
        val state: Int,
    )

    @JvmStatic
    @JvmOverloads
    fun reconcile(
        context: Context,
        progress: ((current: Int, total: Int) -> Unit)? = null,
    ): Result {
        val appContext = context.applicationContext
        val downloadManager = EhApplication.getDownloadManager(appContext)
            ?: return Result(0, 0, 0, 0, emptyList())

        // 主线程取快照，避免后台遍历时和下载队列并发修改冲突。
        val latch = CountDownLatch(1)
        var snapshot: List<DownloadInfo> = emptyList()
        Handler(Looper.getMainLooper()).post {
            try {
                snapshot = ArrayList(downloadManager.getAllDownloadInfoList())
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            return Result(0, 0, 0, 0, emptyList())
        }

        var complete = 0
        var missing = 0
        val missingGids = ArrayList<Long>()
        val updates = ArrayList<Update>()

        for ((index, info) in snapshot.withIndex()) {
            if (isActive(info.state)) continue
            if (index % 200 == 0) {
                progress?.invoke(index, snapshot.size)
            }
            val expected = maxOf(
                info.pages,
                info.total,
                LabPageStore.get(appContext, info.gid),
            )
            val dirname = EhDB.getDownloadDirname(info.gid)
            val dir = if (dirname != null) {
                Settings.getDownloadLocation()?.subFile(dirname)
            } else {
                null
            }
            val integrity = GalleryIntegrityChecker.checkIn(dir, info, expected)
            val pages = integrity.expectedPages
            if (pages <= 0) continue

            val count = integrity.validPages
            val isComplete = integrity.isComplete
            val newState = when {
                isComplete -> DownloadInfo.STATE_FINISH
                info.state == DownloadInfo.STATE_FINISH -> DownloadInfo.STATE_NONE
                else -> info.state
            }
            if (isComplete) complete++ else {
                missing++
                missingGids.add(info.gid)
            }
            if (info.finished != count || info.total != pages || info.state != newState) {
                updates.add(Update(info.gid, count, pages, newState))
            }
        }

        progress?.invoke(snapshot.size, snapshot.size)

        var changed = 0
        if (updates.isNotEmpty()) {
            val applyLatch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    changed = applyUpdates(downloadManager, updates)
                } catch (t: Throwable) {
                    SnapshotLogger.w(TAG, "应用本地文件检查结果失败: ${t.message}", t)
                } finally {
                    applyLatch.countDown()
                }
            }
            if (!applyLatch.await(60, TimeUnit.SECONDS)) changed = 0
        }

        SnapshotLogger.i(
            TAG,
            "reconcile: total=${snapshot.size}, complete=$complete, missing=$missing, changed=$changed",
        )
        return Result(snapshot.size, complete, missing, changed, missingGids)
    }

    private fun isActive(state: Int): Boolean {
        return state == DownloadInfo.STATE_DOWNLOAD ||
                state == DownloadInfo.STATE_WAIT ||
                state == DownloadInfo.STATE_RELAY_DOWNLOAD ||
                state == DownloadInfo.STATE_UPDATE
    }

    private fun applyUpdates(
        downloadManager: com.hippo.ehviewer.download.DownloadManager,
        updates: List<Update>,
    ): Int {
        var changed = 0
        EhDB.runInTransaction {
            for (update in updates) {
                val info = downloadManager.getDownloadInfo(update.gid) ?: continue
                info.finished = update.finished
                info.total = update.total
                info.state = update.state
                EhDB.putDownloadInfo(info)
                changed++
            }
        }
        if (changed > 0) {
            downloadManager.notifySyncedExternal()
            ResponseCache.getInstance().invalidateGalleries()
            DataChangeBus.publish(DataChangeBus.TYPE_DATA_CHANGED, 0L, null, null)
        }
        return changed
    }

    private const val TAG = "LocalFileReconciler"
}
