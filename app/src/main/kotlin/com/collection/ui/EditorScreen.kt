@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.collection.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.collection.domain.*

@Composable
fun EditorScreen(
    draft: Entry, tags: List<Tag>, generating: Boolean, progress: String, aiEdited: Boolean,
    onChange: (Entry, Boolean) -> Unit, onBack: () -> Unit, onAddFiles: () -> Unit,
    onPreview: (Attachment) -> Unit, onRemove: (String) -> Unit,
    onGenerate: () -> Unit, onCancelGeneration: () -> Unit, onAddTag: (String) -> Unit, onSave: () -> Unit,
    hasUnsavedChanges: Boolean = false, onDeleteTag: (Tag) -> Unit = {},
) {
    var confirmReplace by remember { mutableStateOf(false) }
    var editingSourceTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val enabled = !generating
    val effects = CollectionMotionScheme.fastEffectsSpec<Float>()
    val placement = CollectionMotionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    // Unsaved work must stay on this destination; the page follows the system gesture itself
    // instead of letting NavHost pop it early. The real discard/cancel action runs on completion.
    val backMotion = rememberPredictiveBackMotion(enabled = hasUnsavedChanges) { onBack() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        .predictiveBackTransform(backMotion), topBar = {
        CollectionTopBar("新建条目", onBack, scrollBehavior)
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                top = 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "entry_title") {
                Column(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = draft.title, onValueChange = { onChange(draft.copy(title = it), true) },
                            label = { Text("标题") }, maxLines = 2, enabled = enabled,
                            modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.large)
                        FilledTonalIconButton(onClick = {
                            if (aiEdited && draft.title.isNotBlank()) confirmReplace = true else onGenerate()
                        }, enabled = enabled) {
                            Icon(Icons.Default.AutoAwesome, if (draft.title.isBlank()) "生成标题" else "重新生成标题")
                        }
                    }
                    MotionReveal(generating) { Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(32.dp))
                        Text(progress, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onCancelGeneration) { Text("取消") }
                    }
                    }
                    MotionReveal(draft.parseWarnings.isNotEmpty()) { WarningPanel(draft.parseWarnings) }
                }
            }
            item(key = "file_picker") {
                val outline = MaterialTheme.colorScheme.primary.copy(alpha = .55f)
                Surface(
                    onClick = onAddFiles, enabled = enabled,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects).height(120.dp).drawWithContent {
                        drawContent()
                        val stroke = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 7.dp.toPx())))
                        drawRoundRect(outline, cornerRadius = CornerRadius(28.dp.toPx()), style = stroke)
                    },
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        FilledTonalIconButton(onClick = onAddFiles, enabled = enabled, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Add, "添加多个文件")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("添加文件", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (draft.attachments.isNotEmpty()) {
                item(key = "attachment_heading") { Box(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects)) { SectionHeading("已添加文件", "${draft.attachments.size} 份原件") } }
                items(draft.attachments, key = { "attachment:${it.id}" }) { attachment ->
                    Column(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects)) {
                        AttachmentRow(attachment, { onPreview(attachment) }, if (enabled) ({
                            if (editingSourceTitle == attachment.id) { focusManager.clearFocus(); editingSourceTitle = null }
                            onRemove(attachment.id)
                        }) else null,
                            onRename = if (enabled) ({
                                if (editingSourceTitle == attachment.id) focusManager.clearFocus()
                                editingSourceTitle = if (editingSourceTitle == attachment.id) null else attachment.id
                            }) else null)
                        MotionReveal(editingSourceTitle == attachment.id) { OutlinedTextField(
                            value = attachment.title, onValueChange = { title ->
                                onChange(draft.copy(attachments = draft.attachments.map { if (it.id == attachment.id) it.copy(title = title) else it }), true)
                            }, label = { Text("原件标题") }, placeholder = { Text(attachment.name) },
                            modifier = Modifier.fillMaxWidth(), enabled = enabled, maxLines = 2,
                            trailingIcon = { IconButton(onClick = { editingSourceTitle = null; focusManager.clearFocus() }) { Icon(Icons.Default.Check, "完成标题修改") } }) }
                    }
                }
            }
            item(key = "body") {
                OutlinedTextField(
                    value = draft.body, onValueChange = { onChange(draft.copy(body = it), false) },
                    label = { Text("文字与笔记") }, placeholder = { Text("记录想法，或粘贴需要收录的文字…") },
                    minLines = 3, enabled = enabled, modifier = Modifier.fillMaxWidth().animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects).motionContentSize(), shape = MaterialTheme.shapes.large,
                )
            }
            if (draft.body.isNotBlank()) item(key = "body_title") {
                OutlinedTextField(value = draft.bodyTitle,
                    onValueChange = { onChange(draft.copy(bodyTitle = it), true) },
                    label = { Text("原始文字标题") }, placeholder = { Text("文字内容") },
                    modifier = Modifier.fillMaxWidth().animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects), enabled = enabled, maxLines = 2, shape = MaterialTheme.shapes.large)
            }


            item(key = "tags") {
                Column(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading("标签", "可多选")
                    EntryTagsRow(tags, draft.tagIds, enabled,
                        onToggle = { tag -> onChange(draft.copy(tagIds = if (tag.id in draft.tagIds) draft.tagIds - tag.id else draft.tagIds + tag.id), false) },
                        onAdd = onAddTag, onDelete = onDeleteTag)
                }
            }
            item(key = "save") {
                Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth().animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects).height(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text("保存条目")
                }

            }
        }
    }
    if (confirmReplace) AlertDialog(
        onDismissRequest = { confirmReplace = false }, title = { Text("替换已修改的内容？") },
        text = { Text("生成成功后将更新条目及原件标题。生成失败时保留现有内容。") },
        confirmButton = { TextButton(onClick = { confirmReplace = false; onGenerate() }) { Text("重新生成并替换") } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } },
    )
}

@Composable
fun SectionHeading(title: String, supporting: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (supporting.isNotBlank()) Text(supporting, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AttachmentRow(attachment: Attachment, onClick: () -> Unit, onRemove: (() -> Unit)? = null, onRename: (() -> Unit)? = null) {
    ListItem(
        content = { Text(attachment.title.ifBlank { attachment.name }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(formatSize(attachment.size) + " · 点击预览") },
        leadingContent = {
            Icon(when {
                attachment.mime.startsWith("image/") -> Icons.Default.Image
                attachment.name.endsWith(".pdf", true) -> Icons.Default.PictureAsPdf
                else -> Icons.Default.Description
            }, null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
          Row {
            if (onRename != null) IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "修改标题 ${attachment.name}") }
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "移除 ${attachment.name}") }
            else Icon(Icons.Default.ChevronRight, null)
          }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / 1048576.0)
}

@Composable
fun WarningPanel(warnings: List<String>) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("部分资料读取提示", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}


