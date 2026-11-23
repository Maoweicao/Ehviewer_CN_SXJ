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
import com.hippo.ehviewer.lab.sync.LabTasks
import com.hippo.ehviewer.task.impl.BaseBackgroundTask

/**
 * 多机联动后台任务基类。
 *
 * 统一处理：
 * - 三种联动任务共用互斥组，避免同时修改下载数据库和图片目录；
 * - 进度/日志/完成状态都通过主线程投递到后台任务状态管理器；
 * - 可选的 UI 回调，供同步对话框在任务运行时继续显示进度。
 */
abstract class LabBackgroundTask(context: Context) : BaseBackgroundTask(context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 任务进度回调，始终在主线程调用。 */
    @Volatile
    var progressCallback: ((phase: String, current: Int, total: Int, detail: String?) -> Unit)? = null

    /** 任务结束回调（成功或失败），始终在主线程调用。 */
    @Volatile
    var finishCallback: ((success: Boolean, message: String) -> Unit)? = null

    override fun getMutexGroup(): String? = LabTasks.MUTEX_GROUP

    override fun isPersistable(): Boolean = true

    protected fun postProgress(
        phase: String,
        current: Int,
        total: Int,
        detail: String?,
    ) {
        mainHandler.post {
            updateProgress(current, total, detail ?: phase)
            progressCallback?.invoke(phase, current, total, detail)
        }
    }

    protected fun postLog(message: String) {
        appendTaskLog(message)
    }

    protected fun postFinished(success: Boolean, message: String) {
        // 先同步写入日志和状态，再投递 UI 回调：任务 execute() 返回后
        // 状态管理器会立刻把任务移出活动列表，晚到的日志就挂不上任务记录了。
        appendTaskLog(message)
        updateState(if (success) TaskState.COMPLETED else TaskState.FAILED)
        mainHandler.post {
            finishCallback?.invoke(success, message)
        }
    }
}
