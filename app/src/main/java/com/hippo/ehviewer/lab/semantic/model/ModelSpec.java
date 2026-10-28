package com.hippo.ehviewer.lab.semantic.model;

/**
 * 一个可下载的端侧模型清单项。
 */
public class ModelSpec {

    public final String id;
    public final String displayName;
    /** HF 仓库路径，例如 unsloth/embeddinggemma-2-GGUF。 */
    public final String repo;
    /** 仓库内的文件名。 */
    public final String fileName;
    /** 参数量描述，如 270M text。 */
    public final String params;
    /** 约化体积（字节），用于下载前提示。 */
    public final long approxSizeBytes;
    /** 是否为多模态模型（含视觉/音频编码器）。 */
    public final boolean multimodal;
    /** 建议的最小内存档位（{@link com.hippo.ehviewer.lab.semantic.SemanticConfig#TIER_STANDARD} 等）。 */
    public final String requiredTier;
    /** 备注。 */
    public final String notes;

    public ModelSpec(String id, String displayName, String repo, String fileName, String params,
                     long approxSizeBytes, boolean multimodal, String requiredTier, String notes) {
        this.id = id;
        this.displayName = displayName;
        this.repo = repo;
        this.fileName = fileName;
        this.params = params;
        this.approxSizeBytes = approxSizeBytes;
        this.multimodal = multimodal;
        this.requiredTier = requiredTier;
        this.notes = notes;
    }

    /** 官方源直链。 */
    public String officialUrl() {
        return "https://huggingface.co/" + repo + "/resolve/main/" + fileName;
    }

    /** 国内镜像源（hf-mirror），主源失败时自动切换。 */
    public String mirrorUrl() {
        return "https://hf-mirror.com/" + repo + "/resolve/main/" + fileName;
    }

    /** 安装后的本地文件名。 */
    public String localName() {
        return id + ".gguf";
    }

    public String sizeText() {
        return String.format(java.util.Locale.US, "%.0f MB", approxSizeBytes / (1024f * 1024f));
    }
}