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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 单元测试：{@link GalleryIndexStore} 的变更检测、墓碑与 epoch 语义（协议 §5.29.7）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class GalleryIndexStoreTest {

    private static final String SCOPE = "downloads|s:all";

    private Context context;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.application;
        context.getSharedPreferences("gallery_index", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("transfer_change_bus", Context.MODE_PRIVATE).edit().clear().commit();
        File state = new File(context.getFilesDir(), "gallery_index.json");
        if (state.exists()) {
            //noinspection ResultOfMethodCallIgnored
            state.delete();
        }
        resetSingleton();
    }

    @Test
    public void detectsChangesAndKeepsTombstones() {
        GalleryIndexStore store = GalleryIndexStore.get(context);
        String epoch = store.getEpoch();
        assertTrue(epoch.matches("[0-9a-f]{8}"));

        TreeMap<Integer, String> first = new TreeMap<>();
        first.put(5800, "fp-a");
        first.put(5801, "fp-b");
        GalleryIndexStore.ScopeUpdate initial = store.updateScope(SCOPE, first, store.getVersion());
        assertTrue(initial.globalVersion > 0);
        assertEquals(initial.globalVersion, initial.scopeVersion);
        assertEquals(new TreeSet<>(first.keySet()), initial.changedBuckets(0));

        // 内容未变：不推进版本
        GalleryIndexStore.ScopeUpdate unchanged = store.updateScope(SCOPE, first, store.getVersion());
        assertEquals(initial.globalVersion, unchanged.globalVersion);
        assertTrue(unchanged.changedBuckets(initial.globalVersion).isEmpty());
        assertTrue(unchanged.snapshotConsistent);

        // 删除一个桶：墓碑置位并推进版本
        TreeMap<Integer, String> afterDelete = new TreeMap<>();
        afterDelete.put(5800, "fp-a");
        GalleryIndexStore.ScopeUpdate deleted = store.updateScope(SCOPE, afterDelete, store.getVersion());
        assertTrue(deleted.globalVersion > initial.globalVersion);
        assertTrue(deleted.changedBuckets(initial.globalVersion).contains(5801));
        assertFalse(deleted.changedBuckets(initial.globalVersion).contains(5800));

        // 墓碑不会每次构建都重复置位
        GalleryIndexStore.ScopeUpdate stable = store.updateScope(SCOPE, afterDelete, store.getVersion());
        assertEquals(deleted.globalVersion, stable.globalVersion);
        assertTrue(stable.changedBuckets(deleted.globalVersion).isEmpty());

        // 桶重新出现：再次置位
        TreeMap<Integer, String> restored = new TreeMap<>(afterDelete);
        restored.put(5801, "fp-b2");
        GalleryIndexStore.ScopeUpdate again = store.updateScope(SCOPE, restored, store.getVersion());
        assertTrue(again.changedBuckets(deleted.globalVersion).contains(5801));
    }

    @Test
    public void rotateEpochClearsScopesAndPersists() throws Exception {
        GalleryIndexStore store = GalleryIndexStore.get(context);
        TreeMap<Integer, String> fps = new TreeMap<>();
        fps.put(10, "x");
        store.updateScope(SCOPE, fps, store.getVersion());
        assertTrue(store.getScopeVersion(SCOPE) > 0);

        String previousEpoch = store.getEpoch();
        String rotated = store.rotateEpoch();
        assertNotEquals(previousEpoch, rotated);
        assertEquals(0L, store.getScopeVersion(SCOPE));

        // 重新加载单例：epoch 与清空后的状态都应持久化
        resetSingleton();
        GalleryIndexStore reloaded = GalleryIndexStore.get(context);
        assertEquals(rotated, reloaded.getEpoch());
        assertEquals(0L, reloaded.getScopeVersion(SCOPE));
    }

    @Test
    public void refreshBucketBumpsVersionOnDrift() {
        GalleryIndexStore store = GalleryIndexStore.get(context);
        long version = store.refreshBucket(SCOPE, 5800, "fp-1");
        assertTrue(version > 0);
        assertEquals(version, store.refreshBucket(SCOPE, 5800, "fp-1"));
        long changed = store.refreshBucket(SCOPE, 5800, "fp-2");
        assertTrue(changed > version);
    }

    private static void resetSingleton() throws Exception {
        Field instance = GalleryIndexStore.class.getDeclaredField("sInstance");
        instance.setAccessible(true);
        instance.set(null, null);
        Field initialized = com.hippo.ehviewer.transfer.core.DataChangeBus.class
                .getDeclaredField("sInitialized");
        initialized.setAccessible(true);
        initialized.set(null, false);
    }
}
