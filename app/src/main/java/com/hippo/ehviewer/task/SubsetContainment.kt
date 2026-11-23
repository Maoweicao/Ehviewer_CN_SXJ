package com.hippo.ehviewer.task

/**
 * pToken 集包含关系工具。
 *
 * 输入若干“已按升序排列的 int id 数组”，用倒排索引只对 hash 数更多的元素做全包含判定，
 * 避免 O(n²) 全量比较。
 *
 * 从 [ProgressiveScanTask] 抽出，供渐进扫描与下载历史梳理/恢复校验共用，保证判定口径一致。
 */
object SubsetContainment {

    data class SupersetResult(
        /** 每个元素下标 -> 严格包含它的元素下标 */
        val supersets: Array<IntArray>,
        /** 进入全包含检测的候选对数 */
        val candidatePairs: Int,
        /** 实际执行的全包含检测次数 */
        val checkedPairs: Int
    )

    data class Result(
        val parent: IntArray,
        val candidatePairs: Int,
        val checkedPairs: Int
    )

    /**
     * 有序 int 数组的包含判断。
     *
     * @param superArr 升序排列的父集
     * @param subArr   升序排列的子集
     */
    fun containsAll(superArr: IntArray, subArr: IntArray): Boolean {
        if (subArr.isEmpty()) return true
        if (superArr.size < subArr.size) return false
        var si = 0
        var ci = 0
        while (si < superArr.size && ci < subArr.size) {
            when {
                superArr[si] == subArr[ci] -> {
                    si++
                    ci++
                }
                superArr[si] < subArr[ci] -> si++
                else -> return false
            }
        }
        return ci == subArr.size
    }

    /**
     * 找出每个元素的“严格父集”（hash 数更多且包含其全部 hash）。
     *
     * @param sortedHashes 每个元素的 hash id 数组，必须各自升序
     * @param onItem       每个元素开始时回调，调用方可在此检查协程取消
     * @param onProgress   每 500 次全包含检测回调一次（checked, total）
     */
    fun findStrictSupersets(
        sortedHashes: Array<IntArray>,
        onItem: (index: Int) -> Unit = {},
        onProgress: ((checked: Int, total: Int) -> Unit)? = null
    ): SupersetResult {
        val n = sortedHashes.size
        if (n <= 1) {
            return SupersetResult(Array(n) { IntArray(0) }, 0, 0)
        }

        var maxHashId = 0
        for (arr in sortedHashes) {
            for (h in arr) {
                if (h > maxHashId) maxHashId = h
            }
        }

        // 倒排索引：hashId -> 包含它的元素下标
        val hashToOwners = Array(maxHashId + 1) { mutableListOf<Int>() }
        for (i in 0 until n) {
            for (h in sortedHashes[i]) {
                hashToOwners[h].add(i)
            }
        }

        var candidatePairs = 0
        var checkedPairs = 0
        val supersets = Array(n) { IntArray(0) }

        for (i in 0 until n) {
            onItem(i)

            // 只把 hash 数更多的元素当作潜在父集
            val candidates = IntHashSet()
            for (h in sortedHashes[i]) {
                for (j in hashToOwners[h]) {
                    if (j != i && sortedHashes[j].size > sortedHashes[i].size) {
                        candidates.add(j)
                    }
                }
            }

            candidatePairs += candidates.size
            val found = IntHashSet()

            for (j in candidates.values()) {
                checkedPairs++
                if (containsAll(sortedHashes[j], sortedHashes[i])) {
                    found.add(j)
                }
                if (checkedPairs % 500 == 0) {
                    onProgress?.invoke(checkedPairs, candidatePairs)
                }
            }

            supersets[i] = found.values()
        }

        for (arr in hashToOwners) arr.clear()

        return SupersetResult(supersets, candidatePairs, checkedPairs)
    }

    /**
     * 构建包含关系簇（互相包含的元素同根）。
     *
     * @param sortedHashes 每个元素的 hash id 数组，必须各自升序
     */
    fun buildClusters(
        sortedHashes: Array<IntArray>,
        onItem: (index: Int) -> Unit = {},
        onProgress: ((checked: Int, total: Int) -> Unit)? = null
    ): Result {
        val n = sortedHashes.size
        val parent = IntArray(n) { it }
        val result = findStrictSupersets(sortedHashes, onItem, onProgress)
        for (i in 0 until n) {
            for (j in result.supersets[i]) {
                union(parent, i, j)
            }
        }
        return Result(parent, result.candidatePairs, result.checkedPairs)
    }

    fun find(parent: IntArray, x: Int): Int {
        var root = x
        while (parent[root] != root) {
            root = parent[root]
        }
        var cur = x
        while (parent[cur] != root) {
            val next = parent[cur]
            parent[cur] = root
            cur = next
        }
        return root
    }

    fun union(parent: IntArray, a: Int, b: Int) {
        val ra = find(parent, a)
        val rb = find(parent, b)
        if (ra != rb) {
            parent[ra] = rb
        }
    }

    private class IntHashSet {
        private var count = 0
        private var data = IntArray(16)

        val size: Int get() = count

        fun add(value: Int) {
            for (i in 0 until count) {
                if (data[i] == value) return
            }
            if (count == data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[count++] = value
        }

        fun values(): IntArray = data.copyOf(count)
    }
}
