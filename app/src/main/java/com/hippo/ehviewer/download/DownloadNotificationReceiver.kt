package com.hippo.ehviewer.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hippo.ehviewer.EhApplication

class DownloadNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (ACTION_STOP_ALL == intent.action) {
            val dm = EhApplication.getDownloadManager(context)
            dm.stopAllDownload()
            DownloadWorker.cancel(context)
        }
    }

    companion object {
        const val ACTION_STOP_ALL = "com.hippo.ehviewer.download.STOP_ALL"
    }
}
