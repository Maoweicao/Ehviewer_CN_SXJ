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

package com.hippo.ehviewer.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAttemptLimiterTest {

    private var now = 1_000_000L

    private fun limiter(
        perIp: Int = 3,
        global: Int = 10,
        window: Long = 60_000L,
    ) = PairingAttemptLimiter(
        maxPerIp = perIp,
        maxGlobal = global,
        windowMs = window,
        clock = { now },
    )

    @Test
    fun allowsUpToPerIpLimit() {
        val limiter = limiter(perIp = 3)
        assertTrue(limiter.tryConsume("192.168.1.5"))
        assertTrue(limiter.tryConsume("192.168.1.5"))
        assertTrue(limiter.tryConsume("192.168.1.5"))
        assertFalse(limiter.tryConsume("192.168.1.5"))
    }

    @Test
    fun differentIpsAreIndependent() {
        val limiter = limiter(perIp = 1, global = 10)
        assertTrue(limiter.tryConsume("192.168.1.5"))
        assertFalse(limiter.tryConsume("192.168.1.5"))
        assertTrue(limiter.tryConsume("192.168.1.6"))
    }

    @Test
    fun windowSlidesAfterExpiry() {
        val limiter = limiter(perIp = 1, window = 60_000L)
        assertTrue(limiter.tryConsume("192.168.1.5"))
        assertFalse(limiter.tryConsume("192.168.1.5"))
        now += 60_001L
        assertTrue(limiter.tryConsume("192.168.1.5"))
    }

    @Test
    fun globalLimitApplies() {
        val limiter = limiter(perIp = 10, global = 2)
        assertTrue(limiter.tryConsume("10.0.0.1"))
        assertTrue(limiter.tryConsume("10.0.0.2"))
        assertFalse(limiter.tryConsume("10.0.0.3"))
    }

    @Test
    fun retryAfterSecondsReportsRemainingWindow() {
        val limiter = limiter(perIp = 1, window = 60_000L)
        assertTrue(limiter.tryConsume("192.168.1.5"))
        now += 15_000L
        assertEquals(45L, limiter.retryAfterSeconds("192.168.1.5"))
    }

    @Test
    fun nullIpFallsBackToUnknownBucket() {
        val limiter = limiter(perIp = 1)
        assertTrue(limiter.tryConsume(null))
        assertFalse(limiter.tryConsume(null))
        assertFalse(limiter.tryConsume(""))
    }
}
