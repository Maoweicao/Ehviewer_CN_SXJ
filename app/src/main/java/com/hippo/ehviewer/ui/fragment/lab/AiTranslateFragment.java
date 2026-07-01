package com.hippo.ehviewer.ui.fragment.lab;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.translate.AiTranslateManager;
import com.hippo.ehviewer.lab.translate.TranslatePresets;

import java.util.ArrayList;
import java.util.List;

public class AiTranslateFragment extends PreferenceFragmentCompat {

    private static final String KEY_TRANSLATE_ENABLED = "ai_translate_enabled";
    private static final String KEY_OCR_LAN_URL = "ai_ocr_lan_url";
    private static final String KEY_API_KEY = "ai_translate_api_key";
    private static final String KEY_API_URL = "ai_translate_api_url";
    private static final String KEY_MODEL = "ai_translate_model";
    private static final String KEY_SKIP_ANIMATED = "ai_translate_skip_animated";
    private static final String KEY_AUTO_DETECT = "ai_translate_auto_detect";
    private static final String KEY_TRANSLATE_TEST = "ai_translate_test";
    private static final String KEY_OCR_TEST = "ai_ocr_test";
    private static final String KEY_CACHE_CLEAR = "ai_translate_cache_clear";

    private AiTranslateManager translateManager;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.lab_ai_translate, rootKey);

        translateManager = AiTranslateManager.getInstance();

        setupPreference(KEY_TRANSLATE_ENABLED, true, (pref, value) -> {
            Settings.putAiTranslateEnabled((Boolean) value);
            return true;
        });

        setupListPreference("ai_translate_provider", Settings.getAiTranslateProvider());

        setupClickPreference(KEY_OCR_LAN_URL, this::showOcrLanUrlDialog);
        setupClickPreference(KEY_API_KEY, this::showApiKeyDialog);
        setupClickPreference(KEY_API_URL, this::showApiUrlDialog);
        setupClickPreference(KEY_MODEL, this::showModelDialog);

        setupPreference(KEY_SKIP_ANIMATED, true, (pref, value) -> {
            Settings.putAiTranslateSkipAnimated((Boolean) value);
            return true;
        });

        setupPreference(KEY_AUTO_DETECT, true, (pref, value) -> {
            Settings.putAiTranslateAutoDetect((Boolean) value);
            return true;
        });

        setupClickPreference(KEY_TRANSLATE_TEST, this::testTranslate);
        setupClickPreference(KEY_OCR_TEST, this::testOcr);
        setupClickPreference(KEY_CACHE_CLEAR, this::showClearCacheDialog);

        updateSummaries();
    }

    private void setupPreference(String key, boolean defaultValue, PreferenceChangeHandler handler) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setOnPreferenceChangeListener((preference, newValue) -> handler.onChange(preference, newValue));
        }
    }

    private void setupListPreference(String key, String value) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(value);
            pref.setOnPreferenceChangeListener((preference, newValue) -> {
                preference.setSummary(newValue.toString());
                return true;
            });
        }
    }

    private void setupClickPreference(String key, PreferenceClickHandler handler) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setOnPreferenceClickListener(preference -> {
                handler.onClick(preference);
                return true;
            });
        }
    }

    private void updateSummaries() {
        updatePrefSummary(KEY_API_KEY, getApiKeySummary());
        updatePrefSummary(KEY_API_URL, getApiUrlSummary());
        updatePrefSummary(KEY_OCR_LAN_URL, getOcrLanUrlSummary());
        updatePrefSummary(KEY_MODEL, Settings.getAiTranslateModel());
    }

    private void updatePrefSummary(String key, String summary) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(summary);
        }
    }

    private String getApiKeySummary() {
        String key = Settings.getAiTranslateApiKey();
        if (key.isEmpty()) return "Not configured";
        return "Configured (****" + key.substring(Math.max(0, key.length() - 4)) + ")";
    }

    private String getApiUrlSummary() {
        String url = Settings.getAiTranslateApiUrl();
        if (url.isEmpty()) {
            String provider = Settings.getAiTranslateProvider();
            String defaultUrl = TranslatePresets.getDefaultBaseUrl(provider);
            return defaultUrl.isEmpty() ? "Not configured" : "Default: " + defaultUrl;
        }
        return url;
    }

    private String getOcrLanUrlSummary() {
        String url = Settings.getAiOcrLanUrl();
        if (url.isEmpty()) return "Not configured";
        return url;
    }

    private void showOcrLanUrlDialog(Preference preference) {
        Activity activity = getActivity();
        if (activity == null) return;

        EditText input = new EditText(activity);
        input.setHint("http://192.168.1.100:5001");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(Settings.getAiOcrLanUrl());

        new AlertDialog.Builder(activity)
                .setTitle("LAN OCR Server URL")
                .setMessage("Enter the URL of the PaddleOCR server running on your PC.\nExample: http://192.168.1.100:5001")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    Settings.putAiOcrLanUrl(url);
                    updatePrefSummary(KEY_OCR_LAN_URL, getOcrLanUrlSummary());
                    translateManager.initialize();
                    Toast.makeText(activity, "OCR server URL saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showApiKeyDialog(Preference preference) {
        Activity activity = getActivity();
        if (activity == null) return;

        EditText input = new EditText(activity);
        input.setHint("Enter API Key");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(Settings.getAiTranslateApiKey());

        new AlertDialog.Builder(activity)
                .setTitle("API Key")
                .setMessage("Required for DeepSeek, Unsloth, OpenAI. Not needed for local Ollama/llama.cpp.")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String key = input.getText().toString().trim();
                    Settings.putAiTranslateApiKey(key);
                    updatePrefSummary(KEY_API_KEY, getApiKeySummary());
                    Toast.makeText(activity, "API Key saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showApiUrlDialog(Preference preference) {
        Activity activity = getActivity();
        if (activity == null) return;

        EditText input = new EditText(activity);
        String provider = Settings.getAiTranslateProvider();
        String defaultUrl = TranslatePresets.getDefaultBaseUrl(provider);
        input.setHint("Leave empty for default: " + defaultUrl);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(Settings.getAiTranslateApiUrl());

        new AlertDialog.Builder(activity)
                .setTitle("Custom API URL")
                .setMessage("Override the default API endpoint.\nDefault for " + provider + ": " + defaultUrl)
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    Settings.putAiTranslateApiUrl(url);
                    updatePrefSummary(KEY_API_URL, getApiUrlSummary());
                    Toast.makeText(activity, "API URL saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Reset to Default", (dialog, which) -> {
                    Settings.putAiTranslateApiUrl("");
                    updatePrefSummary(KEY_API_URL, getApiUrlSummary());
                })
                .show();
    }

    private void showModelDialog(Preference preference) {
        Activity activity = getActivity();
        if (activity == null) return;

        String provider = Settings.getAiTranslateProvider();
        TranslatePresets.ProviderPreset preset = TranslatePresets.getPreset(provider);

        List<String> modelList = new ArrayList<>();
        if (preset != null && preset.defaultModel != null && !preset.defaultModel.isEmpty()) {
            modelList.add(preset.defaultModel);
        }
        modelList.add("gpt-4o-mini");
        modelList.add("gpt-4o");
        modelList.add("gpt-3.5-turbo");
        modelList.add("gemma2:9b");
        modelList.add("qwen2.5:7b");
        modelList.add("deepseek-chat");

        String[] models = modelList.toArray(new String[0]);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        AutoCompleteTextView autoCompleteTextView = new AutoCompleteTextView(activity);
        autoCompleteTextView.setHint("Select or enter model name");
        autoCompleteTextView.setInputType(InputType.TYPE_CLASS_TEXT);
        autoCompleteTextView.setText(Settings.getAiTranslateModel());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_dropdown_item_1line, models);
        autoCompleteTextView.setAdapter(adapter);

        layout.addView(autoCompleteTextView);

        new AlertDialog.Builder(activity)
                .setTitle("Model (" + provider + ")")
                .setView(layout)
                .setPositiveButton("OK", (dialog, which) -> {
                    String model = autoCompleteTextView.getText().toString().trim();
                    if (!model.isEmpty()) {
                        Settings.putAiTranslateModel(model);
                        updatePrefSummary(KEY_MODEL, model);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void testTranslate(Preference preference) {
        Activity activity = getActivity();
        if (activity == null) return;

        if (!translateManager.isAvailable()) {
            Toast.makeText(activity, "Translation service not available. Check settings.", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(activity, "Testing translation...", Toast.LENGTH_SHORT).show();
        translateManager.testTranslate("Hello, World!", new AiTranslateManager.TranslateCallback() {
            @Override
            public void onSuccess(String translated) {
                activity.runOnUiThread(() -> {
                    new AlertDialog.Builder(activity)
                            .setTitle("Translation Test OK")
                            .setMessage("Original: Hello, World!\nTranslated: " + translated)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }

            @Override
            public void onError(String error) {
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, "Translation test failed: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void testOcr(Preference preference) {
        Activity activity = getActivity();
        if (activity == null) return;

        String url = Settings.getAiOcrLanUrl();
        if (url.isEmpty()) {
            Toast.makeText(activity, "Please configure LAN OCR server URL first", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(activity, "Testing OCR server connection...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            boolean ok = false;
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                String healthUrl = url;
                if (!healthUrl.endsWith("/health")) {
                    if (healthUrl.endsWith("/")) {
                        healthUrl += "health";
                    } else {
                        healthUrl += "/health";
                    }
                }
                okhttp3.Request request = new okhttp3.Request.Builder().url(healthUrl).get().build();
                try (okhttp3.Response response = client.newCall(request).execute()) {
                    ok = response.isSuccessful();
                }
            } catch (Exception e) {
                // ignore
            }

            final boolean finalOk = ok;
            activity.runOnUiThread(() -> {
                if (finalOk) {
                    new AlertDialog.Builder(activity)
                            .setTitle("OCR Server OK")
                            .setMessage("Successfully connected to OCR server at:\n" + url)
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    Toast.makeText(activity, "Cannot connect to OCR server at: " + url, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void showClearCacheDialog(Preference preference) {
        Activity activity = getActivity();
        if (activity == null) return;

        new AlertDialog.Builder(activity)
                .setTitle("Clear Translation Cache")
                .setMessage("Delete all cached translation results?")
                .setPositiveButton("Clear", (dialog, which) -> {
                    translateManager.clearCache();
                    Toast.makeText(activity, "Cache cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface PreferenceChangeHandler {
        boolean onChange(Preference preference, Object newValue);
    }

    private interface PreferenceClickHandler {
        void onClick(Preference preference);
    }
}
