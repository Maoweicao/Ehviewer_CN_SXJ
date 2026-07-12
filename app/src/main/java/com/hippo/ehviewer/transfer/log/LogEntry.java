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

package com.hippo.ehviewer.transfer.log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日志条目数据模型
 */
public class LogEntry {

    private final long timestamp;
    private final LogLevel level;
    private final String tag;
    private final String message;
    private final String stackTrace;

    public LogEntry(long timestamp, LogLevel level, String tag, String message) {
        this(timestamp, level, tag, message, null);
    }

    public LogEntry(long timestamp, LogLevel level, String tag, String message, String stackTrace) {
        this.timestamp = timestamp;
        this.level = level;
        this.tag = tag;
        this.message = message;
        this.stackTrace = stackTrace;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public LogLevel getLevel() {
        return level;
    }

    public String getTag() {
        return tag;
    }

    public String getMessage() {
        return message;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    /**
     * 格式化时间
     */
    public String formatTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    /**
     * 格式化完整日志
     */
    public String format() {
        return String.format("%s [%s] %s: %s", formatTime(), level.getShortName(), tag, message);
    }

    /**
     * 格式化为导出文本
     */
    public String formatForExport() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s [%s] %s: %s\n",
                sdf.format(new Date(timestamp)),
                level.getShortName(),
                tag,
                message));

        if (stackTrace != null && !stackTrace.isEmpty()) {
            sb.append(stackTrace).append("\n");
        }

        return sb.toString();
    }
}
