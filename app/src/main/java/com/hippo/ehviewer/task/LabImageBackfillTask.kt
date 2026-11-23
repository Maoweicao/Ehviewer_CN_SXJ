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

package com.hippo.ehviewer.task

import android.content.Context
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.R
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.lab.sync.LabBackfillStore
import com.hippo.ehviewer.lab.sync.LabImageBackfill
import com.hippo.ehviewer.lab.sync.LabTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从对端补齐缺失图片（可选，默认不启用）。
 *
 * pending 画廊保存在 [LabBackfillStore] 中，因此进程被杀或用户停止后，
 * 下次进入多机联动页会自动以本任务续传。所有进度和结果都会出现在「后台任务」。
 */
class LabImageBackfillTask(
    context: Context,
    private val peerDeviceId: String = "",
) : LabBackgroundTask(context) {

    @Volatile
    private var cancelRequested = false

    companion object {
        @JvmStatic
        fun restore(context: Context, persistData: String?): LabImageBackfillTask? {
            val json = try {
                persistData?.let { JSONObject.parseObject(it) }
            } catch (t: Throwable) {
                null
            } ?: return null
            return LabImageBackfillTask(context, json.getString("peerDeviceId").orEmpty())
        }

        private fun usable(peer: TrustedPeer): Boolean =
            !peer.presharedKey.isNullOrEmpty() && !peer.host.isNullOrEmpty() && peer.port > 0
    }

    fun requestStop() {
        cancelRequested = true
    }

    override fun getTaskId(): String = LabTasks.ID_BACKFILL

    override fun getTaskName(): String = context.getString(R.string.lab_task_images_name)

    override fun getDisplayNameResId(): Int = R.string.lab_task_images_name

    override fun getTaskDescription(): String {
        val pending = LabBackfillStore.pending(context).size
        return if (pending > 0) {
            context.getString(R.string.lab_task_images_desc, pending)
        } else {
            context.getString(R.string.lab_task_images_desc_empty)
        }
    }

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.TRANSFER

    override fun getTaskPersistData(): String = JSONObject().apply {
        put("peerDeviceId", peerDeviceId)
    }.toJSONString()

    override fun isPausable(): Boolean = true

    override suspend fun cancel() {
        cancelRequested = true
        LabImageBackfill.requestStop()
        notifyCancelled()
    }

    override suspend fun execute(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val gids = LabBackfillStore.pending(context)
            if (gids.isEmpty()) {
                val message = context.getString(R.string.lab_task_images_none)
                postFinished(true, message)
                return@withContext Result.success(Unit)
            }
            val peer = resolvePeer()
            if (peer == null) {
                val message = context.getString(R.string.lab_task_images_no_peer)
                postFinished(false, message)
                return@withContext Result.failure(IllegalStateException(message))
            }
            if (LabImageBackfill.isRunning()) {
                val message = context.getString(R.string.lab_task_images_busy)
                postFinished(false, message)
                return@withContext Result.failure(IllegalStateException(message))
            }

            postLog("开始图片补齐: ${gids.size} 个画廊，来源 ${peer.deviceName}")
            val result = LabImageBackfill.backfill(
                context,
                peer,
                gids,
                { title, index, total ->
                    postProgress("images", index, total, title)
                },
            ) {
                cancelRequested || LabImageBackfill.isStopRequested()
            }

            val stopped = cancelRequested || LabImageBackfill.isStopRequested()
            val remaining = LabBackfillStore.pending(context).size
            val message = if (stopped) {
                context.getString(R.string.lab_task_images_stopped, result.downloaded, remaining)
            } else {
                context.getString(
                    R.string.lab_task_images_done,
                    result.downloaded,
                    result.failed,
                    remaining,
                )
            }
            postLog(message)
            postFinished(true, message)
            Result.success(Unit)
        } catch (t: Throwable) {
            val message = context.getString(
                R.string.lab_task_images_failed,
                t.message ?: t.toString(),
            )
            postLog("图片补齐异常: ${t.message}")
            postFinished(false, message)
            Result.failure(t)
        }
    }

    private fun resolvePeer(): TrustedPeer? {
        val store = TrustedPeerStore.getInstance(context)
        if (peerDeviceId.isNotEmpty()) {
            return store.findById(peerDeviceId)?.takeIf { usable(it) }
        }
        val peers = store.list().filter { usable(it) }
        return peers.firstOrNull { store.isEffectivelyOnline(it) } ?: peers.firstOrNull()
    }
}
