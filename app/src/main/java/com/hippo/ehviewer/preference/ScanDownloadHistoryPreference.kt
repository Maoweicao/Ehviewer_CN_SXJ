package com.hippo.ehviewer.preference

import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.Preference
import com.hippo.ehviewer.BackgroundTaskManager
import com.hippo.ehviewer.R
import com.hippo.ehviewer.task.ScanDownloadHistoryTask

/**
 * 补建下载历史记录的 Preference
 * 点击后启动 ScanDownloadHistoryTask 后台任务
 */
class ScanDownloadHistoryPreference : Preference {

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
        val task = ScanDownloadHistoryTask(context)
        taskManager.submitBackgroundTask(task)
        Toast.makeText(context, R.string.scan_download_history_started, Toast.LENGTH_SHORT).show()
    }
}
