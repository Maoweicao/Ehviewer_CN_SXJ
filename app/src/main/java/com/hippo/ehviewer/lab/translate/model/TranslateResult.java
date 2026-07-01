package com.hippo.ehviewer.lab.translate.model;

import android.graphics.Rect;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class TranslateResult {
    public int pageIndex;
    public List<TextRegion> regions;
    public long timestamp;
    public String ocrEngine;

    public TranslateResult() {
        this.regions = new ArrayList<>();
        this.timestamp = System.currentTimeMillis();
        this.ocrEngine = "";
    }

    public TranslateResult(int pageIndex) {
        this();
        this.pageIndex = pageIndex;
    }

    public void addRegion(TextRegion region) {
        regions.add(region);
    }

    public boolean hasRegions() {
        return regions != null && !regions.isEmpty();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("pageIndex", pageIndex);
        json.put("timestamp", timestamp);
        json.put("ocrEngine", ocrEngine);

        JSONArray regionsArray = new JSONArray();
        if (regions != null) {
            for (TextRegion region : regions) {
                regionsArray.add(region.toJson());
            }
        }
        json.put("regions", regionsArray);
        return json;
    }

    public static TranslateResult fromJson(JSONObject json) {
        TranslateResult result = new TranslateResult();
        if (json == null) return result;
        result.pageIndex = json.getIntValue("pageIndex");
        result.timestamp = json.getLongValue("timestamp");
        result.ocrEngine = json.getString("ocrEngine");

        JSONArray regionsArray = json.getJSONArray("regions");
        if (regionsArray != null) {
            for (int i = 0; i < regionsArray.size(); i++) {
                result.regions.add(TextRegion.fromJson(regionsArray.getJSONObject(i)));
            }
        }
        return result;
    }

    public static class TextRegion {
        public String id;
        public Rect rect;
        public String original;
        public String translated;
        public String sourceLang;
        public String targetLang;
        public float confidence;

        public TextRegion() {
            this.id = "r" + System.nanoTime();
            this.rect = new Rect();
            this.original = "";
            this.translated = "";
            this.sourceLang = "";
            this.targetLang = "";
            this.confidence = 0f;
        }

        public TextRegion(Rect rect, String original, String translated) {
            this();
            this.rect = rect;
            this.original = original;
            this.translated = translated;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("id", id);

            JSONObject rectJson = new JSONObject();
            if (rect != null) {
                rectJson.put("x", rect.left);
                rectJson.put("y", rect.top);
                rectJson.put("w", rect.width());
                rectJson.put("h", rect.height());
            }
            json.put("rect", rectJson);

            json.put("original", original);
            json.put("translated", translated);
            json.put("sourceLang", sourceLang);
            json.put("targetLang", targetLang);
            json.put("confidence", confidence);
            return json;
        }

        public static TextRegion fromJson(JSONObject json) {
            TextRegion region = new TextRegion();
            if (json == null) return region;
            region.id = json.getString("id");

            JSONObject rectJson = json.getJSONObject("rect");
            if (rectJson != null) {
                int x = rectJson.getIntValue("x");
                int y = rectJson.getIntValue("y");
                int w = rectJson.getIntValue("w");
                int h = rectJson.getIntValue("h");
                region.rect = new Rect(x, y, x + w, y + h);
            }

            region.original = json.getString("original");
            region.translated = json.getString("translated");
            region.sourceLang = json.getString("sourceLang");
            region.targetLang = json.getString("targetLang");
            region.confidence = json.getFloatValue("confidence");
            return region;
        }
    }
}
