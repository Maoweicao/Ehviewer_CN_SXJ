package com.hippo.ehviewer.ui.local;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地画廊缩略图/预览图的异步加载器。
 *
 * <p>旧实现在 RecyclerView 的 bind 里直接 {@code BitmapFactory.decodeFile} 全尺寸解码，
 * 本地画廊列表一滚动就会在主线程做磁盘 I/O + 大图解码，是"打开本地画廊就卡/ANR"
 * 的主要原因。这里统一改为：
 * - 后台线程降采样解码（长边不超过 {@link #MAX_DECODE_SIZE}）；
 * - 小容量 LruCache，重复滚动不重复解码；
 * - 结果回主线程，由调用方校验 holder 是否已被复用。
 */
public final class LocalThumbnailLoader {

    private static final String TAG = "LocalThumbnailLoader";

    /** 缩略图长边上限，足够卡片/预览格显示，又不会解出整张大图。 */
    private static final int MAX_DECODE_SIZE = 512;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "LocalThumb");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 约 16MB 的缩略图缓存（按 bitmap 字节数计）。 */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getAllocationByteCount();
        }
    };

    public interface Callback {
        void onLoaded(@Nullable Bitmap bitmap);
    }

    private LocalThumbnailLoader() {
    }

    /**
     * 加载画廊卡片缩略图：优先使用已生成的缩略图文件，否则取目录内第一张图片。
     *
     * @param thumbPath  缩略图路径，可为空
     * @param galleryDir 画廊目录，可为空
     */
    public static void loadThumbnail(@Nullable String thumbPath,
                                     @Nullable String galleryDir,
                                     @NonNull Callback callback) {
        final String cacheKey = "thumb:" + (thumbPath != null ? thumbPath : "") + "|"
                + (galleryDir != null ? galleryDir : "");
        Bitmap cached = CACHE.get(cacheKey);
        if (cached != null) {
            callback.onLoaded(cached);
            return;
        }
        EXECUTOR.execute(() -> {
            Bitmap bitmap = decodeThumbnail(thumbPath, galleryDir);
            if (bitmap != null) {
                CACHE.put(cacheKey, bitmap);
            }
            MAIN.post(() -> callback.onLoaded(bitmap));
        });
    }

    /** 加载单张图片文件（预览网格用）。 */
    public static void loadImage(@Nullable String imagePath, @NonNull Callback callback) {
        if (imagePath == null || imagePath.isEmpty()) {
            callback.onLoaded(null);
            return;
        }
        String cacheKey = "img:" + imagePath;
        Bitmap cached = CACHE.get(cacheKey);
        if (cached != null) {
            callback.onLoaded(cached);
            return;
        }
        EXECUTOR.execute(() -> {
            Bitmap bitmap = decodeSampled(new File(imagePath), MAX_DECODE_SIZE);
            if (bitmap != null) {
                CACHE.put(cacheKey, bitmap);
            }
            MAIN.post(() -> callback.onLoaded(bitmap));
        });
    }

    @Nullable
    private static Bitmap decodeThumbnail(@Nullable String thumbPath, @Nullable String galleryDir) {
        if (thumbPath != null && !thumbPath.isEmpty()) {
            File thumbFile = new File(thumbPath);
            if (thumbFile.isFile()) {
                Bitmap bitmap = decodeSampled(thumbFile, MAX_DECODE_SIZE);
                if (bitmap != null) {
                    return bitmap;
                }
            }
        }
        if (galleryDir == null || galleryDir.isEmpty()) {
            return null;
        }
        File galleryDirFile = new File(galleryDir);
        if (!galleryDirFile.isDirectory()) {
            return null;
        }
        File[] files = galleryDirFile.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (file.isFile() && isImageFile(file.getName())) {
                Bitmap bitmap = decodeSampled(file, MAX_DECODE_SIZE);
                if (bitmap != null) {
                    return bitmap;
                }
            }
        }
        return null;
    }

    @Nullable
    private static Bitmap decodeSampled(@NonNull File file, int maxSize) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSize);
            // 缩略图不需要透明通道，RGB_565 省一半内存
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int calculateInSampleSize(int width, int height, int maxSize) {
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / sample > maxSize) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    private static boolean isImageFile(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif")
                || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    public static void clearCache() {
        CACHE.evictAll();
    }

    @NonNull
    public static String tag() {
        return TAG;
    }
}
