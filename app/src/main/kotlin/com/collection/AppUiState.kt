package com.collection

import com.collection.domain.*
import com.collection.data.TrashedEntry

/** One immutable UI snapshot; no Compose runtime state exists in the ViewModel. */
data class AppUiState(
    val library: Library = Library(),
    val home: HomePreferences = HomePreferences(),
    val config: AiConfig = AiConfig(),
    val page: Page = Page.HOME,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val loadError: String? = null,
    val operationError: String? = null,
    val query: String = "",
    val selectedId: String? = null,
    val draft: Entry = Entry(),
    val draftChanged: Boolean = false,
    val aiEdited: Boolean = false,
    val generating: Boolean = false,
    val progress: String = "",
    val pendingShare: SharePayload? = null,
    val canRetry: Boolean = false,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val syncConfig: com.collection.sync.SyncConfig = com.collection.sync.SyncConfig(),
    val syncStatus: com.collection.sync.SyncStatus = com.collection.sync.SyncStatus(),
    val syncTesting: Boolean = false,
    val trash: List<TrashedEntry> = emptyList(),
)

/** A one-shot request; NavHostController, not the ViewModel, owns the back stack. */
data class NavigationCommand(val page: Page, val entryId: String? = null, val finishEditor: Boolean = false)
