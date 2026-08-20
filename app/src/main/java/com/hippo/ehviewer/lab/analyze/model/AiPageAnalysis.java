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
 * 单页图片的 AI 多维分析结果
 */
public class AiPageAnalysis {

    public int pageIndex;
    public String description;
    public List<String> tags;
    public float aestheticScore;
    public String aestheticComment;

    public AiPageAnalysis() {
        tags = new ArrayList<>();
        aestheticScore = -1;
    }

    public AiPageAnalysis(int pageIndex) {
        this();
        this.pageIndex = pageIndex;
    }

    public JSONObject toJson() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("pageIndex", pageIndex);
        jsonObject.put("description", description);
        jsonObject.put("tags", tags);
        jsonObject.put("aestheticScore", aestheticScore);
        jsonObject.put("aestheticComment", aestheticComment);
        return jsonObject;
    }

    public static AiPageAnalysis fromJson(JSONObject jsonObject) {
        AiPageAnalysis analysis = new AiPageAnalysis();
        if (jsonObject == null) {
            return analysis;
        }
        analysis.pageIndex = jsonObject.getIntValue("pageIndex");
        analysis.description = jsonObject.getString("description");
        analysis.aestheticScore = jsonObject.getFloatValue("aestheticScore");
        analysis.aestheticComment = jsonObject.getString("aestheticComment");
        JSONArray tagsArray = jsonObject.getJSONArray("tags");
        if (tagsArray != null) {
            analysis.tags = new ArrayList<>();
            for (int i = 0; i < tagsArray.size(); i++) {
                String tag = tagsArray.getString(i);
                if (tag != null && !tag.isEmpty()) {
                    analysis.tags.add(tag);
                }
            }
        }
        return analysis;
    }
}
