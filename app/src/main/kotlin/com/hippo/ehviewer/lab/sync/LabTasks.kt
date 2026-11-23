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
import com.hippo.ehviewer.BackgroundTaskManager
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.task.LabBackgroundTask
import com.hippo.ehviewer.task.LabImageBackfillTask
import com.hippo.ehviewer.task.LabLocalCheckTask
import com.hippo.ehviewer.task.LabMetadataSyncTask
import com.hippo.ehviewer.ui.task.BackgroundTaskStatusManager
import java.util.function.BiConsumer

/**
 * 多机联动后台任务入口。
 *
 * 同步、图片补齐、本地文件检查都通过这里提交为正式后台任务，
 * 这样「后台任务」页面能看到进度、日志和结果，也能被暂停/取消或重启后恢复。
 * 三种任务共用同一个互斥组，避免同时写下载数据库和图片目录。
 */
object LabTasks {

    const val ID_SYNC = "lab_sync_metadata"
    const val ID_BACKFILL = "lab_sync_images"
    const val ID_CHECK = "lab_local_check"
    const val MUTEX_GROUP = "lab_sync_io"

    @JvmStatic
    fun isActive(taskId: String): Boolean {
        return try {
            BackgroundTaskStatusManager.getInstance().activeTasks.any { it.getTaskId() == taskId }
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun submit(task: LabBackgroundTask): Boolean {
        if (isActive(task.getTaskId())) return false
        return try {
            BackgroundTaskManager.getInstance().submitBackgroundTask(task)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 手动同步单个设备。
     */
    @JvmStatic
    fun syncPeer(
        context: Context,
        peer: TrustedPeer,
        direction: LabSyncDirection,
        syncFiles: Boolean,
        onFinished: BiConsumer<Boolean, String>? = null,
    ): Boolean {
        val task = LabMetadataSyncTask(context.applicationContext, peer.deviceId, direction, syncFiles)
        onFinished?.let { callback ->
            task.finishCallback = { success, message -> callback.accept(success, message) }
        }
        return submit(task)
    }

    /**
     * 自动同步全部已配对设备，只同步元数据。
     */
    @JvmStatic
    fun autoSync(context: Context, direction: LabSyncDirection): Boolean {
        return submit(
            LabMetadataSyncTask(context.applicationContext, "", direction, false, false)
        )
    }

    /**
     * 从对端补齐 pending 图片。peerDeviceId 为空时自动挑一台在线设备。
     */
    @JvmStatic
    fun startBackfill(context: Context, peerDeviceId: String): Boolean {
        // 新的补齐任务开始时清除上一次的停止请求。
        LabImageBackfill.clearStop()
        return submit(LabImageBackfillTask(context.applicationContext, peerDeviceId))
    }

    /**
     * 手动检查本地下载文件完整性。
     */
    @JvmStatic
    fun checkLocal(
        context: Context,
        onFinished: BiConsumer<Boolean, String>? = null,
    ): Boolean {
        val task = LabLocalCheckTask(context.applicationContext)
        onFinished?.let { callback ->
            task.finishCallback = { success, message -> callback.accept(success, message) }
        }
        return submit(task)
    }

    @JvmStatic
    fun stopBackfill() {
        LabImageBackfill.requestStop()
    }
}
