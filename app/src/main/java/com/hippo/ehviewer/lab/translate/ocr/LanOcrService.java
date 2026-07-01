package com.hippo.ehviewer.lab.translate.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.translate.model.TranslateResult;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LanOcrService implements OcrService {
    private static final String TAG = "LanOcrService";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private boolean initialized = false;
    private final ExecutorService executor;
    private final OkHttpClient client;
    private String ocrServerUrl;

    public LanOcrService() {
        executor = Executors.newSingleThreadExecutor();
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void recognize(Bitmap image, OcrCallback callback) {
        if (!initialized) {
            callback.onError("LAN OCR not initialized");
            return;
        }

        String url = ocrServerUrl;
        if (url == null || url.isEmpty()) {
            callback.onError("OCR server URL not configured");
            return;
        }

        executor.execute(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                image.compress(Bitmap.CompressFormat.PNG, 100, baos);
                byte[] imageBytes = baos.toByteArray();
                String base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP);

                JSONObject requestBody = new JSONObject();
                requestBody.put("image_base64", base64Image);

                RequestBody body = RequestBody.create(JSON, requestBody.toJSONString());
                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        JSONObject jsonResponse = JSONObject.parseObject(responseBody);
                        List<TranslateResult.TextRegion> regions = parseOcrResponse(jsonResponse);
                        callback.onSuccess(regions);
                    } else {
                        callback.onError("OCR error HTTP " + response.code());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "LAN OCR failed", e);
                callback.onError(e.getMessage());
            }
        });
    }

    private List<TranslateResult.TextRegion> parseOcrResponse(JSONObject response) {
        List<TranslateResult.TextRegion> regions = new ArrayList<>();
        try {
            JSONArray boxes = response.getJSONArray("boxes");
            if (boxes != null) {
                for (int i = 0; i < boxes.size(); i++) {
                    JSONObject box = boxes.getJSONObject(i);
                    int x = box.getIntValue("x");
                    int y = box.getIntValue("y");
                    int w = box.getIntValue("w");
                    int h = box.getIntValue("h");
                    String text = box.getString("text");
                    float confidence = box.getFloatValue("confidence");

                    Rect rect = new Rect(x, y, x + w, y + h);
                    TranslateResult.TextRegion region = new TranslateResult.TextRegion(rect, text, "");
                    region.confidence = confidence;
                    regions.add(region);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse OCR response", e);
        }
        return regions;
    }

    public boolean healthCheck() {
        if (ocrServerUrl == null || ocrServerUrl.isEmpty()) return false;
        String healthUrl = ocrServerUrl.replace("/ocr", "/health");
        try {
            Request request = new Request.Builder()
                    .url(healthUrl)
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            Log.w(TAG, "Health check failed", e);
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        String url = Settings.getAiOcrLanUrl();
        return url != null && !url.isEmpty();
    }

    @Override
    public String getEngineName() {
        return "LAN PaddleOCR";
    }

    @Override
    public void initialize() {
        ocrServerUrl = Settings.getAiOcrLanUrl();
        if (ocrServerUrl != null && !ocrServerUrl.isEmpty()) {
            if (!ocrServerUrl.endsWith("/ocr")) {
                if (!ocrServerUrl.endsWith("/")) {
                    ocrServerUrl += "/";
                }
                ocrServerUrl += "ocr";
            }
            initialized = true;
            Log.d(TAG, "LAN OCR initialized: " + ocrServerUrl);
        } else {
            Log.w(TAG, "LAN OCR URL not configured");
        }
    }

    @Override
    public void release() {
        initialized = false;
        executor.shutdown();
    }
}
