@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.collection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.collection.data.TrashedEntry
import java.text.DateFormat
import java.util.Date

@Composable
fun TrashScreen(entries: List<TrashedEntry>, busy: Boolean, onBack: () -> Unit,
    onRestore: (Set<String>) -> Unit, onPurge: (Set<String>) -> Unit, backEnabled: Boolean = true) {
    var batch by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmPurge by remember { mutableStateOf(false) }
    val ids = remember(entries) { entries.map { it.id }.toSet() }
    val currentSelection = selected.intersect(ids)
    fun closeBatch() { batch = false; selected = emptySet() }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    LaunchedEffect(ids) {
        selected = selected.intersect(ids)
        if (ids.isEmpty()) closeBatch()
    }
    rememberPredictiveBackMotion(enabled = batch && backEnabled) { closeBatch() }
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val scrolling = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scrolling.nestedScrollConnection),
        topBar = { CollectionTopBar("回收站", { if (batch) closeBatch() else onBack() }, scrolling) },
        bottomBar = {
            MotionReveal(batch) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center) {
                    val all = ids.isNotEmpty() && currentSelection == ids
                    FloatingBatchBar(currentSelection.size, all,
                        onAll = { selected = if (all) emptySet() else ids },
                        onDelete = { confirmPurge = true }, onClose = { closeBatch() }, deleteLabel = "永久清除", enabled = !busy) {
                        IconButton(onClick = { onRestore(currentSelection) }, enabled = !busy && currentSelection.isNotEmpty()) {
                            Icon(Icons.Default.RestoreFromTrash, "恢复")
                        }
                    }
                }
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("条目在回收站保留 30 天，到期后自动清除。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entries.isEmpty()) item {
                Text("回收站为空", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                items(entries, key = { it.id }) { entry ->
                    val checked = entry.id in currentSelection
                    val shape = MaterialTheme.shapes.large
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MotionReveal(batch, horizontal = true) {
                            Checkbox(checked, onCheckedChange = { toggle(entry.id) }, enabled = !busy)
                        }
                        Surface(modifier = Modifier.weight(1f).clip(shape).combinedClickable(enabled = !busy,
                            onClick = { if (batch) toggle(entry.id) },
                            onLongClick = { batch = true; if (!checked) toggle(entry.id) }),
                            shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            ListItem(content = { Text(entry.title.ifBlank { "未命名收录" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text("删除于 ${dates.format(Date(entry.deletedAt))}") },
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                        }
                    }
                }
            }
        }
    }
    if (confirmPurge && currentSelection.isNotEmpty()) AlertDialog(onDismissRequest = { confirmPurge = false },
        title = { Text("永久清除 ${currentSelection.size} 个条目？") }, text = { Text("启用云同步时，清除会同步到云端和其他设备。") },
        confirmButton = { TextButton(onClick = { confirmPurge = false; onPurge(currentSelection) }, enabled = !busy) {
            Text("永久清除", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("取消") } })
}
