package com.collection.domain

import java.net.IDN
import java.net.URI
import java.util.Locale

data class WebLink(val url: String, val domain: String)

private val webCandidate = Regex(
    """(?i)(?<![\p{L}\p{N}_@/.-])(?:https?://[^\s<>"'，。；！？、（）【】]+|(?:www\.)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:[a-z]{2,63}|xn--[a-z0-9-]+)(?::[0-9]{1,5})?(?:[/?#][^\s<>"'，。；！？、（）【】]*)?)""",
)

/** Local extraction only: no page requests, tracking, or changes to the original text. */
fun extractWebLinks(text: String): List<WebLink> {
    val links = linkedMapOf<String, WebLink>()
    for (match in webCandidate.findAll(text)) {
        var raw = match.value.trimEnd('.', ',', ';', '!', '?', ':', ']', '}')
        // Preserve balanced parentheses in Wikipedia and similar paths; strip prose/Markdown closers.
        while (raw.endsWith(')') && raw.count { it == ')' } > raw.count { it == '(' }) raw = raw.dropLast(1)
        if (raw.isEmpty()) continue
        val explicit = raw.startsWith("http://", true) || raw.startsWith("https://", true)
        val parsed = runCatching { URI(if (explicit) raw else "https://$raw") }.getOrNull() ?: continue
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: continue
        if (scheme != "http" && scheme != "https") continue
        val authority = parsed.rawAuthority ?: continue
        if ('@' in authority) continue
        val rawPort = authority.substringAfter(':', "")
        if (':' in authority && (rawPort.toIntOrNull() !in 1..65535)) continue
        val host = parsed.host ?: authority.substringBefore(':')
        val domain = runCatching { IDN.toASCII(host).lowercase(Locale.ROOT) }.getOrNull() ?: continue
        if (domain.isBlank() || domain.any { it.isWhitespace() } || parsed.port > 65535) continue
        // Retain the path/query encoding verbatim; normalize only scheme and domain.
        val port = authority.substringAfter(':', "").let { if (it.isEmpty()) "" else ":$it" }
        val tail = (parsed.rawPath ?: "") + (parsed.rawQuery?.let { "?$it" } ?: "") +
            (parsed.rawFragment?.let { "#$it" } ?: "")
        val url = "$scheme://$domain$port$tail"
        val key = url.removeSuffix("/")
        links.putIfAbsent(key, WebLink(url, domain.removePrefix("www.")))
    }
    return links.values.toList()
}

/** AI receives fetched facts, never a URL as a substitute for unread webpage content. */
internal fun withoutWebLinksForAi(text: String): String = webCandidate.replace(text) { match ->
    if (extractWebLinks(match.value).isNotEmpty()) " " else match.value
}.trim()
