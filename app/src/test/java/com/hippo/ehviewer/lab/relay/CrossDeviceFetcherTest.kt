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
import com.hippo.ehviewer.lab.LabConfig
import com.hippo.ehviewer.lab.LabConfigStore
import com.hippo.ehviewer.lab.LabManager
import com.hippo.ehviewer.lab.TrustedPeer
import com.hippo.ehviewer.lab.TrustedPeerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 单元测试：CrossDeviceFetcher（无网络依赖）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrossDeviceFetcherTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        LabConfigStore.resetForTest()
        TrustedPeerStore.resetForTest()
        CrossDeviceFetcher.resetForTest()
        ResumePlanCalculator.resetForTest()
        LabManager.getInstance(context).configStore.replace(LabConfig.defaultConfig())
    }

    @After
    fun tearDown() {
        LabConfigStore.resetForTest()
        TrustedPeerStore.resetForTest()
        CrossDeviceFetcher.resetForTest()
        ResumePlanCalculator.resetForTest()
    }

    private fun peer(id: String, host: String): TrustedPeer {
        return TrustedPeer.Builder(id, "Device-$id", "pc").host(host).port(8080).build()
    }

    @Test
    fun activeCount_zeroByDefault() {
        val fetcher = CrossDeviceFetcher.getInstance(context)
        assertEquals(0, fetcher.activeCount())
    }

    @Test
    fun addListener_removeListener_noThrow() {
        val fetcher = CrossDeviceFetcher.getInstance(context)
        val l = CrossDeviceFetcher.Listener { _, _ -> }
        fetcher.addListener(l)
        fetcher.removeListener(l)
    }

    @Test
    fun execute_returnsEmpty_whenNoMissingPages() {
        val fetcher = CrossDeviceFetcher.getInstance(context)
        val plan = ResumePlanCalculator.Plan(
                1L, "T", 10, 20, 30,
                emptyList(), null, null, 0L, null)
        val result = fetcher.execute(plan)
        assertEquals(0, result.results.size)
    }

    @Test
    fun execute_returnsEmpty_whenNoSuggestedSource() {
        val fetcher = CrossDeviceFetcher.getInstance(context)
        val plan = ResumePlanCalculator.Plan(
                1L, "T", 10, 20, 30,
                listOf(11, 12, 13), null, null, 0L, null)
        val result = fetcher.execute(plan)
        assertEquals(0, result.results.size)
    }

    @Test
    fun execute_returnsEmpty_whenPeerHasNoHost() {
        TrustedPeerStore.getInstance(context).upsert(
                TrustedPeer.Builder("uuid-no-host", "NoHost", "pc").port(8080).build()
        )
        val fetcher = CrossDeviceFetcher.getInstance(context)
        val plan = ResumePlanCalculator.Plan(
                1L, "T", 10, 20, 30,
                listOf(11, 12, 13), "uuid-no-host", "NoHost", 0L, null)
        val result = fetcher.execute(plan)
        assertEquals(0, result.results.size)
    }

    @Test
    fun fetchPages_returnsEmpty_forEmptyList() {
        val fetcher = CrossDeviceFetcher.getInstance(context)
        val result = fetcher.fetchPages(1L, emptyList())
        assertEquals(0, result.results.size)
    }

    @Test
    fun fetchPages_returnsEmpty_whenNoPeer() {
        val fetcher = CrossDeviceFetcher.getInstance(context)
        val result = fetcher.fetchPages(1L, listOf(1, 2))
        assertEquals(0, result.results.size)
    }
}