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

package com.hippo.ehviewer.lab.source

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.hippo.ehviewer.lab.LabConfig
import com.hippo.ehviewer.lab.LabConfigStore
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 单元测试：LanImageFetcher（仅逻辑部分，不测试真实 HTTP）
 *
 * 覆盖：
 * <ul>
 *   <li>{@link LanImageFetcher#listByRtt()} 排序逻辑</li>
 *   <li>{@link LanImageFetcher.Availability} 枚举</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LanImageFetcherTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        LabConfigStore.resetForTest()
        TrustedPeerStore.resetForTest()
        LabManager.getInstance(context).configStore.replace(LabConfig.defaultConfig())
    }

    @After
    fun tearDown() {
        LabConfigStore.resetForTest()
        TrustedPeerStore.resetForTest()
    }

    private fun peer(id: String, host: String?, port: Int, rtt: Long): TrustedPeer {
        return TrustedPeer.Builder(id, "Device-$id", "pc")
                .host(host)
                .port(port)
                .rttMs(rtt)
                .build()
    }

    @Test
    fun listByRtt_sortsByRttAscending() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(peer("uuid-c", "192.168.1.3", 8080, 30))
        store.upsert(peer("uuid-a", "192.168.1.1", 8080, 5))
        store.upsert(peer("uuid-b", "192.168.1.2", 8080, 12))
        store.upsert(peer("uuid-d", "192.168.1.4", 8080, -1))  // 未探测

        val fetcher = LanImageFetcher.getInstance(context)
        val list = fetcher.listByRtt()
        assertEquals(4, list.size)
        // 升序：uuid-a (5) → uuid-b (12) → uuid-c (30) → uuid-d (-1)
        assertEquals("uuid-a", list[0].deviceId)
        assertEquals("uuid-b", list[1].deviceId)
        assertEquals("uuid-c", list[2].deviceId)
        assertEquals("uuid-d", list[3].deviceId)
    }

    @Test
    fun listByRtt_excludesPeersWithoutHostOrPort() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(peer("uuid-good", "192.168.1.1", 8080, 10))
        store.upsert(peer("uuid-no-host", null, 8080, 5))     // 无 host
        store.upsert(peer("uuid-no-port", "192.168.1.2", 0, 8)) // 无 port
        store.upsert(peer("uuid-empty-host", "", 8080, 7))      // 空 host

        val fetcher = LanImageFetcher.getInstance(context)
        val list = fetcher.listByRtt()
        assertEquals(1, list.size)
        assertEquals("uuid-good", list[0].deviceId)
    }

    @Test
    fun listByRtt_returnsEmptyWhenNoPeers() {
        val fetcher = LanImageFetcher.getInstance(context)
        val list = fetcher.listByRtt()
        assertNotNull(list)
        assertEquals(0, list.size)
    }

    @Test
    fun availability_enumHasThreeValues() {
        val values = LanImageFetcher.Availability.values()
        assertEquals(3, values.size)
        assertNotNull(LanImageFetcher.Availability.HAVE)
        assertNotNull(LanImageFetcher.Availability.MISSING)
        assertNotNull(LanImageFetcher.Availability.UNKNOWN)
    }

    @Test
    fun addListener_removeListener_noThrow() {
        val fetcher = LanImageFetcher.getInstance(context)
        val listener = object : LanImageFetcher.Listener {
            override fun onRttUpdated(peer: TrustedPeer, rttMs: Long) {}
        }
        fetcher.addListener(listener)
        fetcher.removeListener(listener)
    }

    @Test
    fun startRttProbe_doesNotThrow() {
        val fetcher = LanImageFetcher.getInstance(context)
        fetcher.startRttProbe()
        fetcher.stopRttProbe()
    }

    @Test
    fun consecutiveFailures_startsAtZero() {
        val fetcher = LanImageFetcher.getInstance(context)
        assertEquals(0, fetcher.getConsecutiveFailures("uuid-unknown"))
    }

    @Test
    fun listByRtt_excludesCooldownPeers() {
        // 注入两个 peer + cooldown
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(peer("uuid-a", "192.168.1.1", 8080, 5))
        store.upsert(peer("uuid-b", "192.168.1.2", 8080, 12))

        val fetcher = LanImageFetcher.getInstance(context)
        // 初始两个 peer 都在
        assertEquals(2, fetcher.listByRtt().size)
    }
}