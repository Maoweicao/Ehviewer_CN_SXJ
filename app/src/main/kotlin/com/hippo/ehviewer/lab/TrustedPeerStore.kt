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
import com.hippo.ehviewer.transfer.log.TransferLogger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 信任设备存储
 *
 * 协议对应：v3.0 §5.19.1。
 *
 * 设备按 [TrustedPeer.deviceId] 主键存储。
 * [TrustedPeer.presharedKey] <b>仅本地持久化</b>，不参与网络响应。
 * 监听器回调统一投递主线程。
 */
class TrustedPeerStore private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE,
    )

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val lock = Any()

    /** 内存索引：deviceId → TrustedPeer */
    @Volatile private var peersById: HashMap<String, TrustedPeer>? = null

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * 获取所有信任设备（不可变快照）。
     */
    fun list(): List<TrustedPeer> {
        ensureLoaded()
        val map = peersById ?: return emptyList()
        synchronized(lock) { return java.util.Collections.unmodifiableList(ArrayList(map.values)) }
    }

    /**
     * 仅获取在线设备（lastSeen + 60s 内有心跳）。
     */
    fun listOnline(): List<TrustedPeer> {
        val now = System.currentTimeMillis()
        val online = ArrayList<TrustedPeer>()
        for (p in list()) {
            if (p.online || now - p.lastSeen < ONLINE_TIMEOUT_MS) {
                online.add(p)
            }
        }
        return online
    }

    /**
     * 按 deviceId 查找。
     */
    fun findById(deviceId: String): TrustedPeer? {
        ensureLoaded()
        val map = peersById ?: return null
        return synchronized(lock) { map[deviceId] }
    }

    /**
     * 按 deviceName 查找（同名设备取第一个匹配）。
     */
    fun findByName(deviceName: String): TrustedPeer? {
        ensureLoaded()
        val map = peersById ?: return null
        return synchronized(lock) {
            for (p in map.values) {
                if (deviceName == p.deviceName) return p
            }
            null
        }
    }

    /**
     * 添加或更新信任设备（按 deviceId upsert）。
     */
    fun upsert(peer: TrustedPeer): TrustedPeer? {
        ensureLoaded()
        val old: TrustedPeer?
        synchronized(lock) {
            val map = peersById!!
            old = map.put(peer.deviceId, peer)
            persistLocked()
        }
        TransferLogger.getInstance().d(
            "TrustedPeerStore",
            "Upsert peer: deviceId=${peer.deviceId}, name=${peer.deviceName}, isNew=${old == null}",
        )
        mainHandler.post {
            for (l in listeners) {
                if (old == null) l.onPeerAdded(peer) else l.onPeerUpdated(old, peer)
            }
        }
        return old
    }

    /**
     * 撤销信任。
     */
    fun remove(deviceId: String): TrustedPeer? {
        ensureLoaded()
        val removed: TrustedPeer?
        synchronized(lock) {
            val map = peersById!!
            removed = map.remove(deviceId)
            if (removed != null) persistLocked()
        }
        if (removed != null) {
            TransferLogger.getInstance().d(
                "TrustedPeerStore",
                "Removed peer: deviceId=$deviceId, name=${removed.deviceName}",
            )
            mainHandler.post {
                for (l in listeners) l.onPeerRemoved(removed)
            }
        }
        return removed
    }

    /**
     * 更新心跳：刷新 lastSeen 与 online 状态。
     */
    fun touch(deviceId: String, host: String?, port: Int, rttMs: Long): Boolean {
        ensureLoaded()
        var updated = false
        synchronized(lock) {
            val map = peersById!!
            val p = map[deviceId] ?: return@synchronized
            p.touchLastSeen(System.currentTimeMillis())
            if (host != null) p.setHost(host)
            if (port > 0) p.setPort(port)
            if (rttMs >= 0) p.setRttMs(rttMs)
            persistLocked()
            updated = true
        }
        return updated
    }

    /**
     * 标记指定 deviceId 离线（不删除记录）。
     */
    fun markOffline(deviceId: String) {
        ensureLoaded()
        synchronized(lock) {
            val map = peersById!!
            val p = map[deviceId] ?: return@synchronized
            p.setOnline(false)
            persistLocked()
        }
    }

    /**
     * 清空全部信任设备（危险操作，需 UI 二次确认）。
     */
    fun clearAll() {
        ensureLoaded()
        val removed: List<TrustedPeer>
        synchronized(lock) {
            val map = peersById!!
            removed = ArrayList(map.values)
            map.clear()
            persistLocked()
        }
        TransferLogger.getInstance().w("TrustedPeerStore", "Cleared all trusted peers: ${removed.size}")
        mainHandler.post {
            for (p in removed) {
                for (l in listeners) l.onPeerRemoved(p)
            }
        }
    }

    fun addListener(l: Listener) {
        if (l != null && !listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // ==================== 内部 ====================

    private fun ensureLoaded() {
        if (peersById != null) return
        synchronized(this) {
            if (peersById == null) {
                peersById = load()
            }
        }
    }

    private fun load(): HashMap<String, TrustedPeer> {
        val map = HashMap<String, TrustedPeer>()
        try {
            val json = prefs.getString(KEY_PEERS, null) ?: return map
            val arr = com.alibaba.fastjson.JSONArray.parseArray(json) ?: return map
            for (i in 0 until arr.size) {
                val obj = arr.getJSONObject(i) ?: continue
                val p = TrustedPeer.fromLocalJson(obj) ?: continue
                if (p.deviceId.isNotEmpty()) map[p.deviceId] = p
            }
            TransferLogger.getInstance().d("TrustedPeerStore", "Loaded ${map.size} trusted peers")
        } catch (e: Exception) {
            TransferLogger.getInstance().e("TrustedPeerStore", "Failed to load trusted peers", e)
        }
        return map
    }

    /** 必须在 lock 内调用 */
    private fun persistLocked() {
        val arr = com.alibaba.fastjson.JSONArray()
        for (p in peersById!!.values) arr.add(TrustedPeer.toLocalJson(p))
        prefs.edit().putString(KEY_PEERS, arr.toJSONString()).apply()
    }

    interface Listener {
        fun onPeerAdded(peer: TrustedPeer)
        fun onPeerUpdated(oldPeer: TrustedPeer, newPeer: TrustedPeer)
        fun onPeerRemoved(peer: TrustedPeer)
    }

    companion object {
        private const val PREFS_NAME = "lab_trusted_peers"
        private const val KEY_PEERS = "peers"

        private const val ONLINE_TIMEOUT_MS = 60_000L

        @Volatile private var INSTANCE: TrustedPeerStore? = null

        /** 测试钩子：仅用于单元测试重置单例。 */
        @JvmStatic
        fun resetForTest() {
            synchronized(TrustedPeerStore::class.java) {
                INSTANCE = null
            }
        }

        @JvmStatic
        fun getInstance(context: Context): TrustedPeerStore {
            return INSTANCE ?: synchronized(TrustedPeerStore::class.java) {
                INSTANCE ?: TrustedPeerStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}