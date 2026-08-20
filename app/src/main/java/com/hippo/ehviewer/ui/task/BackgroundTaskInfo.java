package com.hippo.ehviewer.ui.task;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.task.BackgroundTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;

/**
 * 后台任务信息类
 * 用于存储和管理后台任务的状态信息
 */
public class BackgroundTaskInfo {
    private static final int MAX_LOG_MESSAGES = 1000;

    private final String taskId;
    private final String taskName;
    private final String taskDescription;
    private volatile Future<?> future;
    // 后台任务实例（仅在活跃期间保留，用于调用真实的 pause/resume/cancel 方法）
    private volatile BackgroundTask task;
    private final BackgroundTask.TaskType taskType;
    private final boolean uniqueTask;
    private final String taskClassName;
    private final String taskPersistData;
    private final String mutexGroup;
    private final long startTime;
    private volatile int currentProgress;
    private volatile int totalProgress;
    private volatile String progressDetail;
    private volatile boolean isCompleted;
    private volatile boolean isCancelled;
    private volatile boolean isPaused;
    private volatile boolean isQueued;
    private volatile String errorMessage;
    private volatile File logFile;
    private final List<String> logMessages;

    public BackgroundTaskInfo(@NonNull String taskId, @NonNull String taskName,
                             @Nullable String taskDescription, @Nullable Future<?> future,
                             @NonNull BackgroundTask.TaskType taskType, boolean uniqueTask,
                             @NonNull String taskClassName, @Nullable String taskPersistData,
                             @Nullable String mutexGroup, long startTime) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.taskDescription = taskDescription;
        this.future = future;
        this.taskType = taskType;
        this.uniqueTask = uniqueTask;
        this.taskClassName = taskClassName;
        this.taskPersistData = taskPersistData;
        this.mutexGroup = mutexGroup;
        this.startTime = startTime;
        this.currentProgress = 0;
        this.totalProgress = -1; // -1 表示不确定进度
        this.progressDetail = null;
        this.isCompleted = false;
        this.isCancelled = false;
        this.isPaused = false;
        this.isQueued = false;
        this.errorMessage = null;
        this.logFile = null;
        this.logMessages = Collections.synchronizedList(new ArrayList<>());
    }

    @NonNull
    public String getTaskId() {
        return taskId;
    }

    @NonNull
    public String getTaskName() {
        return taskName;
    }

    @Nullable
    public String getTaskDescription() {
        return taskDescription;
    }

    @Nullable
    public Future<?> getFuture() {
        return future;
    }

    public void setFuture(@Nullable Future<?> future) {
        this.future = future;
    }

    /**
     * 获取后台任务实例（可能为 null，例如旧式 Runnable 任务或持久化恢复占位）
     */
    @Nullable
    public BackgroundTask getTask() {
        return task;
    }

    public void setTask(@Nullable BackgroundTask task) {
        this.task = task;
    }

    /**
     * 任务是否支持暂停
     */
    public boolean isPausable() {
        return task != null && task.isPausable();
    }

    /**
     * 是否为可恢复持久化任务
     */
    public boolean isPersistable() {
        return task != null && task.isPersistable();
    }

    @NonNull
    public BackgroundTask.TaskType getTaskType() {
        return taskType;
    }

    @NonNull
    public String getTaskClassName() {
        return taskClassName;
    }

    @Nullable
    public String getTaskPersistData() {
        return taskPersistData;
    }

    public boolean isUniqueTask() {
        return uniqueTask;
    }

    @Nullable
    public String getMutexGroup() {
        return mutexGroup;
    }

    public long getStartTime() {
        return startTime;
    }

    public int getCurrentProgress() {
        return currentProgress;
    }

    public void setCurrentProgress(int currentProgress) {
        this.currentProgress = currentProgress;
    }

    public int getTotalProgress() {
        return totalProgress;
    }

    public void setTotalProgress(int totalProgress) {
        this.totalProgress = totalProgress;
    }

    @Nullable
    public String getProgressDetail() {
        return progressDetail;
    }

    public void setProgressDetail(@Nullable String progressDetail) {
        this.progressDetail = progressDetail;
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    public void setCompleted(boolean completed) {
        isCompleted = completed;
    }

    public boolean isCancelled() {
        return isCancelled;
    }

    public void setCancelled(boolean cancelled) {
        isCancelled = cancelled;
    }

    public boolean isPaused() {
        return isPaused;
    }

    public void setPaused(boolean paused) {
        isPaused = paused;
    }

    public boolean isQueued() {
        return isQueued;
    }

    public void setQueued(boolean queued) {
        isQueued = queued;
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(@Nullable String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * 获取进度百分比（0-100）
     * 如果总进度为-1（不确定），返回-1
     */
    public int getProgressPercentage() {
        if (totalProgress <= 0) {
            return -1;
        }
        return (int) ((currentProgress * 100L) / totalProgress);
    }

    /**
     * 获取运行时长（毫秒）
     */
    public long getRunningTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * 估算剩余时间（毫秒）
     * 返回 -1 表示无法估算（无确定进度、已暂停、已完成、刚开始运行）
     */
    public long getEstimatedRemainingTime() {
        if (currentProgress <= 0 || totalProgress <= 0 || currentProgress >= totalProgress) {
            return -1;
        }
        if (isPaused || isCompleted || isCancelled || isQueued) {
            return -1;
        }
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed < 3000) {
            return -1;
        }
        double progressRate = (double) currentProgress / elapsed;
        if (progressRate <= 0) {
            return -1;
        }
        double remaining = totalProgress - currentProgress;
        return (long) (remaining / progressRate);
    }

    public void setLogFile(@Nullable File file) {
        this.logFile = file;
    }

    @Nullable
    public File getLogFile() {
        return logFile;
    }

    public void appendLog(@NonNull String message) {
        String stamped = stampMessage(message);
        logMessages.add(stamped);
        while (logMessages.size() > MAX_LOG_MESSAGES) {
            logMessages.remove(0);
        }

        File target = logFile;
        if (target != null) {
            try (FileWriter writer = new FileWriter(target, true)) {
                writer.append(stamped).append('\n');
            } catch (IOException ignore) {
                // 忽略写入异常，仍然保留内存日志
            }
        }
    }

    public void addLogMessage(@NonNull String message) {
        logMessages.add(message);
        while (logMessages.size() > MAX_LOG_MESSAGES) {
            logMessages.remove(0);
        }
    }

    @NonNull
    public List<String> getRecentLogs(int count) {
        synchronized (logMessages) {
            int size = logMessages.size();
            if (size <= count) {
                return new ArrayList<>(logMessages);
            }
            return new ArrayList<>(logMessages.subList(size - count, size));
        }
    }

    @NonNull
    public List<String> getLogMessages() {
        synchronized (logMessages) {
            return new ArrayList<>(logMessages);
        }
    }

    private String stampMessage(@NonNull String message) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        return format.format(new Date()) + " - " + message;
    }

    /**
     * 取消任务
     */
    public boolean cancel() {
        if (future != null && !future.isDone()) {
            isCancelled = future.cancel(true);
            return isCancelled;
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BackgroundTaskInfo that = (BackgroundTaskInfo) o;
        return taskId.equals(that.taskId);
    }

    @Override
    public int hashCode() {
        return taskId.hashCode();
    }
}