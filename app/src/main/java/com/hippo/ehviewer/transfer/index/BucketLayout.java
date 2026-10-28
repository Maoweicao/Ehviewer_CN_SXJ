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

package com.hippo.ehviewer.transfer.index;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * 画廊索引的分区布局与编码工具（协议 §5.29.1）。
 *
 * <p>纯 Java 实现，不依赖 Android，便于单元测试：
 * <ul>
 *   <li>GID 固定分桶与桶区间换算；</li>
 *   <li>FNV-1a 32 位指纹；</li>
 *   <li>LSB-first 位图与 u16 小端计数的 base64url 编解码（省略 {@code =}）。</li>
 * </ul>
 */
public final class BucketLayout {

    /** 协议支持的桶宽；当前主机固定使用 {@link #DEFAULT_BUCKET_SIZE}。 */
    public static final int[] SUPPORTED_BUCKET_SIZES = {256, 512, 1024, 2048, 4096, 8192};

    public static final int DEFAULT_BUCKET_SIZE = 512;

    /** 索引格式版本；语义不兼容升级时应更换 epoch。 */
    public static final int FORMAT_VERSION = 1;

    /** 视为合法的最大 GID。E-Hentai 实际 GID 远小于该值，同时保证桶号可放入 int。 */
    public static final long MAX_GID = 0x7FFFFFFFL;

    /** system/info 中声明本能力的键名。 */
    public static final String CAPABILITY = "gallery-index-v1";

    private static final char[] BASE64URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private BucketLayout() {
    }

    public static boolean isSupportedBucketSize(int bucketSize) {
        for (int size : SUPPORTED_BUCKET_SIZES) {
            if (size == bucketSize) {
                return true;
            }
        }
        return false;
    }

    /** gid 所属桶号；要求 gid &gt; 0 且不超过 {@link #MAX_GID}。 */
    public static int bucketOf(long gid, int bucketSize) {
        if (gid <= 0 || gid > MAX_GID || bucketSize <= 0) {
            return -1;
        }
        return (int) (gid / bucketSize);
    }

    /** 桶的起始 GID（含）。 */
    public static long bucketFrom(int bucket, int bucketSize) {
        return (long) bucket * bucketSize;
    }

    /** 桶的结束 GID（含）。 */
    public static long bucketTo(int bucket, int bucketSize) {
        return (long) bucket * bucketSize + bucketSize - 1L;
    }

    /** 桶号是否落在协议允许的 GID 范围内。 */
    public static boolean isBucketInRange(int bucket, int bucketSize) {
        return bucket >= 0 && bucketFrom(bucket, bucketSize) <= MAX_GID;
    }

    /**
     * FNV-1a 32 位指纹，返回 8 位小写十六进制。
     */
    public static String fnv1a32(String value) {
        int hash = 0x811C9DC5;
        if (value != null) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                hash ^= (b & 0xFF);
                hash *= 0x01000193;
            }
        }
        char[] hex = new char[8];
        for (int i = 7; i >= 0; i--) {
            int digit = hash & 0xF;
            hex[i] = (char) (digit < 10 ? '0' + digit : 'a' + digit - 10);
            hash >>>= 4;
        }
        return new String(hex);
    }

    /**
     * 生成 LSB-first 位图并做 base64url 编码。
     *
     * @param baseBucket   位 0 对应的桶号
     * @param bitCount     位图覆盖的桶数
     * @param setBuckets   需要置位的绝对桶号集合（范围外的忽略）
     */
    public static String bitmap(int baseBucket, int bitCount, Collection<Integer> setBuckets) {
        if (bitCount <= 0) {
            return "";
        }
        BitSet bits = new BitSet(bitCount);
        if (setBuckets != null) {
            for (Integer bucket : setBuckets) {
                if (bucket == null) {
                    continue;
                }
                int index = bucket - baseBucket;
                if (index >= 0 && index < bitCount) {
                    bits.set(index);
                }
            }
        }
        byte[] out = new byte[(bitCount + 7) / 8];
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            out[i >>> 3] |= (byte) (1 << (i & 7));
        }
        return encodeBase64Url(out);
    }

    /** 生成 u16 小端计数数组并做 base64url 编码；{@code counts[i]} 对应 {@code baseBucket + i}。 */
    public static String counts(int[] counts) {
        if (counts == null || counts.length == 0) {
            return "";
        }
        byte[] out = new byte[counts.length * 2];
        for (int i = 0; i < counts.length; i++) {
            int value = Math.max(0, Math.min(0xFFFF, counts[i]));
            out[i * 2] = (byte) (value & 0xFF);
            out[i * 2 + 1] = (byte) ((value >>> 8) & 0xFF);
        }
        return encodeBase64Url(out);
    }

    /** 位图置位的桶号集合（按升序）。 */
    public static Set<Integer> decodeBitmap(String base64url, int baseBucket, int bitCount) {
        TreeSet<Integer> result = new TreeSet<>();
        if (bitCount <= 0 || base64url == null || base64url.isEmpty()) {
            return result;
        }
        byte[] data = decodeBase64Url(base64url);
        for (int i = 0; i < bitCount; i++) {
            int byteIndex = i >>> 3;
            if (byteIndex < data.length && ((data[byteIndex] >>> (i & 7)) & 1) != 0) {
                result.add(baseBucket + i);
            }
        }
        return result;
    }

    public static String encodeBase64Url(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 3 <= data.length) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(BASE64URL[(chunk >>> 18) & 0x3F]);
            sb.append(BASE64URL[(chunk >>> 12) & 0x3F]);
            sb.append(BASE64URL[(chunk >>> 6) & 0x3F]);
            sb.append(BASE64URL[chunk & 0x3F]);
            i += 3;
        }
        int remaining = data.length - i;
        if (remaining == 1) {
            int chunk = (data[i] & 0xFF) << 16;
            sb.append(BASE64URL[(chunk >>> 18) & 0x3F]);
            sb.append(BASE64URL[(chunk >>> 12) & 0x3F]);
        } else if (remaining == 2) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(BASE64URL[(chunk >>> 18) & 0x3F]);
            sb.append(BASE64URL[(chunk >>> 12) & 0x3F]);
            sb.append(BASE64URL[(chunk >>> 6) & 0x3F]);
        }
        return sb.toString();
    }

    /**
     * 解码 base64url；同时接受带 {@code =} 的输入、标准 base64 的 {@code + /} 字符。
     */
    public static byte[] decodeBase64Url(String value) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        int length = value.length();
        int padding = 0;
        while (length > 0 && value.charAt(length - 1) == '=') {
            length--;
            padding++;
        }
        if (length == 0) {
            return new byte[0];
        }
        int outLength = (length * 6) / 8;
        byte[] out = new byte[outLength];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < length; i++) {
            int sextet = decodeSextet(value.charAt(i));
            if (sextet < 0) {
                throw new IllegalArgumentException("invalid base64url character: " + value.charAt(i));
            }
            buffer = (buffer << 6) | sextet;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (index < outLength) {
                    out[index++] = (byte) ((buffer >>> bits) & 0xFF);
                }
            }
        }
        return out;
    }

    private static int decodeSextet(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        switch (c) {
            case '-':
            case '+':
                return 62;
            case '_':
            case '/':
                return 63;
            default:
                return -1;
        }
    }

    /** 8 位随机十六进制（用于日志；实际 epoch 由安装标识派生）。 */
    static String hex32(int value) {
        char[] hex = new char[8];
        for (int i = 7; i >= 0; i--) {
            int digit = value & 0xF;
            hex[i] = (char) (digit < 10 ? '0' + digit : 'a' + digit - 10);
            value >>>= 4;
        }
        return new String(hex);
    }
}
