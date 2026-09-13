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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 单元测试：PairingCodeManager
 */
class PairingCodeManagerTest {

    @Before
    fun setUp() {
        PairingCodeManager.resetForTest()
    }

    @After
    fun tearDown() {
        PairingCodeManager.resetForTest()
    }

    @Test
    fun issue_returnsValidCode() {
        val mgr = PairingCodeManager.getInstance()
        val issue = mgr.issue("Pixel 8 Pro")
        assertNotNull(issue.code)
        assertEquals(6, issue.code.length)
        assertTrue(issue.code.all { it.isDigit() })
        assertTrue(issue.ttlSeconds in 30..120)
        assertTrue(issue.expiresAt > System.currentTimeMillis())
        assertEquals("Pixel 8 Pro", issue.deviceNameHint)
    }

    @Test
    fun probe_findsActiveCode() {
        val mgr = PairingCodeManager.getInstance()
        val issued = mgr.issue("Pixel")
        val info = mgr.probe(issued.code)
        assertTrue(info.exists)
        assertEquals(issued.expiresAt, info.expiresAt)
    }

    @Test
    fun probe_unknownCode_returnsFalse() {
        val mgr = PairingCodeManager.getInstance()
        val info = mgr.probe("999999")
        assertFalse(info.exists)
    }

    @Test
    fun consume_validCode_returnsHintAndInvalidates() {
        val mgr = PairingCodeManager.getInstance()
        val issued = mgr.issue("Pixel")
        val consumed = mgr.consume(issued.code)
        assertEquals("Pixel", consumed)

        // 再次消费应该失败
        assertNull(mgr.consume(issued.code))
        // probe 应该返回 false
        assertFalse(mgr.probe(issued.code).exists)
    }

    @Test
    fun consume_unknownCode_returnsNull() {
        val mgr = PairingCodeManager.getInstance()
        assertNull(mgr.consume("000001"))
    }

    @Test
    fun getActiveCodeForDisplay_returnsNullWhenNone() {
        val mgr = PairingCodeManager.getInstance()
        assertNull(mgr.getActiveCodeForDisplay())
    }

    @Test
    fun issue_replacesPrevious() {
        val mgr = PairingCodeManager.getInstance()
        val first = mgr.issue("A")
        val second = mgr.issue("B")
        assertNotEquals(first.code, second.code)
        // probe 旧码应该找不到
        assertFalse(mgr.probe(first.code).exists)
        // probe 新码应该成功
        assertTrue(mgr.probe(second.code).exists)
    }

    @Test
    fun hasActive_reflectsState() {
        val mgr = PairingCodeManager.getInstance()
        assertFalse(mgr.hasActive())
        mgr.issue("X")
        assertTrue(mgr.hasActive())
        mgr.consume(mgr.getActiveCodeForDisplay()!!)
        assertFalse(mgr.hasActive())
    }

    @Test
    fun hint_isSanitized() {
        val mgr = PairingCodeManager.getInstance()
        val issue = mgr.issue("\u0000bad\u0007control")
        assertNotNull(issue.deviceNameHint)
        assertFalse(issue.deviceNameHint!!.contains("\u0000"))
        assertFalse(issue.deviceNameHint!!.contains("\u0007"))
    }
}