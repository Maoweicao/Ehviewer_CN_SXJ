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

package com.hippo.ehviewer.lab.ip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashProfileBuilderTest {

    private val yaml = """
        mixed-port: 7890
        proxies:
          - { name: '剩余流量：299.02 GB', type: vless, server: us.example.com, port: 8443 }
          - { name: '距离下次重置剩余：14 天', type: vless, server: us.example.com, port: 8443 }
          - { name: '导航页：www.example.com', type: ss, server: c.example.com, port: 21553 }
          - { name: '127 本地', type: ss, server: 127.0.0.1, port: 1080 }
          - { name: '🇯🇵 日本01', type: vless, server: jp.example.com, port: 443 }
          - { name: '🇭🇰 香港01', type: ss, server: hk.example.com, port: 443 }
        proxy-groups:
          - { name: '🔰 节点选择', type: select, proxies: ['剩余流量：299.02 GB', '🇯🇵 日本01'] }
        rules:
          - MATCH,🔰 节点选择
    """.trimIndent()

    @Test
    fun `removes virtual and invalid nodes and rebuilds groups`() {
        val result = ClashProfileBuilder.build(yaml, 17890, 19090)
        assertEquals(2, result.nodes.size)
        assertTrue(result.nodes.contains("🇯🇵 日本01"))
        assertTrue(result.nodes.contains("🇭🇰 香港01"))
        assertFalse(result.nodes.any { it.contains("剩余流量") })
        assertFalse(result.nodes.any { it.contains("导航页") })
        assertEquals(4, result.removed)

        assertTrue(result.yaml.contains("mixed-port: 17890"))
        assertTrue(result.yaml.contains("external-controller: 127.0.0.1:19090"))
        assertTrue(result.yaml.contains(ClashProfileBuilder.GROUP_SELECT))
        assertTrue(result.yaml.contains("MATCH," + ClashProfileBuilder.GROUP_SELECT))
        assertFalse(result.yaml.contains("剩余流量"))
        assertFalse(result.yaml.contains("rules:\n- DOMAIN"))
    }

    @Test(expected = IllegalStateException::class)
    fun `throws when every node is virtual`() {
        val onlyVirtual = """
            proxies:
              - { name: '剩余流量：1 GB', type: vless, server: a.example.com, port: 443 }
              - { name: '套餐到期：2026-11-29', type: vless, server: b.example.com, port: 443 }
        """.trimIndent()
        ClashProfileBuilder.build(onlyVirtual, 17890, 19090)
    }
}
