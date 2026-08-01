/*
 * Copyright 2025 EhViewer Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.hippo.ehviewer.transfer.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.transfer.data.UnifiedTask;
import com.hippo.unifile.UniFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Executes potentially slow remote delete operations away from HTTP threads. */
public final class DeleteTaskExecutor {
    private static final DeleteTaskExecutor INSTANCE = new DeleteTaskExecutor();

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private DeleteTaskExecutor() {
    }

    public static DeleteTaskExecutor getInstance() {
        return INSTANCE;
    }

    public UnifiedTask submitGalleryDelete(Context context, List<Long> gids, boolean deleteFiles) {
        UnifiedTask task = UnifiedTask.createDeleteTask(gids.size() == 1 ? "gallery" : "gallery_batch");
        task.gids = new ArrayList<>(gids);
        task.total = gids.size();
        task.deleteFiles = deleteFiles;
        submit(context, task);
        return task;
    }

    public UnifiedTask submitDownloadDelete(Context context, List<Long> gids) {
        UnifiedTask task = UnifiedTask.createDeleteTask(gids.size() == 1 ? "download" : "download_batch");
        task.gids = new ArrayList<>(gids);
        task.total = gids.size();
        submit(context, task);
        return task;
    }

    private void submit(Context context, UnifiedTask task) {
        TaskManager.getInstance().addTask(task);
        executor.execute(() -> execute(context, task));
    }

    private void execute(Context context, UnifiedTask task) {
        DownloadManager downloadManager = EhApplication.getDownloadManager(context);
        TaskManager taskManager = TaskManager.getInstance();
        taskManager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_IN_PROGRESS);

        for (Long gid : task.gids) {
            if (UnifiedTask.STATUS_CANCELLED.equals(task.status)) return;
            try {
                DownloadInfo info = downloadManager.getDownloadInfo(gid);
                if (info == null) {
                    task.failed++;
                    task.error = "Download not found: " + gid;
                    task.completed++;
                    taskManager.updateTaskProgress(task.taskId,
                            task.completed * 100.0 / task.total, task.completed);
                    continue;
                }
                UniFile downloadDir = task.deleteFiles && info != null
                        ? SpiderDen.getGalleryDownloadDir(info) : null;
                runOnMainThread(() -> downloadManager.deleteDownload(gid));
                if (downloadDir != null && downloadDir.isDirectory() && !downloadDir.delete()) {
                    task.failed++;
                }
            } catch (Exception e) {
                task.failed++;
                task.error = e.getMessage();
            }
            task.completed++;
            taskManager.updateTaskProgress(task.taskId,
                    task.total == 0 ? 100 : task.completed * 100.0 / task.total,
                    task.completed);
        }

        task.error = task.failed == 0 ? null : task.failed + " item(s) failed";
        if (task.failed == task.total && task.total > 0) {
            taskManager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_FAILED);
        } else {
            taskManager.updateTaskStatus(task.taskId, UnifiedTask.STATUS_COMPLETED);
        }
        ResponseCache.getInstance().invalidateGalleries();
    }

    private void runOnMainThread(Runnable action) throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await();
    }
}
