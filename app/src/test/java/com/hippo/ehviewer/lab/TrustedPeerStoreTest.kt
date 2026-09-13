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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 单元测试：TrustedPeerStore
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrustedPeerStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        context.getSharedPreferences("lab_trusted_peers", Context.MODE_PRIVATE)
                .edit().clear().commit()
        TrustedPeerStore.resetForTest()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("lab_trusted_peers", Context.MODE_PRIVATE)
                .edit().clear().commit()
        TrustedPeerStore.resetForTest()
    }

    private fun newPeer(
        id: String = "uuid-1",
        name: String = "Pixel-1",
        type: String = "android",
        port: Int = 8080,
        caps: List<String> = listOf(TrustedPeer.CAP_PAGE_READ),
        key: String? = null,
    ): TrustedPeer {
        return TrustedPeer.Builder(id, name, type)
                .port(port)
                .capabilities(caps)
                .presharedKey(key)
                .build()
    }

    @Test
    fun upsert_addsNewPeerAndFiresListener() {
        val store = TrustedPeerStore.getInstance(context)
        val latch = CountDownLatch(1)
        var received: TrustedPeer? = null
        store.addListener(object : TrustedPeerStore.Listener {
            override fun onPeerAdded(peer: TrustedPeer) { received = peer; latch.countDown() }
            override fun onPeerUpdated(old: TrustedPeer, new: TrustedPeer) { latch.countDown() }
            override fun onPeerRemoved(peer: TrustedPeer) {}
        })

        store.upsert(newPeer())

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertNotNull(received)
        assertEquals("uuid-1", received!!.deviceId)
        assertEquals(1, store.list().size)
    }

    @Test
    fun upsert_existingPeer_returnsOldAndFiresUpdate() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(newPeer())
        val latch = CountDownLatch(1)
        var oldName: String? = null
        store.addListener(object : TrustedPeerStore.Listener {
            override fun onPeerAdded(peer: TrustedPeer) {}
            override fun onPeerUpdated(old: TrustedPeer, new: TrustedPeer) { oldName = old.deviceName; latch.countDown() }
            override fun onPeerRemoved(peer: TrustedPeer) {}
        })
        val updated = TrustedPeer.Builder("uuid-1", "Pixel-1-updated", "android").port(8080).build()
        val old = store.upsert(updated)

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertNotNull(old)
        assertEquals("Pixel-1", oldName)
        assertEquals(1, store.list().size)
        assertEquals("Pixel-1-updated", store.list()[0].deviceName)
    }

    @Test
    fun remove_existingPeerFiresRemoveListener() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(newPeer())
        val latch = CountDownLatch(1)
        var removed: TrustedPeer? = null
        store.addListener(object : TrustedPeerStore.Listener {
            override fun onPeerAdded(peer: TrustedPeer) {}
            override fun onPeerUpdated(old: TrustedPeer, new: TrustedPeer) {}
            override fun onPeerRemoved(peer: TrustedPeer) { removed = peer; latch.countDown() }
        })

        val result = store.remove("uuid-1")

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertNotNull(removed)
        assertNotNull(result)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun remove_unknown_returnsNull() {
        val store = TrustedPeerStore.getInstance(context)
        assertNull(store.remove("uuid-not-exist"))
    }

    @Test
    fun findById_returnsPeer() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(newPeer())
        val found = store.findById("uuid-1")
        assertNotNull(found)
        assertEquals("Pixel-1", found!!.deviceName)
    }

    @Test
    fun findById_unknown_returnsNull() {
        val store = TrustedPeerStore.getInstance(context)
        assertNull(store.findById("unknown"))
    }

    @Test
    fun touch_updatesLastSeen() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(newPeer())
        val peerBefore = store.findById("uuid-1")!!
        assertEquals(0L, peerBefore.lastSeen)

        Thread.sleep(10)
        assertTrue(store.touch("uuid-1", "192.168.1.10", 9090, 25))

        val peerAfter = store.findById("uuid-1")!!
        assertTrue(peerAfter.lastSeen > 0)
        assertEquals("192.168.1.10", peerAfter.host)
        assertEquals(9090, peerAfter.port)
        assertEquals(25L, peerAfter.rttMs)
        assertTrue(peerAfter.online)
    }

    @Test
    fun touch_unknownDevice_returnsFalse() {
        val store = TrustedPeerStore.getInstance(context)
        assertFalse(store.touch("uuid-unknown", null, 0, -1))
    }

    @Test
    fun markOffline_doesNotDeletePeer() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(newPeer())
        store.markOffline("uuid-1")
        assertNotNull(store.findById("uuid-1"))
        assertFalse(store.findById("uuid-1")!!.online)
    }

    @Test
    fun presharedKey_persistsButNotSerialized() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(newPeer(key = "secret-key"))

        // 触发持久化（reset 单例并 reload）
        TrustedPeerStore.resetForTest()
        val reloaded = TrustedPeerStore.getInstance(context).findById("uuid-1")!!
        assertEquals("secret-key", reloaded.presharedKey)

        // toJson() 不能包含 presharedKey
        val json = reloaded.toJson()
        assertFalse("presharedKey leaked into JSON: $json", json.containsKey("presharedKey"))
    }

    @Test
    fun clearAll_removesEverything() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(newPeer(id = "uuid-1"))
        store.upsert(newPeer(id = "uuid-2", name = "Pixel-2"))
        store.upsert(newPeer(id = "uuid-3", name = "Pixel-3"))
        assertEquals(3, store.list().size)

        store.clearAll()
        assertEquals(0, store.list().size)
    }

    @Test
    fun listOnline_onlyReturnsFreshEntries() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(newPeer(id = "uuid-fresh"))
        store.touch("uuid-fresh", null, 0, -1)

        val stale = newPeer(id = "uuid-stale")
        // 不 touch → lastSeen=0 → 离 60s 阈值
        store.upsert(stale)

        val online = store.listOnline()
        // 至少包含刚刚 touch 的
        assertTrue(online.any { it.deviceId == "uuid-fresh" })
        // stale 因 lastSeen=0，超过 60s
        assertFalse(online.any { it.deviceId == "uuid-stale" })
    }
}