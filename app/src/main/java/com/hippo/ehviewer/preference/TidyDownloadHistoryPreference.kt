package com.hippo.ehviewer.preference

import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.hippo.ehviewer.BackgroundTaskManager
import com.hippo.ehviewer.R
import com.hippo.ehviewer.task.FileDisposal
import com.hippo.ehviewer.task.TidyDownloadHistoryTask

/**
 * 设置页“梳理下载历史”入口。
 *
 * 先让用户在“移入回收站 / 永久删除”之间二选一；选永久删除时再确认一次。
 */
class TidyDownloadHistoryPreference : Preference {

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    override fun onClick() {
        super.onClick()
        val taskManager = BackgroundTaskManager.getInstance()
        if (taskManager.taskStatusManager.activeUniqueNonDownloadTask != null) {
            Toast.makeText(context, R.string.background_task_unique_running, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.tidy_download_history_title)
            .setMessage(R.string.tidy_download_history_message)
            .setPositiveButton(R.string.tidy_download_history_recycle_bin) { _, _ ->
                start(FileDisposal.RECYCLE_BIN)
            }
            .setNeutralButton(R.string.tidy_download_history_permanent) { _, _ ->
                confirmPermanent()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmPermanent() {
        AlertDialog.Builder(context)
            .setTitle(R.string.tidy_download_history_permanent_title)
            .setMessage(R.string.tidy_download_history_permanent_message)
            .setPositiveButton(R.string.tidy_download_history_permanent) { _, _ ->
                start(FileDisposal.PERMANENT_DELETE)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun start(disposal: FileDisposal) {
        val taskManager = BackgroundTaskManager.getInstance()
        taskManager.submitBackgroundTask(TidyDownloadHistoryTask(context, disposal))
        Toast.makeText(context, R.string.tidy_download_history_started, Toast.LENGTH_SHORT).show()
    }
}
