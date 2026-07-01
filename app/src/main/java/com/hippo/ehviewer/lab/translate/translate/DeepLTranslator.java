package com.hippo.ehviewer.lab.translate.translate;

import android.util.Log;

import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;

import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DeepLTranslator implements TranslateService {
    private static final String TAG = "DeepLTranslator";
    private static final String DEFAULT_API_URL = "https://api-free.deepl.com/v2/translate";
    
    private boolean initialized = false;
    private final ExecutorService executor;
    private final OkHttpClient client;

    public DeepLTranslator() {
        executor = Executors.newSingleThreadExecutor();
        client = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void translate(String text, String sourceLang, String targetLang, TranslateCallback callback) {
        if (!initialized) {
            callback.onError("DeepL translator not initialized");
            return;
        }

        String apiKey = Settings.getAiTranslateApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            callback.onError("DeepL API Key not configured");
            return;
        }

        executor.execute(() -> {
            try {
                String apiUrl = getApiUrl();

                RequestBody body = new FormBody.Builder()
                        .add("auth_key", apiKey)
                        .add("text", text)
                        .add("target_lang", mapLanguage(targetLang))
                        .build();

                Request request = new Request.Builder()
                        .url(apiUrl)
                        .post(body)
                        .build();

                try (Response response = client.newCall(request).execute()) {
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
                        callback.onError("DeepL API error: " + response.code());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Translation failed", e);
                callback.onError(e.getMessage());
            }
        });
    }

    private String extractTranslation(JSONObject response) {
        try {
            return response.getJSONArray("translations")
                    .getJSONObject(0)
                    .getString("text")
                    .trim();
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract translation", e);
            return null;
        }
    }

    private String mapLanguage(String lang) {
        if (lang == null) return "EN";
        switch (lang.toLowerCase()) {
            case "zh":
            case "zh-cn":
            case "chinese":
                return "ZH";
            case "ja":
            case "japanese":
                return "JA";
            case "ko":
            case "korean":
                return "KO";
            case "en":
            case "english":
                return "EN";
            default:
                return lang.toUpperCase();
        }
    }

    private String getApiUrl() {
        String customUrl = Settings.getAiTranslateApiUrl();
        if (customUrl != null && !customUrl.isEmpty()) {
            return customUrl;
        }
        return DEFAULT_API_URL;
    }

    @Override
    public boolean isAvailable() {
        String apiKey = Settings.getAiTranslateApiKey();
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public String getProviderName() {
        return "DeepL";
    }

    @Override
    public void initialize() {
        String apiKey = Settings.getAiTranslateApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            initialized = true;
            Log.d(TAG, "DeepL translator initialized");
        } else {
            Log.w(TAG, "DeepL API Key not configured");
        }
    }

    @Override
    public void release() {
        initialized = false;
        executor.shutdown();
    }
}
