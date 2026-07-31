package com.hippo.ehviewer.network

import android.util.Log
import com.hippo.ehviewer.AppConfig
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object NetworkDiagnosticLogger {

    private const val TAG = "NetworkDiagLogger"

    private var isRecording = false
    private val entries = ConcurrentLinkedDeque<HarEntry>()

    data class HarEntry(
        val startedDateTime: String,
        val method: String,
        val url: String,
        val requestHeaders: List<Pair<String, String>>,
        val requestBody: String?,
        val httpVersion: String = "HTTP/1.1",
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
        isRecording = true
        entries.clear()
        Log.i(TAG, "Recording started")
    }

    fun stop() {
        isRecording = false
        Log.i(TAG, "Recording stopped, ${entries.size} entries captured")
    }

    fun isActive(): Boolean = isRecording

    fun createInterceptor(): Interceptor {
        return Interceptor { chain ->
            // 未开启录制时直接透传，避免无谓的对象分配与内存增长
            if (!isRecording) {
                return@Interceptor chain.proceed(chain.request())
            }
            val request = chain.request()
            val entry = HarEntry(
                startedDateTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()),
                method = request.method(),
                url = request.url().toString(),
                requestHeaders = request.headers().toPairs(),
                requestBody = request.body()?.let { body ->
                    try {
                        val buffer = okio.Buffer()
                        body.writeTo(buffer)
                        buffer.readUtf8()
                    } catch (e: Exception) {
                        "<binary>"
                    }
                }
            )

            val startTime = System.currentTimeMillis()
            val response: Response
            try {
                response = chain.proceed(request)
            } catch (e: Exception) {
                entry.timeMs = System.currentTimeMillis() - startTime
                entry.statusCode = -1
                entry.statusText = e.javaClass.simpleName
                entries.addLast(entry)
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
            entry.responseSize = response.body()?.contentLength() ?: 0

            entries.addLast(entry)
            response
        }
    }

    fun createEventListenerFactory(): EventListener.Factory {
        return object : EventListener.Factory {
            override fun create(call: Call): EventListener {
                return DiagnosticEventListener()
            }
        }
    }

    private class DiagnosticEventListener : EventListener() {
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

        override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: Protocol?) {
            if (connectStart > 0 && entries.isNotEmpty()) {
                val last = entries.peekLast()
                if (last != null) {
                    last.connectMs = System.currentTimeMillis() - connectStart
                }
            }
        }
    }

    fun exportHar(diagnosticSummary: String): File? {
        val logDir = AppConfig.getExternalLogcatDir() ?: return null
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(logDir, "network_diagnostic_$timestamp.har")

        val har = JSONObject()
        val log = JSONObject()

        log.put("version", "1.2")
        log.put("creator", JSONObject().apply {
            put("name", "EhViewer")
            put("version", "1.0")
        })
        log.put("comment", diagnosticSummary)

        val entriesArray = JSONArray()
        for (entry in entries) {
            entriesArray.put(buildHarEntry(entry))
        }
        log.put("entries", entriesArray)
        har.put("log", log)

        try {
            FileWriter(file).use { writer ->
                writer.write(har.toString(2))
            }
            Log.i(TAG, "HAR exported to ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export HAR", e)
            return null
        }
    }

    private fun buildHarEntry(entry: HarEntry): JSONObject {
        return JSONObject().apply {
            put("startedDateTime", entry.startedDateTime)
            put("time", entry.timeMs)
            put("request", JSONObject().apply {
                put("method", entry.method)
                put("url", entry.url)
                put("httpVersion", entry.httpVersion)
                put("headers", buildHeadersArray(entry.requestHeaders))
                put("queryString", JSONArray())
                put("headersSize", -1)
                put("bodySize", entry.requestBody?.length?.toLong() ?: 0)
                if (entry.requestBody != null) {
                    put("postData", JSONObject().apply {
                        put("mimeType", "application/json")
                        put("text", entry.requestBody)
                    })
                }
            })
            put("response", JSONObject().apply {
                put("status", entry.statusCode)
                put("statusText", entry.statusText)
                put("httpVersion", entry.httpVersion)
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

    fun getEntryCount(): Int = entries.size

    fun clear() {
        entries.clear()
    }
}
