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

package com.hippo.ehviewer.lab.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingQrCodecTest {

    @Test
    fun encodeDecode_roundTrip() {
        val content = PairingQrCodec.encode("192.168.1.5", 8080, "123456", "Pixel 7")
        val params = PairingQrCodec.decode(content)
        assertEquals("192.168.1.5", params!!.host)
        assertEquals(8080, params.port)
        assertEquals("123456", params.code)
        assertEquals("Pixel 7", params.name)
    }

    @Test
    fun decode_withoutName() {
        val content = PairingQrCodec.encode("192.168.1.5", 8080, "123456", null)
        val params = PairingQrCodec.decode(content)
        assertEquals("192.168.1.5", params!!.host)
        assertNull(params.name)
    }

    @Test
    fun decode_rejectsOtherSchemes() {
        assertNull(PairingQrCodec.decode("ehviewer-transfer://connect?host=1.2.3.4&port=8080"))
        assertNull(PairingQrCodec.decode("https://example.com"))
    }

    @Test
    fun decode_rejectsMalformedContent() {
        assertNull(PairingQrCodec.decode(null))
        assertNull(PairingQrCodec.decode(""))
        assertNull(PairingQrCodec.decode("not a uri"))
        assertNull(PairingQrCodec.decode("ehviewer-pair://pair?host=1.2.3.4&port=8080&code=12"))
        assertNull(PairingQrCodec.decode("ehviewer-pair://pair?host=1.2.3.4&port=0&code=123456"))
        assertNull(PairingQrCodec.decode("ehviewer-pair://pair?host=&port=8080&code=123456"))
        assertNull(PairingQrCodec.decode("ehviewer-pair://pair?host=1.2.3.4&port=8080&code=abcdef"))
        assertNull(PairingQrCodec.decode("ehviewer-pair://pair?host=1.2.3.4&port=8080&code=123456&v=2"))
    }

    @Test
    fun encode_rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingQrCodec.encode("", 8080, "123456", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingQrCodec.encode("1.2.3.4", 70000, "123456", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingQrCodec.encode("1.2.3.4", 8080, "12345", null)
        }
    }

    @Test
    fun isValidCode_enforcesSixDigits() {
        assertTrue(PairingQrCodec.isValidCode("123456"))
        assertFalse(PairingQrCodec.isValidCode("12345"))
        assertFalse(PairingQrCodec.isValidCode("1234567"))
        assertFalse(PairingQrCodec.isValidCode("12a456"))
        assertFalse(PairingQrCodec.isValidCode(null))
    }

    private fun assertThrows(expected: Class<out Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (expected.isInstance(t)) return
            throw AssertionError("Unexpected exception: $t", t)
        }
        throw AssertionError("Expected ${expected.simpleName} but nothing was thrown")
    }
}
