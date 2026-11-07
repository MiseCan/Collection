package com.collection.domain

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

data class Attachment(
    val id: String = newId(),
    val name: String,
    val mime: String,
    val size: Long,
    val path: String,
    val title: String = "",
)

data class Tag(val id: String = newId(), val name: String)

data class Entry(
    val id: String = newId(),
    val title: String = "",
    val summary: String = "",
    val body: String = "",
    val attachments: List<Attachment> = emptyList(),
    val tagIds: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val parseWarnings: List<String> = emptyList(),
    val iconKey: String = "auto",
    val bodyTitle: String = "",
)

data class Library(val entries: List<Entry> = emptyList(), val tags: List<Tag> = emptyList())
enum class SortOrder(val label: String) {
    CREATED_DESC("创建时间 · 从新到旧"), CREATED_ASC("创建时间 · 从旧到新"),
    UPDATED_DESC("更新时间 · 从新到旧"), UPDATED_ASC("更新时间 · 从旧到新"),
    TITLE_ASC("标题 · 升序"), TITLE_DESC("标题 · 降序")
}
data class HomePreferences(val cards: Boolean = true, val sort: SortOrder = SortOrder.UPDATED_DESC, val tagIds: Set<String> = emptySet())
data class AiConfig(
    val apiKey: String = "",
    val endpoint: String = "https://api.deepseek.com/chat/completions",
    val model: String = "deepseek-flash",
    val proxyMode: Boolean = false,
)
data class AiResult(val title: String, val warnings: List<String> = emptyList(), val webSources: List<String> = emptyList())

fun Library.filtered(query: String, prefs: HomePreferences): List<Entry> {
    val tagNames = tags.associate { it.id to it.name }
    val q = query.trim()
    val filtered = entries.filter { entry ->
        entry.tagIds.containsAll(prefs.tagIds) && (q.isEmpty() ||
            entry.title.contains(q, true) ||
            entry.tagIds.any { tagNames[it]?.contains(q, true) == true })
    }
    val comparator = when (prefs.sort) {
        SortOrder.CREATED_DESC -> compareByDescending<Entry> { it.createdAt }
        SortOrder.CREATED_ASC -> compareBy<Entry> { it.createdAt }
        SortOrder.UPDATED_DESC -> compareByDescending<Entry> { it.updatedAt }
        SortOrder.UPDATED_ASC -> compareBy<Entry> { it.updatedAt }
        SortOrder.TITLE_ASC -> compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        SortOrder.TITLE_DESC -> compareByDescending<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
    }
    return filtered.sortedWith(comparator.thenBy { it.id })
}
