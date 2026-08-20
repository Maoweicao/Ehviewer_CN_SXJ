package com.hippo.ehviewer.transfer.core;

import com.hippo.ehviewer.transfer.data.UnifiedTask;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Runs recursive file operations away from NanoHTTPD request threads. */
public final class FileTreeTaskExecutor {
    private static final String TAG = "FileTreeTaskExecutor";
    private static final FileTreeTaskExecutor INSTANCE = new FileTreeTaskExecutor();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, File> archives = new ConcurrentHashMap<>();

    public static FileTreeTaskExecutor getInstance() { return INSTANCE; }

    public UnifiedTask archive(File source, File cacheDir) {
        TransferLogger.getInstance().d(TAG, "创建压缩任务: source=" + source.getAbsolutePath()
                + ", cacheDir=" + cacheDir.getAbsolutePath());
        UnifiedTask task = new UnifiedTask();
        task.type = "archive";
        task.subType = "folder";
        task.taskId = "archive-" + task.taskId;
        task.total = 1;
        TaskManager.getInstance().addTask(task);
        executor.execute(() -> {
            TaskManager manager = TaskManager.getInstance();
            manager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_IN_PROGRESS);
            try {
                File out = new File(cacheDir, task.taskId + ".zip");
                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
                    zip(task, source, source.getName(), zip);
                }
                archives.put(task.taskId, out);
                task.outputFiles.add(out.getName());
                task.completed = 1;
                manager.updateTaskProgress(task.taskId, 100, 1);
                manager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_COMPLETED);
                TransferLogger.getInstance().i(TAG, "压缩完成: taskId=" + task.taskId
                        + ", 输出=" + out.getAbsolutePath() + ", 字节数=" + out.length());
            } catch (Exception e) {
                task.error = e.getMessage();
                manager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_FAILED);
                TransferLogger.getInstance().e(TAG, "压缩任务执行失败: taskId=" + task.taskId + ", source=" + source.getAbsolutePath(), e);
            }
        });
        return task;
    }

    public UnifiedTask delete(File source) {
        TransferLogger.getInstance().d(TAG, "创建删除任务: source=" + source.getAbsolutePath());
        UnifiedTask task = UnifiedTask.createDeleteTask("directory");
        task.total = 1;
        TaskManager.getInstance().addTask(task);
        executor.execute(() -> {
            TaskManager manager = TaskManager.getInstance();
            manager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_IN_PROGRESS);
            if (deleteRecursively(task, source)) task.completed = 1; else task.failed = 1;
            if (task.failed == 0) {
                manager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_COMPLETED);
                TransferLogger.getInstance().i(TAG, "删除完成: taskId=" + task.taskId + ", path=" + source.getAbsolutePath());
            } else {
                task.error = "Failed to delete " + source.getName();
                manager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_FAILED);
                TransferLogger.getInstance().e(TAG, "删除失败: taskId=" + task.taskId + ", path=" + source.getAbsolutePath());
            }
        });
        return task;
    }

    public File getArchive(String taskId) { return archives.get(taskId); }

    private void zip(UnifiedTask task, File file, String path, ZipOutputStream zip) throws IOException {
        // 暂停检查点：暂停时阻塞等待恢复
        if (task.paused && !task.isTerminal()) {
            task.waitWhilePaused();
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) zip(task, child, path + "/" + child.getName(), zip);
            return;
        }
        zip.putNextEntry(new ZipEntry(path));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
        TransferLogger.getInstance().d(TAG, "打包文件: " + path + ", 字节数=" + file.length());
    }

    private boolean deleteRecursively(UnifiedTask task, File file) {
        // 暂停检查点
        if (task.paused && !task.isTerminal()) {
            task.waitWhilePaused();
        }
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) if (!deleteRecursively(task, child)) return false; }
        boolean deleted = file.delete();
        TransferLogger.getInstance().d(TAG, "删除文件: " + file.getAbsolutePath() + ", 结果=" + deleted);
        return deleted;
    }
}
