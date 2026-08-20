package com.hippo.ehviewer.ui.task;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.content.Context;

import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.task.BackgroundTask;
import com.hippo.ehviewer.task.BackgroundTaskController;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 后台任务状态管理器
 * 用于跟踪和管理所有后台任务的状态
 */
public class BackgroundTaskStatusManager {
    private static final String TAG = "BackgroundTaskStatusManager";
    private static final String STATUS_FILE_NAME = "background_tasks.json";
    private static final String LOG_DIR_NAME = "background_task_logs";

    private static BackgroundTaskStatusManager sInstance;

    // 存储所有活跃的任务
    private final Map<String, BackgroundTaskInfo> mActiveTasks = new ConcurrentHashMap<>();
    // 存储已完成的任务（保留最近的一些），LinkedHashMap 保证按插入顺序以实现可预测的最旧条目淘汰
    private final Map<String, BackgroundTaskInfo> mCompletedTasks = Collections.synchronizedMap(new LinkedHashMap<>());
    // 最大保留的已完成任务数量
    private static final int MAX_COMPLETED_TASKS = 50;
    // 互斥任务等待队列（仅内存，进程重启后清空）
    private final Deque<PendingUniqueTask> mUniqueWaitQueue = new ArrayDeque<>();
    // 互斥等待队列变更监听器（由 BackgroundTaskManager 设置，用于在槽位释放时启动下一任务）
    public interface UniqueWaitListener {
        void onUniqueTaskPromotable();
    }
    private volatile UniqueWaitListener mUniqueWaitListener;

    /**
     * 互斥等待队列条目。仅在内存中保留，进程重启即丢失。
     */
    public static final class PendingUniqueTask {
        private final String taskId;
        private final String taskName;
        private final String taskDescription;
        private final BackgroundTask.TaskType taskType;
        private final boolean uniqueTask;
        private final String taskClassName;
        private final String taskPersistData;
        private final BackgroundTask task;

        public PendingUniqueTask(@NonNull BackgroundTask task) {
            this.taskId = task.getTaskId();
            this.taskName = task.getTaskName();
            this.taskDescription = task.getTaskDescription();
            this.taskType = task.getTaskType();
            this.uniqueTask = task.isUniqueTask();
            this.taskClassName = task.getTaskClassName();
            this.taskPersistData = task.getTaskPersistData();
            this.task = task;
        }

        @NonNull public String getTaskId() { return taskId; }
        @NonNull public String getTaskName() { return taskName; }
        @Nullable public String getTaskDescription() { return taskDescription; }
        @NonNull public BackgroundTask.TaskType getTaskType() { return taskType; }
        public boolean isUniqueTask() { return uniqueTask; }
        @NonNull public String getTaskClassName() { return taskClassName; }
        @Nullable public String getTaskPersistData() { return taskPersistData; }
        @NonNull public BackgroundTask getTask() { return task; }
    }

    /**
     * 任务变更监听器
     */
    public interface TaskChangeListener {
        default void onTaskAdded(String taskId) {}
        default void onTaskProgressChanged(String taskId) {}
        default void onTaskStateChanged(String taskId) {}
        default void onTaskRemoved(String taskId) {}
    }

    private final List<TaskChangeListener> mChangeListeners = new CopyOnWriteArrayList<>();

    public void addTaskChangeListener(@NonNull TaskChangeListener listener) {
        if (!mChangeListeners.contains(listener)) {
            mChangeListeners.add(listener);
        }
    }

    public void removeTaskChangeListener(@NonNull TaskChangeListener listener) {
        mChangeListeners.remove(listener);
    }

    /**
     * 注册互斥等待队列的监听器。当任意 unique 任务结束时，会通过该监听器通知
     * BackgroundTaskManager 启动队列中的下一任务。仅保留最近设置的监听器。
     */
    public void setUniqueWaitListener(@Nullable UniqueWaitListener listener) {
        this.mUniqueWaitListener = listener;
    }

    /**
     * 将互斥任务加入等待队列并在活跃表中创建一条占位（isQueued=true，无 Future）。
     * 返回创建的 taskId；若同名 taskId 已存在则返回 null。
     */
    @Nullable
    public synchronized String enqueueUniqueWaitingTask(@NonNull PendingUniqueTask pending) {
        String taskId = pending.getTaskId();
        if (mActiveTasks.containsKey(taskId) || mCompletedTasks.containsKey(taskId)) {
            return null;
        }
        BackgroundTaskInfo info = new BackgroundTaskInfo(
                taskId, pending.getTaskName(), pending.getTaskDescription(), null,
                pending.getTaskType(), pending.isUniqueTask(),
                pending.getTaskClassName(), pending.getTaskPersistData(),
                pending.getTask().getMutexGroup(), System.currentTimeMillis());
        info.setQueued(true);
        File logFile = createTaskLogFile(taskId);
        if (logFile != null) {
            info.setLogFile(logFile);
        }
        mActiveTasks.put(taskId, info);
        mUniqueWaitQueue.addLast(pending);
        savePersistedTasksAsync();
        notifyTaskAdded(taskId);
        return taskId;
    }

    /**
     * 取出队首的待启动互斥任务，并把它在 mActiveTasks 中的条目标记为运行中（清 queued）。
     * 若队列为空则返回 null。
     */
    @Nullable
    public synchronized PendingUniqueTask pollNextUniqueWaitingTask() {
        PendingUniqueTask head = mUniqueWaitQueue.pollFirst();
        if (head == null) {
            return null;
        }
        BackgroundTaskInfo info = mActiveTasks.get(head.getTaskId());
        if (info != null) {
            info.setQueued(false);
            notifyTaskStateChanged(head.getTaskId());
        }
        return head;
    }

    /**
     * 从等待队列中按 taskId 移除（用于取消一个仍排队中的任务）。
     * 同时从 mActiveTasks 中移除占位条目。
     */
    public synchronized boolean removeFromUniqueWaitQueue(@NonNull String taskId) {
        Iterator<PendingUniqueTask> it = mUniqueWaitQueue.iterator();
        boolean removed = false;
        while (it.hasNext()) {
            if (taskId.equals(it.next().getTaskId())) {
                it.remove();
                removed = true;
                break;
            }
        }
        if (removed) {
            BackgroundTaskInfo info = mActiveTasks.remove(taskId);
            if (info != null) {
                savePersistedTasksAsync();
                notifyTaskRemoved(taskId);
            }
        }
        return removed;
    }

    /**
     * 仅从已完成队列移除指定 taskId，不影响活跃任务。
     * 返回是否真的移除了一条记录。
     */
    public synchronized boolean removeFromCompleted(@NonNull String taskId) {
        BackgroundTaskInfo removed = mCompletedTasks.remove(taskId);
        if (removed != null) {
            savePersistedTasksAsync();
            notifyTaskRemoved(taskId);
            return true;
        }
        return false;
    }

    /**
     * 当前互斥等待队列大小（仅 UI 展示用）。
     */
    public synchronized int getUniqueWaitQueueSize() {
        return mUniqueWaitQueue.size();
    }

    /**
     * 检测并解决互斥任务死锁。
     * 如果等待队列中的任务都在等待同一个未执行的任务，则强制启动队列头部的任务。
     *
     * @return true 表示检测到死锁并需要启动队列头部任务
     */
    public synchronized boolean detectAndResolveDeadlock() {
        if (mUniqueWaitQueue.isEmpty()) {
            return false;
        }

        // 收集等待队列中所有任务的互斥组
        java.util.Map<String, java.util.List<PendingUniqueTask>> groupMap = new java.util.HashMap<>();
        for (PendingUniqueTask pending : mUniqueWaitQueue) {
            String group = pending.getTask().getMutexGroup();
            if (group != null) {
                groupMap.computeIfAbsent(group, k -> new java.util.ArrayList<>()).add(pending);
            }
        }

        // 检查每个互斥组是否有死锁
        for (java.util.Map.Entry<String, java.util.List<PendingUniqueTask>> entry : groupMap.entrySet()) {
            String group = entry.getKey();
            java.util.List<PendingUniqueTask> waitingTasks = entry.getValue();

            // 检查是否有任务正在执行该互斥组
            boolean hasRunningTask = false;
            for (BackgroundTaskInfo info : mActiveTasks.values()) {
                if (!info.isQueued() && info.getMutexGroup() != null
                    && info.getMutexGroup().equals(group) && info.getFuture() != null) {
                    hasRunningTask = true;
                    break;
                }
            }

            // 如果没有任务正在执行，但有多个任务在等待，则存在死锁
            if (!hasRunningTask && waitingTasks.size() > 1) {
                android.util.Log.w(TAG, "Detected mutex deadlock in group: " + group
                        + ", forcing queue head to run");
                return true; // 返回 true 表示检测到死锁
            }
        }

        return false;
    }

    private void notifyTaskAdded(String taskId) {
        for (TaskChangeListener l : mChangeListeners) {
            l.onTaskAdded(taskId);
        }
    }

    private void notifyTaskProgressChanged(String taskId) {
        for (TaskChangeListener l : mChangeListeners) {
            l.onTaskProgressChanged(taskId);
        }
    }

    private void notifyTaskStateChanged(String taskId) {
        for (TaskChangeListener l : mChangeListeners) {
            l.onTaskStateChanged(taskId);
        }
    }

    private void notifyTaskRemoved(String taskId) {
        for (TaskChangeListener l : mChangeListeners) {
            l.onTaskRemoved(taskId);
        }
    }

    private final File mStatusFile;
    private final File mLogDir;
    private final ExecutorService mDiskExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BackgroundTaskStatusManager-Disk");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    // 任务控制执行器：用于调用任务的 pause/resume/cancel suspend 方法，避免阻塞调用方线程
    private final ExecutorService mControlExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BackgroundTaskStatusManager-Control");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private final Object mPersistLock = new Object();
    private boolean mPersistDirty = false;

    private BackgroundTaskStatusManager(Context context) {
        File filesDir = context.getFilesDir();
        mStatusFile = new File(filesDir, STATUS_FILE_NAME);
        mLogDir = new File(filesDir, LOG_DIR_NAME);
        if (!mLogDir.exists()) {
            mLogDir.mkdirs();
        }
        restorePersistedTasks();
    }
    
    public static synchronized void initialize(Context context) {
        if (sInstance == null) {
            sInstance = new BackgroundTaskStatusManager(context.getApplicationContext());
        }
    }

    public static synchronized BackgroundTaskStatusManager getInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("BackgroundTaskStatusManager not initialized");
        }
        return sInstance;
    }
    
    /**
     * 添加一个新的后台任务
     */
    @Nullable
    public String addTask(@NonNull String taskName, @Nullable String taskDescription, @Nullable Future<?> future) {
        String taskId = UUID.randomUUID().toString();
        return addTask(taskId, taskName, taskDescription, future, BackgroundTask.TaskType.OTHER, true);
    }

    @Nullable
    public String addTask(@NonNull String taskName, @Nullable String taskDescription, @Nullable Future<?> future,
                          @NonNull BackgroundTask.TaskType taskType, boolean uniqueTask) {
        String taskId = UUID.randomUUID().toString();
        return addTask(taskId, taskName, taskDescription, future, taskType, uniqueTask);
    }

    @Nullable
    public String addTask(@NonNull String taskId, @NonNull String taskName, @Nullable String taskDescription,
                          @Nullable Future<?> future, @NonNull BackgroundTask.TaskType taskType, boolean uniqueTask) {
        return addTask(taskId, taskName, taskDescription, future, taskType, uniqueTask,
                BackgroundTask.class.getName(), null);
    }

    @Nullable
    public String addTask(@NonNull String taskId, @NonNull String taskName, @Nullable String taskDescription,
                          @Nullable Future<?> future, @NonNull BackgroundTask.TaskType taskType, boolean uniqueTask,
                          @NonNull String taskClassName, @Nullable String taskPersistData) {
        return addTask(taskId, taskName, taskDescription, future, taskType, uniqueTask, taskClassName, taskPersistData, null);
    }

    public String addTask(@NonNull String taskId, @NonNull String taskName, @Nullable String taskDescription,
                          @Nullable Future<?> future, @NonNull BackgroundTask.TaskType taskType, boolean uniqueTask,
                          @NonNull String taskClassName, @Nullable String taskPersistData, @Nullable String mutexGroup) {
        if (uniqueTask && taskType != BackgroundTask.TaskType.DOWNLOAD) {
            BackgroundTaskInfo activeUnique = getActiveUniqueNonDownloadTask();
            if (activeUnique != null) {
                return null;
            }
        }

        BackgroundTaskInfo taskInfo = new BackgroundTaskInfo(taskId, taskName, taskDescription, future, taskType,
                uniqueTask, taskClassName, taskPersistData, mutexGroup, System.currentTimeMillis());
        taskInfo.setQueued(future != null);
        File logFile = createTaskLogFile(taskId);
        if (logFile != null) {
            taskInfo.setLogFile(logFile);
        }
        mActiveTasks.put(taskId, taskInfo);
        savePersistedTasksAsync();
        notifyTaskAdded(taskId);
        return taskId;
    }

    /**
     * 查找与指定 mutexGroup 冲突的活跃任务（同组且 unique 且非 DOWNLOAD）。
     * mutexGroup 为 null 时始终返回 null（不参与互斥）。
     * 注意：已排队的任务（isQueued=true）不算作正在运行的冲突。
     */
    @Nullable
    public BackgroundTaskInfo getActiveConflictTask(@Nullable String mutexGroup) {
        if (mutexGroup == null) return null;
        for (BackgroundTaskInfo info : mActiveTasks.values()) {
            // 已排队的任务不算正在运行的冲突
            if (info.isQueued()) continue;
            if (info.isUniqueTask() && info.getTaskType() != BackgroundTask.TaskType.DOWNLOAD) {
                String otherGroup = info.getMutexGroup();
                if (mutexGroup.equals(otherGroup)) {
                    return info;
                }
            }
        }
        return null;
    }

    @Nullable
    public BackgroundTaskInfo getActiveUniqueNonDownloadTask() {
        for (BackgroundTaskInfo info : mActiveTasks.values()) {
            // 已排队的任务不算正在运行
            if (info.isQueued()) continue;
            if (info.isUniqueTask() && info.getTaskType() != BackgroundTask.TaskType.DOWNLOAD) {
                return info;
            }
        }
        return null;
    }
    
    /**
     * 更新任务进度
     */
    public void updateTaskProgress(@NonNull String taskId, int current, int total) {
        updateTaskProgress(taskId, current, total, null);
    }

    public void updateTaskProgress(@NonNull String taskId, int current, int total, @Nullable String detail) {
        BackgroundTaskInfo taskInfo = mActiveTasks.get(taskId);
        if (taskInfo != null) {
            taskInfo.setCurrentProgress(current);
            taskInfo.setTotalProgress(total);
            taskInfo.setProgressDetail(detail);
            
            // 更新通知栏进度
            BackgroundTaskManager.getInstance().updateTaskProgress(
                taskInfo.getTaskName(), 
                taskInfo.getTaskDescription(),
                current, 
                total
            );
            savePersistedTasksAsync();
            notifyTaskProgressChanged(taskId);
        }
    }

    public void updateTaskLogFile(@NonNull String taskId, @Nullable File logFile) {
        BackgroundTaskInfo taskInfo = mActiveTasks.get(taskId);
        if (taskInfo != null) {
            taskInfo.setLogFile(logFile);
        }
    }

    public void appendTaskLog(@NonNull String taskId, @NonNull String message) {
        BackgroundTaskInfo taskInfo = mActiveTasks.get(taskId);
        if (taskInfo != null) {
            taskInfo.appendLog(message);
            savePersistedTasksAsync();
            notifyTaskProgressChanged(taskId);
        }
    }
    
    /**
     * 标记任务完成
     */
    public void markTaskCompleted(@NonNull String taskId) {
        BackgroundTaskInfo taskInfo = mActiveTasks.remove(taskId);
        if (taskInfo != null) {
            taskInfo.setQueued(false);
            taskInfo.setCompleted(true);

            // 添加到已完成任务列表
            mCompletedTasks.put(taskId, taskInfo);
            evictCompletedIfOverLimit();

            savePersistedTasksAsync();
            notifyTaskStateChanged(taskId);
            notifyUniqueSlotFreed();
        }
    }

    /**
     * 标记任务取消
     */
    public void markTaskCancelled(@NonNull String taskId) {
        BackgroundTaskInfo taskInfo = mActiveTasks.remove(taskId);
        if (taskInfo != null) {
            taskInfo.setQueued(false);
            taskInfo.setCancelled(true);

            // 添加到已完成任务列表
            mCompletedTasks.put(taskId, taskInfo);
            evictCompletedIfOverLimit();

            savePersistedTasksAsync();
            notifyTaskStateChanged(taskId);
            notifyUniqueSlotFreed();
        }
    }

    /**
     * 标记任务出错
     */
    public void markTaskError(@NonNull String taskId, @Nullable String errorMessage) {
        BackgroundTaskInfo taskInfo = mActiveTasks.remove(taskId);
        if (taskInfo != null) {
            taskInfo.setQueued(false);
            taskInfo.setErrorMessage(errorMessage);

            // 添加到已完成任务列表
            mCompletedTasks.put(taskId, taskInfo);
            evictCompletedIfOverLimit();

            savePersistedTasksAsync();
            notifyTaskStateChanged(taskId);
            notifyUniqueSlotFreed();
        }
    }

    private void evictCompletedIfOverLimit() {
        synchronized (mCompletedTasks) {
            while (mCompletedTasks.size() > MAX_COMPLETED_TASKS) {
                Iterator<String> it = mCompletedTasks.keySet().iterator();
                if (!it.hasNext()) break;
                it.next();
                it.remove();
            }
        }
    }

    private void notifyUniqueSlotFreed() {
        UniqueWaitListener listener = mUniqueWaitListener;
        if (listener != null && !mUniqueWaitQueue.isEmpty()) {
            try {
                listener.onUniqueTaskPromotable();
            } catch (Exception ignored) {
            }
        }
    }
    
    /**
     * 暂停指定任务。
     * <p>仅当任务持有了 BackgroundTask 实例且 {@link BackgroundTask#isPausable()} 为 true 时才真正暂停；
     * 会调用任务自身的 {@code pause()} 方法，使任务内部进入暂停状态，而非仅修改 UI 标志。
     */
    public boolean pauseTask(@NonNull String taskId) {
        BackgroundTaskInfo taskInfo = mActiveTasks.get(taskId);
        if (taskInfo == null || taskInfo.isCompleted() || taskInfo.isCancelled() || taskInfo.isPaused()) {
            return false;
        }
        final BackgroundTask task = taskInfo.getTask();
        if (task == null || !task.isPausable()) {
            return false;
        }
        mControlExecutor.execute(() -> {
            if (!BackgroundTaskController.runPause(task)) {
                return;
            }
            taskInfo.setQueued(false);
            taskInfo.setPaused(true);
            savePersistedTasksAsync();
            notifyTaskStateChanged(taskId);
        });
        return true;
    }

    public void markTaskRunning(@NonNull String taskId) {
        BackgroundTaskInfo taskInfo = mActiveTasks.get(taskId);
        if (taskInfo != null) {
            taskInfo.setQueued(false);
            taskInfo.setPaused(false);
            savePersistedTasksAsync();
            notifyTaskStateChanged(taskId);
        }
    }

    public void markTaskQueued(@NonNull String taskId, @Nullable String detail) {
        BackgroundTaskInfo taskInfo = mActiveTasks.get(taskId);
        if (taskInfo != null) {
            taskInfo.setQueued(true);
            if (detail != null) {
                taskInfo.setProgressDetail(detail);
            }
            savePersistedTasksAsync();
            notifyTaskStateChanged(taskId);
        }
    }

    /**
     * 恢复指定任务。
     * <p>调用任务自身的 {@code resume()} 方法，使任务内部恢复执行；随后清除暂停标志。
     */
    public boolean resumeTask(@NonNull String taskId) {
        BackgroundTaskInfo taskInfo = mActiveTasks.get(taskId);
        if (taskInfo == null || !taskInfo.isPaused()) {
            return false;
        }
        final BackgroundTask task = taskInfo.getTask();
        mControlExecutor.execute(() -> {
            boolean ok = task == null || BackgroundTaskController.runResume(task);
            if (ok) {
                taskInfo.setPaused(false);
                savePersistedTasksAsync();
                notifyTaskStateChanged(taskId);
            }
        });
        return true;
    }

    /**
     * 取消指定任务。
     * <p>若任务仍在等待队列中（isQueued=true 且无 Future），会直接从队列和活跃表中移除；<br>
     * 若任务正在运行，会先调用任务自身的 {@code cancel()} 优雅取消，再中断 Future 兜底；<br>
     * 若 Future 已 done，按"已结束"对待，把任务直接移到已完成列表以保证 UI 一致性。
     */
    public boolean cancelTask(@NonNull String taskId) {
        BackgroundTaskInfo taskInfo = mActiveTasks.get(taskId);
        if (taskInfo == null) {
            return false;
        }
        // 排队中的 unique 任务：直接出队
        if (taskInfo.isQueued() && taskInfo.getFuture() == null) {
            boolean removed = removeFromUniqueWaitQueue(taskId);
            if (removed) {
                notifyUniqueSlotFreed();
            }
            return removed;
        }
        final BackgroundTask task = taskInfo.getTask();
        final Future<?> future = taskInfo.getFuture();
        mControlExecutor.execute(() -> {
            // 1. 先尝试任务的优雅取消（设置内部取消标志，执行循环会自动停止）
            boolean gracefullyCancelled = false;
            if (task != null) {
                gracefullyCancelled = BackgroundTaskController.runCancel(task);
            }
            // 2. 中断 Future 兜底
            boolean futureCancelled = false;
            if (future != null && !future.isDone()) {
                futureCancelled = future.cancel(true);
            }
            boolean cancelled = gracefullyCancelled || futureCancelled;
            if (cancelled || (future != null && future.isDone())) {
                mActiveTasks.remove(taskId);
                taskInfo.setCancelled(true);
                mCompletedTasks.put(taskId, taskInfo);
                evictCompletedIfOverLimit();
                savePersistedTasksAsync();
                notifyTaskStateChanged(taskId);
                notifyUniqueSlotFreed();
            }
        });
        return true;
    }

    /**
     * 为指定任务写入 BackgroundTask 实例引用，供暂停/恢复/取消调用其真实方法。
     */
    public void setTask(@NonNull String taskId, @NonNull BackgroundTask task) {
        BackgroundTaskInfo taskInfo = mActiveTasks.get(taskId);
        if (taskInfo != null) {
            taskInfo.setTask(task);
        }
    }

    /**
     * 清除所有已完成的任务
     */
    public void clearCompletedTasks() {
        mCompletedTasks.clear();
        savePersistedTasksAsync();
    }

    /**
     * 取消所有活跃任务并清空所有任务记录。
     * <p>先把所有 active 条目一次性从 mActiveTasks 拆走，再统一清空 mCompletedTasks，
     * 避免 put-then-clear 之间的 UI 监听器看到幽灵条目。
     */
    public void clearAllTasks() {
        List<BackgroundTaskInfo> snapshot = new ArrayList<>(mActiveTasks.values());
        mActiveTasks.clear();
        for (BackgroundTaskInfo info : snapshot) {
            info.setCancelled(true);
            Future<?> future = info.getFuture();
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
        mUniqueWaitQueue.clear();
        mCompletedTasks.clear();
        savePersistedTasksAsync();
        for (BackgroundTaskInfo info : snapshot) {
            notifyTaskRemoved(info.getTaskId());
        }
    }

    @NonNull
    public List<BackgroundTaskInfo> getActiveTasks() {
        return new ArrayList<>(mActiveTasks.values());
    }
    
    /**
     * 获取已完成任务列表
     */
    @NonNull
    public List<BackgroundTaskInfo> getCompletedTasks() {
        return new ArrayList<>(mCompletedTasks.values());
    }

    /**
     * 获取正在执行的任务列表（未排队的活跃任务）
     */
    @NonNull
    public List<BackgroundTaskInfo> getRunningTasks() {
        List<BackgroundTaskInfo> running = new ArrayList<>();
        for (BackgroundTaskInfo info : mActiveTasks.values()) {
            if (!info.isQueued()) {
                running.add(info);
            }
        }
        return running;
    }

    /**
     * 获取等待中的任务列表（排队中的活跃任务）
     */
    @NonNull
    public List<BackgroundTaskInfo> getWaitingTasks() {
        List<BackgroundTaskInfo> waiting = new ArrayList<>();
        for (BackgroundTaskInfo info : mActiveTasks.values()) {
            if (info.isQueued()) {
                waiting.add(info);
            }
        }
        return waiting;
    }
    
    /**
     * 获取指定任务信息
     */
    @Nullable
    public BackgroundTaskInfo getTaskInfo(@NonNull String taskId) {
        BackgroundTaskInfo taskInfo = mActiveTasks.get(taskId);
        if (taskInfo == null) {
            taskInfo = mCompletedTasks.get(taskId);
        }
        return taskInfo;
    }

    @NonNull
    public List<String> getTaskLogs(@NonNull String taskId) {
        BackgroundTaskInfo taskInfo = getTaskInfo(taskId);
        if (taskInfo != null) {
            return taskInfo.getLogMessages();
        }
        return new ArrayList<>();
    }

    @NonNull
    public List<String> getRecentTaskLogs(@NonNull String taskId, int count) {
        BackgroundTaskInfo taskInfo = getTaskInfo(taskId);
        if (taskInfo != null) {
            return taskInfo.getRecentLogs(count);
        }
        return new ArrayList<>();
    }
    
    /**
     * 获取活跃任务数量
     */
    public int getActiveTaskCount() {
        return mActiveTasks.size();
    }
    
    /**
     * 获取所有任务数量（包括活跃和已完成的）
     */
    public int getTotalTaskCount() {
        return mActiveTasks.size() + mCompletedTasks.size();
    }

    /**
     * 完全移除指定任务：同时从活跃表与已完成表删除记录。仅用于任务恢复等需要彻底清理的场景。
     * <p>若只想从已完成列表中移出，请改用 {@link #removeFromCompleted(String)}。
     */
    public void removeTask(@NonNull String taskId) {
        mActiveTasks.remove(taskId);
        mCompletedTasks.remove(taskId);
        savePersistedTasksAsync();
        notifyTaskRemoved(taskId);
    }

    /**
     * 移除所有 taskId 以指定前缀开头的任务（用于清理跨会话遗留的占位任务）。
     */
    public void removeTasksWithPrefix(@NonNull String prefix) {
        boolean removed = false;
        Iterator<String> activeIt = mActiveTasks.keySet().iterator();
        while (activeIt.hasNext()) {
            if (activeIt.next().startsWith(prefix)) {
                activeIt.remove();
                removed = true;
            }
        }
        Iterator<String> completedIt = mCompletedTasks.keySet().iterator();
        while (completedIt.hasNext()) {
            if (completedIt.next().startsWith(prefix)) {
                completedIt.remove();
                removed = true;
            }
        }
        if (removed) {
            savePersistedTasksAsync();
            notifyTaskRemoved(prefix);
        }
    }

    private File createTaskLogFile(@NonNull String taskId) {
        File file = new File(mLogDir, taskId + ".log");
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 异步持久化任务状态。高频调用（如每个文件的进度更新）会合并为一次磁盘写入：
     * 只要上一次持久化尚未完成，新的变更只置脏标志，由磁盘线程顺延合并处理。
     */
    private void savePersistedTasksAsync() {
        synchronized (mPersistLock) {
            if (mPersistDirty) {
                return;
            }
            mPersistDirty = true;
        }
        mDiskExecutor.submit(this::drainPersistedTasks);
    }

    /**
     * 磁盘线程的持久化循环：反复写入直到脏标志被消费干净，
     * 避免每个进度/日志回调都触发一次全量 JSON 序列化。
     */
    private void drainPersistedTasks() {
        while (true) {
            synchronized (mPersistLock) {
                if (!mPersistDirty) {
                    return;
                }
                mPersistDirty = false;
            }
            savePersistedTasks();
        }
    }

    private void savePersistedTasks() {
        synchronized (mPersistLock) {
            File tmpFile = new File(mStatusFile.getParentFile(), mStatusFile.getName() + ".tmp");
            try (FileWriter writer = new FileWriter(tmpFile, false)) {
                JSONObject root = new JSONObject();
                root.put("activeTasks", buildTaskArray(mActiveTasks.values()));
                root.put("completedTasks", buildTaskArray(mCompletedTasks.values()));
                writer.write(root.toString());
                writer.flush();
                if (!tmpFile.renameTo(mStatusFile)) {
                    mStatusFile.delete();
                    tmpFile.renameTo(mStatusFile);
                }
            } catch (Exception ignored) {
                if (tmpFile.exists()) {
                    tmpFile.delete();
                }
            }
        }
    }

    private JSONArray buildTaskArray(@NonNull Iterable<BackgroundTaskInfo> taskInfos) throws JSONException {
        JSONArray array = new JSONArray();
        for (BackgroundTaskInfo info : taskInfos) {
            JSONObject object = new JSONObject();
            object.put("taskId", info.getTaskId());
            object.put("taskName", info.getTaskName());
            object.put("taskDescription", info.getTaskDescription());
            object.put("taskType", info.getTaskType().name());
            object.put("uniqueTask", info.isUniqueTask());
            object.put("currentProgress", info.getCurrentProgress());
            object.put("totalProgress", info.getTotalProgress());
            object.put("progressDetail", info.getProgressDetail());
            object.put("startTime", info.getStartTime());
            object.put("isCompleted", info.isCompleted());
            object.put("isCancelled", info.isCancelled());
            object.put("isQueued", info.isQueued());
            object.put("errorMessage", info.getErrorMessage());
            object.put("taskClassName", info.getTaskClassName());
            object.put("taskPersistData", info.getTaskPersistData());
            File logFile = info.getLogFile();
            if (logFile != null) {
                object.put("logFileName", logFile.getName());
            }
            array.put(object);
        }
        return array;
    }

    private void restorePersistedTasks() {
        if (!mStatusFile.exists()) {
            return;
        }

        synchronized (mPersistLock) {
            try (BufferedReader reader = new BufferedReader(new FileReader(mStatusFile))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String content = sb.toString();
                if (content.trim().isEmpty()) {
                    android.util.Log.w(TAG, "background_tasks.json is empty, starting fresh");
                    return;
                }
                JSONObject root = new JSONObject(content);
                parseTaskArray(root.optJSONArray("activeTasks"), mActiveTasks);
                parseTaskArray(root.optJSONArray("completedTasks"), mCompletedTasks);
            } catch (Exception e) {
                android.util.Log.w(TAG, "Failed to restore persisted tasks, corrupt file will be removed", e);
                try {
                    mStatusFile.delete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void parseTaskArray(@Nullable JSONArray array, @NonNull Map<String, BackgroundTaskInfo> target) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                continue;
            }
            String taskId = object.optString("taskId", null);
            String taskName = object.optString("taskName", null);
            if (taskId == null || taskName == null) {
                continue;
            }
            String taskDescription = object.optString("taskDescription", null);
            BackgroundTask.TaskType taskType;
            try {
                taskType = BackgroundTask.TaskType.valueOf(object.optString("taskType", BackgroundTask.TaskType.OTHER.name()));
            } catch (IllegalArgumentException e) {
                taskType = BackgroundTask.TaskType.OTHER;
            }
            boolean uniqueTask = object.optBoolean("uniqueTask", false);
            String taskClassName = object.optString("taskClassName", taskName);
            String taskPersistData = object.optString("taskPersistData", null);
            BackgroundTaskInfo taskInfo = new BackgroundTaskInfo(taskId, taskName, taskDescription,
                    null, taskType, uniqueTask, taskClassName, taskPersistData,
                    null, object.optLong("startTime", System.currentTimeMillis()));
            taskInfo.setCurrentProgress(object.optInt("currentProgress", taskInfo.getCurrentProgress()));
            taskInfo.setTotalProgress(object.optInt("totalProgress", taskInfo.getTotalProgress()));
            taskInfo.setProgressDetail(object.optString("progressDetail", taskInfo.getProgressDetail()));
            taskInfo.setCompleted(object.optBoolean("isCompleted", false));
            taskInfo.setCancelled(object.optBoolean("isCancelled", false));
            taskInfo.setQueued(object.optBoolean("isQueued", false));
            taskInfo.setErrorMessage(object.optString("errorMessage", null));
            String logFileName = object.optString("logFileName", null);
            if (logFileName != null) {
                File logFile = new File(mLogDir, logFileName);
                taskInfo.setLogFile(logFile);
                loadTaskLogMessages(taskInfo);
            }
            target.put(taskId, taskInfo);
        }
    }

    private void loadTaskLogMessages(@NonNull BackgroundTaskInfo taskInfo) {
        File logFile = taskInfo.getLogFile();
        if (logFile == null || !logFile.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                taskInfo.addLogMessage(line);
            }
        } catch (IOException ignored) {
            // Ignore load errors
        }
    }
}