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
 * 单元测试：LabConfigStore
 *
 * 覆盖：
 * <ul>
 *   <li>默认值与反序列化</li>
 *   <li>update() 内存与持久同步</li>
 *   <li>invalidate() 强制重载</li>
 *   <li>Listener 主线程回调</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LabConfigStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        // 清空偏好，确保每个测试用例独立
        context.getSharedPreferences("lab_config", Context.MODE_PRIVATE)
                .edit().clear().commit()
        // 重置单例
        val field = LabConfigStore::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, null)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("lab_config", Context.MODE_PRIVATE)
                .edit().clear().commit()
    }

    @Test
    fun defaultConfig_isLabDisabled() {
        val cfg = LabConfigStore.getInstance(context).get()
        assertFalse("default enabled should be false", cfg.isEnabled())
        assertEquals(LabConfig.ROLE_AUTO, cfg.getRole())
        assertEquals(51200L, cfg.getAutoRelay().speedThresholdBps)
        assertEquals(LabConfig.STRATEGY_UNION, cfg.getIncrementalResume().strategy)
    }

    @Test
    fun update_persistsAcrossInstances() {
        val store = LabConfigStore.getInstance(context)
        store.update { it.toBuilder().enabled(true).build() }
        assertTrue(store.get().isEnabled())

        // 重置单例，模拟进程重启；持久化应该让新实例读到 enabled=true
        val field = LabConfigStore::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, null)

        val store2 = LabConfigStore.getInstance(context)
        assertTrue("persistence failed: enabled should still be true", store2.get().isEnabled())
    }

    @Test
    fun update_passesNewConfigToListeners() {
        val store = LabConfigStore.getInstance(context)
        val latch = CountDownLatch(1)
        var receivedNew: LabConfig? = null
        store.addListener { _, newCfg ->
            receivedNew = newCfg
            latch.countDown()
        }
        store.update { it.toBuilder().role(LabConfig.ROLE_MASTER).build() }

        assertTrue("listener not invoked within 1s", latch.await(1, TimeUnit.SECONDS))
        assertNotNull(receivedNew)
        assertEquals(LabConfig.ROLE_MASTER, receivedNew!!.getRole())
    }

    @Test
    fun isSubEnabled_returnsFalseWhenDisabled() {
        val store = LabConfigStore.getInstance(context)
        // 默认 enabled=false，所有子开关查询都应返回 false
        assertFalse(store.get().isSubEnabled("autoRelay"))
        assertFalse(store.get().isSubEnabled("crossDeviceImage"))
        assertFalse(store.get().isSubEnabled("dbSnapshot"))
        assertFalse(store.get().isSubEnabled("incrementalResume"))
    }

    @Test
    fun isSubEnabled_returnsTrueWhenEnabledAndSubOn() {
        val store = LabConfigStore.getInstance(context)
        store.update {
            it.toBuilder()
                .enabled(true)
                .subSwitches(LabConfig.SubSwitches.Builder().autoRelay(true).build())
                .build()
        }
        assertTrue(store.get().isSubEnabled("autoRelay"))
    }

    @Test
    fun imageSourcePriority_persists() {
        val store = LabConfigStore.getInstance(context)
        store.update {
            it.toBuilder()
                .imageSourcePriority(listOf("lan", "local", "remote_proxy"))
                .build()
        }
        assertEquals(listOf("lan", "local", "remote_proxy"), store.get().imageSourcePriority)
    }

    @Test
    fun toJson_andFromJson_roundTrip() {
        val original = LabConfig.defaultConfig().toBuilder()
            .enabled(true)
            .role(LabConfig.ROLE_MASTER)
            .imageSourcePriority(listOf("lan", "local"))
            .autoRelay(LabConfig.AutoRelay.Builder()
                    .speedThresholdBps(102400)
                    .consecutiveSeconds(60)
                    .build())
            .build()
        val json = original.toJson()
        val restored = LabConfig.fromJson(json)

        assertEquals(original.isEnabled(), restored.isEnabled())
        assertEquals(original.getRole(), restored.getRole())
        assertEquals(original.getImageSourcePriority(), restored.getImageSourcePriority())
        assertEquals(102400L, restored.getAutoRelay().speedThresholdBps)
        assertEquals(60, restored.getAutoRelay().consecutiveSeconds)
    }

    @Test
    fun fromJson_handlesPartialUpdate() {
        // 只更新 enabled 字段，其余字段保持 default
        val partial = com.alibaba.fastjson.JSONObject()
        partial.put("enabled", true)
        val cfg = LabConfig.fromJson(partial)
        assertTrue(cfg.isEnabled())
        // 其他字段是 default
        assertEquals(LabConfig.ROLE_AUTO, cfg.getRole())
    }

    @Test
    fun invalidate_forcesReload() {
        val store = LabConfigStore.getInstance(context)
        store.update { it.toBuilder().enabled(true).build() }
        assertTrue(store.get().isEnabled())

        // 模拟外部修改了 SharedPreferences（例如别的进程）
        context.getSharedPreferences("lab_config", Context.MODE_PRIVATE)
                .edit().putBoolean("lab_enabled", false).commit()
        store.invalidate()
        assertFalse(store.get().isEnabled())
    }

    @Test
    fun removeListener_stopsCallback() {
        val store = LabConfigStore.getInstance(context)
        var count = 0
        val l = LabConfigStore.Listener { _, _ -> count++ }
        store.addListener(l)
        store.update { it.toBuilder().enabled(true).build() }
        store.removeListener(l)
        store.update { it.toBuilder().enabled(false).build() }
        assertEquals(1, count)
    }

    @Test
    fun update_nullUpdater_noOp() {
        val store = LabConfigStore.getInstance(context)
        store.update { it.toBuilder().enabled(true).build() }
        val before = store.get()
        store.update { _ -> null }
        val after = store.get()
        assertEquals(before, after)
    }
}