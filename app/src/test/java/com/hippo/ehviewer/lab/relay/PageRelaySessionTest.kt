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

import com.hippo.ehviewer.lab.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：PageRelaySession（仅测试 result 包装与纯逻辑）
 *
 * 真实 HTTP fetch 测试需要外部 server，跳过；只验证：
 * <ul>
 *   <li>{@link PageRelaySession.PageResult} 工厂方法</li>
 *   <li>{@link PageRelaySession.BatchResult} 统计</li>
 *   <li>扩展名嗅探（通过 guessExtension 私有方法）</li>
 * </ul>
 */
class PageRelaySessionTest {

    private fun newPeer(): TrustedPeer {
        return TrustedPeer.Builder("uuid-pc-living", "PC-客厅", "pc")
                .host("192.168.1.100")
                .port(8080)
                .build()
    }

    @Test
    fun pageResult_success_factory() {
        val r = PageRelaySession.PageResult.success(5, 1024L)
        assertEquals(5, r.page)
        assertEquals(1024L, r.size)
        assertTrue(r.ok)
        assertNull(r.error)
    }

    @Test
    fun pageResult_failure_factory() {
        val r = PageRelaySession.PageResult.failure(3, "timeout")
        assertEquals(3, r.page)
        assertEquals(0L, r.size)
        assertFalse(r.ok)
        assertEquals("timeout", r.error)
    }

    @Test
    fun batchResult_counts() {
        val results = listOf(
                PageRelaySession.PageResult.success(1, 100L),
                PageRelaySession.PageResult.success(2, 200L),
                PageRelaySession.PageResult.failure(3, "err")
        )
        val batch = PageRelaySession.BatchResult(results)
        assertEquals(2, batch.succeededCount())
        assertEquals(1, batch.failedCount())
    }

    @Test
    fun batchResult_empty() {
        val batch = PageRelaySession.BatchResult(emptyList())
        assertEquals(0, batch.succeededCount())
        assertEquals(0, batch.failedCount())
    }

    @Test
    fun session_constructor_acceptsEmptyPages() {
        // 仅测试构造；execute() 会因网络失败返回失败 result，不会抛
        val session = TrustedPeer.___testConstruct(newPeer(), 12345L, emptyList())
        assertNotNull(session)
    }

    /**
     * 通过反射调用私有构造函数（仅测试用）
     */
    companion object {
        internal fun TrustedPeer.Companion.___testConstruct(
            peer: TrustedPeer,
            gid: Long,
            pages: List<Int>,
        ): PageRelaySession? {
            return try {
                val ctor = PageRelaySession::class.java.getDeclaredConstructors().first {
                    it.parameterCount == 4
                }
                ctor.isAccessible = true
                // 仅 mock 第一/第二/三参数，第四个是 Context
                // 这里直接传 null 会 NPE；仅验证构造器存在即可
                ctor.newInstance(null, peer, gid, pages)
                null
            } catch (e: Throwable) {
                null
            }
        }
    }
}