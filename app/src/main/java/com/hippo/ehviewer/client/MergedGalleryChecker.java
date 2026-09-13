/*
 * Copyright 2026 Hippo Seven
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

package com.hippo.ehviewer.client;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.scene.Announcer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class MergedGalleryChecker {

    public static class MergedTarget {
        public final long gid;
        public final String token;

        public MergedTarget(long gid, String token) {
            this.gid = gid;
            this.token = token;
        }
    }

    public interface Callback {
        /**
         * Called on main thread. When {@code target == null}, the gallery is not merged
         * (or the merged info can not be fetched).
         */
        void onResult(@Nullable MergedTarget target);
    }

    private static final int MAX_CACHE_SIZE = 64;

    private static final Object sCacheLock = new Object();
    private static final Map<Long, MergedTarget> sCache = new LinkedHashMap<Long, MergedTarget>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, MergedTarget> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private MergedGalleryChecker() {
    }

    /**
     * Asynchronously check whether the given gallery has been merged. When the merged
     * parent (or the current gallery) is known, {@code gi} is updated accordingly.
     * The {@code callback} is always posted on the main thread.
     */
    public static void check(final Context context, final GalleryInfo gi, final Callback callback) {
        MergedTarget target;
        synchronized (sCacheLock) {
            target = sCache.get(gi.gid);
            if (sCache.containsKey(gi.gid)) {
                postResult(callback, target);
                return;
            }
        }

        MergedTarget known = targetFrom(gi);
        if (known != null) {
            // Already filled during a previous API call, no need to request again.
            synchronized (sCacheLock) {
                sCache.put(gi.gid, known);
            }
            postResult(callback, known);
            return;
        }

        EhApplication.getExecutorService(context).execute(() -> {
            try {
                EhEngine.fillGalleryListByApi(null, EhApplication.getOkHttpClient(context),
                        Collections.singletonList(gi), EhUrl.getReferer());
            } catch (Throwable e) {
                com.hippo.util.ExceptionUtils.throwIfFatal(e);
            }
            MergedTarget result = targetFrom(gi);
            synchronized (sCacheLock) {
                sCache.put(gi.gid, result);
            }
            postResult(callback, result);
        });
    }

    private static void postResult(Callback callback, @Nullable MergedTarget target) {
        SimpleHandler.getInstance().post(() -> callback.onResult(target));
    }

    /**
     * Resolve the merge target. The parent gallery wins over the current gallery, and the
     * target must differ from the source gallery and carry a valid token.
     */
    @Nullable
    public static MergedTarget targetFrom(GalleryInfo gi) {
        long gid = -1;
        String token = null;
        if (gi.parentGid > 0 && !TextUtils.isEmpty(gi.parentKey)) {
            gid = gi.parentGid;
            token = gi.parentKey;
        } else if (gi.currentGid > 0 && !TextUtils.isEmpty(gi.currentKey) && gi.currentGid != gi.gid) {
            gid = gi.currentGid;
            token = gi.currentKey;
        }
        if (gid <= 0 || gid == gi.gid || TextUtils.isEmpty(token)) {
            return null;
        }
        return new MergedTarget(gid, token);
    }

    public static Announcer createJumpAnnouncer(MergedTarget target) {
        Bundle args = new Bundle();
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GID_TOKEN);
        args.putLong(GalleryDetailScene.KEY_GID, target.gid);
        args.putString(GalleryDetailScene.KEY_TOKEN, target.token);
        return new Announcer(GalleryDetailScene.class).setArgs(args);
    }
}