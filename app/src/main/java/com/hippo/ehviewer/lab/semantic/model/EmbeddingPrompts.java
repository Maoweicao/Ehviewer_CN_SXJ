package com.hippo.ehviewer.lab.semantic.model;

import com.hippo.ehviewer.lab.semantic.SemanticConfig;

/**
 * EmbeddingGemma 2 的任务前缀（task steering）。
 *
 * <p>官方说明查询与文档必须使用不同前缀，且必须对索引与查询保持一致，
 * 否则召回质量会明显下降，因此这里集中管理，禁止在别处硬编码。
 */
public final class EmbeddingPrompts {

    private EmbeddingPrompts() {
    }

    /** 查询串：用于搜索入口输入。 */
    public static String query(String text) {
        return "task: search result | query: " + safe(text);
    }

    /** 文档串：用于索引标题 / 标签 / 简介。 */
    public static String document(String text) {
        return "title: none | text: " + safe(text);
    }

    /** 句子相似度：查询与文档两侧都使用同一前缀。 */
    public static String similarity(String text) {
        return "task: sentence similarity | text: " + safe(text);
    }

    private static String safe(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= SemanticConfig.MAX_DOC_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, SemanticConfig.MAX_DOC_CHARS);
    }
}