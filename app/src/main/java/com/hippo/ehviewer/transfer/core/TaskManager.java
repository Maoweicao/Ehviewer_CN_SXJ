/*
 * Copyright 2025 EhViewer Contributors
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

package com.hippo.ehviewer.transfer.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.transfer.data.UnifiedTask;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified task manager for push and compress tasks
 * Persists tasks to SharedPreferences to survive app restarts
 */
public class TaskManager {

    private static final String TAG = "TaskManager";
    private static final String PREFS_NAME = "transfer_tasks";
    private static final String KEY_TASKS = "tasks_json";
    private static final long CLEANUP_THRESHOLD_MS = 24 * 60 * 60 * 1000; // 24 hours

    private static volatile TaskManager instance;

    private final ConcurrentHashMap<String, UnifiedTask> tasks = new ConcurrentHashMap<>();
    private final List<TaskListener> listeners = new ArrayList<>();
    private SharedPreferences prefs;
    private boolean initialized = false;

    private TaskManager() {
    }

    public static TaskManager getInstance() {
        if (instance == null) {
            synchronized (TaskManager.class) {
                if (instance == null) {
                    instance = new TaskManager();
                }
            }
        }
        return instance;
    }

    /**
     * Initialize with application context
     */
    public synchronized void init(Context context) {
        if (initialized) return;
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFromPrefs();
        cleanupOldTasks();
        initialized = true;
        TransferLogger.getInstance().d(TAG, "TaskManager initialized, " + tasks.size() + " tasks loaded");
    }

    // ==================== Task CRUD ====================

    /**
     * Add a new task
     */
    public void addTask(UnifiedTask task) {
        tasks.put(task.taskId, task);
        saveToPrefs();
        notifyTaskAdded(task);
        TransferLogger.getInstance().d(TAG, "Task added: " + task.taskId + " (" + task.type + "/" + task.subType + ")");
        TransferLogger.getInstance().i(TAG, "任务已创建: taskId=" + task.taskId + ", type=" + task.type + ", subType=" + task.subType);
    }

    /**
     * Get a task by ID
     */
    public UnifiedTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * Update task status
     */
    public void updateTaskStatus(String taskId, String status) {
        TransferLogger.getInstance().d(TAG, "更新任务状态: taskId=" + taskId + ", status=" + status);
        UnifiedTask task = tasks.get(taskId);
        if (task != null) {
            task.status = status;
            task.updatedTime = System.currentTimeMillis();
            if (task.isTerminal()) {
                task.completedTime = System.currentTimeMillis();
            }
            saveToPrefs();
            notifyTaskUpdated(task);
            TransferLogger.getInstance().i(TAG, "任务状态变更: taskId=" + taskId + " -> " + status);
        } else {
            TransferLogger.getInstance().w(TAG, "更新任务状态失败，任务不存在: " + taskId);
        }
    }

    /**
     * Update task progress
     */
    public void updateTaskProgress(String taskId, double progress, int completed) {
        TransferLogger.getInstance().d(TAG, "更新任务进度: taskId=" + taskId + ", progress=" + progress + ", completed=" + completed);
        UnifiedTask task = tasks.get(taskId);
        if (task != null) {
            task.progress = progress;
            task.completed = completed;
            task.updatedTime = System.currentTimeMillis();
            saveToPrefs();
            notifyTaskUpdated(task);
        }
    }

    /**
     * Update task fields
     */
    public void updateTask(UnifiedTask task) {
        TransferLogger.getInstance().d(TAG, "更新任务: taskId=" + task.taskId);
        task.updatedTime = System.currentTimeMillis();
        tasks.put(task.taskId, task);
        saveToPrefs();
        notifyTaskUpdated(task);
    }

    /**
     * Remove a task
     */
    public void removeTask(String taskId) {
        UnifiedTask task = tasks.remove(taskId);
        if (task != null) {
            saveToPrefs();
            notifyTaskRemoved(task);
            TransferLogger.getInstance().d(TAG, "Task removed: " + taskId);
        }
    }

    /**
     * 暂停任务：设置暂停标志，执行循环会在检查点阻塞等待恢复。
     * 返回是否成功置为暂停状态。
     */
    public boolean pauseTask(String taskId) {
        UnifiedTask task = tasks.get(taskId);
        if (task == null || !task.canPause()) {
            return false;
        }
        task.paused = true;
        task.updatedTime = System.currentTimeMillis();
        saveToPrefs();
        notifyTaskUpdated(task);
        TransferLogger.getInstance().i(TAG, "任务已暂停: taskId=" + taskId);
        return true;
    }

    /**
     * 恢复任务：清除暂停标志。
     * 返回是否成功恢复。
     */
    public boolean resumeTask(String taskId) {
        UnifiedTask task = tasks.get(taskId);
        if (task == null || !task.canResume()) {
            return false;
        }
        task.paused = false;
        task.updatedTime = System.currentTimeMillis();
        saveToPrefs();
        notifyTaskUpdated(task);
        TransferLogger.getInstance().i(TAG, "任务已恢复: taskId=" + taskId);
        return true;
    }

    /**
     * Get all tasks
     */
    public List<UnifiedTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * Get tasks by type
     */
    public List<UnifiedTask> getTasksByType(String type) {
        List<UnifiedTask> result = new ArrayList<>();
        for (UnifiedTask task : tasks.values()) {
            if (type.equals(task.type)) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * Get tasks by status
     */
    public List<UnifiedTask> getTasksByStatus(String status) {
        List<UnifiedTask> result = new ArrayList<>();
        for (UnifiedTask task : tasks.values()) {
            if (status.equals(task.status)) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * Get tasks filtered by type and status
     */
    public List<UnifiedTask> getTasks(String type, String status) {
        List<UnifiedTask> result = new ArrayList<>();
        for (UnifiedTask task : tasks.values()) {
            boolean typeMatch = "all".equals(type) || type == null || type.equals(task.type);
            boolean statusMatch = "all".equals(status) || status == null || status.equals(task.status);
            if (typeMatch && statusMatch) {
                result.add(task);
            }
        }
        // Sort by created time descending
        result.sort((a, b) -> Long.compare(b.createdTime, a.createdTime));
        return result;
    }

    /**
     * Get count of active (non-terminal) tasks
     */
    public int getActiveTaskCount() {
        int count = 0;
        for (UnifiedTask task : tasks.values()) {
            if (!task.isTerminal()) {
                count++;
            }
        }
        return count;
    }

    // ==================== Persistence ====================

    /**
     * Save all tasks to SharedPreferences
     */
    private synchronized void saveToPrefs() {
        if (prefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (UnifiedTask task : tasks.values()) {
                arr.add(task.toJson());
            }
            prefs.edit().putString(KEY_TASKS, arr.toJSONString()).apply();
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to save tasks", e);
        }
    }

    /**
     * Load tasks from SharedPreferences
     */
    private synchronized void loadFromPrefs() {
        if (prefs == null) return;
        try {
            String json = prefs.getString(KEY_TASKS, "[]");
            JSONArray arr = JSON.parseArray(json);
            tasks.clear();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                UnifiedTask task = UnifiedTask.fromJson(obj);
                tasks.put(task.taskId, task);
            }
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to load tasks", e);
            tasks.clear();
        }
    }

    /**
     * Cleanup old terminal tasks
     */
    private void cleanupOldTasks() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        for (UnifiedTask task : tasks.values()) {
            if (task.isTerminal() && (now - task.completedTime) > CLEANUP_THRESHOLD_MS) {
                toRemove.add(task.taskId);
            }
        }
        for (String id : toRemove) {
            tasks.remove(id);
        }
        if (!toRemove.isEmpty()) {
            saveToPrefs();
            TransferLogger.getInstance().d(TAG, "Cleaned up " + toRemove.size() + " old tasks");
        }
    }

    // ==================== Listeners ====================

    public void addListener(TaskListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(TaskListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyTaskAdded(UnifiedTask task) {
        synchronized (listeners) {
            for (TaskListener listener : listeners) {
                try { listener.onTaskAdded(task); } catch (Exception e) { /* ignore */ }
            }
        }
    }

    private void notifyTaskUpdated(UnifiedTask task) {
        synchronized (listeners) {
            for (TaskListener listener : listeners) {
                try { listener.onTaskUpdated(task); } catch (Exception e) { /* ignore */ }
            }
        }
    }

    private void notifyTaskRemoved(UnifiedTask task) {
        synchronized (listeners) {
            for (TaskListener listener : listeners) {
                try { listener.onTaskRemoved(task); } catch (Exception e) { /* ignore */ }
            }
        }
    }

    /**
     * Task event listener interface
     */
    public interface TaskListener {
        default void onTaskAdded(UnifiedTask task) {}
        default void onTaskUpdated(UnifiedTask task) {}
        default void onTaskRemoved(UnifiedTask task) {}
    }
}
