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
import android.os.Handler
import android.os.Looper
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.lab.sync.LabSyncDirection
import com.hippo.ehviewer.lab.sync.LabSyncEngine
import com.hippo.ehviewer.task.impl.BaseBackgroundTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备间快速同步任务（协议 §5.30），供「自动化」以定时/事件方式调用。
 *
 * <p>方向读取实验室设置 `lab_sync_direction`；默认双向。只同步元数据，
 * 图片仍按需从对端读取。
 */
class LabSyncTask(context: Context) : BaseBackgroundTask(context), ParamAwareTask {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var directionOverride: LabSyncDirection? = null

    override fun setAutomationParams(params: String?) {
        if (params.isNullOrEmpty()) return
        try {
            val json = com.alibaba.fastjson.JSONObject.parseObject(params)
            directionOverride = LabSyncDirection.fromWire(json?.getString("direction"))
        } catch (ignored: Throwable) {
        }
    }

    override fun getTaskId(): String = "lab_sync"

    override fun getTaskName(): String = context.getString(R.string.lab_sync_task_name)

    override fun getDisplayNameResId(): Int = R.string.lab_sync_task_name

    override fun getTaskDescription(): String = context.getString(R.string.lab_sync_task_summary)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.SYNC

    override suspend fun execute(): Result<Unit> = withContext(Dispatchers.IO) {
        updateState(TaskState.RUNNING)
        appendTaskLog("开始设备间同步")
        try {
            val direction = directionOverride
                ?: LabSyncDirection.fromWire(Settings.getLabSyncDirection())
                ?: LabSyncDirection.BIDIRECTIONAL
            val applied = LabSyncEngine.getInstance(context).syncAll(direction) { phase, current, total, detail ->
                mainHandler.post {
                    updateProgress(current, total, detail ?: phase)
                    appendTaskLog("$phase $current/$total ${detail.orEmpty()}")
                }
            }
            if (applied > 0) {
                mainHandler.post {
                    updateState(TaskState.COMPLETED)
                    appendTaskLog("同步完成: $applied 台设备")
                }
                Result.success(Unit)
            } else {
                val message = context.getString(R.string.lab_sync_no_peer)
                mainHandler.post {
                    updateState(TaskState.FAILED)
                    appendTaskLog(message)
                }
                Result.failure(IllegalStateException(message))
            }
        } catch (t: Throwable) {
            mainHandler.post {
                updateState(TaskState.FAILED)
                appendTaskLog("同步失败: ${t.message}")
            }
            Result.failure(t)
        }
    }
}
