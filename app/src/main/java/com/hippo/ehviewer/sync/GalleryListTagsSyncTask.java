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

import android.util.Log;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.List;

/**
 * 把列表页解析出来的标签写进 {@code Gallery_Tags}。
 *
 * <p>历史上这个方法体是被整段注释掉的（2022 年引入时就是死代码），所以列表页的标签
 * 从来没入库，只有打开画廊详情页才会写 —— 结果「排除标签 / 标签搜索 / 无标签筛选 /
 * 标签联想」只能看到极少数条目。现在改为直接委托 {@link GalleryTagsWriter}：
 * 列表页每解析一次就顺手补齐标签，浏览过（或从列表下载）的画廊都能被标签规则命中。
 */
public class GalleryListTagsSyncTask {

    private static final String TAG = "GalleryListTagsSyncTask";

    final List<GalleryInfo> galleryInfoList;

    public GalleryListTagsSyncTask(List<GalleryInfo> galleryInfoList) {
        this.galleryInfoList = galleryInfoList;
    }

    public void execute() {
        IoThreadPoolExecutor.Companion.getInstance().execute(this::executeFunction);
    }

    private void executeFunction() {
        if (galleryInfoList == null || galleryInfoList.isEmpty()) {
            return;
        }
        int saved = 0;
        for (GalleryInfo info : galleryInfoList) {
            if (info == null) {
                continue;
            }
            try {
                if (GalleryTagsWriter.saveFromGalleryInfo(info)) {
                    saved++;
                }
            } catch (Throwable e) {
                Log.e(TAG, "保存标签失败 gid=" + info.gid, e);
            }
        }
        if (saved > 0) {
            Log.d(TAG, "列表页标签入库: " + saved + "/" + galleryInfoList.size());
        }
    }
}
