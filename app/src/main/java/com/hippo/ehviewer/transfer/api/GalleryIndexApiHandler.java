/*
 * Copyright 2026 EhViewer Contributors
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

package com.hippo.ehviewer.transfer.api;

import android.content.Context;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.dao.LocalFavoriteInfo;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.DataChangeBus;
import com.hippo.ehviewer.transfer.index.BucketLayout;
import com.hippo.ehviewer.transfer.index.GalleryIndexStore;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import fi.iki.elonen.NanoHTTPD;

/**
 * 画廊索引与分区位图 API（协议 §5.29）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/sync/gallery-index/scopes} 列表级索引；</li>
 *   <li>{@code GET /api/v1/sync/gallery-index} 桶级位图清单；</li>
 *   <li>{@code GET /api/v1/sync/gallery-index/buckets/{bucket}} 桶内行（GID 升序 keyset）。</li>
 * </ul>
 */
public class GalleryIndexApiHandler extends BaseApiHandler {

    private static final String TAG = "GalleryIndexApi";

    private static final String PATH_SCOPES = "/api/v1/sync/gallery-index/scopes";
    private static final String PATH_MANIFEST = "/api/v1/sync/gallery-index";
    private static final String PATH_BUCKETS = "/api/v1/sync/gallery-index/buckets/";

    private static final String SCOPE_DOWNLOADS = "downloads";
    private static final String SCOPE_FAVORITES = "favorites";
    private static final String SCOPE_LABEL = "label";
    private static final String DEFAULT_LABEL = "默认";

    private static final int DEFAULT_SCOPE_LIMIT = 200;
    private static final int MAX_SCOPE_LIMIT = 500;
    private static final int DEFAULT_BUCKET_LIMIT = 500;
    private static final int MAX_EXPANDED_BUCKETS = 512;

    /** 构建清单前的堆余量门槛，低于此值直接返回 503（协议 §5.1.3/§5.29.7）。 */
    private static final long INDEX_HEAP_HEADROOM_BYTES = 24L * 1024 * 1024;

    private static final String[] CATEGORY_NAMES = {
            "Misc", "Doujinshi", "Manga", "Artist CG", "Game CG",
            "Image Set", "Cosplay", "Asian Porn", "Non-H", "Western"
    };

    private static final SerializerFeature[] JSON_FEATURES = {
            SerializerFeature.WriteMapNullValue
    };

    private final GalleryIndexStore store;

    public GalleryIndexApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
        this.store = GalleryIndexStore.get(context);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri, session);
        String path = uri.split("\\?")[0];

        if (PATH_SCOPES.equals(path)) {
            return handleScopes(session);
        }
        if (PATH_MANIFEST.equals(path)) {
            return handleManifest(session);
        }
        if (path.startsWith(PATH_BUCKETS)) {
            String raw = path.substring(PATH_BUCKETS.length());
            if (!raw.matches("\\d{1,12}")) {
                return ResponseBuilder.notFound("Bucket");
            }
            long bucket;
            try {
                bucket = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return ResponseBuilder.notFound("Bucket");
            }
            if (bucket > Integer.MAX_VALUE) {
                return indexError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "bucket out of range", "INDEX_BUCKET_OUT_OF_RANGE");
            }
            return handleBucket(session, (int) bucket);
        }
        return ResponseBuilder.notFound("Endpoint");
    }

    // ==================== 5.29.3 scopes ====================

    private NanoHTTPD.Response handleScopes(NanoHTTPD.IHTTPSession session) {
        try {
            boolean sinceGiven = RequestParser.getQueryParameter(session, "since") != null;
            long since = RequestParser.getLongQueryParameter(session, "since", 0L);
            boolean digest = !isOff(RequestParser.getQueryParameter(session, "digest", "1"));
            boolean includeEmpty = !isOff(RequestParser.getQueryParameter(session, "includeEmpty", "1"));
            String labelFilter = trimToNull(RequestParser.getQueryParameter(session, "label"));
            int limit = clamp(RequestParser.getIntQueryParameter(session, "limit", DEFAULT_SCOPE_LIMIT),
                    1, MAX_SCOPE_LIMIT);
            int offset = decodeCursor(RequestParser.getQueryParameter(session, "cursor"));
            Set<Integer> states = parseStates(RequestParser.getQueryParameter(session, "state"));
            String stateKey = stateKey(states);

            List<DownloadInfo> downloads = downloadManager.getAllDownloadInfoSnapshot();
            List<Long> downloadGids = new ArrayList<>();
            Map<String, List<Long>> labelGids = new LinkedHashMap<>();
            for (DownloadInfo info : downloads) {
                if (info == null || info.gid <= 0 || !stateMatches(states, info.state)) {
                    continue;
                }
                downloadGids.add(info.gid);
                String label = labelOf(info);
                List<Long> list = labelGids.get(label);
                if (list == null) {
                    list = new ArrayList<>();
                    labelGids.put(label, list);
                }
                list.add(info.gid);
            }
            Collections.sort(downloadGids);
            for (List<Long> list : labelGids.values()) {
                Collections.sort(list);
            }

            List<Long> favoriteGids = loadFavoriteGids(states);
            Collections.sort(favoriteGids);

            List<JSONObject> entries = new ArrayList<>();
            entries.add(scopeEntry(SCOPE_DOWNLOADS, null, downloadGids, digest, sinceGiven, since, stateKey));
            entries.add(scopeEntry(SCOPE_FAVORITES, null, favoriteGids, digest, sinceGiven, since, stateKey));

            for (String label : collectLabelNames(labelFilter, labelGids)) {
                List<Long> gids = labelGids.get(label);
                if (gids == null) {
                    gids = new ArrayList<>();
                }
                if (!includeEmpty && gids.isEmpty()) {
                    continue;
                }
                entries.add(scopeEntry(SCOPE_LABEL, label, gids, digest, sinceGiven, since, stateKey));
            }

            int from = Math.min(Math.max(0, offset), entries.size());
            int to = Math.min(from + limit, entries.size());
            JSONArray scopesJson = new JSONArray();
            for (int i = from; i < to; i++) {
                scopesJson.add(entries.get(i));
            }

            JSONObject response = new JSONObject(true);
            response.put("epoch", store.getEpoch());
            response.put("version", DataChangeBus.getVersion());
            response.put("since", sinceGiven ? since : null);
            response.put("bucketSize", store.getBucketSize());
            response.put("supportsIncremental", true);
            response.put("scopes", scopesJson);
            response.put("nextCursor", to < entries.size() ? encodeCursor(to) : null);

            return json(response);
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "Error listing index scopes", t);
            return ResponseBuilder.internalError(t.getMessage());
        }
    }

    private JSONObject scopeEntry(String scope, String label, List<Long> gids, boolean withDigest,
                                  boolean sinceGiven, long since, String stateKey) {
        String key = storeKey(scope, label, stateKey);
        long version = store.getScopeVersion(key);
        JSONObject entry = new JSONObject(true);
        entry.put("scope", scope);
        entry.put("label", label);
        entry.put("count", gids.size());
        entry.put("digest", withDigest ? digestOf(gids) : null);
        entry.put("version", version);
        // version=0 表示该列表从未构建过清单（例如 since=0 的首次同步），
        // 此时不能因为 0>0 不成立就告诉客户端"没有变化"。
        entry.put("changed", !sinceGiven || version <= 0 || version > since);
        return entry;
    }

    private List<Long> loadFavoriteGids(Set<Integer> states) {
        List<Long> gids = new ArrayList<>();
        List<GalleryInfo> favorites;
        try {
            favorites = EhDB.getAllLocalFavorites();
        } catch (Throwable t) {
            TransferLogger.getInstance().w(TAG, "读取本地收藏失败: " + t.getMessage());
            return gids;
        }
        if (favorites == null) {
            return gids;
        }
        for (GalleryInfo info : favorites) {
            if (info == null || info.gid <= 0) {
                continue;
            }
            DownloadInfo download = downloadManager.getDownloadInfo(info.gid);
            int state = download != null ? download.state : DownloadInfo.STATE_NONE;
            if (stateMatches(states, state)) {
                gids.add(info.gid);
            }
        }
        return gids;
    }

    /** 标签 scope 的顺序：默认标签优先，其后按下载标签列表顺序；labelFilter 只保留指定标签。 */
    private List<String> collectLabelNames(String labelFilter, Map<String, List<Long>> labelGids) {
        if (labelFilter != null) {
            return Collections.singletonList(labelFilter);
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(DEFAULT_LABEL);
        try {
            List<DownloadLabel> labels = EhDB.getAllDownloadLabelList();
            if (labels != null) {
                for (DownloadLabel label : labels) {
                    if (label != null && label.getLabel() != null && !label.getLabel().isEmpty()) {
                        names.add(label.getLabel());
                    }
                }
            }
        } catch (Throwable t) {
            TransferLogger.getInstance().w(TAG, "读取下载标签失败: " + t.getMessage());
        }
        // 数据里出现但标签表缺失的名称也补上，避免客户端看不到内容
        names.addAll(labelGids.keySet());
        return new ArrayList<>(names);
    }

    // ==================== 5.29.4 manifest ====================

    private NanoHTTPD.Response handleManifest(NanoHTTPD.IHTTPSession session) {
        try {
            String scope = trimToNull(RequestParser.getQueryParameter(session, "scope"));
            if (scope == null) {
                return indexError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "invalid scope", "INDEX_SCOPE_INVALID");
            }
            String label = trimToNull(RequestParser.getQueryParameter(session, "label"));
            if (SCOPE_LABEL.equals(scope) && label == null) {
                return indexError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "scope=label requires label", "INDEX_SCOPE_INVALID");
            }
            if (!SCOPE_DOWNLOADS.equals(scope) && !SCOPE_FAVORITES.equals(scope) && !SCOPE_LABEL.equals(scope)) {
                return indexError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "invalid scope", "INDEX_SCOPE_INVALID");
            }

            int bucketSizeParam = RequestParser.getIntQueryParameter(session, "bucketSize", -1);
            if (bucketSizeParam > 0 && bucketSizeParam != store.getBucketSize()) {
                return layoutMismatch();
            }
            if (!hasHeapHeadroom()) {
                return ResponseBuilder.serviceUnavailable();
            }

            boolean sinceGiven = RequestParser.getQueryParameter(session, "since") != null;
            long since = RequestParser.getLongQueryParameter(session, "since", 0L);
            String stateRaw = RequestParser.getQueryParameter(session, "state", "all");
            Set<Integer> states = parseStates(stateRaw);
            String stateKey = stateKey(states);
            String scopeKey = storeKey(scope, label, stateKey);

            String bitmapsParam = RequestParser.getQueryParameter(session, "bitmaps", "presence,changed");
            boolean includePresence = true;
            boolean includeChanged = true;
            if (bitmapsParam != null) {
                String normalized = bitmapsParam.trim().toLowerCase(Locale.US);
                if ("none".equals(normalized)) {
                    includePresence = false;
                    includeChanged = false;
                } else {
                    includePresence = normalized.contains("presence");
                    includeChanged = normalized.contains("changed");
                }
            }
            boolean withCounts = "1".equals(RequestParser.getQueryParameter(session, "counts", "0"))
                    || "true".equalsIgnoreCase(RequestParser.getQueryParameter(session, "counts", "0"));
            String fingerprintMode = RequestParser.getQueryParameter(session, "fingerprints", "0");
            if (fingerprintMode == null) {
                fingerprintMode = "0";
            }
            fingerprintMode = fingerprintMode.trim().toLowerCase(Locale.US);
            String[] fpFields = parseFpFields(RequestParser.getQueryParameter(session, "fp", "gid"));
            boolean expand = "changed".equalsIgnoreCase(RequestParser.getQueryParameter(session, "expand", ""));

            long startVersion = DataChangeBus.getVersion();
            List<Row> rows = loadRows(scope, label, states);
            TreeMap<Integer, List<Row>> buckets = groupByBucket(rows);
            TreeMap<Integer, String> currentFps = new TreeMap<>();
            for (Map.Entry<Integer, List<Row>> entry : buckets.entrySet()) {
                currentFps.put(entry.getKey(), internalFingerprint(entry.getValue()));
            }

            GalleryIndexStore.ScopeUpdate update = store.updateScope(scopeKey, currentFps, startVersion);

            boolean empty = rows.isEmpty();
            int baseBucket = empty ? 0 : update.baseBucket;
            int bitCount = empty ? 0 : update.bitCount;

            Set<Integer> presence = new TreeSet<>(buckets.keySet());
            TreeSet<Integer> changed = sinceGiven ? update.changedBuckets(since) : null;
            if (changed == null) {
                changed = new TreeSet<>();
            }
            if (empty) {
                changed.clear();
            } else {
                changed.retainAll(bucketRange(baseBucket, bitCount));
            }

            JSONObject response = new JSONObject(true);
            response.put("epoch", store.getEpoch());
            response.put("version", update.globalVersion);
            response.put("since", sinceGiven ? since : null);
            response.put("sinceApplied", sinceGiven);
            response.put("sinceStale", sinceGiven && since > update.globalVersion);
            response.put("scope", scope);
            response.put("label", label);
            response.put("state", stateRaw == null ? "all" : stateRaw);
            response.put("bucketSize", store.getBucketSize());
            response.put("baseBucket", baseBucket);
            response.put("bitCount", bitCount);
            response.put("count", rows.size());
            response.put("digest", digestOf(gidsOf(rows)));
            response.put("supportsIncremental", true);
            response.put("snapshotConsistent", update.snapshotConsistent);

            JSONObject bitmaps = null;
            if (includePresence || includeChanged) {
                bitmaps = new JSONObject(true);
                bitmaps.put("encoding", "base64url");
                if (includePresence) {
                    bitmaps.put("presence", empty ? "" : BucketLayout.bitmap(baseBucket, bitCount, presence));
                }
                if (includeChanged) {
                    bitmaps.put("changed", empty || !sinceGiven
                            ? null
                            : BucketLayout.bitmap(baseBucket, bitCount, changed));
                }
            }
            response.put("bitmaps", bitmaps);
            response.put("counts", withCounts
                    ? BucketLayout.counts(countsArray(buckets, baseBucket, bitCount))
                    : null);

            TreeMap<Integer, String> fingerprints = null;
            if ("all".equals(fingerprintMode) || "changed".equals(fingerprintMode)) {
                fingerprints = new TreeMap<>();
                Set<Integer> targets = "all".equals(fingerprintMode) ? presence : changed;
                for (Integer bucket : targets) {
                    List<Row> bucketRows = buckets.get(bucket);
                    if (bucketRows != null && !bucketRows.isEmpty()) {
                        fingerprints.put(bucket, projectedFingerprint(bucketRows, fpFields));
                    }
                }
            }
            response.put("fingerprints", fingerprints == null ? null : fingerprintsJson(fingerprints));

            if (expand && changed.size() <= MAX_EXPANDED_BUCKETS) {
                JSONArray array = new JSONArray();
                for (Integer bucket : changed) {
                    array.add(bucket);
                }
                response.put("changedBuckets", array);
            } else {
                response.put("changedBuckets", null);
            }

            return json(response);
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "Error building index manifest", t);
            return ResponseBuilder.internalError(t.getMessage());
        }
    }

    // ==================== 5.29.5 buckets ====================

    private NanoHTTPD.Response handleBucket(NanoHTTPD.IHTTPSession session, int bucket) {
        try {
            int bucketSize = store.getBucketSize();
            if (!BucketLayout.isBucketInRange(bucket, bucketSize)) {
                return indexError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "bucket out of range", "INDEX_BUCKET_OUT_OF_RANGE");
            }
            int bucketSizeParam = RequestParser.getIntQueryParameter(session, "bucketSize", -1);
            if (bucketSizeParam > 0 && bucketSizeParam != bucketSize) {
                return layoutMismatch();
            }

            String scope = trimToNull(RequestParser.getQueryParameter(session, "scope"));
            if (scope == null) {
                // 协议示例省略 scope；为兼容旧文档默认按下载列表返回
                scope = SCOPE_DOWNLOADS;
            }
            if (!SCOPE_DOWNLOADS.equals(scope) && !SCOPE_FAVORITES.equals(scope) && !SCOPE_LABEL.equals(scope)) {
                return indexError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "invalid scope", "INDEX_SCOPE_INVALID");
            }
            String label = trimToNull(RequestParser.getQueryParameter(session, "label"));
            if (SCOPE_LABEL.equals(scope) && label == null) {
                return indexError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "scope=label requires label", "INDEX_SCOPE_INVALID");
            }
            Set<Integer> states = parseStates(RequestParser.getQueryParameter(session, "state"));
            String stateKey = stateKey(states);
            String scopeKey = storeKey(scope, label, stateKey);

            long cursor = RequestParser.getLongQueryParameter(session, "cursor", 0L);
            if (cursor < 0) {
                cursor = 0;
            }
            int limit = RequestParser.getIntQueryParameter(session, "limit", Math.min(DEFAULT_BUCKET_LIMIT, bucketSize));
            limit = clamp(limit, 1, Math.min(DEFAULT_BUCKET_LIMIT, bucketSize));
            String fields = RequestParser.getQueryParameter(session, "fields", "summary");
            if (fields == null) {
                fields = "summary";
            }
            fields = fields.trim().toLowerCase(Locale.US);
            if (!"ids".equals(fields) && !"fav".equals(fields) && !"full".equals(fields)) {
                fields = "summary";
            }
            long expectedVersion = RequestParser.getLongQueryParameter(session, "expectedVersion", 0L);

            List<Row> rows = loadRows(scope, label, states);
            List<Row> bucketRows = new ArrayList<>();
            for (Row row : rows) {
                if (BucketLayout.bucketOf(row.gid, bucketSize) == bucket) {
                    bucketRows.add(row);
                }
            }
            long version = store.refreshBucket(scopeKey, bucket,
                    bucketRows.isEmpty() ? null : internalFingerprint(bucketRows));
            boolean versionChanged = expectedVersion > 0 && expectedVersion != version;

            List<Row> pageRows = new ArrayList<>();
            Long nextCursor = null;
            if (!versionChanged) {
                int start = 0;
                if (cursor > 0) {
                    while (start < bucketRows.size() && bucketRows.get(start).gid <= cursor) {
                        start++;
                    }
                }
                int end = Math.min(start + limit, bucketRows.size());
                if (start < end) {
                    pageRows = bucketRows.subList(start, end);
                }
                if (end < bucketRows.size() && !pageRows.isEmpty()) {
                    nextCursor = pageRows.get(pageRows.size() - 1).gid;
                }
            }

            JSONObject response = new JSONObject(true);
            response.put("epoch", store.getEpoch());
            response.put("bucket", bucket);
            response.put("from", BucketLayout.bucketFrom(bucket, bucketSize));
            response.put("to", BucketLayout.bucketTo(bucket, bucketSize));
            response.put("count", bucketRows.size());
            response.put("version", version);
            response.put("versionChanged", versionChanged);
            response.put("fields", fields);
            if ("ids".equals(fields)) {
                JSONArray gids = new JSONArray();
                if (!versionChanged) {
                    for (Row row : pageRows) {
                        gids.add(row.gid);
                    }
                }
                response.put("gids", gids);
            } else {
                JSONArray galleries = new JSONArray();
                if (!versionChanged) {
                    for (Row row : pageRows) {
                        galleries.add(formatRow(row, fields));
                    }
                }
                response.put("galleries", galleries);
            }
            response.put("nextCursor", nextCursor == null ? null : String.valueOf(nextCursor));
            response.put("complete", !versionChanged && nextCursor == null);

            return json(response);
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "Error reading index bucket " + bucket, t);
            return ResponseBuilder.internalError(t.getMessage());
        }
    }

    // ==================== 数据读取 ====================

    private static final class Row {
        long gid;
        DownloadInfo download;
        GalleryInfo favorite;
        int pages;
        int state;
        long time;
        String label;
    }

    private List<Row> loadRows(String scope, String label, Set<Integer> states) {
        List<Row> rows = new ArrayList<>();
        if (SCOPE_FAVORITES.equals(scope)) {
            List<GalleryInfo> favorites;
            try {
                favorites = EhDB.getAllLocalFavorites();
            } catch (Throwable t) {
                TransferLogger.getInstance().w(TAG, "读取本地收藏失败: " + t.getMessage());
                favorites = null;
            }
            if (favorites != null) {
                for (GalleryInfo info : favorites) {
                    if (info == null || info.gid <= 0) {
                        continue;
                    }
                    DownloadInfo download = downloadManager.getDownloadInfo(info.gid);
                    int state = download != null ? download.state : DownloadInfo.STATE_NONE;
                    if (!stateMatches(states, state)) {
                        continue;
                    }
                    Row row = new Row();
                    row.gid = info.gid;
                    row.favorite = info;
                    row.download = download;
                    row.state = state;
                    row.pages = download != null && download.pages > 0 ? download.pages : info.pages;
                    if (info instanceof LocalFavoriteInfo) {
                        row.time = ((LocalFavoriteInfo) info).time;
                    } else if (download != null) {
                        row.time = download.time;
                    }
                    row.label = download != null ? labelOf(download) : "";
                    rows.add(row);
                }
            }
        } else {
            List<DownloadInfo> downloads = downloadManager.getAllDownloadInfoSnapshot();
            for (DownloadInfo info : downloads) {
                if (info == null || info.gid <= 0) {
                    continue;
                }
                if (SCOPE_LABEL.equals(scope) && !labelOf(info).equals(label)) {
                    continue;
                }
                if (!stateMatches(states, info.state)) {
                    continue;
                }
                Row row = new Row();
                row.gid = info.gid;
                row.download = info;
                row.state = info.state;
                row.pages = info.pages > 0 ? info.pages : info.total;
                row.time = info.time;
                row.label = labelOf(info);
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparingLong(row -> row.gid));
        return rows;
    }

    private static TreeMap<Integer, List<Row>> groupByBucket(List<Row> rows) {
        TreeMap<Integer, List<Row>> buckets = new TreeMap<>();
        int bucketSize = BucketLayout.DEFAULT_BUCKET_SIZE;
        for (Row row : rows) {
            int bucket = BucketLayout.bucketOf(row.gid, bucketSize);
            if (bucket < 0) {
                continue;
            }
            List<Row> list = buckets.get(bucket);
            if (list == null) {
                list = new ArrayList<>();
                buckets.put(bucket, list);
            }
            list.add(row);
        }
        return buckets;
    }

    private static String internalFingerprint(List<Row> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 24);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (i > 0) {
                sb.append(';');
            }
            sb.append(row.gid).append(':').append(row.pages).append(':').append(row.state);
        }
        return BucketLayout.fnv1a32(sb.toString());
    }

    private static String projectedFingerprint(List<Row> rows, String[] fields) {
        StringBuilder sb = new StringBuilder(rows.size() * 24);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (i > 0) {
                sb.append(';');
            }
            for (int j = 0; j < fields.length; j++) {
                if (j > 0) {
                    sb.append(':');
                }
                sb.append(fieldValue(row, fields[j]));
            }
        }
        return BucketLayout.fnv1a32(sb.toString());
    }

    private static String fieldValue(Row row, String field) {
        switch (field) {
            case "pages":
                return String.valueOf(row.pages);
            case "state":
                return String.valueOf(row.state);
            case "label":
                return row.label == null ? "" : row.label;
            case "time":
                return String.valueOf(row.time);
            case "title":
                return row.download != null && row.download.title != null ? row.download.title
                        : row.favorite != null && row.favorite.title != null ? row.favorite.title : "";
            case "token":
                return row.download != null && row.download.token != null ? row.download.token
                        : row.favorite != null && row.favorite.token != null ? row.favorite.token : "";
            case "gid":
            default:
                return String.valueOf(row.gid);
        }
    }

    private static String[] parseFpFields(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new String[]{"gid"};
        }
        String[] parts = raw.split(",");
        List<String> fields = new ArrayList<>();
        for (String part : parts) {
            String field = part.trim().toLowerCase(Locale.US);
            if (field.isEmpty()) {
                continue;
            }
            if ("gid".equals(field) || "pages".equals(field) || "state".equals(field)
                    || "label".equals(field) || "time".equals(field) || "title".equals(field)
                    || "token".equals(field)) {
                fields.add(field);
            }
        }
        return fields.isEmpty() ? new String[]{"gid"} : fields.toArray(new String[0]);
    }

    // ==================== JSON 输出 ====================

    private JSONObject formatRow(Row row, String fields) {
        JSONObject json = new JSONObject(true);
        json.put("gid", row.gid);
        if ("fav".equals(fields)) {
            json.put("favCat", 0);
            json.put("favNote", null);
            json.put("favTime", row.time);
            return json;
        }
        DownloadInfo download = row.download;
        GalleryInfo info = download != null ? download : row.favorite;
        if (info == null) {
            return json;
        }
        json.put("token", info.token);
        json.put("title", info.title);
        json.put("titleJpn", info.titleJpn);
        json.put("category", categoryName(info.category));
        json.put("categoryValue", info.category);
        json.put("pages", row.pages);
        json.put("state", row.state);
        json.put("size", download != null ? Math.max(0L, download.fileSize) : 0L);
        json.put("createdDate", formatTime(row.time));
        json.put("isComplete", row.state == DownloadInfo.STATE_FINISH);
        json.put("label", row.label == null || row.label.isEmpty() ? DEFAULT_LABEL : row.label);
        if ("full".equals(fields)) {
            json.put("posted", info.posted);
            json.put("uploader", info.uploader);
            json.put("rating", info.rating);
            json.put("language", info.simpleLanguage);
            json.put("time", row.time);
            json.put("createdTime", row.time);
            if (download != null) {
                json.put("finished", download.finished);
                json.put("downloaded", download.downloaded);
                json.put("total", download.total);
                json.put("fileSize", download.fileSize);
                json.put("downloadedPages", download.finished > 0 ? download.finished : download.downloaded);
            } else {
                json.put("downloadedPages", 0);
            }
            if (info.tgList != null && !info.tgList.isEmpty()) {
                JSONArray tags = new JSONArray();
                tags.addAll(info.tgList);
                json.put("tags", tags);
            } else if (info.simpleTags != null && info.simpleTags.length > 0) {
                List<Object> tags = new ArrayList<>();
                Collections.addAll(tags, info.simpleTags);
                json.put("tags", new JSONArray(tags));
            }
        }
        return json;
    }

    private JSONObject fingerprintsJson(TreeMap<Integer, String> map) {
        JSONObject object = new JSONObject(true);
        for (Map.Entry<Integer, String> entry : map.entrySet()) {
            object.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return object;
    }

    private static NanoHTTPD.Response json(JSONObject object) {
        return ResponseBuilder.jsonSuccess(JSON.toJSONString(object, JSON_FEATURES));
    }

    // ==================== 参数与工具 ====================

    private static Set<Integer> parseStates(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || "all".equalsIgnoreCase(value)) {
            return null;
        }
        Set<Integer> states = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            try {
                states.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return states.isEmpty() ? null : states;
    }

    private static boolean stateMatches(Set<Integer> states, int state) {
        return states == null || states.contains(state);
    }

    private static String stateKey(Set<Integer> states) {
        if (states == null || states.isEmpty()) {
            return "all";
        }
        TreeSet<Integer> sorted = new TreeSet<>(states);
        StringBuilder sb = new StringBuilder();
        for (Integer state : sorted) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(state);
        }
        return sb.toString();
    }

    private static String storeKey(String scope, String label, String stateKey) {
        String base = SCOPE_LABEL.equals(scope) ? SCOPE_LABEL + ":" + label : scope;
        return base + "|s:" + stateKey;
    }

    /** 下载标签的展示名：未打标签的记录归入"默认"。 */
    private static String labelOf(DownloadInfo info) {
        String label = info.label;
        return label == null || label.isEmpty() ? DEFAULT_LABEL : label;
    }

    private static String digestOf(List<Long> gids) {
        StringBuilder sb = new StringBuilder(gids.size() * 8);
        for (int i = 0; i < gids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(gids.get(i));
        }
        return BucketLayout.fnv1a32(sb.toString());
    }

    private static List<Long> gidsOf(List<Row> rows) {
        List<Long> gids = new ArrayList<>(rows.size());
        for (Row row : rows) {
            gids.add(row.gid);
        }
        return gids;
    }

    private static int[] countsArray(TreeMap<Integer, List<Row>> buckets, int baseBucket, int bitCount) {
        int[] counts = new int[Math.max(0, bitCount)];
        if (bitCount <= 0) {
            return counts;
        }
        for (int i = 0; i < bitCount; i++) {
            List<Row> rows = buckets.get(baseBucket + i);
            counts[i] = rows == null ? 0 : rows.size();
        }
        return counts;
    }

    private static Set<Integer> bucketRange(int baseBucket, int bitCount) {
        Set<Integer> range = new TreeSet<>();
        for (int i = 0; i < bitCount; i++) {
            range.add(baseBucket + i);
        }
        return range;
    }

    private static String categoryName(int category) {
        int index = -1;
        int temp = category;
        while (temp > 0) {
            temp >>= 1;
            index++;
        }
        return index >= 0 && index < CATEGORY_NAMES.length ? CATEGORY_NAMES[index] : "Unknown";
    }

    private static String formatTime(long time) {
        if (time <= 0) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(time));
    }

    private static boolean isOff(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.US);
        return "0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String encodeCursor(int offset) {
        return BucketLayout.encodeBase64Url(("o:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) {
            return 0;
        }
        String value = cursor.trim();
        if (value.matches("\\d+")) {
            try {
                return Math.max(0, Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        try {
            value = new String(BucketLayout.decodeBase64Url(value), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return 0;
        }
        if (value.startsWith("o:")) {
            value = value.substring(2);
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean hasHeapHeadroom() {
        Runtime runtime = Runtime.getRuntime();
        long free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        return free > INDEX_HEAP_HEADROOM_BYTES;
    }

    private NanoHTTPD.Response layoutMismatch() {
        JSONObject body = new JSONObject(true);
        body.put("success", false);
        body.put("error", "index layout mismatch");
        body.put("code", 400);
        body.put("errorCode", "INDEX_LAYOUT_MISMATCH");
        body.put("epoch", store.getEpoch());
        body.put("bucketSize", store.getBucketSize());
        return ResponseBuilder.jsonSuccess(NanoHTTPD.Response.Status.BAD_REQUEST,
                JSON.toJSONString(body, JSON_FEATURES));
    }

    private static NanoHTTPD.Response indexError(NanoHTTPD.Response.Status status,
                                                 String message, String errorCode) {
        JSONObject body = new JSONObject(true);
        body.put("success", false);
        body.put("error", message);
        body.put("code", status.getRequestStatus());
        body.put("errorCode", errorCode);
        return ResponseBuilder.jsonSuccess(status, JSON.toJSONString(body, JSON_FEATURES));
    }
}
