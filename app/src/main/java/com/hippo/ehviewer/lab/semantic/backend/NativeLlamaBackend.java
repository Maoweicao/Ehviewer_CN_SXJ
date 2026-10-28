package com.hippo.ehviewer.lab.semantic.backend;

import android.graphics.Bitmap;
import android.util.Log;

import com.hippo.ehviewer.lab.semantic.SemanticConfig;

import java.io.File;
import java.util.List;

/**
 * 端侧 llama.cpp 后端（GGUF 量化模型）。
 *
 * <p><b>当前为 JNI 契约层</b>：native 侧（{@code libsemantic.so}）由
 * {@code app/src/main/cpp/semantic/} 下的 llama.cpp 集成提供，未随 APK 预置（模型体积大，
 * 且 llama.cpp 会显著增加 so 体积）。因此这里的策略是：
 * <ul>
 *   <li>so 缺失或加载失败 → 抛 {@link EmbeddingException#NATIVE_UNAVAILABLE}，由
 *       {@link BackendFactory} 自动降级到远程后端</li>
 *   <li>模型未下载 → 抛 {@link EmbeddingException#MODEL_MISSING}，设置页引导下载</li>
 * </ul>
 * 这样即使 native 未编译，实验室功能也能用远程后端完整跑通。
 */
public class NativeLlamaBackend implements EmbeddingBackend {

    private static final String TAG = "NativeLlamaBackend";
    private static final String LIB_NAME = "semantic";

    private static volatile Boolean sLibraryLoaded;
    private static volatile String sLoadError;

    private final String modelPath;
    private final int dim;
    private final boolean useGpu;
    private final int threads;

    private long nativeHandle;
    private boolean loaded;

    public NativeLlamaBackend(String modelPath, int dim, boolean useGpu, int threads) {
        this.modelPath = modelPath;
        this.dim = dim > 0 ? dim : SemanticConfig.DIM_256;
        this.useGpu = useGpu;
        this.threads = threads > 0 ? threads : defaultThreads();
    }

    /** 探测 native 库是否可用（不加载模型）。 */
    public static boolean isNativeAvailable() {
        return loadLibrary();
    }

    /** native 库加载失败原因，可用时返回 null。 */
    public static String getLoadError() {
        loadLibrary();
        return sLoadError;
    }

    private static boolean loadLibrary() {
        Boolean cached = sLibraryLoaded;
        if (cached != null) {
            return cached;
        }
        synchronized (NativeLlamaBackend.class) {
            if (sLibraryLoaded != null) {
                return sLibraryLoaded;
            }
            try {
                System.loadLibrary(LIB_NAME);
                sLoadError = nativeProbe() ? null : "libsemantic.so 已加载但缺少必要符号（llama.cpp 版本不匹配）";
            } catch (Throwable t) {
                sLoadError = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            sLibraryLoaded = sLoadError == null;
            Log.i(TAG, "native library available=" + sLibraryLoaded + (sLoadError != null ? " (" + sLoadError + ")" : ""));
            return sLibraryLoaded;
        }
    }

    private static int defaultThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        // 留一核给 UI 与解码线程，避免前台卡顿
        return Math.max(2, cores - 2);
    }

    @Override
    public String id() {
        return useGpu ? SemanticConfig.BACKEND_NATIVE_GPU : SemanticConfig.BACKEND_NATIVE_CPU;
    }

    @Override
    public boolean isReady() {
        return loaded && isModelPresent();
    }

    @Override
    public int dimension() {
        return dim;
    }

    @Override
    public String modelId() {
        File file = new File(modelPath);
        return file.getName();
    }

    @Override
    public boolean supportsImage() {
        // EmbeddingGemma 2 的视觉编码器在 llama.cpp 侧尚未稳定验证，
        // 这里保守关闭；P3 阶段验证后再开启。
        return false;
    }

    private boolean isModelPresent() {
        File file = new File(modelPath);
        return file.exists() && file.length() > 0;
    }

    /** 懒加载模型。 */
    public synchronized void ensureLoaded() throws EmbeddingException {
        if (loaded) {
            return;
        }
        if (!loadLibrary()) {
            throw new EmbeddingException(EmbeddingException.NATIVE_UNAVAILABLE,
                    "端侧推理库不可用: " + sLoadError);
        }
        if (!isModelPresent()) {
            throw new EmbeddingException(EmbeddingException.MODEL_MISSING,
                    "模型文件不存在: " + modelPath);
        }
        long handle = nativeLoad(modelPath, threads, useGpu, dim);
        if (handle == 0L) {
            throw new EmbeddingException(EmbeddingException.INFERENCE_FAILED,
                    "模型加载失败（可能为 GGUF 格式或量化类型不受支持）");
        }
        nativeHandle = handle;
        loaded = true;
    }

    @Override
    public synchronized float[] embedQuery(String query) throws EmbeddingException {
        ensureLoaded();
        return wrap(nativeEmbed(nativeHandle, com.hippo.ehviewer.lab.semantic.model.EmbeddingPrompts.query(query), dim));
    }

    @Override
    public synchronized List<float[]> embedDocuments(List<String> documents) throws EmbeddingException {
        ensureLoaded();
        if (documents == null || documents.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        List<float[]> result = new java.util.ArrayList<>(documents.size());
        for (String doc : documents) {
            String payload = com.hippo.ehviewer.lab.semantic.model.EmbeddingPrompts.document(doc);
            result.add(wrap(nativeEmbed(nativeHandle, payload, dim)));
        }
        return result;
    }

    @Override
    public synchronized float[] embedImage(Bitmap bitmap) throws EmbeddingException {
        throw new EmbeddingException(EmbeddingException.FATAL, "端侧视觉编码尚未启用");
    }

    private static float[] wrap(float[] raw) throws EmbeddingException {
        if (raw == null || raw.length == 0) {
            throw new EmbeddingException(EmbeddingException.INFERENCE_FAILED, "推理返回空向量");
        }
        if (!com.hippo.ehviewer.lab.semantic.index.VectorMath.isValid(raw)) {
            // EmbeddingGemma 2 在 FP16 下会产出 NaN，这里直接拦截并提示量化设置
            throw new EmbeddingException(EmbeddingException.INFERENCE_FAILED,
                    "推理结果非法（NaN/Inf），请改用 Q4_K 量化模型");
        }
        return raw;
    }

    @Override
    public synchronized void close() {
        if (loaded && nativeHandle != 0L) {
            nativeFree(nativeHandle);
            nativeHandle = 0L;
        }
        loaded = false;
    }

    // ---- native 契约 ----

    private static native boolean nativeProbe();

    private static native long nativeLoad(String modelPath, int threads, boolean useGpu, int dim);

    private static native float[] nativeEmbed(long handle, String payload, int dim);

    private static native void nativeFree(long handle);
}