@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.collection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.collection.domain.AiConfig
import java.net.URI

@Composable
fun AiSettingsScreen(config: AiConfig, onBack: () -> Unit, onSave: (AiConfig) -> Unit, backEnabled: Boolean = true) {
    var key by remember(config) { mutableStateOf(config.apiKey) }
    val focusManager = LocalFocusManager.current
    var model by remember(config) { mutableStateOf(config.model) }
    var proxy by remember(config) { mutableStateOf(config.proxyMode) }
    var endpoint by remember(config) { mutableStateOf(if (config.proxyMode) config.endpoint else "") }
    var visible by remember { mutableStateOf(false) }
    var showSavedError by remember { mutableStateOf(false) }
    var leaveConfirm by remember { mutableStateOf(false) }
    val validEndpoint = !proxy || runCatching { URI(endpoint.trim()).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null } }.getOrDefault(false)
    val changed = key != config.apiKey || model != config.model || proxy != config.proxyMode || (proxy && endpoint != config.endpoint)
    fun goBack() { if (changed) leaveConfirm = true else onBack() }
    // Settings uses the same system progress for its visual transform. A completed gesture opens
    // the discard confirmation; only its confirm action actually leaves the destination.
    val backMotion = rememberPredictiveBackMotion(enabled = changed && backEnabled) { goBack() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        .predictiveBackTransform(backMotion), topBar = {
        CollectionTopBar("AI 连接", { goBack() }, scrollBehavior)
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
            top = 12.dp, bottom = padding.calculateBottomPadding() + 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ListItem(
                        content = { Text("使用服务端代理") },
                        supportingContent = { Text("公开发布时，DeepSeek Key 由服务端保管") },
                        trailingContent = { Switch(checked = proxy, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = proxy, role = Role.Switch, onValueChange = { focusManager.clearFocus(); proxy = it }),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                    MotionSwap(proxy, Modifier.fillMaxWidth()) { useProxy ->
                    if (useProxy) OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("代理完整接口地址（HTTPS）") },
                        placeholder = { Text("https://your-server.example/chat/completions") }, modifier = Modifier.fillMaxWidth(),
                        isError = showSavedError && !validEndpoint, supportingText = { Text("代理需兼容 Chat Completions；需自行部署。") }, shape = MaterialTheme.shapes.large)
                    else Text("官方接口：api.deepseek.com/chat/completions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(value = key, onValueChange = { key = it.trim() }, label = { Text(if (proxy) "代理访问令牌（可选）" else "DeepSeek API Key") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.large,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "隐藏密钥" else "显示密钥") } },
                        supportingText = { Text("加密保存在本机，不会进入备份或源码。清空后保存可移除。") })
                    OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名称") }, placeholder = { Text("deepseek-flash") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.large)
                    Button(onClick = {
                        showSavedError = true
                        if (validEndpoint && model.isNotBlank()) onSave(AiConfig(key.trim(), if (proxy) endpoint.trim() else "https://api.deepseek.com/chat/completions", model.trim(), proxy))
                    }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("保存 AI 设置") }
                    MotionReveal(showSavedError && (!validEndpoint || model.isBlank())) { Text("请填写有效的 HTTPS 地址和模型名称", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    if (leaveConfirm) AlertDialog(onDismissRequest = { leaveConfirm = false }, title = { Text("放弃未保存的设置？") },
        text = { Text("AI 连接设置还没有保存。") }, confirmButton = { TextButton(onClick = { leaveConfirm = false; onBack() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { leaveConfirm = false }) { Text("继续编辑") } })
}
