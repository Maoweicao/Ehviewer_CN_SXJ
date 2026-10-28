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

package com.hippo.ehviewer.download

import android.content.Context
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.download.progressive.ProgressiveStats

/**
 * 删除保护：判断哪些画廊不该在批量删除里被顺手删掉。
 *
 * 两条规则，各自有独立开关（设置 → 下载 → 删除保护）：
 *
 * 1. **已加入收藏** —— 用户明确标记过，删掉大概率不是本意。
 * 2. **递进传递次数 ≥ 阈值** —— 该画廊里装着 N 个画廊的内容（见 [ProgressiveStats]），
 *    删掉等于一次丢 N 份，默认阈值 3。
 *
 * 行为约定：
 *
 * - **批量删除**：受保护的条目被自动剔除，剔了几条会告诉用户。
 * - **单个删除**：不阻止，只在原有确认框之后再弹一次「受保护画廊删除确认」；
 *   用户再次确认就**无视规则**照常删除。
 *
 * 作用范围仅限下载列表的删除入口，不影响合并重复 / 清理冗余等维护任务。
 */
object DeleteProtectHelper {

    /** 触发的保护规则。 */
    enum class Reason {
        /** 已加入收藏。 */
        FAVORITED,

        /** 递进传递次数达到阈值。 */
        PROGRESSIVE,
    }

    /** 某个画廊受保护的原因。 */
    data class Protection(val reason: Reason, val progressiveCount: Int) {

        /** 给用户看的一句话，例如「已收藏」「吸收过 5 个画廊」。 */
        fun describe(context: Context): String = when (reason) {
            Reason.FAVORITED -> context.getString(R.string.delete_protect_reason_favorited)
            Reason.PROGRESSIVE -> context.getString(
                R.string.delete_protect_reason_progressive, progressiveCount)
        }
    }

    /** 批量删除的拆分结果。 */
    data class SplitResult(
        /** 可以正常删除的。 */
        @JvmField val deletable: List<GalleryInfo>,
        /** 被保护、这次不删的，附带原因。 */
        @JvmField val protected: List<Pair<GalleryInfo, Protection>>,
    ) {
        val protectedCount: Int get() = protected.size
        val deletableCount: Int get() = deletable.size
    }

    /** 递进传递次数阈值。 */
    @JvmStatic
    fun progressiveThreshold(): Int = try {
        Settings.getDeleteProtectProgressiveThreshold().coerceAtLeast(1)
    } catch (t: Throwable) {
        3
    }

    /** 是否有任何一条保护规则生效。没开开关时是 O(1) 直接返回 false。 */
    @JvmStatic
    fun isProtectionEnabled(): Boolean = protectFavorited() || protectProgressive()

    /** 提前把递进索引建好，避免删除时才全表扫。 */
    @JvmStatic
    fun warmUpAsync() {
        if (protectProgressive()) {
            ProgressiveStats.warmUpAsync()
        }
    }

    private fun protectFavorited(): Boolean = try {
        Settings.getDeleteProtectFavorited()
    } catch (t: Throwable) {
        true
    }

    private fun protectProgressive(): Boolean = try {
        Settings.getDeleteProtectProgressive()
    } catch (t: Throwable) {
        false
    }

    /** 该画廊是否已收藏。仓库里统一用 `favoriteSlot != -2` 判断。 */
    @JvmStatic
    fun isFavorited(info: GalleryInfo?): Boolean = info != null && info.favoriteSlot != -2

    /**
     * 判断单个画廊是否受保护；不受保护返回 null。
     *
     * 两条规则都命中时「已收藏」优先 —— 用户显式标记过的比统计出来的更可信。
     */
    @JvmStatic
    fun isProtected(info: GalleryInfo?): Protection? {
        if (info == null) return null
        if (protectFavorited() && isFavorited(info)) {
            return Protection(Reason.FAVORITED, 0)
        }
        if (protectProgressive()) {
            val count = ProgressiveStats.absorbedCount(info.gid)
            if (count >= progressiveThreshold()) {
                return Protection(Reason.PROGRESSIVE, count)
            }
        }
        return null
    }

    /** 批量场景：把选中项拆成「可删」与「受保护」两堆。 */
    @JvmStatic
    fun splitProtected(infos: List<GalleryInfo>): SplitResult {
        if (infos.isEmpty()) {
            return SplitResult(emptyList(), emptyList())
        }
        // 规则全关时别白扫一遍统计索引
        if (!isProtectionEnabled()) {
            return SplitResult(ArrayList(infos), emptyList())
        }
        val deletable = ArrayList<GalleryInfo>(infos.size)
        val protected = ArrayList<Pair<GalleryInfo, Protection>>()
        for (info in infos) {
            val protection = isProtected(info)
            if (protection == null) {
                deletable.add(info)
            } else {
                protected.add(info to protection)
            }
        }
        return SplitResult(deletable, protected)
    }
}
