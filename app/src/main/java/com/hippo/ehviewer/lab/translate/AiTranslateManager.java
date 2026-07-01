package com.hippo.ehviewer.lab.translate;

import android.graphics.Bitmap;
import android.util.Log;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.translate.cache.TranslateCache;
import com.hippo.ehviewer.lab.translate.model.TranslateResult;
import com.hippo.ehviewer.lab.translate.ocr.LanOcrService;
import com.hippo.ehviewer.lab.translate.ocr.OcrService;
import com.hippo.ehviewer.lab.translate.translate.OpenAiTranslator;
import com.hippo.ehviewer.lab.translate.translate.TranslateService;

import java.util.List;

public class AiTranslateManager {
    private static final String TAG = "AiTranslateManager";
    private static AiTranslateManager instance;

    private OcrService ocrService;
    private TranslateService translateService;
    private TranslateCache cache;
    private boolean initialized = false;

    public interface TranslateCallback {
        void onSuccess(String translated);
        void onError(String error);
    }

    public interface FullTranslateCallback {
        void onSuccess(TranslateResult result);
        void onProgress(int completed, int total);
        void onError(String error);
    }

    private AiTranslateManager() {
        cache = new TranslateCache();
    }

    public static synchronized AiTranslateManager getInstance() {
        if (instance == null) {
            instance = new AiTranslateManager();
        }
        return instance;
    }

    public void initialize() {
        if (initialized) {
            // Re-init services in case settings changed
            releaseServices();
        }

        ocrService = new LanOcrService();
        ocrService.initialize();

        translateService = new OpenAiTranslator();
        translateService.initialize();

        initialized = true;
        Log.d(TAG, "AiTranslateManager initialized with LAN OCR + " + translateService.getProviderName());
    }

    private void releaseServices() {
        if (ocrService != null) {
            ocrService.release();
            ocrService = null;
        }
        if (translateService != null) {
            translateService.release();
            translateService = null;
        }
    }

    public void release() {
        releaseServices();
        initialized = false;
    }

    public boolean isEnabled() {
        return Settings.getAiTranslateEnabled() && Settings.getLabEnabled();
    }

    public boolean isAvailable() {
        return initialized && ocrService != null && translateService != null;
    }

    public boolean isAnimatedPage(int galleryId, int pageIndex, boolean isAnimated) {
        if (isAnimated && Settings.getAiTranslateSkipAnimated()) {
            return true;
        }
        return false;
    }

    public void translatePage(int galleryId, int pageIndex, Bitmap image,
                              String targetLang, FullTranslateCallback callback) {
        if (!isEnabled()) {
            callback.onError("AI translation not enabled");
            return;
        }

        if (!initialized) {
            initialize();
        }

        if (Settings.getAiTranslateCacheEnabled()) {
            TranslateResult cached = cache.get(galleryId, pageIndex);
            if (cached != null) {
                Log.d(TAG, "Cache hit for gallery " + galleryId + " page " + pageIndex);
                callback.onSuccess(cached);
                return;
            }
        }

        if (ocrService == null) {
            callback.onError("OCR service not available");
            return;
        }

        if (!ocrService.isAvailable()) {
            callback.onError("OCR server URL not configured. Please set LAN OCR address in settings.");
            return;
        }

        Bitmap processedImage = image;
        if (Settings.getAiTranslateAutoDetect() && image != null) {
            ImageDetectHelper.DetectResult detectResult = ImageDetectHelper.detect(image);
            if (!detectResult.hasText) {
                Log.d(TAG, "No text detected in image, skipping translation");
                TranslateResult emptyResult = new TranslateResult(pageIndex);
                emptyResult.ocrEngine = ocrService.getEngineName();
                callback.onSuccess(emptyResult);
                return;
            }
        }

        ocrService.recognize(processedImage, new OcrService.OcrCallback() {
            @Override
            public void onSuccess(List<TranslateResult.TextRegion> regions) {
                if (regions.isEmpty()) {
                    TranslateResult emptyResult = new TranslateResult(pageIndex);
                    emptyResult.ocrEngine = ocrService.getEngineName();
                    callback.onSuccess(emptyResult);
                    return;
                }

                TranslateResult result = new TranslateResult(pageIndex);
                result.ocrEngine = ocrService.getEngineName();

                translateRegions(regions, targetLang, new TranslateRegionsCallback() {
                    @Override
                    public void onAllTranslated(List<TranslateResult.TextRegion> translatedRegions) {
                        result.regions = translatedRegions;

                        if (Settings.getAiTranslateCacheEnabled()) {
                            cache.put(galleryId, pageIndex, result);
                        }

                        callback.onSuccess(result);
                    }

                    @Override
                    public void onProgress(int completed, int total) {
                        callback.onProgress(completed, total);
                    }

                    @Override
                    public void onError(String error) {
                        callback.onError(error);
                    }
                });
            }

            @Override
            public void onError(String error) {
                callback.onError("OCR error: " + error);
            }
        });
    }

    private interface TranslateRegionsCallback {
        void onAllTranslated(List<TranslateResult.TextRegion> translatedRegions);
        void onProgress(int completed, int total);
        void onError(String error);
    }

    private void translateRegions(List<TranslateResult.TextRegion> regions, String targetLang,
                                  TranslateRegionsCallback callback) {
        if (translateService == null) {
            callback.onError("Translation service not available");
            return;
        }

        translateRegionBatch(regions, 0, targetLang, callback);
    }

    private void translateRegionBatch(List<TranslateResult.TextRegion> regions, int index,
                                      String targetLang, TranslateRegionsCallback callback) {
        if (index >= regions.size()) {
            callback.onAllTranslated(regions);
            return;
        }

        TranslateResult.TextRegion region = regions.get(index);
        translateService.translate(region.original, null, targetLang, new TranslateService.TranslateCallback() {
            @Override
            public void onSuccess(String translated) {
                region.translated = translated;
                region.targetLang = targetLang;
                callback.onProgress(index + 1, regions.size());
                translateRegionBatch(regions, index + 1, targetLang, callback);
            }

            @Override
            public void onError(String error) {
                region.translated = "[TL:" + error + "]";
                callback.onProgress(index + 1, regions.size());
                translateRegionBatch(regions, index + 1, targetLang, callback);
            }
        });
    }

    public void testTranslate(String text, TranslateCallback callback) {
        if (!initialized) {
            initialize();
        }

        if (translateService == null) {
            callback.onError("Translation service not available");
            return;
        }

        translateService.translate(text, null, "zh", new TranslateService.TranslateCallback() {
            @Override
            public void onSuccess(String translated) {
                callback.onSuccess(translated);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    public void testOcr(Bitmap testImage, OcrService.OcrCallback callback) {
        if (!initialized) {
            initialize();
        }

        if (ocrService == null) {
            callback.onError("OCR service not available");
            return;
        }

        ocrService.recognize(testImage, callback);
    }

    public void clearCache() {
        if (cache != null) {
            cache.clear();
        }
    }

    public OcrService getOcrService() {
        return ocrService;
    }

    public TranslateService getTranslateService() {
        return translateService;
    }
}
