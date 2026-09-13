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

import android.content.Context
import android.os.Build
import androidx.annotation.NonNull
import com.hippo.ehviewer.transfer.log.TransferLogger
import java.util.UUID

/**
 * 实验室门面（LabManager）
 *
 * M1 阶段仅实现以下能力：
 * <ul>
 *   <li>本机设备 ID 生成与持久化</li>
 *   <li>实验室配置的读取入口（代理到 [LabConfigStore]）</li>
 *   <li>信任设备列表的读取入口（代理到 [TrustedPeerStore]）</li>
 *   <li>配对码管理入口（代理到 [PairingCodeManager]）</li>
 *   <li>自身 capabilities 声明（固定集合，本机是 Android 客户端）</li>
 * </ul>
 *
 * M2+ 将在此基础上叠加 DB 快照、ImageSourceChain、AutoRelay 等子管理器。
 */
class LabManager private constructor(private val appContext: Context) {

    val configStore: LabConfigStore = LabConfigStore.getInstance(appContext)
    val peerStore: TrustedPeerStore = TrustedPeerStore.getInstance(appContext)
    val pairingManager: PairingCodeManager = PairingCodeManager.getInstance()

    @Volatile private var cachedDeviceId: String? = null
    @Volatile private var cachedDeviceType: String? = null

    // ==================== 本机身份 ====================

    /**
     * 获取本机 deviceId（持久化）。首次调用时生成 UUID 并缓存。
     */
    @NonNull
    fun getSelfDeviceId(): String {
        cachedDeviceId?.let { return it }
        return synchronized(this) {
            cachedDeviceId ?: loadOrCreateDeviceId().also { cachedDeviceId = it }
        }
    }

    /**
     * 获取本机 deviceType（持久化）。默认 [android]。
     */
    @NonNull
    fun getSelfDeviceType(): String {
        cachedDeviceType?.let { return it }
        return synchronized(this) {
            cachedDeviceType
                ?: appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_DEVICE_TYPE, "android")
                    ?: "android"
        }.also { cachedDeviceType = it }
    }

    /**
     * 获取本机设备名（[Build.MODEL]，可能包含空格与特殊字符）。
     */
    @NonNull
    fun getSelfDeviceName(): String {
        val model = Build.MODEL
        return if (model.isNullOrEmpty()) "Android-${Build.SERIAL}" else model
    }

    /**
     * 获取本机声明的能力列表。
     */
    @NonNull
    fun getSelfCapabilities(): Array<String> = ANDROID_CAPABILITIES.copyOf()

    /**
     * 获取本机公钥提示（M1 阶段未实现真实密钥交换，返回 null）。
     */
    fun getSelfPubkeyHint(): String? = null

    // ==================== 便捷方法 ====================

    /**
     * 判断当前实验室功能是否启用（含总开关与子开关检查）。
     *
     * @param subKey 子开关 key（{@code autoRelay} / {@code crossDeviceImage} / {@code dbSnapshot} / {@code incrementalResume}）
     */
    fun isLabEnabled(subKey: String?): Boolean {
        val cfg = configStore.get()
        if (!cfg.isEnabled()) return false
        if (subKey == null) return true
        return cfg.isSubEnabled(subKey)
    }

    // ==================== 内部 ====================

    private fun loadOrCreateDeviceId(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            TransferLogger.getInstance().i(TAG, "Generated new self deviceId: $id")
        }
        return id
    }

    companion object {
        private const val TAG = "LabManager"

        private const val PREFS_NAME = "lab_self"
        private const val KEY_DEVICE_ID = "self_device_id"
        private const val KEY_DEVICE_TYPE = "self_device_type"

        /**
         * Android 端默认能力集。
         * 完整子集（M4-M6 实施后）应包含：GALLERY_LIST, PAGE_READ, DOWNLOAD, RELAY, SNAPSHOT, THUMBNAIL。
         */
        private val ANDROID_CAPABILITIES = arrayOf(
            TrustedPeer.CAP_GALLERY_LIST,
            TrustedPeer.CAP_PAGE_READ,
            TrustedPeer.CAP_DOWNLOAD,
            TrustedPeer.CAP_RELAY,
            TrustedPeer.CAP_SNAPSHOT,
            TrustedPeer.CAP_THUMBNAIL,
        )

        @Volatile private var INSTANCE: LabManager? = null

        /**
         * 测试钩子：仅用于单元测试重置单例。
         */
        @JvmStatic
        fun resetForTest() {
            synchronized(LabManager::class.java) {
                INSTANCE = null
            }
        }

        @JvmStatic
        fun getInstance(context: Context): LabManager {
            return INSTANCE ?: synchronized(LabManager::class.java) {
                INSTANCE ?: LabManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}