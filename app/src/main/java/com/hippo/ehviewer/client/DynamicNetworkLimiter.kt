package com.hippo.ehviewer.client

import java.util.concurrent.Semaphore

/**
 * 可动态调整上限的全局网络并发限流器。
 *
 * 背景：EhEngine 的请求全部是同步 [java.util.concurrent.Callable] 内的
 * `call.execute()`，不受 OkHttp Dispatcher（仅约束异步 enqueue）限制；
 * 大规模任务时曾观测到单主机 161 个在飞请求，白白占用内存、Socket 与
 * 系统调度资源。本限流器在 EhEngine 侧为同步请求加上全局在飞上限。
 *
 * 动态调整语义（设置项即时生效）：
 * - 扩容：立即补充 permit；
 * - 缩容：先回收当前空闲的多余 permit（[Semaphore.tryAcquire] 逐个回收），
 *   已被占用的部分由 [release] 渐进收敛（归还时若空闲已达上限则不再归还），
 *   无需等待持有者归还、也不会出现 permit 漂移。
 *
 * 公平性：底层为 FIFO 公平信号量，先到先得，避免下载线程长期饿死页面请求。
 */
class DynamicNetworkLimiter(initialLimit: Int) {

    private val semaphore = Semaphore(0, true)

    // 许可占用计数（在飞执行 + 等待许可的调用方），供资源查看器展示
    private val activeCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var limit: Int = 0

    init {
        limit = initialLimit.coerceAtLeast(1)
        semaphore.release(limit)
    }

    /** 当前生效的并发上限 */
    fun currentLimit(): Int = limit

    /** 当前许可占用数（在飞 + 等待），供资源查看器展示 */
    fun activeCount(): Int = activeCount.get()

    /**
     * 动态调整并发上限。扩容立即生效；缩容时空闲 permit 立即回收，
     * 在飞部分随请求结束渐进收敛到新上限。
     */
    @Synchronized
    fun resize(newLimit: Int) {
        val target = newLimit.coerceAtLeast(1)
        if (target == limit) return
        if (target > limit) {
            semaphore.release(target - limit)
        } else {
            // 立即回收空闲的多余 permit（不会抢占已排队等待的调用方）；
            // 被占用的 permit 由 release() 渐进回收
            while (semaphore.availablePermits() > target && semaphore.tryAcquire()) {
                // 回收
            }
        }
        limit = target
    }

    /** 获取一个在飞许可（不可中断，与同步 execute 的语义一致） */
    fun acquire() {
        activeCount.incrementAndGet()
        semaphore.acquireUninterruptibly()
    }

    /** 归还一个在飞许可；缩容期间空闲已达标时不再归还（渐进收敛） */
    fun release() {
        activeCount.decrementAndGet()
        if (semaphore.availablePermits() >= limit) {
            return
        }
        semaphore.release()
    }
}
