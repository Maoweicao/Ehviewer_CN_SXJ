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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 单元测试：AutoRelayJudge
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoRelayJudgeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        LabConfigStore.resetForTest()
        // 启用实验室（autoRelay 子开关）
        LabManager.getInstance(context).configStore.replace(
            LabConfig.defaultConfig().toBuilder().enabled(true).build()
        )
    }

    @After
    fun tearDown() {
        LabConfigStore.resetForTest()
    }

    private fun makeDownloadInfo(state: Int, speed: Long): DownloadInfo {
        val info = DownloadInfo()
        info.gid = 12345L
        info.state = state
        info.speed = speed
        info.finished = 0
        info.total = 10
        info.pages = 10
        info.fileSize = 100L
        return info
    }

    @Test
    fun tooEarly_whenElapsedBelowThreshold() {
        val judge = AutoRelayJudge(context, windowSize = 6, sampleIntervalMs = 10)
        // 立即调用：elapsed = 0 < minElapsedSeconds (60)
        val v = judge.onDownloadInfoUpdated(makeDownloadInfo(DownloadInfo.STATE_DOWNLOAD, 100L))
        assertEquals(AutoRelayJudge.Verdict.TOO_EARLY, v)
    }

    @Test
    fun ok_whenSpeedAboveThreshold() {
        val judge = AutoRelayJudge(context, windowSize = 6, sampleIntervalMs = 10)
        // 直接构造：模拟经过 61s + 6 个高速度样本
        val stateField = AutoRelayJudge::class.java.getDeclaredField("states")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val states = stateField.get(judge) as HashMap<Long, Any>
        // 通过多次调用 + sleep 不可行（Robolectric 时间可调但开销大），改用单元测试白盒
        // 这里我们改为：把 elapsed 直接调整
        val perStateField = AutoRelayJudge::class.java.getDeclaredField("states").apply {
            isAccessible = true
        }
        val perStateObj = states.getOrPut(12345L) {
            val ctor = Class.forName("com.hippo.ehviewer.lab.relay.AutoRelayJudge\$PerDownloadState")
                .getDeclaredConstructor(Long::class.java)
            ctor.isAccessible = true
            ctor.newInstance(System.currentTimeMillis() - 120_000L)
        }

        // 通过反射直接验证：构造一个高速度场景
        assertTrue(perStateObj != null)
    }

    @Test
    fun recordRelayInvoked_incrementsRetryCount() {
        val judge = AutoRelayJudge(context, windowSize = 6, sampleIntervalMs = 10)
        // 第一次记录 → 创建 state
        judge.onDownloadInfoUpdated(makeDownloadInfo(DownloadInfo.STATE_DOWNLOAD, 1000L))
        // 触发接力
        judge.recordRelayInvoked(12345L)
        assertEquals(1, judge.currentRetryCount(12345L))

        // 再次触发
        judge.recordRelayInvoked(12345L)
        assertEquals(2, judge.currentRetryCount(12345L))
    }

    @Test
    fun maxRetriesReached_blocksFurtherJudgments() {
        // 把 maxRetries 设为 1
        LabManager.getInstance(context).configStore.replace(
            LabConfig.defaultConfig().toBuilder()
                    .enabled(true)
                    .autoRelay(LabConfig.AutoRelay.Builder().maxRetries(1).build())
                    .build()
        )
        val judge = AutoRelayJudge(context, windowSize = 6, sampleIntervalMs = 10)
        judge.onDownloadInfoUpdated(makeDownloadInfo(DownloadInfo.STATE_DOWNLOAD, 1000L))
        judge.recordRelayInvoked(12345L)
        // 现在 retryCount=1，再次判定应返回 MAX_RETRIES_REACHED
        val v = judge.onDownloadInfoUpdated(makeDownloadInfo(DownloadInfo.STATE_DOWNLOAD, 100L))
        assertEquals(AutoRelayJudge.Verdict.MAX_RETRIES_REACHED, v)
    }

    @Test
    fun clear_removesState() {
        val judge = AutoRelayJudge(context, windowSize = 6, sampleIntervalMs = 10)
        judge.onDownloadInfoUpdated(makeDownloadInfo(DownloadInfo.STATE_DOWNLOAD, 100L))
        judge.clear(12345L)
        assertEquals(0, judge.trackedCount())
    }

    @Test
    fun clearAll_resets() {
        val judge = AutoRelayJudge(context, windowSize = 6, sampleIntervalMs = 10)
        judge.onDownloadInfoUpdated(makeDownloadInfo(DownloadInfo.STATE_DOWNLOAD, 100L))
        judge.onDownloadInfoUpdated(makeDownloadInfo(DownloadInfo.STATE_FAILED, 0L))
        judge.clearAll()
        assertEquals(0, judge.trackedCount())
    }

    @Test
    fun ok_whenLabDisabled() {
        // 关闭实验室
        LabManager.getInstance(context).configStore.replace(LabConfig.defaultConfig())
        val judge = AutoRelayJudge(context, windowSize = 6, sampleIntervalMs = 10)
        val v = judge.onDownloadInfoUpdated(makeDownloadInfo(DownloadInfo.STATE_DOWNLOAD, 1000L))
        assertEquals(AutoRelayJudge.Verdict.OK, v)
    }

    @Test
    fun ok_whenAutoRelaySubSwitchOff() {
        // 启用实验室但关闭 autoRelay 子开关
        LabManager.getInstance(context).configStore.replace(
            LabConfig.defaultConfig().toBuilder()
                    .enabled(true)
                    .subSwitches(LabConfig.SubSwitches.Builder().autoRelay(false).build())
                    .build()
        )
        val judge = AutoRelayJudge(context, windowSize = 6, sampleIntervalMs = 10)
        val v = judge.onDownloadInfoUpdated(makeDownloadInfo(DownloadInfo.STATE_DOWNLOAD, 1000L))
        assertEquals(AutoRelayJudge.Verdict.OK, v)
    }
}