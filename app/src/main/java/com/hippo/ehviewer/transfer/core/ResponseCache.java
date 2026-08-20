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

import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory response cache with TTL support.
 * Used to cache expensive API responses (gallery lists, integrity checks, etc.)
 */
public class ResponseCache {

    private static final String TAG = "ResponseCache";
    private static final long DEFAULT_TTL_MS = 5 * 60 * 60 * 1000; // 5 hours

    private static volatile ResponseCache instance;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private ResponseCache() {}

    public static ResponseCache getInstance() {
        if (instance == null) {
            synchronized (ResponseCache.class) {
                if (instance == null) {
                    instance = new ResponseCache();
                }
            }
        }
        return instance;
    }

    /**
     * Get cached response if valid
     * @param key Cache key
     * @return Cached JSON string, or null if expired/missing
     */
    public String get(String key) {
        TransferLogger.getInstance().d(TAG, "读取缓存: key=" + key);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            TransferLogger.getInstance().d(TAG, "缓存未命中: key=" + key);
            return null;
        }

        if (System.currentTimeMillis() - entry.createdAt > entry.ttlMs) {
            cache.remove(key);
            TransferLogger.getInstance().d(TAG, "缓存已过期并移除: key=" + key);
            return null;
        }

        TransferLogger.getInstance().d(TAG, "缓存命中: key=" + key + ", 字节数=" + entry.json.length());
        return entry.json;
    }

    /**
     * Store response in cache
     * @param key Cache key
     * @param json Response JSON string
     */
    public void put(String key, String json) {
        put(key, json, DEFAULT_TTL_MS);
    }

    /**
     * Store response in cache with custom TTL
     * @param key Cache key
     * @param json Response JSON string
     * @param ttlMs TTL in milliseconds
     */
    public void put(String key, String json, long ttlMs) {
        cache.put(key, new CacheEntry(json, System.currentTimeMillis(), ttlMs));
        TransferLogger.getInstance().d(TAG, "写入缓存: key=" + key + ", 字节数=" + json.length() + ", ttlMs=" + ttlMs);
    }

    /**
     * Invalidate cache entries matching a prefix
     * @param prefix Key prefix to match
     */
    public void invalidateByPrefix(String prefix) {
        int before = cache.size();
        cache.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
        int removed = before - cache.size();
        if (removed > 0) {
            TransferLogger.getInstance().d(TAG, "按前缀失效缓存: prefix=" + prefix + ", 移除=" + removed + "条");
        }
    }

    /**
     * Invalidate a specific cache key
     * @param key Cache key
     */
    public void invalidate(String key) {
        cache.remove(key);
        TransferLogger.getInstance().d(TAG, "失效缓存: key=" + key);
    }

    /**
     * Invalidate all gallery-related caches.
     * Called when gallery count changes.
     */
    public void invalidateGalleries() {
        invalidateByPrefix("galleries:");
        invalidateByPrefix("gallery_detail:");
        invalidateByPrefix("labels");
        invalidateByPrefix("integrity:");
        invalidateByPrefix("system_stats");
        invalidateByPrefix("export_downloads");
        invalidateByPrefix("export_favorites");
        invalidateByPrefix("export_bookmarks");
    }

    /**
     * Invalidate all file-related caches.
     * Called when files change.
     */
    public void invalidateFiles() {
        invalidateByPrefix("files:");
        invalidateByPrefix("folders");
        invalidateByPrefix("integrity:");
        invalidateByPrefix("file_hash:");
        invalidateByPrefix("export_files");
    }

    /**
     * Invalidate settings-related caches.
     */
    public void invalidateSettings() {
        invalidateByPrefix("settings:");
        invalidateByPrefix("system_info");
    }

    /**
     * Invalidate label-related caches.
     */
    public void invalidateLabels() {
        invalidateByPrefix("labels");
    }

    /**
     * Invalidate task-related caches.
     */
    public void invalidateTasks() {
        invalidateByPrefix("tasks:");
        invalidateByPrefix("compress_tasks:");
    }

    /**
     * Clear all caches
     */
    public void clear() {
        cache.clear();
        TransferLogger.getInstance().d(TAG, "清空缓存");
    }

    /**
     * Get cache size
     */
    public int size() {
        return cache.size();
    }

    /**
     * Get a snapshot of all cache entries.
     * Expired entries are removed during iteration.
     *
     * @return List of cache entry snapshots
     */
    public List<CacheEntrySnapshot> getSnapshot() {
        List<CacheEntrySnapshot> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> now - entry.getValue().createdAt > entry.getValue().ttlMs);
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            CacheEntry e = entry.getValue();
            long remaining = Math.max(0, e.createdAt + e.ttlMs - now);
            list.add(new CacheEntrySnapshot(entry.getKey(), e.json.length(), e.createdAt, e.ttlMs, e.createdAt + e.ttlMs, remaining));
        }
        return list;
    }

    /**
     * Snapshot of a single cache entry.
     */
    public static class CacheEntrySnapshot {
        public final String key;
        public final int size;
        public final long createdAt;
        public final long ttlMs;
        public final long expiresAt;
        public final long remainingMs;

        CacheEntrySnapshot(String key, int size, long createdAt, long ttlMs, long expiresAt, long remainingMs) {
            this.key = key;
            this.size = size;
            this.createdAt = createdAt;
            this.ttlMs = ttlMs;
            this.expiresAt = expiresAt;
            this.remainingMs = remainingMs;
        }
    }

    /**
     * Build a cache key from path and parameters
     */
    public static String buildKey(String path, String... params) {
        StringBuilder sb = new StringBuilder(path);
        for (String param : params) {
            if (param != null && !param.isEmpty()) {
                sb.append(':').append(param);
            }
        }
        return sb.toString();
    }

    private static class CacheEntry {
        final String json;
        final long createdAt;
        final long ttlMs;

        CacheEntry(String json, long createdAt, long ttlMs) {
            this.json = json;
            this.createdAt = createdAt;
            this.ttlMs = ttlMs;
        }
    }
}
