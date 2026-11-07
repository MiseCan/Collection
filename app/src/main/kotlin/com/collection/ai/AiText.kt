package com.collection.ai

import org.json.JSONObject

internal data class GeneratedText(val title: String)

/** Splits without dropping a character, including supplementary Unicode code points. */
internal fun chunkText(text: String, maxChars: Int = 12_000): List<String> {
    require(maxChars >= 2)
    if (text.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + maxChars).coerceAtMost(text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        if (end < text.length) {
            val paragraph = text.lastIndexOf('\n', end - 1)
            if (paragraph > start + maxChars / 2) end = paragraph + 1
        }
        result += text.substring(start, end)
        start = end
    }
    return result
}

internal fun validateOutput(content: String, finishReason: String): GeneratedText {
    require(finishReason == "stop") { "AI 输出未完整结束，请重试" }
    require(content.isNotBlank()) { "AI 返回了空结果，请重试" }
    val json = try { JSONObject(content) } catch (_: Exception) {
        throw IllegalArgumentException("AI 返回的 JSON 无法解析，请重试")
    }
    val titleValue = json.opt("title")
    require(titleValue is String) { "AI 返回字段格式不正确，请重试" }
    val title = titleValue.trim()
    require(title.isNotBlank()) { "AI 返回的标题为空，请重试" }
    require(title.codePointCount(0, title.length) <= 30) { "AI 标题超过 30 字，请重试" }
    return GeneratedText(title)
}
