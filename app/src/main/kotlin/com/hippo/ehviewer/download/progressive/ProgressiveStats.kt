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

package com.hippo.ehviewer.download.progressive

import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.util.SearchDebugLog
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「递进传递」关系统计。
 *
 * 数据来自 `DOWNLOAD_HISTORY`：当画廊 A 因重复/递进关系被并入画廊 B 时，A 的历史记录里
 * 会留下 `mergedTargetGid = B`。于是「谁吸收了谁」构成一片以最新画廊为根的有向森林。
 *
 * 两个指标：
 *
 * - [absorbedCount]：**传递地**被吸收进该画廊的画廊总数（不含它自己）。也就是「现在这个画廊里
 *   装着几个画廊的内容」。
 * - [longestChainGalleries]：根到最深叶子的最长链条上的**画廊个数**（含它自己）。
 *
 * 例：合并历史 `A → B → G`、`X → G`，则 G 的 [absorbedCount] = 3（A、B、X），
 * [longestChainGalleries] = 3（A→B→G）。
 *
 * **性能**：建索引要全表扫 `DOWNLOAD_HISTORY`，所以只做一次并缓存；查询全部是内存 Map 遍历，
 * 可以安全地放在列表渲染这类热路径上。数据变了调 [invalidate] 标脏后台重建，重建期间旧索引
 * 继续可用，不闪空。
 */
object ProgressiveStats {

    /** targetGid -> 被它吸收的画廊列表（即 target 的直接子节点）。 */
    @Volatile
    private var childrenOf: Map<Long, List<Long>> = emptyMap()

    @Volatile
    private var ready = false

    @Volatile
    private var stale = true

    private val building = AtomicBoolean(false)

    /** 只增不减的代数，用来判断「构建期间数据又变了」。 */
    private val generation = AtomicInteger(0)

    /** 需要时在后台建索引；绝不能同步调用（会全表扫）。 */
    @JvmStatic
    fun warmUpAsync() {
        if (!stale && ready) return
        if (!building.compareAndSet(false, true)) return
        val thread = Thread {
            val gen = generation.get()
            try {
                if (stale) {
                    childrenOf = buildIndex()
                    ready = true
                }
            } catch (t: Throwable) {
                // 统计失败只影响显示与保护判断，不影响删除功能本身
                ready = true
            } finally {
                building.set(false)
                // 构建期间数据又变了：这一版已经过期，排下一轮
                stale = generation.get() != gen
                if (stale) {
                    warmUpAsync()
                }
            }
        }
        thread.name = "progressive-stats"
        thread.isDaemon = true
        thread.priority = Thread.NORM_PRIORITY - 1
        thread.start()
    }

    /** 合并/递进关系变了就标脏，后台重建一次。 */
    @JvmStatic
    fun invalidate() {
        generation.incrementAndGet()
        stale = true
        warmUpAsync()
    }

    private fun buildIndex(): Map<Long, List<Long>> {
        val map = HashMap<Long, MutableList<Long>>()
        val edges = try {
            EhDB.queryProgressiveMergeEdges()
        } catch (t: Throwable) {
            emptyList()
        }
        for (edge in edges) {
            map.getOrPut(edge.targetGid) { ArrayList(2) }.add(edge.sourceGid)
        }
        SearchDebugLog.d(
            "ProgressiveStats",
            "buildIndex: ${edges.size} edges -> ${map.size} targets",
        )
        return map
    }

    /**
     * **传递地**被吸收进 [gid] 的画廊总数（不含 [gid] 自己）。
     *
     * 用迭代 DFS 并带 visited 集合：递进合并的数据是用户历史里攒出来的，真出现环也不会死循环。
     */
    @JvmStatic
    fun absorbedCount(gid: Long): Int {
        if (gid <= 0) return 0
        // 索引没好之前也别拿半成品算：直接查一次（调用方应当已经 warmUp 过）
        if (stale) warmUpAsync()
        val children = childrenOf
        val roots = children[gid] ?: return 0

        val visited = HashSet<Long>(roots.size * 2)
        val stack = ArrayDeque<Long>(roots.size)
        for (child in roots) {
            if (visited.add(child)) stack.addLast(child)
        }
        var count = visited.size
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (child in children[current].orEmpty()) {
                if (visited.add(child)) {
                    stack.addLast(child)
                    count++
                }
            }
        }
        return count
    }

    /**
     * 最长一条递进链上的**画廊个数**（含 [gid] 自己）。
     *
     * 例：`A → B → G` 返回 3。
     */
    @JvmStatic
    fun longestChainGalleries(gid: Long): Int = longestChainGids(gid).size

    /**
     * 最长那条递进链的 gid 序列，从最深的一端排到 [gid]（即 `A → B → G`）。
     *
     * 用于详情页展示具体是哪条链，比一个孤零零的数字有用。
     */
    @JvmStatic
    fun longestChainGids(gid: Long): List<Long> {
        if (gid <= 0) return emptyList()
        if (stale) warmUpAsync()
        val children = childrenOf
        if (children.isEmpty()) return emptyList()

        val memo = HashMap<Long, Int>()
        val onPath = HashSet<Long>()

        fun chainLenOf(node: Long): Int {
            memo[node]?.let { return it }
            // 环：断开这条边继续算，避免无限递归
            if (!onPath.add(node)) return 1
            var best = 1
            for (child in children[node].orEmpty()) {
                val candidate = chainLenOf(child) + 1
                if (candidate > best) best = candidate
            }
            onPath.remove(node)
            memo[node] = best
            return best
        }

        val bestChild = children[gid]
            ?.maxByOrNull { chainLenOf(it) }
            ?: return listOf(gid)

        // 沿最长链回溯：bestChild 是最深的那个叶子
        val reversed = ArrayList<Long>(4)
        var cursor = bestChild
        val walked = HashSet<Long>()
        while (walked.add(cursor)) {
            reversed.add(cursor)
            val next = children[cursor]?.maxByOrNull { memo[it] ?: chainLenOf(it) }
            if (next == null) break
            cursor = next
        }
        reversed.reverse()
        reversed.add(gid)
        return reversed
    }

    /**
     * 一次性算出多个 gid 的 [absorbedCount]，比逐个调省掉重复的 Map 查找。
     *
     * 供删除保护在批量场景使用（要先给整批算一遍再筛）。
     */
    @JvmStatic
    fun absorbedCounts(gids: Collection<Long>): Map<Long, Int> {
        if (gids.isEmpty()) return emptyMap()
        if (stale) warmUpAsync()
        val out = LinkedHashMap<Long, Int>(gids.size * 2)
        for (gid in gids) {
            out[gid] = absorbedCount(gid)
        }
        return out
    }
}
