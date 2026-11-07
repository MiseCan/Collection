@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.collection.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.collection.R

@Composable
fun SettingsScreen(onBack: () -> Unit, onTheme: () -> Unit, onSync: () -> Unit, onTrash: () -> Unit, onAi: () -> Unit) {
    val scrolling = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scrolling.nestedScrollConnection),
        topBar = { CollectionTopBar("设置", onBack, scrolling) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { SettingsDestination("主题设置", "配色、明暗、缩放与页面转场", Icons.Default.Palette, onTheme) }
            item { SettingsDestination("同步与备份", "坚果云 · WebDAV · 本地备份", Icons.Default.CloudSync, onSync) }
            item { SettingsDestination("AI 连接", "DeepSeek、模型与服务端代理", Icons.Default.AutoAwesome, onAi) }
            item { SettingsDestination("回收站", "恢复或永久清除已删除的条目", Icons.Default.DeleteOutline, onTrash) }
            item { AboutCard() }
        }
    }
}

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ListItem(
            content = { Text("关于") },
            supportingContent = { Text("${stringResource(R.string.app_name)} $version".trimEnd()) },
            leadingContent = { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("把文字、链接、图片和文件收进同一个条目，保留原件。可通过系统分享导入，用标签、搜索和排序整理资料，也支持本地备份、坚果云 WebDAV 同步和回收站。", style = MaterialTheme.typography.bodyMedium)
            Text("AI 可读取图片、Word（DOC 和 DOCX）、PDF 与文本来生成标题。扫描页使用视觉识别，识别结果请结合原件核对。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(if (showHistory) "收起更新日志" else "查看更新日志")
            }
            AnimatedVisibility(showHistory) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    collectionReleaseNotes.forEach { (release, changes) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(release, style = MaterialTheme.typography.titleSmall)
                            Text(changes, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private val collectionReleaseNotes = listOf(
    "1.2.4" to "设置新增关于卡片，集中展示版本、功能介绍和更新日志。",
    "1.2.3" to "支持读取旧版 Word（.doc）正文生成 AI 标题。提供开启 R8 裁剪的安装包。",
    "1.2.2" to "调整设置页顺序，将同步入口命名为同步与备份，回收站移至设置末尾。",
    "1.2.1" to "回收站新增长按批量操作。永久清除会同步到云端和其他设备。",
    "1.2.0" to "接入坚果云 WebDAV 双向同步，加入回收站和 30 天自动清理。",
    "1.1.1–1.1.2" to "调整首页搜索和长按多选。选择框移到左侧，条目卡片在进入多选时向右收缩。",
    "1.1.0" to "最低系统版本提升至 Android 12，清理旧版本兼容代码。",
    "1.0.0" to "应用改名为 Collection。支持混合资料收录、标签整理和 AI 标题，也可读取公开网页正文来生成标题。",
)

@Composable
private fun SettingsDestination(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        ListItem(content = { Text(title) }, supportingContent = { Text(description) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}
