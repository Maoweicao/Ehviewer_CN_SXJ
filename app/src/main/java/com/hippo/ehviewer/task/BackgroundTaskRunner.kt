package com.hippo.ehviewer.task

import android.os.Process
import android.util.Log
import kotlinx.coroutines.runBlocking

object BackgroundTaskRunner {

    private const val TAG = "BackgroundTaskRunner"

    /**
     * 以阻塞方式执行后台任务，并在执行期间提升线程优先级，
     * 确保合并、压缩等操作的执行速度不被系统降低。
     */
    @JvmStatic
    fun runBlockingExecute(task: BackgroundTask): Throwable? {
        val thread = Thread.currentThread()
        val originalJavaPriority = thread.priority
        var originalTidPriority = Process.THREAD_PRIORITY_DEFAULT
        var priorityElevated = false
        try {
            // Boost both Linux and Java thread priorities for CPU-bound tasks
            // (merge, compress, scan) to prevent HyperOS/system throttling
            originalTidPriority = Process.getThreadPriority(Process.myTid())
            if (originalTidPriority > Process.THREAD_PRIORITY_DEFAULT) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                priorityElevated = true
                Log.d(TAG, "Task ${task.getTaskId()} tid priority: $originalTidPriority -> THREAD_PRIORITY_DEFAULT")
            } else if (originalTidPriority == Process.THREAD_PRIORITY_DEFAULT) {
                // Already at default, but also set to more favorable to be safe on throttled devices
                Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
                priorityElevated = true
                Log.d(TAG, "Task ${task.getTaskId()} tid priority boosted: DEFAULT -> MORE_FAVORABLE")
            }

            // Also raise Java thread priority for extra safety on HyperOS
            if (thread.priority < Thread.NORM_PRIORITY + 2) {
                thread.priority = Thread.NORM_PRIORITY + 2
            }
        } catch (_: Exception) {
        }
        try {
            return runBlocking {
                try {
                    val result = task.execute()
                    result.exceptionOrNull()
                } catch (t: Throwable) {
                    t
                }
            }
        } finally {
            if (priorityElevated) {
                try {
                    Process.setThreadPriority(originalTidPriority)
                } catch (_: Exception) {
                }
            }
            thread.priority = originalJavaPriority
        }
    }
}
