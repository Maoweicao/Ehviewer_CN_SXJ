package com.hippo.ehviewer.download.tag

import com.hippo.ehviewer.dao.QuickSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagQueryTest {

    private fun tokens(vararg values: String): Set<String> = values.toSet()

    @Test
    fun testExactAndSubstring() {
        val exact = TagQueryParser.parse("female:\"sole female\"$")
        assertTrue(exact.isValid)
        assertTrue(exact.matches(tokens("female:sole female")))

        val substring = TagQueryParser.parse("female:sole")
        assertTrue(substring.isValid)
        assertTrue(substring.matches(tokens("female:sole female")))

        // 没有 $ 时是包含匹配，带 $ 时要求完全相等
        assertFalse(exact.matches(tokens("female:sole female reverse")))
    }

    @Test
    fun testNamespaceAndBareValue() {
        val namespaced = TagQueryParser.parse("artist:fukuro")
        assertTrue(namespaced.matches(tokens("artist:fukuro")))
        assertFalse(namespaced.matches(tokens("group:fukuro")))

        val bare = TagQueryParser.parse("fukuro")
        assertTrue(bare.matches(tokens("group:fukuro")))
    }

    @Test
    fun testAndNegationAndOr() {
        val andQuery = TagQueryParser.parse("language:chinese$ female:a$")
        assertTrue(andQuery.matches(tokens("language:chinese", "female:a")))
        assertFalse(andQuery.matches(tokens("language:chinese", "female:b")))

        val negated = TagQueryParser.parse("language:chinese$ -male:solo")
        assertTrue(negated.matches(tokens("language:chinese", "female:a")))
        assertFalse(negated.matches(tokens("language:chinese", "male:solo")))

        val orQuery = TagQueryParser.parse("language:chinese$ ~language:japanese$")
        assertTrue(orQuery.matches(tokens("language:chinese")))
        assertTrue(orQuery.matches(tokens("language:japanese")))
        assertFalse(orQuery.matches(tokens("language:english")))
    }

    @Test
    fun testNormalizesPlusAndCase() {
        val query = TagQueryParser.parse("female:Sole+Female$")
        assertTrue(query.matches(tokens("female:sole female")))
    }

    @Test
    fun testNonTagQueriesAreRejected() {
        assertFalse(TagQueryParser.parse("title:hello").isValid)
        assertFalse(TagQueryParser.parse("uploader:someone").isValid)
        assertFalse(TagQueryParser.parse("").isValid)
        assertFalse(TagQueryParser.parse("female:\"unclosed").isValid)
        assertFalse(TagQueryParser.parse("~female:a").isValid)
        assertFalse(TagQueryParser.parse("female:a*").isValid)
    }

    @Test
    fun testCategoriesCountEveryMatchingGallery() {
        val tags = mapOf(
            1L to tokens("female:a", "language:chinese"),
            2L to tokens("female:b", "language:chinese"),
            3L to tokens("female:a"),
        )
        val searches = listOf(
            quickSearch(1, "female:a"),
            quickSearch(2, "language:chinese$"),
            quickSearch(3, "female:a language:chinese$"),
            quickSearch(4, "male:z"),
            quickSearch(5, "title:not-a-tag"),
        )

        val categories = TagCategoryBuilder.build(searches, tags)

        assertEquals(2, categories[0].count)
        assertEquals(2, categories[1].count)
        assertEquals(1, categories[2].count)
        assertEquals(0, categories[3].count)
        assertTrue(categories[3].valid)
        assertEquals(0, categories[4].count)
        assertFalse(categories[4].valid)
    }

    @Test
    fun testCategoryScopeOnlyCountsGivenGids() {
        val tags = mapOf(
            1L to tokens("female:a"),
            2L to tokens("female:a"),
        )
        val categories = TagCategoryBuilder.build(
            listOf(quickSearch(1, "female:a")), tags, listOf(2L)
        )
        assertEquals(1, categories[0].count)
    }

    @Test
    fun testFilterConditionKeepsTagQueryAcrossJson() {
        val condition = com.hippo.ehviewer.download.filter.FilterCondition.EMPTY
            .withTagQuery("female:a")
        val restored = com.hippo.ehviewer.download.filter.FilterCondition.fromJson(condition.toJson())
        assertEquals("female:a", restored.tagQuery)
    }

    private fun quickSearch(id: Long, keyword: String): QuickSearch {
        val search = QuickSearch()
        search.id = id
        search.name = keyword
        search.keyword = keyword
        return search
    }
}
