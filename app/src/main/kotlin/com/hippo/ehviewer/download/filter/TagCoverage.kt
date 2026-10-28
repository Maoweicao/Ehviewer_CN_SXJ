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

package com.hippo.ehviewer.download.filter

import com.hippo.ehviewer.dao.DownloadInfo

/**
 * 「标签规则到底能看到多少条目」的统计。
 *
 * 排除标签 / 标签关键词搜索 / 「无标签」筛选的标签来源只有两个：
 * 条目内存里的 `simpleTags`/`tgList`，以及 `Gallery_Tags` 表。
 * 两者都为空的条目对标签规则来说等于「没有标签」，会被静默地当成不命中。
 *
 * 这里只做统计（不读 IO），供排除面板提示用：DB 的 gid 集合由调用方一次性查好传进来。
 */
object TagCoverage {

    /** 该条目是否有标签数据可供规则判断。 */
    @JvmStatic
    @JvmOverloads
    fun hasTags(info: DownloadInfo?, tagGids: Set<Long>? = null): Boolean {
        if (info == null) return false
        if (info.simpleTags?.isNotEmpty() == true) return true
        if (info.tgList?.isNotEmpty() == true) return true
        return tagGids != null && tagGids.contains(info.gid)
    }

    /** 列表里有多少条目能被标签规则正确判断。 */
    @JvmStatic
    @JvmOverloads
    fun countTagged(list: List<DownloadInfo>?, tagGids: Set<Long>? = null): Int {
        if (list == null || list.isEmpty()) return 0
        var count = 0
        for (info in list) {
            if (hasTags(info, tagGids)) count++
        }
        return count
    }
}
