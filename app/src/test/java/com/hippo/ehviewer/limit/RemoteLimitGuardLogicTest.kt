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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLimitGuardLogicTest {

    private val threshold = 20L * 1024L
    private val oneMb = 1024L * 1024L

    @Test
    fun `zero speed counts toward throttling`() {
        assertTrue(
            RemoteLimitGuard.shouldTriggerLowSpeed(
                speed = 0L,
                lowSpeedSeconds = 60,
                requiredSeconds = 60,
                sessionBytes = 2 * oneMb,
                sessionPages = 3,
                thresholdBytes = threshold,
            ),
        )
    }

    @Test
    fun `zero speed still needs enough data and pages`() {
        assertFalse(
            RemoteLimitGuard.shouldTriggerLowSpeed(
                speed = 0L,
                lowSpeedSeconds = 60,
                requiredSeconds = 60,
                sessionBytes = oneMb / 2,
                sessionPages = 3,
                thresholdBytes = threshold,
            ),
        )
        assertFalse(
            RemoteLimitGuard.shouldTriggerLowSpeed(
                speed = 0L,
                lowSpeedSeconds = 60,
                requiredSeconds = 60,
                sessionBytes = 2 * oneMb,
                sessionPages = 0,
                thresholdBytes = threshold,
            ),
        )
    }

    @Test
    fun `short zero speed window does not trigger`() {
        assertFalse(
            RemoteLimitGuard.shouldTriggerLowSpeed(
                speed = 0L,
                lowSpeedSeconds = 58,
                requiredSeconds = 60,
                sessionBytes = 2 * oneMb,
                sessionPages = 3,
                thresholdBytes = threshold,
            ),
        )
    }

    @Test
    fun `normal speed does not trigger`() {
        assertFalse(
            RemoteLimitGuard.shouldTriggerLowSpeed(
                speed = 512L * 1024L,
                lowSpeedSeconds = 60,
                requiredSeconds = 60,
                sessionBytes = 2 * oneMb,
                sessionPages = 3,
                thresholdBytes = threshold,
            ),
        )
    }

    @Test
    fun `ip change requires two known different values`() {
        assertTrue(RemoteLimitGuard.hasIpChanged("203.0.113.1", "203.0.113.2"))
        assertFalse(RemoteLimitGuard.hasIpChanged("203.0.113.1", "203.0.113.1"))
        assertFalse(RemoteLimitGuard.hasIpChanged(null, "203.0.113.2"))
        assertFalse(RemoteLimitGuard.hasIpChanged("203.0.113.1", null))
        assertFalse(RemoteLimitGuard.hasIpChanged("", "203.0.113.2"))
    }

    @Test
    fun `normal forum page counts as reachable`() {
        assertTrue(
            RemoteLimitGuard.isForumNormal(
                LimitProbe.ForumResult(200, false, false, "E-Hentai Forums", 0L, null),
            ),
        )
    }

    @Test
    fun `cloudflare or limit forum is not normal`() {
        assertFalse(
            RemoteLimitGuard.isForumNormal(
                LimitProbe.ForumResult(403, true, false, "Just a moment", 0L, null),
            ),
        )
        assertFalse(
            RemoteLimitGuard.isForumNormal(
                LimitProbe.ForumResult(200, false, true, "banned", 0L, null),
            ),
        )
        assertFalse(
            RemoteLimitGuard.isForumNormal(
                LimitProbe.ForumResult(0, false, false, null, 0L, "timeout"),
            ),
        )
        assertFalse(
            RemoteLimitGuard.isForumNormal(
                LimitProbe.ForumResult(302, false, false, "login", 0L, null),
            ),
        )
        assertFalse(
            RemoteLimitGuard.isForumNormal(
                LimitProbe.ForumResult(200, false, false, "login page", 0L, null, false),
            ),
        )
    }

    @Test
    fun `only ambiguous sources can be cleared by forum result`() {
        assertTrue(RemoteLimitGuard.isAmbiguousLimitSource("cloudflare"))
        assertTrue(RemoteLimitGuard.isAmbiguousLimitSource("low_speed"))
        assertTrue(RemoteLimitGuard.isAmbiguousLimitSource("zero_speed"))
        assertFalse(RemoteLimitGuard.isAmbiguousLimitSource("eh_engine_body"))
        assertFalse(RemoteLimitGuard.isAmbiguousLimitSource("forum_probe"))
        assertFalse(RemoteLimitGuard.isAmbiguousLimitSource(null))
    }

    @Test
    fun `zero speed needs a minute without healthy samples`() {
        assertFalse(RemoteLimitGuard.isZeroSpeedConfirmed(0L, 30_000L))
        assertFalse(RemoteLimitGuard.isZeroSpeedConfirmed(0L, 59_999L))
        assertTrue(RemoteLimitGuard.isZeroSpeedConfirmed(0L, 60_000L))
        assertTrue(RemoteLimitGuard.isZeroSpeedConfirmed(0L, 120_000L))
        // 非 0 的低速不受“近期健康速度”限制
        assertTrue(RemoteLimitGuard.isZeroSpeedConfirmed(10L * 1024L, 10_000L))
    }
}
