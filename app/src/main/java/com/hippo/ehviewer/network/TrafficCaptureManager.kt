package com.hippo.ehviewer.network

import android.os.Environment
import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

object TrafficCaptureManager {

    private const val TAG = "TrafficCapture"
    private const val CAPTURE_DIR = "EhViewer/networkcapture"

    @Volatile
    private var isCapturing = false

    private val entries = ConcurrentLinkedDeque<HarEntry>()
    private val totalBytesSent = AtomicLong(0)
    private val totalBytesReceived = AtomicLong(0)
    private var captureStartTime = 0L

    data class HarEntry(
        val startedDateTime: String,
        val method: String,
        val url: String,
        val requestHeaders: List<Pair<String, String>>,
        val requestBodySize: Long,
        var statusCode: Int = 0,
        var statusText: String = "",
        var responseHeaders: List<Pair<String, String>> = emptyList(),
        var responseSize: Long = 0,
        var responseMimeType: String = "",
        var timeMs: Long = 0,
        var dnsMs: Long = 0,
        var connectMs: Long = 0,
        var sslMs: Long = 0,
        var sendMs: Long = 0,
        var waitMs: Long = 0,
        var receiveMs: Long = 0
    )

    fun start() {
        isCapturing = true
        entries.clear()
        totalBytesSent.set(0)
        totalBytesReceived.set(0)
        captureStartTime = System.currentTimeMillis()
        Log.i(TAG, "Traffic capture started")
    }

    fun stop() {
        isCapturing = false
        Log.i(TAG, "Traffic capture stopped, ${entries.size} entries")
    }

    fun isActive(): Boolean = isCapturing

    fun getEntryCount(): Int = entries.size

    fun getTotalBytesSent(): Long = totalBytesSent.get()

    fun getTotalBytesReceived(): Long = totalBytesReceived.get()

    fun getCaptureDurationMs(): Long =
        if (captureStartTime > 0) System.currentTimeMillis() - captureStartTime else 0

    fun getCaptureDir(): File {
        val external = Environment.getExternalStorageDirectory()
        val dir = File(external, CAPTURE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun createInterceptor(): Interceptor {
        return Interceptor { chain ->
            // 未开启抓包时直接透传，避免每次请求都无谓构造 HarEntry / 拷贝 header
            if (!isCapturing) {
                return@Interceptor chain.proceed(chain.request())
            }
            val request = chain.request()
            val startTime = System.currentTimeMillis()
            val requestBodySize = request.body()?.contentLength() ?: 0

            val entry = HarEntry(
                startedDateTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()),
                method = request.method(),
                url = request.url().toString(),
                requestHeaders = request.headers().toPairs(),
                requestBodySize = if (requestBodySize > 0) requestBodySize else 0
            )

            val response: Response
            try {
                response = chain.proceed(request)
            } catch (e: Exception) {
                entry.timeMs = System.currentTimeMillis() - startTime
                entry.statusCode = -1
                entry.statusText = e.javaClass.simpleName
                if (isCapturing) entries.addLast(entry)
                throw e
            }

            val elapsed = System.currentTimeMillis() - startTime
            entry.statusCode = response.code()
            entry.statusText = response.message()
            entry.responseHeaders = response.headers().toPairs()
            entry.timeMs = elapsed
            entry.sendMs = 1
            entry.waitMs = elapsed - 1
            entry.receiveMs = 1

            val contentType = response.body()?.contentType()
            entry.responseMimeType = contentType?.toString() ?: ""
            val bodySize = response.body()?.contentLength() ?: 0
            entry.responseSize = if (bodySize > 0) bodySize else 0

            if (requestBodySize > 0) totalBytesSent.addAndGet(requestBodySize)
            if (bodySize > 0) totalBytesReceived.addAndGet(bodySize)

            if (isCapturing) entries.addLast(entry)
            response
        }
    }

    fun createEventListenerFactory(): EventListener.Factory {
        return object : EventListener.Factory {
            override fun create(call: Call): EventListener {
                return CaptureEventListener()
            }
        }
    }

    private class CaptureEventListener : EventListener() {
        private var dnsStart = 0L
        private var connectStart = 0L
        private var sslStart = 0L

        override fun dnsStart(call: Call, domainName: String) {
            dnsStart = System.currentTimeMillis()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            if (dnsStart > 0 && entries.isNotEmpty()) {
                val last = entries.peekLast()
                if (last != null) {
                    last.dnsMs = System.currentTimeMillis() - dnsStart
                }
            }
        }

        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            connectStart = System.currentTimeMillis()
        }

        override fun secureConnectStart(call: Call) {
            sslStart = System.currentTimeMillis()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            if (sslStart > 0 && entries.isNotEmpty()) {
                val last = entries.peekLast()
                if (last != null) {
                    last.sslMs = System.currentTimeMillis() - sslStart
                }
            }
        }

        override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
            if (connectStart > 0 && entries.isNotEmpty()) {
                val last = entries.peekLast()
                if (last != null) {
                    last.connectMs = System.currentTimeMillis() - connectStart
                }
            }
        }
    }

    fun exportHar(): File? {
        val dir = getCaptureDir()
        val timestamp = SimpleDateFormat("yyyy-MM-ddHH:mm", Locale.US).format(Date())
        val file = File(dir, "$timestamp.har")

        val har = JSONObject()
        val log = JSONObject()

        log.put("version", "1.2")
        log.put("creator", JSONObject().apply {
            put("name", "EhViewer")
            put("version", "2.0.2.2")
        })
        log.put("comment", "EhViewer traffic capture, ${entries.size} entries")

        val entriesArray = JSONArray()
        for (entry in entries) {
            entriesArray.put(buildHarEntry(entry))
        }
        log.put("entries", entriesArray)
        har.put("log", log)

        return try {
            FileWriter(file).use { writer ->
                writer.write(har.toString(2))
            }
            Log.i(TAG, "HAR exported to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export HAR", e)
            null
        }
    }

    private fun buildHarEntry(entry: HarEntry): JSONObject {
        return JSONObject().apply {
            put("startedDateTime", entry.startedDateTime)
            put("time", entry.timeMs)
            put("request", JSONObject().apply {
                put("method", entry.method)
                put("url", entry.url)
                put("httpVersion", "HTTP/1.1")
                put("headers", buildHeadersArray(entry.requestHeaders))
                put("queryString", JSONArray())
                put("headersSize", -1)
                put("bodySize", entry.requestBodySize)
            })
            put("response", JSONObject().apply {
                put("status", entry.statusCode)
                put("statusText", entry.statusText)
                put("httpVersion", "HTTP/1.1")
                put("headers", buildHeadersArray(entry.responseHeaders))
                put("content", JSONObject().apply {
                    put("size", entry.responseSize)
                    put("mimeType", entry.responseMimeType)
                })
                put("headersSize", -1)
                put("bodySize", entry.responseSize)
                put("redirectURL", "")
            })
            put("cache", JSONObject())
            put("timings", JSONObject().apply {
                put("blocked", -1)
                put("dns", entry.dnsMs)
                put("connect", entry.connectMs)
                put("ssl", entry.sslMs)
                put("send", entry.sendMs)
                put("wait", entry.waitMs)
                put("receive", entry.receiveMs)
            })
            put("time", entry.timeMs)
        }
    }

    private fun buildHeadersArray(headers: List<Pair<String, String>>): JSONArray {
        val arr = JSONArray()
        for ((name, value) in headers) {
            arr.put(JSONObject().apply {
                put("name", name)
                put("value", value)
            })
        }
        return arr
    }

    private fun okhttp3.Headers.toPairs(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until this.size()) {
            list.add(this.name(i) to this.value(i))
        }
        return list
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
