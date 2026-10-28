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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * 协议 §5.28 数据变更总线单元测试（纯 JVM）。
 */
public class DataChangeBusTest {

    @Test
    public void publishIncrementsVersionAndBroadcasts() {
        long base = DataChangeBus.getVersion();
        AtomicReference<String> type = new AtomicReference<>();
        AtomicReference<String> data = new AtomicReference<>();
        DataChangeBus.Listener listener = (t, d) -> {
            type.set(t);
            data.set(d);
        };
        DataChangeBus.addListener(listener);
        try {
            long version = DataChangeBus.publish(
                    DataChangeBus.TYPE_FAVORITE_ADDED, 123L, null, null);

            assertEquals(base + 1, version);
            assertEquals(DataChangeBus.TYPE_FAVORITE_ADDED, type.get());
            assertTrue(data.get().contains("\"version\":" + version));
            assertTrue(data.get().contains("\"gid\":123"));
            assertFalse(data.get().contains("signedIn"));
            assertFalse(data.get().contains("state"));
        } finally {
            DataChangeBus.removeListener(listener);
        }
    }

    @Test
    public void downloadChangedCarriesState() {
        AtomicReference<String> data = new AtomicReference<>();
        DataChangeBus.Listener listener = (t, d) -> data.set(d);
        DataChangeBus.addListener(listener);
        try {
            DataChangeBus.publish(DataChangeBus.TYPE_DOWNLOAD_CHANGED, 42L, 2, null);
            assertTrue(data.get().contains("\"gid\":42"));
            assertTrue(data.get().contains("\"state\":2"));
        } finally {
            DataChangeBus.removeListener(listener);
        }
    }

    @Test
    public void loginChangedCarriesSignedInFlag() {
        AtomicReference<String> data = new AtomicReference<>();
        DataChangeBus.Listener listener = (t, d) -> data.set(d);
        DataChangeBus.addListener(listener);
        try {
            DataChangeBus.publish(DataChangeBus.TYPE_LOGIN_CHANGED, 0L, null, Boolean.TRUE);
            assertTrue(data.get().contains("\"signedIn\":true"));
            assertFalse(data.get().contains("\"gid\""));
        } finally {
            DataChangeBus.removeListener(listener);
        }
    }

    @Test
    public void removedListenerNoLongerReceives() {
        AtomicInteger count = new AtomicInteger();
        DataChangeBus.Listener listener = (t, d) -> count.incrementAndGet();
        DataChangeBus.addListener(listener);
        try {
            DataChangeBus.publish(DataChangeBus.TYPE_DATA_CHANGED);
            assertEquals(1, count.get());
            DataChangeBus.removeListener(listener);
            DataChangeBus.publish(DataChangeBus.TYPE_DATA_CHANGED);
            assertEquals(1, count.get());
        } finally {
            DataChangeBus.removeListener(listener);
        }
    }

    @Test
    public void listenerExceptionDoesNotBlockOthers() {
        AtomicInteger good = new AtomicInteger();
        DataChangeBus.Listener bad = (t, d) -> {
            throw new RuntimeException("boom");
        };
        DataChangeBus.Listener ok = (t, d) -> good.incrementAndGet();
        DataChangeBus.addListener(bad);
        DataChangeBus.addListener(ok);
        try {
            DataChangeBus.publish(DataChangeBus.TYPE_DATA_CHANGED);
            assertEquals(1, good.get());
        } finally {
            DataChangeBus.removeListener(bad);
            DataChangeBus.removeListener(ok);
        }
    }
}
