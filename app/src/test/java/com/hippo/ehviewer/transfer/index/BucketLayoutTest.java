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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * 协议 §5.29.1 的分区/编码示例校验。
 */
public class BucketLayoutTest {

    @Test
    public void bucketMapping() {
        assertEquals(5800, BucketLayout.bucketOf(2969601L, 512));
        assertEquals(2969600L, BucketLayout.bucketFrom(5800, 512));
        assertEquals(2970111L, BucketLayout.bucketTo(5800, 512));

        assertEquals(-1, BucketLayout.bucketOf(0L, 512));
        assertEquals(-1, BucketLayout.bucketOf(-1L, 512));

        assertTrue(BucketLayout.isBucketInRange(0, 512));
        assertTrue(BucketLayout.isBucketInRange(5800, 512));
        assertFalse(BucketLayout.isBucketInRange(-1, 512));
        assertFalse(BucketLayout.isBucketInRange(4_194_304, 512));

        for (int size : BucketLayout.SUPPORTED_BUCKET_SIZES) {
            assertTrue(BucketLayout.isSupportedBucketSize(size));
            assertEquals(1L, BucketLayout.bucketOf(size + 1L, size));
        }
        assertFalse(BucketLayout.isSupportedBucketSize(300));
    }

    @Test
    public void fnv1a32KnownVectors() {
        assertEquals("4f9f2cab", BucketLayout.fnv1a32("hello"));
        assertEquals("811c9dc5", BucketLayout.fnv1a32(""));
    }

    @Test
    public void bitmapMatchesProtocolExamples() {
        Set<Integer> presence = new TreeSet<>(Arrays.asList(5800, 5801, 5803));
        Set<Integer> changed = new TreeSet<>(Arrays.asList(5800, 5803));

        assertEquals("Cw", BucketLayout.bitmap(5800, 4, presence));
        assertEquals("CQ", BucketLayout.bitmap(5800, 4, changed));

        assertEquals(new TreeSet<>(presence),
                BucketLayout.decodeBitmap("Cw", 5800, 4));
        assertEquals(new TreeSet<>(changed),
                BucketLayout.decodeBitmap("CQ==", 5800, 4));
    }

    @Test
    public void bitmapSpansMultipleBytes() {
        Set<Integer> buckets = new TreeSet<>(Arrays.asList(0, 8));
        assertEquals("AQE", BucketLayout.bitmap(0, 9, buckets));
        assertEquals(new TreeSet<>(buckets), BucketLayout.decodeBitmap("AQE", 0, 9));
    }

    @Test
    public void emptyBitmapForEmptyRange() {
        assertEquals("", BucketLayout.bitmap(0, 0, new TreeSet<>()));
        assertTrue(BucketLayout.decodeBitmap("", 0, 0).isEmpty());
    }

    @Test
    public void countsMatchProtocolExample() {
        String encoded = BucketLayout.counts(new int[]{3, 1, 0, 2});
        assertEquals("AwABAAAAAgA", encoded);
        assertArrayEquals(new byte[]{0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00},
                BucketLayout.decodeBase64Url(encoded));
    }

    @Test
    public void base64UrlAcceptsPaddingAndUnknownBits() {
        assertArrayEquals(new byte[]{0x0b}, BucketLayout.decodeBase64Url("Cw"));
        assertArrayEquals(new byte[]{0x0b}, BucketLayout.decodeBase64Url("Cw=="));
        assertEquals("Cw", BucketLayout.encodeBase64Url(new byte[]{0x0b}));
        assertEquals("", BucketLayout.encodeBase64Url(new byte[0]));
    }
}
