package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.lab.ip.IpSwitchController
import com.hippo.ehviewer.limit.LimitSignals
import com.hippo.ehviewer.limit.RemoteLimitGuard
import com.hippo.network.StatusCodeException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * 预下载扫描（预下载合并 / 预碰撞检测）遇到限流时的失败登记与 IP 切换后重试。
 *
 * <p>旧实现把所有网络异常都吞成 null 并直接回退正常下载，导致：
 *  - 限流时扫描静默失败，用户只看到"没合并"；
 *  - 自动换 IP 成功后也不会再扫描。
 *
 * <p>这里把限流失败登记到持久化集合，并在 IP 切换成功后由
 * [retryPending] 重新提交扫描任务。
 */
object PreDownloadScanRetryStore {

    private const val TAG = "PreDownloadRetry"
    private const val PREFS = "pre_download_scan_retry"
    private const val KEY_PENDING = "pending_gids"

    @Synchronized
    fun markFailed(context: Context, gid: Long, reason: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet(KEY_PENDING, emptySet()) ?: emptySet())
        set.add(gid.toString())
        prefs.edit().putStringSet(KEY_PENDING, set).apply()
        Log.w(TAG, "Predownload scan failed gid=$gid, reason=$reason")
    }

    @Synchronized
    fun clear(context: Context, gid: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet(KEY_PENDING, emptySet()) ?: emptySet())
        if (set.remove(gid.toString())) {
            prefs.edit().putStringSet(KEY_PENDING, set).apply()
        }
    }

    @Synchronized
    fun pendingGids(context: Context): List<Long> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_PENDING, emptySet()) ?: emptySet()
        val result = ArrayList<Long>(set.size)
        for (value in set) {
            value.toLongOrNull()?.let { result.add(it) }
        }
        result.sort()
        return result
    }

    fun isPending(context: Context, gid: Long): Boolean =
        pendingGids(context).contains(gid)

    @Synchronized
    fun clearAll(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING).apply()
    }
}

object PreDownloadScanRetryCoordinator {

    private const val TAG = "PreDownloadRetry"
    private const val DEFAULT_WAIT_MS = 120_000L
    private const val POLL_MS = 500L

    /** 当前异常是否应视为限流/封禁。 */
    fun isLimitFailure(context: Context, error: Throwable?): Boolean {        if (error == null) return false
        val guard = RemoteLimitGuard.getInstance(context.applicationContext)
        if (guard.isBlocked() || guard.isSwitchInFlight()) return true
        val statusCode = findStatusCode(error)
        val body = (error as? com.hippo.ehviewer.client.exception.ParseException)?.body
        return LimitSignals.isLimitCode(statusCode ?: 0) ||
            LimitSignals.isLimitBody(body) ||
            LimitSignals.isLimitThrowable(error)
    }

    /** 当前是否处于限流冷却或正在自动换 IP。 */
    fun isLimitState(context: Context): Boolean {
        val guard = RemoteLimitGuard.getInstance(context.applicationContext)
        return guard.isBlocked() || guard.isSwitchInFlight()
    }

    /**
     * 等待自动换 IP 完成。
     *
     * @return true 表示节点已切换且限流状态解除，可以立即重试；
     *         false 表示未开启自动换 IP、切换失败或超时。
     */
    suspend fun awaitIpSwitch(context: Context, timeoutMs: Long = DEFAULT_WAIT_MS): Boolean {
        if (!Settings.getIpSwitchEnabled()) return false
        val app = context.applicationContext
        val controller = IpSwitchController.getInstance()
        val guard = RemoteLimitGuard.getInstance(app)
        val startGeneration = controller.switchGeneration
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(POLL_MS)
        while (System.currentTimeMillis() < deadline) {
            if (!guard.isSwitchInFlight()) {
                val generationChanged = controller.switchGeneration > startGeneration
                if (generationChanged && !guard.isBlocked()) {
                    return true
                }
                // 没有正在切换、也没有限流：说明本次异常不是自动换 IP 能处理的
                if (!guard.isBlocked() && !guard.isSwitchBackoff()) {
                    return false
                }
            }
            delay(POLL_MS)
        }
        Log.w(TAG, "Await IP switch timed out")
        return false
    }

    /** IP 切换成功后的统一入口。 */
    fun retryPending(context: Context) {
        val app = context.applicationContext
        val gids = PreDownloadScanRetryStore.pendingGids(app)
        if (gids.isEmpty()) return
        try {
            val dm = EhApplication.getDownloadManager(app)
            Log.i(TAG, "IP switched; retrying predownload scans: $gids")
            dm.retryPreDownloadScan(gids)
        } catch (t: Throwable) {
            Log.w(TAG, "Retry pending predownload scans failed", t)
        }
    }

    private fun findStatusCode(error: Throwable): Int? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            if (current is StatusCodeException) return current.responseCode
            current = current.cause
            depth++
        }
        return null
    }
}

/** 限流导致预下载扫描无法继续；由任务捕获并转为失败状态。 */
class PreDownloadLimitException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
