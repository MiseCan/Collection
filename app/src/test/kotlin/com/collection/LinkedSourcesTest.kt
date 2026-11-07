package com.collection

import com.collection.ai.*
import com.collection.web.WebPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LinkedSourcesTest {
    @Test fun addsFetchedFactsKeepsNotesAndDoesNotRecursivelyFollowPageLinks() = runBlocking {
        val fetched = mutableListOf<String>()
        val original = "我的学习笔记 https://example.com/article"
        val result = readLinkedSources(listOf(LinkedText("文字", original)), read = { url ->
            fetched += url; WebPage(url, "真正的网页标题", "网页正文的实验事实，含有 https://nested.example/link", emptyList(), false)
        })
        assertEquals(listOf("https://example.com/article"), fetched)
        assertTrue(result.texts.any { it.text.contains("实验事实") })
        assertTrue(result.texts.any { it.text.contains("我的学习笔记") && !it.text.contains("example.com") })
        assertEquals(listOf("example.com"), result.domains); assertTrue(result.warnings.isEmpty())
        assertEquals("我的学习笔记 https://example.com/article", original)
    }

    @Test fun unreadableUrlsAreNotSentAsFactsButManualNotesSurvive() = runBlocking {
        val result = readLinkedSources(listOf(LinkedText("文字", "手动记录 https://private.example/page")), { throw java.io.IOException("需要登录") })
        assertEquals("手动记录", result.texts.single().text)
        assertTrue(result.warnings.single().contains("需要登录")); assertFalse(result.texts.any { it.text.contains("private.example") })
        val onlyLink = readLinkedSources(listOf(LinkedText("文字", "https://private.example/page")), { throw java.io.IOException("需要登录") })
        assertTrue(onlyLink.texts.isEmpty())
    }

    @Test fun limitsRequestsDeduplicatesUrlsAndReportsPartialContent() = runBlocking {
        var requests = 0
        val urls = (1..5).joinToString(" ") { "https://site$it.example/article" }
        val result = readLinkedSources(listOf(LinkedText("文字", "$urls https://site1.example/article")), { url ->
            requests++; WebPage(url, "页面", "真实内容", emptyList(), true)
        })
        assertEquals(3, requests)
        assertTrue(result.warnings.any { it.contains("前3个") }); assertEquals(3, result.warnings.count { it.contains("部分正文") })
        assertFalse(result.texts.any { it.text.contains("site4.example") || it.text.contains("site5.example") })
    }

    @Test fun cancellationIsNotConvertedIntoAPartialSuccess() = runBlocking {
        try {
            readLinkedSources(listOf(LinkedText("文字", "https://example.com")), { throw CancellationException("cancel") })
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun successfulRetryClearsOnlyThatWebsitesPreviousWarning() {
        val previous = listOf("网页·example.com：读取失败", "网页·other.example：需要登录", "原件.pdf：部分页面不可读")
        val merged = mergeWebReadWarnings(previous, emptyList(), listOf("example.com"))
        assertEquals(previous.drop(1), merged)
    }
}
