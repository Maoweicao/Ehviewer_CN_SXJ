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

package com.hippo.ehviewer.lab.analyze;

import android.graphics.Bitmap;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.translate.TranslatePresets;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 基于 OpenAI 兼容 /chat/completions 接口的多模态图片分析客户端。
 * 复用 AI 翻译的 provider / API 地址 / API Key，使用独立的分析模型。
 */
public class OpenAiImageAnalyzer {
    private static final String TAG = "OpenAiImageAnalyzer";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ExecutorService executor;
    private final OkHttpClient client;

    public OpenAiImageAnalyzer() {
        executor = Executors.newSingleThreadExecutor();
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(300, TimeUnit.SECONDS)
                .build();
    }

    public interface AnalyzeCallback {
        void onSuccess(JSONObject result);
        void onError(String error);
    }

    /**
     * 纯文本请求（用于画廊整体汇总，不需要图片）
     */
    public void analyzeText(String prompt, String responseFormatJson, AnalyzeCallback callback) {
        String provider = Settings.getAiTranslateProvider();
        String apiUrl = getApiUrl(provider);
        if (apiUrl == null || apiUrl.isEmpty()) {
            callback.onError("API URL not configured. Please set provider or custom URL.");
            return;
        }

        String apiKey = Settings.getAiTranslateApiKey();
        if (TranslatePresets.needsApiKey(provider) && (apiKey == null || apiKey.isEmpty())) {
            callback.onError("API Key not configured for " + provider);
            return;
        }

        executor.execute(() -> {
            try {
                String model = getModel(provider);
                String finalApiKey = apiKey != null && !apiKey.isEmpty() ? apiKey : "";

                JSONArray messages = new JSONArray();

                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", "You are a professional image analyst for manga/comic/gallery pages. " +
                        "Output the result as valid JSON only, without any extra text, markdown fences or explanations. " +
                        (responseFormatJson != null && !responseFormatJson.isEmpty()
                                ? "Expected JSON format: " + responseFormatJson : ""));
                messages.add(systemMsg);

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", prompt);
                messages.add(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", model);
                requestBody.put("messages", messages);
                requestBody.put("temperature", 0.2f);
                requestBody.put("max_tokens", 2000);

                RequestBody body = RequestBody.create(JSON, requestBody.toJSONString());
                Request.Builder reqBuilder = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("Content-Type", "application/json")
                        .post(body);

                if (!finalApiKey.isEmpty()) {
                    reqBuilder.addHeader("Authorization", "Bearer " + finalApiKey);
                }

                try (Response response = client.newCall(reqBuilder.build()).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        JSONObject jsonResponse = JSONObject.parseObject(responseBody);
                        JSONObject result = extractResult(jsonResponse);
                        if (result != null) {
                            callback.onSuccess(result);
                        } else {
                            callback.onError("Failed to parse analysis result");
                        }
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "";
                        callback.onError("API error " + response.code() + ": " + errorBody);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Image analysis failed", e);
                callback.onError(e.getMessage());
            }
        });
    }

    public void analyze(Bitmap bitmap, String prompt, String responseFormatJson, AnalyzeCallback callback) {
        if (bitmap == null) {
            callback.onError("Empty image");
            return;
        }

        String provider = Settings.getAiTranslateProvider();
        String apiUrl = getApiUrl(provider);
        if (apiUrl == null || apiUrl.isEmpty()) {
            callback.onError("API URL not configured. Please set provider or custom URL.");
            return;
        }

        String apiKey = Settings.getAiTranslateApiKey();
        if (TranslatePresets.needsApiKey(provider) && (apiKey == null || apiKey.isEmpty())) {
            callback.onError("API Key not configured for " + provider);
            return;
        }

        String base64Image = bitmapToBase64(bitmap);
        if (base64Image == null) {
            callback.onError("Failed to encode image");
            return;
        }

        executor.execute(() -> {
            try {
                String model = getModel(provider);
                String finalApiKey = apiKey != null && !apiKey.isEmpty() ? apiKey : "";

                JSONArray messages = new JSONArray();

                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", "You are a professional image analyst for manga/comic/gallery pages. " +
                        "Analyze the given image carefully and output the result as valid JSON only, " +
                        "without any extra text, markdown fences or explanations. " +
                        (responseFormatJson != null && !responseFormatJson.isEmpty()
                                ? "Expected JSON format: " + responseFormatJson : ""));
                messages.add(systemMsg);

                JSONObject userMsg = new JSONObject();
                JSONArray contentArray = new JSONArray();

                JSONObject textPart = new JSONObject();
                textPart.put("type", "text");
                textPart.put("text", prompt);
                contentArray.add(textPart);

                JSONObject imagePart = new JSONObject();
                imagePart.put("type", "image_url");
                JSONObject imageUrl = new JSONObject();
                imageUrl.put("url", "data:image/jpeg;base64," + base64Image);
                imagePart.put("image_url", imageUrl);
                contentArray.add(imagePart);

                userMsg.put("role", "user");
                userMsg.put("content", contentArray);
                messages.add(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", model);
                requestBody.put("messages", messages);
                requestBody.put("temperature", 0.2f);
                requestBody.put("max_tokens", 2000);

                RequestBody body = RequestBody.create(JSON, requestBody.toJSONString());
                Request.Builder reqBuilder = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("Content-Type", "application/json")
                        .post(body);

                if (!finalApiKey.isEmpty()) {
                    reqBuilder.addHeader("Authorization", "Bearer " + finalApiKey);
                }

                try (Response response = client.newCall(reqBuilder.build()).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        JSONObject jsonResponse = JSONObject.parseObject(responseBody);
                        JSONObject result = extractResult(jsonResponse);
                        if (result != null) {
                            callback.onSuccess(result);
                        } else {
                            callback.onError("Failed to parse analysis result");
                        }
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "";
                        callback.onError("API error " + response.code() + ": " + errorBody);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Image analysis failed", e);
                callback.onError(e.getMessage());
            }
        });
    }

    /**
     * 将 Bitmap 缩放到最大边不超过 maxDimension 并编码为 JPEG Base64
     */
    @androidx.annotation.Nullable
    private String bitmapToBase64(Bitmap bitmap) {
        try {
            Bitmap scaled = bitmap;
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int maxDimension = Settings.getAiAnalyzeMaxDimension();
            if (maxDimension > 0 && (w > maxDimension || h > maxDimension)) {
                float scale = Math.min((float) maxDimension / w, (float) maxDimension / h);
                Bitmap resized = Bitmap.createScaledBitmap(bitmap,
                        (int) (w * scale), (int) (h * scale), true);
                if (resized != bitmap) {
                    scaled = resized;
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            if (scaled != bitmap) {
                scaled.recycle();
            }
            return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "bitmapToBase64 failed", e);
            return null;
        }
    }

    private String getApiUrl(String provider) {
        String customUrl = Settings.getAiTranslateApiUrl();
        if (customUrl != null && !customUrl.isEmpty()) {
            return customUrl;
        }

        String baseUrl = TranslatePresets.getDefaultBaseUrl(provider);
        if (baseUrl != null && !baseUrl.isEmpty()) {
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            return baseUrl + "/chat/completions";
        }

        return null;
    }

    private String getModel(String provider) {
        String model = Settings.getAiAnalyzeModel();
        if (model != null && !model.isEmpty()) {
            return model;
        }

        // 回退到翻译模型
        model = Settings.getAiTranslateModel();
        if (model != null && !model.isEmpty()) {
            return model;
        }

        TranslatePresets.ProviderPreset preset = TranslatePresets.getPreset(provider);
        if (preset != null && preset.defaultModel != null && !preset.defaultModel.isEmpty()) {
            return preset.defaultModel;
        }

        return "gpt-4o-mini";
    }

    /**
     * 从 chat completions 响应中提取 message.content，并尝试解析为 JSON 对象
     */
    @androidx.annotation.Nullable
    private JSONObject extractResult(JSONObject response) {
        try {
            String content = response.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();

            if (content == null || content.isEmpty()) {
                return null;
            }

            // 去除可能包裹的 ```json ... ``` 代码块
            if (content.startsWith("```")) {
                int firstNewLine = content.indexOf('\n');
                if (firstNewLine != -1) {
                    content = content.substring(firstNewLine + 1);
                }
                int lastFence = content.lastIndexOf("```");
                if (lastFence != -1) {
                    content = content.substring(0, lastFence);
                }
                content = content.trim();
            }

            return JSONObject.parseObject(content);
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract analysis result", e);
            return null;
        }
    }

    public void release() {
        executor.shutdown();
    }
}
