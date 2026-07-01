package com.hippo.ehviewer.lab.translate.cache;

import android.util.Log;

import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.translate.model.TranslateResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class TranslateCache {
    private static final String TAG = "TranslateCache";
    private static final String CACHE_DIR = "ai_translate_cache";
    private static final String CACHE_FILE_NAME = ".aitranslate.json";
    
    private final ConcurrentHashMap<String, TranslateResult> memoryCache;
    private final ExecutorService executor;
    private File cacheDir;

    public TranslateCache() {
        memoryCache = new ConcurrentHashMap<>();
        executor = Executors.newSingleThreadExecutor();
        initCacheDir();
    }

    private void initCacheDir() {
        cacheDir = AppConfig.getDirInExternalAppDir(CACHE_DIR);
        if (cacheDir != null && !cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    public TranslateResult get(int galleryId, int pageIndex) {
        String key = generateKey(galleryId, pageIndex);

        // Check memory cache first
        TranslateResult result = memoryCache.get(key);
        if (result != null) {
            return result;
        }

        // Check file cache
        result = loadFromFile(galleryId, pageIndex);
        if (result != null) {
            // Check if expired
            if (!isExpired(result)) {
                memoryCache.put(key, result);
                return result;
            } else {
                // Remove expired cache
                remove(galleryId, pageIndex);
            }
        }

        return null;
    }

    public void put(int galleryId, int pageIndex, TranslateResult result) {
        if (result == null) return;

        String key = generateKey(galleryId, pageIndex);
        memoryCache.put(key, result);

        // Save to file asynchronously
        executor.execute(() -> saveToFile(galleryId, pageIndex, result));
    }

    public void remove(int galleryId, int pageIndex) {
        String key = generateKey(galleryId, pageIndex);
        memoryCache.remove(key);

        executor.execute(() -> deleteFile(galleryId, pageIndex));
    }

    public void clear() {
        memoryCache.clear();

        executor.execute(() -> {
            if (cacheDir != null && cacheDir.exists()) {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
            }
            Log.d(TAG, "Cache cleared");
        });
    }

    public void clearExpired() {
        executor.execute(() -> {
            if (cacheDir == null || !cacheDir.exists()) return;

            File[] files = cacheDir.listFiles();
            if (files == null) return;

            int removed = 0;
            for (File file : files) {
                try {
                    String content = readFile(file);
                    if (content != null) {
                        JSONObject json = JSONObject.parseObject(content);
                        TranslateResult result = TranslateResult.fromJson(json);
                        if (isExpired(result)) {
                            file.delete();
                            removed++;
                        }
                    }
                } catch (Exception e) {
                    // Remove invalid files
                    file.delete();
                    removed++;
                }
            }

            Log.d(TAG, "Cleared " + removed + " expired cache files");
        });
    }

    private boolean isExpired(TranslateResult result) {
        if (result == null) return true;

        int expireDays = Settings.getAiTranslateCacheExpire();
        if (expireDays <= 0) return false; // Never expire

        long expireMs = expireDays * 24L * 60L * 60L * 1000L;
        return (System.currentTimeMillis() - result.timestamp) > expireMs;
    }

    private String generateKey(int galleryId, int pageIndex) {
        return galleryId + "_" + pageIndex;
    }

    private String getCacheFilePath(int galleryId) {
        if (cacheDir == null) return null;
        return new File(cacheDir, galleryId + ".json").getAbsolutePath();
    }

    private TranslateResult loadFromFile(int galleryId, int pageIndex) {
        String filePath = getCacheFilePath(galleryId);
        if (filePath == null) return null;

        File file = new File(filePath);
        if (!file.exists()) return null;

        try {
            String content = readFile(file);
            if (content != null) {
                JSONObject json = JSONObject.parseObject(content);
                JSONObject pages = json.getJSONObject("pages");
                if (pages != null) {
                    JSONObject pageJson = pages.getJSONObject(String.valueOf(pageIndex));
                    if (pageJson != null) {
                        return TranslateResult.fromJson(pageJson);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load cache", e);
        }

        return null;
    }

    private void saveToFile(int galleryId, int pageIndex, TranslateResult result) {
        String filePath = getCacheFilePath(galleryId);
        if (filePath == null) return;

        try {
            File file = new File(filePath);
            JSONObject root;

            // Load existing file if exists
            if (file.exists()) {
                String content = readFile(file);
                root = content != null ? JSONObject.parseObject(content) : new JSONObject();
            } else {
                root = new JSONObject();
            }

            // Update root
            root.put("version", 1);
            root.put("galleryId", galleryId);

            // Update pages
            JSONObject pages = root.getJSONObject("pages");
            if (pages == null) {
                pages = new JSONObject();
                root.put("pages", pages);
            }

            pages.put(String.valueOf(pageIndex), result.toJson());

            // Write to file
            writeFile(file, root.toJSONString());

            Log.d(TAG, "Saved cache for gallery " + galleryId + " page " + pageIndex);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save cache", e);
        }
    }

    private void deleteFile(int galleryId, int pageIndex) {
        String filePath = getCacheFilePath(galleryId);
        if (filePath == null) return;

        try {
            File file = new File(filePath);
            if (file.exists()) {
                String content = readFile(file);
                if (content != null) {
                    JSONObject root = JSONObject.parseObject(content);
                    JSONObject pages = root.getJSONObject("pages");
                    if (pages != null) {
                        pages.remove(String.valueOf(pageIndex));
                        writeFile(file, root.toJSONString());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete cache entry", e);
        }
    }

    private String readFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return new String(data, "UTF-8");
        } catch (IOException e) {
            Log.e(TAG, "Failed to read file", e);
            return null;
        }
    }

    private void writeFile(File file, String content) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes("UTF-8"));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write file", e);
        }
    }
}
