package com.hippo.ehviewer.ui.fragment.lab;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.semantic.SemanticSearchManager;
import com.hippo.ehviewer.lab.semantic.backend.EmbeddingException;
import com.hippo.ehviewer.lab.semantic.backend.NativeLlamaBackend;
import com.hippo.ehviewer.lab.semantic.capability.CapabilityProfile;
import com.hippo.ehviewer.lab.semantic.index.IndexingPipeline;
import com.hippo.ehviewer.lab.semantic.model.ModelCatalog;
import com.hippo.ehviewer.lab.semantic.model.ModelManager;
import com.hippo.ehviewer.lab.semantic.model.ModelSpec;

import java.io.File;
import java.util.List;

/**
 * AI 语义搜索（实验室功能）设置页。
 *
 * <p>与 {@code AiTranslateFragment} / {@code AiAnalyzeFragment} 保持一致的结构：
 * XML Preference 声明 + 代码绑定 + 对话框编辑。本页额外承担三件事：
 * <ol>
 *   <li>展示平台能力探测报告，让用户看清「为什么自动选了某个后端」</li>
 *   <li>端侧模型下载（断点续传 + 镜像源）与删除</li>
 *   <li>远程后端连通性自检与索引触发</li>
 * </ol>
 */
public class SemanticSearchFragment extends PreferenceFragmentCompat {

    private static final String KEY_ENABLED = "semantic_enabled";
    private static final String KEY_CAPABILITY = "semantic_capability";
    private static final String KEY_BACKEND = "semantic_backend";
    private static final String KEY_DIM = "semantic_dim";
    private static final String KEY_MODEL = "semantic_model";
    private static final String KEY_MODEL_DOWNLOAD = "semantic_model_download";
    private static final String KEY_GPU_INDEX = "semantic_gpu_index";
    private static final String KEY_INDEX_ON_CHARGE = "semantic_index_on_charge";
    private static final String KEY_REMOTE_URL = "semantic_remote_url";
    private static final String KEY_REMOTE_MODEL = "semantic_remote_model";
    private static final String KEY_REMOTE_PROTOCOL = "semantic_remote_protocol";
    private static final String KEY_REMOTE_KEY = "semantic_remote_key";
    private static final String KEY_REMOTE_TEST = "semantic_remote_test";
    private static final String KEY_INDEX_STATUS = "semantic_index_status";
    private static final String KEY_INDEX_START = "semantic_index_start";
    private static final String KEY_INDEX_CLEAR = "semantic_index_clear";

    private SemanticSearchManager manager;
    private ModelManager modelManager;

    /** 当前进度对话框（下载模型或建立索引时显示）。 */
    private AlertDialog progressDialog;
    private View progressView;
    private TextView progressText;
    private ProgressBar progressBar;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.lab_semantic, rootKey);

        Activity activity = getActivity();
        manager = SemanticSearchManager.getInstance(activity);
        modelManager = new ModelManager(activity);

        setupEnable();
        setupListPreference(KEY_BACKEND, value -> Settings.putSemanticBackend(String.valueOf(value)));
        setupListPreference(KEY_DIM, value -> {
            try {
                Settings.putSemanticDim(Integer.parseInt(String.valueOf(value)));
            } catch (NumberFormatException e) {
                // ignore
            }
        });
        setupListPreference(KEY_REMOTE_PROTOCOL, value -> Settings.putSemanticRemoteProtocol(String.valueOf(value)));

        setupSwitch(KEY_GPU_INDEX, value -> Settings.putSemanticGpuIndex((Boolean) value));
        setupSwitch(KEY_INDEX_ON_CHARGE, value -> Settings.putSemanticIndexOnCharge((Boolean) value));

        setupClick(KEY_CAPABILITY, preference -> showCapability());
        setupClick(KEY_MODEL, preference -> showModelPicker());
        setupClick(KEY_MODEL_DOWNLOAD, preference -> showModelPicker());
        setupClick(KEY_REMOTE_URL, preference -> showTextDialog(KEY_REMOTE_URL,
                R.string.lab_semantic_remote_url,
                "http://192.168.1.10:8080/v1",
                Settings.getSemanticRemoteUrl(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
        setupClick(KEY_REMOTE_MODEL, preference -> showTextDialog(KEY_REMOTE_MODEL,
                R.string.lab_semantic_remote_model, "embeddinggemma-2",
                Settings.getSemanticRemoteModel(), InputType.TYPE_CLASS_TEXT));
        setupClick(KEY_REMOTE_KEY, preference -> showTextDialog(KEY_REMOTE_KEY,
                R.string.lab_semantic_remote_key, "sk-...",
                Settings.getSemanticRemoteKey(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        setupClick(KEY_REMOTE_TEST, preference -> testRemote());
        setupClick(KEY_INDEX_STATUS, preference -> updateSummaries());
        setupClick(KEY_INDEX_START, preference -> startIndexing());
        setupClick(KEY_INDEX_CLEAR, preference -> confirmClearIndex());

        updateSummaries();
    }

    // ---- 基础绑定 ----

    private void setupEnable() {
        Preference pref = findPreference(KEY_ENABLED);
        if (pref instanceof SwitchPreferenceCompat) {
            pref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean value = Boolean.TRUE.equals(newValue);
                if (value) {
                    // 开启前先确认有可用的推理路径，避免用户开完发现什么都搜不出来
                    CapabilityProfile profile = manager.getProfile();
                    boolean remoteConfigured = !Settings.getSemanticRemoteUrl().isEmpty();
                    boolean nativeOk = profile.nativeCapable()
                            && NativeLlamaBackend.isNativeAvailable();
                    if (!nativeOk && !remoteConfigured) {
                        toast("端侧推理不可用且未配置远程接口，语义搜索将不会生效");
                    }
                }
                Settings.putSemanticEnabled(value);
                return true;
            });
        }
    }

    private void setupSwitch(String key, SettingSetter setter) {
        Preference pref = findPreference(key);
        if (pref instanceof SwitchPreferenceCompat) {
            pref.setOnPreferenceChangeListener((preference, newValue) -> {
                setter.set((Boolean) newValue);
                manager.refresh();
                return true;
            });
        }
    }

    private void setupListPreference(String key, SettingSetter setter) {
        ListPreference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            setter.set(newValue);
            manager.refresh();
            updateSummaries();
            return true;
        });
    }

    private void setupClick(String key, SettingClickHandler handler) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setOnPreferenceClickListener(preference -> {
                handler.onClick(preference);
                return true;
            });
        }
    }

    // ---- 能力报告 ----

    private void showCapability() {
        CapabilityProfile profile = manager.getProfile();
        StringBuilder sb = new StringBuilder(profile.report());
        sb.append("\n\n").append("native: ")
                .append(NativeLlamaBackend.isNativeAvailable() ? "可用" : "不可用")
                .append(NativeLlamaBackend.getLoadError() != null
                        ? " (" + NativeLlamaBackend.getLoadError() + ")" : "");
        sb.append("\n当前后端：").append(manager.backendSummary());

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.lab_semantic_capability_title)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---- 模型选择与下载 ----

    private void showModelPicker() {
        CapabilityProfile profile = manager.getProfile();
        List<ModelSpec> specs = ModelCatalog.listForTier(profile.tier());
        if (specs.isEmpty()) {
            toast(getString(R.string.lab_semantic_model_none_installed));
            return;
        }

        String[] labels = new String[specs.size()];
        for (int i = 0; i < specs.size(); i++) {
            ModelSpec spec = specs.get(i);
            String state = modelManager.isInstalled(spec) ? "✓ " : "　";
            labels[i] = state + spec.displayName + "（" + spec.params + "，约 " + spec.sizeText() + "）";
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.lab_semantic_model_select_title)
                .setItems(labels, (dialog, which) -> onModelSelected(specs.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onModelSelected(ModelSpec spec) {
        Settings.putSemanticModelId(spec.id);
        manager.refresh();
        updateSummaries();

        if (modelManager.isInstalled(spec)) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(spec.displayName)
                    .setMessage("模型已安装：" + modelManager.localFile(spec).getAbsolutePath())
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton("删除", (dialog, which) -> {
                        if (modelManager.delete(spec)) {
                            manager.refresh();
                            updateSummaries();
                            toast("已删除模型");
                        }
                    })
                    .show();
            return;
        }

        if (!hasStorageForModel()) {
            toast("存储空间不足，无法下载模型");
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(spec.displayName)
                .setMessage(spec.notes + "\n\n约 " + spec.sizeText()
                        + "\n将从 HuggingFace 下载，失败时自动切换镜像源。")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> startDownload(spec))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean hasStorageForModel() {
        return manager.getProfile().freeStorageBytes >= com.hippo.ehviewer.lab.semantic.SemanticConfig.MIN_FREE_STORAGE;
    }

    private void startDownload(final ModelSpec spec) {
        showProgressDialog();
        modelManager.download(spec, new ModelManager.DownloadCallback() {
            @Override
            public void onProgress(final long downloaded, final long total) {
                Activity activity = getActivity();
                if (activity == null) {
                    return;
                }
                activity.runOnUiThread(() -> {
                    if (progressText == null) {
                        return;
                    }
                    String downloadedText = ModelManager.formatSize(downloaded);
                    String totalText = total > 0 ? ModelManager.formatSize(total) : spec.sizeText();
                    progressText.setText(getString(R.string.lab_semantic_model_downloading,
                            downloadedText, totalText));
                    if (total > 0) {
                        progressBar.setProgress((int) Math.min(100, downloaded * 100 / total));
                    }
                });
            }

            @Override
            public void onSuccess(final File file) {
                Activity activity = getActivity();
                if (activity == null) {
                    return;
                }
                activity.runOnUiThread(() -> {
                    dismissProgressDialog();
                    manager.refresh();
                    updateSummaries();
                    toast(getString(R.string.lab_semantic_model_downloaded, spec.displayName));
                });
            }

            @Override
            public void onError(final String error) {
                Activity activity = getActivity();
                if (activity == null) {
                    return;
                }
                activity.runOnUiThread(() -> {
                    dismissProgressDialog();
                    toast("下载失败：" + error);
                });
            }
        });
    }

    // ---- 远程自检 ----

    private void testRemote() {
        String url = Settings.getSemanticRemoteUrl();
        if (url.isEmpty()) {
            toast("请先配置远程接口地址");
            return;
        }
        toast("正在测试远程后端…");
        new Thread(() -> {
            String message;
            try {
                int dim = manager.probeRemote();
                message = getString(R.string.lab_semantic_test_ok, dim);
            } catch (EmbeddingException e) {
                message = "测试失败：" + e.getMessage();
            } catch (Throwable t) {
                message = "测试失败：" + t.getMessage();
            }
            final String result = message;
            Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> toast(result));
            }
        }, "SemanticRemoteProbe").start();
    }

    // ---- 索引 ----

    private void startIndexing() {
        if (!Settings.getSemanticEnabled()) {
            toast("请先启用语义搜索");
            return;
        }
        showProgressDialog();
        IndexingPipeline pipeline = IndexingPipeline.getInstance(requireContext());
        boolean submitted = pipeline.submit(new IndexingPipeline.ProgressListener() {
            @Override
            public void onStart(final int total) {
                post(() -> {
                    if (progressText != null) {
                        progressText.setText(getString(R.string.lab_semantic_indexing, 0, total));
                    }
                });
            }

            @Override
            public void onProgress(final int done, final int total, final String currentTitle) {
                post(() -> {
                    if (progressText != null) {
                        progressText.setText(getString(R.string.lab_semantic_indexing, done, total));
                    }
                    if (progressBar != null && total > 0) {
                        progressBar.setProgress(done * 100 / total);
                    }
                });
            }

            @Override
            public void onFinish(final int indexed, final boolean aborted) {
                post(() -> {
                    dismissProgressDialog();
                    updateSummaries();
                    toast(aborted ? getString(R.string.lab_semantic_index_aborted)
                            : getString(R.string.lab_semantic_index_done, indexed));
                });
            }

            @Override
            public void onError(final String error) {
                post(() -> {
                    dismissProgressDialog();
                    toast(error);
                });
            }
        });
        if (!submitted) {
            dismissProgressDialog();
        }
    }

    private void confirmClearIndex() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.lab_semantic_index_clear)
                .setMessage(R.string.lab_semantic_index_clear_summary)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    IndexingPipeline.getInstance(requireContext()).clearIndex();
                    updateSummaries();
                    toast("索引已清空");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- 摘要刷新 ----

    private void updateSummaries() {
        CapabilityProfile profile = manager.getProfile();
        setSummary(KEY_CAPABILITY, profile.tier() + " · " + profile.recommendedBackend());

        String modelSummary;
        if (modelManager.isDownloading()) {
            modelSummary = "下载中…";
        } else {
            List<ModelSpec> installed = modelManager.installed();
            modelSummary = installed.isEmpty()
                    ? getString(R.string.lab_semantic_model_none_installed)
                    : installed.get(0).displayName + " · " + manager.backendSummary();
        }
        setSummary(KEY_MODEL, modelSummary);
        setSummary(KEY_MODEL_DOWNLOAD, "已占用 " + ModelManager.formatSize(modelManager.installedSize()));

        IndexingPipeline pipeline = IndexingPipeline.getInstance(requireContext());
        String status = pipeline.stats(manager.getStore(), manager.getBackend().modelId());
        if (pipeline.isRunning()) {
            status = "索引进行中… " + status;
        }
        setSummary(KEY_INDEX_STATUS, status);
    }

    private void setSummary(String key, String summary) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(summary);
        }
    }

    // ---- 对话框工具 ----

    private void showTextDialog(String key, int titleRes, String hint,
                                String current, int inputType) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setInputType(inputType);
        input.setText(current);

        new AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    applyTextSetting(key, value);
                    manager.refresh();
                    updateSummaries();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyTextSetting(String key, String value) {
        switch (key) {
            case KEY_REMOTE_URL: Settings.putSemanticRemoteUrl(value); break;
            case KEY_REMOTE_MODEL: Settings.putSemanticRemoteModel(value); break;
            case KEY_REMOTE_KEY: Settings.putSemanticRemoteKey(value); break;
            default: break;
        }
    }

    private void showProgressDialog() {
        Activity activity = getActivity();
        if (activity == null || progressDialog != null) {
            return;
        }
        View view = activity.getLayoutInflater().inflate(R.layout.lab_semantic_progress, null);
        progressBar = view.findViewById(R.id.semantic_progress_bar);
        progressText = view.findViewById(R.id.semantic_progress_text);
        if (progressBar != null) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
        }
        progressView = view;
        progressDialog = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(false)
                .show();
    }

    private void dismissProgressDialog() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
        progressView = null;
        progressText = null;
        progressBar = null;
    }

    private void post(Runnable runnable) {
        Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(runnable);
        }
    }

    private void toast(String message) {
        Activity activity = getActivity();
        if (activity != null && message != null) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (manager != null) {
            manager.refresh();
            updateSummaries();
        }
    }

    private interface SettingSetter {
        void set(Object value);
    }

    private interface SettingClickHandler {
        void onClick(Preference preference);
    }
}