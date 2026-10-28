package com.hippo.ehviewer.lab.semantic.index;

import android.util.Log;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.GalleryTags;
import com.hippo.ehviewer.lab.semantic.SemanticConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 把画廊数据拼装成用于编码的文档文本。
 *
 * <p>组合了标题（中/日/英）、上传者与各命名空间标签。EmbeddingGemma 支持 100+ 语言的
 * 跨语言语义，因此「校园恋爱」这类中文查询可以命中标题为 school romance 的条目——
 * 这正是替换纯字面 LIKE 匹配的价值所在。
 */
public final class DocumentComposer {

    private static final String TAG = "DocumentComposer";

    private static final String[] TAG_NAMESPACES = {
            "female", "male", "other", "misc", "mixed", "reclass",
            "language", "group", "artist", "cosplayer", "character", "parody", "rows"
    };

    private DocumentComposer() {
    }

    /** 拼装单条画廊文档；无标题时返回 null（跳过索引）。 */
    public static String compose(GalleryInfo info, GalleryTags tags) {
        if (info == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(320);

        appendIfPresent(sb, info.title);
        appendIfPresent(sb, info.titleJpn);
        if (info.uploader != null && !info.uploader.isEmpty()) {
            sb.append("uploader: ").append(info.uploader).append('\n');
        }

        if (tags != null) {
            appendTags(sb, tags);
        }

        String doc = sb.toString().trim();
        if (doc.isEmpty()) {
            return null;
        }
        if (doc.length() > SemanticConfig.MAX_DOC_CHARS) {
            doc = doc.substring(0, SemanticConfig.MAX_DOC_CHARS);
        }
        return doc;
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        // 去重：英文标题常与日文标题相同
        String existing = sb.toString();
        if (existing.contains(trimmed)) {
            return;
        }
        sb.append(trimmed).append('\n');
    }

    private static void appendTags(StringBuilder sb, GalleryTags tags) {
        for (String namespace : TAG_NAMESPACES) {
            String raw = readNamespace(tags, namespace);
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            sb.append(namespace).append(": ").append(raw.trim()).append('\n');
        }
    }

    private static String readNamespace(GalleryTags tags, String namespace) {
        try {
            switch (namespace) {
                case "rows": return tags.rows;
                case "artist": return tags.artist;
                case "cosplayer": return tags.cosplayer;
                case "character": return tags.character;
                case "female": return tags.female;
                case "group": return tags.group;
                case "language": return tags.language;
                case "male": return tags.male;
                case "misc": return tags.misc;
                case "mixed": return tags.mixed;
                case "other": return tags.other;
                case "parody": return tags.parody;
                case "reclass": return tags.reclass;
                default: return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 提取标签名列表（去空、去重），供标签联想索引使用。
     * 词库为逗号分隔的裸标签名。
     */
    public static List<String> extractTagNames(GalleryTags tags) {
        List<String> result = new ArrayList<>();
        if (tags == null) {
            return result;
        }
        for (String namespace : TAG_NAMESPACES) {
            String raw = readNamespace(tags, namespace);
            if (raw == null) {
                continue;
            }
            for (String item : raw.split(",")) {
                String tag = item.trim();
                if (!tag.isEmpty() && !result.contains(tag)) {
                    result.add(tag);
                }
            }
        }
        return result;
    }

    /** 供本地库搜索用：仅标题命中判断（用于混合排序中的关键词通道）。 */
    public static boolean matchesKeyword(GalleryInfo info, String lowerKeyword) {
        if (info == null || lowerKeyword == null || lowerKeyword.isEmpty()) {
            return false;
        }
        return containsIgnoreCase(info.title, lowerKeyword)
                || containsIgnoreCase(info.titleJpn, lowerKeyword)
                || containsIgnoreCase(info.uploader, lowerKeyword);
    }

    private static boolean containsIgnoreCase(String value, String lowerKeyword) {
        if (value == null) {
            return false;
        }
        return value.toLowerCase(java.util.Locale.ROOT).contains(lowerKeyword);
    }

    static void logSkipped(String reason) {
        Log.d(TAG, "skip document: " + reason);
    }
}