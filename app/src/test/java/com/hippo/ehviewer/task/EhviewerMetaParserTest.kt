package com.hippo.ehviewer.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class EhviewerMetaParserTest {

    private fun parse(content: String): EhviewerMetaParser.Result? {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val reader = BufferedReader(InputStreamReader(bytes.inputStream(), StandardCharsets.UTF_8))
        return EhviewerMetaParser.parseFromReader(reader)
    }

    @Test
    fun testV2Standard() {
        val content = buildString {
            appendLine("VERSION2")
            appendLine("00000000")
            appendLine("12345")
            appendLine("abc123token")
            appendLine("1")
            appendLine("5")
            appendLine("20")
            appendLine("3")
            appendLine("0 hash_aaa")
            appendLine("1 hash_bbb")
            appendLine("2 hash_ccc")
        }
        val result = parse(content)
        assertNotNull(result)
        result!!
        assertEquals(12345L, result.gid)
        assertEquals("abc123token", result.token)
        assertEquals(3, result.pages)
        assertEquals(3, result.hashes.size)
        assertEquals("hash_aaa", result.hashes[0])
        assertEquals("hash_bbb", result.hashes[1])
        assertEquals("hash_ccc", result.hashes[2])
        assertEquals("hash_aaa", result.indexToHash[0])
        assertEquals("hash_bbb", result.indexToHash[1])
        assertEquals("hash_ccc", result.indexToHash[2])
    }

    @Test
    fun testV1Format() {
        val content = buildString {
            appendLine("00000010")
            appendLine("99999")
            appendLine("token_v1")
            appendLine("1")
            appendLine("3")
            appendLine("15")
            appendLine("2")
            appendLine("0 hash1")
            appendLine("1 hash2")
        }
        val result = parse(content)
        assertNotNull(result)
        result!!
        assertEquals(99999L, result.gid)
        assertEquals("token_v1", result.token)
        assertEquals(2, result.pages)
        assertEquals(2, result.hashes.size)
    }

    @Test
    fun testTabSeparated() {
        val content = buildString {
            appendLine("VERSION2")
            appendLine("00000000")
            appendLine("100")
            appendLine("tok")
            appendLine("1")
            appendLine("1")
            appendLine("10")
            appendLine("2")
            appendLine("0\thash_x")
            appendLine("1\thash_y")
        }
        val result = parse(content)
        assertNotNull(result)
        result!!
        assertEquals(2, result.hashes.size)
        assertEquals("hash_x", result.indexToHash[0])
        assertEquals("hash_y", result.indexToHash[1])
    }

    @Test
    fun testWindowsLineEndings() {
        val content = "VERSION2\r\n00000000\r\n50\r\ntok\r\n1\r\n1\r\n10\r\n2\r\n0 hash_a\r\n1 hash_b\r\n"
        val result = parse(content)
        assertNotNull(result)
        result!!
        assertEquals(50L, result.gid)
        assertEquals("tok", result.token)
        assertEquals(2, result.hashes.size)
    }

    @Test
    fun testTooFewLines() {
        assertNull(parse("VERSION2\n00000000\n100\n"))
        assertNull(parse("VERSION2\n00000000\n100\ntok\n1\n"))
        assertNull(parse(""))
    }

    @Test
    fun testInvalidGid() {
        assertNull(parse("VERSION2\n00000000\nnot_a_number\ntok\n1\n1\n10\n2\n0 hash\n"))
    }

    @Test
    fun testInvalidPages() {
        assertNull(parse("VERSION2\n00000000\n100\ntok\n1\n1\n10\n0\n0 hash\n"))
        assertNull(parse("VERSION2\n00000000\n100\ntok\n1\n1\n10\n-5\n0 hash\n"))
    }

    @Test
    fun testMissingToken() {
        assertNull(parse("VERSION2\n00000000\n100\n\n1\n1\n10\n2\n0 hash\n"))
    }

    @Test
    fun testMalformedHashLinesSkipped() {
        val content = buildString {
            appendLine("VERSION2")
            appendLine("00000000")
            appendLine("100")
            appendLine("tok")
            appendLine("1")
            appendLine("1")
            appendLine("10")
            appendLine("3")
            appendLine("0 good_hash")
            appendLine("bad_line_no_space")
            appendLine("")
            appendLine("1 another_hash")
        }
        val result = parse(content)
        assertNotNull(result)
        result!!
        assertEquals(2, result.hashes.size)
        assertEquals("good_hash", result.hashes[0])
        assertEquals("another_hash", result.hashes[1])
    }

    @Test
    fun testEmptyHashLine() {
        val content = buildString {
            appendLine("VERSION2")
            appendLine("00000000")
            appendLine("100")
            appendLine("tok")
            appendLine("1")
            appendLine("1")
            appendLine("10")
            appendLine("2")
            appendLine("0 hash_a")
            appendLine("1 ")
        }
        val result = parse(content)
        assertNotNull(result)
        result!!
        assertEquals(1, result.hashes.size)
        assertEquals("hash_a", result.hashes[0])
    }

    @Test
    fun testLargePageCount() {
        val sb = StringBuilder()
        sb.appendLine("VERSION2")
        sb.appendLine("00000000")
        sb.appendLine("100")
        sb.appendLine("tok")
        sb.appendLine("1")
        sb.appendLine("1")
        sb.appendLine("10")
        sb.appendLine("200000")
        for (i in 0 until 5) {
            sb.appendLine("$i hash_$i")
        }
        assertNull(parse(sb.toString()))
    }

    @Test
    fun testDuplicateHashesPreserved() {
        val content = buildString {
            appendLine("VERSION2")
            appendLine("00000000")
            appendLine("100")
            appendLine("tok")
            appendLine("1")
            appendLine("1")
            appendLine("10")
            appendLine("3")
            appendLine("0 same_hash")
            appendLine("1 same_hash")
            appendLine("2 other_hash")
        }
        val result = parse(content)
        assertNotNull(result)
        result!!
        assertEquals(3, result.hashes.size)
        assertEquals("same_hash", result.indexToHash[0])
        assertEquals("same_hash", result.indexToHash[1])
        assertEquals("other_hash", result.indexToHash[2])
    }

    @Test
    fun testHashWithSpecialChars() {
        val content = buildString {
            appendLine("VERSION2")
            appendLine("00000000")
            appendLine("100")
            appendLine("tok")
            appendLine("1")
            appendLine("1")
            appendLine("10")
            appendLine("1")
            appendLine("0 abc123def456ghi789jkl012mno345pqr678stu9")
        }
        val result = parse(content)
        assertNotNull(result)
        result!!
        assertEquals(1, result.hashes.size)
        assertEquals("abc123def456ghi789jkl012mno345pqr678stu9", result.hashes[0])
    }
}
