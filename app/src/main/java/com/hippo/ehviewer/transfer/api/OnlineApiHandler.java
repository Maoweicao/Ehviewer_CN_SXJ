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

package com.hippo.ehviewer.transfer.api;

import android.content.Context;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhCookieStore;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.unifile.UniFile;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;

/**
 * 在线画廊 API 处理器（协议 §5.25）
 *
 * <p>通过主机内嵌的 {@link EhEngine} 直接从源站在线获取画廊数据，使远端可在主机已登录
 * 前提下进行在线浏览。
 *
 * <ul>
 *   <li>GET/QUERY /api/v1/online/search                       在线搜索/列表</li>
 *   <li>GET       /api/v1/online/galleries/{gid}               在线详情</li>
 *   <li>GET       /api/v1/online/galleries/{gid}/pages         在线页面列表</li>
 *   <li>GET       /api/v1/online/galleries/{gid}/pages/{page}  在线取图（源站代理）</li>
 * </ul>
 */
public class OnlineApiHandler extends BaseApiHandler {

    private static final String TAG = "OnlineApiHandler";

    private static final int PROXY_DOWNLOAD_TIMEOUT = 30;

    private static final String[] CATEGORY_NAMES = {
            "Misc", "Doujinshi", "Manga", "Artist CG", "Game CG",
            "Image Set", "Cosplay", "Asian Porn", "Non-H", "Western"
    };

    public OnlineApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri, session);
        String path = uri.split("\\?")[0];

        if (path.equals("/api/v1/online/search")) {
            return handleSearch(session);
        }
        if (path.matches("/api/v1/online/galleries/\\d+/pages/\\d+")) {
            long gid = extractOnlineGid(path);
            int page = RequestParser.extractPage(path);
            return handleOnlineImage(session, gid, page);
        }
        if (path.matches("/api/v1/online/galleries/\\d+/pages")) {
            long gid = extractOnlineGid(path);
            return handleOnlinePageList(session, gid);
        }
        if (path.matches("/api/v1/online/galleries/\\d+")) {
            long gid = extractOnlineGid(path);
            return handleOnlineDetail(session, gid);
        }
        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handleQuery(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("QUERY", uri, session);
        String path = uri.split("\\?")[0];
        if (path.equals("/api/v1/online/search")) {
            return handleSearch(session);
        }
        return ResponseBuilder.notFound("Endpoint");
    }

    // ==================== §5.25.1 在线搜索 ====================

    private NanoHTTPD.Response handleSearch(NanoHTTPD.IHTTPSession session) {
        if (!ensureSignedIn()) {
            return notSignedIn();
        }
        try {
            String keyword = null;
            int page = 0;
            String mode = "normal";
            int category = 0;
            int advance = ListUrlBuilder.DEFAULT_ADVANCE;
            boolean thumb = false;

            if (isQueryBody(session)) {
                String body = RequestParser.readBody(session);
                JSONObject json = JSONObject.parseObject(body);
                if (json != null) {
                    keyword = json.getString("keyword");
                    if (json.containsKey("page")) page = json.getIntValue("page");
                    if (json.containsKey("mode")) mode = json.getString("mode");
                    if (json.containsKey("category")) category = json.getIntValue("category");
                    thumb = json.getBooleanValue("thumb") || json.getBooleanValue("includeThumb");
                    advance = readAdvance(json.getBooleanValue("searchName"),
                            json.getBooleanValue("searchTags"), json.getBooleanValue("searchDesc"), advance);
                }
            } else {
                keyword = RequestParser.getQueryParameter(session, "keyword");
                page = RequestParser.getIntQueryParameter(session, "page", 0);
                mode = RequestParser.getQueryParameter(session, "mode", "normal");
                category = RequestParser.getIntQueryParameter(session, "category", 0);
                thumb = RequestParser.getBooleanQueryParameter(session, "thumb", false)
                        || RequestParser.getBooleanQueryParameter(session, "includeThumb", false);
            }

            ListUrlBuilder builder = new ListUrlBuilder();
            builder.setMode(mapMode(mode));
            builder.setPageIndex(Math.max(page, 0));
            if (keyword != null && !keyword.trim().isEmpty()) {
                builder.setKeyword(keyword.trim());
            }
            if (category != 0) {
                builder.setCategory(category);
            }
            builder.setAdvanceSearch(advance);

            String url = builder.build();
            GalleryListParser.Result result = EhEngine.getGalleryList(
                    null, EhApplication.getOkHttpClient(context), url, builder.getMode());

            JSONArray arr = new JSONArray();
            if (result.galleryInfoList != null) {
                for (GalleryInfo gi : result.galleryInfoList) {
                    arr.add(galleryInfoToJson(gi, thumb));
                }
            }

            JSONObject resp = new JSONObject();
            resp.put("source", "online");
            resp.put("keyword", keyword != null ? keyword : "");
            resp.put("mode", mode);
            resp.put("page", page);
            resp.put("hasPrev", page > 0);
            resp.put("hasNext", result.nextPage >= 0);
            resp.put("galleries", arr);
            return ResponseBuilder.jsonSuccess(resp.toJSONString());

        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "online search failed", wrap(t));
            return ResponseBuilder.internalError(t.getMessage());
        }
    }

    // ==================== §5.25.2 在线详情 ====================

    private NanoHTTPD.Response handleOnlineDetail(NanoHTTPD.IHTTPSession session, long gid) {
        if (!ensureSignedIn()) {
            return notSignedIn();
        }
        String token = galleryToken(session);
        if (token == null || token.isEmpty()) {
            return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "token is required", 400);
        }
        try {
            String url = EhUrl.getGalleryDetailUrl(gid, token);
            GalleryDetail detail = EhEngine.getGalleryDetail(
                    null, EhApplication.getOkHttpClient(context), url);

            JSONObject o = galleryInfoToJson(detail, false);
            o.put("source", "online");
            if (detail != null) {
                o.put("favoriteCount", detail.favoriteCount);
                o.put("isFavorited", detail.isFavorited);
                o.put("ratingCount", detail.ratingCount);
                o.put("torrentCount", detail.torrentCount);
                o.put("torrentUrl", nz(detail.torrentUrl));
                o.put("archiveUrl", nz(detail.archiveUrl));
                o.put("previewPages", detail.previewPages);
                o.put("apiUid", detail.apiUid);
                o.put("apiKey", nz(detail.apiKey));
                o.put("parent", nz(detail.parent));
                o.put("visible", nz(detail.visible));
                o.put("size", nz(detail.size));
            }
            o.put("comments", new JSONArray());
            return ResponseBuilder.jsonSuccess(o.toJSONString());
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "online detail failed", wrap(t));
            return ResponseBuilder.internalError(t.getMessage());
        }
    }

    // ==================== §5.25.3 在线页面列表 ====================

    private NanoHTTPD.Response handleOnlinePageList(NanoHTTPD.IHTTPSession session, long gid) {
        if (!ensureSignedIn()) {
            return notSignedIn();
        }
        String token = galleryToken(session);
        if (token == null) token = "";
        try {
            GalleryInfo gi = new GalleryInfo();
            gi.gid = gid;
            gi.token = token;

            int pages = 0;
            SpiderQueen queen = obtainQueenOnUi(gi, SpiderQueen.MODE_READ);
            try {
                if (queen != null) {
                    pages = queen.size();
                }
            } finally {
                if (queen != null) releaseQueenOnUi(queen, SpiderQueen.MODE_READ);
            }

            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(gi);
            SimpleDiskCache cache = SpiderDen.getCache();

            JSONArray pageList = new JSONArray();
            for (int i = 0; i < pages; i++) {
                String state = "pending";
                if (downloadDir != null && downloadDir.isDirectory()
                        && SpiderDen.findImageFile(downloadDir, i) != null) {
                    state = "downloaded";
                } else if (cache != null
                        && cache.contain(EhCacheKeyFactory.getImageKey(gid, i))) {
                    state = "cached";
                }
                JSONObject p = new JSONObject();
                p.put("page", i + 1);
                p.put("state", state);
                p.put("pToken", "");
                p.put("pageUrl", "");
                pageList.add(p);
            }

            JSONObject resp = new JSONObject();
            resp.put("gid", gid);
            resp.put("token", token);
            resp.put("pages", pages);
            resp.put("pageList", pageList);
            return ResponseBuilder.jsonSuccess(resp.toJSONString());
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "online page list failed", wrap(t));
            return ResponseBuilder.internalError(t.getMessage());
        }
    }

    // ==================== §5.25.4 在线取图（源站代理） ====================

    private NanoHTTPD.Response handleOnlineImage(NanoHTTPD.IHTTPSession session, long gid, int page) {
        if (!ensureSignedIn()) {
            return notSignedIn();
        }
        String token = galleryToken(session);
        if (token == null) token = "";
        String mode = RequestParser.getQueryParameter(session, "mode", "proxy");
        int index = Math.max(page - 1, 0);

        try {
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (info == null) {
                info = new DownloadInfo();
                info.gid = gid;
                info.token = token;
                info.title = "Gallery " + gid;
                info.state = DownloadInfo.STATE_NONE;
            }

            // 1. 本地下载目录
            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            if (downloadDir != null && downloadDir.isDirectory()) {
                UniFile imageFile = SpiderDen.findImageFile(downloadDir, index);
                if (imageFile != null) {
                    return serveFile(imageFile);
                }
            }

            // 2. 磁盘缓存
            SimpleDiskCache cache = SpiderDen.getCache();
            if (cache != null) {
                String key = EhCacheKeyFactory.getImageKey(gid, index);
                if (cache.contain(key)) {
                    com.hippo.streampipe.InputStreamPipe pipe = cache.getInputStreamPipe(key);
                    if (pipe != null) {
                        return servePipe(pipe);
                    }
                }
            }

            // 3. local 模式不再联网
            if ("local".equals(mode)) {
                return ResponseBuilder.notFound("Image (local mode: not in local)");
            }

            // 4. 代理下载
            return proxyDownload(info, index);
        } catch (Throwable t) {
            TransferLogger.getInstance().e(TAG, "online image failed", wrap(t));
            return ResponseBuilder.internalError(t.getMessage());
        }
    }

    private NanoHTTPD.Response proxyDownload(DownloadInfo info, int index) throws Exception {
        final int requestPage = index;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean(false);

        SpiderQueen queen = obtainQueenOnUi(info, SpiderQueen.MODE_DOWNLOAD);
        if (queen == null) {
            return ResponseBuilder.internalError("Cannot obtain spider queen");
        }
        try {
            queen.addOnSpiderListener(new SpiderQueen.OnSpiderListener() {
                @Override public void onGetPages(int pages) {}
                @Override public void onGet509(int index) {}
                @Override public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {}
                @Override public void onPageSuccess(int index, int finished, int downloaded, int total) {
                    if (index == requestPage) {
                        success.set(true);
                        latch.countDown();
                    }
                }
                @Override public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
                    if (index == requestPage) {
                        latch.countDown();
                    }
                }
                @Override public void onFinish(int finished, int downloaded, int total) {}
                @Override public void onGetImageSuccess(int index, com.hippo.lib.image.Image image) {}
                @Override public void onGetImageFailure(int index, String error) {
                    if (index == requestPage) {
                        latch.countDown();
                    }
                }
            });

            // request 需在 UI 线程（SpiderQueen 约束）
            final SpiderQueen q = queen;
            runOnUiThreadSync(() -> q.request(requestPage));

            boolean completed = latch.await(PROXY_DOWNLOAD_TIMEOUT, TimeUnit.SECONDS);
            if (!completed || !success.get()) {
                return ResponseBuilder.internalError("Download timeout or failed");
            }

            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            if (downloadDir != null && downloadDir.isDirectory()) {
                UniFile imageFile = SpiderDen.findImageFile(downloadDir, index);
                if (imageFile != null) {
                    return serveFile(imageFile);
                }
            }
            return ResponseBuilder.internalError("Download completed but file not found");
        } finally {
            releaseQueenOnUi(queen, SpiderQueen.MODE_DOWNLOAD);
        }
    }

    // ==================== 工具 ====================

    /** 从 /api/v1/online/galleries/{gid}... 中提取 gid */
    private static long extractOnlineGid(String path) {
        int idx = path.indexOf("/galleries/");
        if (idx < 0) return -1;
        int start = idx + "/galleries/".length();
        int end = path.indexOf('/', start);
        String num = end < 0 ? path.substring(start) : path.substring(start, end);
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 画廊 Token 读取：优先 {@code token}，兼容 {@code gToken}。
     * 之所以兼容 {@code gToken}，是因为带认证时 {@code token} 查询参数会被
     * 复用为会话 Token（§4.5），<img> 无法携带 Authorization 头。
     */
    private String galleryToken(NanoHTTPD.IHTTPSession session) {
        String t = RequestParser.getQueryParameter(session, "gToken");
        if (t == null || t.isEmpty()) {
            t = RequestParser.getQueryParameter(session, "token");
        }
        return t;
    }

    private boolean ensureSignedIn() {
        return EhApplication.getEhCookieStore(context).hasSignedIn();
    }

    private NanoHTTPD.Response notSignedIn() {
        return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.UNAUTHORIZED,
                "Not signed in to E-Hentai", 401);
    }

    private int readAdvance(boolean sname, boolean stags, boolean sdesc, int def) {
        int v = 0;
        v |= sname ? com.hippo.ehviewer.widget.AdvanceSearchTable.SNAME : 0;
        v |= stags ? com.hippo.ehviewer.widget.AdvanceSearchTable.STAGS : 0;
        v |= sdesc ? com.hippo.ehviewer.widget.AdvanceSearchTable.SDESC : 0;
        return v == 0 ? def : v;
    }

    private int mapMode(String mode) {
        if (mode == null) return ListUrlBuilder.MODE_NORMAL;
        switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "popular":
                return ListUrlBuilder.MODE_WHATS_HOT;
            case "top":
                return ListUrlBuilder.MODE_TOP_LIST;
            case "watched":
                return ListUrlBuilder.MODE_SUBSCRIPTION;
            case "normal":
            case "frontpage":
            default:
                return ListUrlBuilder.MODE_NORMAL;
        }
    }

    private JSONObject galleryInfoToJson(GalleryInfo gi, boolean inlineThumb) {
        JSONObject o = new JSONObject();
        o.put("gid", gi.gid);
        o.put("token", nz(gi.token));
        o.put("title", nz(gi.title));
        o.put("titleJpn", nz(gi.titleJpn));
        o.put("category", categoryName(gi.category));
        o.put("categoryValue", gi.category);
        o.put("posted", nz(gi.posted));
        o.put("uploader", nz(gi.uploader));
        o.put("rating", gi.rating);
        o.put("language", nz(gi.simpleLanguage));
        o.put("pages", gi.pages);
        if (inlineThumb && gi.thumb != null) {
            o.put("thumb", gi.thumb);
        }
        if (gi.simpleTags != null && gi.simpleTags.length > 0) {
            JSONArray tags = new JSONArray();
            for (String t : gi.simpleTags) {
                tags.add(t);
            }
            o.put("tags", tags);
        }
        return o;
    }

    private static String categoryName(int category) {
        int index = -1;
        int temp = category;
        while (temp > 0) {
            temp >>= 1;
            index++;
        }
        if (index >= 0 && index < CATEGORY_NAMES.length) {
            return CATEGORY_NAMES[index];
        }
        return "Unknown";
    }

    private NanoHTTPD.Response serveFile(UniFile file) {
        try {
            long length = file.length();
            InputStream stream = file.openInputStream();
            return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, guessMime(file.getName()), stream, length);
        } catch (Exception e) {
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    private NanoHTTPD.Response servePipe(com.hippo.streampipe.InputStreamPipe pipe) {
        try {
            pipe.obtain();
            InputStream stream = pipe.open();
            return NanoHTTPD.newChunkedResponse(
                    NanoHTTPD.Response.Status.OK, "image/jpeg", stream);
        } catch (Exception e) {
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    private static String guessMime(String name) {
        if (name == null) return "image/jpeg";
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---------- SpiderQueen 需在 UI 线程调用 ----------

    private SpiderQueen obtainQueenOnUi(GalleryInfo gi, int mode) {
        final SpiderQueen[] holder = new SpiderQueen[1];
        runOnUiThreadSync(() -> holder[0] = SpiderQueen.obtainSpiderQueen(context, gi, mode));
        return holder[0];
    }

    private void releaseQueenOnUi(SpiderQueen queen, int mode) {
        runOnUiThreadSync(() -> SpiderQueen.releaseSpiderQueen(queen, mode));
    }

    private static void runOnUiThreadSync(Runnable task) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Throwable wrap(Throwable t) {
        return t instanceof Exception ? t : new Exception(t);
    }
}
