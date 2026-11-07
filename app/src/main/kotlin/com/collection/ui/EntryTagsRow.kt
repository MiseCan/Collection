package com.collection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import com.collection.domain.Tag

@Composable
internal fun EntryTagsRow(tags: List<Tag>, selected: Set<String>, enabled: Boolean,
    onToggle: (Tag) -> Unit, onAdd: (String) -> Unit, onDelete: (Tag) -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val duplicate = tags.any { it.name.equals(name.trim(), true) }
    val valid = enabled && name.isNotBlank() && !duplicate
    fun submit() {
        if (valid) { onAdd(name.trim()); name = ""; adding = false; focusManager.clearFocus() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(modifier = Modifier.fillMaxWidth().motionContentSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.sortedBy { if (it.id in selected) 0 else 1 }.forEach { tag -> key(tag.id) {
                var menu by remember { mutableStateOf(false) }
                MotionPlacement(Modifier.align(Alignment.CenterVertically)) {
                    TagFilterChip(tag.name, tag.id in selected, { onToggle(tag) }, { menu = true },
                        modifier = Modifier.testTag("entry_tag_${tag.id}"), enabled = enabled, longPressLabel = "标签操作")
                    DropdownMenu(expanded = menu && enabled, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete(tag) })
                    }
                }
            } }
            val rotation by animateFloatAsState(if (adding) 45f else 0f,
                CollectionMotionScheme.fastSpatialSpec(), label = "tag_add_rotation")
            FilledTonalIconButton(enabled = enabled, onClick = { if (adding) focusManager.clearFocus(); adding = !adding; name = "" }, modifier = Modifier.align(Alignment.CenterVertically)) {
                Icon(Icons.Default.Add, "添加标签", Modifier.graphicsLayer { rotationZ = rotation })
            }
        }
        MotionReveal(adding) {
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) },
                label = { Text("标签名称") }, singleLine = true, enabled = enabled,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("inline_tag_name"),
                isError = duplicate,
                supportingText = if (duplicate) ({ Text("标签已存在，请直接选择") }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    Row {
                        IconButton(enabled = valid, onClick = { submit() }) { Icon(Icons.Default.Check, "创建标签") }
                        IconButton(onClick = { name = ""; adding = false; focusManager.clearFocus() }) { Icon(Icons.Default.Close, "取消添加标签") }
                    }
                })
        }
    }
}
