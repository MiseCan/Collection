@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.collection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.collection.sync.SyncConfig
import com.collection.sync.SyncStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun SyncSettingsScreen(config: SyncConfig, status: SyncStatus, testing: Boolean,
    onBack: () -> Unit, onSave: (SyncConfig) -> Unit, onTest: (SyncConfig) -> Unit, onSync: () -> Unit,
    backEnabled: Boolean = true, onExport: () -> Unit = {}, onRestore: () -> Unit = {}) {
    var draft by remember(config) { mutableStateOf(config) }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var restoreConfirm by remember { mutableStateOf(false) }
    val changed = draft != config
    val focus = LocalFocusManager.current
    fun back() { focus.clearFocus(); if (changed) discard = true else onBack() }
    val motion = rememberPredictiveBackMotion(enabled = changed && backEnabled) { back() }
    val scrolling = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    fun validated(action: (SyncConfig) -> Unit) {
        focus.clearFocus()
        try { action(draft.validated()); error = null }
        catch (e: IllegalArgumentException) { error = e.message ?: "请检查连接配置" }
    }
    Scaffold(modifier = Modifier.nestedScroll(scrolling.nestedScrollConnection).predictiveBackTransform(motion),
        topBar = { CollectionTopBar("同步与备份", { back() }, scrolling) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp,
                bottom = padding.calculateBottomPadding() + 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "status") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MotionSwap(status.message) { Text(it, style = MaterialTheme.typography.titleMedium) }
                    MotionReveal(status.running || testing) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (status.lastSuccess > 0) Text("上次成功：" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(status.lastSuccess)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = onSync, enabled = config.configured && !changed && !status.running && !testing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text("立即同步")
                    }
                }
            }
            item(key = "connection") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("WebDAV 连接")
                    OutlinedTextField(draft.endpoint, { draft = draft.copy(endpoint = it) }, label = { Text("服务器地址（HTTPS）") },
                        singleLine = true, enabled = !testing && !status.running, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large)
                    OutlinedTextField(draft.username, { draft = draft.copy(username = it) }, label = { Text("账号邮箱") },
                        singleLine = true, enabled = !testing && !status.running, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large)
                    OutlinedTextField(draft.password, { draft = draft.copy(password = it) }, label = { Text("应用密码") },
                        singleLine = true, enabled = !testing && !status.running, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showPassword) "隐藏应用密码" else "显示应用密码")
                        } })
                    Text("在坚果云「第三方应用管理」生成应用密码。资料保存在 Collection 文件夹；密码仅加密保存在本机。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { validated(onTest) }, enabled = !testing && !status.running,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (testing) "正在验证连接…" else "测试连接") }
                }
            }
            item(key = "automatic") {
                Column {
                    ListItem(content = { Text("自动双向同步") }, supportingContent = { Text("保存后合并同步，后台约每 30 分钟检查") },
                        trailingContent = { Switch(draft.automatic, null) }, modifier = Modifier.toggleable(draft.automatic, role = Role.Switch,
                            enabled = !status.running, onValueChange = { draft = draft.copy(automatic = it) }))
                    MotionReveal(draft.automatic) {
                        ListItem(content = { Text("仅非计费网络自动同步") }, supportingContent = { Text("手动同步可使用当前网络") },
                            trailingContent = { Switch(draft.wifiOnly, null) }, modifier = Modifier.toggleable(draft.wifiOnly, role = Role.Switch,
                                enabled = !status.running, onValueChange = { draft = draft.copy(wifiOnly = it) }))
                    }
                }
            }
            item(key = "save") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MotionReveal(error != null) { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { validated(onSave) }, enabled = !testing && !status.running,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) { Text("保存同步设置") }
                    Text("同步条目、标签和完整原件。并发修改保留冲突副本，删除操作也会同步。后台执行时间由系统调度。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "backup") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider()
                    SectionHeading("本地资料与备份")
                    Text("导出独立备份可保留本机条目和原件。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = onExport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.FileDownload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("导出备份（含原始文件）")
                    }
                    OutlinedButton(onClick = { restoreConfirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.FileUpload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("从备份恢复")
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的设置？") },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    if (restoreConfirm) AlertDialog(onDismissRequest = { restoreConfirm = false }, title = { Text("合并导入备份") },
        text = { Text("备份中的条目会作为新条目导入。同名标签会合并，重复导入同一备份会产生重复条目。") },
        confirmButton = { TextButton(onClick = { restoreConfirm = false; onRestore() }) { Text("选择备份") } },
        dismissButton = { TextButton(onClick = { restoreConfirm = false }) { Text("取消") } })
}
