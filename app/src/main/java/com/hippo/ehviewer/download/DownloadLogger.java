package com.hippo.ehviewer.download;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.unifile.UniFile;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 下载日志管理器
 * 负责记录下载相关操作的详细日志，并自动保存到指定目录
 */
public class DownloadLogger {
    private static final String TAG = "DownloadLogger";
    private static final String LOG_FILE_EXTENSION = ".log";
    private static final int MAX_LOG_QUEUE_SIZE = 1000;
    private static final long LOG_FLUSH_INTERVAL = 5000; // 5秒
    
    private static DownloadLogger sInstance;
    private final Context mContext;
    private final ExecutorService mLogExecutor;
    private final BlockingQueue<LogEntry> mLogQueue;
    private final SimpleDateFormat mDateFormat;
    private final Object mLock = new Object();
    private long mLastFlushTime;
    private boolean mIsLoggingEnabled;
    
    public static synchronized void initialize(@NonNull Context context) {
        if (sInstance == null) {
            try {
                sInstance = new DownloadLogger(context.getApplicationContext());
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize DownloadLogger", e);
                // Create a fallback instance that does nothing
                sInstance = new DownloadLogger(context.getApplicationContext(), true);
            }
        }
    }
    
    public static DownloadLogger getInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("DownloadLogger not initialized");
        }
        return sInstance;
    }

    /** 未初始化时返回 null，供高频日志调用点安全使用。 */
    @Nullable
    public static DownloadLogger peek() {
        return sInstance;
    }
    
    private DownloadLogger(@NonNull Context context) {
        this(context, false);
    }
    
    private DownloadLogger(@NonNull Context context, boolean fallback) {
        mContext = context;
        mIsLoggingEnabled = fallback ? false : Settings.getDownloadLoggingEnabled();
        if (fallback) {
            mLogExecutor = null;
            mLogQueue = null;
            mDateFormat = null;
        } else {
            mLogExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "DownloadLogger");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
            mLogQueue = new LinkedBlockingQueue<>(MAX_LOG_QUEUE_SIZE);
            mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
            // 启动日志处理线程
            startLogProcessor();
        }
    }
    
    /**
     * 记录单页下载的链接级日志，供事后分析失败/限流/切节点。
     * 一行一个事件，键值对格式，URL 不带空格，可直接用脚本解析。
     */
    public void logPageEvent(@Nullable String gid, int pageIndex, int totalPages, int attempt,
                             @NonNull String phase, @Nullable String result,
                             @Nullable String pageUrl, @Nullable String imageUrl,
                             int httpCode, long bytes, long elapsedMs,
                             @Nullable String node, @Nullable String detail) {
        if (!mIsLoggingEnabled) return;

        StringBuilder sb = new StringBuilder(320);
        sb.append("phase=").append(phase);
        if (result != null && !result.isEmpty()) {
            sb.append(" result=").append(result);
        }
        if (pageIndex >= 0) {
            sb.append(" page=").append(pageIndex + 1);
            if (totalPages > 0) sb.append('/').append(totalPages);
        }
        if (attempt >= 0) {
            sb.append(" try=").append(attempt);
        }
        if (httpCode > 0) {
            sb.append(" http=").append(httpCode);
        }
        if (bytes >= 0) {
            sb.append(" bytes=").append(bytes);
        }
        if (elapsedMs >= 0) {
            sb.append(" ms=").append(elapsedMs);
        }
        if (node != null && !node.isEmpty()) {
            sb.append(" node=\"").append(node).append('"');
        }
        if (pageUrl != null && !pageUrl.isEmpty()) {
            sb.append(" pageUrl=").append(pageUrl);
        }
        if (imageUrl != null && !imageUrl.isEmpty()) {
            sb.append(" imageUrl=").append(imageUrl);
        }
        if (detail != null && !detail.isEmpty()) {
            String oneLine = detail.replace('\r', ' ').replace('\n', ' ');
            if (oneLine.length() > 400) oneLine = oneLine.substring(0, 400) + "...";
            sb.append(" detail=\"").append(oneLine).append('"');
        }
        log(LogLevel.INFO, "DownloadPage", sb.toString(), gid, null);
    }

    /** 记录节点切换，便于把下载失败与换 IP 对齐。 */
    public void logSwitchEvent(@Nullable String gid, @Nullable String from, @Nullable String to,
                               @Nullable String reason, int generation, long elapsedMs) {
        if (!mIsLoggingEnabled) return;
        StringBuilder sb = new StringBuilder(160);
        sb.append("phase=SWITCH");
        sb.append(" from=\"").append(from == null ? "" : from).append('"');
        sb.append(" to=\"").append(to == null ? "" : to).append('"');
        sb.append(" reason=").append(reason == null ? "unknown" : reason);
        sb.append(" generation=").append(generation);
        if (elapsedMs >= 0) sb.append(" ms=").append(elapsedMs);
        log(LogLevel.INFO, "NodeSwitch", sb.toString(), gid, null);
    }

    /**
     * 记录下载开始日志（包含完整下载信息）
     */
    public void logDownloadStart(@NonNull DownloadInfo info) {
        if (!mIsLoggingEnabled) return;

        String categoryStr = EhUtils.getCategory(info.category);
        String ratingStr = String.format(Locale.getDefault(), "%.1f", info.rating);
        String titleSuitable = EhUtils.getSuitableTitle(info);
        String message = String.format(Locale.getDefault(),
            "下载开始 | GID:%d | Token:%s | 标题:%s | 标题(JPN):%s | 分类:%s | 上传者:%s | 评分:%s | 页数:%s | 发布时间:%s | 标签:%s | 缩略图:%s | 节点:%s",
            info.gid, info.token, titleSuitable, 
            info.titleJpn != null ? info.titleJpn : "N/A",
            categoryStr != null ? categoryStr : "N/A",
            info.uploader != null ? info.uploader : "N/A",
            ratingStr,
            info.pages > 0 ? String.valueOf(info.pages) : "待定",
            info.posted != null ? info.posted : "N/A",
            info.label != null ? info.label : "默认",
            info.thumb != null ? info.thumb : "N/A",
            currentNodeLabel() != null ? currentNodeLabel() : "N/A");
        log(LogLevel.INFO, "DownloadStart", message, String.valueOf(info.gid), titleSuitable);
    }

    /**
     * @deprecated Use {@link #logDownloadStart(DownloadInfo)} instead
     */
    @Deprecated
    public void logDownloadStart(@NonNull String gid, @NonNull String title, int totalPages) {
        if (!mIsLoggingEnabled) return;

        String message = String.format(Locale.getDefault(),
            "下载开始 - GID: %s, 标题: %s, 总页数: %d", gid, title, totalPages);
        log(LogLevel.INFO, "DownloadStart", message, gid, title);
    }
    
    /**
     * 记录下载进度日志
     */
    public void logDownloadProgress(@NonNull String gid, @NonNull String title, 
                                  int currentPage, int totalPages, int speed) {
        if (!mIsLoggingEnabled) return;
        
        String message = String.format(Locale.getDefault(),
            "下载进度 - GID: %s, 标题: %s, 当前进度: %d/%d, 速度: %d KB/s", 
            gid, title, currentPage, totalPages, speed);
        log(LogLevel.DEBUG, "DownloadProgress", message, gid, title);
    }
    
    /**
     * 记录下载完成日志
     */
    public void logDownloadComplete(@NonNull String gid, @NonNull String title, 
                                  long totalTime, int successCount, int failCount) {
        if (!mIsLoggingEnabled) return;
        
        String message = String.format(Locale.getDefault(),
            "下载完成 - GID: %s, 标题: %s, 总耗时: %d ms, 成功: %d, 失败: %d", 
            gid, title, totalTime, successCount, failCount);
        log(LogLevel.INFO, "DownloadComplete", message, gid, title);
    }

    public void logPhaseChange(@NonNull String gid, @NonNull String title, int phase) {
        if (!mIsLoggingEnabled) return;

        String phaseStr;
        switch (phase) {
            case 0: phaseStr = "本地复制"; break;
            case 1: phaseStr = "网络下载"; break;
            default: phaseStr = "空闲"; break;
        }
        String message = String.format(Locale.getDefault(),
            "阶段切换 - GID: %s, 标题: %s, 阶段: %s", gid, title, phaseStr);
        log(LogLevel.INFO, "PhaseChange", message, gid, title);
    }
    
    /**
     * 记录下载错误日志
     */
    public void logDownloadError(@NonNull String gid, @NonNull String title, 
                               @NonNull String error, @Nullable Exception exception) {
        if (!mIsLoggingEnabled) return;
        
        String message = String.format(Locale.getDefault(),
            "下载错误 - GID: %s, 标题: %s, 错误: %s", gid, title, error);
        if (exception != null) {
            message += ", 异常: " + exception.getMessage();
        }
        log(LogLevel.ERROR, "DownloadError", message, gid, title, exception);
    }
    
    /**
     * 记录后台任务开始日志
     */
    public void logBackgroundTaskStart(@NonNull String taskType, @NonNull String taskName) {
        if (!mIsLoggingEnabled) return;
        
        String message = String.format("后台任务开始 - 类型: %s, 名称: %s", taskType, taskName);
        log(LogLevel.INFO, "BackgroundTaskStart", message, null, taskName);
    }
    
    /**
     * 记录后台任务进度日志
     */
    public void logBackgroundTaskProgress(@NonNull String taskType, @NonNull String taskName, 
                                        int current, int total) {
        if (!mIsLoggingEnabled) return;
        
        String message = String.format("后台任务进度 - 类型: %s, 名称: %s, 进度: %d/%d", 
            taskType, taskName, current, total);
        log(LogLevel.DEBUG, "BackgroundTaskProgress", message, null, taskName);
    }
    
    /**
     * 记录后台任务完成日志
     */
    public void logBackgroundTaskComplete(@NonNull String taskType, @NonNull String taskName, 
                                        long totalTime, boolean success) {
        if (!mIsLoggingEnabled) return;
        
        String message = String.format("后台任务完成 - 类型: %s, 名称: %s, 总耗时: %d ms, 成功: %s", 
            taskType, taskName, totalTime, success);
        log(LogLevel.INFO, "BackgroundTaskComplete", message, null, taskName);
    }
    
    /**
     * 记录设置变更日志
     */
    public void logSettingsChange(@NonNull String key, @Nullable Object oldValue, 
                                @Nullable Object newValue) {
        if (!mIsLoggingEnabled) return;
        
        String message = String.format("设置变更 - 键: %s, 旧值: %s, 新值: %s", 
            key, oldValue, newValue);
        log(LogLevel.INFO, "SettingsChange", message, null, null);
    }
    
    /**
     * 记录通用日志
     */
    public void log(@NonNull LogLevel level, @NonNull String tag, @NonNull String message, 
                   @Nullable String gid, @Nullable String title) {
        log(level, tag, message, gid, title, null);
    }
    
    /**
     * 记录通用日志（带异常）
     */
    public void log(@NonNull LogLevel level, @NonNull String tag, @NonNull String message, 
                   @Nullable String gid, @Nullable String title, @Nullable Exception exception) {
        if (!mIsLoggingEnabled) return;
        
        LogEntry entry = new LogEntry(level, tag, message, gid, title, exception, System.currentTimeMillis());
        
        // 添加到队列，如果队列满了则移除最旧的条目
        if (!mLogQueue.offer(entry)) {
            mLogQueue.poll();
            mLogQueue.offer(entry);
        }
        
        // 同时输出到Logcat
        outputToLogcat(level, tag, message, exception);
    }
    
    /**
     * 输出日志到Logcat
     */
    private void outputToLogcat(@NonNull LogLevel level, @NonNull String tag, @NonNull String message, 
                               @Nullable Exception exception) {
        switch (level) {
            case DEBUG:
                Log.d(tag, message, exception);
                break;
            case INFO:
                Log.i(tag, message, exception);
                break;
            case WARN:
                Log.w(tag, message, exception);
                break;
            case ERROR:
                Log.e(tag, message, exception);
                break;
        }
    }
    
    /**
     * 启用或禁用日志记录
     */
    public void setLoggingEnabled(boolean enabled) {
        synchronized (mLock) {
            mIsLoggingEnabled = enabled;
            Settings.putDownloadLoggingEnabled(enabled);
            
            if (enabled) {
                log(LogLevel.INFO, TAG, "下载日志已启用", null, null);
            } else {
                log(LogLevel.INFO, TAG, "下载日志已禁用", null, null);
                flushLogs();
            }
        }
    }
    
    /**
     * 立即刷新日志到文件
     */
    public void flushLogs() {
        // Every entry is opened and flushed immediately so logs survive process kills.
    }
    
    /**
     * 启动日志处理器
     */
    private void startLogProcessor() {
        mLogExecutor.execute(() -> {
            while (true) {
                try {
                    LogEntry entry = mLogQueue.take();
                    writeLogToFile(entry);
                    
                    // 定期刷新日志文件
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - mLastFlushTime > LOG_FLUSH_INTERVAL) {
                        flushLogs();
                        mLastFlushTime = currentTime;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "日志处理器异常", e);
                }
            }
        });
    }
    
    /**
     * 写入日志到文件
     */
    private void writeLogToFile(@NonNull LogEntry entry) {
        synchronized (mLock) {
            File logDir = getLogDirectory();
            if (logDir == null) {
                Log.w(TAG, "Download log path is unavailable; entry remains in Logcat only");
                return;
            }
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.e(TAG, "Unable to create download log directory: " + logDir);
                return;
            }
            File logFile = new File(logDir, buildLogFileName(entry));
            try (FileWriter writer = new FileWriter(logFile, true)) {
                if (logFile.length() == 0) {
                    writer.write("=== EhViewer 下载日志 - " + mDateFormat.format(new Date(entry.timestamp)) + " ===\n");
                }
                writer.write(formatLogEntry(entry));
                writer.write("\n");
                writer.flush();
            } catch (IOException e) {
                Log.e(TAG, "写入日志文件失败", e);
            }
        }
    }

    private String buildLogFileName(@NonNull LogEntry entry) {
        String gid = entry.gid == null ? null : entry.gid.replaceAll("[^0-9]", "");
        if (gid == null || gid.isEmpty()) {
            // 没有 GID 的事件（后台任务、切节点）按天汇总。
            String day = new SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                    .format(new Date(entry.timestamp));
            return "session_" + day + LOG_FILE_EXTENSION;
        }
        // 同一个画廊始终写同一个文件，避免跨分钟/跨小时被拆成多个文件。
        return gid + LOG_FILE_EXTENSION;
    }

    @Nullable
    private static String currentNodeLabel() {
        try {
            return com.hippo.ehviewer.lab.ip.IpSwitchController.getInstance().getCurrentNodeName();
        } catch (Throwable t) {
            return null;
        }
    }
    
    /**
     * 获取日志目录
     */
    @Nullable
    public File getLogDirectory() {
        // 固定放在 /sdcard/EhViewer/logs/download，方便用文件管理器或 USB 取出。
        try {
            File dir = AppConfig.getDirInExternalAppDir("logs/download");
            if (dir != null) return dir;
        } catch (Throwable ignored) {
        }
        UniFile downloadLocation = Settings.getDownloadLocation();
        if (downloadLocation != null) {
            String path = downloadLocation.getUri().getPath();
            if (path != null) {
                File downloadDir = new File(path);
                File parent = downloadDir.getParentFile();
                if (parent != null) return new File(new File(parent, "logs"), "download");
            }
        }
        return null;
    }
    
    /**
     * 把全部下载链接日志打包成 ZIP，便于导出到电脑分析。
     */
    @Nullable
    public File exportLogsZip() {
        File dir = getLogDirectory();
        if (dir == null) return null;
        File[] files = getLogFiles();
        if (files.length == 0) return null;

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        File outDir = AppConfig.getDirInExternalAppDir("logs");
        if (outDir == null) outDir = dir;
        File zip = new File(outDir, "download-logs-" + stamp + ".zip");

        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zip))) {
            byte[] buffer = new byte[8192];
            for (File file : files) {
                if (file == null || !file.isFile()) continue;
                zos.putNextEntry(new java.util.zip.ZipEntry(file.getName()));
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }
            zos.putNextEntry(new java.util.zip.ZipEntry("README.txt"));
            String readme = "EhViewer 下载链接日志\n"
                    + "每行一个事件，键值对格式：phase=... page=... http=... bytes=... ms=... node=... pageUrl=... imageUrl=...\n"
                    + "phase 含义：START 开始 / PAGE_REQ 取页面 / PAGE_OK 页面成功 / PAGE_ERR 页面失败 / PAGE_509 封禁\n"
                    + "IMG_REQ 图片请求 / IMG_RESP 图片响应 / IMG_OK 图片成功 / IMG_ERR 图片失败 / IMG_WARN 大小异常\n"
                    + "SWITCH 换节点 / SWITCH_RETRY 换节点后重试 / FAIL 该页最终失败 / LAN_OK 局域网命中 / SKIP 跳过\n";
            zos.write(readme.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            Log.e(TAG, "导出下载日志失败", e);
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
            return null;
        }
        return zip;
    }

    /**
     * 获取所有日志文件
     */
    public File[] getLogFiles() {
        File logDir = getLogDirectory();
        if (logDir != null && logDir.exists() && logDir.isDirectory()) {
            File[] files = logDir.listFiles((dir, name) -> name.endsWith(LOG_FILE_EXTENSION));
            if (files == null) return new File[0];
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            return files;
        }
        return new File[0];
    }
    
    /**
     * 清理旧日志文件（保留最近7天）
     */
    public void cleanOldLogs() {
        File logDir = getLogDirectory();
        if (logDir != null && logDir.exists() && logDir.isDirectory()) {
            File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(LOG_FILE_EXTENSION));
            if (logFiles != null) {
                long sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L);
                for (File logFile : logFiles) {
                    if (logFile.lastModified() < sevenDaysAgo) {
                        if (logFile.delete()) {
                            Log.i(TAG, "删除旧日志文件: " + logFile.getName());
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 格式化日志条目
     */
    private String formatLogEntry(@NonNull LogEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(mDateFormat.format(new Date(entry.timestamp)));
        sb.append(" [").append(entry.level.name()).append("]");
        sb.append(" [").append(entry.tag).append("]");
        
        if (entry.gid != null) {
            sb.append(" [GID:").append(entry.gid).append("]");
        }
        
        if (entry.title != null) {
            sb.append(" [").append(entry.title).append("]");
        }
        
        sb.append(" ").append(entry.message);
        
        if (entry.exception != null) {
            sb.append("\n异常堆栈: ").append(Log.getStackTraceString(entry.exception));
        }
        
        return sb.toString();
    }
    
    /**
     * 日志级别
     */
    public enum LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
    
    /**
     * 日志条目
     */
    private static class LogEntry {
        final LogLevel level;
        final String tag;
        final String message;
        final String gid;
        final String title;
        final Exception exception;
        final long timestamp;
        
        LogEntry(LogLevel level, String tag, String message, String gid, String title, 
                Exception exception, long timestamp) {
            this.level = level;
            this.tag = tag;
            this.message = message;
            this.gid = gid;
            this.title = title;
            this.exception = exception;
            this.timestamp = timestamp;
        }
    }
}
