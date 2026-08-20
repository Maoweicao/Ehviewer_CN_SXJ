package com.hippo.ehviewer.ui.fragment.lab;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.analyze.OpenAiImageAnalyzer;

/**
 * AI图片分析实验功能设置
 */
public class AiAnalyzeFragment extends PreferenceFragmentCompat {

    private static final String KEY_ANALYZE_ENABLED = "ai_analyze_enabled";
    private static final String KEY_MODEL = "ai_analyze_model";
    private static final String KEY_TEST = "ai_analyze_test";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.lab_ai_analyze, rootKey);

        setupPreference(KEY_ANALYZE_ENABLED, (pref, value) -> {
            Settings.putAiAnalyzeEnabled((Boolean) value);
            return true;
        });

        ListPreference sampleStepPref = findPreference("ai_analyze_sample_step");
        if (sampleStepPref != null) {
            sampleStepPref.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    Settings.putAiAnalyzeSampleStep(Integer.parseInt(newValue.toString()));
                } catch (NumberFormatException e) {
                    // ignore
                }
                return true;
            });
        }

        setupPreference(KEY_MODEL, (pref, value) -> {
            String model = value != null ? value.toString() : "";
            Settings.putAiAnalyzeModel(model);
            return true;
        });

        Preference autoPref = findPreference("ai_analyze_auto_on_finish");
        if (autoPref != null) {
            autoPref.setOnPreferenceChangeListener((preference, newValue) -> {
                Settings.putAiAnalyzeAutoOnFinish((Boolean) newValue);
                return true;
            });
        }

        setupClickPreference(KEY_MODEL, this::showModelDialog);
        setupClickPreference(KEY_TEST, this::testAnalyze);

        updateSummaries();
    }

    private void setupPreference(String key, PreferenceChangeHandler handler) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setOnPreferenceChangeListener((preference, newValue) -> handler.onChange(preference, newValue));
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
        updatePrefSummary(KEY_MODEL, getModelSummary());
    }

    private void updatePrefSummary(String key, String summary) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(summary);
        }
    }

    private String getModelSummary() {
        String model = Settings.getAiAnalyzeModel();
        if (model.isEmpty()) {
            String translateModel = Settings.getAiTranslateModel();
            return translateModel.isEmpty() ? "Not configured (fallback to translate model)" : "Fallback: " + translateModel;
        }
        return model;
    }

    private void showModelDialog(Preference preference) {
        Activity activity = getActivity();
        if (activity == null) return;

        EditText input = new EditText(activity);
        input.setHint("e.g. qwen2.5-vl:7b, llama3.2-vision, minicpm-v");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(Settings.getAiAnalyzeModel());

        new AlertDialog.Builder(activity)
                .setTitle(R.string.lab_ai_analyze_model)
                .setMessage(R.string.lab_ai_analyze_model_dialog_message)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String model = input.getText().toString().trim();
                    Settings.putAiAnalyzeModel(model);
                    updatePrefSummary(KEY_MODEL, getModelSummary());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void testAnalyze(Preference preference) {
        Activity activity = getActivity();
        if (activity == null) return;

        String apiUrl = Settings.getAiTranslateApiUrl();
        if (apiUrl == null || apiUrl.isEmpty()) {
            Toast.makeText(activity, "请先在AI翻译设置中配置 API 地址", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(activity, R.string.lab_ai_analyze_testing, Toast.LENGTH_SHORT).show();

        OpenAiImageAnalyzer analyzer = new OpenAiImageAnalyzer();
        analyzer.analyzeText("只输出JSON：{\"ok\": true}", "", new OpenAiImageAnalyzer.AnalyzeCallback() {
            @Override
            public void onSuccess(com.alibaba.fastjson.JSONObject result) {
                analyzer.release();
                activity.runOnUiThread(() ->
                        new AlertDialog.Builder(activity)
                                .setTitle(R.string.lab_ai_analyze_test_ok)
                                .setMessage("Analyze API works. Response: " + result.toJSONString())
                                .setPositiveButton(android.R.string.ok, null)
                                .show());
            }

            @Override
            public void onError(String error) {
                analyzer.release();
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, "Analyze test failed: " + error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private interface PreferenceChangeHandler {
        boolean onChange(Preference preference, Object newValue);
    }

    private interface PreferenceClickHandler {
        void onClick(Preference preference);
    }
}
