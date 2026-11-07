@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.collection.ui

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.collection.AppViewModel
import com.collection.Page
import com.collection.domain.Attachment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle




@Composable
fun AppRoot(vm: AppViewModel, navController: CollectionNavigator = rememberCollectionNavigator()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(navController) {
        snapshotFlow { navController.currentBackStackEntry }.collect { entry ->
            if (entry == null) return@collect
            val target = AppRoutes.page(entry.destination.route) ?: return@collect
            if (target == Page.EDITOR) vm.restoreEditor(entry.arguments?.getString("entryId"))
            else vm.onDestinationChanged(target, entry.arguments?.getString("entryId"))
        }
    }
    LaunchedEffect(state.loading, navController, lifecycle) {
        if (!state.loading) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.navigationEvents.collect { navController.applyCommand(it) }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val appName = stringResource(com.collection.R.string.app_name)
    val scope = rememberCoroutineScope()
    var discard by remember { mutableStateOf(false) }
    var fileTarget by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.importToDraft(it) }
    val addEntryFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = fileTarget; fileTarget = null
        if (id != null && uris.isNotEmpty()) vm.importToEntry(id, uris)
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::exportBackup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restoreBackup) }
    LaunchedEffect(vm, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.messages.collect { snackbar.showSnackbar(it) } } }
    fun showOriginal(attachment: Attachment) {
        if (attachment.hasNativeViewer()) navController.navigate("original/${android.net.Uri.encode(attachment.id)}")
        else openOriginalExternally(context, attachment)?.let { message -> scope.launch { snackbar.showSnackbar(message) } }
    }
    fun editorBack() { if (state.draftChanged || state.generating) discard = true else vm.cancelEditor() }
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize()) {
            when {
                state.loading -> StateScreen("正在打开资料库…", loading = true)
                state.loadError != null -> StateScreen(state.loadError!!, action = "重新加载", onAction = vm::reload)
                else -> AppNavHost(state, vm, navController,
                    onOriginal = { showOriginal(it) }, onEditorBack = { editorBack() },
                    onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onAddEntryFiles = { id -> fileTarget = id; addEntryFiles.launch(arrayOf("*/*")) },
                    onExport = { backup.launch("${appName}备份-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA).format(java.util.Date())}.zip") },
                    onRestore = { restore.launch(arrayOf("application/zip", "application/octet-stream")) })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(
            start = 16.dp, end = 16.dp, top = 16.dp,
            bottom = when (currentEntry?.destination?.route) {
                AppRoutes.HOME, AppRoutes.TRASH -> 96.dp
                else -> 16.dp
            }))
    }
    if (state.busy) Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoadingIndicator(Modifier.size(48.dp)); Text("正在保存和处理资料…")
            }
        }
    }
    if (state.operationError != null) AlertDialog(onDismissRequest = vm::clearError, title = { Text("暂时未能完成") },
        text = { Text(state.operationError!!) }, confirmButton = {
            if (state.canRetry) TextButton(onClick = vm::retry) { Text("重试") } else TextButton(onClick = vm::clearError) { Text("知道了") }
        }, dismissButton = { if (state.canRetry) TextButton(onClick = vm::clearError) { Text("继续手动操作") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃本次编辑？") },
        text = { Text("未保存的文字、AI 结果和新增附件将被放弃，已保存的原条目保留。") },
        confirmButton = { TextButton(onClick = { discard = false; vm.cancelEditor() }) { Text("放弃编辑") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    state.pendingShare?.let { payload ->
        var separate by remember(payload) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = vm::dismissShare, title = { Text("收录分享内容") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("收到 ${payload.uris.size} 个文件" + if (payload.text.isNotBlank()) "和分享文字" else "")
                    if (state.page == Page.EDITOR && state.draftChanged) Text("继续导入将放弃当前未保存的编辑。", color = MaterialTheme.colorScheme.error)
                    if (payload.uris.size > 1) {
                        Column(Modifier.selectableGroup()) {
                            ImportChoice("合并为一个条目", !separate) { separate = false }
                            ImportChoice("每个文件独立创建条目", separate) { separate = true }
                        }
                        MotionReveal(separate) { Text("分享文字会附到每个条目；导入后可编辑并生成 AI 标题。", style = MaterialTheme.typography.bodySmall) }
                    } else Text("将在编辑页中确认并保存。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.acceptShare(separate) }, enabled = !state.loading && !state.busy && !state.generating) { Text("开始导入") } },
            dismissButton = { TextButton(onClick = vm::dismissShare) { Text("取消") } })
    }

}

@Composable
fun StateScreen(message: String, action: String? = null, onAction: () -> Unit = {}, loading: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        MotionReveal(loading) { LoadingIndicator() }
        MotionSwap(message) { Text(it, style = MaterialTheme.typography.bodyLarge) }
        MotionReveal(action != null) { Button(onClick = onAction) { Text(action.orEmpty()) } }
    }
}


