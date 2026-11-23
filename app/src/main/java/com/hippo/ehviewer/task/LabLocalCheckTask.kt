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
import com.hippo.ehviewer.R
import com.hippo.ehviewer.lab.sync.LabTasks
import com.hippo.ehviewer.lab.sync.LocalFileReconciler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地下载文件完整性检查任务。
 *
 * 按下载目录里的真实图片重新计算已完成页数和状态，并修正假完成/假待下载。
 */
class LabLocalCheckTask(context: Context) : LabBackgroundTask(context) {

    companion object {
        @JvmStatic
        fun restore(context: Context, persistData: String?): LabLocalCheckTask =
            LabLocalCheckTask(context)
    }

    override fun getTaskId(): String = LabTasks.ID_CHECK

    override fun getTaskName(): String = context.getString(R.string.lab_task_check_name)

    override fun getDisplayNameResId(): Int = R.string.lab_task_check_name

    override fun getTaskDescription(): String = context.getString(R.string.lab_task_check_desc)

    override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.SCAN

    override fun getTaskPersistData(): String = "{}"

    override suspend fun execute(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            postProgress("check", 0, 1, context.getString(R.string.lab_task_check_step))
            val result = LocalFileReconciler.reconcile(context) { current, total ->
                postProgress("check", current, total, context.getString(R.string.lab_task_check_step))
            }
            val message = if (result.total == 0) {
                context.getString(R.string.lab_task_check_empty)
            } else {
                context.getString(
                    R.string.lab_task_check_done,
                    result.total,
                    result.complete,
                    result.missing,
                    result.changed,
                )
            }
            postLog(message)
            postFinished(true, message)
            Result.success(Unit)
        } catch (t: Throwable) {
            val message = context.getString(
                R.string.lab_task_check_failed,
                t.message ?: t.toString(),
            )
            postLog("本地检查异常: ${t.message}")
            postFinished(false, message)
            Result.failure(t)
        }
    }
}
