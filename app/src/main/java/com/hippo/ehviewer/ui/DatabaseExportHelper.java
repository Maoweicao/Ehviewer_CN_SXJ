/*
 * Copyright 2025 Hippo Seven
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

package com.hippo.ehviewer.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.hippo.content.FileProvider;
import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.task.BackgroundTask;
import com.hippo.ehviewer.task.ExportFileResult;
import com.hippo.ehviewer.ui.task.BackgroundTaskInfo;
import com.hippo.ehviewer.ui.task.BackgroundTaskStatusManager;

import java.io.File;

/**
 * 数据库查看器的导出分享辅助类。
 * 提交导出后台任务，任务完成后自动弹出系统分享面板分享生成的文件。
 */
public final class DatabaseExportHelper {

    private DatabaseExportHelper() {
    }

    /**
     * 提交一个导出任务，并在任务完成后自动分享生成的文件。
     * 任务必须是 {@link ExportFileResult} 的实现。
     */
    public static void submitAndShare(Context context, BackgroundTask task) {
        if (!(task instanceof ExportFileResult)) {
            throw new IllegalArgumentException("task must implement ExportFileResult");
        }
        if (context == null) {
            return;
        }
        final BackgroundTaskManager manager;
        try {
            manager = BackgroundTaskManager.getInstance();
        } catch (IllegalStateException e) {
            Toast.makeText(context, R.string.database_viewer_service_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        final BackgroundTaskStatusManager statusManager = manager.getTaskStatusManager();
        final Context appContext = context.getApplicationContext();
        final String taskId = task.getTaskId();

        BackgroundTaskStatusManager.TaskChangeListener listener =
                new BackgroundTaskStatusManager.TaskChangeListener() {
                    @Override
                    public void onTaskStateChanged(String changedId) {
                        if (!taskId.equals(changedId)) {
                            return;
                        }
                        statusManager.removeTaskChangeListener(this);
                        BackgroundTaskInfo info = statusManager.getTaskInfo(taskId);
                        final Handler mainHandler = new Handler(Looper.getMainLooper());
                        if (info == null || info.isCancelled()) {
                            mainHandler.post(() -> Toast.makeText(appContext,
                                    R.string.database_viewer_export_cancelled, Toast.LENGTH_SHORT).show());
                            return;
                        }
                        if (!info.isCompleted() || info.getErrorMessage() != null) {
                            mainHandler.post(() -> Toast.makeText(appContext,
                                    R.string.database_viewer_export_failed, Toast.LENGTH_SHORT).show());
                            return;
                        }
                        File file = ((ExportFileResult) task).getExportedFile();
                        if (file != null && file.exists()) {
                            mainHandler.post(() -> shareFile(appContext, file));
                        } else {
                            mainHandler.post(() -> Toast.makeText(appContext,
                                    R.string.database_viewer_export_failed, Toast.LENGTH_SHORT).show());
                        }
                    }
                };

        statusManager.addTaskChangeListener(listener);
        try {
            manager.submitBackgroundTask(task);
        } catch (IllegalStateException e) {
            statusManager.removeTaskChangeListener(listener);
            Toast.makeText(context, R.string.database_viewer_service_not_ready, Toast.LENGTH_SHORT).show();
        }
    }

    private static void shareFile(Context context, File file) {
        String mime = file.getName().endsWith(".csv") ? "text/csv" : "application/octet-stream";
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(Intent.createChooser(intent,
                    context.getString(R.string.database_viewer_share)));
        } catch (Exception e) {
            Toast.makeText(context, R.string.database_viewer_export_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
