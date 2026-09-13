/*
 * Copyright 2025 EhViewer Contributors
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

import androidx.annotation.Nullable
import com.hippo.ehviewer.transfer.log.TransferLogger
import java.security.SecureRandom

/**
 * 配对码管理器
 *
 * 协议对应：v3.0 §5.19.1.2 / §5.19.1.3 / §5.19.4。
 *
 * 配对码约束：
 * <ul>
 *   <li>6 位数字字符串（000000-999999，但 000000 / 111111 等弱码禁用）</li>
 *   <li>60 秒 TTL（来自协议 §5.19.1.2 字段 {@code ttlSeconds}）</li>
 *   <li>一次性使用：消费后立即失效</li>
 *   <li>同时仅存在一个有效配对码（新申请会替换旧的）</li>
 * </ul>
 */
class PairingCodeManager private constructor() {

    private val random = SecureRandom()

    @Volatile private var activeCode: String? = null
    @Volatile private var activeExpiresAt: Long = 0
    @Volatile private var activeDeviceNameHint: String? = null

    /**
     * 申请新的配对码（会替换旧的）。
     */
    @Synchronized
    fun issue(selfDeviceNameHint: String?): PairingIssue {
        val code = generateUniqueCode()
        val expiresAt = System.currentTimeMillis() + TTL_MS
        activeCode = code
        activeExpiresAt = expiresAt
        activeDeviceNameHint = sanitizeHint(selfDeviceNameHint)

        TransferLogger.getInstance().i(
            TAG,
            "Issued pairing code (hashed): ${maskCode(code)}, ttl=${TTL_MS}ms",
        )
        return PairingIssue(code, expiresAt, TTL_MS, activeDeviceNameHint)
    }

    /**
     * 探测某个码是否存在（用于 §5.19.1.5 pair-info 端点）。
     * <b>不消费</b>，仅探测。
     */
    fun probe(code: String): PairingInfo {
        synchronized(this) {
            expireIfNeeded()
            if (activeCode != null && activeCode == code) {
                return PairingInfo(true, activeExpiresAt, activeDeviceNameHint)
            }
            return PairingInfo(false, 0L, null)
        }
    }

    /**
     * 消费配对码：原子地校验并失效。
     */
    fun consume(code: String): String? {
        synchronized(this) {
            expireIfNeeded()
            if (activeCode == null || System.currentTimeMillis() > activeExpiresAt) {
                activeCode = null
                activeExpiresAt = 0
                activeDeviceNameHint = null
                return null
            }
            if (activeCode != code) return null
            val hint = activeDeviceNameHint
            // 一次性：消费即失效
            activeCode = null
            activeExpiresAt = 0
            activeDeviceNameHint = null
            TransferLogger.getInstance().i(TAG, "Pairing code consumed")
            return hint
        }
    }

    /**
     * 当前是否有有效配对码（供 UI 判断是否需要展示新码）。
     */
    fun hasActive(): Boolean = synchronized(this) {
        activeCode != null && System.currentTimeMillis() <= activeExpiresAt
    }

    @Nullable
    fun getActiveCodeForDisplay(): String? = synchronized(this) {
        if (activeCode == null || System.currentTimeMillis() > activeExpiresAt) null
        else activeCode
    }

    fun getActiveExpiresAt(): Long = synchronized(this) { activeExpiresAt }

    private fun expireIfNeeded() {
        if (activeCode != null && System.currentTimeMillis() > activeExpiresAt) {
            activeCode = null
            activeExpiresAt = 0
            activeDeviceNameHint = null
        }
    }

    private fun generateUniqueCode(): String {
        for (attempt in 0 until 16) {
            val n = random.nextInt(1_000_000)
            val code = "%06d".format(n)
            if (!isWeakCode(code) && code != activeCode) return code
        }
        // 极端兜底：取一个非弱码
        for (i in 100000 until 1_000_000) {
            val code = "%06d".format(i)
            if (!isWeakCode(code) && code != activeCode) return code
        }
        return "000000" // unreachable
    }

    private fun isWeakCode(code: String): Boolean {
        for (w in WEAK_CODES) {
            if (w == code) return true
        }
        return false
    }

    private fun sanitizeHint(hint: String?): String? {
        if (hint == null) return null
        val trimmed = if (hint.length > 32) hint.substring(0, 32) else hint
        return trimmed.replace("[\\p{Cntrl}]".toRegex(), "")
    }

    private fun maskCode(code: String?): String =
        if (code == null || code.length != 6) "******" else code.substring(0, 2) + "****"

    companion object {
        private const val TAG = "PairingCode"

        private const val CODE_LENGTH = 6
        private const val TTL_MS = 60_000L

        private val WEAK_CODES = setOf(
            "000000", "111111", "222222", "333333", "444444",
            "555555", "666666", "777777", "888888", "999999",
            "123456", "654321", "012345", "543210",
        )

        @Volatile private var INSTANCE: PairingCodeManager? = null

        /**
         * 测试钩子：仅用于单元测试重置单例。
         */
        @JvmStatic
        fun resetForTest() {
            synchronized(PairingCodeManager::class.java) {
                INSTANCE = null
            }
        }

        @JvmStatic
        fun getInstance(): PairingCodeManager {
            return INSTANCE ?: synchronized(PairingCodeManager::class.java) {
                INSTANCE ?: PairingCodeManager().also { INSTANCE = it }
            }
        }
    }

    data class PairingIssue(
        val code: String,
        val expiresAt: Long,
        val ttlSeconds: Long,
        @Nullable val deviceNameHint: String?,
    )

    data class PairingInfo(
        val exists: Boolean,
        val expiresAt: Long,
        @Nullable val deviceNameHint: String?,
    )
}