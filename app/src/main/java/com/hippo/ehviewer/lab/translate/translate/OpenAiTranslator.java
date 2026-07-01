package com.hippo.ehviewer.lab.translate.translate;

import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.translate.TranslatePresets;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OpenAiTranslator implements TranslateService {
    private static final String TAG = "OpenAiTranslator";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private boolean initialized = false;
    private final ExecutorService executor;
    private final OkHttpClient client;

    public OpenAiTranslator() {
        executor = Executors.newSingleThreadExecutor();
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void translate(String text, String sourceLang, String targetLang, TranslateCallback callback) {
        if (!initialized) {
            initialize();
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

        executor.execute(() -> {
            try {
                String model = getModel(provider);

                JSONArray messages = new JSONArray();

                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", "You are a professional translator. Translate the given text from " +
                        (sourceLang != null ? sourceLang : "the source language") + " to " + targetLang +
                        ". Only return the translated text, nothing else. Do not add any explanations.");
                messages.add(systemMsg);

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", text);
                messages.add(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", model);
                requestBody.put("messages", messages);
                requestBody.put("temperature", 0.3f);
                requestBody.put("max_tokens", 2000);

                String finalApiKey = apiKey != null && !apiKey.isEmpty() ? apiKey : "";

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
                        String translated = extractTranslation(jsonResponse);
                        if (translated != null && !translated.isEmpty()) {
                            callback.onSuccess(translated);
                        } else {
                            callback.onError("Empty translation result");
                        }
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "";
                        callback.onError("API error " + response.code() + ": " + errorBody);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Translation failed", e);
                callback.onError(e.getMessage());
            }
        });
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
        String model = Settings.getAiTranslateModel();
        if (model != null && !model.isEmpty() && !model.equals("gpt-4o-mini")) {
            return model;
        }

        TranslatePresets.ProviderPreset preset = TranslatePresets.getPreset(provider);
        if (preset != null && preset.defaultModel != null && !preset.defaultModel.isEmpty()) {
            return preset.defaultModel;
        }

        return "gpt-4o-mini";
    }

    private String extractTranslation(JSONObject response) {
        try {
            return response.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract translation", e);
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        String provider = Settings.getAiTranslateProvider();
        String apiUrl = getApiUrl(provider);
        if (apiUrl == null || apiUrl.isEmpty()) return false;
        if (TranslatePresets.needsApiKey(provider)) {
            String apiKey = Settings.getAiTranslateApiKey();
            return apiKey != null && !apiKey.isEmpty();
        }
        return true;
    }

    @Override
    public String getProviderName() {
        String provider = Settings.getAiTranslateProvider();
        TranslatePresets.ProviderPreset preset = TranslatePresets.getPreset(provider);
        return preset != null ? preset.name : "Custom API";
    }

    @Override
    public void initialize() {
        initialized = true;
        Log.d(TAG, "Translator initialized: " + getProviderName());
    }

    @Override
    public void release() {
        initialized = false;
        executor.shutdown();
    }
}
