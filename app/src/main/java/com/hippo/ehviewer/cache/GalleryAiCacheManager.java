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

package com.hippo.ehviewer.cache;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.lab.analyze.model.AiGalleryAnalysis;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.unifile.UniFile;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * AI 画廊分析缓存管理器
 * 负责 .ehviewer.extra.ai.json 文件的读写、删除
 */
public class GalleryAiCacheManager {

    private static final String TAG = "GalleryAiCacheManager";
    public static final String AI_CACHE_FILENAME = AiGalleryAnalysis.GALLERY_AI_CACHE_FILENAME;

    private static GalleryAiCacheManager sInstance;
    private final Context mContext;

    private GalleryAiCacheManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public static GalleryAiCacheManager getInstance() {
        if (sInstance == null) {
            sInstance = new GalleryAiCacheManager(null);
        }
        return sInstance;
    }

    public static GalleryAiCacheManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new GalleryAiCacheManager(context);
        }
        return sInstance;
    }

    /**
     * 检查画廊是否已有 AI 分析缓存文件
     */
    public boolean hasAnalysis(long gid) {
        UniFile downloadDir = getDownloadDir(gid);
        if (downloadDir == null || !downloadDir.isDirectory()) {
            return false;
        }
        UniFile cacheFile = downloadDir.findFile(AI_CACHE_FILENAME);
        return cacheFile != null && cacheFile.isFile();
    }

    /**
     * 从下载目录读取 AI 分析结果
     */
    @Nullable
    public AiGalleryAnalysis readAnalysis(long gid) {
        UniFile downloadDir = getDownloadDir(gid);
        if (downloadDir == null || !downloadDir.isDirectory()) {
            return null;
        }
        UniFile cacheFile = downloadDir.findFile(AI_CACHE_FILENAME);
        if (cacheFile == null || !cacheFile.isFile()) {
            return null;
        }
        InputStream is = null;
        try {
            is = cacheFile.openInputStream();
            if (is == null) {
                return null;
            }
            String jsonContent = IOUtils.readString(is, "UTF-8");
            if (jsonContent == null || jsonContent.isEmpty()) {
                return null;
            }
            JSONObject jsonObject = JSON.parseObject(jsonContent);
            if (jsonObject == null) {
                return null;
            }
            return AiGalleryAnalysis.fromJson(jsonObject);
        } catch (Exception e) {
            Log.e(TAG, "读取AI分析失败: GID " + gid, e);
            return null;
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    /**
     * 从指定画廊目录读取 AI 分析结果
     */
    @Nullable
    public AiGalleryAnalysis readAnalysisFromDir(@NonNull UniFile downloadDir) {
        if (downloadDir == null || !downloadDir.isDirectory()) {
            return null;
        }
        UniFile cacheFile = downloadDir.findFile(AI_CACHE_FILENAME);
        if (cacheFile == null || !cacheFile.isFile()) {
            return null;
        }
        InputStream is = null;
        try {
            is = cacheFile.openInputStream();
            if (is == null) {
                return null;
            }
            String jsonContent = IOUtils.readString(is, "UTF-8");
            if (jsonContent == null || jsonContent.isEmpty()) {
                return null;
            }
            JSONObject jsonObject = JSON.parseObject(jsonContent);
            if (jsonObject == null) {
                return null;
            }
            return AiGalleryAnalysis.fromJson(jsonObject);
        } catch (Exception e) {
            Log.e(TAG, "读取AI分析失败: " + downloadDir.getName(), e);
            return null;
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    /**
     * 将 AI 分析结果写入下载目录 .ehviewer.extra.ai.json
     */
    public boolean saveAnalysis(@NonNull AiGalleryAnalysis analysis) {
        UniFile downloadDir = getDownloadDir(analysis.gid);
        if (downloadDir == null) {
            Log.e(TAG, "无法获取下载目录: GID " + analysis.gid);
            return false;
        }
        if (!downloadDir.ensureDir()) {
            Log.e(TAG, "无法创建下载目录: GID " + analysis.gid);
            return false;
        }

        UniFile existingFile = downloadDir.findFile(AI_CACHE_FILENAME);
        if (existingFile != null) {
            existingFile.delete();
        }

        UniFile cacheFile = downloadDir.createFile(AI_CACHE_FILENAME);
        if (cacheFile == null) {
            Log.e(TAG, "无法创建AI分析缓存文件: GID " + analysis.gid);
            return false;
        }

        OutputStream os = null;
        try {
            String jsonContent = JSON.toJSONString(analysis.toJson(), true);
            os = cacheFile.openOutputStream();
            if (os == null) {
                return false;
            }
            os.write(jsonContent.getBytes("UTF-8"));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "写入AI分析失败: GID " + analysis.gid, e);
            return false;
        } finally {
            IOUtils.closeQuietly(os);
        }
    }

    /**
     * 删除 AI 分析缓存文件
     */
    public boolean deleteAnalysis(long gid) {
        UniFile downloadDir = getDownloadDir(gid);
        if (downloadDir == null || !downloadDir.isDirectory()) {
            return false;
        }
        UniFile cacheFile = downloadDir.findFile(AI_CACHE_FILENAME);
        if (cacheFile == null) {
            return false;
        }
        return cacheFile.delete();
    }

    @Nullable
    private UniFile getDownloadDir(long gid) {
        return SpiderDen.getGalleryDownloadDir(new GalleryInfo() {{
            this.gid = gid;
        }});
    }
}
