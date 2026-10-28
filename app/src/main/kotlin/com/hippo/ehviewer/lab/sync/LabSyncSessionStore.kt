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

package com.hippo.ehviewer.lab.sync

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 同步方向（发起方视角）。 */
enum class LabSyncDirection(val wire: String) {
    BIDIRECTIONAL("bidirectional"),
    PULL("pull"),
    PUSH("push");

    /** 对端角色：拉取方 → 提供方；推送方 → 对端反向拉取；双向 → 双向。 */
    fun peerRole(): String = when (this) {
        PULL -> ROLE_SERVE
        PUSH -> ROLE_PULL
        BIDIRECTIONAL -> ROLE_BIDIRECTIONAL
    }

    companion object {
        const val ROLE_SERVE = "serve"
        const val ROLE_PULL = "pull"
        const val ROLE_BIDIRECTIONAL = "bidirectional"

        @JvmStatic
        fun fromWire(value: String?): LabSyncDirection? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * 一次设备间同步会话。
 *
 * @param direction 发起方选择的方向
 * @param role      本机在该会话中的角色（serve / pull / bidirectional）
 * @param state     running / completed / failed / closed
 */
class LabSyncSession(
    val id: String,
    val peerDeviceId: String,
    val peerName: String,
    val direction: String,
    val role: String,
    val scopes: List<String>,
    val createdAt: Long,
    val expiresAt: Long,
) {
    @Volatile var state: String = "running"
    @Volatile var applied: Int = 0
    @Volatile var detail: String? = null
    @Volatile var finishedAt: Long = 0L

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
            now > expiresAt && "running" == state
}

/**
 * 活动同步会话（仅内存；重启后由客户端重新发起）。
 *
 * <p>同一对对端之间的非双向会话互斥：新的会话会替换旧的未完成会话。
 */
class LabSyncSessionStore private constructor() {

    private val sessions = ConcurrentHashMap<String, LabSyncSession>()

    fun create(
        peerDeviceId: String,
        peerName: String,
        direction: LabSyncDirection,
        scopes: List<String>,
    ): LabSyncSession {
        cleanup()
        // 同一对端只保留一个 running 会话
        sessions.values
            .filter { it.peerDeviceId == peerDeviceId && it.state == "running" }
            .forEach { it.state = "superseded"; it.finishedAt = System.currentTimeMillis() }

        val now = System.currentTimeMillis()
        val session = LabSyncSession(
            id = UUID.randomUUID().toString().replace("-", ""),
            peerDeviceId = peerDeviceId,
            peerName = peerName,
            direction = direction.wire,
            role = direction.peerRole(),
            scopes = scopes,
            createdAt = now,
            expiresAt = now + SESSION_TTL_MS,
        )
        sessions[session.id] = session
        return session
    }

    fun find(id: String): LabSyncSession? {
        cleanup()
        return sessions[id]
    }

    fun update(id: String, applied: Int? = null, detail: String? = null) {
        val session = sessions[id] ?: return
        if (applied != null) session.applied = applied
        if (detail != null) session.detail = detail
    }

    fun finish(id: String, state: String, detail: String? = null) {
        val session = sessions[id] ?: return
        session.state = state
        session.detail = detail ?: session.detail
        session.finishedAt = System.currentTimeMillis()
    }

    fun running(): List<LabSyncSession> {
        cleanup()
        return sessions.values.filter { it.state == "running" }
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { entry ->
            val s = entry.value
            val expired = s.isExpired(now)
            if (expired) {
                s.state = "expired"
                s.finishedAt = now
            }
            // 已结束的会话保留 10 分钟供客户端轮询，然后回收
            expired || (s.state != "running" && now - s.finishedAt > FINISHED_RETENTION_MS)
        }
    }

    companion object {
        private const val SESSION_TTL_MS = 10 * 60 * 1000L
        private const val FINISHED_RETENTION_MS = 10 * 60 * 1000L

        @Volatile private var INSTANCE: LabSyncSessionStore? = null

        @JvmStatic
        fun getInstance(): LabSyncSessionStore {
            return INSTANCE ?: synchronized(LabSyncSessionStore::class.java) {
                INSTANCE ?: LabSyncSessionStore().also { INSTANCE = it }
            }
        }

        @JvmStatic
        fun resetForTest() {
            synchronized(LabSyncSessionStore::class.java) {
                INSTANCE = null
            }
        }
    }
}
