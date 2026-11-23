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

package com.hippo.ehviewer.lab

/**
 * 配对尝试限流器
 *
 * 配对码是 6 位数字（约 100 万种组合），且 `/pair` 为了首次配对被加入免认证白名单，
 * 因此必须限制单个来源 IP 与全局的尝试次数，避免暴力枚举。
 *
 * 采用固定窗口：每 [windowMs] 重置一次。实现刻意保持简单，
 * 允许注入 [clock] 以便单元测试。
 */
class PairingAttemptLimiter(
    private val maxPerIp: Int = DEFAULT_MAX_PER_IP,
    private val maxGlobal: Int = DEFAULT_MAX_GLOBAL,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Bucket(val startMs: Long, var count: Int)

    private val lock = Any()
    private val perIp = HashMap<String, Bucket>()
    private var globalStartMs = 0L
    private var globalCount = 0

    /**
     * 记录一次尝试。
     *
     * @return true 表示允许本次尝试；false 表示已超限
     */
    fun tryConsume(ip: String?): Boolean = synchronized(lock) {
        val now = clock()
        rollWindow(now)

        if (globalCount >= maxGlobal) return@synchronized false

        val key = ip?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_IP
        val bucket = perIp[key]
        if (bucket == null) {
            perIp[key] = Bucket(now, 1)
        } else {
            if (bucket.count >= maxPerIp) return@synchronized false
            bucket.count++
        }
        globalCount++
        true
    }

    /**
     * 超限后建议的重试等待秒数（未超限返回 0）。
     */
    fun retryAfterSeconds(ip: String?): Long = synchronized(lock) {
        val now = clock()
        val key = ip?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_IP
        val bucket = perIp[key]
        val ipRemain = if (bucket != null && bucket.count >= maxPerIp) {
            (bucket.startMs + windowMs - now).coerceAtLeast(0L)
        } else {
            0L
        }
        val globalRemain = if (globalCount >= maxGlobal) {
            (globalStartMs + windowMs - now).coerceAtLeast(0L)
        } else {
            0L
        }
        val remain = maxOf(ipRemain, globalRemain)
        (remain + 999L) / 1000L
    }

    /** 仅用于测试：清空所有计数。 */
    @Synchronized
    fun resetForTest() {
        perIp.clear()
        globalStartMs = 0L
        globalCount = 0
    }

    private fun rollWindow(now: Long) {
        if (now - globalStartMs >= windowMs) {
            globalStartMs = now
            globalCount = 0
        }
        val iterator = perIp.values.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().startMs >= windowMs) iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_MAX_PER_IP = 10
        const val DEFAULT_MAX_GLOBAL = 100
        const val DEFAULT_WINDOW_MS = 5 * 60_000L
        private const val UNKNOWN_IP = "unknown"
    }
}
