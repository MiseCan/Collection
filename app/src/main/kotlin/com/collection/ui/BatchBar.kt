package com.collection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun BatchBar(count: Int, allSelected: Boolean, onAll: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionSwap(count, Modifier.weight(1f)) { Text("已选 $it", style = MaterialTheme.typography.labelLarge) }
            TextButton(onClick = onAll) { MotionSwap(allSelected) { Text(if (it) "取消全选" else "全选") } }
            TextButton(onClick = onDelete, enabled = count > 0) { Text("删除") }
            TextButton(onClick = onClose) { Text("完成") }
        }
    }
}

@Composable
internal fun FloatingBatchBar(
    count: Int,
    allSelected: Boolean,
    onAll: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    deleteLabel: String = "删除",
    enabled: Boolean = true,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    HorizontalFloatingToolbar(expanded = true, modifier = modifier.height(FloatingToolbarDefaults.ContainerSize)) {
        Text("已选 $count", Modifier.weight(1f, fill = false).align(Alignment.CenterVertically)
            .padding(start = 12.dp, end = 8.dp).widthIn(max = 128.dp),
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onAll, enabled = enabled) {
            Icon(if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                if (allSelected) "取消全选" else "全选")
        }
        extraActions()
        IconButton(onClick = onDelete, enabled = enabled && count > 0) { Icon(Icons.Default.DeleteOutline, deleteLabel) }
        IconButton(onClick = onClose) { Icon(Icons.Default.Check, "完成") }
    }
}
