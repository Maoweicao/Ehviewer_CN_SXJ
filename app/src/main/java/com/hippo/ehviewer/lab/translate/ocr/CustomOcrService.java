package com.hippo.ehviewer.lab.translate.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.translate.model.TranslateResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CustomOcrService implements OcrService {
    private static final String TAG = "CustomOcrService";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private boolean initialized = false;
    private final ExecutorService executor;
    private final OkHttpClient client;

    public CustomOcrService() {
        executor = Executors.newSingleThreadExecutor();
        client = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void recognize(Bitmap image, OcrCallback callback) {
        if (!initialized) {
            callback.onError("Custom OCR not initialized");
            return;
        }

        String apiUrl = Settings.getAiOcrApiUrl();
        if (apiUrl == null || apiUrl.isEmpty()) {
            callback.onError("OCR API URL not configured");
            return;
        }

        executor.execute(() -> {
            try {
                // Convert bitmap to base64
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                image.compress(Bitmap.CompressFormat.PNG, 100, baos);
                byte[] imageBytes = baos.toByteArray();
                String base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.DEFAULT);

                // Create request body
                JSONObject requestBody = new JSONObject();
                requestBody.put("image", base64Image);
                requestBody.put("format", "png");

                RequestBody body = RequestBody.create(JSON, requestBody.toJSONString());
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .post(body)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        JSONObject jsonResponse = JSONObject.parseObject(responseBody);
                        
                        List<TranslateResult.TextRegion> regions = parseOcrResponse(jsonResponse);
                        callback.onSuccess(regions);
                    } else {
                        callback.onError("OCR API error: " + response.code());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Custom OCR failed", e);
                callback.onError(e.getMessage());
            }
        });
    }

    private List<TranslateResult.TextRegion> parseOcrResponse(JSONObject response) {
        List<TranslateResult.TextRegion> regions = new ArrayList<>();
        
        try {
            JSONArray results = response.getJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.size(); i++) {
                    JSONObject item = results.getJSONObject(i);
                    
                    String text = item.getString("text");
                    float confidence = item.getFloatValue("confidence");
                    
                    JSONObject box = item.getJSONObject("box");
                    int x = box.getIntValue("x");
                    int y = box.getIntValue("y");
                    int w = box.getIntValue("width");
                    int h = box.getIntValue("height");
                    
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

    @Override
    public boolean isAvailable() {
        String apiUrl = Settings.getAiOcrApiUrl();
        return apiUrl != null && !apiUrl.isEmpty();
    }

    @Override
    public String getEngineName() {
        return "Custom API";
    }

    @Override
    public void initialize() {
        String apiUrl = Settings.getAiOcrApiUrl();
        if (apiUrl != null && !apiUrl.isEmpty()) {
            initialized = true;
            Log.d(TAG, "Custom OCR initialized with URL: " + apiUrl);
        } else {
            Log.w(TAG, "Custom OCR API URL not configured");
        }
    }

    @Override
    public void release() {
        initialized = false;
        executor.shutdown();
    }
}
