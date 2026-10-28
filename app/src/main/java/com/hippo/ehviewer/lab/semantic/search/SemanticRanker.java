package com.hippo.ehviewer.lab.semantic.search;

import android.util.Log;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.lab.semantic.SemanticConfig;
import com.hippo.ehviewer.lab.semantic.SemanticSearchManager;
import com.hippo.ehviewer.lab.semantic.backend.EmbeddingException;
import com.hippo.ehviewer.lab.semantic.index.DocumentComposer;
import com.hippo.ehviewer.lab.semantic.index.SemanticVectorStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 关键词 + 语义双路召回与混合排序。
 *
 * <p>关键词通道保留原有子串匹配行为（精确、快、零成本），语义通道补上跨语言与近义匹配，
 * 最终按 {@link Settings#getSemanticBlend()} 加权融合。这样即使模型未安装或推理失败，
 * 搜索结果也不会退化为空——语义通道失败时直接退化为纯关键词结果。
 */
public class SemanticRanker {

    private static final String TAG = "SemanticRanker";

    /** 相似度阈值：低于该值视为噪声，不进入结果。 */
    private static final float MIN_SCORE = 0.15f;

    /** 一条召回结果。 */
    public static final class Result {
        public final long gid;
        public final float score;
        public final boolean semanticHit;

        Result(long gid, float score, boolean semanticHit) {
            this.gid = gid;
            this.score = score;
            this.semanticHit = semanticHit;
        }
    }

    private SemanticRanker() {
    }

    /**
     * 在给定候选集内做混合排序。
     *
     * @param candidates 候选画廊
     * @param query 用户输入
     * @param limit 最多返回条数
     */
    public static List<Result> search(List<? extends GalleryInfo> candidates, String query, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            return new ArrayList<>();
        }
        String lower = keyword.toLowerCase(Locale.ROOT);

        // 关键词通道
        Map<Long, Float> keywordScores = new LinkedHashMap<>();
        for (GalleryInfo info : candidates) {
            if (DocumentComposer.matchesKeyword(info, lower)) {
                keywordScores.put(info.gid, 1f);
            }
        }

        // 语义通道
        Map<Long, Float> semanticScores = semanticScores(keyword, limit * 4);

        return merge(candidates, keywordScores, semanticScores, limit);
    }

    /**
     * 仅按语义检索，返回 gid → 相似度（已按相似度降序）。
     *
     * <p>供 EhDB 等既有搜索入口做「补充召回」：先用原有 LIKE 查出精确结果，
     * 再用这里的结果补上语义相近但字面不匹配的条目。
     */
    public static List<ScoredGallery> semanticSearch(String query, int limit) {
        Map<Long, Float> scores = semanticScores(query == null ? "" : query.trim(), limit);
        List<ScoredGallery> list = new ArrayList<>(scores.size());
        for (Map.Entry<Long, Float> entry : scores.entrySet()) {
            list.add(new ScoredGallery(entry.getKey(), entry.getValue()));
        }
        list.sort((a, b) -> Float.compare(b.score, a.score));
        return list;
    }

    /** 语义召回条目。 */
    public static final class ScoredGallery {
        public final long gid;
        public final float score;

        ScoredGallery(long gid, float score) {
            this.gid = gid;
            this.score = score;
        }
    }

    /** 执行语义检索，返回 gid → 相似度。失败时返回空表。 */
    private static Map<Long, Float> semanticScores(String query, int limit) {
        Map<Long, Float> result = new HashMap<>();
        if (!SemanticSearchManager.isEnabled() || query.isEmpty()) {
            return result;
        }
        SemanticSearchManager manager = SemanticSearchManager.getInstance(EhApplication.getInstance());
        try {
            float[] vector = manager.embedQuery(query);
            SemanticVectorStore store = manager.getStore();
            List<SemanticVectorStore.Scored> scored = store.search(
                    SemanticConfig.KIND_GALLERY,
                    manager.getBackend().modelId(),
                    vector.length,
                    vector,
                    limit);
            for (SemanticVectorStore.Scored item : scored) {
                try {
                    long gid = Long.parseLong(item.key);
                    // 负相关直接丢弃，避免纯噪声条目挤占结果
                    if (item.score > MIN_SCORE) {
                        result.put(gid, item.score);
                    }
                } catch (NumberFormatException ignored) {
                    // 非数字 key（如标签）跳过
                }
            }
        } catch (EmbeddingException e) {
            Log.w(TAG, "semantic channel failed, fallback to keyword: " + e.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "semantic channel crash", t);
        }
        return result;
    }

    /**
     * 融合两路结果。
     *
     * <p>语义分已归一化到 [0,1]，关键词命中给固定基分；命中两路的条目额外加权，
     * 保证「既匹配字面又语义相近」的条目排在最前。
     */
    static List<Result> merge(List<? extends GalleryInfo> candidates,
                              Map<Long, Float> keywordScores,
                              Map<Long, Float> semanticScores,
                              int limit) {
        float blend = Settings.getSemanticBlend();
        Map<Long, Float> fused = new HashMap<>();

        for (GalleryInfo info : candidates) {
            if (info == null) {
                continue;
            }
            Float kw = keywordScores.get(info.gid);
            Float sem = semanticScores.get(info.gid);
            if (kw == null && sem == null) {
                continue;
            }
            float score = 0f;
            if (sem != null) {
                score += blend * sem;
            }
            if (kw != null) {
                // 关键词通道占剩余权重，并给基分，避免语义分普遍偏低时被淹没
                score += (1f - blend) * kw + 0.05f;
            }
            if (kw != null && sem != null) {
                score += 0.1f;
            }
            fused.put(info.gid, score);
        }

        List<Result> results = new ArrayList<>(fused.size());
        for (Map.Entry<Long, Float> entry : fused.entrySet()) {
            results.add(new Result(entry.getKey(), entry.getValue(),
                    semanticScores.containsKey(entry.getKey())));
        }
        results.sort((a, b) -> Float.compare(b.score, a.score));

        return results.size() > limit ? new ArrayList<>(results.subList(0, limit)) : results;
    }
}