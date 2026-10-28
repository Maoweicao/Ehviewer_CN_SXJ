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

package com.hippo.ehviewer.transfer.api;

import android.content.Context;

import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.DataChangeBus;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import fi.iki.elonen.NanoHTTPD;

/**
 * 实时变更 API 处理器（协议 §5.28）
 *
 * <ul>
 *   <li>GET /api/v1/system/version  数据版本号（轮询）</li>
 *   <li>GET /api/v1/events          事件流（SSE）</li>
 * </ul>
 */
public class EventsApiHandler extends BaseApiHandler {

    private static final String TAG = "EventsApiHandler";

    /** 心跳间隔（毫秒） */
    private static final long HEARTBEAT_MS = 25_000L;
    /** 单连接事件队列容量（满则丢弃，避免阻塞发布线程） */
    private static final int QUEUE_CAPACITY = 64;
    /** SSE 并发连接上限（见协议 §5.1.5 有界并发） */
    private static final int MAX_SSE_CONNECTIONS = 8;

    private static final AtomicInteger sSseConnections = new AtomicInteger(0);

    public EventsApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri, session);
        String path = uri.split("\\?")[0];
        if (path.equals("/api/v1/system/version")) {
            return handleVersion(session);
        }
        if (path.equals("/api/v1/events")) {
            return handleEvents(session);
        }
        return ResponseBuilder.notFound("Endpoint");
    }

    private NanoHTTPD.Response handleVersion(NanoHTTPD.IHTTPSession session) {
        long version = DataChangeBus.getVersion();
        long since = RequestParser.getLongQueryParameter(session, "since", 0L);
        StringBuilder sb = new StringBuilder(80);
        sb.append("{\"version\":").append(version);
        sb.append(",\"since\":").append(since);
        if (since > 0) {
            sb.append(",\"changed\":").append(version > since);
        }
        sb.append('}');
        return ResponseBuilder.jsonSuccess(sb.toString());
    }

    private NanoHTTPD.Response handleEvents(NanoHTTPD.IHTTPSession session) {
        if (sSseConnections.get() >= MAX_SSE_CONNECTIONS) {
            TransferLogger.getInstance().w(TAG, "SSE connection limit reached, returning 503");
            return ResponseBuilder.serviceUnavailable();
        }
        sSseConnections.incrementAndGet();

        final PipedOutputStream pos = new PipedOutputStream();
        final PipedInputStream pis;
        try {
            pis = new PipedInputStream(pos, 16 * 1024);
        } catch (IOException e) {
            sSseConnections.decrementAndGet();
            return ResponseBuilder.internalError(e.getMessage());
        }

        final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        final AtomicBoolean running = new AtomicBoolean(true);

        final DataChangeBus.Listener listener = (type, dataJson) -> {
            // 非阻塞投递，队列满则丢弃（客户端可退回轮询）
            queue.offer(formatEvent(type, dataJson));
        };
        DataChangeBus.addListener(listener);

        Thread writer = new Thread(() -> {
            OutputStream out = pos;
            try {
                // 建立连接后立即下发一次心跳，携带当前版本
                writeEvent(out, "heartbeat", heartbeatData());
                while (running.get()) {
                    String msg = queue.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS);
                    if (msg == null) {
                        writeEvent(out, "heartbeat", heartbeatData());
                    } else {
                        out.write(msg.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                TransferLogger.getInstance().d(TAG, "SSE client disconnected: " + e.getMessage());
            } finally {
                running.set(false);
                DataChangeBus.removeListener(listener);
                try {
                    pos.close();
                } catch (IOException ignored) {
                }
                sSseConnections.decrementAndGet();
            }
        }, "TransferSse");
        writer.setDaemon(true);
        writer.start();

        NanoHTTPD.Response response = NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.OK, "text/event-stream", (InputStream) pis);
        response.addHeader("Cache-Control", "no-cache");
        response.addHeader("Connection", "keep-alive");
        return response;
    }

    private static void writeEvent(OutputStream out, String event, String data) throws IOException {
        String s = formatEvent(event, data);
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String formatEvent(String event, String data) {
        return "event: " + event + "\n" + "data: " + data + "\n\n";
    }

    private static String heartbeatData() {
        return "{\"version\":" + DataChangeBus.getVersion()
                + ",\"time\":" + System.currentTimeMillis() + "}";
    }
}
