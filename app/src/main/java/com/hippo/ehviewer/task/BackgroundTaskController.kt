package com.hippo.ehviewer.task

import kotlinx.coroutines.runBlocking

/**
 * 后台任务控制执行器
 * 从 Java 侧以阻塞方式调用任务的暂停/恢复/取消 suspend 方法。
 *
 * 注意：BackgroundTask.pause()/resume()/cancel() 被标注为 @MainThread，
 * 但各任务实现均只是设置 volatile 标志并写入日志，不触碰 UI 状态，
 * 因此在后台线程调用是安全的（具体实现请保持这一约定）。
 */
object BackgroundTaskController {

    /**
     * 调用任务的 pause()。返回是否调用成功。
     * 任务不支持暂停时返回 false。
     */
    @JvmStatic
    fun runPause(task: BackgroundTask): Boolean {
        return runBlocking {
            try {
                task.pause()
                true
            } catch (_: UnsupportedOperationException) {
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 调用任务的 resume()。返回是否调用成功。
     */
    @JvmStatic
    fun runResume(task: BackgroundTask): Boolean {
        return runBlocking {
            try {
                task.resume()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 调用任务的 cancel()。返回是否调用成功。
     */
    @JvmStatic
    fun runCancel(task: BackgroundTask): Boolean {
        return runBlocking {
            try {
                task.cancel()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 判断任务是否支持暂停
     */
    @JvmStatic
    fun isPausable(task: BackgroundTask): Boolean {
        return task.isPausable()
    }
}
