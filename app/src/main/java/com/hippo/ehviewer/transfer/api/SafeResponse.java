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

package com.hippo.ehviewer.transfer.api;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.transfer.core.ResponseCache;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import fi.iki.elonen.NanoHTTPD;

/**
 * 抗 OOM 的固定长度响应。
 *
 * <p>历史 crash（例如 {@code crash-2026-10-14}）的爆发点就在
 * {@link NanoHTTPD.Response#send(OutputStream)}：堆已被传输列表/多下载耗尽，
 * 连 writer 的 8KB 缓冲都分配不出来，异常沿 {@code ClientHandler} 线程上抛导致进程崩溃。
 *
 * <p>本类在 {@code send} 外层兜住 {@link OutOfMemoryError} 与其它 {@link Throwable}，
 * 触发时清理内存缓存与响应缓存，尽量不再把进程带崩；同时把 String→byte[] 的编码
 * 放进工厂方法里，编码失败时退回一个极小的静态 503 响应体。
 */
public class SafeResponse extends NanoHTTPD.Response {

    private static final String TAG = "SafeResponse";

    private static final byte[] OOM_BODY =
            "{\"success\":false,\"error\":\"server low memory\",\"code\":503}"
                    .getBytes(StandardCharsets.UTF_8);

    private SafeResponse(IStatus status, String mimeType, byte[] body) {
        super(status, mimeType, new ByteArrayInputStream(body), body.length);
    }

    /** 用 JSON 字符串构造抗 OOM 响应；编码本身 OOM 时退回静态 503。 */
    public static NanoHTTPD.Response json(IStatus status, String json) {
        byte[] body = null;
        try {
            if (json != null) {
                body = json.getBytes(StandardCharsets.UTF_8);
            }
        } catch (OutOfMemoryError oom) {
            TransferLogger.getInstance().e(TAG, "OOM while encoding JSON body", oom);
            freeMemoryCaches();
        }
        if (body == null) {
            return new SafeResponse(Status.SERVICE_UNAVAILABLE, "application/json", OOM_BODY);
        }
        return new SafeResponse(status, "application/json", body);
    }

    /** 服务端低内存/过载的极小 503 响应。 */
    public static NanoHTTPD.Response serviceUnavailable() {
        return new SafeResponse(Status.SERVICE_UNAVAILABLE, "application/json", OOM_BODY);
    }

    @Override
    protected void send(OutputStream outputStream) {
        try {
            super.send(outputStream);
        } catch (OutOfMemoryError oom) {
            // 只记录并释放缓存，不重试写出：此刻头部可能已部分写出，重写会破坏协议；
            // 目标是避免异常上抛把进程带崩。
            TransferLogger.getInstance().e(TAG, "OOM while sending HTTP response", oom);
            freeMemoryCaches();
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "Error while sending HTTP response", t);
        }
    }

    private static void freeMemoryCaches() {
        try {
            EhApplication.clearMemoryCacheSafely();
        } catch (Throwable ignored) {
        }
        try {
            ResponseCache.getInstance().clear();
        } catch (Throwable ignored) {
        }
    }
}
