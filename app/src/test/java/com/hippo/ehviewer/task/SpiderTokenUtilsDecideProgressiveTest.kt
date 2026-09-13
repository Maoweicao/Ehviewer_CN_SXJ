package com.hippo.ehviewer.task

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 测试 [SpiderTokenUtils.decideProgressiveRelation] 的各种边界条件。
 *
 * 这是预碰撞检测的核心判定逻辑（替代旧"按页数差异直接合并"的 bug），
 * 必须保证：
 *  1. 同名无关图（token 完全无交集）→ 不合并
 *  2. 子集关系 → 移除旧版本
 *  3. 相等关系 → 按页数大小移除较小者；页数相同保留两者
 *  4. 部分重叠但非子集 → 不合并
 */
class SpiderTokenUtilsDecideProgressiveTest {

    private fun decide(
        targetTokens: Set<String>,
        targetPages: Int,
        candidateTokens: Set<String>,
        candidatePages: Int
    ) = SpiderTokenUtils.decideProgressiveRelation(
        targetTokens, targetPages,
        candidateTokens, candidatePages
    )

    @Test
    fun testCompletelyUnrelatedTokens() {
        // 两个图库 token 完全没有交集 → 不是递进关系
        val target = setOf("aaaa", "bbbb", "cccc")
        val candidate = setOf("xxxx", "yyyy", "zzzz")
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.NONE,
            decide(target, 30, candidate, 25)
        )
    }

    @Test
    fun testCandidateIsStrictSubsetOfTarget() {
        // candidate 是 target 的严格子集 → candidate 是旧版本
        val target = setOf("a", "b", "c", "d")
        val candidate = setOf("a", "b")
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.REMOVE_CANDIDATE,
            decide(target, 30, candidate, 10)
        )
    }

    @Test
    fun testTargetIsStrictSubsetOfCandidate() {
        // target 是 candidate 的严格子集 → target 是旧版本
        val target = setOf("a", "b")
        val candidate = setOf("a", "b", "c", "d")
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.REMOVE_TARGET,
            decide(target, 10, candidate, 30)
        )
    }

    @Test
    fun testEqualTokensCandidateBiggerPages() {
        // token 完全相等，candidate 页数更大 → 移除 target
        val target = setOf("a", "b", "c")
        val candidate = setOf("a", "b", "c")
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.REMOVE_EQUAL_TARGET,
            decide(target, 10, candidate, 30)
        )
    }

    @Test
    fun testEqualTokensTargetBiggerPages() {
        // token 完全相等，target 页数更大 → 移除 candidate
        val target = setOf("a", "b", "c")
        val candidate = setOf("a", "b", "c")
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.REMOVE_EQUAL_CANDIDATE,
            decide(target, 30, candidate, 10)
        )
    }

    @Test
    fun testEqualTokensAndPages() {
        // token 完全相等且页数相同 → 保守保留两者
        val target = setOf("a", "b", "c")
        val candidate = setOf("a", "b", "c")
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.NONE,
            decide(target, 30, candidate, 30)
        )
    }

    @Test
    fun testPartialOverlapNotSubset() {
        // 部分重叠但不互相是子集 → 不合并
        val target = setOf("a", "b")
        val candidate = setOf("b", "c")
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.NONE,
            decide(target, 10, candidate, 10)
        )
    }

    @Test
    fun testSingleIntersectionButDifferentContent() {
        // 真实场景：两个同名画廊只共享 1 张图，其余完全不同
        val target = setOf("common", "t1", "t2", "t3", "t4")
        val candidate = setOf("common", "c1", "c2", "c3", "c4")
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.NONE,
            decide(target, 25, candidate, 25)
        )
    }

    @Test
    fun testEmptyCandidateSet() {
        // candidate token 集为空（拉取失败或全 failed）→ 保守保留
        val target = setOf("a", "b", "c")
        val candidate = emptySet<String>()
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.NONE,
            decide(target, 10, candidate, 10)
        )
    }

    @Test
    fun testEmptyTargetSet() {
        // target token 集为空 → 保守保留
        val target = emptySet<String>()
        val candidate = setOf("a", "b", "c")
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.NONE,
            decide(target, 10, candidate, 10)
        )
    }

    @Test
    fun testBothEmptySets() {
        // 双方都空 → 保守保留
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.NONE,
            decide(emptySet(), 0, emptySet(), 0)
        )
    }

    @Test
    fun testEqualSingleToken() {
        // 仅一张图相同且页数都为 1 → 不视为重复（保守）
        val target = setOf("a")
        val candidate = setOf("a")
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.NONE,
            decide(target, 1, candidate, 1)
        )
    }

    @Test
    fun testEmptyCandidateContainsAll() {
        // candidate 是空集时，target.containsAll(candidate) 为 true，但 candidate.isNotEmpty() 为 false
        // 因此应返回 NONE（不能因为 target ⊇ 空集就合并）
        val target = setOf("a", "b", "c")
        val candidate = emptySet<String>()
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.NONE,
            decide(target, 30, candidate, 0)
        )
    }

    @Test
    fun testEmptyTargetContainsAll() {
        // target 是空集时，candidate.containsAll(target) 为 true，但 target.isNotEmpty() 为 false
        // 因此应返回 NONE（不能因为 candidate ⊇ 空集就合并 target）
        val target = emptySet<String>()
        val candidate = setOf("a", "b", "c")
        assertEquals(
            SpiderTokenUtils.ProgressiveDecision.NONE,
            decide(target, 0, candidate, 30)
        )
    }
}