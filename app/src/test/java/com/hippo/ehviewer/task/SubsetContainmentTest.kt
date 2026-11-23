package com.hippo.ehviewer.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SubsetContainment] 的纯算法测试。
 *
 * 恢复校验与下载历史梳理共用这套包含判定，这里锁定“严格超集 / 等集 / 无关集”的语义，
 * 避免两处口径漂移。
 */
class SubsetContainmentTest {

    @Test
    fun testContainsAll() {
        assertTrue(SubsetContainment.containsAll(intArrayOf(1, 2, 3), intArrayOf(1, 3)))
        assertTrue(SubsetContainment.containsAll(intArrayOf(1, 2, 3), intArrayOf(1, 2, 3)))
        assertTrue(SubsetContainment.containsAll(intArrayOf(1, 2, 3), intArrayOf()))
        assertFalse(SubsetContainment.containsAll(intArrayOf(1, 2), intArrayOf(1, 3)))
        assertFalse(SubsetContainment.containsAll(intArrayOf(1, 2), intArrayOf(1, 2, 3)))
    }

    @Test
    fun testFindStrictSupersetsIgnoresEqualSets() {
        val hashes = arrayOf(
            intArrayOf(1, 2),
            intArrayOf(1, 2, 3),
            intArrayOf(1, 2),
            intArrayOf(9),
        )

        val result = SubsetContainment.findStrictSupersets(hashes)

        assertEquals(listOf(1), result.supersets[0].toList())
        assertTrue(result.supersets[1].isEmpty())
        assertEquals(listOf(1), result.supersets[2].toList())
        assertTrue(result.supersets[3].isEmpty())
    }

    @Test
    fun testBuildClustersGroupsChainsButNotEqualSets() {
        val hashes = arrayOf(
            intArrayOf(1),          // 0: 被 1、2 包含
            intArrayOf(1, 2),       // 1: 被 2 包含
            intArrayOf(1, 2, 3),    // 2: 最完整
            intArrayOf(4, 5),       // 3: 与 4 等集，buildClusters 不合并
            intArrayOf(4, 5),       // 4
            intArrayOf(7),          // 5: 无关
        )

        val result = SubsetContainment.buildClusters(hashes)

        val root0 = SubsetContainment.find(result.parent, 0)
        val root1 = SubsetContainment.find(result.parent, 1)
        val root2 = SubsetContainment.find(result.parent, 2)
        val root3 = SubsetContainment.find(result.parent, 3)
        val root4 = SubsetContainment.find(result.parent, 4)
        val root5 = SubsetContainment.find(result.parent, 5)

        assertEquals(root0, root1)
        assertEquals(root1, root2)
        // 等集只做“严格包含”判定，调用方需要自行合并（见 TidyDownloadHistoryTask）
        assertFalse(root3 == root4)
        assertFalse(root0 == root3)
        assertFalse(root0 == root5)
    }

    @Test
    fun testEmptyInputIsSafe() {
        val result = SubsetContainment.buildClusters(emptyArray())
        assertTrue(result.parent.isEmpty())
        assertEquals(0, result.candidatePairs)
        assertEquals(0, result.checkedPairs)
    }
}
