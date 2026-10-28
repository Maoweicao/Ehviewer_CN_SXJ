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
        for (Listener listener : sListeners) {
            try {
                listener.onEvent(type, data);
            } catch (Throwable ignored) {
                // 单个监听器异常不得影响其它监听器与写操作
            }
        }
        return version;
    }

    /** 便捷方法：无附加字段的变更（如 data_changed） */
    public static long publish(String type) {
        return publish(type, 0L, null, null);
    }
}
