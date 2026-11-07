package com.collection.web

import okhttp3.HttpUrl
import org.jsoup.Jsoup

internal data class WebPage(val url: String, val title: String, val text: String,
    val icons: List<String>, val truncated: Boolean)

internal fun parseWebPage(bytes: ByteArray, url: HttpUrl, charset: String? = null, partial: Boolean = false): WebPage {
    val document = Jsoup.parse(bytes.inputStream(), charset, url.toString())
    val title = document.title().ifBlank { document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
        .replace(Regex("\\s+"), " ").trim().take(200)
    val base = document.selectFirst("base[href]")?.attr("href")?.let { url.resolve(it) } ?: url
    val icons = document.select("link[href]").mapNotNull { link ->
        val rel = link.attr("rel").lowercase().split(Regex("\\s+"))
        val priority = when { "apple-touch-icon" in rel || "apple-touch-icon-precomposed" in rel -> 0; "icon" in rel -> 1; else -> return@mapNotNull null }
        val resolved = base.resolve(link.attr("href")) ?: return@mapNotNull null
        if (link.attr("type").contains("svg", true) || resolved.encodedPath.endsWith(".svg", true)) return@mapNotNull null
        priority to resolved.toString()
    }.sortedBy { it.first }.map { it.second }.distinct().take(4) + url.resolve("/favicon.ico").toString()
    document.select("script,style,noscript,nav,header,footer,aside,form,iframe,svg,canvas,button,input,select,textarea,[hidden],[aria-hidden=true],.advertisement,.ad-banner,.cookie-banner,.cookie-consent,.sidebar").remove()
    val candidates = document.select("article,main,[role=main],#js_content,#article_content,.article-content,.post-content,.entry-content")
    val root = candidates.maxByOrNull { it.text().length } ?: document.body()
    val text = root.text().replace(Regex("\\s+"), " ").trim()
    return WebPage(url.toString(), title, text.take(MAX_WEB_TEXT), icons.distinct(), partial || text.length > MAX_WEB_TEXT)
}

internal const val MAX_WEB_TEXT = 12_000
