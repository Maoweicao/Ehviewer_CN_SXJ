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

/**
 * 日志级别枚举
 */
public enum LogLevel {
    VERBOSE("V", android.util.Log.VERBOSE, "#666666"),
    DEBUG("D", android.util.Log.DEBUG, "#999999"),
    INFO("I", android.util.Log.INFO, "#FFFFFF"),
    WARN("W", android.util.Log.WARN, "#FFEB3B"),
    ERROR("E", android.util.Log.ERROR, "#F44336");

    private final String shortName;
    private final int priority;
    private final String color;

    LogLevel(String shortName, int priority, String color) {
        this.shortName = shortName;
        this.priority = priority;
        this.color = color;
    }

    public String getShortName() {
        return shortName;
    }

    public int getPriority() {
        return priority;
    }

    public String getColor() {
        return color;
    }

    /**
     * 从短名称获取级别
     */
    public static LogLevel fromShortName(String name) {
        for (LogLevel level : values()) {
            if (level.shortName.equals(name)) {
                return level;
            }
        }
        return DEBUG;
    }

    /**
     * 从优先级获取级别
     */
    public static LogLevel fromPriority(int priority) {
        for (LogLevel level : values()) {
            if (level.priority == priority) {
                return level;
            }
        }
        return DEBUG;
    }
}
