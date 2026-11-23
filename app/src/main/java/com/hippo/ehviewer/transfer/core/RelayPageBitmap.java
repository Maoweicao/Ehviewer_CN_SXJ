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

package com.hippo.ehviewer.transfer.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 页位图编解码（协议 §5.17.2，能力 page-bitmap-v1）。
 *
 * <pre>
 * 第 1 页 = bit 0，第 n 页 = bit (n-1)
 * 字节内 LSB first
 * 长度 = ceil(totalPages / 8)
 * base64url（- _），省略 "="，解码时同时接受带 "=" 的输入
 * </pre>
 */
public final class RelayPageBitmap {

    private static final char[] URL_SAFE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final int[] REVERSE = new int[128];

    static {
        for (int i = 0; i < REVERSE.length; i++) {
            REVERSE[i] = -1;
        }
        for (int i = 0; i < URL_SAFE.length; i++) {
            REVERSE[URL_SAFE[i]] = i;
        }
        // 兼容标准 base64 的 + / 输入
        REVERSE['+'] = 62;
        REVERSE['/'] = 63;
    }

    private RelayPageBitmap() {
    }

    /** 由已下载页集合编码位图。downloaded 下标 0 表示第 1 页。 */
    @NonNull
    public static String encode(@Nullable boolean[] downloaded, int totalPages) {
        if (totalPages <= 0) {
            return "";
        }
        int byteCount = (totalPages + 7) / 8;
        byte[] bytes = new byte[byteCount];
        if (downloaded != null) {
            int n = Math.min(downloaded.length, totalPages);
            for (int i = 0; i < n; i++) {
                if (downloaded[i]) {
                    bytes[i >> 3] |= (byte) (1 << (i & 7));
                }
            }
        }
        return encodeBytes(bytes);
    }

    /** 由页码集合（1-indexed）编码位图。 */
    @NonNull
    public static String encodePages(@Nullable List<Integer> pages, int totalPages) {
        boolean[] downloaded = new boolean[Math.max(0, totalPages)];
        if (pages != null) {
            for (Integer page : pages) {
                if (page != null && page >= 1 && page <= totalPages) {
                    downloaded[page - 1] = true;
                }
            }
        }
        return encode(downloaded, totalPages);
    }

    @NonNull
    public static String encodeBytes(@NonNull byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder((bytes.length * 4 + 2) / 3);
        int i = 0;
        while (i + 2 < bytes.length) {
            int n = ((bytes[i] & 0xFF) << 16) | ((bytes[i + 1] & 0xFF) << 8) | (bytes[i + 2] & 0xFF);
            sb.append(URL_SAFE[(n >> 18) & 63]);
            sb.append(URL_SAFE[(n >> 12) & 63]);
            sb.append(URL_SAFE[(n >> 6) & 63]);
            sb.append(URL_SAFE[n & 63]);
            i += 3;
        }
        int remaining = bytes.length - i;
        if (remaining == 1) {
            int n = (bytes[i] & 0xFF) << 16;
            sb.append(URL_SAFE[(n >> 18) & 63]);
            sb.append(URL_SAFE[(n >> 12) & 63]);
        } else if (remaining == 2) {
            int n = ((bytes[i] & 0xFF) << 16) | ((bytes[i + 1] & 0xFF) << 8);
            sb.append(URL_SAFE[(n >> 18) & 63]);
            sb.append(URL_SAFE[(n >> 12) & 63]);
            sb.append(URL_SAFE[(n >> 6) & 63]);
        }
        return sb.toString();
    }

    /**
     * 解码位图。输入允许省略或携带 "="，并兼容标准 base64 字符。
     *
     * @return 第 1 页对应下标 0 的布尔数组；无法解析时返回 null
     */
    @Nullable
    public static boolean[] decode(@Nullable String encoded, int totalPages) {
        if (encoded == null) {
            return null;
        }
        String s = encoded.trim();
        if (s.isEmpty()) {
            return new boolean[Math.max(0, totalPages)];
        }
        // 去掉填充与空白
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || Character.isWhitespace(c)) {
                continue;
            }
            if (c >= 128 || REVERSE[c] < 0) {
                return null;
            }
            clean.append(c);
        }
        int len = clean.length();
        if (len % 4 == 1) {
            return null;
        }
        int byteCount = (len * 3) / 4;
        byte[] bytes = new byte[byteCount];
        int out = 0;
        int i = 0;
        while (i < len) {
            int remaining = len - i;
            int c0 = REVERSE[clean.charAt(i)];
            int c1 = remaining > 1 ? REVERSE[clean.charAt(i + 1)] : 0;
            int c2 = remaining > 2 ? REVERSE[clean.charAt(i + 2)] : 0;
            int c3 = remaining > 3 ? REVERSE[clean.charAt(i + 3)] : 0;
            if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) {
                return null;
            }
            int n = (c0 << 18) | (c1 << 12) | (c2 << 6) | c3;
            if (out < bytes.length) {
                bytes[out++] = (byte) ((n >> 16) & 0xFF);
            }
            if (out < bytes.length) {
                bytes[out++] = (byte) ((n >> 8) & 0xFF);
            }
            if (out < bytes.length) {
                bytes[out++] = (byte) (n & 0xFF);
            }
            i += 4;
        }
        int needed = (Math.max(0, totalPages) + 7) / 8;
        boolean[] result = new boolean[Math.max(0, totalPages)];
        int usable = Math.min(bytes.length, needed);
        for (int p = 0; p < totalPages; p++) {
            int byteIndex = p >> 3;
            if (byteIndex >= usable) {
                break;
            }
            result[p] = (bytes[byteIndex] & (1 << (p & 7))) != 0;
        }
        return result;
    }

    /** 位图 -> 缺页列表（1-indexed）。 */
    @NonNull
    public static List<Integer> missingPages(@Nullable boolean[] downloaded, int totalPages) {
        List<Integer> missing = new ArrayList<>();
        if (totalPages <= 0) {
            return missing;
        }
        for (int i = 0; i < totalPages; i++) {
            if (downloaded == null || i >= downloaded.length || !downloaded[i]) {
                missing.add(i + 1);
            }
        }
        return missing;
    }

    /** 位图 -> 已下载页数。 */
    public static int countDownloaded(@Nullable boolean[] downloaded, int totalPages) {
        if (downloaded == null || totalPages <= 0) {
            return 0;
        }
        int count = 0;
        int n = Math.min(downloaded.length, totalPages);
        for (int i = 0; i < n; i++) {
            if (downloaded[i]) {
                count++;
            }
        }
        return count;
    }

    /** 位图 -> 已下载页码列表（1-indexed）。 */
    @NonNull
    public static List<Integer> downloadedPages(@Nullable boolean[] downloaded, int totalPages) {
        List<Integer> pages = new ArrayList<>();
        if (downloaded == null || totalPages <= 0) {
            return pages;
        }
        int n = Math.min(downloaded.length, totalPages);
        for (int i = 0; i < n; i++) {
            if (downloaded[i]) {
                pages.add(i + 1);
            }
        }
        return pages;
    }

    /** 位图 -> BitSet。 */
    @NonNull
    public static BitSet toBitSet(@Nullable boolean[] downloaded, int totalPages) {
        BitSet set = new BitSet(Math.max(0, totalPages));
        if (downloaded != null) {
            int n = Math.min(downloaded.length, totalPages);
            for (int i = 0; i < n; i++) {
                if (downloaded[i]) {
                    set.set(i);
                }
            }
        }
        return set;
    }
}
