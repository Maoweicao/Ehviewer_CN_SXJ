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

package com.hippo.ehviewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EhProxySelectorTest {

    @Test
    fun `lan and loopback hosts do not use the built-in proxy`() {
        assertTrue(EhProxySelector.isLocalHost("127.0.0.1"))
        assertTrue(EhProxySelector.isLocalHost("10.167.86.4"))
        assertTrue(EhProxySelector.isLocalHost("192.168.1.183"))
        assertTrue(EhProxySelector.isLocalHost("172.16.0.2"))
        assertTrue(EhProxySelector.isLocalHost("172.31.255.1"))
        assertTrue(EhProxySelector.isLocalHost("localhost"))
        assertTrue(EhProxySelector.isLocalHost("device.local"))
        assertTrue(EhProxySelector.isLocalHost("::1"))
    }

    @Test
    fun `public hosts use the built-in proxy`() {
        assertFalse(EhProxySelector.isLocalHost("e-hentai.org"))
        assertFalse(EhProxySelector.isLocalHost("ehgt.org"))
        assertFalse(EhProxySelector.isLocalHost("172.32.0.1"))
        assertFalse(EhProxySelector.isLocalHost("8.8.8.8"))
        assertFalse(EhProxySelector.isLocalHost(""))
        assertFalse(EhProxySelector.isLocalHost(null))
    }
}
