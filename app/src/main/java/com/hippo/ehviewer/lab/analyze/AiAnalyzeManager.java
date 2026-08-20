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

package com.hippo.ehviewer.lab.analyze;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.cache.GalleryAiCacheManager;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.GalleryAiInfo;
import com.hippo.ehviewer.lab.analyze.model.AiGalleryAnalysis;
import com.hippo.ehviewer.lab.analyze.model.AiPageAnalysis;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.unifile.UniFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 图片分析编排器。
 * 遍历下载画廊目录中的图片，逐页（按采样步长）调用多模态模型分析，
 * 汇总生成画廊整体分析，并写入 .ehviewer.extra.ai.json 与数据库。
 */
public class AiAnalyzeManager {
    private static final String TAG = "AiAnalyzeManager";

    public interface AnalyzeCallback {
        void onProgress(int current, int total, String detail);
        void onSuccess(AiGalleryAnalysis analysis);
        void onError(String error);
    }

    private static volatile AiAnalyzeManager instance;

    private final ExecutorService executor;
    private OpenAiImageAnalyzer analyzer;

    private AiAnalyzeManager() {
        executor = Executors.newSingleThreadExecutor();
        analyzer = new OpenAiImageAnalyzer();
    }

    public static synchronized AiAnalyzeManager getInstance() {
        if (instance == null) {
            instance = new AiAnalyzeManager();
        }
        return instance;
    }

    public void release() {
        if (analyzer != null) {
            analyzer.release();
        }
        executor.shutdown();
    }

    public boolean isEnabled() {
        return Settings.getAiAnalyzeEnabled();
    }

    public boolean isAvailable() {
        return Settings.getAiTranslateApiUrl() != null && !Settings.getAiTranslateApiUrl().isEmpty();
    }

    /**
     * 检查画廊是否已有分析结果
     */
    public boolean hasAnalysis(long gid) {
        return GalleryAiCacheManager.getInstance().hasAnalysis(gid);
    }

    /**
     * 读取画廊分析结果（优先内存/文件）
     */
    public AiGalleryAnalysis getAnalysis(long gid) {
        return GalleryAiCacheManager.getInstance().readAnalysis(gid);
    }

    /**
     * 分析整个画廊（后台线程执行）
     */
    public void analyzeGallery(DownloadInfo info, AnalyzeCallback callback) {
        executor.execute(() -> doAnalyzeGallery(info, callback));
    }

    private void doAnalyzeGallery(DownloadInfo info, AnalyzeCallback callback) {
        if (info == null) {
            callback.onError("Download info is null");
            return;
        }
        long gid = info.gid;
        UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
        if (downloadDir == null || !downloadDir.isDirectory()) {
            callback.onError("Download directory not found for gid " + gid);
            return;
        }

        int totalPages = info.pages > 0 ? info.pages : countPagesInDir(downloadDir);
        if (totalPages <= 0) {
            callback.onError("No pages found for gid " + gid);
            return;
        }

        int sampleStep = Math.max(1, Settings.getAiAnalyzeSampleStep());
        List<Integer> pageIndices = buildSampleIndices(totalPages, sampleStep);

        AiGalleryAnalysis analysis = new AiGalleryAnalysis();
        analysis.gid = gid;
        analysis.model = Settings.getAiAnalyzeModel();
        if (analysis.model == null || analysis.model.isEmpty()) {
            analysis.model = Settings.getAiTranslateModel();
        }
        analysis.analyzedAt = System.currentTimeMillis();
        analysis.sampleStep = sampleStep;

        int done = 0;
        int total = pageIndices.size();
        for (int pageIndex : pageIndices) {
            AiPageAnalysis page = analyzeSinglePage(gid, downloadDir, pageIndex);
            if (page != null) {
                analysis.pages.add(page);
            }
            done++;
            callback.onProgress(done, total, "P" + (pageIndex + 1));
        }

        if (analysis.pages.isEmpty()) {
            callback.onError("All pages failed to analyze for gid " + gid);
            return;
        }

        analyzeGalleryOverview(gid, analysis);

        boolean saved = GalleryAiCacheManager.getInstance().saveAnalysis(analysis);
        if (saved) {
            writeToDatabase(analysis);
        } else {
            Log.w(TAG, "Failed to save .ehviewer.extra.ai.json for gid " + gid);
        }

        callback.onSuccess(analysis);
    }

    private AiPageAnalysis analyzeSinglePage(long gid, UniFile downloadDir, int pageIndex) {
        try {
            UniFile imageFile = SpiderDen.findImageFile(downloadDir, pageIndex);
            if (imageFile == null) {
                Log.w(TAG, "Image file not found for gid " + gid + " page " + pageIndex);
                return null;
            }
            Bitmap bitmap = loadImage(imageFile);
            if (bitmap == null) {
                return null;
            }

            String responseFormat = "{"
                    + "\"description\": \"自然语言描述画面内容\","
                    + "\"tags\": [\"Danbooru风格标签1\", \"标签2\"],"
                    + "\"aestheticScore\": 8.5,"
                    + "\"aestheticComment\": \"美学评分理由\""
                    + "}";
            String prompt = "请分析这张漫画/画集图片，从多个维度输出：\n"
                    + "1. description: 用中文自然语言详细描述这张图片的画面内容（人物、动作、场景、构图、氛围）。\n"
                    + "2. tags: 输出 Danbooru 风格的英文标签列表（如 1girl, long_hair, school_uniform），概括画面要素。\n"
                    + "3. aestheticScore: 给出 0-10 的美学评分（可带一位小数），基于构图、色彩、细节、表现力。\n"
                    + "4. aestheticComment: 用中文简要说明美学评分理由。\n"
                    + "只输出 JSON，不要输出其他任何内容。";

            final AiPageAnalysis[] resultHolder = new AiPageAnalysis[1];
            final String[] errorHolder = new String[1];
            final Object lock = new Object();

            analyzer.analyze(bitmap, prompt, responseFormat, new OpenAiImageAnalyzer.AnalyzeCallback() {
                @Override
                public void onSuccess(JSONObject result) {
                    AiPageAnalysis page = new AiPageAnalysis(pageIndex);
                    page.description = result.getString("description");
                    page.aestheticScore = result.getFloatValue("aestheticScore");
                    page.aestheticComment = result.getString("aestheticComment");
                    JSONArray tags = result.getJSONArray("tags");
                    if (tags != null) {
                        List<String> tagList = new ArrayList<>();
                        for (int i = 0; i < tags.size(); i++) {
                            String tag = tags.getString(i);
                            if (tag != null && !tag.isEmpty()) {
                                tagList.add(tag);
                            }
                        }
                        page.tags = tagList;
                    }
                    synchronized (lock) {
                        resultHolder[0] = page;
                        lock.notifyAll();
                    }
                }

                @Override
                public void onError(String error) {
                    synchronized (lock) {
                        errorHolder[0] = error;
                        lock.notifyAll();
                    }
                }
            });

            synchronized (lock) {
                lock.wait(120000);
            }
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }

            if (resultHolder[0] != null) {
                return resultHolder[0];
            } else {
                Log.w(TAG, "Page analysis failed for gid " + gid + " page " + pageIndex
                        + ": " + errorHolder[0]);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "analyzeSinglePage failed for gid " + gid + " page " + pageIndex, e);
            return null;
        }
    }

    private void analyzeGalleryOverview(long gid, AiGalleryAnalysis analysis) {
        try {
            // 取首、中、尾各一页图片进行整体分析
            List<AiPageAnalysis> pages = analysis.pages;
            if (pages.isEmpty()) {
                return;
            }
            int first = pages.get(0).pageIndex;
            int last = pages.get(pages.size() - 1).pageIndex;
            int mid = pages.get(pages.size() / 2).pageIndex;

            // 展示给模型的整体 prompt（不使用图片，仅依据已有分页结果汇总）
            StringBuilder sb = new StringBuilder();
            sb.append("以下是某画廊中若干页面的AI分析结果：\n");
            int shown = 0;
            for (int idx : new int[]{first, mid, last}) {
                AiPageAnalysis page = findPageByIndex(pages, idx);
                if (page != null) {
                    sb.append("页面 ").append(idx + 1).append(": ")
                            .append(page.description == null ? "" : page.description)
                            .append(" 标签: ").append(page.tags == null ? "" : page.tags)
                            .append("\n");
                    shown++;
                }
            }
            if (shown == 0) {
                return;
            }
            sb.append("\n请基于以上信息，给出该画廊的整体分析：\n")
                    .append("summary: 用中文概括整个画廊的主题、剧情走向、风格特点。\n")
                    .append("tags: 输出 Danbooru 风格的整体标签列表。\n")
                    .append("aestheticScore: 0-10 的整体美学评分。\n")
                    .append("aestheticComment: 整体美学评分理由（中文）。\n")
                    .append("只输出 JSON。");

            String responseFormat = "{"
                    + "\"summary\": \"整体描述\","
                    + "\"tags\": [\"整体标签1\", \"标签2\"],"
                    + "\"aestheticScore\": 8.0,"
                    + "\"aestheticComment\": \"整体评分理由\""
                    + "}";

            final Object lock = new Object();
            final AiGalleryAnalysis[] resultHolder = new AiGalleryAnalysis[1];
            final String[] errorHolder = new String[1];

            analyzer.analyzeText(sb.toString(), responseFormat, new OpenAiImageAnalyzer.AnalyzeCallback() {
                @Override
                public void onSuccess(JSONObject result) {
                    analysis.summary = result.getString("summary");
                    analysis.aestheticScore = result.getFloatValue("aestheticScore");
                    analysis.aestheticComment = result.getString("aestheticComment");
                    JSONArray tags = result.getJSONArray("tags");
                    if (tags != null) {
                        List<String> tagList = new ArrayList<>();
                        for (int i = 0; i < tags.size(); i++) {
                            String tag = tags.getString(i);
                            if (tag != null && !tag.isEmpty()) {
                                tagList.add(tag);
                            }
                        }
                        analysis.tags = tagList;
                    }
                    synchronized (lock) {
                        resultHolder[0] = analysis;
                        lock.notifyAll();
                    }
                }

                @Override
                public void onError(String error) {
                    synchronized (lock) {
                        errorHolder[0] = error;
                        lock.notifyAll();
                    }
                }
            });

            synchronized (lock) {
                lock.wait(120000);
            }
            if (resultHolder[0] == null) {
                Log.w(TAG, "Gallery overview analysis failed for gid " + gid + ": " + errorHolder[0]);
            }
        } catch (Exception e) {
            Log.e(TAG, "analyzeGalleryOverview failed for gid " + gid, e);
        }
    }

    private AiPageAnalysis findPageByIndex(List<AiPageAnalysis> pages, int pageIndex) {
        for (AiPageAnalysis page : pages) {
            if (page.pageIndex == pageIndex) {
                return page;
            }
        }
        return null;
    }

    private List<Integer> buildSampleIndices(int totalPages, int sampleStep) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < totalPages; i += sampleStep) {
            indices.add(i);
        }
        // 保证包含最后一页
        if (indices.isEmpty() || indices.get(indices.size() - 1) != totalPages - 1) {
            indices.add(totalPages - 1);
        }
        return indices;
    }

    private int countPagesInDir(UniFile downloadDir) {
        int count = 0;
        UniFile[] files = downloadDir.listFiles();
        if (files != null) {
            for (UniFile file : files) {
                if (file.isFile()) {
                    String name = file.getName().toLowerCase(Locale.US);
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                            || name.endsWith(".webp") || name.endsWith(".gif")) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @androidx.annotation.Nullable
    private Bitmap loadImage(UniFile imageFile) {
        InputStream is = null;
        try {
            is = imageFile.openInputStream();
            if (is == null) {
                return null;
            }
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            Log.e(TAG, "loadImage failed", e);
            return null;
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    private void writeToDatabase(AiGalleryAnalysis analysis) {
        try {
            GalleryAiInfo info = new GalleryAiInfo();
            info.setGid(analysis.gid);
            info.setSummary(analysis.summary);
            info.setTags(joinList(analysis.tags));
            info.setDescriptions(joinPageDescriptions(analysis));
            info.setAestheticScore(analysis.aestheticScore);
            info.setUpdatedAt(analysis.analyzedAt);
            EhDB.putGalleryAiInfo(info);
        } catch (Exception e) {
            Log.e(TAG, "writeToDatabase failed", e);
        }
    }

    private String joinList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private String joinPageDescriptions(AiGalleryAnalysis analysis) {
        if (analysis == null || analysis.pages == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AiPageAnalysis page : analysis.pages) {
            if (page == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("P").append(page.pageIndex + 1).append(": ");
            if (page.description != null) {
                sb.append(page.description);
            }
            if (page.tags != null && !page.tags.isEmpty()) {
                sb.append(" [").append(joinList(page.tags)).append("]");
            }
        }
        return sb.toString();
    }
}
