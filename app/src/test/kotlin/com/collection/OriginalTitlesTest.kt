package com.collection

import com.collection.ai.generateOriginalTitles
import com.collection.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OriginalTitlesTest {
    @Test fun topicCombinationDoesNotTreatGeneratedTitlesAsNewWebSources() = runBlocking {
        var originals = 0
        var combinations = 0
        val result = generateOriginalTitles(Entry(body = "原始文字", attachments = listOf(pdf)),
            generate = { _, _, _ -> originals++; AiResult("example.com 资料主题") },
            combineTopics = { topics, _ -> combinations++; assertTrue(topics.contains("example.com")); AiResult("综合标题") })
        assertEquals(2, originals); assertEquals(1, combinations); assertEquals("综合标题", result.title)
    }
    @Test fun selectedSourceGenerationNeverSendsTheOtherOriginals() = runBlocking {
        val first = Attachment(id = "first", name = "one.pdf", mime = "application/pdf", size = 1, path = "/one")
        val second = first.copy(id = "second", name = "two.pdf", path = "/two")
        val entry = Entry(title = "条目", body = "只在选择文字时发送", attachments = listOf(first, second))
        val calls = mutableListOf<Pair<String, List<Attachment>>>()
        val generator: suspend (String, List<Attachment>, (String) -> Unit) -> AiResult = { body, files, _ ->
            calls += body to files; AiResult("单独生成的标题")
        }
        com.collection.ai.generateSourceTitle(entry, second.id, generator)
        assertEquals(listOf("" to listOf(second)), calls)
        calls.clear()
        com.collection.ai.generateSourceTitle(entry, null, generator)
        assertEquals(listOf(entry.body to emptyList<Attachment>()), calls)
    }
    private val pdf = Attachment(name = "a.pdf", mime = "application/pdf", size = 1, path = "a")
    private val image = Attachment(name = "b.png", mime = "image/png", size = 1, path = "b")
    @Test fun originalsGetIndependentTitlesAndEntryUsesOnlyShortTopics() = runBlocking {
        val calls = mutableListOf<Pair<String, List<Attachment>>>()
        val result = generateOriginalTitles(Entry(body = "原始正文", attachments = listOf(pdf, image)),
            generate = { text, files, _ ->
                calls += text to files
                AiResult(when { files.firstOrNull()?.id == pdf.id -> "PDF资料主题"
                    files.isNotEmpty() -> "图片资料主题"
                    text == "原始正文" -> "正文主题"
                    else -> "综合条目标题" })
            })
        assertEquals(4, calls.size)
        assertEquals("正文主题", result.bodyTitle)
        assertEquals(listOf("PDF资料主题", "图片资料主题"), result.attachments.map { it.title })
        assertEquals("综合条目标题", result.title)
        assertEquals("正文主题\nPDF资料主题\n图片资料主题", calls.last().first)
        assertTrue(calls.last().second.isEmpty())
    }
    @Test fun singleOriginalReusesItsTitleWithoutPayingForAnExtraCall() = runBlocking {
        var calls = 0
        val result = generateOriginalTitles(Entry(attachments = listOf(pdf)), generate = { _, _, _ -> calls++; AiResult("原件标题") })
        assertEquals(1, calls)
        assertEquals("原件标题", result.title)
        assertEquals("原件标题", result.attachments.single().title)
    }
    @Test fun partialFailureKeepsSuccessfulTitlesAndPreservesUnchangedOriginals() = runBlocking {
        val result = generateOriginalTitles(Entry(attachments = listOf(pdf, image)), generate = { _, files, _ ->
            if (files.single().id == pdf.id) throw java.io.IOException("解析失败")
            AiResult("图片主题")
        })
        assertEquals("", result.attachments.first().title)
        assertEquals(pdf.path, result.attachments.first().path)
        assertEquals("图片主题", result.attachments.last().title)
        assertTrue(result.warnings.single().contains(pdf.name))
    }
    @Test fun cancellationStopsInsteadOfStartingTheRemainingOriginals() {
        var calls = 0
        try {
            runBlocking {
                generateOriginalTitles(Entry(attachments = listOf(pdf, image)), generate = { _, _, _ -> calls++; throw CancellationException() })
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(1, calls) }
    }
}
