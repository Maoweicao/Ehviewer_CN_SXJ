package com.hippo.ehviewer.transfer.core;

import com.hippo.ehviewer.transfer.data.UnifiedTask;

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
    private static final FileTreeTaskExecutor INSTANCE = new FileTreeTaskExecutor();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, File> archives = new ConcurrentHashMap<>();

    public static FileTreeTaskExecutor getInstance() { return INSTANCE; }

    public UnifiedTask archive(File source, File cacheDir) {
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
                    zip(source, source.getName(), zip);
                }
                archives.put(task.taskId, out);
                task.outputFiles.add(out.getName());
                task.completed = 1;
                manager.updateTaskProgress(task.taskId, 100, 1);
                manager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_COMPLETED);
            } catch (Exception e) {
                task.error = e.getMessage();
                manager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_FAILED);
            }
        });
        return task;
    }

    public UnifiedTask delete(File source) {
        UnifiedTask task = UnifiedTask.createDeleteTask("directory");
        task.total = 1;
        TaskManager.getInstance().addTask(task);
        executor.execute(() -> {
            TaskManager manager = TaskManager.getInstance();
            manager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_IN_PROGRESS);
            if (deleteRecursively(source)) task.completed = 1; else task.failed = 1;
            if (task.failed == 0) manager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_COMPLETED);
            else { task.error = "Failed to delete " + source.getName(); manager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_FAILED); }
        });
        return task;
    }

    public File getArchive(String taskId) { return archives.get(taskId); }

    private void zip(File file, String path, ZipOutputStream zip) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) zip(child, path + "/" + child.getName(), zip);
            return;
        }
        zip.putNextEntry(new ZipEntry(path));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) if (!deleteRecursively(child)) return false; }
        return file.delete();
    }
}
