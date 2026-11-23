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

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitSignalsTest {

    private val cloudflareBody = "<html><head><title>Just a moment...</title></head>" +
        "<body>Enable JavaScript and cookies to continue</body></html>"

    @Test
    fun `cloudflare body is not a site limit`() {
        assertTrue(LimitSignals.isCloudflareChallenge(403, null, cloudflareBody))
        assertFalse(LimitSignals.isLimitBody(cloudflareBody))
    }

    @Test
    fun `cloudflare challenge header is detected`() {
        val headers = Headers.of("cf-mitigated", "challenge")
        assertTrue(LimitSignals.isCloudflareChallenge(403, headers, ""))
    }

    @Test
    fun `real ban body is still a limit`() {
        val body = "Your IP address has been temporarily banned for excessive pageloads."
        assertTrue(LimitSignals.isLimitBody(body))
        assertFalse(LimitSignals.isCloudflareChallenge(200, null, body))
    }

    @Test
    fun `http 509 is a limit not cloudflare`() {
        assertTrue(LimitSignals.isLimitCode(509))
        assertFalse(LimitSignals.isCloudflareChallenge(509, null, "busy"))
    }

    @Test
    fun `cloudflare server with challenge script is detected`() {
        val headers = Headers.of("server", "cloudflare")
        val body = "<html><script src=\"/cdn-cgi/challenge-platform/x\"></script></html>"
        assertTrue(LimitSignals.isCloudflareChallenge(503, headers, body))
    }

    @Test
    fun `plain text ban time is summed across sentence parts`() {
        val body = "This IP address has been temporarily banned due to an excessive request " +
            "rate. The ban expires in 59 minutes and 13 seconds"
        assertEquals((59 * 60L + 13L) * 1000L, LimitSignals.parseBanDurationMs(body))
        assertEquals(
            90L * 60L * 1000L,
            LimitSignals.parseBanDurationMs("You are banned. The ban expires in 1 hour and 30 minutes"),
        )
        assertEquals(0L, LimitSignals.parseBanDurationMs("welcome to the forums"))
    }
}
