package com.hippo.ehviewer.lab.semantic.model;

import com.hippo.ehviewer.lab.semantic.SemanticConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端侧模型清单。
 *
 * <p>默认使用纯文本 270M 版（Q4_K_XL 约 176MB），它在红米 K50 Pro / 拯救者 Y700
 * 这类 2022 旗舰 SoC 上单次查询编码约 50~150ms，内存占用约 350~450MB。
 * 多模态 740M 版体积约 500MB、内存峰值接近 1GB，仅在增强档开放。
 */
public final class ModelCatalog {

    private static final Map<String, ModelSpec> MODELS = new LinkedHashMap<>();

    /** 默认推荐（标准档通用）。 */
    public static final String DEFAULT_TEXT_MODEL = "embeddinggemma2-text-270m-q4";
    /** 增强档多模态。 */
    public static final String DEFAULT_MULTIMODAL_MODEL = "embeddinggemma2-740m-q4";

    static {
        add(new ModelSpec(
                DEFAULT_TEXT_MODEL,
                "EmbeddingGemma 2 Text 270M (Q4_K)",
                "unsloth/embeddinggemma-2-GGUF",
                "embeddinggemma-2-UD-Q4_K_XL.gguf",
                "270M text",
                176L * 1024 * 1024,
                false,
                SemanticConfig.TIER_STANDARD,
                "纯文本，多语言，适合语义搜索与标签联想"));

        add(new ModelSpec(
                DEFAULT_MULTIMODAL_MODEL,
                "EmbeddingGemma 2 740M Multimodal (Q4_K)",
                "unsloth/embeddinggemma-2-GGUF",
                "embeddinggemma-2-UD-Q4_K_XL-F16.gguf",
                "740M text+vision+audio",
                500L * 1024 * 1024,
                true,
                SemanticConfig.TIER_ENHANCED,
                "全模态，用于以图搜图；仅增强档开放"));

        add(new ModelSpec(
                "embeddinggemma2-text-270m-q8",
                "EmbeddingGemma 2 Text 270M (Q8_0)",
                "unsloth/embeddinggemma-2-GGUF",
                "embeddinggemma-2-Q8_0.gguf",
                "270M text",
                290L * 1024 * 1024,
                false,
                SemanticConfig.TIER_ENHANCED,
                "更高精度，体积与内存开销更大"));
    }

    private ModelCatalog() {
    }

    private static void add(ModelSpec spec) {
        MODELS.put(spec.id, spec);
    }

    public static ModelSpec get(String id) {
        ModelSpec spec = MODELS.get(id);
        if (spec != null) {
            return spec;
        }
        return MODELS.get(DEFAULT_TEXT_MODEL);
    }

    /** 列出适配当前档位的候选模型。 */
    public static List<ModelSpec> listForTier(String tier) {
        List<ModelSpec> result = new ArrayList<>();
        for (ModelSpec spec : MODELS.values()) {
            if (spec.multimodal && !SemanticConfig.TIER_ENHANCED.equals(tier)) {
                continue;
            }
            result.add(spec);
        }
        return result;
    }

    public static List<ModelSpec> all() {
        return new ArrayList<>(MODELS.values());
    }
}