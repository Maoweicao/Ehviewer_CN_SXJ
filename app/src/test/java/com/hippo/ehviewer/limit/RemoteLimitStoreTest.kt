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

package com.hippo.ehviewer.limit

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 回归测试：历史上限裁剪必须真正移除元素。
 *
 * <p>Kotlin 对 Java JSONArray 调用 `remove(0)` 会解析成 `remove(Object)`，
 * 装箱后的 0 永远匹配不到，while 循环会一直自旋并卡死主线程（线上 ANR）。
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class RemoteLimitStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("remote_limit", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test(timeout = 5_000)
    fun `history stays bounded and does not hang`() {
        for (i in 0 until 25) {
            RemoteLimitStore.addSession(
                context,
                LimitSession(
                    gid = i.toLong(),
                    title = "t$i",
                    startedAt = i.toLong(),
                    endedAt = i.toLong(),
                    bytes = 0,
                    durationMs = 1,
                    avgSpeed = 0,
                    peakSpeed = 0,
                    pages = 0,
                    stopReason = "test",
                ),
            )
        }

        val history = RemoteLimitStore.getHistory(context)
        assertEquals(20, history.size)
        assertEquals(5L, history.first().gid)
        assertEquals(24L, history.last().gid)
    }

    @Test
    fun `ip and forum probe survive clearBlock`() {
        RemoteLimitStore.setBlocked(
            context,
            System.currentTimeMillis() + 60_000L,
            "test",
            "unit",
            "evidence",
        )
        RemoteLimitStore.setLastIp(context, "203.0.113.9")
        RemoteLimitStore.setForumProbe(context, 200, false, true, "banned page")

        assertEquals("203.0.113.9", RemoteLimitStore.getLastIp(context))
        assertEquals(200, RemoteLimitStore.getForumProbeCode(context))
        assertEquals("banned page", RemoteLimitStore.getForumProbeContent(context))
        assertTrue(RemoteLimitStore.isForumProbeBanned(context))
        assertFalse(RemoteLimitStore.isForumProbeCloudflare(context))

        RemoteLimitStore.clearBlock(context)

        // IP 需要保留下来，供下一次限流时对比
        assertEquals("203.0.113.9", RemoteLimitStore.getLastIp(context))
        assertNull(RemoteLimitStore.getForumProbeContent(context))
        assertFalse(RemoteLimitStore.isForumProbeBanned(context))
        assertFalse(RemoteLimitStore.isForumProbeCloudflare(context))
        assertEquals(0L, RemoteLimitStore.getBlockedUntil(context))
    }
}
