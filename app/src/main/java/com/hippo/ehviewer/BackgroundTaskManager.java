package com.hippo.ehviewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import android.net.Uri;
import android.os.Process;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.task.impl.CompressSelectedGalleriesTask;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadLogger;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.task.BackgroundTask;
import com.hippo.ehviewer.task.BackgroundTaskRunner;
import com.hippo.ehviewer.task.MergeDuplicateGalleryTask;
import com.hippo.ehviewer.task.TaskRegistry;
import com.hippo.ehviewer.service.BackgroundTaskService;
import com.hippo.ehviewer.ui.task.BackgroundTaskInfo;
import com.hippo.ehviewer.ui.task.BackgroundTaskStatusManager;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.lib.yorozuya.thread.PriorityThreadFactory;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台任务管理器
 * 提供多线程处理和数据库单线程操作的能力
 */
public class BackgroundTaskManager {
    
    private static final String TAG = BackgroundTaskManager.class.getSimpleName();
    private static final String CHANNEL_ID = "eh_background_tasks";
    private static final int NOTIFICATION_ID = 1001;
    private static final int IO_TASK_QUEUE_CAPACITY = 500;
    
    private static BackgroundTaskManager sInstance;
    
    private final Context mContext;
    private final Handler mMainHandler;

    private final Map<String, BackgroundTaskFactory> mTaskFactoryMap = new ConcurrentHashMap<>();
    
    // CPU密集型任务线程池（用于文件扫描、压缩、合并等）
    private final ThreadPoolExecutor mCpuExecutor;
    
    // 数据库操作单线程执行器（确保数据库操作串行化）
    private final ExecutorService mDbExecutor;
    
    // IO密集型任务线程池（用于网络或文件IO）
    private final ThreadPoolExecutor mIoExecutor;
    private volatile int mBackgroundConcurrentTasks;
    
    // 网络线程池（专门用于与Eh交互）
    private final ExecutorService mNetworkExecutor;
    
    private NotificationManager mNotificationManager;
    private boolean mForegroundServiceRunning = false;
    private final AtomicInteger mActiveTaskCount = new AtomicInteger(0);
    private final AtomicInteger mNotificationTaskIndex = new AtomicInteger(0);
    
    // 下载日志记录器
    private final DownloadLogger mDownloadLogger;
    
    // 任务状态管理器
    private final BackgroundTaskStatusManager mTaskStatusManager;

    public interface BackgroundTaskFactory {
        @Nullable
        BackgroundTask create(@NonNull Context context, @NonNull String taskId, @Nullable String persistData);
    }
    
    public static synchronized void initialize(Context context) {
        if (sInstance == null) {
            sInstance = new BackgroundTaskManager(context.getApplicationContext());
            sInstance.recoverPersistedTasks();
        }
    }
    
    public static BackgroundTaskManager getInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("BackgroundTaskManager not initialized");
        }
        return sInstance;
    }

    public void registerTaskFactory(@NonNull String taskClassName, @NonNull BackgroundTaskFactory factory) {
        mTaskFactoryMap.put(taskClassName, factory);
    }

    private void recoverPersistedTasks() {
        List<BackgroundTaskInfo> activeTasks = new ArrayList<>(mTaskStatusManager.getActiveTasks());
        for (BackgroundTaskInfo info : activeTasks) {
            if (info.isCompleted() || info.isCancelled() || info.getFuture() != null) {
                continue;
            }
            if (info.getTaskType() == BackgroundTask.TaskType.DOWNLOAD) {
                String persistData = info.getTaskPersistData();
                long gid = -1;
                if (persistData != null) {
                    try {
                        gid = Long.parseLong(persistData);
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (gid >= 0) {
                    DownloadInfo downloadInfo = EhDB.getDownloadInfo(gid);
                    if (downloadInfo != null && downloadInfo.state == DownloadInfo.STATE_FINISH) {
                        mTaskStatusManager.markTaskCompleted(info.getTaskId());
                    } else {
                        mTaskStatusManager.markTaskCancelled(info.getTaskId());
                    }
                } else {
                    mTaskStatusManager.markTaskCancelled(info.getTaskId());
                }
                continue;
            }
            String className = info.getTaskClassName();
            String persistData = info.getTaskPersistData();
            if (persistData == null || className == null) {
                mTaskStatusManager.markTaskError(info.getTaskId(), mContext.getString(R.string.background_task_not_recoverable));
                continue;
            }
            BackgroundTaskFactory factory = mTaskFactoryMap.get(className);
            if (factory == null) {
                mTaskStatusManager.markTaskError(info.getTaskId(), mContext.getString(R.string.background_task_not_recoverable));
                continue;
            }
            BackgroundTask task = factory.create(mContext, info.getTaskId(), persistData);
            if (task != null) {
                mTaskStatusManager.removeTask(info.getTaskId());
                submitBackgroundTask(task);
            } else {
                mTaskStatusManager.markTaskError(info.getTaskId(), mContext.getString(R.string.background_task_not_recoverable));
            }
        }
    }

    private BackgroundTaskManager(Context context) {
        mContext = context;
        mMainHandler = new Handler(Looper.getMainLooper());

        // 初始化下载日志记录器
        mDownloadLogger = DownloadLogger.getInstance();

        // 初始化任务状态管理器
        BackgroundTaskStatusManager.initialize(context);
        mTaskStatusManager = BackgroundTaskStatusManager.getInstance();
        // 注册互斥等待队列监听器：在槽位释放时自动接力
        mTaskStatusManager.setUniqueWaitListener(this::promoteNextUniqueTask);

        // 注册可恢复任务工厂
        registerTaskFactory(CompressSelectedGalleriesTask.class.getName(), (ctx, taskId, persistData) ->
                CompressSelectedGalleriesTask.restore(ctx, taskId, persistData));
        registerTaskFactory(MergeDuplicateGalleryTask.class.getName(), (ctx, taskId, persistData) ->
            MergeDuplicateGalleryTask.restore(ctx, taskId, persistData));
        registerTaskFactory(com.hippo.ehviewer.task.ProgressiveScanTask.class.getName(), (ctx, taskId, persistData) ->
            new com.hippo.ehviewer.task.ProgressiveScanTask(ctx));
        registerTaskFactory(com.hippo.ehviewer.task.ProgressiveMergeTask.class.getName(), (ctx, taskId, persistData) ->
            new com.hippo.ehviewer.task.ProgressiveMergeTask(ctx, -1L, java.util.Collections.emptyList(), taskId));
        
        // CPU密集型任务线程池（用于文件扫描、压缩、合并等）
        // 使用 THREAD_PRIORITY_DEFAULT 确保非网络后台任务不被系统过度降速
        int cpuCount = Runtime.getRuntime().availableProcessors();
        int corePoolSize = Math.max(2, cpuCount);
        int maxPoolSize = cpuCount * 2;
        mCpuExecutor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new PriorityThreadFactory("BgTask-CPU", Process.THREAD_PRIORITY_DEFAULT)
        );
        
        // 数据库执行器：单线程确保数据库操作串行化
        mDbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "BackgroundTaskManager-DB");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        
        // IO密集型任务线程池（用于文件IO、网络请求等）
        // 使用可配置并发和有界队列，避免后台任务无限堆积导致资源耗尽
        mBackgroundConcurrentTasks = Settings.getBackgroundConcurrentTasks();
        mIoExecutor = new ThreadPoolExecutor(
            mBackgroundConcurrentTasks,
            mBackgroundConcurrentTasks,
                60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(IO_TASK_QUEUE_CAPACITY),
                r -> {
                    Thread thread = new Thread(r, "BackgroundTaskManager-IO");
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                }
        );
        
        // 网络线程池：专门用于与Eh交互
        int networkCorePoolSize = Math.max(2, cpuCount / 2);
        int networkMaxPoolSize = cpuCount;
        mNetworkExecutor = new ThreadPoolExecutor(
                networkCorePoolSize,
                networkMaxPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread thread = new Thread(r, "BackgroundTaskManager-Network");
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                }
        );
        
        // 初始化通知通道
        initNotificationChannel();
        
        // 启动互斥死锁定期检查
        startDeadlockCheck();
    }
    
    private void initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationManager = mContext.getSystemService(NotificationManager.class);
            if (mNotificationManager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        mContext.getString(R.string.background_tasks_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription(mContext.getString(R.string.background_tasks_channel_description));
                channel.setShowBadge(false);
                mNotificationManager.createNotificationChannel(channel);
            }
        } else {
            mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }
    }

    @Nullable
    private BackgroundTaskInfo getNextActiveTaskInfo() {
        List<BackgroundTaskInfo> activeTasks = mTaskStatusManager.getActiveTasks();
        if (activeTasks.isEmpty()) {
            return null;
        }
        int index = Math.abs(mNotificationTaskIndex.getAndIncrement());
        return activeTasks.get(index % activeTasks.size());
    }

    @NonNull
    private String getRunningTasksTitle(int count) {
        if (count <= 0) {
            return mContext.getString(R.string.background_task_running);
        }
        return mContext.getResources().getQuantityString(R.plurals.background_tasks_running, count, count);
    }
    
    /**
     * 提交CPU密集型任务（如图像处理、计算等）
     */
    public <T> Future<T> submitCpuTask(Callable<T> task) {
        return mCpuExecutor.submit(task);
    }
    
    /**
     * 提交CPU密集型任务（无返回值）
     */
    public Future<?> submitCpuTask(Runnable task) {
        return mCpuExecutor.submit(task);
    }
    
    /**
     * 提交数据库操作任务（确保单线程执行）
     */
    public <T> Future<T> submitDbTask(Callable<T> task) {
        return mDbExecutor.submit(task);
    }
    
    /**
     * 提交数据库操作任务（无返回值）
     */
    public Future<?> submitDbTask(Runnable task) {
        return mDbExecutor.submit(task);
    }
    
    /**
     * 提交IO密集型任务（如文件读写、网络请求等）
     */
    public <T> Future<T> submitIoTask(Callable<T> task) {
        return mIoExecutor.submit(task);
    }
    
    /**
     * 提交IO密集型任务（无返回值）
     */
    public Future<?> submitIoTask(Runnable task) {
        return mIoExecutor.submit(task);
    }

    private boolean canAcceptIoTask() {
        if (mIoExecutor.getActiveCount() < mBackgroundConcurrentTasks) {
            return true;
        }
        return mIoExecutor.getQueue().remainingCapacity() > 0;
    }

    public synchronized void applyBackgroundConcurrentTaskSetting() {
        int newConcurrent = Settings.getBackgroundConcurrentTasks();
        int oldConcurrent = mBackgroundConcurrentTasks;
        if (newConcurrent == oldConcurrent) {
            return;
        }

        // ThreadPoolExecutor 要求 corePoolSize <= maximumPoolSize，调整顺序要根据增减方向处理。
        if (newConcurrent > oldConcurrent) {
            mIoExecutor.setMaximumPoolSize(newConcurrent);
            mIoExecutor.setCorePoolSize(newConcurrent);
        } else {
            mIoExecutor.setCorePoolSize(newConcurrent);
            mIoExecutor.setMaximumPoolSize(newConcurrent);
        }

        mBackgroundConcurrentTasks = newConcurrent;
        Log.i(TAG, "Apply background concurrent tasks: " + oldConcurrent + " -> " + newConcurrent);
    }

    /**
     * 提交已有的FutureTask到CPU线程池
     */
    public Future<?> submitCpuFutureTask(java.util.concurrent.FutureTask<?> task) {
        mCpuExecutor.execute(task);
        return task;
    }

    /**
     * 提交已有的FutureTask到IO线程池
     */
    public Future<?> submitIoFutureTask(java.util.concurrent.FutureTask<?> task) {
        mIoExecutor.execute(task);
        return task;
    }

    /**
     * 判断是否为CPU密集型任务（压缩、合并、扫描、更新等）
     * 这些任务不依赖网络，应分配到CPU线程池以获得更高的执行优先级
     */
    private static boolean isCpuBoundTask(BackgroundTask.TaskType type) {
        switch (type) {
            case MERGE:
            case CLEANUP:
            case SCAN:
            case UPDATE:
                return true;
            default:
                return false;
        }
    }

    private boolean canAcceptCpuTask() {
        return mCpuExecutor.getQueue().remainingCapacity() > 0;
    }
    
    /**
     * 提交网络任务（专门用于与Eh交互）
     */
    public <T> Future<T> submitNetworkTask(Callable<T> task) {
        return mNetworkExecutor.submit(task);
    }
    
    /**
     * 提交网络任务（无返回值）
     */
    public Future<?> submitNetworkTask(Runnable task) {
        return mNetworkExecutor.submit(task);
    }

    /**
     * 后台任务提交结果
     */
    public static class TaskHandle {
        public final String taskId;
        public final Future<?> future;

        public TaskHandle(@NonNull String taskId, @NonNull Future<?> future) {
            this.taskId = taskId;
            this.future = future;
        }
    }

    /**
     * 提交BackgroundTask并接入任务管理与通知。
     * <p>当 unique 任务与已有互斥任务冲突时，新任务进入等待队列；当前置任务结束后自动接力启动。
     */
    @NonNull
    public TaskHandle submitBackgroundTask(@NonNull BackgroundTask task) {
        final String taskId = task.getTaskId();
        final String taskName = task.getTaskName();
        final String taskDescription = task.getTaskDescription();
        final BackgroundTask.TaskType taskType = task.getTaskType();
        final boolean cpuBound = isCpuBoundTask(taskType);

        if (cpuBound) {
            if (!canAcceptCpuTask()) {
                String rejectedTaskId = mTaskStatusManager.addTask(taskId, taskName, taskDescription, null,
                        taskType, task.isUniqueTask(), task.getTaskClassName(), task.getTaskPersistData());
                if (rejectedTaskId != null) {
                    mTaskStatusManager.markTaskError(rejectedTaskId, mContext.getString(R.string.background_task_queue_full));
                }
                return new TaskHandle(taskId, createNoOpFuture());
            }
        } else {
            if (!canAcceptIoTask()) {
                String rejectedTaskId = mTaskStatusManager.addTask(taskId, taskName, taskDescription, null,
                        taskType, task.isUniqueTask(), task.getTaskClassName(), task.getTaskPersistData());
                if (rejectedTaskId != null) {
                    mTaskStatusManager.markTaskError(rejectedTaskId, mContext.getString(R.string.background_task_queue_full));
                }
                return new TaskHandle(taskId, createNoOpFuture());
            }
        }

        if (task.isUniqueTask() && taskType != BackgroundTask.TaskType.DOWNLOAD) {
            String mutexGroup = task.getMutexGroup();
            if (mutexGroup != null) {
                BackgroundTaskInfo conflict = mTaskStatusManager.getActiveConflictTask(mutexGroup);
                if (conflict != null) {
                    Log.d(TAG, "Enqueue unique task (group=" + mutexGroup + "), conflict: " + conflict.getTaskId());
                    BackgroundTaskStatusManager.PendingUniqueTask pending =
                            new BackgroundTaskStatusManager.PendingUniqueTask(task);
                    String enqueuedId = mTaskStatusManager.enqueueUniqueWaitingTask(pending);
                    if (enqueuedId == null) {
                        Log.w(TAG, "Failed to enqueue unique task (duplicate id): " + taskId);
                        return new TaskHandle(taskId, createNoOpFuture());
                    }
                    return new TaskHandle(enqueuedId, createNoOpFuture());
                }
            }
        }

        return doSubmitRunning(task, taskId, taskName, taskDescription, taskType, cpuBound);
    }

    /**
     * 实际启动任务的内部入口：构造 FutureTask、注册到状态管理器、提交到对应线程池。
     * 被 submitBackgroundTask（初次提交）和 promoteNextUniqueTask（接力启动）共用。
     */
    @NonNull
    private TaskHandle doSubmitRunning(@NonNull BackgroundTask task, @NonNull String taskId,
                                       @NonNull String taskName, @Nullable String taskDescription,
                                       @NonNull BackgroundTask.TaskType taskType, boolean cpuBound) {
        task.setProgressListener(new BackgroundTask.ProgressListener() {
            @Override
            public void onProgressChanged(int progress, @Nullable String detail) {
                int total = progress >= 0 ? 100 : -1;
                int current = progress >= 0 ? progress : -1;
                mTaskStatusManager.updateTaskProgress(taskId, current, total, detail);
            }

            @Override
            public void onProgressChanged(int current, int total, @Nullable String detail) {
                mTaskStatusManager.updateTaskProgress(taskId, current, total, detail);
            }

            @Override
            public void onCompleted() {
                mTaskStatusManager.updateTaskProgress(taskId, 100, 100, taskDescription);
            }

            @Override
            public void onError(@NonNull Throwable error) {
                // 统一记录最终异常：写入 logcat 与任务日志文件，方便后期维护
                String errorMessage = error.getMessage();
                if (errorMessage == null || errorMessage.isEmpty()) {
                    errorMessage = error.toString();
                }
                Log.e(TAG, "Background task error: " + taskId + " (" + taskName + ")", error);
                mTaskStatusManager.appendTaskLog(taskId, "任务失败: " + errorMessage);
                mTaskStatusManager.markTaskError(taskId, errorMessage);
            }
        });

        java.util.concurrent.FutureTask<?> futureTask = new java.util.concurrent.FutureTask<>(() -> {
            mTaskStatusManager.markTaskRunning(taskId);
            startForegroundTask(taskName, taskDescription);
            try {
                Throwable error = BackgroundTaskRunner.runBlockingExecute(task);
                if (error == null) {
                    mTaskStatusManager.markTaskCompleted(taskId);
                } else {
                    // 任务被取消（优雅取消或中断）时标记为已取消而非失败
                    BackgroundTaskInfo info = mTaskStatusManager.getTaskInfo(taskId);
                    boolean cancelled = error instanceof java.util.concurrent.CancellationException
                            || error instanceof InterruptedException
                            || (info != null && info.isCancelled());
                    if (cancelled) {
                        mTaskStatusManager.markTaskCancelled(taskId);
                    } else {
                        // 统一记录最终异常：写入 logcat 与任务日志文件，方便后期维护
                        String errorMessage = error.getMessage();
                        if (errorMessage == null || errorMessage.isEmpty()) {
                            errorMessage = error.toString();
                        }
                        Log.e(TAG, "Background task failed: " + taskId + " (" + taskName + ")", error);
                        mTaskStatusManager.appendTaskLog(taskId, "任务失败: " + errorMessage);
                        mTaskStatusManager.markTaskError(taskId, errorMessage);
                    }
                }
            } finally {
                endForegroundTask(taskName);
            }
            return null;
        });

        String registeredTaskId = mTaskStatusManager.addTask(taskId, taskName, taskDescription, futureTask,
                taskType, task.isUniqueTask(), task.getTaskClassName(), task.getTaskPersistData(),
                task.getMutexGroup());
        if (registeredTaskId == null) {
            return new TaskHandle(taskId, createNoOpFuture());
        }
        // 写回任务实例引用，供暂停/恢复/取消调用其真实 suspend 方法
        mTaskStatusManager.setTask(registeredTaskId, task);
        mTaskStatusManager.markTaskQueued(registeredTaskId, null);
        try {
            if (cpuBound) {
                submitCpuFutureTask(futureTask);
            } else {
                submitIoFutureTask(futureTask);
            }
        } catch (RejectedExecutionException e) {
            mTaskStatusManager.markTaskError(registeredTaskId, mContext.getString(R.string.background_task_queue_full));
            return new TaskHandle(taskId, createNoOpFuture());
        }

        return new TaskHandle(taskId, futureTask);
    }

    /**
     * 从互斥等待队列中取出队首任务并真正启动。
     * 该方法通常由 BackgroundTaskStatusManager 在 unique 槽位释放时回调。
     */
    private void promoteNextUniqueTask() {
        BackgroundTaskStatusManager.PendingUniqueTask pending =
                mTaskStatusManager.pollNextUniqueWaitingTask();
        if (pending == null) {
            return;
        }
        BackgroundTask task = pending.getTask();
        boolean cpuBound = isCpuBoundTask(pending.getTaskType());
        Log.d(TAG, "Promote unique task: " + pending.getTaskId());
        doSubmitRunning(task, pending.getTaskId(), pending.getTaskName(), pending.getTaskDescription(),
                pending.getTaskType(), cpuBound);
    }

    private Future<?> createNoOpFuture() {
        java.util.concurrent.FutureTask<?> futureTask = new java.util.concurrent.FutureTask<>(() -> null);
        futureTask.run();
        return futureTask;
    }
    
    /**
     * 开始一个前台任务（显示通知）
     */
    public void startForegroundTask(String taskName, @Nullable String taskDescription) {
        int activeCount = mActiveTaskCount.incrementAndGet();
        Log.d(TAG, "Start foreground task: " + taskName + ", active tasks: " + activeCount);
        
        if (activeCount == 1) {
            // 第一个前台任务，启动 BackgroundTaskService 持有 WakeLock 防休眠
            boolean serviceStarted = BackgroundTaskService.start(mContext, taskName, 1);
            if (!serviceStarted) {
                Log.w(TAG, "Foreground service failed to start (possibly background restriction), "
                        + "task will run without WakeLock protection");
            }
            // 同时显示通知
            showForegroundNotification(taskName, taskDescription);
        } else {
            // 更新通知和 Service
            updateForegroundNotification(taskName, taskDescription);
            updateService(taskName, activeCount);
        }
    }
    
    /**
     * 结束一个前台任务
     */
    public void endForegroundTask(String taskName) {
        int activeCount = mActiveTaskCount.decrementAndGet();
        Log.d(TAG, "End foreground task: " + taskName + ", active tasks: " + activeCount);
        
        if (activeCount <= 0) {
            mActiveTaskCount.set(0);
            // 所有任务完成，停止 BackgroundTaskService 释放 WakeLock
            BackgroundTaskService.stop(mContext);
            showIdleNotification();
        } else {
            updateForegroundNotification(null, null);
            updateService(taskName, activeCount);
        }
    }
    
    /**
     * 显示前台通知
     */
    private void showForegroundNotification(@Nullable String taskName, @Nullable String taskDescription) {
        showForegroundNotification(taskName, taskDescription, -1, -1);
    }
    
    /**
     * 显示前台通知（带进度）
     */
    private void showForegroundNotification(@Nullable String taskName, @Nullable String taskDescription, 
                                          int currentProgress, int totalProgress) {
        int activeTasks = Math.max(mTaskStatusManager.getActiveTaskCount(), mActiveTaskCount.get());
        BackgroundTaskInfo activeInfo = getNextActiveTaskInfo();

        String title = getRunningTasksTitle(activeTasks);
        String line = activeInfo != null ? activeInfo.getTaskName()
            : (taskName != null ? taskName : mContext.getString(R.string.background_task_running));
        String detail = activeInfo != null ? activeInfo.getProgressDetail() : taskDescription;
        int displayCurrent = activeInfo != null ? activeInfo.getCurrentProgress() : currentProgress;
        int displayTotal = activeInfo != null ? activeInfo.getTotalProgress() : totalProgress;

        String text = detail != null ? line + " - " + detail : line;
        
        Intent intent = new Intent(mContext, com.hippo.ehviewer.ui.task.BackgroundTaskActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent);
        
        // 添加进度显示
        if (displayCurrent >= 0 && displayTotal > 0) {
            builder.setProgress(displayTotal, displayCurrent, false);
            // 更新文本显示进度
            String progressText = mContext.getString(R.string.task_progress_format, 
                    displayCurrent, displayTotal, (displayCurrent * 100) / displayTotal);
            builder.setContentText(text + " - " + progressText);
        } else {
            builder.setProgress(0, 0, true); // 不确定进度
        }
        
        Notification notification = builder.build();
        
        if (mNotificationManager != null) {
            mNotificationManager.notify(NOTIFICATION_ID, notification);
        }
    }
    
    /**
     * 更新前台通知
     */
    private void updateForegroundNotification(@Nullable String taskName, @Nullable String taskDescription) {
        updateForegroundNotification(taskName, taskDescription, -1, -1);
    }
    
    /**
     * 更新前台通知（带进度）
     */
    private void updateForegroundNotification(@Nullable String taskName, @Nullable String taskDescription,
                                            int currentProgress, int totalProgress) {
        int activeTasks = mActiveTaskCount.get();
        if (activeTasks <= 0) {
            showIdleNotification();
            return;
        }

        int runningCount = Math.max(mTaskStatusManager.getActiveTaskCount(), activeTasks);
        BackgroundTaskInfo activeInfo = getNextActiveTaskInfo();

        String title = getRunningTasksTitle(runningCount);
        String line = activeInfo != null ? activeInfo.getTaskName()
            : (taskName != null ? taskName : mContext.getString(R.string.background_task_running));
        String detail = activeInfo != null ? activeInfo.getProgressDetail() : taskDescription;
        int displayCurrent = activeInfo != null ? activeInfo.getCurrentProgress() : currentProgress;
        int displayTotal = activeInfo != null ? activeInfo.getTotalProgress() : totalProgress;

        String text = detail != null ? line + " - " + detail : line;
        
        Intent intent = new Intent(mContext, com.hippo.ehviewer.ui.task.BackgroundTaskActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                mContext, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent);
        
        // 添加进度显示
        if (displayCurrent >= 0 && displayTotal > 0) {
            builder.setProgress(displayTotal, displayCurrent, false);
            // 更新文本显示进度
            String progressText = mContext.getString(R.string.task_progress_format, 
                    displayCurrent, displayTotal, (displayCurrent * 100) / displayTotal);
            builder.setContentText(text + " - " + progressText);
        } else {
            builder.setProgress(0, 0, true); // 不确定进度
        }
        
        Notification notification = builder.build();
        
        if (mNotificationManager != null) {
            mNotificationManager.notify(NOTIFICATION_ID, notification);
        }
    }
    
    /**
     * 显示空闲状态通知（没有正在执行的任务）
     */
    private void showIdleNotification() {
        if (mNotificationManager != null) {
            String title = mContext.getString(R.string.background_task_running);
            String content = mContext.getString(R.string.no_background_tasks);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(false)
                    .setOnlyAlertOnce(true);

            Notification notification = builder.build();
            mNotificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    /**
     * 隐藏前台通知（如果需要完全取消当前通知）
     */
    private void hideForegroundNotification() {
        if (mNotificationManager != null) {
            mNotificationManager.cancel(NOTIFICATION_ID);
        }
    }
    
    /**
     * 更新任务进度通知
     * @param taskName 任务名称
     * @param taskDescription 任务描述
     * @param currentProgress 当前进度
     * @param totalProgress 总进度
     */
    public void updateTaskProgress(@Nullable String taskName, @Nullable String taskDescription,
                                  int currentProgress, int totalProgress) {
        if (mActiveTaskCount.get() > 0) {
            updateForegroundNotification(taskName, taskDescription, currentProgress, totalProgress);
        }
    }
    
    /**
     * 在UI线程执行任务
     */
    public void runOnUiThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mMainHandler.post(runnable);
        }
    }
    
    /**
     * 获取任务状态管理器
     */
    public BackgroundTaskStatusManager getTaskStatusManager() {
        return mTaskStatusManager;
    }

    /**
     * 暂停指定任务
     */
    public boolean pauseTask(@NonNull String taskId) {
        return mTaskStatusManager.pauseTask(taskId);
    }

    /**
     * 恢复指定任务
     */
    public boolean resumeTask(@NonNull String taskId) {
        return mTaskStatusManager.resumeTask(taskId);
    }

    /**
     * 取消指定任务
     */
    public boolean cancelTask(@NonNull String taskId) {
        return mTaskStatusManager.cancelTask(taskId);
    }

    /**
     * 清空已完成的任务
     */
    public void clearCompletedTasks() {
        mTaskStatusManager.clearCompletedTasks();
    }

    /**
     * 更新 BackgroundTaskService（传当前任务数供通知显示）
     */
    private void updateService(@Nullable String taskName, int activeCount) {
        try {
            BackgroundTaskService.start(mContext, taskName, activeCount);
        } catch (Exception e) {
            Log.w(TAG, "Failed to update BackgroundTaskService", e);
        }
    }

    /**
     * 强力停止并清空所有后台任务
     */
    public void forceStopAllTasks() {
        mTaskStatusManager.clearAllTasks();
        mActiveTaskCount.set(0);
        BackgroundTaskService.stop(mContext);
        hideForegroundNotification();
    }

    /**
     * 移除指定任务（从活跃和已完成列表中删除）
     */
    public void removeTask(@NonNull String taskId) {
        mTaskStatusManager.removeTask(taskId);
    }

    /**
     * 指定任务是否支持暂停（供 UI / Web API 显示控制能力）
     */
    public boolean isTaskPausable(@NonNull String taskId) {
        BackgroundTaskInfo info = mTaskStatusManager.getTaskInfo(taskId);
        return info != null && info.isPausable();
    }

    /**
     * 按类名创建后台任务并提交执行（供 Web API「创建任务」使用）。
     * <p>无参数任务（TaskRegistry 中 requiresParams=false）通过反射调用 {@code (Context)} 构造器创建；
     * 带参数任务支持约定格式：{@code gids:[long...]}（压缩/删除/范围下载）、{@code uri}（导入下载列表）、
     * {@code filePath}（导入数据库/旧版数据）。
     *
     * @return 提交结果；类名未知、参数缺失或实例化失败时返回 null
     */
    @Nullable
    public TaskHandle createTaskByClassName(@NonNull String className, @Nullable JSONObject params) {
        BackgroundTask task = instantiateTaskByClassName(className, params);
        if (task == null) {
            return null;
        }
        return submitBackgroundTask(task);
    }

    @Nullable
    private BackgroundTask instantiateTaskByClassName(@NonNull String className, @Nullable JSONObject params) {
        try {
            TaskRegistry.TaskMetadata meta = TaskRegistry.INSTANCE.getMetadata(className);
            boolean requiresParams = meta != null && meta.getRequiresParams();
            if (requiresParams) {
                return params == null ? null : instantiateParamTask(className, params);
            }
            // 无参数任务：反射调用 (Context) 构造器
            Class<?> taskClass = Class.forName(className);
            Constructor<?> ctor = taskClass.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            return (BackgroundTask) ctor.newInstance(mContext);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to instantiate background task: " + className, e);
            return null;
        }
    }

    @Nullable
    private BackgroundTask instantiateParamTask(@NonNull String className, @NonNull JSONObject params) {
        // gids 数组：压缩 / 删除 / 范围下载
        JSONArray gidsArray = params.getJSONArray("gids");
        if (gidsArray != null && !gidsArray.isEmpty()) {
            LongList gidList = new LongList();
            for (int i = 0; i < gidsArray.size(); i++) {
                gidList.add(gidsArray.getLongValue(i));
            }
            if ("com.hippo.ehviewer.task.impl.StartRangeDownloadTask".equals(className)) {
                return new com.hippo.ehviewer.task.impl.StartRangeDownloadTask(mContext, gidList);
            }
            if ("com.hippo.ehviewer.task.impl.DeleteRangeDownloadTask".equals(className)) {
                DownloadManager dm = EhApplication.getDownloadManager(mContext);
                boolean deleteFiles = Settings.isDeleteFilesOnRemoteDelete();
                if (params.containsKey("deleteFiles")) {
                    deleteFiles = params.getBooleanValue("deleteFiles");
                }
                return new com.hippo.ehviewer.task.impl.DeleteRangeDownloadTask(mContext, dm, gidList, deleteFiles, null);
            }
            if ("com.hippo.ehviewer.task.impl.CompressSelectedGalleriesTask".equals(className)) {
                List<DownloadInfo> infos = new ArrayList<>();
                for (int i = 0; i < gidList.size(); i++) {
                    DownloadInfo info = EhDB.getDownloadInfo(gidList.get(i));
                    if (info != null) {
                        infos.add(info);
                    }
                }
                if (infos.isEmpty()) {
                    return null;
                }
                return new com.hippo.ehviewer.task.impl.CompressSelectedGalleriesTask(mContext, infos);
            }
        }
        // uri：导入下载列表
        String uri = params.getString("uri");
        if (uri != null && !uri.isEmpty()) {
            if ("com.hippo.ehviewer.task.ImportDownloadItemsTask".equals(className)) {
                return new com.hippo.ehviewer.task.ImportDownloadItemsTask(mContext, Uri.parse(uri));
            }
        }
        // filePath：导入数据库 / 旧版数据
        String filePath = params.getString("filePath");
        if (filePath != null && !filePath.isEmpty()) {
            File file = new File(filePath);
            if ("com.hippo.ehviewer.task.impl.ImportDataTask".equals(className)) {
                return new com.hippo.ehviewer.task.impl.ImportDataTask(mContext, file);
            }
            if ("com.hippo.ehviewer.task.impl.ImportLegacyDataTask".equals(className)) {
                return new com.hippo.ehviewer.task.impl.ImportLegacyDataTask(mContext, file);
            }
        }
        return null;
    }
    
    /**
     * 提交扫描下载文件任务
     * @return Future用于等待任务完成或取消任务
     */
    public Future<?> submitScanDownloadTask(@Nullable final DownloadedFileManagerScanListener progressListener) {
        long startTime = System.currentTimeMillis();
        mDownloadLogger.logBackgroundTaskStart("ScanDownload", "扫描下载文件");
        
        String taskName = mContext.getString(R.string.settings_download_scan_download_files);
        String taskDescription = mContext.getString(R.string.settings_download_scan_download_files_summary);
        
        // 添加到任务状态管理器
        String taskId = mTaskStatusManager.addTask(taskName, taskDescription, null,
            BackgroundTask.TaskType.SCAN, true);
        if (taskId == null) {
            Log.d(TAG, "Skip scan download task, unique task running");
            return createNoOpFuture();
        }
        
        java.util.concurrent.FutureTask<?> futureTask = new java.util.concurrent.FutureTask<>(() -> {
            mTaskStatusManager.markTaskRunning(taskId);
            startForegroundTask(taskName, taskDescription);
            
            try {
                DownloadedFileManager manager = DownloadedFileManager.getInstance();
                manager.scanDownloadDirectories(new DownloadedFileManagerScanListener() {
                    @Override
                    public void onProgress(final int current, final int total) {
                        mDownloadLogger.logBackgroundTaskProgress("ScanDownload", "扫描下载文件", current, total);
                        
                        // 更新任务进度
                        mTaskStatusManager.updateTaskProgress(taskId, current, total);
                        
                        runOnUiThread(() -> {
                            if (progressListener != null) {
                                progressListener.onProgress(current, total);
                            }
                        });
                    }

                    @Override
                    public void onCompleted() {
                        long totalTime = System.currentTimeMillis() - startTime;
                        mDownloadLogger.logBackgroundTaskComplete("ScanDownload", "扫描下载文件", totalTime, true);
                        
                        // 标记任务完成
                        mTaskStatusManager.markTaskCompleted(taskId);
                        
                        runOnUiThread(() -> {
                            if (progressListener != null) {
                                progressListener.onCompleted();
                            }
                        });
                        endForegroundTask("scan_download");
                    }

                    @Override
                    public void onError(final Exception e) {
                        long totalTime = System.currentTimeMillis() - startTime;
                        mDownloadLogger.logBackgroundTaskComplete("ScanDownload", "扫描下载文件", totalTime, false);
                        mDownloadLogger.logDownloadError("ScanDownload", "扫描下载文件", e.getMessage(), e);
                        
                        // 标记任务出错
                        mTaskStatusManager.markTaskError(taskId, e.getMessage());
                        
                        runOnUiThread(() -> {
                            if (progressListener != null) {
                                progressListener.onError(e);
                            }
                        });
                        endForegroundTask("scan_download");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in scan download task", e);
                
                // 统一记录最终异常：写入任务日志文件，方便后期维护
                String errorMessage = e.getMessage();
                if (errorMessage == null || errorMessage.isEmpty()) {
                    errorMessage = e.toString();
                }
                mTaskStatusManager.appendTaskLog(taskId, "任务失败: " + errorMessage);
                // 标记任务出错
                mTaskStatusManager.markTaskError(taskId, errorMessage);
                
                endForegroundTask("scan_download");
                runOnUiThread(() -> {
                    if (progressListener != null) {
                        progressListener.onError(e);
                    }
                });
                throw e; // 重新抛出异常，以便Future能够捕获
            }
            return null;
        });

        mTaskStatusManager.markTaskQueued(taskId, null);
        try {
            submitIoFutureTask(futureTask);
        } catch (RejectedExecutionException e) {
            mTaskStatusManager.markTaskError(taskId, mContext.getString(R.string.background_task_queue_full));
            return createNoOpFuture();
        }
        return futureTask;
    }
    
    /**
     * 提交通用后台任务
     * @param taskName 任务名称（显示在通知中）
     * @param taskDescription 任务描述（显示在通知中）
     * @param task 要执行的任务
     * @return Future用于等待任务完成或取消任务
     */
    public Future<?> submitLongRunningTask(String taskName, String taskDescription, Runnable task) {
        return submitLongRunningTask(taskName, taskDescription, task, null, BackgroundTask.TaskType.OTHER, true);
    }

    public Future<?> submitLongRunningTask(String taskName, String taskDescription, Runnable task, @Nullable String existingTaskId) {
        return submitLongRunningTask(taskName, taskDescription, task, existingTaskId, BackgroundTask.TaskType.OTHER, true);
    }

    public Future<?> submitLongRunningTask(String taskName, String taskDescription, Runnable task,
                                           @Nullable String existingTaskId, @NonNull BackgroundTask.TaskType taskType,
                                           boolean uniqueTask) {
        String candidateTaskId = existingTaskId != null ? existingTaskId : java.util.UUID.randomUUID().toString();
        java.util.concurrent.FutureTask<?> futureTask = new java.util.concurrent.FutureTask<>(() -> {
            mTaskStatusManager.markTaskRunning(candidateTaskId);
            startForegroundTask(taskName, taskDescription);
            try {
                task.run();
                // 标记任务完成
                mTaskStatusManager.markTaskCompleted(candidateTaskId);
            } catch (Exception e) {
                // 统一记录最终异常：写入 logcat 与任务日志文件，方便后期维护
                String errorMessage = e.getMessage();
                if (errorMessage == null || errorMessage.isEmpty()) {
                    errorMessage = e.toString();
                }
                Log.e(TAG, "Background task failed: " + candidateTaskId + " (" + taskName + ")", e);
                mTaskStatusManager.appendTaskLog(candidateTaskId, "任务失败: " + errorMessage);
                // 标记任务出错
                mTaskStatusManager.markTaskError(candidateTaskId, errorMessage);
                throw e;
            } finally {
                endForegroundTask(taskName);
            }
            return null;
        });

        final String taskId = existingTaskId != null ? existingTaskId :
                mTaskStatusManager.addTask(candidateTaskId, taskName, taskDescription, futureTask,
                        taskType, uniqueTask, BackgroundTask.class.getName(), null);
        if (taskId == null) {
            Log.d(TAG, "Skip task, unique task running: " + taskName);
            return createNoOpFuture();
        }

        mTaskStatusManager.markTaskQueued(taskId, null);
        try {
            submitIoFutureTask(futureTask);
        } catch (RejectedExecutionException e) {
            mTaskStatusManager.markTaskError(taskId, mContext.getString(R.string.background_task_queue_full));
            return createNoOpFuture();
        }
        return futureTask;
    }

    /**
     * 提交恢复下载项任务
     * @return Future用于等待任务完成或取消任务
     */
    public Future<?> submitRestoreDownloadTask(Runnable task) {
        return submitLongRunningTask(
                mContext.getString(R.string.settings_download_restore_download_items),
                mContext.getString(R.string.settings_download_restore_download_items_summary),
                task,
                null,
                BackgroundTask.TaskType.SCAN,
                true
        );
    }

    // 互斥死锁定期检查
    private final Handler mDeadlockCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDeadlockCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkMutexDeadlock();
            int interval = Settings.getMutexDeadlockCheckInterval() * 1000;
            mDeadlockCheckHandler.postDelayed(this, interval);
        }
    };

    /**
     * 启动互斥死锁定期检查
     */
    private void startDeadlockCheck() {
        int interval = Settings.getMutexDeadlockCheckInterval() * 1000;
        mDeadlockCheckHandler.postDelayed(mDeadlockCheckRunnable, interval);
    }

    /**
     * 停止互斥死锁定期检查
     */
    private void stopDeadlockCheck() {
        mDeadlockCheckHandler.removeCallbacks(mDeadlockCheckRunnable);
    }

    /**
     * 检查互斥死锁
     */
    private void checkMutexDeadlock() {
        if (mTaskStatusManager.detectAndResolveDeadlock()) {
            promoteNextUniqueTask();
        }
    }

    /**
     * 关闭所有线程池
     */
    public void shutdown() {
        stopDeadlockCheck();
        mCpuExecutor.shutdown();
        mDbExecutor.shutdown();
        mIoExecutor.shutdown();
        mNetworkExecutor.shutdown();
        
        try {
            if (!mCpuExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                mCpuExecutor.shutdownNow();
            }
            if (!mDbExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                mDbExecutor.shutdownNow();
            }
            if (!mIoExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                mIoExecutor.shutdownNow();
            }
            if (!mNetworkExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                mNetworkExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mCpuExecutor.shutdownNow();
            mDbExecutor.shutdownNow();
            mIoExecutor.shutdownNow();
            mNetworkExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        hideForegroundNotification();
    }
}