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

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore

/**
 * 未完成图片补齐的自动续传入口。
 *
 * 进程被杀后 [LabBackfillStore] 里仍保留 pending gid，下次进入多机联动页
 * 或手动同步时会从这里继续。所有补齐动作都提交为后台任务
 * （[com.hippo.ehviewer.task.LabImageBackfillTask]），因此会出现在「后台任务」。
 */
object LabBackfillResumer {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isRunning(): Boolean =
        LabTasks.isActive(LabTasks.ID_BACKFILL) || LabImageBackfill.isRunning()

    @JvmStatic
    fun stop() {
        LabImageBackfill.requestStop()
    }

    @JvmStatic
    fun resumeIfPossible(
        context: Context,
        callback: ((LabImageBackfill.Result?) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        mainHandler.post {
            val pending = LabBackfillStore.pending(appContext)
            if (pending.isEmpty() || isRunning()) {
                callback?.invoke(null)
                return@post
            }
            val peer = pickPeer(appContext)
            if (peer == null) {
                callback?.invoke(null)
                return@post
            }
            if (!LabTasks.startBackfill(appContext, peer.deviceId)) {
                callback?.invoke(null)
                return@post
            }
            callback?.invoke(null)
        }
    }

    private fun pickPeer(context: Context): TrustedPeer? {
        val store = TrustedPeerStore.getInstance(context)
        val peers = store.list().filter {
            !it.presharedKey.isNullOrEmpty() &&
                !it.host.isNullOrEmpty() &&
                it.port > 0
        }
        return peers.firstOrNull { store.isEffectivelyOnline(it) } ?: peers.firstOrNull()
    }
}
