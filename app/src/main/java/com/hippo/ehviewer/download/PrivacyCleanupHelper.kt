package com.hippo.ehviewer.download

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import java.io.File

/**
 * 隐私清理助手：在 Settings.downloadPrivateMode == true 时，
 * 下载完成后延迟 3 分钟清理系统 DownloadManager 痕迹。
 *
 * 删除范围：
 *  - SystemDownloadTask 表中 gid 所有行
 *  - 系统 DM 中 gid 所有 downloadId 任务（dm.remove 会同时删目标文件）
 *  - 如果下载位置是 Ehviewer 专属目录（dataDir / externalFilesDir），
 *    删除 SpiderDen 里这个 gid 的整个画廊目录
 *
 * 不删除范围：
 *  - Ehviewer 内的 DownloadInfo 行（保留）
 *  - Ehviewer 内的 HistoryInfo 行（保留）
 */
object PrivacyCleanupHelper {

    private const val TAG = "PrivacyCleanupHelper"
    private const val DELAY_MS = 3L * 60L * 1000L  // 3 分钟

    /**
     * 主入口：调度一个延迟清理任务。
     * 多次调用同一个 gid 会覆盖之前的延迟（postDelayed 唯一 task id）。
     */
    fun scheduleCleanup(context: Context, gid: Long) {
        if (!Settings.getDownloadPrivateMode()) {
            Log.d(TAG, "scheduleCleanup: private mode off, skip gid=$gid")
            return
        }
        // 用 class-level 静态 map 跟踪 pending 任务，避免重复 post
        synchronized(sPending) {
            sPending[gid]?.let { HANDLER.removeCallbacks(it) }
            val runnable = Runnable {
                sPending.remove(gid)
                executeCleanup(context.applicationContext, gid)
            }
            sPending[gid] = runnable
            HANDLER.postDelayed(runnable, DELAY_MS)
        }
    }

    /**
     * 立即执行清理（不延迟）。可用于 stopAll / delete 时主动清理。
     */
    fun cleanupForGid(context: Context, gid: Long) {
        executeCleanup(context.applicationContext, gid)
    }

    private val HANDLER = android.os.Handler(android.os.Looper.getMainLooper())
    private val sPending = java.util.concurrent.ConcurrentHashMap<Long, Runnable>()

    private fun executeCleanup(appContext: Context, gid: Long) {
        // 期间用户关了开关就跳过
        if (!Settings.getDownloadPrivateMode()) {
            Log.d(TAG, "executeCleanup: private mode off at execution time, skip gid=$gid")
            return
        }

        // 1. 系统 DM 痕迹
        try {
            SystemDMBackend.getInstance(appContext).cleanupForGid(gid)
        } catch (e: Exception) {
            Log.w(TAG, "SystemDMBackend.cleanupForGid failed for $gid", e)
        }

        // 2. SpiderDen 目录（仅 Ehviewer 专属下载位置时）
        val info: DownloadInfo? = try {
            com.hippo.ehviewer.EhDB.getDownloadInfo(gid)
        } catch (e: Exception) {
            null
        }
        if (info != null && isEhviewerOwnedLocation()) {
            try {
                val dir = SpiderDen.getExistingGalleryDownloadDir(info)
                dir?.delete()
                Log.i(TAG, "Deleted SpiderDen directory for gid=$gid")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete SpiderDen directory for gid=$gid", e)
            }
        } else {
            Log.i(TAG, "Download location not ehviewer-owned; images preserved for gid=$gid")
        }
    }

    /**
     * 判断当前下载位置是否在 Ehviewer 专属目录内（applicationInfo.dataDir
     * 或 getExternalFilesDir(null)）。
     *
     * SAF Tree URI（content://）不算 Ehviewer 专属（用户在公共位置挑的）。
     * 公共 Downloads（默认未修改的下载位置）也不算 Ehviewer 专属。
     */
    private fun isEhviewerOwnedLocation(): Boolean {
        val location = Settings.getDownloadLocation() ?: return false
        val uri = location.uri ?: return false
        val scheme = uri.scheme ?: return false

        // SAF Tree URI → 用户显式选择的公共目录，不属于 ehviewer 专属
        if ("content".equals(scheme, ignoreCase = true)) return false

        // file:// 路径
        if (!"file".equals(scheme, ignoreCase = true)) return false
        val path = uri.path ?: return false

        val ctx = com.hippo.ehviewer.EhApplication.getInstance()
        val appDir = try {
            ctx.applicationInfo.dataDir
        } catch (e: Exception) {
            null
        }
        val extDir = try {
            ctx.getExternalFilesDir(null)
        } catch (e: Exception) {
            null
        }

        return try {
            val canonicalPath = File(path).canonicalPath
            val appDirCanonical = appDir?.let { File(it).canonicalPath }
            val extDirCanonical = extDir?.canonicalPath
            (appDirCanonical != null && canonicalPath.startsWith(appDirCanonical)) ||
                    (extDirCanonical != null && canonicalPath.startsWith(extDirCanonical))
        } catch (e: Exception) {
            false
        }
    }
}