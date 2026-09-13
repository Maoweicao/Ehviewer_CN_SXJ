/*
 * Copyright 2026
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

package com.hippo.ehviewer.util;

import android.util.Log;

/**
 * 画廊搜索流程的 DEBUG 级日志工具。
 *
 * <p>统一使用 logcat tag {@code EhSearch} 前缀，方便过滤：</p>
 * <pre>
 *   adb logcat -s EhSearch:* EhSearch.*:D
 * </pre>
 * 或者
 * <pre>
 *   adb logcat | findstr EhSearch
 * </pre>
 *
 * <p>日志覆盖：tag 数据库建议/翻译查询、高级搜索选项组装、搜索 URL 构造等。
 * 由于不涉及真正的 SQLite，涉及“查库”时以等价的伪 SQL 形式输出，便于理解匹配规则。</p>
 */
public final class SearchDebugLog {

    public static final String TAG = "EhSearch";

    /** 是否输出日志。置 false 可彻底关闭（Release 包也可保留，默认 logcat 会过滤 D 级别）。 */
    private static final boolean ENABLED = true;

    private SearchDebugLog() {
    }

    /** @param section 小节名，例如 EhTagDB / GallerySearch / AdvanceSearch */
    public static void d(String section, String message) {
        if (ENABLED) {
            Log.d(TAG, '[' + section + "] " + message);
        }
    }
}