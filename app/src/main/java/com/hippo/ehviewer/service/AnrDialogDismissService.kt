package com.hippo.ehviewer.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hippo.ehviewer.BackgroundTaskManager
import com.hippo.ehviewer.Settings
import java.util.Locale

/**
 * 系统 ANR 弹窗自动清除服务（无障碍）。
 *
 * 背景：系统"应用无响应"（App isn't responding）弹窗属于 system_server 进程，
 * 应用无法通过 WindowManager/反射直接关闭；无 root 情况下唯一可靠的自动化方案
 * 就是无障碍服务——读取弹窗内容并自动点击其中的"等待 (Wait)"按钮，
 * 让应用在主线程恢复后继续运行，后台任务（压缩/合并/扫描等）不被误杀。
 *
 * 触发方式（双通道）：
 * 1. 事件驱动：无障碍监听窗口状态/内容变化事件，发现 ANR 弹窗立即处理；
 * 2. 定时驱动：[HealthWatchdog] 检测到主线程阻塞（早于系统 ANR 判定）后，
 *    周期调用 [requestDismiss] 主动扫描并点击（覆盖弹窗刚弹出的事件空窗期）。
 *
 * 生效门槛（缺一不可，避免平时误干预）：
 * - Settings.isAnrAutoDismissEnabled() 为 true；
 * - BackgroundTaskManager 存在活跃后台任务（用户选择"仅后台任务运行时生效"）；
 * - 窗口特征匹配：存在 ANR 特征文本（"无响应"/"isn't responding" 等），
 *   且存在可点击的"等待/Wait"按钮；系统窗口（包名 android / systemui）之外
 *   的窗口需同时满足两条特征才处理，防止误点应用自身界面。
 */
class AnrDialogDismissService : AccessibilityService() {

    companion object {
        private const val TAG = "AnrDialogDismiss"

        // ANR 弹窗特征文本（标题区域）。中英文覆盖；其他语言的 ROM 靠
        // "特征文本 + 等待按钮" 双特征兜底，匹配不到时不做任何点击（安全降级）。
        private val ANR_TITLE_HINTS =
            listOf("无响应", "没有响应", "isn't responding", "not responding")

        // ANR 弹窗中"等待"按钮文本：点击后应用继续运行，进程不被杀死。
        // 中文 ROM 常见 "等待"/"等待应用"，AOSP 英文为 "Wait"。
        private const val WAIT_EN = "wait"
        private const val WAIT_ZH_PREFIX = "等待"

        // 事件驱动路径的尝试节流：窗口内容变化事件非常频繁，避免风暴期间反复遍历节点树
        private const val EVENT_ATTEMPT_INTERVAL_MS = 500L

        // 看门狗定时驱动路径的防抖间隔（HealthWatchdog 心跳 2s，略小于心跳即可全生效）
        private const val REQUEST_INTERVAL_MS = 1500L

        // 点击"等待"后的冷却期：弹窗消失过程中事件仍会到达，避免重复点击
        private const val CLICK_COOLDOWN_MS = 3000L

        @Volatile
        private var sInstance: AnrDialogDismissService? = null

        @Volatile
        private var sLastEventAttemptAt = 0L

        @Volatile
        private var sLastRequestAt = 0L

        @Volatile
        private var sLastClickAt = 0L

        /** 无障碍服务是否已连接（用户已在系统设置中开启） */
        @JvmStatic
        fun isServiceConnected(): Boolean = sInstance != null

        /**
         * 请求立即扫描并清除当前屏幕上的 ANR 弹窗。
         * 由 [HealthWatchdog] 在主线程阻塞期间定时调用；
         * 服务未连接（用户未开启无障碍）时静默忽略。
         */
        @JvmStatic
        fun requestDismiss() {
            val service = sInstance ?: return
            val now = SystemClock.uptimeMillis()
            if (now - sLastRequestAt < REQUEST_INTERVAL_MS) return
            sLastRequestAt = now
            try {
                service.tryDismissAnrDialog("watchdog")
            } catch (e: Throwable) {
                Log.w(TAG, "requestDismiss failed", e)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        sInstance = this
        Log.i(TAG, "AnrDialogDismissService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        sInstance = null
        Log.i(TAG, "AnrDialogDismissService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        sInstance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        // 无障碍被系统中断（如其他服务抢占）时无需处理
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> Unit
            else -> return
        }
        // 只处理系统窗口的事件，减少应用自身 UI 事件风暴下的无效遍历
        val pkg = event.packageName?.toString()
        if (pkg != null && !isSystemWindowPackage(pkg)) return

        val now = SystemClock.uptimeMillis()
        if (now - sLastEventAttemptAt < EVENT_ATTEMPT_INTERVAL_MS) return
        sLastEventAttemptAt = now
        try {
            tryDismissAnrDialog("event")
        } catch (e: Throwable) {
            Log.w(TAG, "onAccessibilityEvent dismiss failed", e)
        }
    }

    /**
     * 扫描当前窗口，若存在系统 ANR 弹窗则点击其中的"等待"按钮。
     *
     * @param source 触发来源（event / watchdog），仅用于日志
     * @return 是否执行了点击
     */
    private fun tryDismissAnrDialog(source: String): Boolean {
        // 生效门槛 1：设置开关
        if (!Settings.isAnrAutoDismissEnabled()) return false

        // 生效门槛 2：仅当存在活跃后台任务时工作（平时绝不干扰）
        if (!isBackgroundTaskActive()) return false

        // 点击后的冷却期内不再重复处理
        val now = SystemClock.uptimeMillis()
        if (now - sLastClickAt < CLICK_COOLDOWN_MS) return false

        var clicked = false
        for (root in collectWindowRoots()) {
            if (clicked) break
            val pkg = try { root.packageName?.toString() } catch (_: Throwable) { null }
            val systemWindow = pkg == null || isSystemWindowPackage(pkg)
            // 特征一：窗口内存在 ANR 特征文本
            if (!hasAnrTitleHint(root)) continue
            // 特征二：窗口内存在可点击的"等待"按钮
            val waitNode = findWaitButton(root) ?: continue
            // 系统窗口直接处理；非 system 窗口（ROM 差异兜底）需双特征同时
            // 成立（此处已满足）才处理，防止误点应用自身界面
            if (!systemWindow && pkg != null) {
                Log.i(TAG, "ANR-like dialog in non-system window pkg=$pkg, dismissing with dual-signature")
            }
            clicked = waitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(
                TAG,
                "ANR dialog dismissed via '$source': click(wait)=" + clicked
                    + ", pkg=" + pkg
            )
        }
        if (clicked) {
            sLastClickAt = now
        }
        return clicked
    }

    /** 是否存在活跃后台任务（BackgroundTaskManager 未初始化时视为无） */
    private fun isBackgroundTaskActive(): Boolean {
        return try {
            BackgroundTaskManager.getInstance().getTaskStatusManager().activeTaskCount > 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun isSystemWindowPackage(pkg: String): Boolean {
        return pkg == "android" || pkg == "com.android.systemui" || pkg.startsWith("com.miui.")
    }

    /**
     * 收集可扫描的窗口根节点：优先遍历无障碍可见的所有窗口
     * （ANR 弹窗以系统窗口形式悬浮于应用之上），失败时回退到活动窗口。
     */
    private fun collectWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        try {
            val wins = windows
            if (wins != null) {
                val seenIds = HashSet<Int>()
                for (w in wins) {
                    val r = w.root
                    if (r != null && seenIds.add(w.id)) {
                        roots.add(r)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        if (roots.isEmpty()) {
            try {
                rootInActiveWindow?.let { roots.add(it) }
            } catch (_: Throwable) {
            }
        }
        return roots
    }

    /**
     * 窗口内是否存在 ANR 特征文本（"无响应"/"isn't responding" 等）。
     */
    private fun hasAnrTitleHint(root: AccessibilityNodeInfo): Boolean {
        for (hint in ANR_TITLE_HINTS) {
            try {
                if (root.findAccessibilityNodeInfosByText(hint).isNotEmpty()) {
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    /**
     * 在窗口节点树中查找"等待"按钮，返回可点击的节点（必要时向上找可点击祖先）。
     * 找不到返回 null。
     */
    private fun findWaitButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = ArrayList<AccessibilityNodeInfo>(4)
        try {
            candidates.addAll(root.findAccessibilityNodeInfosByText(WAIT_ZH_PREFIX))
            candidates.addAll(root.findAccessibilityNodeInfosByText(WAIT_EN))
        } catch (_: Throwable) {
            return null
        }
        for (node in candidates) {
            val text = nodeText(node)
            if (!isWaitButtonLabel(text)) continue
            val clickable = findClickableSelfOrAncestor(node) ?: continue
            return clickable
        }
        return null
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        val text = try { node.text?.toString() } catch (_: Throwable) { null } ?: ""
        val desc = try { node.contentDescription?.toString() } catch (_: Throwable) { null } ?: ""
        return when {
            text.isNotEmpty() && desc.isNotEmpty() -> "$text/$desc"
            text.isNotEmpty() -> text
            else -> desc
        }
    }

    private fun isWaitButtonLabel(rawText: String): Boolean {
        val t = rawText.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return false
        // 英文：精确匹配 "wait"（避免把含 wait 的长文案误当按钮）
        if (t == WAIT_EN) return true
        // 中文：允许 "等待"/"等待应用" 等以"等待"开头的短文案
        return rawText.trim().startsWith(WAIT_ZH_PREFIX) && rawText.trim().length <= 6
    }

    /** 返回节点自身或最近的可点击祖先（按钮有时不可点击而由父容器代理） */
    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var depth = 0
        while (cur != null && depth < 6) {
            val clickable = try { cur.isClickable } catch (_: Throwable) { false }
            if (clickable) return cur
            cur = try { cur.parent } catch (_: Throwable) { null }
            depth++
        }
        return null
    }
}
