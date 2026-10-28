package com.hippo.ehviewer.lab.semantic;

import android.content.Context;
import android.util.Log;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.semantic.backend.BackendFactory;
import com.hippo.ehviewer.lab.semantic.backend.EmbeddingBackend;
import com.hippo.ehviewer.lab.semantic.backend.EmbeddingException;
import com.hippo.ehviewer.lab.semantic.backend.RemoteEmbeddingBackend;
import com.hippo.ehviewer.lab.semantic.capability.CapabilityDetector;
import com.hippo.ehviewer.lab.semantic.capability.CapabilityProfile;
import com.hippo.ehviewer.lab.semantic.index.SemanticVectorStore;

import java.util.List;

/**
 * 语义搜索总入口（实验室功能）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>懒加载单例，行为对齐 {@code AiAnalyzeManager}：使用处才初始化</li>
 *   <li>{@link #isEnabled()} 采用与 AI 翻译/分析一致的双开关（实验室总开关 + 本功能开关）</li>
 *   <li>后端由 {@link BackendFactory} 按平台能力自动选择，端侧失败自动降级远程</li>
 * </ul>
 */
public class SemanticSearchManager {

    private static final String TAG = "SemanticSearchManager";

    private static volatile SemanticSearchManager sInstance;

    private final Context appContext;
    private CapabilityProfile profile;
    private EmbeddingBackend backend;
    private SemanticVectorStore store;

    private SemanticSearchManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static SemanticSearchManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (SemanticSearchManager.class) {
                if (sInstance == null) {
                    sInstance = new SemanticSearchManager(context);
                }
            }
        }
        return sInstance;
    }

    /** 实验室总开关 + 本功能开关。 */
    public static boolean isEnabled() {
        return Settings.getLabEnabled() && Settings.getSemanticEnabled();
    }

    public SemanticVectorStore getStore() {
        if (store == null) {
            store = SemanticVectorStore.getInstance(appContext);
        }
        return store;
    }

    public CapabilityProfile getProfile() {
        if (profile == null) {
            profile = CapabilityDetector.get(appContext);
        }
        return profile;
    }

    /** 重新探测能力并重建后端（设置页保存后调用）。 */
    public synchronized void refresh() {
        releaseBackend();
        profile = CapabilityDetector.refresh(appContext);
        Log.i(TAG, "backend refreshed -> " + backendId());
    }

    /** 懒初始化后端。 */
    public synchronized EmbeddingBackend getBackend() {
        if (backend == null) {
            backend = BackendFactory.create(appContext, getProfile(), Settings.getSemanticBackend());
        }
        return backend;
    }

    public synchronized String backendId() {
        return getBackend().id();
    }

    /** 设置页展示：当前后端与模型的一句话摘要。 */
    public String backendSummary() {
        EmbeddingBackend current = getBackend();
        StringBuilder sb = new StringBuilder(current.id());
        String model = current.modelId();
        if (model != null && !model.isEmpty()) {
            sb.append(" · ").append(model);
        }
        sb.append(" · ").append(current.dimension()).append("d");
        if (!current.supportsImage()) {
            sb.append(" · 纯文本");
        }
        return sb.toString();
    }

    /**
     * 编码查询串。失败时自动降级一次（端侧 → 远程），仍失败则抛异常。
     */
    public float[] embedQuery(String query) throws EmbeddingException {
        try {
            return getBackend().embedQuery(query);
        } catch (EmbeddingException e) {
            if (!fallbackOnce(e)) {
                throw e;
            }
        }
        return getBackend().embedQuery(query);
    }

    /** 批量编码文档，失败同样降级一次。 */
    public List<float[]> embedDocuments(List<String> documents) throws EmbeddingException {
        try {
            return getBackend().embedDocuments(documents);
        } catch (EmbeddingException e) {
            if (!fallbackOnce(e)) {
                throw e;
            }
        }
        return getBackend().embedDocuments(documents);
    }

    /**
     * 触发一次降级：释放当前后端并强制切到远程。
     *
     * @return 是否成功降级
     */
    private boolean fallbackOnce(EmbeddingException error) {
        String suggested = BackendFactory.markDegraded(getBackend(), error);
        if (suggested == null) {
            return false;
        }
        synchronized (this) {
            releaseBackend();
            Settings.putSemanticBackend(SemanticConfig.BACKEND_REMOTE);
        }
        return SemanticConfig.BACKEND_REMOTE.equals(suggested);
    }

    /**
     * 远程后端连通性自检（设置页「测试远程」按钮）。
     *
     * @return 实际维度
     */
    public int probeRemote() throws EmbeddingException {
        RemoteEmbeddingBackend remote = new RemoteEmbeddingBackend(
                Settings.getSemanticRemoteUrl(),
                Settings.getSemanticRemoteKey(),
                Settings.getSemanticRemoteModel(),
                Settings.getSemanticRemoteProtocol(),
                Settings.getSemanticDim());
        try {
            return remote.probe();
        } finally {
            remote.close();
        }
    }

    private void releaseBackend() {
        if (backend != null) {
            try {
                backend.close();
            } catch (Throwable ignored) {
                // ignore
            }
            backend = null;
        }
    }

    /** 释放后端资源（可由 App 生命周期钩子调用）。 */
    public synchronized void shutdown() {
        releaseBackend();
        profile = null;
    }
}