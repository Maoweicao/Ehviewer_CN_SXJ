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

package com.hippo.ehviewer.lab.analyze.model;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 画廊整体的 AI 分析结果，包含整体描述与逐页分析
 */
public class AiGalleryAnalysis {

    public static final String GALLERY_AI_CACHE_FILENAME = ".ehviewer.extra.ai.json";

    public long gid;
    public String model;
    public long analyzedAt;
    public int sampleStep;
    public String summary;
    public List<String> tags;
    public float aestheticScore;
    public String aestheticComment;
    public List<AiPageAnalysis> pages;

    public AiGalleryAnalysis() {
        tags = new ArrayList<>();
        pages = new ArrayList<>();
        aestheticScore = -1;
    }

    public boolean hasPageAnalysis() {
        return pages != null && !pages.isEmpty();
    }

    public boolean hasGalleryAnalysis() {
        return summary != null && !summary.isEmpty();
    }

    public JSONObject toJson() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("gid", gid);
        jsonObject.put("model", model);
        jsonObject.put("analyzedAt", analyzedAt);
        jsonObject.put("sampleStep", sampleStep);
        JSONObject gallery = new JSONObject();
        gallery.put("summary", summary);
        gallery.put("tags", tags);
        gallery.put("aestheticScore", aestheticScore);
        gallery.put("aestheticComment", aestheticComment);
        jsonObject.put("gallery", gallery);
        JSONArray pagesArray = new JSONArray();
        if (pages != null) {
            for (AiPageAnalysis page : pages) {
                if (page != null) {
                    pagesArray.add(page.toJson());
                }
            }
        }
        jsonObject.put("pages", pagesArray);
        return jsonObject;
    }

    public static AiGalleryAnalysis fromJson(JSONObject jsonObject) {
        AiGalleryAnalysis analysis = new AiGalleryAnalysis();
        if (jsonObject == null) {
            return analysis;
        }
        analysis.gid = jsonObject.getLongValue("gid");
        analysis.model = jsonObject.getString("model");
        analysis.analyzedAt = jsonObject.getLongValue("analyzedAt");
        analysis.sampleStep = jsonObject.getIntValue("sampleStep");
        JSONObject gallery = jsonObject.getJSONObject("gallery");
        if (gallery != null) {
            analysis.summary = gallery.getString("summary");
            analysis.aestheticScore = gallery.getFloatValue("aestheticScore");
            analysis.aestheticComment = gallery.getString("aestheticComment");
            JSONArray tagsArray = gallery.getJSONArray("tags");
            if (tagsArray != null) {
                analysis.tags = new ArrayList<>();
                for (int i = 0; i < tagsArray.size(); i++) {
                    String tag = tagsArray.getString(i);
                    if (tag != null && !tag.isEmpty()) {
                        analysis.tags.add(tag);
                    }
                }
            }
        }
        JSONArray pagesArray = jsonObject.getJSONArray("pages");
        if (pagesArray != null) {
            analysis.pages = new ArrayList<>();
            for (int i = 0; i < pagesArray.size(); i++) {
                JSONObject pageJson = pagesArray.getJSONObject(i);
                if (pageJson != null) {
                    analysis.pages.add(AiPageAnalysis.fromJson(pageJson));
                }
            }
        }
        return analysis;
    }
}
