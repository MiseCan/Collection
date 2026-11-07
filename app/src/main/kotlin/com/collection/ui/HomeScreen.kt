@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.collection.ui

import androidx.compose.ui.res.stringResource
import com.collection.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.collection.domain.*

@Composable
fun HomeScreen(
    library: Library,
    preferences: HomePreferences,
    query: String,
    onQuery: (String) -> Unit,
    onPreferences: (HomePreferences) -> Unit,
    onOpen: (Entry) -> Unit,
    onCreate: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onReorderTags: (List<String>) -> Unit = {},
    backEnabled: Boolean = true,
    onDeleteEntries: (Set<String>) -> Unit = {},
) {
    val homeEntries = remember(library, preferences, query) { library.filtered(query, preferences) }
    val tagsById = remember(library.tags) { library.tags.associateBy { it.id } }
    var batch by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmBatch by remember { mutableStateOf(false) }
    fun select(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun closeBatch() {
        batch = false
        selected = emptySet()
    }
    var searching by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    fun closeSearch() {
        focus.clearFocus()
        keyboard?.hide()
        searching = false
        onQuery("")
    }
    rememberPredictiveBackMotion(enabled = batch && backEnabled) { closeBatch() }
    rememberPredictiveBackMotion(enabled = searching && !batch && backEnabled) { closeSearch() }
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val placement = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val gridState = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridActions: @Composable RowScope.() -> Unit = {
        var sortMenu by remember { mutableStateOf(false) }
        IconButton(onClick = { onPreferences(preferences.copy(cards = !preferences.cards)) }) {
            MotionSwap(preferences.cards) { single ->
                Icon(if (single) Icons.Default.GridView else Icons.Default.ViewAgenda,
                    if (single) "切换为两列视图" else "切换为单列视图")
            }
        }
        Box {
            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "排序") }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SortOrder.entries.forEach { sort ->
                    DropdownMenuItem(text = { Text(sort.label) },
                        onClick = { onPreferences(preferences.copy(sort = sort)); sortMenu = false },
                        trailingIcon = if (sort == preferences.sort) ({ Icon(Icons.Default.Check, null) }) else null)
                }
            }
        }
    }
    val batchToolbar: @Composable (List<Entry>) -> Unit = { visibleEntries ->
        MotionReveal(batch) {
            val ids = remember(visibleEntries) { visibleEntries.mapTo(mutableSetOf()) { it.id } }
            val allSelected = ids.isNotEmpty() && selected.containsAll(ids)
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                FloatingBatchBar(selected.size, allSelected,
                    onAll = { selected = if (allSelected) selected - ids else selected + ids },
                    onDelete = { confirmBatch = true }, onClose = { closeBatch() },
                    modifier = Modifier.testTag("home_batch_toolbar"))
            }
        }
    }
    val entryGrid: @Composable (List<Entry>, LazyGridState, PaddingValues) -> Unit =
        { entries, state, innerPadding ->
        var gridViewport by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Fixed(if (preferences.cards) 1 else 2),
            modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())
                .onGloballyPositioned { gridViewport = it.boundsInRoot() },
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp,
                bottom = innerPadding.calculateBottomPadding() + if (batch) 16.dp else 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "filters_and_view", contentType = "controls", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects)) {
                HomeTagFilters(library.tags, preferences.tagIds,
                    { onPreferences(preferences.copy(tagIds = it)) }, onReorderTags,
                    gridViewport, { state.scrollBy(it) })
                }
            }

            if (entries.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    val filtered = query.isNotBlank() || preferences.tagIds.isNotEmpty()
                    Box(Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects)) {
                    EmptyLibrary(
                        filtered = filtered,
                        onCreate = onCreate,
                        onClear = { onQuery(""); onPreferences(preferences.copy(tagIds = emptySet())) },
                    )
                    }
                }
            } else {
                items(entries, key = { it.id }, contentType = { "entry" }) { entry ->
                    EntryCard(
                        entry = entry,
                        tags = entry.tagIds.mapNotNull { tagsById[it] },
                        twoColumns = !preferences.cards,
                        onClick = {
                            if (batch) select(entry.id)
                            else onOpen(entry)
                        },
                        onLongClick = { batch = true; select(entry.id) },
                        selected = if (batch) entry.id in selected else null,
                        modifier = Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects),
                    )
                }
            }
        }
    }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MotionSwap(searching, Modifier.fillMaxWidth()) { active ->
                if (active) {
                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    TopAppBar(
                        modifier = Modifier.testTag("home_app_bar"),
                        title = {
                            TextField(value = query, onValueChange = onQuery,
                                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                                placeholder = { Text("搜索标题或标签") }, singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                trailingIcon = {
                                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                                        Icon(Icons.Default.Close, "清空搜索")
                                    }
                                })
                        },
                        navigationIcon = { IconButton(onClick = { closeSearch() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭搜索")
                        } },
                        actions = gridActions,
                    )
                } else LargeFlexibleTopAppBar(
                    modifier = Modifier.testTag("home_app_bar"),
                    title = { Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold, fontSize = (32f - 10f * scrollBehavior.state.collapsedFraction).sp) },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "搜索资料") }
                        gridActions()
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "打开设置") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = { batchToolbar(homeEntries) },
        // Keep the outgoing FAB at its original anchor as the batch bar enters.
        floatingActionButtonPosition = FabPosition.EndOverlay,
        floatingActionButton = {
            AnimatedVisibility(
                visible = !batch,
                enter = fadeIn(effects) + scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), initialScale = 0.85f),
                exit = fadeOut(effects) + scaleOut(MaterialTheme.motionScheme.fastSpatialSpec(), targetScale = 0.85f),
            ) {
                ExtendedFloatingActionButton(onClick = onCreate, modifier = Modifier.testTag("home_create_fab"),
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(12.dp))
                    Text("收录新资料")
                }
            }
        },
    ) { entryGrid(homeEntries, gridState, it) }

    if (confirmBatch) AlertDialog(onDismissRequest = { confirmBatch = false }, title = { Text("删除所选条目？") },
        text = { Text("所选条目将移到回收站，30 天内可以恢复。") },
        confirmButton = { TextButton(onClick = { confirmBatch = false; onDeleteEntries(selected); closeBatch() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmBatch = false }) { Text("取消") } })

}

@Composable
private fun EntryCard(entry: Entry, tags: List<Tag>, twoColumns: Boolean, onClick: () -> Unit,
    onLongClick: () -> Unit, selected: Boolean?, modifier: Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val cardShape = MaterialTheme.shapes.large
    Row(modifier.fillMaxWidth().testTag("entry_row_${entry.id}"), verticalAlignment = Alignment.CenterVertically) {
        MotionReveal(selected != null, horizontal = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected == true, onCheckedChange = { onClick() },
                    modifier = Modifier.testTag("entry_select_${entry.id}"))
                Spacer(Modifier.width(8.dp))
            }
        }
        Card(modifier = Modifier.weight(1f).motionPressFeedback(interactions).clip(cardShape)
            .combinedClickable(interactionSource = interactions, indication = LocalIndication.current,
                onClick = onClick, onLongClick = onLongClick).testTag("entry_${entry.id}"),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            MotionSwap(twoColumns, Modifier.fillMaxWidth()) { mode ->
                if (mode) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EntryOriginalStack(entry)
                            Text(entry.title.ifBlank { "未命名收录" }, Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (entry.attachments.isEmpty()) "文字笔记" else "${entry.attachments.size} 个原始附件",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tags.isNotEmpty()) Text(tags.joinToString(" ") { "# ${it.name}" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EntryOriginalStack(entry)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.title.ifBlank { "未命名收录" }, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (entry.attachments.isEmpty()) "文字笔记" else "${entry.attachments.size} 个原始附件",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (tags.isNotEmpty()) Text(tags.joinToString("  ") { "# ${it.name}" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(filtered: Boolean, onCreate: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(34.dp, 34.dp, 12.dp, 34.dp),
            color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(if (filtered) Icons.Default.SearchOff else Icons.Default.AutoStories, null,
                Modifier.padding(26.dp).size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(2.dp))
        if (filtered) {
            Text("还没有匹配的资料", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            Text("换个关键词，或减少几个筛选标签。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (filtered) OutlinedButton(onClick = onClear) { Text("清除搜索和筛选") }
        else FilledTonalButton(onClick = onCreate) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("开始第一条收录")
        }
    }
}



