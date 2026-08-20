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

package com.hippo.ehviewer.transfer.log;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 传输日志管理器
 * 单例模式，支持日志记录、过滤、导出
 */
public class TransferLogger {

    private static final String TAG = "TransferLogger";
    private static final int MAX_LOG_SIZE = 1000;

    private static TransferLogger instance;

    private final List<LogEntry> logs = Collections.synchronizedList(new ArrayList<>());
    private final List<LogListener> listeners = new CopyOnWriteArrayList<>();

    private TransferLogger() {
    }

    /**
     * 获取单例实例
     */
    public static synchronized TransferLogger getInstance() {
        if (instance == null) {
            instance = new TransferLogger();
        }
        return instance;
    }

    // ========== 日志记录方法 ==========

    public void v(String tag, String msg) {
        addLog(LogLevel.VERBOSE, tag, msg, null);
    }

    public void d(String tag, String msg) {
        addLog(LogLevel.DEBUG, tag, msg, null);
    }

    public void i(String tag, String msg) {
        addLog(LogLevel.INFO, tag, msg, null);
    }

    public void w(String tag, String msg) {
        addLog(LogLevel.WARN, tag, msg, null);
    }

    public void w(String tag, String msg, Throwable t) {
        addLog(LogLevel.WARN, tag, msg, Log.getStackTraceString(t));
    }

    public void e(String tag, String msg) {
        addLog(LogLevel.ERROR, tag, msg, null);
    }

    public void e(String tag, String msg, Throwable t) {
        addLog(LogLevel.ERROR, tag, msg, Log.getStackTraceString(t));
    }

    /**
     * 添加日志
     */
    private void addLog(LogLevel level, String tag, String msg, String stackTrace) {
        // 同时输出到Android Logcat
        android.util.Log.println(level.getPriority(), tag, msg);

        LogEntry entry = new LogEntry(System.currentTimeMillis(), level, tag, msg, stackTrace);

        // 超过最大数量，移除最旧的
        while (logs.size() >= MAX_LOG_SIZE) {
            logs.remove(0);
        }

        logs.add(entry);
        notifyLogAdded(entry);
    }

    // ========== 日志查询方法 ==========

    /**
     * 获取所有日志
     */
    public List<LogEntry> getLogs() {
        return new ArrayList<>(logs);
    }

    /**
     * 获取指定级别以上的日志
     */
    public List<LogEntry> getLogs(LogLevel minLevel) {
        List<LogEntry> filtered = new ArrayList<>();
        for (LogEntry entry : logs) {
            if (entry.getLevel().getPriority() >= minLevel.getPriority()) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * 获取日志数量
     */
    public int getLogCount() {
        return logs.size();
    }

    // ========== 日志操作方法 ==========

    /**
     * 清空日志
     */
    public void clear() {
        logs.clear();
        notifyLogsCleared();
    }

    /**
     * 导出日志到文件
     */
    public boolean exportToFile(File file) {
        try {
            FileWriter writer = new FileWriter(file);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

            writer.write("=== EhViewer Transfer Log ===\n");
            writer.write("Export Time: " + sdf.format(new Date()) + "\n");
            writer.write("Log Count: " + logs.size() + "\n");
            writer.write("============================\n\n");

            for (LogEntry entry : logs) {
                writer.write(entry.formatForExport());
            }

            writer.close();
            Log.i(TAG, "Logs exported to: " + file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to export logs", e);
            return false;
        }
    }

    // ========== 监听器管理 ==========

    public void addListener(LogListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(LogListener listener) {
        listeners.remove(listener);
    }

    private void notifyLogAdded(LogEntry entry) {
        for (LogListener listener : listeners) {
            listener.onLogAdded(entry);
        }
    }

    private void notifyLogsCleared() {
        for (LogListener listener : listeners) {
            listener.onLogsCleared();
        }
    }

    // ========== 日志监听器接口 ==========

    public interface LogListener {
        void onLogAdded(LogEntry entry);
        void onLogsCleared();
    }
}
