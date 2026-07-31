package com.hippo.ehviewer.task.scheduled

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hippo.ehviewer.BackgroundTaskManager
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.task.BackgroundTask
import com.hippo.ehviewer.task.TaskRegistry
import com.hippo.ehviewer.task.scheduled.holiday.CdnHolidayDataProvider
import com.hippo.ehviewer.task.scheduled.holiday.HolidayDataProvider
import com.hippo.ehviewer.task.scheduled.holiday.WeekdayConfig
import com.hippo.ehviewer.ui.task.BackgroundTaskInfo
import com.hippo.ehviewer.ui.task.BackgroundTaskStatusManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 定时任务调度管理器
 */
class ScheduledTaskManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "ScheduledTaskManager"
        private const val SCHEDULER_INTERVAL_SECONDS = 30L  // 调度检查间隔

        @Volatile
        private var instance: ScheduledTaskManager? = null

        @JvmStatic
        fun getInstance(context: Context): ScheduledTaskManager {
            return instance ?: synchronized(this) {
                instance ?: ScheduledTaskManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val persistence = ScheduledTaskPersistence(context)
    private val logger = ScheduledTaskLogger(context)
    private val cronBuilder = CronExpressionBuilder()
    private val holidayProvider: HolidayDataProvider = CdnHolidayDataProvider(context)
    
    // 任务队列（按组组织）
    private val taskGroups = ConcurrentHashMap<String, MutableList<ScheduledTask>>()
    // 组配置
    private val groups = ConcurrentHashMap<String, TaskGroup>()
    // 工作日配置
    private var weekdayConfig: WeekdayConfig = WeekdayConfig()
    // 监听器
    private val listeners = CopyOnWriteArrayList<ScheduledTaskListener>()
    // 主 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    // 调度检查定时器
    private var schedulerTimer: ScheduledExecutorService? = null
    // 协程作用域
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 下载管理器
    private val downloadManager: DownloadManager by lazy {
        EhApplication.getDownloadManager(context)
    }
    // 后台任务管理器
    private val backgroundTaskManager: BackgroundTaskManager by lazy {
        BackgroundTaskManager.getInstance()
    }
    private val backgroundTaskStatusManager: BackgroundTaskStatusManager by lazy {
        BackgroundTaskStatusManager.getInstance()
    }
    // 记录正在运行的 BackgroundTask id -> ScheduledTask id 的映射
    private val runningTaskMap = ConcurrentHashMap<String, String>()

    // 延时条件检查器
    private val conditionChecker = DelayConditionChecker(downloadManager, backgroundTaskManager)
    // 重复模式调度器
    private val repeatScheduler = RepeatModeScheduler(holidayProvider, weekdayConfig)

    /**
     * 初始化
     */
    fun initialize() {
        loadFromPersistence()
        registerDownloadListener()
        registerBackgroundTaskListener()
        startScheduler()
        Log.d(TAG, "ScheduledTaskManager initialized")
    }

    /**
     * 添加定时任务
     */
    fun addScheduledTask(task: ScheduledTask) {
        val groupTasks = taskGroups.getOrPut(task.groupId) { mutableListOf() }
        
        synchronized(groupTasks) {
            // 设置顺序
            task.order = groupTasks.size
            groupTasks.add(task)
        }

        // 根据延时条件设置初始状态
        when (task.delayCondition) {
            DelayCondition.IMMEDIATE -> {
                task.state = ScheduledTaskState.PENDING
            }
            DelayCondition.DOWNLOAD_COMPLETE,
            DelayCondition.OTHER_TASKS_COMPLETE -> {
                task.state = ScheduledTaskState.WAITING_CONDITION
            }
            DelayCondition.SPECIFIC_TIME -> {
                task.state = ScheduledTaskState.WAITING_TIME
            }
        }

        // 如果是重复任务，计算下次执行时间
        if (task.repeatMode != RepeatMode.ONCE) {
            task.scheduledTime = repeatScheduler.getNextExecutionTime(task)
        }

        persistence.saveTasks(getAllTasks())
        notifyTaskAdded(task)

        Log.d(TAG, "Added scheduled task: ${task.taskDisplayName}")
    }

    private fun notifyTaskAdded(task: ScheduledTask) {
        listeners.forEach { it.onTaskAdded(task) }
    }

    private fun notifyTaskRemoved(taskId: String) {
        listeners.forEach { it.onTaskRemoved(taskId) }
    }

    private fun notifyTaskStateChanged(taskId: String) {
        listeners.forEach { it.onTaskStateChanged(taskId) }
    }

    /**
     * 移除定时任务
     */
    fun removeScheduledTask(taskId: String) {
        taskGroups.forEach { (_, tasks) ->
            synchronized(tasks) {
                tasks.removeAll { it.id == taskId }
            }
        }
        
        persistence.saveTasks(getAllTasks())
        notifyTaskRemoved(taskId)
        Log.d(TAG, "Removed scheduled task: $taskId")
    }

    /**
     * 更新任务顺序
     */
    fun updateTaskOrder(groupId: String, taskIds: List<String>) {
        val tasks = taskGroups[groupId] ?: return
        
        synchronized(tasks) {
            taskIds.forEachIndexed { index, taskId ->
                tasks.find { it.id == taskId }?.order = index
            }
            tasks.sortBy { it.order }
        }
        
        persistence.saveTasks(getAllTasks())
        Log.d(TAG, "Updated task order for group: $groupId")
    }

    /**
     * 移动任务到新组
     */
    fun moveTaskToGroup(taskId: String, newGroupId: String) {
        var task: ScheduledTask? = null
        
        // 从原组移除
        taskGroups.forEach { (_, tasks) ->
            synchronized(tasks) {
                val found = tasks.find { it.id == taskId }
                if (found != null) {
                    task = found
                    tasks.remove(found)
                }
            }
        }
        
        // 添加到新组
        if (task != null) {
            val newTask = task!!.copy(groupId = newGroupId)
            val groupTasks = taskGroups.getOrPut(newGroupId) { mutableListOf() }
            synchronized(groupTasks) {
                newTask.order = groupTasks.size
                groupTasks.add(newTask)
            }
            
            persistence.saveTasks(getAllTasks())
            Log.d(TAG, "Moved task $taskId to group $newGroupId")
        }
    }

    /**
     * 创建任务组
     */
    fun createGroup(groupName: String, mode: ExecutionMode): TaskGroup {
        val groupId = "group_${System.currentTimeMillis()}"
        val group = TaskGroup(groupId, groupName, mode)
        groups[groupId] = group
        
        persistence.saveGroups(groups.values.toList())
        Log.d(TAG, "Created group: $groupName")
        return group
    }

    /**
     * 删除任务组
     */
    fun deleteGroup(groupId: String) {
        if (groupId == TaskGroup.DEFAULT_GROUP_ID) {
            Log.w(TAG, "Cannot delete default group")
            return
        }
        
        // 将组内任务移动到默认组
        val tasks = taskGroups.remove(groupId)
        if (tasks != null) {
            val defaultTasks = taskGroups.getOrPut(TaskGroup.DEFAULT_GROUP_ID) { mutableListOf() }
            synchronized(defaultTasks) {
                tasks.forEach { task ->
                    val movedTask = task.copy(groupId = TaskGroup.DEFAULT_GROUP_ID)
                    movedTask.order = defaultTasks.size
                    defaultTasks.add(movedTask)
                }
            }
        }
        
        groups.remove(groupId)
        
        persistence.saveGroups(groups.values.toList())
        persistence.saveTasks(getAllTasks())
        Log.d(TAG, "Deleted group: $groupId")
    }

    /**
     * 更新任务组
     */
    fun updateGroup(group: TaskGroup) {
        groups[group.groupId] = group
        persistence.saveGroups(groups.values.toList())
        Log.d(TAG, "Updated group: ${group.groupId}")
    }

    /**
     * 获取所有任务
     */
    fun getAllTasks(): List<ScheduledTask> {
        val allTasks = mutableListOf<ScheduledTask>()
        taskGroups.forEach { (_, tasks) ->
            synchronized(tasks) {
                allTasks.addAll(tasks)
            }
        }
        return allTasks.sortedBy { it.order }
    }

    /**
     * 获取任务组列表
     */
    fun getTaskGroups(): List<TaskGroup> {
        return groups.values.toList()
    }

    /**
     * 获取指定组的任务
     */
    fun getTasksByGroup(groupId: String): List<ScheduledTask> {
        return taskGroups[groupId]?.toList() ?: emptyList()
    }

    /**
     * 获取工作日配置
     */
    fun getWeekdayConfig(): WeekdayConfig = weekdayConfig

    /**
     * 更新工作日配置
     */
    fun updateWeekdayConfig(config: WeekdayConfig) {
        weekdayConfig = config
        persistence.saveWeekdayConfig(config)
        Log.d(TAG, "Updated weekday config")
    }

    /**
     * 获取节假日数据提供者
     */
    fun getHolidayProvider(): HolidayDataProvider = holidayProvider

    /**
     * 刷新节假日数据
     */
    fun refreshHolidayData() {
        coroutineScope.launch {
            try {
                holidayProvider.refreshData()
                persistence.saveHolidayLastUpdateTime(System.currentTimeMillis())
                Log.d(TAG, "Refreshed holiday data")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh holiday data", e)
            }
        }
    }

    /**
     * 添加监听器
     */
    fun addListener(listener: ScheduledTaskListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * 移除监听器
     */
    fun removeListener(listener: ScheduledTaskListener) {
        listeners.remove(listener)
    }

    /**
     * 检查并执行待执行任务
     */
    private fun checkAndExecuteTasks() {
        val allTasks = getAllTasks()
        
        allTasks.forEach { task ->
            when (task.state) {
                ScheduledTaskState.PENDING -> {
                    submitTask(task)
                }
                ScheduledTaskState.WAITING_CONDITION -> {
                    if (conditionChecker.canExecute(task)) {
                        logger.logConditionMet(task, task.delayCondition)
                        submitTask(task)
                    }
                }
                ScheduledTaskState.WAITING_TIME -> {
                    val scheduledTime = task.scheduledTime
                    if (scheduledTime != null && System.currentTimeMillis() >= scheduledTime) {
                        logger.logConditionMet(task, DelayCondition.SPECIFIC_TIME)
                        submitTask(task)
                    }
                }
                ScheduledTaskState.FAILED -> {
                    if (task.retryConfig.mode != RetryMode.NO_RETRY) {
                        handleRetry(task)
                    }
                }
                else -> {
                    // 其他状态不处理
                }
            }
        }
    }

    /**
     * 提交任务到 BackgroundTaskManager
     */
    private fun submitTask(task: ScheduledTask) {
        // 检查组是否可以执行
        if (!canSubmitToGroup(task.groupId)) {
            logger.logConditionCheck(task, task.delayCondition, false)
            return
        }

        task.state = ScheduledTaskState.RUNNING
        task.lastExecutedAt = System.currentTimeMillis()
        task.executionCount++
        
        persistence.saveTasks(getAllTasks())
        notifyTaskStateChanged(task.id)

        // 创建并提交实际任务
        val backgroundTask = createBackgroundTask(task)
        if (backgroundTask != null) {
            runningTaskMap[backgroundTask.getTaskId()] = task.id
            backgroundTaskManager.submitBackgroundTask(backgroundTask)
            Log.d(TAG, "Submitted task: ${task.taskDisplayName}")
        } else {
            task.state = ScheduledTaskState.FAILED
            logger.logTaskFailed(task, "无法创建任务实例")
            persistence.saveTasks(getAllTasks())
            notifyTaskStateChanged(task.id)
        }
    }

    /**
     * 创建后台任务实例
     */
    private fun createBackgroundTask(task: ScheduledTask): BackgroundTask? {
        return try {
            val taskClass = Class.forName(task.taskClassName)
            val constructor = taskClass.getDeclaredConstructor(Context::class.java)
            constructor.newInstance(context) as? BackgroundTask
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create task: ${task.taskClassName}", e)
            null
        }
    }

    /**
     * 检查组是否可以提交任务
     */
    private fun canSubmitToGroup(groupId: String): Boolean {
        val group = groups[groupId] ?: return false
        val tasks = taskGroups[groupId] ?: return false

        val runningTasks = tasks.filter { it.state == ScheduledTaskState.RUNNING }

        return when (group.executionMode) {
            ExecutionMode.SEQUENTIAL -> runningTasks.isEmpty()
            ExecutionMode.PARALLEL -> {
                // 受后台并发任务数限制
                val maxConcurrent = com.hippo.ehviewer.Settings.getBackgroundConcurrentTasks()
                runningTasks.size < maxConcurrent
            }
        }
    }

    /**
     * 处理任务完成
     */
    private fun handleTaskCompleted(taskId: String, success: Boolean) {
        val task = findTask(taskId) ?: return

        if (success) {
            task.state = ScheduledTaskState.COMPLETED
            task.retryCount = 0
            
            // 如果是重复任务，计算下次执行时间
            if (task.repeatMode != RepeatMode.ONCE) {
                val nextTime = repeatScheduler.getNextExecutionTime(task)
                if (nextTime != null) {
                    task.scheduledTime = nextTime
                    task.state = ScheduledTaskState.WAITING_TIME
                    logger.logTaskCompleted(task, true, 0)
                }
            } else {
                logger.logTaskCompleted(task, true, 0)
            }
        } else {
            task.state = ScheduledTaskState.FAILED
            logger.logTaskFailed(task, "任务执行失败")
        }

        persistence.saveTasks(getAllTasks())
        notifyTaskStateChanged(taskId)
    }

    /**
     * 处理重试
     */
    private fun handleRetry(task: ScheduledTask) {
        if (task.retryCount >= task.retryConfig.maxRetries) {
            Log.d(TAG, "Max retries reached for task: ${task.taskDisplayName}")
            return
        }

        task.retryCount++
        logger.logTaskRetried(task, task.retryCount)

        when (task.retryConfig.mode) {
            RetryMode.IMMEDIATE -> {
                task.state = ScheduledTaskState.PENDING
            }
            RetryMode.DELAYED -> {
                task.state = ScheduledTaskState.WAITING_TIME
                task.scheduledTime = System.currentTimeMillis() + task.retryConfig.delayMillis
            }
            RetryMode.NEXT_SCHEDULE -> {
                task.state = ScheduledTaskState.WAITING_TIME
                task.scheduledTime = repeatScheduler.getNextExecutionTime(task)
            }
            else -> {}
        }

        persistence.saveTasks(getAllTasks())
        notifyTaskStateChanged(task.id)
    }

    /**
     * 查找任务
     */
    private fun findTask(taskId: String): ScheduledTask? {
        taskGroups.forEach { (_, tasks) ->
            synchronized(tasks) {
                val task = tasks.find { it.id == taskId }
                if (task != null) return task
            }
        }
        return null
    }

    /**
     * 从持久化存储加载数据
     */
    private fun loadFromPersistence() {
        // 加载组
        val savedGroups = persistence.loadGroups()
        savedGroups.forEach { group ->
            groups[group.groupId] = group
        }

        // 加载工作日配置
        weekdayConfig = persistence.loadWeekdayConfig()

        // 加载任务
        val savedTasks = persistence.loadTasks()
        savedTasks.forEach { task ->
            val groupTasks = taskGroups.getOrPut(task.groupId) { mutableListOf() }
            synchronized(groupTasks) {
                groupTasks.add(task)
            }
        }

        // 恢复未完成任务的状态
        taskGroups.forEach { (_, tasks) ->
            synchronized(tasks) {
                tasks.forEach { task ->
                    if (task.state == ScheduledTaskState.RUNNING) {
                        // 应用重启时，运行中的任务重置为待执行
                        task.state = ScheduledTaskState.PENDING
                    }
                }
            }
        }

        Log.d(TAG, "Loaded ${savedTasks.size} tasks and ${savedGroups.size} groups from persistence")
    }

    /**
     * 注册下载监听器
     */
    private fun registerDownloadListener() {
        downloadManager.addDownloadListener(object : DownloadManager.DownloadListener {
            override fun onGet509() {}

            override fun onStart(info: com.hippo.ehviewer.dao.DownloadInfo) {}

            override fun onDownload(info: com.hippo.ehviewer.dao.DownloadInfo) {}

            override fun onGetPage(info: com.hippo.ehviewer.dao.DownloadInfo) {}

            override fun onFinish(info: com.hippo.ehviewer.dao.DownloadInfo) {
                if (downloadManager.getDownloadingCount() == 0 && downloadManager.getWaitingCount() == 0) {
                    mainHandler.post {
                        onDownloadsCompleted()
                    }
                }
            }

            override fun onCancel(info: com.hippo.ehviewer.dao.DownloadInfo) {}
        })
    }

    /**
     * 注册后台任务监听器，监听任务完成/失败/取消事件
     */
    private fun registerBackgroundTaskListener() {
        backgroundTaskStatusManager.addTaskChangeListener(object : BackgroundTaskStatusManager.TaskChangeListener {
            override fun onTaskStateChanged(taskId: String) {
                val scheduledTaskId = runningTaskMap.remove(taskId)
                if (scheduledTaskId != null) {
                    val info: BackgroundTaskInfo = backgroundTaskStatusManager.getTaskInfo(taskId) ?: return
                    val success = info.isCompleted && !info.isCancelled && info.errorMessage == null
                    handleTaskCompleted(scheduledTaskId, success)
                }
                onBackgroundTaskCompleted(taskId)
            }
        })
    }

    /**
     * 下载完成事件处理
     */
    private fun onDownloadsCompleted() {
        val allTasks = getAllTasks()
        val waitingTasks = allTasks.filter { 
            it.state == ScheduledTaskState.WAITING_CONDITION && 
            it.delayCondition == DelayCondition.DOWNLOAD_COMPLETE 
        }
        
        waitingTasks.forEach { task ->
            logger.logConditionMet(task, DelayCondition.DOWNLOAD_COMPLETE)
            submitTask(task)
        }
    }

    /**
     * 后台任务完成事件处理
     */
    private fun onBackgroundTaskCompleted(completedTaskId: String) {
        val allTasks = getAllTasks()
        val waitingTasks = allTasks.filter {
            it.state == ScheduledTaskState.WAITING_CONDITION &&
            it.delayCondition == DelayCondition.OTHER_TASKS_COMPLETE
        }

        waitingTasks.forEach { task ->
            // 检查是否除了自己以外没有其他任务在运行
            if (conditionChecker.checkOtherTasksComplete(task.id)) {
                logger.logConditionMet(task, DelayCondition.OTHER_TASKS_COMPLETE)
                submitTask(task)
            }
        }
    }

    /**
     * 启动调度检查
     */
    private fun startScheduler() {
        schedulerTimer = Executors.newSingleThreadScheduledExecutor()
        schedulerTimer?.scheduleAtFixedRate({
            try {
                checkAndExecuteTasks()
            } catch (e: Exception) {
                Log.e(TAG, "Error in scheduler check", e)
            }
        }, 0, SCHEDULER_INTERVAL_SECONDS, TimeUnit.SECONDS)
        
        Log.d(TAG, "Scheduler started with interval: ${SCHEDULER_INTERVAL_SECONDS}s")
    }

    /**
     * 停止调度
     */
    fun shutdown() {
        schedulerTimer?.shutdown()
        schedulerTimer = null
        coroutineScope.cancel()
        Log.d(TAG, "ScheduledTaskManager shutdown")
    }

    /**
     * 监听器接口
     */
    interface ScheduledTaskListener {
        fun onTaskAdded(task: ScheduledTask) {}
        fun onTaskRemoved(taskId: String) {}
        fun onTaskStateChanged(taskId: String) {}
        fun onTaskGroupChanged() {}
    }

    /**
     * 延时条件检查器
     */
    private class DelayConditionChecker(
        private val downloadManager: DownloadManager,
        private val backgroundTaskManager: BackgroundTaskManager
    ) {
        fun canExecute(task: ScheduledTask): Boolean {
            return when (task.delayCondition) {
                DelayCondition.IMMEDIATE -> true
                DelayCondition.DOWNLOAD_COMPLETE -> checkDownloadComplete()
                DelayCondition.OTHER_TASKS_COMPLETE -> checkOtherTasksComplete(task.id)
                DelayCondition.SPECIFIC_TIME -> checkScheduledTime(task)
            }
        }

        private fun checkDownloadComplete(): Boolean {
            return downloadManager.getDownloadingCount() == 0 && downloadManager.getWaitingCount() == 0
        }

        fun checkOtherTasksComplete(currentTaskId: String): Boolean {
            // 检查除了当前任务外是否有其他任务在运行
            val activeTasks = backgroundTaskManager.taskStatusManager.activeTasks
            return activeTasks.none { it.taskId != currentTaskId }
        }

        private fun checkScheduledTime(task: ScheduledTask): Boolean {
            val scheduledTime = task.scheduledTime ?: return false
            return System.currentTimeMillis() >= scheduledTime
        }
    }

    /**
     * 重复模式调度器
     */
    private class RepeatModeScheduler(
        private val holidayProvider: HolidayDataProvider,
        private val weekdayConfig: WeekdayConfig
    ) {
        fun getNextExecutionTime(task: ScheduledTask): Long? {
            return when (task.repeatMode) {
                RepeatMode.ONCE -> task.scheduledTime
                RepeatMode.DAILY -> getNextDailyTime(task)
                RepeatMode.WEEKDAYS -> getNextWeekdaysTime(task)
                RepeatMode.WEEKENDS -> getNextWeekendsTime(task)
                RepeatMode.HOLIDAYS -> getNextHolidaysTime(task)
                RepeatMode.CUSTOM_CRON -> getNextCronTime(task)
            }
        }

        private fun getNextDailyTime(task: ScheduledTask): Long {
            val calendar = java.util.Calendar.getInstance()
            if (task.scheduledTime != null) {
                calendar.timeInMillis = task.scheduledTime!!
                calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            return calendar.timeInMillis
        }

        private fun getNextWeekdaysTime(task: ScheduledTask): Long {
            val calendar = java.util.Calendar.getInstance()
            if (task.scheduledTime != null) {
                calendar.timeInMillis = task.scheduledTime!!
            }
            
            // 找到下一个工作日
            repeat(7) {
                calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
                val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1
                if (weekdayConfig.isWorkday(java.time.DayOfWeek.of(dayOfWeek % 7 + 1))) {
                    return calendar.timeInMillis
                }
            }
            
            return calendar.timeInMillis
        }

        private fun getNextWeekendsTime(task: ScheduledTask): Long {
            val calendar = java.util.Calendar.getInstance()
            if (task.scheduledTime != null) {
                calendar.timeInMillis = task.scheduledTime!!
            }
            
            // 找到下一个周末
            repeat(7) {
                calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
                val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1
                if (!weekdayConfig.isWorkday(java.time.DayOfWeek.of(dayOfWeek % 7 + 1))) {
                    return calendar.timeInMillis
                }
            }
            
            return calendar.timeInMillis
        }

        private fun getNextHolidaysTime(task: ScheduledTask): Long {
            // 实现节假日调度
            return getNextDailyTime(task)
        }

        private fun getNextCronTime(task: ScheduledTask): Long? {
            val cronExpression = task.cronExpression ?: return null
            val cronBuilder = CronExpressionBuilder()
            return cronBuilder.getNextExecutionTime(cronExpression)
        }
    }
}
