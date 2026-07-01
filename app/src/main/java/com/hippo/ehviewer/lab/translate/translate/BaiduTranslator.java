package com.hippo.ehviewer.lab.translate.translate;

import android.util.Log;

import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;

import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class BaiduTranslator implements TranslateService {
    private static final String TAG = "BaiduTranslator";
    private static final String DEFAULT_API_URL = "https://fanyi-api.baidu.com/api/trans/vip/translate";
    
    private boolean initialized = false;
    private final ExecutorService executor;
    private final OkHttpClient client;

    public BaiduTranslator() {
        executor = Executors.newSingleThreadExecutor();
        client = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void translate(String text, String sourceLang, String targetLang, TranslateCallback callback) {
        if (!initialized) {
            callback.onError("Baidu translator not initialized");
            return;
        }

        String apiKey = Settings.getAiTranslateApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            callback.onError("Baidu API Key not configured");
            return;
        }

        executor.execute(() -> {
            try {
                String apiUrl = getApiUrl();
                String salt = String.valueOf(System.currentTimeMillis());
                String sign = generateSign(apiKey, text, salt);

                RequestBody body = new FormBody.Builder()
                        .add("q", text)
                        .add("from", mapLanguage(sourceLang))
                        .add("to", mapLanguage(targetLang))
                        .add("appid", apiKey)
                        .add("salt", salt)
                        .add("sign", sign)
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
                            String errorMsg = jsonResponse.getString("error_msg");
                            callback.onError(errorMsg != null ? errorMsg : "Empty translation result");
                        }
                    } else {
                        callback.onError("Baidu API error: " + response.code());
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
            return response.getJSONArray("trans_result")
                    .getJSONObject(0)
                    .getString("dst")
                    .trim();
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract translation", e);
            return null;
        }
    }

    private String generateSign(String appId, String query, String salt) {
        try {
            String str = appId + query + salt + "your_secret_key"; // TODO: Add secret key
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(str.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate sign", e);
            return "";
        }
    }

    private String mapLanguage(String lang) {
        if (lang == null) return "auto";
        switch (lang.toLowerCase()) {
            case "zh":
            case "zh-cn":
            case "chinese":
                return "zh";
            case "ja":
            case "japanese":
                return "jp";
            case "ko":
            case "korean":
                return "kor";
            case "en":
            case "english":
                return "en";
            default:
                return "auto";
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
        return "百度翻译";
    }

    @Override
    public void initialize() {
        String apiKey = Settings.getAiTranslateApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            initialized = true;
            Log.d(TAG, "Baidu translator initialized");
        } else {
            Log.w(TAG, "Baidu API Key not configured");
        }
    }

    @Override
    public void release() {
        initialized = false;
        executor.shutdown();
    }
}
