package com.hippo.ehviewer.download;

import android.content.Context;

import com.hippo.app.CheckBoxDialogBuilder;
import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.task.impl.DeleteRangeDownloadTask;
import com.hippo.lib.yorozuya.collect.LongList;

import java.util.List;

/**
 * 下载画廊删除的统一入口。
 *
 * 前台删除只提交后台任务（DeleteRangeDownloadTask），由后台任务依次完成：
 *  1. 写入 Download_history 表
 *  2. 删除数据库记录
 *  3. 清理本地文件（deleteFiles=true 复用清理冗余逻辑永久删除；
 *     deleteFiles=false 将画廊目录移入回收站 .recycle_bin）
 */
public final class DownloadDeleteHelper {

    private DownloadDeleteHelper() {
    }

    /** 单个画廊删除对话框，含"删除本地文件"复选框。 */
    public static void showDeleteDialog(Context context, GalleryInfo info) {
        CheckBoxDialogBuilder builder = new CheckBoxDialogBuilder(context,
                context.getString(R.string.download_remove_dialog_message, info.title),
                context.getString(R.string.download_remove_dialog_check_text),
                Settings.getRemoveImageFiles());
        builder.setTitle(R.string.download_remove_dialog_title);
        builder.setPositiveButton(android.R.string.ok, (d, w) -> {
            boolean deleteFiles = builder.isChecked();
            Settings.putRemoveImageFiles(deleteFiles);
            performDelete(context, info, deleteFiles);
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    /** 批量删除对话框，含"删除本地文件"复选框。 */
    public static void showDeleteRangeDialog(Context context, List<? extends GalleryInfo> list) {
        CheckBoxDialogBuilder builder = new CheckBoxDialogBuilder(context,
                context.getString(R.string.download_remove_dialog_message_2, list.size()),
                context.getString(R.string.download_remove_dialog_check_text),
                Settings.getRemoveImageFiles());
        builder.setTitle(R.string.download_remove_dialog_title);
        builder.setPositiveButton(android.R.string.ok, (d, w) -> {
            boolean deleteFiles = builder.isChecked();
            Settings.putRemoveImageFiles(deleteFiles);
            performDeleteRange(context, list, deleteFiles);
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    /** 执行单个画廊删除（提交后台任务）。 */
    public static void performDelete(Context context, GalleryInfo info, boolean deleteFiles) {
        if (info == null) {
            return;
        }
        DownloadManager dm = EhApplication.getDownloadManager(context);
        if (dm == null) {
            return;
        }
        LongList gidList = new LongList(1);
        gidList.add(info.gid);
        submitDeleteTask(context, dm, gidList, deleteFiles);
    }

    /** 执行批量画廊删除（提交后台任务）。 */
    public static void performDeleteRange(Context context, List<? extends GalleryInfo> list, boolean deleteFiles) {
        if (list == null || list.isEmpty()) {
            return;
        }
        DownloadManager dm = EhApplication.getDownloadManager(context);
        if (dm == null) {
            return;
        }
        LongList gidList = new LongList(list.size());
        for (GalleryInfo info : list) {
            if (info != null) {
                gidList.add(info.gid);
            }
        }
        if (gidList.size() <= 0) {
            return;
        }
        submitDeleteTask(context, dm, gidList, deleteFiles);
    }

    private static void submitDeleteTask(Context context, DownloadManager dm, LongList gidList, boolean deleteFiles) {
        // 立即从内存列表和数据库中移除画廊，让用户能即时看到效果
        // 后台任务将负责写入下载历史和清理本地文件
        dm.deleteRangeDownload(gidList);

        DeleteRangeDownloadTask task = new DeleteRangeDownloadTask(context, dm, gidList, deleteFiles, null);
        BackgroundTaskManager.getInstance().submitBackgroundTask(task);
    }
}
