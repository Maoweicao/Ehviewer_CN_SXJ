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

package com.hippo.ehviewer.lab.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 单元测试：ImageSourceHeader
 */
class ImageSourceHeaderTest {

    @Test
    fun parseKind_handlesAllValues() {
        assertEquals(ImageSource.Kind.LOCAL_FILE, ImageSourceHeader.parseKind("local"))
        assertEquals(ImageSource.Kind.LOCAL_CACHE, ImageSourceHeader.parseKind("local:cache"))
        assertEquals(ImageSource.Kind.LAN, ImageSourceHeader.parseKind("lab:uuid-1234"))
        assertEquals(ImageSource.Kind.REMOTE, ImageSourceHeader.parseKind("remote"))
        assertEquals(ImageSource.Kind.UNKNOWN, ImageSourceHeader.parseKind(null))
        assertEquals(ImageSource.Kind.UNKNOWN, ImageSourceHeader.parseKind(""))
        assertEquals(ImageSource.Kind.UNKNOWN, ImageSourceHeader.parseKind("weird"))
    }

    @Test
    fun parseLanDeviceId_extractsDeviceId() {
        assertEquals("uuid-bbb", ImageSourceHeader.parseLanDeviceId("lab:uuid-bbb"))
        assertEquals(null, ImageSourceHeader.parseLanDeviceId("local"))
        assertEquals(null, ImageSourceHeader.parseLanDeviceId("remote"))
        assertEquals(null, ImageSourceHeader.parseLanDeviceId(null))
        assertEquals(null, ImageSourceHeader.parseLanDeviceId("lab:"))
    }

    @Test
    fun parseChain_parsesBracketedList() {
        assertEquals(listOf("local", "lan:dev-a", "remote"),
                ImageSourceHeader.parseChain("[local,lan:dev-a,remote]"))
        assertEquals(listOf("local"), ImageSourceHeader.parseChain("[local]"))
        assertEquals(emptyList<String>(), ImageSourceHeader.parseChain("[]"))
        assertEquals(emptyList<String>(), ImageSourceHeader.parseChain(""))
        assertEquals(emptyList<String>(), ImageSourceHeader.parseChain(null))
    }

    @Test
    fun parseChain_trimsWhitespace() {
        assertEquals(listOf("local", "lan"),
                ImageSourceHeader.parseChain(" [ local , lan ] "))
    }

    @Test
    fun parseLatencyMs_returnsValidNumber() {
        assertEquals(42L, ImageSourceHeader.parseLatencyMs("42"))
        assertEquals(0L, ImageSourceHeader.parseLatencyMs("0"))
        assertEquals(-1L, ImageSourceHeader.parseLatencyMs(null))
        assertEquals(-1L, ImageSourceHeader.parseLatencyMs(""))
        assertEquals(-1L, ImageSourceHeader.parseLatencyMs("abc"))
    }

    @Test
    fun toHeader_roundTrip() {
        val localSrc = ImageSource.Builder().kind(ImageSource.Kind.LOCAL_FILE).build()
        assertEquals("local", localSrc.toHeader())

        val cacheSrc = ImageSource.Builder().kind(ImageSource.Kind.LOCAL_CACHE).build()
        assertEquals("local:cache", cacheSrc.toHeader())

        val lanSrc = ImageSource.Builder()
                .kind(ImageSource.Kind.LAN)
                .deviceId("uuid-bbb")
                .build()
        assertEquals("lab:uuid-bbb", lanSrc.toHeader())

        val lanSrc2 = ImageSource.Builder().kind(ImageSource.Kind.LAN).build()
        assertEquals("lab:unknown", lanSrc2.toHeader())

        val remoteSrc = ImageSource.Builder().kind(ImageSource.Kind.REMOTE).build()
        assertEquals("remote", remoteSrc.toHeader())
    }

    @Test
    fun chainHeader_includesAllAttemptedSources() {
        val src = ImageSource.Builder()
                .kind(ImageSource.Kind.LAN)
                .deviceId("dev-b")
                .triedChain(listOf("local", "lab:dev-a", "lab:dev-b"))
                .build()
        assertEquals("[local,lab:dev-a,lab:dev-b]", src.chainHeader())
    }
}