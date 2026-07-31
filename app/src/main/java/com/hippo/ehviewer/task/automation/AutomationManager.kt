package com.hippo.ehviewer.task.automation

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.BackgroundTaskManager
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.task.BackgroundTask
import com.hippo.ehviewer.ui.task.BackgroundTaskInfo
import com.hippo.ehviewer.ui.task.BackgroundTaskStatusManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 自动化任务管理器。
 *
 * 旧 ScheduledTaskManager 的职责重新组织为：
 * - 触发器管理（Schedule / Event / Manual）
 * - 条件评估（WiFi / 充电 / 屏幕 / 时段）
 * - 动作执行（顺序 / 并行）
 * - 完成回调与重试
 */
class AutomationManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AutomationManager"
        private const val SCHEDULER_INTERVAL_SECONDS = 30L

        @Volatile
        private var instance: AutomationManager? = null

        @JvmStatic
        fun getInstance(context: Context): AutomationManager {
            return instance ?: synchronized(this) {
                instance ?: AutomationManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val persistence = AutomationPersistence(context)
    private val logger = AutomationLogger(context)
    private val conditionEvaluator = ConditionEvaluator(context)

    private val downloadManager: DownloadManager by lazy {
        EhApplication.getDownloadManager(context)
    }
    private val backgroundTaskManager: BackgroundTaskManager by lazy {
        BackgroundTaskManager.getInstance()
    }
    private val backgroundTaskStatusManager: BackgroundTaskStatusManager by lazy {
        BackgroundTaskStatusManager.getInstance()
    }

    private val tasks = ConcurrentHashMap<String, AutomationTask>()
    private val runningActionIds = ConcurrentHashMap<String, String>()
    private val actionCallbacks = ConcurrentHashMap<String, ((Boolean) -> Unit)?>()
    private val parallelTrackers = ConcurrentHashMap<String, ParallelTracker>()
    private val listeners = CopyOnWriteArrayList<AutomationListener>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var schedulerTimer: ScheduledExecutorService? = null

    @Volatile
    private var initialized: Boolean = false

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            persistence.loadTasks().forEach { tasks[it.id] = it }
            registerBackgroundTaskListener()
            startScheduler()
            initialized = true
            Log.i(TAG, "AutomationManager initialized with ${tasks.size} rules")
        }
    }

    fun shutdown() {
        schedulerTimer?.shutdown()
        schedulerTimer = null
        coroutineScope.cancel()
        Log.d(TAG, "AutomationManager shutdown")
    }

    fun getAllTasks(): List<AutomationTask> = tasks.values.sortedBy { it.order }

    fun getTask(id: String): AutomationTask? = tasks[id]

    fun upsertTask(task: AutomationTask) {
        tasks[task.id] = task
        persistence.saveTasks(getAllTasks())
        notifyTaskChanged(task.id)
    }

    fun deleteTask(id: String) {
        if (tasks.remove(id) != null) {
            persistence.saveTasks(getAllTasks())
            notifyTaskRemoved(id)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val task = tasks[id] ?: return
        tasks[id] = task.copy(enabled = enabled, state = if (enabled) AutomationState.PENDING else AutomationState.DISABLED)
        persistence.saveTasks(getAllTasks())
        notifyTaskChanged(id)
    }

    fun runNow(id: String) {
        val task = tasks[id] ?: return
        if (!task.enabled) return
        tryExecute(task, "manual_run")
    }

    fun addListener(listener: AutomationListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: AutomationListener) {
        listeners.remove(listener)
    }

    private fun notifyTaskChanged(taskId: String) {
        listeners.forEach { it.onTaskChanged(taskId) }
    }

    private fun notifyTaskRemoved(taskId: String) {
        listeners.forEach { it.onTaskRemoved(taskId) }
    }

    /**
     * 时间触发器轮询入口。
     */
    private fun checkAndExecuteScheduledTasks() {
        val now = System.currentTimeMillis()
        tasks.values.forEach { task ->
            if (!task.enabled) return@forEach
            if (task.state != AutomationState.PENDING && task.state != AutomationState.WAITING_TIME) return@forEach
            val trigger = task.trigger as? AutomationTrigger.Schedule ?: return@forEach
            val scheduled = task.nextScheduledTime
            if (scheduled != null && now >= scheduled) {
                logger.logTriggered(task, "schedule_fired")
                tryExecute(task, "schedule")
                scheduleNextRun(task, trigger)
            }
        }
    }

    private fun scheduleNextRun(task: AutomationTask, trigger: AutomationTrigger.Schedule) {
        val nextTime = computeNextRun(task, trigger)
        tasks[task.id] = task.copy(nextScheduledTime = nextTime)
    }

    /**
     * 事件触发入口（由 EventTriggerDispatcher 调用）。
     */
    internal fun onEvent(event: AutomationEvent) {
        tasks.values.forEach { task ->
            if (!task.enabled) return@forEach
            val trigger = task.trigger as? AutomationTrigger.Event ?: return@forEach
            if (trigger.type != event.type) return@forEach
            if (!matchesFilter(trigger.filter, event)) return@forEach
            logger.logTriggered(task, "event:${event.type.name}")
            tryExecute(task, "event:${event.type.name}")
        }
    }

    private fun matchesFilter(filter: EventFilter, event: AutomationEvent): Boolean {
        return when (filter) {
            EventFilter.None -> true
            is EventFilter.DownloadLabel -> {
                val payload = event.payload[AutomationEvent.KEY_LABEL]
                filter.label == null || filter.label == payload
            }
            is EventFilter.FilePath -> {
                val path = event.payload[AutomationEvent.KEY_PATH] ?: return false
                path.startsWith(filter.path)
            }
            is EventFilter.IntentFilter -> {
                val action = event.payload[AutomationEvent.KEY_ACTION] ?: return false
                action == filter.action
            }
        }
    }

    private fun computeNextRun(task: AutomationTask, trigger: AutomationTrigger.Schedule): Long? {
        val now = System.currentTimeMillis()
        return when (trigger.repeatMode) {
            RepeatMode.ONCE -> null
            RepeatMode.DAILY -> nextDaily(now)
            RepeatMode.WEEKDAYS -> nextWeekday(now)
            RepeatMode.WEEKENDS -> nextWeekend(now)
            RepeatMode.HOLIDAYS -> nextDaily(now)
            RepeatMode.CUSTOM_CRON -> trigger.cronExpression?.let { cronNext(it, now) }
        }
    }

    private fun nextDaily(from: Long): Long {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = from }
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun nextWeekday(from: Long): Long {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = from }
        repeat(7) {
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
            if (dow in java.util.Calendar.MONDAY..java.util.Calendar.FRIDAY) return cal.timeInMillis
        }
        return cal.timeInMillis
    }

    private fun nextWeekend(from: Long): Long {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = from }
        repeat(7) {
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
            if (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY) return cal.timeInMillis
        }
        return cal.timeInMillis
    }

    private fun cronNext(expression: String, from: Long): Long? {
        return try {
            com.hippo.ehviewer.task.scheduled.CronExpressionBuilder().getNextExecutionTime(expression, from)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 评估条件并提交执行。
     */
    private fun tryExecute(task: AutomationTask, reason: String) {
        val now = System.currentTimeMillis()
        val lastExec = task.lastExecutedAt
        if (task.cooldownMillis > 0 && lastExec != null &&
            now - lastExec < task.cooldownMillis
        ) return

        if (!conditionsMet(task)) {
            logger.logConditionsBlocked(task, listOf("not_met"))
            return
        }

        val running = task.copy(
            state = AutomationState.RUNNING,
            lastExecutedAt = now,
            executionCount = task.executionCount + 1,
            retryCount = 0,
        )
        tasks[task.id] = running
        notifyTaskChanged(task.id)
        persistence.saveTasks(getAllTasks())

        when (task.executionMode) {
            ExecutionMode.SEQUENTIAL -> runSequentially(running, 0)
            ExecutionMode.PARALLEL -> {
                running.actions.forEach { action ->
                    submitAction(running, action)
                }
            }
        }
    }

    private fun conditionsMet(task: AutomationTask): Boolean {
        return when (val result = conditionEvaluator.evaluate(task.conditions)) {
            is ConditionResult.Met -> true
            is ConditionResult.NotMet -> {
                logger.logConditionsBlocked(task, listOf(result.reason))
                false
            }
        }
    }

    private fun runSequentially(task: AutomationTask, index: Int) {
        if (index >= task.actions.size) {
            onAllActionsCompleted(task.id, success = true)
            return
        }
        val action = task.actions[index]
        submitAction(task, action, onComplete = { success ->
            if (!success) {
                onAllActionsCompleted(task.id, success = false)
            } else {
                runSequentially(task, index + 1)
            }
        })
    }

    private fun submitAction(
        task: AutomationTask,
        action: AutomationAction,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        if (!action.enabled) {
            onComplete?.invoke(true)
            return
        }
        val backgroundTask = createBackgroundTask(action) ?: run {
            logger.logFailed(task, "无法创建任务实例: ${action.taskClassName}")
            onComplete?.invoke(false)
            return
        }
        runningActionIds[backgroundTask.getTaskId()] = task.id
        actionCallbacks[backgroundTask.getTaskId()] = onComplete
        backgroundTaskManager.submitBackgroundTask(backgroundTask)
        logger.logStarted(task)
    }

    private fun onAllActionsCompleted(taskId: String, success: Boolean) {
        val task = tasks[taskId] ?: return
        val newState = if (success) AutomationState.COMPLETED else AutomationState.FAILED
        val updated = task.copy(state = newState)
        tasks[taskId] = updated
        persistence.saveTasks(getAllTasks())
        notifyTaskChanged(taskId)

        if (!success && updated.retryConfig.mode != RetryMode.NO_RETRY && updated.retryCount < updated.retryConfig.maxRetries) {
            handleRetry(updated)
        }
    }

    private fun handleRetry(task: AutomationTask) {
        val next = task.copy(retryCount = task.retryCount + 1)
        tasks[task.id] = next
        logger.logRetried(next, next.retryCount)
        persistence.saveTasks(getAllTasks())
        notifyTaskChanged(next.id)
    }

    /**
     * 监听 BackgroundTaskStatusManager 任务完成/失败/取消事件，
     * 把后台任务 ID 映射回 automationId。
     */
    private fun registerBackgroundTaskListener() {
        backgroundTaskStatusManager.addTaskChangeListener(object : BackgroundTaskStatusManager.TaskChangeListener {
            override fun onTaskStateChanged(taskId: String) {
                val automationId = runningActionIds.remove(taskId) ?: return
                val callback = actionCallbacks.remove(taskId)
                val info: BackgroundTaskInfo = backgroundTaskStatusManager.getTaskInfo(taskId) ?: return
                val success = info.isCompleted && !info.isCancelled && info.errorMessage == null
                val task = tasks[automationId] ?: return
                logger.logCompleted(task, success, 0L)

                if (callback != null) {
                    callback.invoke(success)
                } else {
                    val tracker = parallelTrackers.computeIfAbsent(automationId) {
                        ParallelTracker(task.actions.count { it.enabled }, 0, false)
                    }
                    tracker.completed++
                    if (!success) tracker.anyFailed = true
                    if (tracker.completed >= tracker.expected) {
                        parallelTrackers.remove(automationId)
                        onAllActionsCompleted(automationId, !tracker.anyFailed)
                    }
                }
            }
        })
    }

    private data class ParallelTracker(
        val expected: Int,
        var completed: Int,
        var anyFailed: Boolean,
    )

    private fun createBackgroundTask(action: AutomationAction): BackgroundTask? {
        return try {
            val taskClass = Class.forName(action.taskClassName)
            val ctor: Constructor<*> = taskClass.getDeclaredConstructor(Context::class.java)
            ctor.newInstance(context) as? BackgroundTask
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create task: ${action.taskClassName}", e)
            null
        }
    }

    private fun startScheduler() {
        schedulerTimer = Executors.newSingleThreadScheduledExecutor()
        schedulerTimer?.scheduleAtFixedRate({
            try {
                checkAndExecuteScheduledTasks()
            } catch (e: Exception) {
                Log.e(TAG, "Error in scheduler check", e)
            }
        }, 0, SCHEDULER_INTERVAL_SECONDS, TimeUnit.SECONDS)
        Log.d(TAG, "Scheduler started with interval: ${SCHEDULER_INTERVAL_SECONDS}s")
    }

    interface AutomationListener {
        fun onTaskChanged(taskId: String) {}
        fun onTaskRemoved(taskId: String) {}
    }
}

/**
 * 事件载荷（在 EventTriggerDispatcher 中构造，AutomationManager 接收）。
 */
data class AutomationEvent(
    val type: EventTriggerType,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: Map<String, String> = emptyMap(),
) {
    companion object {
        const val KEY_LABEL = "label"
        const val KEY_PATH = "path"
        const val KEY_ACTION = "action"
        const val KEY_GID = "gid"
    }
}