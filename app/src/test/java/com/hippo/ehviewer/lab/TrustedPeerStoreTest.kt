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
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
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

        // 监听器回调投递主线程，Robolectric PAUSED looper 需手动 idle
        shadowOf(Looper.getMainLooper()).idle()
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

        shadowOf(Looper.getMainLooper()).idle()
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

        shadowOf(Looper.getMainLooper()).idle()
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

    @Test
    fun updateEndpoint_keepsKeyAndCapabilities() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(
            TrustedPeer.Builder("uuid-1", "Pixel-1", "android")
                .host("192.168.1.10")
                .port(8080)
                .trustedAt(1234L)
                .capabilities(listOf(TrustedPeer.CAP_PAGE_READ, TrustedPeer.CAP_RELAY))
                .presharedKey("secret-key")
                .pubkeyHint("hint")
                .role("slave")
                .build(),
        )

        val updated = store.updateEndpoint("uuid-1", "Pixel-1-renamed", "192.168.1.20", 9090)

        assertNotNull(updated)
        assertEquals("Pixel-1-renamed", updated!!.deviceName)
        assertEquals("192.168.1.20", updated.host)
        assertEquals(9090, updated.port)
        // 密钥与配对相关字段不能丢
        assertEquals("secret-key", updated.presharedKey)
        assertEquals("hint", updated.pubkeyHint)
        assertEquals(1234L, updated.trustedAt)
        assertEquals("slave", updated.role)
        assertEquals(
            listOf(TrustedPeer.CAP_PAGE_READ, TrustedPeer.CAP_RELAY),
            updated.capabilities,
        )
        // 持久化后仍保留密钥
        TrustedPeerStore.resetForTest()
        val reloaded = TrustedPeerStore.getInstance(context).findById("uuid-1")!!
        assertEquals("secret-key", reloaded.presharedKey)
        assertEquals("192.168.1.20", reloaded.host)
    }

    @Test
    fun updateEndpoint_unknown_returnsNull() {
        val store = TrustedPeerStore.getInstance(context)
        assertNull(store.updateEndpoint("uuid-not-exist", "name", "192.168.1.20", 8080))
    }

    @Test
    fun updateEndpoint_firesListener() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(newPeer())
        val latch = CountDownLatch(1)
        var newName: String? = null
        store.addListener(object : TrustedPeerStore.Listener {
            override fun onPeerAdded(peer: TrustedPeer) {}
            override fun onPeerUpdated(old: TrustedPeer, new: TrustedPeer) {
                newName = new.deviceName
                latch.countDown()
            }
            override fun onPeerRemoved(peer: TrustedPeer) {}
        })

        store.updateEndpoint("uuid-1", "Pixel-1-edited", "192.168.1.20", 8080)

        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("Pixel-1-edited", newName)
    }

    @Test
    fun touch_notifiesListener() {
        val store = TrustedPeerStore.getInstance(context)
        store.upsert(newPeer())
        val latch = CountDownLatch(1)
        store.addListener(object : TrustedPeerStore.Listener {
            override fun onPeerAdded(peer: TrustedPeer) {}
            override fun onPeerUpdated(old: TrustedPeer, new: TrustedPeer) { latch.countDown() }
            override fun onPeerRemoved(peer: TrustedPeer) {}
        })

        store.touch("uuid-1", null, 0, 12)

        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(latch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun isEffectivelyOnline_honoursHeartbeatWindow() {
        val store = TrustedPeerStore.getInstance(context)
        val peer = newPeer()
        peer.setLastSeen(System.currentTimeMillis())
        assertTrue(store.isEffectivelyOnline(peer))
        assertFalse(store.isEffectivelyOnline(peer, System.currentTimeMillis() + 61_000L))
        peer.setOnline(false)
        assertFalse(store.isEffectivelyOnline(peer))
    }
}