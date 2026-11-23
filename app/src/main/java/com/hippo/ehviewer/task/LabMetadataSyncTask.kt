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
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import com.hippo.ehviewer.lab.sync.LabBackfillStore
import com.hippo.ehviewer.lab.sync.LabSyncDirection
import com.hippo.ehviewer.lab.sync.LabSyncEngine
import com.hippo.ehviewer.lab.sync.LabTasks
import com.hippo.ehviewer.lab.sync.LocalFileReconciler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 多机联动元数据同步任务。
 *
 * 手动同步完成后继续做一次本地文件完整性检查，把「列表显示已完成但本地没图」
 * 和「本地有图却显示待下载」两类状态纠正过来；自动同步只在确实拉到变更时检查。
 * 勾选同步图片时，再把本次涉及、本地缺图以及上次未完成的画廊交给
 * [LabImageBackfillTask] 在后台补齐。
 */
class LabMetadataSyncTask(
    context: Context,
    private val peerDeviceId: String = "",
    private val directionOverride: LabSyncDirection? = null,
    private val syncFiles: Boolean = false,
    private val checkLocal: Boolean = true,
) : LabBackgroundTask(context) {

    companion object {
        @JvmStatic
        fun restore(context: Context, persistData: String?): LabMetadataSyncTask? {
            val json = try {
                persistData?.let { JSONObject.parseObject(it) }
            } catch (t: Throwable) {
                null
            } ?: return null
            return LabMetadataSyncTask(
                context,
                json.getString("peerDeviceId").orEmpty(),
                LabSyncDirection.fromWire(json.getString("direction")),
                json.getBooleanValue("syncFiles"),
                !json.containsKey("checkLocal") || json.getBooleanValue("checkLocal"),
            )
        }

        private fun usable(peer: TrustedPeer): Boolean =
            !peer.presharedKey.isNullOrEmpty() && !peer.host.isNullOrEmpty() && peer.port > 0
    }

    override fun getTaskId(): String =
        if (peerDeviceId.isEmpty()) {
            LabTasks.ID_SYNC
        } else {
            "${LabTasks.ID_SYNC}:$peerDeviceId"
        }

    override fun getTaskName(): String = context.getString(R.string.lab_task_sync_name)

    override fun getDisplayNameResId(): Int = R.string.lab_task_sync_name

    override fun getTaskDescription(): String {
        val name = if (peerDeviceId.isEmpty()) {
            null
        } else {
            TrustedPeerStore.getInstance(context).findById(peerDeviceId)?.deviceName
        }
        return if (name != null) {
            context.getString(R.string.lab_task_sync_desc_peer, name)
        } else {
            context.getString(R.string.lab_task_sync_desc_all)
        }
    }

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.SYNC

    override fun getTaskPersistData(): String = JSONObject().apply {
        put("peerDeviceId", peerDeviceId)
        put("direction", (directionOverride ?: resolvedDirection()).wire)
        put("syncFiles", syncFiles)
        put("checkLocal", checkLocal)
    }.toJSONString()

    private fun resolvedDirection(): LabSyncDirection =
        LabSyncDirection.fromWire(Settings.getLabSyncDirection())
            ?: LabSyncDirection.BIDIRECTIONAL

    override suspend fun execute(): Result<Unit> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val direction = directionOverride ?: resolvedDirection()
            val store = TrustedPeerStore.getInstance(context)
            val peers = if (peerDeviceId.isEmpty()) {
                store.list().filter { usable(it) }
            } else {
                listOfNotNull(store.findById(peerDeviceId)?.takeIf { usable(it) })
            }
            if (peers.isEmpty()) {
                val message = context.getString(R.string.lab_task_sync_no_peer)
                postFinished(false, message)
                return@withContext Result.failure(IllegalStateException(message))
            }

            val engine = LabSyncEngine.getInstance(context)
            var applied = 0
            var succeeded = 0
            val touched = LinkedHashSet<Long>()
            val errors = ArrayList<String>()

            peers.forEachIndexed { index, peer ->
                postProgress("peer", index, peers.size, peer.deviceName)
                postLog("开始同步 ${peer.deviceName} (${peer.host}:${peer.port})")
                val outcome = engine.requestSync(peer, direction) { phase, current, total, detail ->
                    postProgress(phase, current, total, detail)
                }
                if (outcome.success) {
                    succeeded++
                    applied += outcome.applied
                    touched.addAll(outcome.touchedGids)
                    postLog("${peer.deviceName}: 应用 ${outcome.applied} 条，涉及 ${outcome.touchedGids.size} 个画廊")
                } else {
                    errors.add("${peer.deviceName}: ${outcome.error.orEmpty()}")
                    postLog("${peer.deviceName} 同步失败: ${outcome.error.orEmpty()}")
                }
            }

            if (succeeded == 0) {
                val message = context.getString(
                    R.string.lab_task_sync_failed,
                    errors.firstOrNull().orEmpty(),
                )
                postFinished(false, message)
                return@withContext Result.failure(IllegalStateException(message))
            }

            // 手动同步总是检查；自动同步只有确实拉到变更时才检查，
            // 避免每 60 秒扫描一遍两万多个下载目录。
            val shouldCheck = checkLocal || applied > 0
            val check = if (shouldCheck) {
                postProgress("check", 0, 1, context.getString(R.string.lab_task_check_step))
                LocalFileReconciler.reconcile(context) { current, total ->
                    postProgress("check", current, total, context.getString(R.string.lab_task_check_step))
                }.also {
                    postLog("本地检查: 完整 ${it.complete}，缺图 ${it.missing}，更新 ${it.changed}")
                }
            } else {
                null
            }

            var backfillQueued = false
            if (syncFiles) {
                val all = LinkedHashSet<Long>()
                all.addAll(touched)
                check?.missingGids?.let { all.addAll(it) }
                all.addAll(LabBackfillStore.pending(context))
                if (all.isNotEmpty()) {
                    LabBackfillStore.add(context, all)
                    val targetPeer = if (peers.size == 1) peers[0].deviceId else ""
                    backfillQueued = LabTasks.startBackfill(context, targetPeer)
                    if (backfillQueued) {
                        postLog("已加入图片补齐任务: ${all.size} 个画廊")
                    }
                }
            }

            val completeCount = check?.complete ?: 0
            val missingCount = check?.missing ?: 0
            val base = if (check == null) {
                when {
                    direction == LabSyncDirection.PUSH ->
                        context.getString(R.string.lab_task_sync_pushed_short, succeeded)
                    applied > 0 -> context.getString(R.string.lab_sync_done, applied)
                    else -> context.getString(R.string.lab_sync_up_to_date)
                }
            } else {
                when {
                    direction == LabSyncDirection.PUSH -> context.getString(
                        R.string.lab_task_sync_pushed, succeeded, completeCount, missingCount)
                    applied > 0 -> context.getString(
                        R.string.lab_task_sync_done, applied, completeCount, missingCount)
                    else -> context.getString(
                        R.string.lab_task_sync_updated, completeCount, missingCount)
                }
            }
            val message = if (backfillQueued) {
                base + context.getString(R.string.lab_task_images_queued)
            } else {
                base
            }
            postLog("同步耗时 ${(System.currentTimeMillis() - startedAt) / 1000}s")
            postFinished(true, message)
            Result.success(Unit)
        } catch (t: Throwable) {
            val message = context.getString(
                R.string.lab_task_sync_failed,
                t.message ?: t.toString(),
            )
            postLog("同步异常: ${t.message}")
            postFinished(false, message)
            Result.failure(t)
        }
    }
}
