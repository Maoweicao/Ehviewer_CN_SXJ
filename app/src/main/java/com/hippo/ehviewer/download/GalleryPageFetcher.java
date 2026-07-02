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
import com.hippo.ehviewer.network.NetworkStateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
    private static final int PER_REQUEST_TIMEOUT_SECONDS = 15;
    private static final int MAX_RETRIES = 2;
    private static final long GRACEFUL_SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final Random JITTER_RANDOM = new Random();

    /** Per-request client with stricter timeout for batch fetch operations */
    private static volatile OkHttpClient sBatchClient;
    private static final Object sBatchClientLock = new Object();

    private static OkHttpClient getBatchClient(Context context) {
        if (sBatchClient == null) {
            synchronized (sBatchClientLock) {
                if (sBatchClient == null) {
                    sBatchClient = EhApplication.getOkHttpClient(context).newBuilder()
                            .callTimeout(PER_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return sBatchClient;
    }

    /**
     * Fetch page count for a single DownloadInfo from the network.
     *
     * @param context Application context
     * @param info    DownloadInfo with gid and token
     * @return page count, or -1 on failure, -2 if gallery removed
     */
    public static int fetchPages(Context context, DownloadInfo info) {
        return fetchPagesInternal(context, info, MAX_RETRIES);
    }

    private static int fetchPagesInternal(Context context, DownloadInfo info, int retriesLeft) {
        try {
            String url = EhUrl.getGalleryDetailUrl(info.gid, info.token);
            OkHttpClient okHttpClient = getBatchClient(context);
            GalleryDetail detail = EhEngine.getGalleryDetail(null, okHttpClient, url);

            if (detail != null && detail.pages > 0) {
                Log.d(TAG, "Fetched " + detail.pages + " pages for GID=" + info.gid);
                return detail.pages;
            }
        } catch (Throwable e) {
            if (isGalleryGone(e)) {
                Log.i(TAG, "Gallery GID=" + info.gid + " has been removed (404/Gone)");
                return -2;
            }
            if (retriesLeft > 0 && isTransientError(e)) {
                long delay = calculateBackoff(MAX_RETRIES - retriesLeft);
                Log.w(TAG, "Retry fetching pages for GID=" + info.gid
                        + " after " + delay + "ms, retries left: " + (retriesLeft - 1));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                return fetchPagesInternal(context, info, retriesLeft - 1);
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
     * Check if the exception is a transient network error worth retrying.
     */
    private static boolean isTransientError(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("timeout")
                || msg.contains("SocketTimeoutException")
                || msg.contains("StreamResetException")
                || msg.contains("stream was reset")
                || msg.contains("Canceled")
                || msg.contains("Connection")
                || msg.contains("ConnectException")
                || msg.contains("InterruptedIOException");
    }

    /**
     * Calculate exponential backoff with jitter.
     * Attempt 0: ~1s + jitter, Attempt 1: ~3s + jitter
     */
    private static long calculateBackoff(int attempt) {
        long base = (long) (1000 * Math.pow(3, attempt));
        long jitter = JITTER_RANDOM.nextLong() % 500;
        return Math.max(500, base + jitter);
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

        // Wait for all tasks with a capped timeout
        long totalTimeout = Math.min(
                (long) PER_REQUEST_TIMEOUT_SECONDS * needFetch.size() / concurrency + 10,
                300 // hard cap at 5 minutes
        );
        try {
            boolean completed = latch.await(totalTimeout, TimeUnit.SECONDS);
            if (!completed) {
                Log.w(TAG, "Page fetch batch timed out after " + totalTimeout + "s, "
                        + successCount.get() + "/" + needFetch.size() + " succeeded so far");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Page fetch batch interrupted");
        } finally {
            // Graceful shutdown: try to let in-flight requests finish
            executor.shutdown();
            try {
                if (!executor.awaitTermination(GRACEFUL_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        Log.i(TAG, "Page fetch batch complete: " + successCount.get()
                + "/" + needFetch.size() + " succeeded");
        return successCount.get();
    }
}
