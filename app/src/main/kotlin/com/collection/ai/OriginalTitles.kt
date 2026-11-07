package com.collection.ai

import com.collection.domain.*
import kotlinx.coroutines.CancellationException

internal data class OriginalTitles(val title: String, val bodyTitle: String, val attachments: List<Attachment>, val warnings: List<String>)

/** Null selects only the entry's raw text; an ID selects exactly one attachment. */
internal suspend fun generateSourceTitle(source: Entry, attachmentId: String?,
    generate: suspend (String, List<Attachment>, (String) -> Unit) -> AiResult,
    onProgress: (String) -> Unit = {},
): AiResult = if (attachmentId == null) {
    require(source.body.isNotBlank()) { "没有可读取的文字" }
    generate(source.body, emptyList(), onProgress)
} else {
    val attachment = source.attachments.firstOrNull { it.id == attachmentId }
        ?: throw IllegalArgumentException("原件已不存在")
    generate("", listOf(attachment), onProgress)
}

/** Each original is analyzed alone; only short generated topics are sent for the entry title. */
internal suspend fun generateOriginalTitles(source: Entry,
    generate: suspend (String, List<Attachment>, (String) -> Unit) -> AiResult,
    onProgress: (String) -> Unit = {},
    combineTopics: (suspend (String, (String) -> Unit) -> AiResult)? = null,
): OriginalTitles {
    val warnings = mutableListOf<String>()
    val topics = mutableListOf<String>()
    var bodyTitle = source.bodyTitle
    if (source.body.isNotBlank()) {
        try {
            val result = generate(source.body, emptyList()) { onProgress("文字：$it") }
            bodyTitle = result.title; topics += result.title; warnings += result.warnings
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { warnings += "文字：${e.message ?: "标题生成失败"}" }
    }
    val attachments = source.attachments.map { file ->
        try {
            val result = generate("", listOf(file)) { onProgress(it) }
            topics += result.title; warnings += result.warnings
            file.copy(title = result.title)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { warnings += "${file.name}：${e.message ?: "标题生成失败"}"; file }
    }
    if (topics.isEmpty()) throw java.io.IOException(warnings.firstOrNull() ?: "请先添加文字或可读取的资料")
    val title = if (topics.size == 1) topics.first() else {
        try {
            val progress: (String) -> Unit = { onProgress("条目：$it") }
            (combineTopics?.invoke(topics.joinToString("\n"), progress)
                ?: generate(topics.joinToString("\n"), emptyList(), progress)).title
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { warnings += "条目标题综合失败，暂用首份资料标题"; topics.first() }
    }

    return OriginalTitles(title, bodyTitle, attachments, warnings.distinct())
}
