package com.hippo.ehviewer.transfer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPageBitmapTest {

    @Test
    fun `protocol example 25 pages first 18 downloaded`() {
        val pages = (1..18).toList()
        assertEquals("__8DAA", RelayPageBitmap.encodePages(pages, 25))
    }

    @Test
    fun `round trip keeps page bits`() {
        val pages = listOf(1, 3, 4, 9, 25)
        val encoded = RelayPageBitmap.encodePages(pages, 25)
        val decoded = RelayPageBitmap.decode(encoded, 25)!!
        for (page in 1..25) {
            assertEquals("page $page", pages.contains(page), decoded[page - 1])
        }
        assertEquals(pages, RelayPageBitmap.downloadedPages(decoded, 25))
        assertTrue(RelayPageBitmap.missingPages(decoded, 25).contains(2))
        assertFalse(RelayPageBitmap.missingPages(decoded, 25).contains(3))
    }

    @Test
    fun `decoder accepts padded and standard base64`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x03, 0)
        val url = RelayPageBitmap.encodeBytes(bytes)
        val padded = url + "=".repeat((4 - url.length % 4) % 4)
        val standard = padded.replace('-', '+').replace('_', '/')
        val a = RelayPageBitmap.decode(url, 25)!!
        val b = RelayPageBitmap.decode(standard, 25)!!
        assertEquals(RelayPageBitmap.encode(a, 25), RelayPageBitmap.encode(b, 25))
    }

    @Test
    fun `bitmap equality ignores padding`() {
        assertTrue(RelayPoolManager.bitmapEquals("__8DAA", "__8DAA="))
        assertFalse(RelayPoolManager.bitmapEquals("__8DAA", "__8HAA"))
    }

    @Test
    fun `count and missing pages use disk semantics`() {
        val downloaded = BooleanArray(25).also {
            for (i in 0 until 7) it[i] = true
        }
        assertEquals(7, RelayPageBitmap.countDownloaded(downloaded, 25))
        assertEquals((8..25).toList(), RelayPageBitmap.missingPages(downloaded, 25))
    }
}
