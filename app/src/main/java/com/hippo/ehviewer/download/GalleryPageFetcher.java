/*
 * Copyright 2025 Hippo Seven
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

package com.hippo.ehviewer.download;

import android.content.Context;
import android.util.Log;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.dao.DownloadInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/**
 * Utility class for fetching gallery page counts from network
 * before download sorting, ensuring accurate queue ordering.
 */
public class GalleryPageFetcher {

    private static final String TAG = "GalleryPageFetcher";
    private static final int TIMEOUT_SECONDS = 10;
    private static final int MAX_RETRIES = 1;

    /**
     * Fetch page count for a single DownloadInfo from the network.
     *
     * @param context Application context
     * @param info    DownloadInfo with gid and token
     * @return page count, or -1 on failure
     */
    public static int fetchPages(Context context, DownloadInfo info) {
        return fetchPagesInternal(context, info, MAX_RETRIES);
    }

    private static int fetchPagesInternal(Context context, DownloadInfo info, int retriesLeft) {
        try {
            String url = EhUrl.getGalleryDetailUrl(info.gid, info.token);
            OkHttpClient okHttpClient = EhApplication.getOkHttpClient(context);
            GalleryDetail detail = EhEngine.getGalleryDetail(null, okHttpClient, url);

            if (detail != null && detail.pages > 0) {
                Log.d(TAG, "Fetched " + detail.pages + " pages for GID=" + info.gid);
                return detail.pages;
            }
        } catch (Throwable e) {
            if (retriesLeft > 0 && !isGalleryGone(e)) {
                Log.w(TAG, "Retry fetching pages for GID=" + info.gid + ", retries left: " + (retriesLeft - 1));
                return fetchPagesInternal(context, info, retriesLeft - 1);
            }
            if (isGalleryGone(e)) {
                Log.i(TAG, "Gallery GID=" + info.gid + " has been removed (404/Gone)");
                return -2; // -2 means gallery removed
            }
            Log.w(TAG, "Failed to fetch pages for GID=" + info.gid + ": " + e.getMessage());
        }
        return -1;
    }

    /**
     * Check if the exception indicates the gallery has been removed.
     */
    private static boolean isGalleryGone(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("Gallery Not Found")
                || msg.contains("Pining")
                || msg.contains("404")
                || msg.contains("GalleryUnavailable");
    }

    /**
     * Batch fetch page counts for multiple DownloadInfo objects with controlled concurrency.
     * Only fetches for items where total == 0 (unknown page count).
     * Writes fetched page counts back to DB via EhDB.putDownloadInfo.
     *
     * @param context     Application context
     * @param infos       List of DownloadInfo to check
     * @param concurrency Max simultaneous network requests
     * @return number of successfully fetched page counts
     */
    public static int fetchPagesBatch(Context context, List<DownloadInfo> infos, int concurrency) {
        // Filter items that need page count fetching
        List<DownloadInfo> needFetch = new ArrayList<>();
        for (DownloadInfo info : infos) {
            if (info.total <= 0) {
                needFetch.add(info);
            }
        }

        if (needFetch.isEmpty()) {
            Log.d(TAG, "No items need page count fetching");
            return 0;
        }

        if (concurrency <= 0) {
            concurrency = Settings.DEFAULT_DOWNLOAD_PREFETCH_PAGES_CONCURRENCY;
        }

        Log.i(TAG, "Batch fetching page counts for " + needFetch.size()
                + " items with concurrency=" + concurrency);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(needFetch.size());

        for (DownloadInfo info : needFetch) {
            executor.execute(() -> {
                try {
                    int pages = fetchPages(context, info);
                    if (pages > 0) {
                        info.total = pages;
                        EhDB.putDownloadInfo(info);
                        successCount.incrementAndGet();
                        Log.d(TAG, "Updated total=" + pages + " for GID=" + info.gid);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all tasks or timeout
        try {
            boolean completed = latch.await(TIMEOUT_SECONDS * needFetch.size() / concurrency + 10,
                    TimeUnit.SECONDS);
            if (!completed) {
                Log.w(TAG, "Page fetch batch timed out, some items may not have page counts");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Page fetch batch interrupted");
        } finally {
            executor.shutdownNow();
        }

        Log.i(TAG, "Page fetch batch complete: " + successCount.get()
                + "/" + needFetch.size() + " succeeded");
        return successCount.get();
    }
}
