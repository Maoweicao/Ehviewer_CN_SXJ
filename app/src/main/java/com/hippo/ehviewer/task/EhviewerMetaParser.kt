package com.hippo.ehviewer.task

import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.unifile.UniFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object EhviewerMetaParser {

    private const val VERSION_PREFIX = "VERSION"
    private const val MAX_PAGES = 100_000
    private const val MAX_LINE_LENGTH = 8192

    data class Result(
        val gid: Long,
        val token: String,
        val pages: Int,
        val hashes: List<String>,
        val indexToHash: Map<Int, String>
    )

    fun parse(dir: UniFile): Result? {
        val file = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
            ?: return null
        if (!file.exists() || !file.isFile) return null

        var reader: BufferedReader? = null
        return try {
            val inputStream = file.openInputStream()
            reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            parseFromReader(reader)
        } catch (_: Exception) {
            null
        } finally {
            try { reader?.close() } catch (_: Exception) {}
        }
    }

    fun parseFromReader(reader: BufferedReader): Result? {
        val firstLine = readLine(reader) ?: return null
        val gid: Long
        val token: String

        if (firstLine.startsWith(VERSION_PREFIX)) {
            readLine(reader) ?: return null // v2 startPage (hex)
            gid = readLine(reader)?.toLongOrNull() ?: return null
            token = readLine(reader) ?: return null
            readLine(reader) ?: return null // deprecated mode
            readLine(reader) ?: return null // previewPages
            readLine(reader) ?: return null // previewPerPage
        } else {
            // v1: firstLine is startPage (hex), already consumed
            gid = readLine(reader)?.toLongOrNull() ?: return null
            token = readLine(reader) ?: return null
            readLine(reader) ?: return null // deprecated mode
            readLine(reader) ?: return null // previewPages
            readLine(reader) ?: return null // previewPerPage
        }

        val pagesLine = readLine(reader) ?: return null
        val pages = pagesLine.toIntOrNull() ?: return null
        if (pages <= 0 || pages > MAX_PAGES) return null

        val hashes = ArrayList<String>(pages)
        val indexToHash = HashMap<Int, String>(pages)

        while (true) {
            val line = readLine(reader) ?: break
            val sepIdx = indexOfWhitespace(line)
            if (sepIdx <= 0 || sepIdx >= line.length - 1) continue
            val idx = line.substring(0, sepIdx).toIntOrNull() ?: continue
            val hash = line.substring(sepIdx + 1).trim()
            if (hash.isEmpty()) continue
            hashes.add(hash)
            indexToHash[idx] = hash
        }

        if (gid == -1L || token.isEmpty()) return null

        return Result(
            gid = gid,
            token = token,
            pages = pages,
            hashes = hashes,
            indexToHash = indexToHash
        )
    }

    private fun indexOfWhitespace(s: String): Int {
        for (i in s.indices) {
            val c = s[i]
            if (c == ' ' || c == '\t') return i
        }
        return -1
    }

    private fun readLine(reader: BufferedReader): String? {
        val raw = reader.readLine() ?: return null
        val line = if (raw.length > MAX_LINE_LENGTH) raw.substring(0, MAX_LINE_LENGTH) else raw
        return line.trim()
    }
}
