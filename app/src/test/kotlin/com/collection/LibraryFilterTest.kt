package com.collection

import com.collection.domain.Entry
import com.collection.domain.HomePreferences
import com.collection.domain.Library
import com.collection.domain.SortOrder
import com.collection.domain.Tag
import com.collection.domain.filtered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {
    private val research = Tag("research", "论文 Research")
    private val work = Tag("work", "工作")
    private val sample = Library(
        entries = listOf(
            Entry(id = "a", title = "Zulu", summary = "文献阅读笔记", body = "仅正文中的特殊词",
                tagIds = setOf(research.id, work.id), createdAt = 10, updatedAt = 300),
            Entry(id = "b", title = "alpha", summary = "Compose 界面方案",
                tagIds = setOf(work.id), createdAt = 30, updatedAt = 100),
            Entry(id = "c", title = "Beta", summary = "本周文献摘要",
                tagIds = setOf(research.id), createdAt = 20, updatedAt = 200),
        ),
        tags = listOf(research, work),
    )

    @Test
    fun `all six sorts order by their chosen field and direction`() {
        val expected = mapOf(
            SortOrder.CREATED_DESC to listOf("b", "c", "a"),
            SortOrder.CREATED_ASC to listOf("a", "c", "b"),
            SortOrder.UPDATED_DESC to listOf("a", "c", "b"),
            SortOrder.UPDATED_ASC to listOf("b", "c", "a"),
            SortOrder.TITLE_ASC to listOf("b", "c", "a"),
            SortOrder.TITLE_DESC to listOf("a", "c", "b"),
        )
        expected.forEach { (sort, ids) ->
            assertEquals(sort.name, ids, sample.filtered("", HomePreferences(sort = sort)).map { it.id })
        }
    }

    @Test
    fun `multiple selected tags require every tag rather than any tag`() {
        val result = sample.filtered("", HomePreferences(tagIds = setOf(research.id, work.id)))
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `single tag includes entries that also have other tags`() {
        val result = sample.filtered("", HomePreferences(tagIds = setOf(research.id), sort = SortOrder.CREATED_DESC))
        assertEquals(listOf("c", "a"), result.map { it.id })
    }

    @Test
    fun `legacy summary is no longer searchable`() {
        val result = sample.filtered("文献", HomePreferences())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `search finds tag names and ignores letter case`() {
        val result = sample.filtered("rEsEaRcH", HomePreferences())
        assertEquals(listOf("a", "c"), result.map { it.id })
    }

    @Test
    fun `title and summary searches ignore case and surrounding whitespace`() {
        assertEquals(listOf("b"), sample.filtered("  ALPHA  ", HomePreferences()).map { it.id })
        assertTrue(sample.filtered(" cOMPose ", HomePreferences()).isEmpty())
    }

    @Test
    fun `search is restricted to title summary and tags`() {
        assertTrue(sample.filtered("特殊词", HomePreferences()).isEmpty())
    }

    @Test
    fun `query tag filters and sorting compose in one result`() {
        val combined = sample.filtered("Research", HomePreferences(
            tagIds = setOf(research.id), sort = SortOrder.CREATED_DESC,
        ))
        assertEquals(listOf("c", "a"), combined.map { it.id })
        val narrowed = sample.filtered("Compose", HomePreferences(tagIds = setOf(research.id)))
        assertTrue(narrowed.isEmpty())
    }

    @Test
    fun `clearing search and tags restores every entry`() {
        val all = sample.filtered("   ", HomePreferences(sort = SortOrder.CREATED_ASC))
        assertEquals(listOf("a", "c", "b"), all.map { it.id })
    }

    @Test
    fun `all sorts use stable id order for ties independently of insertion order`() {
        val tied = Library(entries = listOf("c", "a", "b").map { id ->
            Entry(id = id, title = "同一个标题", createdAt = 1, updatedAt = 1)
        })
        SortOrder.entries.forEach { sort ->
            assertEquals(sort.name, listOf("a", "b", "c"),
                tied.filtered("", HomePreferences(sort = sort)).map { it.id })
        }
    }

    @Test
    fun `title sorting treats different letter cases as ties`() {
        val titles = Library(entries = listOf(
            Entry(id = "b", title = "ALPHA"), Entry(id = "a", title = "alpha"),
        ))
        listOf(SortOrder.TITLE_ASC, SortOrder.TITLE_DESC).forEach { sort ->
            assertEquals(listOf("a", "b"), titles.filtered("", HomePreferences(sort = sort)).map { it.id })
        }
    }

    @Test
    fun `missing selected tags produce an empty result safely`() {
        assertTrue(sample.filtered("", HomePreferences(tagIds = setOf("deleted-tag"))).isEmpty())
        assertTrue(Library().filtered("anything", HomePreferences()).isEmpty())
    }

    @Test
    fun `changing visual layout preserves search and sort results`() {
        val prefs = HomePreferences(cards = true, sort = SortOrder.TITLE_DESC, tagIds = setOf(work.id))
        assertEquals(sample.filtered("", prefs), sample.filtered("", prefs.copy(cards = false)))
    }
}
