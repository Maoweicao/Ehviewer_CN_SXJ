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

package com.hippo.ehviewer.transfer.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据变更总线（协议 §5.28）
 *
 * <p>维护一个单调递增的全局数据版本号，任何写操作（收藏增删、下载增删改、登录态变更、
 * 导入、删除等）都调用 {@link #publish} 将其 {@code +1} 并广播事件，使:
 * <ul>
 *   <li>Web 端可通过 {@code GET /api/v1/system/version} 轮询感知变化；</li>
 *   <li>Web 端可通过 {@code GET /api/v1/events}（SSE）近实时接收事件；</li>
 *   <li>Android 端 UI 可通过 {@link #addListener} 在进程内监听并刷新。</li>
 * </ul>
 */
public final class DataChangeBus {

    // 事件类型（协议 §5.28.2）
    public static final String TYPE_FAVORITE_ADDED = "favorite_added";
    public static final String TYPE_FAVORITE_REMOVED = "favorite_removed";
    public static final String TYPE_DOWNLOAD_ADDED = "download_added";
    public static final String TYPE_DOWNLOAD_CHANGED = "download_changed";
    public static final String TYPE_DOWNLOAD_REMOVED = "download_removed";
    public static final String TYPE_LOGIN_CHANGED = "login_changed";
    public static final String TYPE_DATA_CHANGED = "data_changed";
    // §5.17 接力下载事件（与 §5.23 labEvent 同名同义）
    public static final String TYPE_RELAY_POOL_CHANGED = "relay_pool_changed";
    public static final String TYPE_RELAY_TASK_CHANGED = "relay_task_changed";
    public static final String TYPE_PAGE_RECEIVED = "page_received";
    public static final String TYPE_RELAY_LEASE_EXPIRED = "relay_lease_expired";
    public static final String TYPE_GALLERY_PROGRESS = "gallery_progress";
    public static final String TYPE_GALLERY_COMPLETED = "gallery_completed";

    private static final AtomicLong sVersion = new AtomicLong(0L);

    private static final String PREFS_NAME = "transfer_change_bus";
    private static final String KEY_VERSION = "data_version";

    private static volatile SharedPreferences sPrefs;
    private static volatile boolean sInitialized;

    private DataChangeBus() {
    }

    /**
     * 载入持久化版本号。传输服务启动时调用一次即可；重复调用无副作用。
     *
     * <p>版本号跨进程重启保持单调，客户端保存的 §5.28/§5.29 水位在重启后仍然有效。
     */
    public static synchronized void init(Context context) {
        if (sInitialized || context == null) {
            return;
        }
        try {
            Context app = context.getApplicationContext();
            sPrefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            sVersion.set(Math.max(0L, sPrefs.getLong(KEY_VERSION, 0L)));
            sInitialized = true;
        } catch (Throwable ignored) {
            // 拿不到 Context 时退化为纯内存计数，不影响协议基本可用性
        }
    }

    /** 当前数据版本号 */
    public static long getVersion() {
        return sVersion.get();
    }

    /**
     * 仅推进版本号，不广播事件。
     *
     * <p>供画廊索引在构建清单时发现内容漂移后使用：客户端轮询 §5.28.1 也能看到变化，
     * 但不会因此收到大量无意义的 SSE 事件。
     *
     * @return 新的版本号
     */
    public static long touch() {
        return nextVersion();
    }

    private static long nextVersion() {
        long version = sVersion.incrementAndGet();
        SharedPreferences prefs = sPrefs;
        if (prefs != null) {
            try {
                prefs.edit().putLong(KEY_VERSION, version).apply();
            } catch (Throwable ignored) {
            }
        }
        return version;
    }

    /**
     * 事件监听器。回调在发布线程执行，实现方必须快速返回，禁止阻塞或做重活。
     */
    public interface Listener {
        void onEvent(String type, String dataJson);
    }

    private static final CopyOnWriteArrayList<Listener> sListeners = new CopyOnWriteArrayList<>();

    /** §5.23 labEvent 待投递队列（下次 /connect 心跳带回）。 */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> sLabEvents =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final int MAX_LAB_EVENTS = 64;

    public static void addListener(Listener listener) {
        if (listener != null) {
            sListeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        if (listener != null) {
            sListeners.remove(listener);
        }
    }

    /**
     * 发布一次数据变更：版本号 +1 并广播事件。
     *
     * @param type     事件类型，见常量
     * @param gid      相关画廊 gid，无则传 0
     * @param state    下载状态（仅 download_changed 有意义），可为 null
     * @param signedIn 登录状态（仅 login_changed 有意义），可为 null
     * @return 新的数据版本号
     */
    public static long publish(String type, long gid, Integer state, Boolean signedIn) {
        long version = nextVersion();
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"version\":").append(version);
        if (gid > 0) {
            sb.append(",\"gid\":").append(gid);
        }
        if (state != null) {
            sb.append(",\"state\":").append(state);
        }
        if (signedIn != null) {
            sb.append(",\"signedIn\":").append(signedIn);
        }
        sb.append(",\"time\":").append(System.currentTimeMillis()).append('}');
        String data = sb.toString();
        enqueueLabEvent(type, data);
        broadcast(type, data);
        return version;
    }

    /** 便捷方法：无附加字段的变更（如 data_changed） */
    public static long publish(String type) {
        return publish(type, 0L, null, null);
    }

    /**
     * 发布带自定义字段的事件（§5.17 接力事件）。
     *
     * @param type       事件类型
     * @param fieldsJson 不含外层大括号的 JSON 字段串，例如
     *                   {@code "gid":123,"status":"partial"}；可为 null
     * @return 新的数据版本号
     */
    public static long publishFields(String type, String fieldsJson) {
        long version = nextVersion();
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"version\":").append(version);
        if (fieldsJson != null && !fieldsJson.isEmpty()) {
            sb.append(',').append(fieldsJson);
        }
        sb.append(",\"time\":").append(System.currentTimeMillis()).append('}');
        String data = sb.toString();
        enqueueLabEvent(type, data);
        broadcast(type, data);
        return version;
    }

    private static void enqueueLabEvent(String type, String data) {
        sLabEvents.offer("{\"type\":" + quote(type) + ",\"payload\":" + data + "}");
        while (sLabEvents.size() > MAX_LAB_EVENTS) {
            sLabEvents.poll();
        }
    }

    /**
     * 取出并清空待投递的 labEvent（§5.23）。
     *
     * @return JSON 数组字符串；无事件时返回 null
     */
    @Nullable
    public static String drainLabEventsJson() {
        if (sLabEvents.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append('[');
        boolean first = true;
        String item;
        while ((item = sLabEvents.poll()) != null) {
            if (!first) sb.append(',');
            sb.append(item);
            first = false;
        }
        sb.append(']');
        return first ? null : sb.toString();
    }

    private static void broadcast(String type, String data) {
        for (Listener listener : sListeners) {
            try {
                listener.onEvent(type, data);
            } catch (Throwable ignored) {
                // 单个监听器异常不得影响其它监听器与写操作
            }
        }
    }

    /** JSON 字符串字段转义（双引号包裹）。 */
    public static String quote(@Nullable String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
