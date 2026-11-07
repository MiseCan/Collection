@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.collection.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.collection.domain.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class SourceSelection(val attachmentId: String?, val title: String)

@Composable
fun DetailScreen(entry: Entry, tags: List<Tag>, onBack: () -> Unit,
    onDelete: () -> Unit, onPreview: (Attachment) -> Unit, onText: (() -> Unit)? = null,
    onOpenLink: ((String) -> Unit)? = null, onUpdate: (Entry) -> Unit = {},
    onAddFiles: () -> Unit = {}, onAddTag: (String) -> Unit = {},
    onGenerateSource: (String?) -> Unit = {}, onGenerateEntry: () -> Unit = {},
    generating: Boolean = false, progress: String = "", onCancelGeneration: () -> Unit = {},
    onDeleteTag: (Tag) -> Unit = {}, onToggleTag: ((Tag) -> Unit)? = null) {
    var viewingText by rememberSaveable(entry.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmBatch by remember { mutableStateOf(false) }
    val sourceIds = entry.attachments.map { it.id }.toSet() + if (entry.body.isNotBlank()) setOf("__body__") else emptySet()
    fun select(id: String) { selected = if (id in selected) selected - id else selected + id }
    rememberPredictiveBackMotion(enabled = batch) { batch = false; selected = emptySet() }
    var titleEditing by rememberSaveable(entry.id) { mutableStateOf(false) }
    var title by rememberSaveable(entry.id) { mutableStateOf(entry.title) }
    var sourceTitle by remember { mutableStateOf<SourceSelection?>(null) }
    var removeSource by remember { mutableStateOf<SourceSelection?>(null) }
    var generateSource by remember { mutableStateOf<SourceSelection?>(null) }
    var generateEntry by remember { mutableStateOf(false) }
    val links = remember(entry.body) { extractWebLinks(entry.body) }
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf<String?>(null) }
    val enabled = !generating
    val effects = CollectionMotionScheme.fastEffectsSpec<Float>()
    val placement = CollectionMotionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val focusManager = LocalFocusManager.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val titleFocus = remember { FocusRequester() }
    fun requestSourceAi(source: SourceSelection) {
        if (source.title.isBlank()) onGenerateSource(source.attachmentId) else generateSource = source
    }
    Scaffold(modifier = Modifier.nestedScroll(scroll.nestedScrollConnection), topBar = {
        CollectionTopBar(entry.title, onBack, scroll, titleContent = {
            MotionSwap(titleEditing, Modifier.fillMaxWidth()) { editing ->
            if (editing) {
              LaunchedEffect(Unit) { titleFocus.requestFocus() }
              OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("detail_title_input").focusRequester(titleFocus), enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (enabled && title.isNotBlank()) { onUpdate(entry.copy(title = title.trim())); titleEditing = false; focusManager.clearFocus() }
                }),
                trailingIcon = { IconButton(enabled = enabled && title.isNotBlank(), onClick = {
                    onUpdate(entry.copy(title = title.trim())); titleEditing = false; focusManager.clearFocus()
                }) { Icon(Icons.Default.Check, "保存标题") } })
            }
            else Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = "修改标题") {
                    title = entry.title; titleEditing = true
                })
            }
        }) {
            IconButton(enabled = enabled, onClick = { generateEntry = true }) { Icon(Icons.Default.AutoAwesome, "AI 生成条目标题") }
            IconButton(enabled = enabled, onClick = { confirmDelete = true }) { Icon(Icons.Default.DeleteOutline, "删除条目") }
        }
    }) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp,
            bottom = padding.calculateBottomPadding() + 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding()) {
            item(key = "properties") {
                Column(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val date = remember(entry) { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.CHINA) }
                    Text("创建 ${date.format(Date(entry.createdAt))}\n更新 ${date.format(Date(entry.updatedAt))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "tags") {
                Box(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects)) {
                EntryTagsRow(tags, entry.tagIds, enabled,
                    onToggle = { tag -> if (onToggleTag != null) onToggleTag(tag) else onUpdate(entry.copy(tagIds = if (tag.id in entry.tagIds) entry.tagIds - tag.id else entry.tagIds + tag.id)) },
                    onAdd = onAddTag, onDelete = onDeleteTag)
                }
            }
            if (generating) item(key = "ai_progress") {
                Row(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Text(progress, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancelGeneration) { Text("取消") }
                }
            }
            if (entry.parseWarnings.isNotEmpty()) item(key = "parse_warnings") {
                Box(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects)) { WarningPanel(entry.parseWarnings) }
            }
            // Links precede the original-content heading and every original card.
            items(links, key = { "link:${it.url}" }) { link ->
                Box(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects)) {
                WebLinkCard(link) { url ->
                    try { if (onOpenLink != null) onOpenLink(url) else uriHandler.openUri(url); linkError = null }
                    catch (_: Exception) { linkError = "没有可打开链接的应用，请安装浏览器后重试。" }
                }
            }
            }
            if (linkError != null) item(key = "link_error") { Text(linkError!!, Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects), color = MaterialTheme.colorScheme.error) }
            item(key = "source_heading") {
                Row(Modifier.fillMaxWidth().animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects), verticalAlignment = Alignment.CenterVertically) {
                    Text("原始内容", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(enabled = enabled, onClick = { batch = !batch; selected = emptySet() }) { MotionSwap(batch) { Text(if (it) "退出批量" else "批量管理") } }
                    IconButton(enabled = enabled, onClick = onAddFiles) { Icon(Icons.Default.Add, "添加内容") }
                }
            }
            if (batch) item(key = "batch") {
                Box(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects)) {
                    BatchBar(selected.count { it in sourceIds }, selected.containsAll(sourceIds),
                        { selected = if (selected.containsAll(sourceIds)) emptySet() else sourceIds },
                        { confirmBatch = true }, { batch = false; selected = emptySet() })
                }
            }
            if (entry.body.isBlank() && !batch) item(key = "empty_body") {
                Box(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects)) {
                    OriginalTextCard("添加文字") { if (onText != null) onText() else viewingText = true }
                }
            }
            if (entry.body.isNotBlank()) item(key = "body") {
                val selection = SourceSelection(null, entry.bodyTitle)
                SourceActions(enabled, onAi = { requestSourceAi(selection) }, onRename = { sourceTitle = selection },
                    onRemove = { removeSource = selection },
                    modifier = Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects)) { longPress ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MotionReveal(batch, horizontal = true) { Checkbox(checked = "__body__" in selected, onCheckedChange = { select("__body__") }) }
                        Box(Modifier.weight(1f)) { OriginalTextCard(entry.bodyTitle, hasLinks = links.isNotEmpty(), onLongClick = longPress, webUrl = links.firstOrNull()?.url) {
                            if (batch) select("__body__") else if (onText != null) onText() else viewingText = true
                        } }
                    }
                }
            }
            items(entry.attachments, key = { it.id }) { attachment ->
                val selection = SourceSelection(attachment.id, attachment.title)
                SourceActions(enabled, onAi = { requestSourceAi(selection) }, onRename = { sourceTitle = selection },
                    onRemove = { removeSource = selection },
                    modifier = Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects)) { longPress ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MotionReveal(batch, horizontal = true) { Checkbox(checked = attachment.id in selected, onCheckedChange = { select(attachment.id) }) }
                        Box(Modifier.weight(1f)) { OriginalFileCard(attachment, onLongClick = longPress) {
                            if (batch) select(attachment.id) else onPreview(attachment)
                        } }
                    }
                }
            }
        }
    }
    if (viewingText) OriginalTextViewer(entry.body, entry.bodyTitle.ifBlank { "文字与笔记" },
        onSaveText = if (enabled) ({ onUpdate(entry.copy(body = it)) }) else null) { viewingText = false }
    if (confirmBatch) DetailConfirmation("删除所选原始内容？", "选中的文字和附件将从此条目移除。", "删除", { confirmBatch = false }) {
        confirmBatch = false
        onUpdate(entry.copy(body = if ("__body__" in selected) "" else entry.body,
            bodyTitle = if ("__body__" in selected) "" else entry.bodyTitle,
            attachments = entry.attachments.filterNot { it.id in selected }))
        selected = emptySet(); batch = false
    }
    sourceTitle?.let { target ->
        DetailTextEditor(if (target.attachmentId == null) "原始文字标题" else "原件标题", target.title,
            onDismiss = { sourceTitle = null }) { value ->
            sourceTitle = null
            onUpdate(if (target.attachmentId == null) entry.copy(bodyTitle = value.trim())
                else entry.copy(attachments = entry.attachments.map { if (it.id == target.attachmentId) it.copy(title = value.trim()) else it }))
        }
    }
    generateSource?.let { target ->
        DetailConfirmation("替换原件标题？", "只读取这份原始资料并替换它的标题，其他内容保留。", "生成", { generateSource = null }) {
            generateSource = null; onGenerateSource(target.attachmentId)
        }
    }
    if (generateEntry) DetailConfirmation("生成条目标题？", "根据本条目的资料生成标题，原件标题保留。", "生成", { generateEntry = false }) {
        generateEntry = false; onGenerateEntry()
    }
    removeSource?.let { target ->
        DetailConfirmation("移除原始内容？", "将从此条目移除这份资料。", "移除", { removeSource = null }) {
            removeSource = null
            onUpdate(if (target.attachmentId == null) entry.copy(body = "", bodyTitle = "")
                else entry.copy(attachments = entry.attachments.filterNot { it.id == target.attachmentId }))
        }
    }
    if (confirmDelete) DetailConfirmation("删除这个条目？", "条目和原件将移到回收站，30 天内可以恢复。", "删除", { confirmDelete = false }) {
        confirmDelete = false; onDelete()
    }
}

@Composable
private fun SourceActions(enabled: Boolean, onAi: () -> Unit, onRename: () -> Unit,
    onRemove: () -> Unit, modifier: Modifier = Modifier, content: @Composable (() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        content { if (enabled) expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("AI 生成标题") }, onClick = { expanded = false; onAi() })
            DropdownMenuItem(text = { Text("修改标题") }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text("移除") }, onClick = { expanded = false; onRemove() })
        }
    }
}

@Composable
private fun DetailTextEditor(label: String, initial: String, multiline: Boolean = false, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(label) }, text = {
        OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth().testTag("detail_field_input"),
            minLines = if (multiline) 4 else 1, maxLines = if (multiline) 10 else 2)
    }, confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun DetailConfirmation(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
