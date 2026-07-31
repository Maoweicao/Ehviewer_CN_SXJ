package com.hippo.ehviewer.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveChainBuilderTest {

    private data class TestFolder(
        val gid: Long,
        val hashes: Set<String>
    )

    private fun buildChains(folders: List<TestFolder>): List<List<Long>> {
        val sorted = folders.sortedBy { it.hashes.size }
        val n = sorted.size
        val hashToOwners = HashMap<String, MutableList<Int>>()
        for (i in 0 until n) {
            for (hash in sorted[i].hashes) {
                hashToOwners.getOrPut(hash) { mutableListOf() }.add(i)
            }
        }

        val uf = IntArray(n) { it }
        for (i in 0 until n) {
            val candidates = HashSet<Int>()
            for (hash in sorted[i].hashes) {
                val owners = hashToOwners[hash] ?: continue
                for (j in owners) {
                    if (j != i && sorted[j].hashes.size > sorted[i].hashes.size) {
                        candidates.add(j)
                    }
                }
            }
            for (j in candidates) {
                if (sorted[j].hashes.containsAll(sorted[i].hashes)) {
                    union(uf, i, j)
                }
            }
        }

        val components = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val root = find(uf, i)
            components.getOrPut(root) { mutableListOf() }.add(i)
        }

        return components.values
            .filter { it.size >= 2 }
            .map { component ->
                component.sortedBy { sorted[it].hashes.size }.map { sorted[it].gid }
            }
    }

    private fun find(uf: IntArray, x: Int): Int {
        var root = x
        while (uf[root] != root) root = uf[root]
        var cur = x
        while (uf[cur] != root) {
            val next = uf[cur]
            uf[cur] = root
            cur = next
        }
        return root
    }

    private fun union(uf: IntArray, a: Int, b: Int) {
        val ra = find(uf, a)
        val rb = find(uf, b)
        if (ra != rb) uf[ra] = rb
    }

    @Test
    fun testChainProgression() {
        val folders = listOf(
            TestFolder(1, setOf("a", "b", "c")),
            TestFolder(2, setOf("a")),
            TestFolder(3, setOf("a", "b"))
        )
        val chains = buildChains(folders)
        assertEquals(1, chains.size)
        assertEquals(listOf(2L, 3L, 1L), chains[0])
    }

    @Test
    fun testBranchingSubset() {
        val folders = listOf(
            TestFolder(1, setOf("a")),
            TestFolder(2, setOf("a", "b")),
            TestFolder(3, setOf("a", "c"))
        )
        val chains = buildChains(folders)
        assertEquals(1, chains.size)
        assertEquals(3, chains[0].size)
        assertTrue(chains[0].contains(1L))
        assertTrue(chains[0].contains(2L))
        assertTrue(chains[0].contains(3L))
    }

    @Test
    fun testNoRelationship() {
        val folders = listOf(
            TestFolder(1, setOf("a", "b")),
            TestFolder(2, setOf("c", "d")),
            TestFolder(3, setOf("e", "f"))
        )
        val chains = buildChains(folders)
        assertEquals(0, chains.size)
    }

    @Test
    fun testEqualSetsNoChain() {
        val folders = listOf(
            TestFolder(1, setOf("a", "b")),
            TestFolder(2, setOf("a", "b"))
        )
        val chains = buildChains(folders)
        assertEquals(0, chains.size)
    }

    @Test
    fun testSingleElementNoChain() {
        val folders = listOf(
            TestFolder(1, setOf("a", "b", "c"))
        )
        val chains = buildChains(folders)
        assertEquals(0, chains.size)
    }

    @Test
    fun testTwoSeparateChains() {
        val folders = listOf(
            TestFolder(1, setOf("a")),
            TestFolder(2, setOf("a", "b")),
            TestFolder(3, setOf("x")),
            TestFolder(4, setOf("x", "y"))
        )
        val chains = buildChains(folders)
        assertEquals(2, chains.size)
        assertTrue(chains.any { it == listOf(1L, 2L) })
        assertTrue(chains.any { it == listOf(3L, 4L) })
    }

    @Test
    fun testLongChain() {
        val folders = listOf(
            TestFolder(1, setOf("a", "b", "c", "d", "e")),
            TestFolder(2, setOf("a")),
            TestFolder(3, setOf("a", "b")),
            TestFolder(4, setOf("a", "b", "c")),
            TestFolder(5, setOf("a", "b", "c", "d"))
        )
        val chains = buildChains(folders)
        assertEquals(1, chains.size)
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), chains[0])
    }

    @Test
    fun testPartialOverlapNotLinked() {
        val folders = listOf(
            TestFolder(1, setOf("a", "b")),
            TestFolder(2, setOf("b", "c")),
            TestFolder(3, setOf("a", "c"))
        )
        val chains = buildChains(folders)
        assertEquals(0, chains.size)
    }

    @Test
    fun testEmptyHashes() {
        val folders = listOf(
            TestFolder(1, emptySet()),
            TestFolder(2, setOf("a"))
        )
        val chains = buildChains(folders)
        assertEquals(0, chains.size)
    }
}
