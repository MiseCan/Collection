package com.collection.ai

import com.collection.domain.extractWebLinks
import com.collection.domain.withoutWebLinksForAi
import com.collection.web.WebPage
import kotlinx.coroutines.CancellationException

internal data class LinkedText(val label: String, val text: String)
internal data class LinkedSources(val texts: List<LinkedText>, val warnings: List<String>, val domains: List<String>)

internal suspend fun readLinkedSources(texts: List<LinkedText>, read: suspend (String) -> WebPage,
    onProgress: (String) -> Unit = {}): LinkedSources {
    val links = texts.asSequence().flatMap { extractWebLinks(it.text).asSequence() }.distinctBy { it.url }.take(4).toList()
    if (links.isEmpty()) return LinkedSources(texts, emptyList(), emptyList())
    val warnings = mutableListOf<String>()
    if (links.size > 3) warnings += "网页链接：此次只读取前3个网页，其余链接未参与标题生成"
    val result = texts.mapNotNull { source ->
        withoutWebLinksForAi(source.text).takeIf { it.isNotBlank() }?.let { source.copy(text = it) }
    }.toMutableList()
    links.take(3).forEachIndexed { index, link ->
        onProgress("正在读取网页 ${index + 1}/${minOf(3, links.size)}：${link.domain}")
        try {
            val page = read(link.url)
            result += LinkedText("网页·${link.domain} ${page.title}", page.text)
            if (page.truncated) warnings += "网页·${link.domain}：网页较长，只读取了部分正文"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { warnings += "网页·${link.domain}：${e.message ?: "读取失败"}，未参与AI标题" }
    }
    return LinkedSources(result, warnings, links.map { it.domain }.distinct())
}

internal fun mergeWebReadWarnings(previous: List<String>, warnings: List<String>, domains: List<String>): List<String> =
    (previous.filterNot { value -> domains.isNotEmpty() &&
        (value.startsWith("网页链接：") || domains.any { value.startsWith("网页·$it：") }) } + warnings).distinct()
