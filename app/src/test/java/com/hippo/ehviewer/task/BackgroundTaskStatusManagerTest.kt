/*
 * Copyright 2024 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.task

import android.content.Context
import com.hippo.ehviewer.ui.task.BackgroundTaskStatusManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 验证互斥等待队列与已完成移出语义。
 */
@Config(manifest = Config.NONE, sdk = [28])
@RunWith(RobolectricTestRunner::class)
class BackgroundTaskStatusManagerTest {

    private lateinit var manager: BackgroundTaskStatusManager

    @Before
    fun setUp() {
        val appContext: Context = RuntimeEnvironment.application
        BackgroundTaskStatusManager.initialize(appContext)
        manager = BackgroundTaskStatusManager.getInstance()
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        // 清理 singleton 与持久化文件，防止测试间相互污染
        val f: Field = BackgroundTaskStatusManager::class.java.getDeclaredField("sInstance")
        f.isAccessible = true
        f.set(null, null)
        val filesDir = RuntimeEnvironment.application.filesDir
        File(filesDir, "background_tasks.json").delete()
        File(filesDir, "background_tasks.json.tmp").delete()
    }

    private fun uniqueId(prefix: String): String = "$prefix-${System.nanoTime()}"

    private fun makeUniqueTask(id: String, name: String): BackgroundTask =
        StubUniqueTask(id, name)

    /** enqueueUniqueWaitingTask 应把任务加入 mActiveTasks 且 isQueued=true。 */
    @Test
    fun enqueueUniqueWaitingTask_addsToActiveAndQueue() {
        val taskId = uniqueId("t")
        val task = makeUniqueTask(taskId, "merge")
        val returnedId = manager.enqueueUniqueWaitingTask(
            BackgroundTaskStatusManager.PendingUniqueTask(task)
        )
        assertEquals(taskId, returnedId)
        val info = manager.getTaskInfo(taskId)
        assertNotNull(info)
        assertTrue(info!!.isQueued)
        assertNull(info.future)
        assertEquals(1, manager.uniqueWaitQueueSize)
    }

    /** markTaskCompleted 后应回调 UniqueWaitListener。 */
    @Test
    @Throws(Exception::class)
    fun markTaskCompleted_invokesUniqueWaitListener() {
        val calls = AtomicInteger(0)
        val latch = CountDownLatch(1)
        manager.setUniqueWaitListener(BackgroundTaskStatusManager.UniqueWaitListener {
            calls.incrementAndGet()
            latch.countDown()
        })
        val activeId = uniqueId("active")
        val waitingId = uniqueId("waiting")
        val future = FutureTask<Any?> { null }
        manager.addTask(activeId, "name", "desc", future,
            BackgroundTask.TaskType.MERGE, true)
        manager.enqueueUniqueWaitingTask(
            BackgroundTaskStatusManager.PendingUniqueTask(makeUniqueTask(waitingId, "merge2"))
        )
        manager.markTaskCompleted(activeId)
        assertTrue("listener should fire within 2s", latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        // 等待任务仍在 mActiveTasks 中（poll 由 listener 自行处理）
        val waitingInfo = manager.getTaskInfo(waitingId)
        assertNotNull(waitingInfo)
    }

    /** removeFromCompleted 只触碰 mCompletedTasks，活跃任务不被误删。 */
    @Test
    fun removeFromCompleted_doesNotTouchActive() {
        val activeId = uniqueId("a")
        val completedId = uniqueId("c")
        val future = FutureTask<Any?> { null }
        manager.addTask(activeId, "a", "d", future,
            BackgroundTask.TaskType.MERGE, true)
        manager.addTask(completedId, "c", "d", null,
            BackgroundTask.TaskType.OTHER, false)
        manager.markTaskCompleted(completedId)
        assertNotNull(manager.getTaskInfo(activeId))
        assertNotNull(manager.getTaskInfo(completedId))
        val removed = manager.removeFromCompleted(completedId)
        assertTrue(removed)
        assertNotNull("active task should not be removed", manager.getTaskInfo(activeId))
        assertNull(manager.getTaskInfo(completedId))
    }

    /** cancelTask 对排队中的 unique 任务只移出队列，不进入已完成。 */
    @Test
    fun cancelTask_forQueuedUnique_removesFromQueue() {
        val taskId = uniqueId("q")
        manager.enqueueUniqueWaitingTask(
            BackgroundTaskStatusManager.PendingUniqueTask(makeUniqueTask(taskId, "queued"))
        )
        val cancelled = manager.cancelTask(taskId)
        assertTrue(cancelled)
        assertNull("queued task should be gone from active", manager.getTaskInfo(taskId))
        assertEquals(0, manager.uniqueWaitQueueSize)
    }

    /** cancelTask 对运行中任务（Future 未跑完）会把任务从 active 移除。 */
    @Test
    fun cancelTask_forActiveTask_removesFromActive() {
        val taskId = uniqueId("r")
        val future = FutureTask<Any?> { null }
        manager.addTask(taskId, "running", "d", future,
            BackgroundTask.TaskType.MERGE, true)
        manager.cancelTask(taskId)
        assertNull("active task should be removed from active", manager.getTaskInfo(taskId))
    }

    /** enqueueUniqueWaitingTask 对已存在 taskId 返回 null。 */
    @Test
    fun enqueueUniqueWaitingTask_returnsNullForDuplicate() {
        val taskId = uniqueId("dup")
        val future = FutureTask<Any?> { null }
        manager.addTask(taskId, "n", "d", future,
            BackgroundTask.TaskType.MERGE, true)
        val returnedId = manager.enqueueUniqueWaitingTask(
            BackgroundTaskStatusManager.PendingUniqueTask(makeUniqueTask(taskId, "n"))
        )
        assertNull(returnedId)
    }

    /** pollNextUniqueWaitingTask 返回队首并清 isQueued。 */
    @Test
    fun pollNextUniqueWaitingTask_returnsHeadAndClearsQueued() {
        val a = uniqueId("a")
        val b = uniqueId("b")
        manager.enqueueUniqueWaitingTask(
            BackgroundTaskStatusManager.PendingUniqueTask(makeUniqueTask(a, "A"))
        )
        manager.enqueueUniqueWaitingTask(
            BackgroundTaskStatusManager.PendingUniqueTask(makeUniqueTask(b, "B"))
        )
        assertEquals(2, manager.uniqueWaitQueueSize)
        val head = manager.pollNextUniqueWaitingTask()
        assertNotNull(head)
        assertEquals(a, head!!.taskId)
        val infoA = manager.getTaskInfo(a)
        assertNotNull(infoA)
        assertFalse("polled task should no longer be queued", infoA!!.isQueued)
        assertEquals(1, manager.uniqueWaitQueueSize)
    }

    /** 已完成列表按插入顺序淘汰：最旧的条目被驱逐。 */
    @Test
    fun evictCompletedIfOverLimit_removesOldest() {
        // 填满 50 条
        for (i in 0 until 50) {
            val id = "old-$i"
            manager.addTask(id, "n", "d", null,
                BackgroundTask.TaskType.OTHER, false)
            manager.markTaskCompleted(id)
        }
        val oldestId = "old-0"
        assertNotNull(manager.getTaskInfo(oldestId))
        // 插入第 51 条，触发驱逐
        manager.addTask("new-1", "n", "d", null,
            BackgroundTask.TaskType.OTHER, false)
        manager.markTaskCompleted("new-1")
        assertNull("oldest should have been evicted", manager.getTaskInfo(oldestId))
        assertNotNull("newest should be present", manager.getTaskInfo("new-1"))
    }

    /** 简单的 unique 任务实现。execute() 不会被测试直接调用。 */
    private class StubUniqueTask(
        private val id: String,
        private val name: String
    ) : BackgroundTask {
        override fun getTaskId(): String = id
        override fun getTaskName(): String = name
        override fun getTaskDescription(): String? = null
        override fun getTaskType(): BackgroundTask.TaskType = BackgroundTask.TaskType.MERGE
        override fun isUniqueTask(): Boolean = true
        override fun getTaskClassName(): String = javaClass.name
        override fun getTaskPersistData(): String? = null
        override fun setProgressListener(listener: BackgroundTask.ProgressListener?) {}
        override suspend fun execute(): kotlin.Result<Unit> = kotlin.Result.success(Unit)
    }
}