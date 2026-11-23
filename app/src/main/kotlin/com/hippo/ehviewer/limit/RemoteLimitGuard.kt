/*
 * Copyright 2026 EhViewer Contributors
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

package com.hippo.ehviewer.limit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.exception.Image509Exception
import com.hippo.ehviewer.client.exception.ParseException
import com.hippo.ehviewer.network.NetworkStateManager
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.network.StatusCodeException
import com.hippo.scene.StageActivity
import com.hippo.util.IoThreadPoolExecutor
import okhttp3.Headers
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远端限流/封禁探测与冷却保护。
 *
 * 设计原则：
 * - 只有拿到服务器响应（HTTP 状态码、响应正文、明确的 limit/ban 文案）才判定为封禁；
 *   断网、DNS 失败、连接超时等交给网络状态管理器，不在这里误判。
 * - 明确 ban/限流优先使用 Retry-After 或正文中的等待时间，否则默认 1 小时。
 * - 持续低速（默认 &lt;20KB/s 连续 60 秒，0 KB/s 同样计入）判定为疑似限速，默认冷却 15 分钟；
 *   低速判定与“限流后暂停下载”是两个独立开关。
 * - 限流时记录公网 IP，并请求 EH 设置页（uconfig.php）作为判定依据：
 *   它被限流时会如实返回纯文本封禁信息和剩余时间。
 * - Cloudflare 验证页不直接当作限流：先短时暂停远端请求，再用 EH 设置页结果判定；
 *   设置页正常时按误报解除（明确 429/509/封禁页除外），设置页被 Cloudflare 拦截时再回退到论坛页。
 * - 重新进入应用或网络恢复时对比公网 IP，IP 变化则自动解除限流。
 * - 冷却期间只阻止远端下载；局域网下载是否继续由 DownloadManager 结合
 *   “下载优先从局域网”/“仅局域网模式”决定。
 */
class RemoteLimitGuard private constructor(context: Context) {

    fun interface Listener {
        fun onLimitDetected(reason: String, untilMs: Long)

        /** 限流被解除（手动清除或公网 IP 变化自动解除）。 */
        fun onLimitCleared(reason: String) {}
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var sessionGid = 0L
    private var sessionTitle: String? = null
    private var sessionStart = 0L
    private var sessionBytes = 0L
    private var sessionPages = 0
    private var sessionPeakSpeed = 0L
    private var lowSpeedSeconds = 0
    private var zeroSpeedSeconds = 0
    private var lastHealthySpeedAt = 0L
    private var lastDetectionNoticeAt = 0L
    private var lastAutoSwitchAt = 0L

    /** 自动换 IP 的“切换中”闩锁：worker 等它结束后再重试，而不是盲目重试同一 IP。 */
    @Volatile
    private var switchInFlight = false
    @Volatile
    private var switchLatch: java.util.concurrent.CountDownLatch? = null

    /** 切换失败后的短退避，避免立刻又在对封禁 IP 上重试。 */
    @Volatile
    private var switchBackoffUntil = 0L

    /** 自动换 IP 已开启但暂时无法切换时的内部重试延迟（不是用户冷却）。 */
    @Volatile
    private var limitRetryAfter = 0L

    private val probeInFlight = AtomicBoolean(false)
    private val ipCheckInFlight = AtomicBoolean(false)
    private var lastIpCheckAt = 0L
    private var scheduledResumeUntil = 0L

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun isEnabled(): Boolean =
        Settings.isLimitDetectionEnabled() || Settings.getIpSwitchEnabled()

    /** 低速/零速判定是否启用（与“限流后暂停下载”独立，且不因自动换 IP 被强制打开）。 */
    fun isLowSpeedEnabled(): Boolean =
        Settings.isLimitDetectionEnabled() && Settings.isLimitLowSpeedEnabled()

    /** 检测到限流后是否真的暂停远端下载。 */
    fun isCooldownEnabled(): Boolean = Settings.isLimitCooldownEnabled()

    fun isBlocked(): Boolean =
        isCooldownEnabled() &&
            RemoteLimitStore.getBlockedUntil(appContext) > System.currentTimeMillis()

    fun getBlockedUntil(): Long = RemoteLimitStore.getBlockedUntil(appContext)

    fun getRemainingMs(): Long =
        (RemoteLimitStore.getBlockedUntil(appContext) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun getReason(): String? = RemoteLimitStore.getReason(appContext)

    fun getEvidence(): String? = RemoteLimitStore.getEvidence(appContext)

    fun getSource(): String? = RemoteLimitStore.getSource(appContext)

    fun clearBlock() {
        RemoteLimitStore.clearBlock(appContext)
        Log.i(TAG, "Remote limit block cleared manually")
        notifyCleared("manual")
    }

    /** 自动换 IP 是否正在进行中。 */
    fun isSwitchInFlight(): Boolean = switchInFlight

    /**
     * 等待正在进行的自动切换结束。
     *
     * @return true 表示切换已经结束（成功或失败），可以用新节点重试；
     *         false 表示等待超时，调用方应让该页先失败，交给后续重试。
     */
    fun awaitSwitch(timeoutMs: Long): Boolean {
        val latch = switchLatch ?: return true
        return try {
            latch.await(timeoutMs.coerceAtLeast(0L), java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 最近一次自动切换失败后仍处于短退避期。 */
    fun isSwitchBackoff(): Boolean = System.currentTimeMillis() < switchBackoffUntil

    fun getSwitchBackoffRemainingMs(): Long =
        (switchBackoffUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    /** 自动换 IP 已开启但暂时无法切换时，worker 应等待的剩余时间。 */
    fun getLimitRetryDelayRemainingMs(): Long =
        (limitRetryAfter - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * 应用回到前台或网络恢复时调用：如果仍处于限流冷却且记录的 IP 与当前公网 IP
     * 不同，说明换了网络出口，自动解除限流。
     */
    @JvmOverloads
    fun maybeCheckIpChanged(force: Boolean = false) {
        if (!isBlocked()) return
        ensureCooldownResumeScheduled()
        if (!NetworkStateManager.isOnline()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastIpCheckAt < IP_CHECK_INTERVAL_MS) return
        if (!ipCheckInFlight.compareAndSet(false, true)) return
        lastIpCheckAt = now
        val previousIp = RemoteLimitStore.getLastIp(appContext)
        IoThreadPoolExecutor.instance.execute {
            try {
                val result = LimitProbe.fetchPublicIp(EhApplication.getOkHttpClient(appContext))
                val currentIp = result.ip
                if (currentIp.isNullOrBlank()) {
                    Log.w(TAG, "IP change check failed: ${result.error}")
                    return@execute
                }
                if (previousIp.isNullOrBlank()) {
                    RemoteLimitStore.setLastIp(appContext, currentIp)
                    Log.i(TAG, "No previous IP recorded; stored current IP $currentIp")
                    return@execute
                }
                if (!hasIpChanged(previousIp, currentIp)) {
                    Log.d(TAG, "Public IP unchanged: $currentIp")
                    val probeAt = RemoteLimitStore.getForumProbeAt(appContext)
                    if (now - probeAt > FORUM_RECHECK_INTERVAL_MS) {
                        probeForumForExistingBlock()
                    }
                    return@execute
                }
                Log.i(TAG, "Public IP changed: $previousIp -> $currentIp, clearing rate limit")
                RemoteLimitStore.setLastIp(appContext, currentIp)
                mainHandler.post {
                    RemoteLimitStore.clearBlock(appContext)
                    notifyCleared("ip_changed")
                    Toast.makeText(
                        appContext,
                        R.string.limit_auto_cleared_ip_changed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "IP change check failed", t)
            } finally {
                ipCheckInFlight.set(false)
            }
        }
    }

    private fun notifyCleared(reason: String) {
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onLimitCleared(reason)
                } catch (t: Throwable) {
                    Log.w(TAG, "Remote limit clear listener failed", t)
                }
            }
        }
    }

    /** 冷却到期时通知监听者恢复下载（进程存活时生效）。 */
    @Synchronized
    private fun ensureCooldownResumeScheduled() {
        val until = RemoteLimitStore.getBlockedUntil(appContext)
        if (until <= System.currentTimeMillis()) return
        if (until == scheduledResumeUntil) return
        scheduledResumeUntil = until
        scheduleCooldownResume(until)
    }

    private fun scheduleCooldownResume(untilMs: Long) {
        val delay = (untilMs - System.currentTimeMillis()).coerceAtLeast(0L) + 1_000L
        mainHandler.postDelayed({
            if (isBlocked()) {
                return@postDelayed
            }
            Log.i(TAG, "Rate-limit cooldown expired, resuming downloads")
            for (listener in listeners) {
                try {
                    listener.onLimitCleared("cooldown_expired")
                } catch (t: Throwable) {
                    Log.w(TAG, "Remote limit clear listener failed", t)
                }
            }
        }, delay)
    }

    // ==================== 信号入口 ====================

    /**
     * 统一检查 HTTP 响应。EhEngine 的列表/详情/页 API 都会经过这里。
     */
    fun inspectHttp(
        code: Int,
        headers: Headers?,
        body: String?,
        throwable: Throwable?,
        source: String,
    ) {
        if (!isEnabled() || isBlocked()) return
        if (LimitSignals.isCloudflareChallenge(code, headers, body)) {
            handleCloudflareChallenge(source, buildEvidence(code, body, throwable))
            return
        }
        val retryAfter = LimitSignals.parseRetryAfter(headers?.get("Retry-After"))
        val bodyDuration = LimitSignals.parseLimitDurationMs(body)
        val explicit = LimitSignals.isLimitCode(code) || LimitSignals.isLimitBody(body)
        if (explicit) {
            reportBan(
                source = source,
                evidence = buildEvidence(code, body, throwable),
                cooldownMs = firstNonZero(retryAfter, bodyDuration, DEFAULT_BAN_COOLDOWN_MS),
                reason = describeReason(code, body, throwable),
            )
            return
        }
        inspectThrowable(throwable, source)
    }

    /** 检查 HTTP 200 但正文是封禁/限流页的情况。 */
    fun inspectBody(body: String?, source: String) {
        if (!isEnabled() || isBlocked()) return
        if (LimitSignals.isCloudflareChallenge(0, null, body)) {
            handleCloudflareChallenge(source, body?.take(800))
            return
        }
        if (!LimitSignals.isLimitBody(body)) return
        reportBan(
            source = source,
            evidence = body?.take(800),
            cooldownMs = firstNonZero(
                LimitSignals.parseLimitDurationMs(body),
                DEFAULT_BAN_COOLDOWN_MS,
            ),
            reason = describeReason(0, body, null),
        )
    }

    fun inspectThrowable(throwable: Throwable?, source: String) {
        if (!isEnabled() || isBlocked() || throwable == null) return
        if (LimitSignals.isNetworkDisconnect(throwable)) {
            // 真正的断网/超时，不当作封禁
            return
        }
        val statusCode = findStatusCode(throwable)
        val body = (throwable as? ParseException)?.body
        if (LimitSignals.isCloudflareChallenge(statusCode ?: 0, null, body)) {
            handleCloudflareChallenge(source, buildEvidence(statusCode ?: 0, body, throwable))
            return
        }
        val explicit = (statusCode != null && LimitSignals.isLimitCode(statusCode)) ||
            LimitSignals.isLimitBody(body) ||
            LimitSignals.isLimitThrowable(throwable)
        if (!explicit) return
        reportBan(
            source = source,
            evidence = buildEvidence(statusCode ?: 0, body, throwable),
            cooldownMs = firstNonZero(
                LimitSignals.parseLimitDurationMs(body),
                DEFAULT_BAN_COOLDOWN_MS,
            ),
            reason = describeReason(statusCode ?: 0, body, throwable),
        )
    }

    fun report509(source: String) {
        if (!isEnabled() || isBlocked()) return
        reportBan(
            source = source,
            evidence = "509 / bandwidth limit",
            cooldownMs = DEFAULT_BAN_COOLDOWN_MS,
            reason = "509 带宽/请求限制",
        )
    }

    /**
     * Cloudflare 验证页：先暂停远端请求（不当作明确封禁，不发限流通知），
     * 然后通过 EH 设置页/论坛页判断是误报还是真实限流。
     */
    private fun handleCloudflareChallenge(source: String, evidence: String?) {
        if (!isEnabled() || isBlocked()) return
        if (!isCooldownEnabled()) {
            Log.i(TAG, "Cloudflare challenge seen, but download cooldown is disabled")
            RemoteLimitStore.setBlocked(
                appContext,
                0L,
                appContext.getString(R.string.limit_reason_cloudflare),
                "cloudflare",
                evidence?.take(800),
            )
            probeAfterBlock()
            return
        }
        val until = System.currentTimeMillis() + CLOUDFLARE_VERIFY_PAUSE_MS
        RemoteLimitStore.setBlocked(
            appContext,
            until,
            appContext.getString(R.string.limit_reason_cloudflare),
            "cloudflare",
            evidence?.take(800),
        )
        endSession("cloudflare")
        Log.i(TAG, "Cloudflare challenge detected; pausing remote downloads and verifying EH settings")
        probeAfterBlock()
        ensureCooldownResumeScheduled()
    }

    private fun reportBan(source: String, evidence: String?, cooldownMs: Long, reason: String) {
        val until = System.currentTimeMillis() + cooldownMs.coerceAtLeast(60_000L)
        markCurrentNodeBlocked(cooldownMs)
        applyBlock(reason, until, source, evidence)
    }

    /**
     * 把当前节点标记为冷却，避免节点轮回立刻又选回刚被封的 IP。
     * 以服务器返回的封禁时长为准，至少 1 分钟；用户设置的“解封时间”只作为下限参考。
     */
    private fun markCurrentNodeBlocked(banMs: Long) {
        try {
            val node = com.hippo.ehviewer.lab.ip.IpSwitchController.getInstance()
                .getCurrentNodeName() ?: return
            val configuredMs = Settings.getIpBlockDuration().toLong() * 60_000L
            val totalMs = maxOf(banMs, configuredMs, 60_000L)
            val minutes = ((totalMs + 59_999L) / 60_000L).toInt()
            com.hippo.ehviewer.lab.ip.IpPoolManager.getInstance()
                .markNodeBlocked(node, minutes)
            logSwitchAttempt("mark node blocked: $node ${minutes}min")
        } catch (t: Throwable) {
            Log.w(TAG, "mark current node blocked failed", t)
        }
    }

    private fun reportLowSpeed(speed: Long, healthyAgoMs: Long) {
        val kbps = Settings.getLimitLowSpeedKbps()
        val seconds = Settings.getLimitLowSpeedSeconds()
        val until = System.currentTimeMillis() + LOW_SPEED_COOLDOWN_MS
        val reason = if (speed <= 0L) {
            appContext.getString(R.string.limit_reason_zero_speed, seconds)
        } else {
            appContext.getString(R.string.limit_reason_low_speed, kbps, seconds)
        }
        applyBlock(
            reason = reason,
            untilMs = until,
            source = if (speed <= 0L) "zero_speed" else "low_speed",
            evidence = "speed=$speed B/s, threshold=${kbps}KB/s, " +
                "lowDuration=${lowSpeedSeconds}s, zeroDuration=${zeroSpeedSeconds}s, " +
                "lastHealthyAgo=${if (healthyAgoMs == Long.MAX_VALUE) "never" else "${healthyAgoMs}ms"}",
        )
    }

    private fun applyBlock(reason: String, untilMs: Long, source: String, evidence: String?) {
        if (tryAutoSwitchIp(reason, untilMs, source, evidence)) {
            return
        }
        applyBlockNow(reason, untilMs, source, evidence)
    }

    /**
     * 检测到限流时优先尝试切换内置 Clash 节点。
     *
     * 先做一次很短的“临时暂停”，后台切换成功后立即解除并恢复下载；
     * 切换失败再走原来的冷却逻辑。这样不会阻塞请求线程太久。
     */
    private fun tryAutoSwitchIp(
        reason: String,
        untilMs: Long,
        source: String,
        evidence: String?,
    ): Boolean {
        if (!isEnabled() || !Settings.getIpSwitchEnabled()) return false
        val controller = com.hippo.ehviewer.lab.ip.IpSwitchController.getInstance()
        val now = System.currentTimeMillis()
        if (!controller.isCoreRunning()) {
            // 自动换 IP 已开启但内核没起来：给 worker 一个短等待，避免对封禁 IP 高频重试。
            limitRetryAfter = now + AUTO_SWITCH_UNAVAILABLE_RETRY_MS
            Log.w(TAG, "Auto switch requested but core is not running; delaying retries")
            return false
        }
        if (now - lastAutoSwitchAt < AUTO_SWITCH_MIN_INTERVAL_MS) {
            // 两次封禁间隔太短：等一会再切，期间 worker 不重复打同一 IP。
            val remaining = AUTO_SWITCH_MIN_INTERVAL_MS - (now - lastAutoSwitchAt)
            limitRetryAfter = maxOf(limitRetryAfter, now + remaining.coerceAtMost(15_000L))
            return false
        }
        lastAutoSwitchAt = now
        limitRetryAfter = 0L

        if (isCooldownEnabled()) {
            // 只在用户开启冷却时设置临时暂停；自动换 IP 期间冷却默认已关闭。
            val provisionalUntil = now + AUTO_SWITCH_PAUSE_MS
            RemoteLimitStore.setBlocked(appContext, provisionalUntil, reason, source, evidence)
            ensureCooldownResumeScheduled()
        }
        endSession("rate_limited")
        switchLatch = java.util.concurrent.CountDownLatch(1)
        switchInFlight = true
        Log.i(TAG, "Rate limit hit; trying to switch IP node before blocking")
        logSwitchAttempt("auto-switch start: " + reason)

        IoThreadPoolExecutor.instance.execute {
            var node: String? = null
            try {
                node = controller.switchToNextNodeBlocking("auto-limit")
            } catch (t: Throwable) {
                Log.w(TAG, "Auto IP switch failed", t)
            }
            val switched = node
            mainHandler.post {
                if (switched != null) {
                    RemoteLimitStore.clearBlock(appContext)
                    switchBackoffUntil = 0L
                    limitRetryAfter = 0L
                    EhApplication.flushConnections()
                    Log.i(TAG, "Switched IP node to $switched; downloads resumed")
                    logSwitchAttempt("auto-switch ok: " + switched)
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.limit_switched_ip, switched),
                        Toast.LENGTH_LONG,
                    ).show()
                    notifyCleared("ip_switch")
                    try {
                        val dm = EhApplication.getDownloadManager(appContext)
                        dm?.onIpSwitched()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Resume downloads after IP switch failed", t)
                    }
                } else {
                    Log.w(TAG, "IP switch failed; applying normal cooldown")
                    applyBlockNow(reason, untilMs, source, evidence)
                    switchBackoffUntil = System.currentTimeMillis() + AUTO_SWITCH_FAIL_BACKOFF_MS
                    limitRetryAfter = switchBackoffUntil
                    logSwitchAttempt("auto-switch failed: " + reason)
                }
                // 必须等节点/冷却状态落定后再放行 worker。
                switchInFlight = false
                switchLatch?.countDown()
                switchLatch = null
            }
        }
        return true
    }

    private fun logSwitchAttempt(message: String) {
        try {
            com.hippo.ehviewer.download.DownloadLogger.peek()?.log(
                com.hippo.ehviewer.download.DownloadLogger.LogLevel.INFO,
                "RemoteLimitGuard",
                message,
                null,
                null,
            )
        } catch (_: Throwable) {
        }
    }

    private fun applyBlockNow(reason: String, untilMs: Long, source: String, evidence: String?) {
        val cooldown = isCooldownEnabled()
        val currentUntil = RemoteLimitStore.getBlockedUntil(appContext)
        val effectiveUntil = if (cooldown) maxOf(untilMs, currentUntil) else 0L
        RemoteLimitStore.setBlocked(
            appContext, effectiveUntil, reason, source, evidence,
        )
        endSession("rate_limited")
        Log.w(
            TAG,
            "Remote limit detected: source=$source reason=$reason until=$effectiveUntil " +
                "cooldown=$cooldown",
        )
        probeAfterBlock()
        if (!cooldown) {
            // 冷却关闭：只记录证据，不暂停下载。限制提示频率，避免每 2 秒刷一次。
            val now = System.currentTimeMillis()
            if (now - lastDetectionNoticeAt < DETECTION_NOTICE_INTERVAL_MS) return
            lastDetectionNoticeAt = now
            mainHandler.post {
                Toast.makeText(
                    appContext,
                    R.string.limit_detected_no_cooldown,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        ensureCooldownResumeScheduled()
        mainHandler.post {
            val remaining = (effectiveUntil - System.currentTimeMillis()).coerceAtLeast(0L)
            val text = appContext.getString(
                R.string.limit_detected_toast, reason, formatRemaining(remaining),
            )
            Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
            showNotification(reason, effectiveUntil)
            for (listener in listeners) {
                try {
                    listener.onLimitDetected(reason, effectiveUntil)
                } catch (t: Throwable) {
                    Log.w(TAG, "Remote limit listener failed", t)
                }
            }
        }
    }

    /**
     * 限流后请求 EH 设置页（uconfig.php）作为权威依据，并记录公网 IP。
     *
     * 设置页被限流时会如实返回纯文本封禁信息和到期时间；正常时返回设置页本身。
     * 只有设置页被 Cloudflare 拦截或请求失败时，才回退到论坛页。
     */
    private fun probeAfterBlock() {
        if (!probeInFlight.compareAndSet(false, true)) return
        IoThreadPoolExecutor.instance.execute {
            try {
                val client = EhApplication.getOkHttpClient(appContext)

                val settings = LimitProbe.fetchEhSettings(client)
                storeSettingsProbe(settings)
                Log.i(
                    TAG,
                    "EH settings probe: code=${settings.code} cloudflare=${settings.cloudflare} " +
                        "banned=${settings.banned} target=${settings.targetPage} " +
                        "durationMs=${settings.durationMs} " +
                        "content=${(settings.content ?: settings.error ?: "").take(300)}",
                )
                val decided = evaluateSettingsResult(settings)
                if (!decided && isBlocked()) {
                    val forum = LimitProbe.fetchForum(client)
                    storeForumProbe(forum)
                    Log.i(
                        TAG,
                        "Forum probe: code=${forum.code} cloudflare=${forum.cloudflare} " +
                            "banned=${forum.banned} durationMs=${forum.durationMs} " +
                            "content=${(forum.content ?: forum.error ?: "").take(300)}",
                    )
                    evaluateForumResult(forum, isAmbiguousSource())
                }

                val ipResult = LimitProbe.fetchPublicIp(client)
                val ip = ipResult.ip
                if (!ip.isNullOrBlank()) {
                    RemoteLimitStore.setLastIp(appContext, ip)
                    Log.i(TAG, "Rate limit public IP: $ip")
                } else {
                    Log.w(TAG, "Failed to fetch public IP: ${ipResult.error}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Rate-limit probe failed", t)
            } finally {
                probeInFlight.set(false)
            }
        }
    }

    /**
     * 已有冷却但还没有探测结果时（例如升级前创建的冷却），补一次 EH 设置页请求。
     */
    private fun probeForumForExistingBlock() {
        probeAfterBlock()
    }

    private fun storeSettingsProbe(result: LimitProbe.ForumResult) {
        RemoteLimitStore.setSettingsProbe(
            appContext,
            result.code,
            result.cloudflare,
            result.banned,
            result.content ?: result.error,
        )
    }

    private fun storeForumProbe(result: LimitProbe.ForumResult) {
        RemoteLimitStore.setForumProbe(
            appContext,
            result.code,
            result.cloudflare,
            result.banned,
            result.content ?: result.error,
        )
    }

    /**
     * EH 设置页的判定结果：
     * - 返回纯文本封禁信息 → 确认为限流，并用其中的到期时间校正冷却；
     * - 正常返回设置页 → 对 Cloudflare/低速等不确定信号判定为误报；
     * - Cloudflare 或请求失败 → 无法判断，返回 false 交给论坛页。
     */
    private fun evaluateSettingsResult(settings: LimitProbe.ForumResult): Boolean {
        if (settings.error != null && settings.code == 0) {
            Log.w(TAG, "EH settings probe failed: ${settings.error}")
            return false
        }
        if (settings.banned) {
            Log.w(TAG, "EH settings page reports a site ban")
            extendBlockFromProbe(settings)
            return true
        }
        if (settings.cloudflare) {
            Log.i(TAG, "EH settings page is behind Cloudflare; falling back to forum")
            return false
        }
        if (!isForumNormal(settings)) return false
        if (!isBlocked()) return true
        if (isAmbiguousSource()) {
            Log.i(TAG, "EH settings page loaded normally; treating block as false positive")
            mainHandler.post { clearBlockAsFalsePositive() }
        } else {
            Log.i(TAG, "EH settings page loaded normally, but block came from an explicit limit; keep it")
        }
        return true
    }

    /** 用探测到的封禁剩余时间延长冷却（只在冷却开关开启时生效）。 */
    private fun extendBlockFromProbe(result: LimitProbe.ForumResult) {
        if (!isCooldownEnabled() || !isBlocked()) return
        if (result.durationMs <= 0L) return
        val until = System.currentTimeMillis() + result.durationMs
        if (until <= RemoteLimitStore.getBlockedUntil(appContext)) return
        RemoteLimitStore.setBlocked(
            appContext,
            until,
            RemoteLimitStore.getReason(appContext) ?: appContext.getString(R.string.limit_status_title),
            RemoteLimitStore.getSource(appContext),
            RemoteLimitStore.getEvidence(appContext),
        )
        Log.i(TAG, "Ban duration from EH settings applied; cooldown until $until")
        ensureCooldownResumeScheduled()
    }

    /**
     * 论坛页只作为 EH 设置页被 Cloudflare 拦截时的备用依据：
     * - 论坛正常加载 → 对 Cloudflare/低速等不确定信号判定为误报并解除；
     * - 论坛也是限流页 / Cloudflare / 请求失败 → 暂不解除，保留冷却。
     */
    private fun evaluateForumResult(forum: LimitProbe.ForumResult, allowClear: Boolean) {
        if (!isBlocked()) return
        if (forum.error != null && forum.code == 0) {
            Log.w(TAG, "Forum probe failed; keep current block: ${forum.error}")
            return
        }
        if (forum.banned) {
            Log.w(TAG, "Forum also shows a limit page; keep current block")
            return
        }
        if (forum.cloudflare) {
            Log.i(TAG, "Forum is also behind Cloudflare; keep current block")
            return
        }
        if (isForumNormal(forum)) {
            if (allowClear) {
                Log.i(TAG, "Forum loaded normally; treating rate-limit block as false positive")
                mainHandler.post { clearBlockAsFalsePositive() }
            } else {
                Log.i(TAG, "Forum loaded normally, but block came from an explicit limit; keep it")
            }
        }
    }

    /**
     * Cloudflare/低速/零速属于不确定信号，允许用“论坛可访问”判定误报。
     * 明确 429/509/封禁页仍然保留冷却，避免把真实带宽限制当成误报。
     */
    private fun isAmbiguousSource(): Boolean =
        isAmbiguousLimitSource(RemoteLimitStore.getSource(appContext))

    private fun clearBlockAsFalsePositive() {
        if (!isBlocked()) return
        RemoteLimitStore.clearBlock(appContext)
        Log.i(TAG, "Rate-limit block cleared: forum is reachable")
        Toast.makeText(
            appContext,
            R.string.limit_false_positive_cleared,
            Toast.LENGTH_LONG,
        ).show()
        notifyCleared("forum_ok")
    }

    // ==================== 下载会话速率记录 ====================

    @Synchronized
    fun beginSession(gid: Long, title: String?) {
        if (sessionGid > 0) {
            endSession("replaced")
        }
        sessionGid = gid
        sessionTitle = title
        sessionStart = System.currentTimeMillis()
        sessionBytes = 0L
        sessionPages = 0
        sessionPeakSpeed = 0L
        lowSpeedSeconds = 0
        zeroSpeedSeconds = 0
        lastHealthySpeedAt = System.currentTimeMillis()
        Log.d(TAG, "Session begin: gid=$gid title=$title")
    }

    @Synchronized
    fun onBytes(bytes: Long) {
        if (sessionGid <= 0 || bytes <= 0) return
        sessionBytes += bytes
    }

    @Synchronized
    fun onPageSuccess() {
        if (sessionGid <= 0) return
        sessionPages++
    }

    @Synchronized
    fun onSpeedSample(speed: Long) {
        if (sessionGid <= 0) return
        if (speed > sessionPeakSpeed) sessionPeakSpeed = speed
        // 低速判定可以单独关闭：关闭后仍记录会话速率，但不会因低速触发限流。
        if (!isLowSpeedEnabled() || isBlocked()) return
        if (NetworkStateManager.shouldPauseDownloads()) {
            lowSpeedSeconds = 0
            zeroSpeedSeconds = 0
            return
        }

        val now = System.currentTimeMillis()
        val threshold = Settings.getLimitLowSpeedKbps() * 1024L
        // 近期出现过“健康速度”时，短暂 0 KB/s 不立即判定为限流。
        val healthyThreshold = maxOf(HEALTHY_MIN_SPEED_BYTES, threshold * 3L)
        if (speed >= healthyThreshold) {
            lastHealthySpeedAt = now
        }

        if (speed <= 0) {
            zeroSpeedSeconds += SPEED_SAMPLE_SECONDS
        } else {
            zeroSpeedSeconds = 0
        }

        if (speed < threshold) {
            lowSpeedSeconds += SPEED_SAMPLE_SECONDS
            if (shouldTriggerLowSpeed(
                    speed = speed,
                    lowSpeedSeconds = lowSpeedSeconds,
                    requiredSeconds = Settings.getLimitLowSpeedSeconds(),
                    sessionBytes = sessionBytes,
                    sessionPages = sessionPages,
                    thresholdBytes = threshold,
                )
            ) {
                val healthyAgo = if (lastHealthySpeedAt > 0L) {
                    now - lastHealthySpeedAt
                } else {
                    Long.MAX_VALUE
                }
                // 0 KB/s：必须连续零速达到设定时长，且最近 1 分钟内没有健康速度样本。
                if (speed <= 0) {
                    if (zeroSpeedSeconds >= Settings.getLimitLowSpeedSeconds() &&
                        isZeroSpeedConfirmed(speed, healthyAgo)
                    ) {
                        reportLowSpeed(speed, healthyAgo)
                    }
                } else {
                    reportLowSpeed(speed, healthyAgo)
                }
            }
        } else {
            lowSpeedSeconds = 0
            zeroSpeedSeconds = 0
        }
    }

    @Synchronized
    fun endSession(stopReason: String?) {
        if (sessionGid <= 0) return
        val end = System.currentTimeMillis()
        val duration = (end - sessionStart).coerceAtLeast(1L)
        val avg = sessionBytes * 1000L / duration
        val session = LimitSession(
            gid = sessionGid,
            title = sessionTitle,
            startedAt = sessionStart,
            endedAt = end,
            bytes = sessionBytes,
            durationMs = duration,
            avgSpeed = avg,
            peakSpeed = sessionPeakSpeed,
            pages = sessionPages,
            stopReason = stopReason,
        )
        RemoteLimitStore.addSession(appContext, session)
        Log.i(
            TAG,
            "Session end: gid=${session.gid} pages=${session.pages} bytes=${session.bytes}" +
                " avg=${session.avgSpeed}B/s peak=${session.peakSpeed}B/s reason=$stopReason",
        )
        sessionGid = 0L
        sessionTitle = null
        sessionStart = 0L
        sessionBytes = 0L
        sessionPages = 0
        sessionPeakSpeed = 0L
        lowSpeedSeconds = 0
        zeroSpeedSeconds = 0
        lastHealthySpeedAt = 0L
    }

    // ==================== 通知 ====================

    private fun showNotification(reason: String, untilMs: Long) {
        try {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.limit_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                manager.createNotificationChannel(channel)
            }
            val intent = Intent(appContext, MainActivity::class.java).apply {
                action = StageActivity.ACTION_START_SCENE
                putExtra(
                    StageActivity.KEY_SCENE_NAME,
                    DownloadsScene::class.java.name,
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pending = PendingIntent.getActivity(
                appContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val remaining = (untilMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(appContext.getString(R.string.limit_notification_title))
                .setContentText(
                    appContext.getString(
                        R.string.limit_notification_text,
                        reason,
                        formatRemaining(remaining),
                    )
                )
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        appContext.getString(
                            R.string.limit_notification_text,
                            reason,
                            formatRemaining(remaining),
                        )
                    )
                )
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to show remote limit notification", t)
        }
    }

    companion object {
        private const val TAG = "RemoteLimitGuard"
        private const val CHANNEL_ID = "eh_remote_limit"
        private const val NOTIFICATION_ID = 6001
        private const val DEFAULT_BAN_COOLDOWN_MS = 60L * 60L * 1000L
        private const val LOW_SPEED_COOLDOWN_MS = 15L * 60L * 1000L
        private const val LOW_SPEED_MIN_BYTES = 1024L * 1024L
        private const val SPEED_SAMPLE_SECONDS = 2
        private const val HEALTHY_MIN_SPEED_BYTES = 60L * 1024L
        private const val HEALTHY_SPEED_WINDOW_MS = 60_000L
        private const val IP_CHECK_INTERVAL_MS = 60_000L
        private const val FORUM_RECHECK_INTERVAL_MS = 5L * 60L * 1000L
        private const val CLOUDFLARE_VERIFY_PAUSE_MS = 5L * 60L * 1000L
        private const val DETECTION_NOTICE_INTERVAL_MS = 60_000L
        private const val AUTO_SWITCH_PAUSE_MS = 20_000L
        private const val AUTO_SWITCH_MIN_INTERVAL_MS = 15_000L
        private const val AUTO_SWITCH_FAIL_BACKOFF_MS = 15_000L
        private const val AUTO_SWITCH_UNAVAILABLE_RETRY_MS = 10_000L

        @Volatile
        private var INSTANCE: RemoteLimitGuard? = null

        @JvmStatic
        fun getInstance(context: Context): RemoteLimitGuard =
            INSTANCE ?: synchronized(RemoteLimitGuard::class.java) {
                INSTANCE ?: RemoteLimitGuard(context).also { INSTANCE = it }
            }

        @JvmStatic
        fun resetForTest() {
            synchronized(RemoteLimitGuard::class.java) {
                INSTANCE = null
            }
        }

        @JvmStatic
        fun formatRemaining(ms: Long): String {
            val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }

        private fun firstNonZero(vararg values: Long): Long =
            values.firstOrNull { it > 0L } ?: DEFAULT_BAN_COOLDOWN_MS

        /**
         * 连续低速/零速是否达到判定条件。
         *
         * 0 KB/s 也会计入 lowSpeedSeconds；但仍要求本次会话已下载足够数据且有成功页，
         * 避免正常空闲停顿被误判。
         */
        @JvmStatic
        internal fun shouldTriggerLowSpeed(
            speed: Long,
            lowSpeedSeconds: Int,
            requiredSeconds: Int,
            sessionBytes: Long,
            sessionPages: Int,
            thresholdBytes: Long,
        ): Boolean =
            speed < thresholdBytes &&
                lowSpeedSeconds >= requiredSeconds &&
                sessionBytes >= LOW_SPEED_MIN_BYTES &&
                sessionPages > 0

        /** 公网 IP 发生变化（任一为空时不判定为变化）。 */
        @JvmStatic
        internal fun hasIpChanged(previousIp: String?, currentIp: String?): Boolean =
            !previousIp.isNullOrBlank() &&
                !currentIp.isNullOrBlank() &&
                previousIp != currentIp

        /** 论坛页是否正常加载（2xx、目标页面、非 Cloudflare、非限流页、无请求错误）。 */
        @JvmStatic
        internal fun isForumNormal(forum: LimitProbe.ForumResult): Boolean =
            forum.error == null &&
                forum.targetPage &&
                !forum.cloudflare &&
                !forum.banned &&
                forum.code in 200..299

        /** 是否属于可以按“论坛正常”解除的模糊限流来源。 */
        @JvmStatic
        internal fun isAmbiguousLimitSource(source: String?): Boolean =
            source == "cloudflare" || source == "low_speed" || source == "zero_speed"

        /**
         * 0 KB/s 是否已经可以确认限流：最近 1 分钟内出现过健康速度时要继续等待；
         * 非 0 的低速不受这条限制。
         */
        @JvmStatic
        internal fun isZeroSpeedConfirmed(speed: Long, healthyAgoMs: Long): Boolean =
            speed > 0L || healthyAgoMs >= HEALTHY_SPEED_WINDOW_MS

        private fun findStatusCode(t: Throwable?): Int? {
            var current = t
            var depth = 0
            while (current != null && depth < 8) {
                if (current is StatusCodeException) return current.responseCode
                current = current.cause
                depth++
            }
            return null
        }

        private fun describeReason(code: Int, body: String?, throwable: Throwable?): String {
            val source = when {
                LimitSignals.isLimitBody(body) -> "站点返回限流/封禁页面"
                code == 509 -> "509 带宽/请求限制"
                code == 429 -> "HTTP 429 请求过多"
                code == 403 -> "HTTP 403 访问受限"
                code == 503 -> "HTTP 503 服务受限"
                throwable is Image509Exception -> "509 图片限制"
                else -> "站点限流"
            }
            return if (code > 0) "$source (HTTP $code)" else source
        }

        private fun buildEvidence(code: Int, body: String?, throwable: Throwable?): String {
            val parts = ArrayList<String>(3)
            if (code > 0) parts.add("code=$code")
            throwable?.message?.takeIf { it.isNotBlank() }?.let {
                parts.add("error=${it.take(300)}")
            }
            body?.takeIf { it.isNotBlank() }?.let {
                parts.add("body=${it.take(500)}")
            }
            return parts.joinToString(" | ")
        }
    }
}

