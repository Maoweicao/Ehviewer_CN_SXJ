package com.hippo.ehviewer.download;

import android.content.Context;
import android.widget.Toast;

import com.hippo.app.CheckBoxDialogBuilder;
import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.task.impl.DeleteRangeDownloadTask;
import com.hippo.lib.yorozuya.collect.LongList;
import kotlin.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载画廊删除的统一入口。
 *
 * 删除流程全部由后台任务（DeleteRangeDownloadTask）完成：
 *  1. 确认被移除的画廊是否存在于下载历史中，如果不存在补充下载历史表
 *  2. 从数据库中的下载表中移除对应条目
 *  3. 如果勾选了删除图像文件直接删除，否则移动文件夹到回收站中
 *
 * 前台仅负责：收集 DownloadInfo、从内存列表移除（即时刷新 UI）、提交后台任务。
 */
public final class DownloadDeleteHelper {

    private DownloadDeleteHelper() {
    }

    /** 单个画廊删除对话框，含"删除本地文件"复选框。 */
    public static void showDeleteDialog(Context context, GalleryInfo info) {
        DeleteProtectHelper.warmUpAsync();
        CheckBoxDialogBuilder builder = new CheckBoxDialogBuilder(context,
                context.getString(R.string.download_remove_dialog_message, info.title),
                context.getString(R.string.download_remove_dialog_check_text),
                Settings.getRemoveImageFiles());
        builder.setTitle(R.string.download_remove_dialog_title);
        builder.setPositiveButton(android.R.string.ok, (d, w) -> {
            boolean deleteFiles = builder.isChecked();
            Settings.putRemoveImageFiles(deleteFiles);
            // 删除保护：不阻止，只在原有确认之后再问一次。用户再确认就无视规则照删。
            DeleteProtectHelper.Protection protection = DeleteProtectHelper.isProtected(info);
            if (protection != null) {
                confirmProtectedDelete(context, protection, () -> performDelete(context, info, deleteFiles));
            } else {
                performDelete(context, info, deleteFiles);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    /**
     * 受保护画廊的二次删除确认。
     *
     * 列出让它受保护的原因（「已加入收藏」/「吸收过 N 个画廊」），
     * 再点一次「仍要删除」就执行 onConfirm —— 无视所有保护规则。
     */
    public static void confirmProtectedDelete(Context context,
                                              DeleteProtectHelper.Protection protection,
                                              final Runnable onConfirm) {
        if (protection == null || onConfirm == null) {
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.delete_protect_dialog_title)
                .setMessage(context.getString(R.string.delete_protect_dialog_message,
                        protection.describe(context)))
                .setPositiveButton(R.string.delete_protect_dialog_confirm, (d, w) -> onConfirm.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 批量删除对话框，含"删除本地文件"复选框。 */
    public static void showDeleteRangeDialog(Context context, List<? extends GalleryInfo> list) {
        DeleteProtectHelper.warmUpAsync();
        final DeleteProtectHelper.SplitResult split = splitProtected(list);
        List<? extends GalleryInfo> deletable = split.deletable;
        int protectedCount = split.getProtectedCount();
        if (deletable.isEmpty()) {
            // 全被保护住了，没什么可删的
            Toast.makeText(context,
                    context.getString(R.string.delete_protect_batch_skipped, protectedCount),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        CheckBoxDialogBuilder builder = new CheckBoxDialogBuilder(context,
                buildRangeMessage(context, deletable.size(), protectedCount),
                context.getString(R.string.download_remove_dialog_check_text),
                Settings.getRemoveImageFiles());
        builder.setTitle(R.string.download_remove_dialog_title);
        builder.setPositiveButton(android.R.string.ok, (d, w) -> {
            boolean deleteFiles = builder.isChecked();
            Settings.putRemoveImageFiles(deleteFiles);
            performDeleteRange(context, deletable, deleteFiles);
            notifySkipped(context, protectedCount);
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    /**
     * 批量删除的确认文案。有受保护条目时额外说明会跳过几个。
     */
    public static String buildRangeMessage(Context context, int deletableCount, int protectedCount) {
        String base = context.getString(R.string.download_remove_dialog_message_2, deletableCount);
        if (protectedCount <= 0) {
            return base;
        }
        return base + "\n"
                + context.getString(R.string.delete_protect_batch_confirm, protectedCount);
    }

    /** 拆分选中项为「可删」与「受保护」。规则全关时原样返回，不做无谓计算。 */
    public static DeleteProtectHelper.SplitResult splitProtected(
            List<? extends GalleryInfo> list) {
        if (list == null || list.isEmpty()) {
            return new DeleteProtectHelper.SplitResult(
                    new ArrayList<GalleryInfo>(),
                    new ArrayList<Pair<GalleryInfo, DeleteProtectHelper.Protection>>());
        }
        return DeleteProtectHelper.splitProtected(new ArrayList<GalleryInfo>(list));
    }

    /** 删除完成后告诉用户跳过了几个受保护画廊。 */
    public static void notifySkipped(Context context, int protectedCount) {
        if (protectedCount <= 0) {
            return;
        }
        Toast.makeText(context,
                context.getString(R.string.delete_protect_batch_skipped, protectedCount),
                Toast.LENGTH_SHORT).show();
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

    /**
     * 提交删除后台任务。
     *
     * 流程：
     *  1. 先收集 DownloadInfo 对象（此时仍在 DownloadManager 内存中）
     *  2. 从 DownloadManager 内存列表中移除（即时刷新 UI，不再显示在下载列表中）
     *  3. 提交后台任务，由后台依次完成：补充下载历史 → 删除数据库记录 → 清理本地文件
     */
    private static void submitDeleteTask(Context context, DownloadManager dm, LongList gidList, boolean deleteFiles) {
        // 第 1 步：收集 DownloadInfo 对象（必须在移除前完成）
        List<DownloadInfo> infoList = new ArrayList<>(gidList.size());
        for (int i = 0, n = gidList.size(); i < n; i++) {
            long gid = gidList.get(i);
            DownloadInfo info = dm.getDownloadInfo(gid);
            if (info != null) {
                infoList.add(info);
            }
        }

        // 第 2 步：从 DownloadManager 内存列表中移除（即时刷新 UI）
        // 不调用 deleteRangeDownload，因为数据库记录由后台任务按步骤删除
        dm.removeFromMemoryRange(gidList);

        // 第 3 步：提交后台任务（携带 DownloadInfo 列表，后台任务可正常访问）
        DeleteRangeDownloadTask task = new DeleteRangeDownloadTask(context, dm, infoList, deleteFiles, null);
        BackgroundTaskManager.getInstance().submitBackgroundTask(task);
    }
}
