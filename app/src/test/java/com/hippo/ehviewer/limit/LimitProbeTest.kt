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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitProbeTest {

    @Test
    fun `parses ip from cloudflare trace`() {
        val body = """
            fl=123f
            h=www.cloudflare.com
            ip=203.0.113.7
            ts=1700000000.000
        """.trimIndent()
        assertEquals("203.0.113.7", LimitProbe.parsePublicIp(body))
    }

    @Test
    fun `parses ip from plain text endpoint`() {
        assertEquals("198.51.100.42", LimitProbe.parsePublicIp("198.51.100.42\n"))
        assertEquals(
            "2001:db8::1",
            LimitProbe.parsePublicIp("fl=1\nip=2001:db8::1\n"),
        )
    }

    @Test
    fun `rejects invalid ip bodies`() {
        assertNull(LimitProbe.parsePublicIp(null))
        assertNull(LimitProbe.parsePublicIp(""))
        assertNull(LimitProbe.parsePublicIp("not an ip"))
        assertNull(LimitProbe.parsePublicIp("999.1.1.1"))
    }

    @Test
    fun `forum ban page is detected`() {
        val body = "<html><title>E-Hentai Forums</title><body>" +
            "Your IP address has been temporarily banned for excessive pageloads." +
            "</body></html>"
        val result = LimitProbe.parseForumResult(200, body)
        assertTrue(result.banned)
        assertTrue(result.content.orEmpty().contains("temporarily banned"))
    }

    @Test
    fun `normal forum page is not a ban`() {
        val body = "<html><head><title>E-Hentai Forums</title>" +
            "<style>body{color:red}</style></head>" +
            "<body><script>var a=1;</script><div>Welcome to the forums</div></body></html>"
        val result = LimitProbe.parseForumResult(200, body)
        assertFalse(result.banned)
        val content = result.content.orEmpty()
        assertTrue(content.startsWith("E-Hentai Forums"))
        assertTrue(content.contains("Welcome to the forums"))
        assertFalse(content.contains("<div>"))
        assertFalse(content.contains("var a=1"))
    }

    @Test
    fun `cloudflare challenge is not a ban`() {
        val body = "<html><head><title>Just a moment...</title></head><body>" +
            "<div id=\"cf-challenge\">Enable JavaScript and cookies to continue</div>" +
            "</body></html>"
        val result = LimitProbe.parseForumResult(403, body)
        assertTrue(result.cloudflare)
        assertFalse(result.banned)
        assertEquals(0L, result.durationMs)
    }

    @Test
    fun `cloudflare header marks challenge`() {
        val headers = okhttp3.Headers.of("cf-mitigated", "challenge")
        val result = LimitProbe.parseForumResult(403, headers, "<html>blocked</html>")
        assertTrue(result.cloudflare)
        assertFalse(result.banned)
    }

    @Test
    fun `http limit code is a ban`() {
        assertTrue(LimitProbe.parseForumResult(509, "busy").banned)
        assertTrue(LimitProbe.parseForumResult(429, null).banned)
        assertFalse(LimitProbe.parseForumResult(509, "busy").cloudflare)
    }

    @Test
    fun `eh settings page markers`() {
        assertTrue(
            LimitProbe.isEhSettingsPage(
                "<html><title>E-Hentai Galleries - Settings</title>" +
                    "<form action=\"uconfig.php\"></form></html>",
            ),
        )
        assertTrue(LimitProbe.isEhSettingsPage("<a href=\"https://e-hentai.org/uconfig.php\">Settings</a>"))
        assertFalse(
            LimitProbe.isEhSettingsPage(
                "<html><title>Login</title><form action=\"index.php?act=Login&CODE=01\"></form></html>",
            ),
        )
        assertFalse(
            LimitProbe.isEhSettingsPage(
                "This IP address has been temporarily banned due to an excessive request rate. " +
                    "The ban expires in 59 minutes and 13 seconds",
            ),
        )
        assertFalse(LimitProbe.isEhSettingsPage(null))
    }

    @Test
    fun `plain text ban page gives exact remaining time`() {
        val body = "This IP address has been temporarily banned due to an excessive request " +
            "rate. This probably means you are using automated mirroring/harvesting software " +
            "or share the IP address with someone who does. The ban expires in 59 minutes " +
            "and 13 seconds"
        val result = LimitProbe.parseForumResult(200, body)
        assertTrue(result.banned)
        assertEquals((59 * 60L + 13L) * 1000L, result.durationMs)
    }

    @Test
    fun `empty body yields no content`() {
        assertNull(LimitProbe.normalizeContent(null))
        assertNull(LimitProbe.normalizeContent("   "))
        assertNull(LimitProbe.parsePublicIp("   "))
    }
}
