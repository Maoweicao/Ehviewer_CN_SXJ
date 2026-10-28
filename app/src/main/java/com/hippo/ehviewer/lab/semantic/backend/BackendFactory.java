package com.hippo.ehviewer.lab.semantic.backend;

import android.content.Context;
import android.util.Log;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.semantic.SemanticConfig;
import com.hippo.ehviewer.lab.semantic.capability.CapabilityProfile;
import com.hippo.ehviewer.lab.semantic.model.ModelManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 后端选择与降级链。
 *
 * <p>候选顺序（自动档）：native_gpu → native_cpu → remote → disabled。
 * 每次构建都会按「用户设置 → 平台能力 → 模型是否就位」逐个校验，
 * 第一个通过校验的即为最终后端；运行时若后端抛出可降级错误，调用方可通过
 * {@link #markDegraded(EmbeddingBackend, EmbeddingException)} 触发重建。
 */
public final class BackendFactory {

    private static final String TAG = "SemanticBackendFactory";

    /** 一次降级事件回调，供实验室设置页展示「已从端侧切换到远程」这类提示。 */
    public interface DegradeListener {
        void onDegraded(String fromBackend, String toBackend, String reason);
    }

    private static volatile DegradeListener sListener;

    private BackendFactory() {
    }

    public static void setDegradeListener(DegradeListener listener) {
        sListener = listener;
    }

    /**
     * 按能力档案与用户设置构建后端。
     *
     * @param profile 平台能力快照
     * @param userMode 用户选择的模式（{@link SemanticConfig#BACKEND_AUTO} 表示自动）
     */
    public static EmbeddingBackend create(Context context, CapabilityProfile profile, String userMode) {
        String mode = userMode == null || userMode.isEmpty()
                ? SemanticConfig.BACKEND_AUTO : userMode;

        if (SemanticConfig.BACKEND_OFF.equals(mode)) {
            return new DisabledBackend();
        }
        if (SemanticConfig.BACKEND_REMOTE.equals(mode)) {
            return createRemoteOrDisabled();
        }

        boolean auto = SemanticConfig.BACKEND_AUTO.equals(mode);
        List<String> chain = new ArrayList<>();
        if (auto) {
            chain.add(profile.recommendedBackend());
            // 自动档始终保留 CPU 兜底（GPU 失败/模型未就位时）
            if (!SemanticConfig.BACKEND_NATIVE_CPU.equals(profile.recommendedBackend())) {
                chain.add(SemanticConfig.BACKEND_NATIVE_CPU);
            }
            chain.add(SemanticConfig.BACKEND_REMOTE);
        } else {
            chain.add(mode);
            // 用户显式选 GPU 但条件不满足时降级 CPU，再降级远程
            if (SemanticConfig.BACKEND_NATIVE_GPU.equals(mode)) {
                chain.add(SemanticConfig.BACKEND_NATIVE_CPU);
            }
            chain.add(SemanticConfig.BACKEND_REMOTE);
        }

        ModelManager modelManager = new ModelManager(context);
        for (String candidate : chain) {
            EmbeddingBackend backend = tryCreate(context, profile, candidate, modelManager);
            if (backend != null) {
                Log.i(TAG, "backend selected: " + candidate + " (" + backend.modelId()
                        + ", dim=" + backend.dimension() + ")");
                return backend;
            }
        }
        return new DisabledBackend();
    }

    private static EmbeddingBackend tryCreate(Context context, CapabilityProfile profile,
                                              String candidate, ModelManager modelManager) {
        switch (candidate) {
            case SemanticConfig.BACKEND_NATIVE_GPU:
                if (!profile.gpuAccelAvailable()) {
                    Log.i(TAG, "skip native_gpu: GPU 不满足加速条件");
                    return null;
                }
                // 落到 CPU 实现，用 GPU 标志区分批量索引路径
                return createNative(profile, modelManager, true);
            case SemanticConfig.BACKEND_NATIVE_CPU:
                if (!profile.nativeCapable()) {
                    Log.i(TAG, "skip native_cpu: 平台不具备端侧运行条件");
                    return null;
                }
                return createNative(profile, modelManager, false);
            case SemanticConfig.BACKEND_REMOTE:
                return createRemoteOrDisabled();
            default:
                return null;
        }
    }

    private static EmbeddingBackend createNative(CapabilityProfile profile,
                                                 ModelManager modelManager, boolean useGpu) {
        String modelPath = modelManager.resolveModelPath(profile);
        if (modelPath == null) {
            Log.i(TAG, "skip native: 未安装模型文件");
            return null;
        }
        int dim = Settings.getSemanticDim() > 0 ? Settings.getSemanticDim() : profile.recommendedDim();
        return new NativeLlamaBackend(modelPath, dim, useGpu, 0);
    }

    private static EmbeddingBackend createRemoteOrDisabled() {
        String url = Settings.getSemanticRemoteUrl();
        if (url == null || url.isEmpty()) {
            return new DisabledBackend();
        }
        int dim = Settings.getSemanticDim() > 0
                ? Settings.getSemanticDim() : SemanticConfig.DIM_256;
        return new RemoteEmbeddingBackend(
                url,
                Settings.getSemanticRemoteKey(),
                Settings.getSemanticRemoteModel(),
                Settings.getSemanticRemoteProtocol(),
                dim);
    }

    /**
     * 记录一次降级事件：通知监听者并返回建议切换到的后端 id。
     */
    public static String markDegraded(EmbeddingBackend current, EmbeddingException error) {
        if (current == null || error == null || !error.isDegradable()) {
            return null;
        }
        String from = current.id();
        String to;
        if (SemanticConfig.BACKEND_REMOTE.equals(from)) {
            to = SemanticConfig.BACKEND_OFF;
        } else {
            to = SemanticConfig.BACKEND_REMOTE;
        }
        Log.w(TAG, "degrade " + from + " -> " + to + " (" + error.getCode() + ": " + error.getMessage() + ")");
        try {
            current.close();
        } catch (Throwable ignored) {
            // ignore
        }
        DegradeListener listener = sListener;
        if (listener != null) {
            listener.onDegraded(from, to, error.getMessage());
        }
        return to;
    }

    /** 端侧模型与 native 库是否就绪（设置页展示用）。 */
    public static boolean nativeReady(Context context, CapabilityProfile profile) {
        if (!profile.nativeCapable()) {
            return false;
        }
        if (!NativeLlamaBackend.isNativeAvailable()) {
            return false;
        }
        return new ModelManager(context).resolveModelPath(profile) != null;
    }
}