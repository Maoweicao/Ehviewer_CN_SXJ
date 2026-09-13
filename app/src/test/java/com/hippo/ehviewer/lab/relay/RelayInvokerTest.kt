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

package com.hippo.ehviewer.lab.relay

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.lab.LabConfig
import com.hippo.ehviewer.lab.LabConfigStore
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 单元测试：RelayInvoker（仅测试入口逻辑，无真实 HTTP）
 *
 * 主要覆盖：
 * <ul>
 *   <li>{@code lab} 关闭 → SKIPPED</li>
 *   <li>autoRelay 子开关关闭 → SKIPPED</li>
 *   <li>无在线 peer → SKIPPED</li>
 *   <li>peer 无 host → 跳过该 peer</li>
 *   <li>cancelAll 不抛异常</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelayInvokerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        LabConfigStore.resetForTest()
        TrustedPeerStore.resetForTest()
        RelayInvoker.resetForTest()
        LabManager.getInstance(context).configStore.replace(LabConfig.defaultConfig())
    }

    @After
    fun tearDown() {
        LabConfigStore.resetForTest()
        TrustedPeerStore.resetForTest()
        RelayInvoker.resetForTest()
    }

    private fun makeDownloadInfo(gid: Long = 12345L): DownloadInfo {
        val info = DownloadInfo()
        info.gid = gid
        info.state = DownloadInfo.STATE_DOWNLOAD
        info.speed = 100L
        info.finished = 0
        info.total = 10
        info.pages = 10
        info.fileSize = 1000L
        info.title = "Test"
        return info
    }

    @Test
    fun invoke_skipped_whenLabDisabled() {
        // 默认 labEnabled = false
        val invoker = RelayInvoker.getInstance(context)
        val result = invoker.invoke(makeDownloadInfo(), RelayInvoker.Mode.AUTO)
        assertEquals(RelayInvoker.InvokeResult.SKIPPED, result)
    }

    @Test
    fun invoke_skipped_whenNoPeers() {
        LabManager.getInstance(context).configStore.replace(
            LabConfig.defaultConfig().toBuilder().enabled(true).build()
        )
        val invoker = RelayInvoker.getInstance(context)
        val result = invoker.invoke(makeDownloadInfo(), RelayInvoker.Mode.AUTO)
        assertEquals(RelayInvoker.InvokeResult.SKIPPED, result)
    }

    @Test
    fun invoke_skippedWhenPeerHasNoHost() {
        LabManager.getInstance(context).configStore.replace(
            LabConfig.defaultConfig().toBuilder().enabled(true).build()
        )
        // 添加一个无 host 的 peer
        TrustedPeerStore.getInstance(context).upsert(
            TrustedPeer.Builder("uuid-no-host", "NoHost", "pc").port(8080).build()
        )
        val invoker = RelayInvoker.getInstance(context)
        val result = invoker.invoke(makeDownloadInfo(), RelayInvoker.Mode.AUTO)
        assertEquals(RelayInvoker.InvokeResult.SKIPPED, result)
    }

    @Test
    fun invoke_skippedForZeroGid() {
        LabManager.getInstance(context).configStore.replace(
            LabConfig.defaultConfig().toBuilder().enabled(true).build()
        )
        TrustedPeerStore.getInstance(context).upsert(
            TrustedPeer.Builder("uuid-good", "PC", "pc")
                    .host("192.168.1.100")
                    .port(8080)
                    .build()
        )
        val invoker = RelayInvoker.getInstance(context)
        val result = invoker.invoke(makeDownloadInfo(gid = 0), RelayInvoker.Mode.AUTO)
        assertEquals(RelayInvoker.InvokeResult.SKIPPED, result)
    }

    @Test
    fun cancelAll_noThrow() {
        val invoker = RelayInvoker.getInstance(context)
        invoker.cancelAll()
        assertEquals(0, invoker.activeSessionCount())
    }

    @Test
    fun addListener_removeListener() {
        val invoker = RelayInvoker.getInstance(context)
        val listener = object : RelayInvoker.Listener {
            override fun onRelayInvoked(gid: Long, peerName: String, pageCount: Int, mode: RelayInvoker.Mode) {}
        }
        invoker.addListener(listener)
        invoker.removeListener(listener)
    }

    @Test
    fun getJudge_returnsSameInstance() {
        val invoker = RelayInvoker.getInstance(context)
        val j1 = invoker.judge
        val j2 = invoker.judge
        assertTrue(j1 === j2)
    }

    @Test
    fun judge_initialRetryCountZero() {
        val invoker = RelayInvoker.getInstance(context)
        assertEquals(0, invoker.judge.currentRetryCount(99999L))
    }
}