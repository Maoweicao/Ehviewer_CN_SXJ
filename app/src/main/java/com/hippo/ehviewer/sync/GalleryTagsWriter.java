/*
 * Copyright 2025 EhViewer Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.sync;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.ehviewer.dao.GalleryTags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把 `namespace:value` 形式的标签写进 {@code Gallery_Tags}。
 *
 * <p>排除规则 / 标签关键词搜索 / 「无标签」筛选 / 标签联想都只认这张表，
 * 而历史上只有「打开画廊详情页」（{@code GalleryDetailTagsSyncTask}）会写它，
 * 于是列表页/下载时拿到的标签全丢了。这里提供统一的写入入口：
 *
 * <ul>
 *   <li>{@link #saveFromGalleryInfo} —— 下载入库、列表页解析、修复画廊后调用；</li>
 *   <li>{@link #save} —— 已有标签集合时调用。</li>
 * </ul>
 *
 * <p>写入策略：行已存在时按 namespace <b>合并</b>（并集），不覆盖已有数据 ——
 * 列表页拿到的标签可能是子集，详情页刷新仍走 {@code EhDB.updateGalleryTags} 做整体替换。
 * 值统一规范化为小写、`+` 转空格，与 {@code FilterMatcher} 的匹配口径一致，
 * 便于去重（读取侧本来也会再规范化一次）。
 */
public final class GalleryTagsWriter {

    /** 与 {@code Gallery_Tags} 的列一一对应的 namespace。 */
    private static final String[] NAMESPACES = {
            "rows", "artist", "cosplayer", "character", "female", "group",
            "language", "male", "misc", "mixed", "other", "parody", "reclass", "location",
    };

    private GalleryTagsWriter() {
    }

    /**
     * 取条目当前携带的标签 token，没有则返回 null。
     *
     * <p>详情页（{@link GalleryDetail}）的标签在 {@code tags}（按 namespace 分组的
     * {@link GalleryTagGroup}）里，而 {@code simpleTags}/{@code tgList} 常常是空的，
     * 所以优先用分组；列表页则只有 simpleTags/tgList。
     */
    @Nullable
    public static List<String> tagsOf(@Nullable GalleryInfo info) {
        if (info == null) {
            return null;
        }
        if (info instanceof GalleryDetail) {
            List<String> grouped = tagsOfGroups(((GalleryDetail) info).tags);
            if (!grouped.isEmpty()) {
                return grouped;
            }
        }
        if (info.simpleTags != null && info.simpleTags.length > 0) {
            return Arrays.asList(info.simpleTags);
        }
        if (info.tgList != null && !info.tgList.isEmpty()) {
            return new ArrayList<>(info.tgList);
        }
        return null;
    }

    /** 把详情页的 tag group 摊平成 `namespace:value`（与列表页 simpleTags 同格式）。 */
    public static List<String> tagsOfGroups(@Nullable GalleryTagGroup[] groups) {
        List<String> tags = new ArrayList<>();
        if (groups == null) {
            return tags;
        }
        for (GalleryTagGroup group : groups) {
            if (group == null || group.groupName == null) {
                continue;
            }
            for (int i = 0, n = group.size(); i < n; i++) {
                String value = group.getTagAt(i);
                if (value == null || value.isEmpty()) {
                    continue;
                }
                tags.add(group.groupName + ":" + value);
            }
        }
        return tags;
    }

    /**
     * 把条目携带的标签持久化到 {@code Gallery_Tags}。
     *
     * @return 是否真的写入（无标签 / 无变化 / 失败时为 false）
     */
    public static boolean saveFromGalleryInfo(@Nullable GalleryInfo info) {
        if (info == null) {
            return false;
        }
        return save(info.gid, tagsOf(info));
    }

    /**
     * 把 `namespace:value` 标签集合持久化到 {@code Gallery_Tags}。
     *
     * @return 是否真的写入
     */
    public static boolean save(long gid, @Nullable Collection<String> tags) {
        if (gid <= 0 || tags == null || tags.isEmpty()) {
            return false;
        }
        Map<String, Set<String>> incoming = group(tags);
        if (incoming.isEmpty()) {
            return false;
        }
        try {
            GalleryTags row = EhDB.queryGalleryTags(gid);
            boolean exists = row != null;
            if (row == null) {
                row = new GalleryTags(gid);
            }
            if (!merge(row, incoming)) {
                return false;
            }
            if (exists) {
                EhDB.updateGalleryTags(row);
            } else {
                EhDB.insertGalleryTags(row);
            }
            return true;
        } catch (Throwable e) {
            // 标签写入失败只影响筛选/搜索质量，不能影响下载本身
            return false;
        }
    }

    /** 解析并分组：namespace -> 规范化后的值集合（丢弃无 namespace 或未知 namespace 的 token）。 */
    private static Map<String, Set<String>> group(Collection<String> tags) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            int idx = tag.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String namespace = tag.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            if (!isNamespace(namespace)) {
                continue;
            }
            String value = normalize(tag.substring(idx + 1));
            if (value.isEmpty()) {
                continue;
            }
            Set<String> values = map.get(namespace);
            if (values == null) {
                values = new LinkedHashSet<>();
                map.put(namespace, values);
            }
            values.add(value);
        }
        return map;
    }

    /** 把新值并进行里；返回是否有变化。 */
    private static boolean merge(GalleryTags row, Map<String, Set<String>> incoming) {
        boolean changed = false;
        for (Map.Entry<String, Set<String>> entry : incoming.entrySet()) {
            String namespace = entry.getKey();
            Set<String> values = new LinkedHashSet<>();
            String existing = columnOf(row, namespace);
            if (existing != null && !existing.isEmpty()) {
                for (String old : existing.split(",")) {
                    String normalized = normalize(old);
                    if (!normalized.isEmpty()) {
                        values.add(normalized);
                    }
                }
            }
            int before = values.size();
            values.addAll(entry.getValue());
            if (values.size() != before) {
                setColumn(row, namespace, join(values));
                changed = true;
            }
        }
        return changed;
    }

    private static String join(Set<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private static boolean isNamespace(String namespace) {
        for (String item : NAMESPACES) {
            if (item.equals(namespace)) {
                return true;
            }
        }
        return false;
    }

    /** 与 FilterMatcher 一致：去空白、小写、e-hentai 标签里的 `+` 表示空格。 */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('+', ' ');
    }

    @Nullable
    private static String columnOf(GalleryTags row, String namespace) {
        switch (namespace) {
            case "rows": return row.rows;
            case "artist": return row.artist;
            case "cosplayer": return row.cosplayer;
            case "character": return row.character;
            case "female": return row.female;
            case "group": return row.group;
            case "language": return row.language;
            case "male": return row.male;
            case "misc": return row.misc;
            case "mixed": return row.mixed;
            case "other": return row.other;
            case "parody": return row.parody;
            case "reclass": return row.reclass;
            case "location": return row.location;
            default: return null;
        }
    }

    private static void setColumn(GalleryTags row, String namespace, String value) {
        switch (namespace) {
            case "rows": row.rows = value; break;
            case "artist": row.artist = value; break;
            case "cosplayer": row.cosplayer = value; break;
            case "character": row.character = value; break;
            case "female": row.female = value; break;
            case "group": row.group = value; break;
            case "language": row.language = value; break;
            case "male": row.male = value; break;
            case "misc": row.misc = value; break;
            case "mixed": row.mixed = value; break;
            case "other": row.other = value; break;
            case "parody": row.parody = value; break;
            case "reclass": row.reclass = value; break;
            case "location": row.location = value; break;
            default: break;
        }
    }
}
