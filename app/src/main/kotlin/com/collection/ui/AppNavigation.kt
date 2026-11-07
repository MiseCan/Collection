@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.collection.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics

import top.yukonga.miuix.kmp.nav.core.*
import top.yukonga.miuix.kmp.nav.transition.*

import com.collection.*
import com.collection.domain.Attachment
import com.collection.domain.PageTransitionStyle
import kotlin.math.abs

object AppRoutes {
    const val HOME = "home"
    const val DETAIL = "detail/{entryId}"
    const val EDITOR = "editor?entryId={entryId}"
    const val SETTINGS = "settings"
    const val THEME = "theme"
    const val SYNC = "sync"
    const val TRASH = "trash"
    const val AI = "ai"
    const val ORIGINAL = "original/{attachmentId}"
    const val TEXT = "text/{entryId}"
    fun page(route: String?): Page? = when (route) {
        HOME -> Page.HOME; DETAIL -> Page.DETAIL; EDITOR -> Page.EDITOR; SETTINGS -> Page.SETTINGS
        THEME -> Page.THEME
        SYNC -> Page.SYNC
        TRASH -> Page.TRASH
        AI -> Page.AI
        else -> null
    }
}

/** Applies business navigation requests to the persisted Miuix page stack. */
fun CollectionNavigator.applyCommand(command: NavigationCommand) {
    if (command.finishEditor && currentDestination?.route == AppRoutes.EDITOR) popBackStack()
    when (command.page) {
        Page.HOME -> popToHome()
        Page.DETAIL -> {
            val id = command.entryId ?: return
            if (currentDestination?.route != AppRoutes.DETAIL || currentBackStackEntry?.arguments?.getString("entryId") != id) {
                navigate("detail/${Uri.encode(id)}")
            }
        }
        Page.EDITOR -> navigate(command.entryId?.let { "editor?entryId=${Uri.encode(it)}" } ?: "editor")
        Page.SETTINGS -> navigate(AppRoutes.SETTINGS)
        Page.THEME -> navigate(AppRoutes.THEME)
        Page.SYNC -> navigate(AppRoutes.SYNC)
        Page.TRASH -> navigate(AppRoutes.TRASH)
        Page.AI -> navigate(AppRoutes.AI)
    }
}

@Composable
private fun AdaptivePage(form: Boolean, page: String, active: Boolean, content: @Composable () -> Unit) {
    val window = currentWindowAdaptiveInfoV2().windowSizeClass
    val expanded = window.isWidthAtLeastBreakpoint(840)
    val maxWidth = if (form) 840.dp else 1200.dp
    val semantics = if (active) Modifier else Modifier.clearAndSetSemantics { }
    Surface(Modifier.fillMaxSize().testTag("page_$page").then(semantics)) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = if (expanded) maxWidth else androidx.compose.ui.unit.Dp.Infinity)
            .fillMaxHeight().fillMaxWidth()) { content() }
      }
    }
}

@Composable
fun AppNavHost(state: AppUiState, vm: AppViewModel, controller: CollectionNavigator,
    onOriginal: (Attachment) -> Unit, onEditorBack: () -> Unit, onAddFiles: () -> Unit,
    onExport: () -> Unit, onRestore: () -> Unit, onAddEntryFiles: (String) -> Unit) {
    // Destination content reads the latest immutable snapshot.
    val ui by rememberUpdatedState(state)
    val original by rememberUpdatedState(onOriginal)
    val editorBack by rememberUpdatedState(onEditorBack)
    val addFiles by rememberUpdatedState(onAddFiles)
    val export by rememberUpdatedState(onExport)
    val restore by rememberUpdatedState(onRestore)
    val addEntryFiles by rememberUpdatedState(onAddEntryFiles)
    val currentEntry by controller.currentBackStackEntryAsState()
    val currentId by rememberUpdatedState(currentEntry?.id)
    fun backHome() { if (!controller.popBackStack()) vm.openPage(Page.HOME) }
    val appearance = ui.appearance
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dirtyEditor = currentEntry?.destination?.route == AppRoutes.EDITOR && (ui.draftChanged || ui.generating)
    val swipe = if (!appearance.predictiveBack || !appearance.edgeAwareBack || dirtyEditor) NavSwipeDirection.None
        else if (rtl) NavSwipeDirection.RightToLeft else NavSwipeDirection.LeftToRight
    val transition = remember(appearance.pageTransition, appearance.predictiveBack) {
        val base = if (appearance.pageTransition == PageTransitionStyle.MIUIX) NavTransitions.MiuixDefault
        else navGraphicsTransition { scope -> alpha = (1f - abs(scope.relativeDepth)).coerceIn(0f, 1f) }
        NavTransition { scope ->
            if (!appearance.predictiveBack && scope.gesture != null) this
            else with(base) { transformEntry(scope) }
        }
    }
    NavDisplay(backStack = controller.backStack, modifier = Modifier.fillMaxSize(),
        transition = transition,
        effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
        onBack = { if (dirtyEditor) editorBack() else controller.popBackStack() }) {
      entry<CollectionRoute>(swipeDismiss = swipe) { entry ->
       when (entry.destination.route) {
        AppRoutes.HOME -> {
            AdaptivePage(form = false, page = "home", active = currentId == entry.id) {
                HomeScreen(ui.library, ui.home, ui.query, vm::updateQuery, vm::updateHome, { vm.openEntry(it) }, { vm.newEntry() },
                    { vm.openPage(Page.SETTINGS) }, onReorderTags = vm::reorderTags,
                    backEnabled = currentId == entry.id, onDeleteEntries = vm::deleteEntries)
            }
        }
        AppRoutes.DETAIL -> {
            val id = entry.arguments?.getString("entryId")
            var retained by remember(entry.id) { mutableStateOf(ui.library.entries.firstOrNull { it.id == id }) }
            val active = currentId == entry.id && ui.page == Page.DETAIL
            val item = if (active) ui.library.entries.firstOrNull { it.id == id } else retained
            if (active && item != null) SideEffect { retained = item }
            AdaptivePage(form = true, page = "detail", active = currentId == entry.id) {
                if (item != null) DetailScreen(item, ui.library.tags, { backHome() }, { vm.deleteEntry(item) }, original,
                    onText = { controller.navigate("text/${Uri.encode(item.id)}") }, onUpdate = vm::updateStoredEntry,
                    onAddFiles = { addEntryFiles(item.id) }, onAddTag = { vm.addTagToEntry(item.id, it) },
                    onGenerateSource = { vm.generateStoredTitle(item.id, it) },
                    onGenerateEntry = { vm.generateStoredTitle(item.id, wholeEntry = true) },
                    generating = ui.generating, progress = ui.progress, onCancelGeneration = vm::cancelGeneration,
                    onDeleteTag = vm::deleteTag, onToggleTag = { vm.toggleEntryTag(item.id, it.id) })
                else StateScreen("这个条目已不存在", "返回资料库", { vm.openPage(Page.HOME) })
            }
        }
        AppRoutes.EDITOR -> {
            // The outgoing destination must not redraw with a cleared/new draft during pop.
            var retained by remember(entry.id) { mutableStateOf(ui) }
            val active = currentId == entry.id && ui.page == Page.EDITOR
            val editorUi = if (active) ui else retained
            if (active) SideEffect { retained = editorUi }
            AdaptivePage(form = true, page = "editor", active = currentId == entry.id) {
                EditorScreen(editorUi.draft, editorUi.library.tags, editorUi.generating, editorUi.progress, editorUi.aiEdited, vm::changeDraft, { editorBack() },
                    // File picking is delegated to AppRoot, which owns the activity result launcher.
                    onAddFiles = addFiles, onPreview = original, onRemove = vm::removeAttachment,
                    onGenerate = vm::generate, onCancelGeneration = vm::cancelGeneration,
                    onAddTag = { vm.addTag(it, true) }, onSave = { vm.saveEntry() },
                    hasUnsavedChanges = currentId == entry.id && (ui.draftChanged || ui.generating), onDeleteTag = vm::deleteTag)
            }
        }
        AppRoutes.ORIGINAL -> {
            val id = entry.arguments?.getString("attachmentId")
            val attachment = (ui.draft.attachments + ui.library.entries.flatMap { it.attachments }).firstOrNull { it.id == id }
            AdaptivePage(form = false, page = "original", active = currentId == entry.id) {
                if (attachment != null) AttachmentPreview(attachment, embedded = true) { backHome() }
                else StateScreen("原件暂时不可读", "返回", { backHome() })
            }
        }
        AppRoutes.TEXT -> {
            val item = ui.library.entries.firstOrNull { it.id == entry.arguments?.getString("entryId") }
            AdaptivePage(form = true, page = "text", active = currentId == entry.id) {
                if (item != null) OriginalTextViewer(item.body, item.bodyTitle.ifBlank { "文字与笔记" }, embedded = true,
                    onSaveText = if (ui.generating) null else ({ body -> vm.updateStoredEntry(item.copy(body = body)) })) { backHome() }
                else StateScreen("原文暂时不可读", "返回", { backHome() })
            }
        }
        AppRoutes.SETTINGS -> {
            AdaptivePage(form = true, page = "settings", active = currentId == entry.id) {
                SettingsScreen(onBack = { backHome() },
                    onTheme = { vm.openPage(Page.THEME) },
                    onSync = { vm.openPage(Page.SYNC) },
                    onTrash = { vm.openPage(Page.TRASH) },
                    onAi = { vm.openPage(Page.AI) })
            }
        }
        AppRoutes.THEME -> {
            AdaptivePage(form = true, page = "theme", active = currentId == entry.id) {
                ThemeSettingsScreen(
                    appearance = ui.appearance,
                    onBack = { if (!controller.popBackStack()) vm.openPage(Page.SETTINGS) },
                    onAppearanceChange = vm::updateAppearance,
                )
            }
        }
        AppRoutes.SYNC -> {
            AdaptivePage(form = true, page = "sync", active = currentId == entry.id) {
                SyncSettingsScreen(ui.syncConfig, ui.syncStatus, ui.syncTesting,
                    onBack = { if (!controller.popBackStack()) vm.openPage(Page.SETTINGS) },
                    onSave = vm::saveSyncConfig, onTest = vm::testSync, onSync = vm::syncNow,
                    backEnabled = currentId == entry.id, onExport = export, onRestore = restore)
            }
        }
        AppRoutes.TRASH -> {
            AdaptivePage(form = true, page = "trash", active = currentId == entry.id) {
                TrashScreen(ui.trash, ui.busy, onBack = { backHome() },
                    onRestore = vm::restoreTrash, onPurge = vm::purgeTrash, backEnabled = currentId == entry.id)
            }
        }
        AppRoutes.AI -> {
            AdaptivePage(form = true, page = "ai", active = currentId == entry.id) {
                AiSettingsScreen(ui.config, onBack = { backHome() }, onSave = vm::saveConfig,
                    backEnabled = currentId == entry.id)
            }
        }
       }
      }
    }
}


