package com.collection

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.collection.ai.AiService
import com.collection.data.LibraryStore
import com.collection.data.PreferencesStore
import com.collection.data.TrashCleanupWorker
import com.collection.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import com.collection.sync.*

enum class Page { HOME, EDITOR, DETAIL, SETTINGS, THEME, SYNC, TRASH, AI }
data class SharePayload(val text: String, val uris: List<Uri>)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LibraryStore(application)
    private val preferencesStore = PreferencesStore(application)
    private val syncSettings = SyncSettings(application)
    private val ai = AiService(application)
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private val navigationChannel = Channel<NavigationCommand>(Channel.BUFFERED)
    val navigationEvents = navigationChannel.receiveAsFlow()
    var library: Library
        get() = _uiState.value.library
        private set(value) { _uiState.update { it.copy(library = value) } }
    var home: HomePreferences
        get() = _uiState.value.home
        private set(value) { _uiState.update { it.copy(home = value) } }
    var config: AiConfig
        get() = _uiState.value.config
        private set(value) { _uiState.update { it.copy(config = value) } }
    var page: Page
        get() = _uiState.value.page
        private set(value) { _uiState.update { it.copy(page = value) } }
    var loading: Boolean
        get() = _uiState.value.loading
        private set(value) { _uiState.update { it.copy(loading = value) } }
    var busy: Boolean
        get() = _uiState.value.busy
        private set(value) { _uiState.update { it.copy(busy = value) } }
    var loadError: String?
        get() = _uiState.value.loadError
        private set(value) { _uiState.update { it.copy(loadError = value) } }
    var operationError: String?
        get() = _uiState.value.operationError
        private set(value) { _uiState.update { it.copy(operationError = value) } }
    var query: String
        get() = _uiState.value.query
        private set(value) { _uiState.update { it.copy(query = value) } }
    var selectedId: String?
        get() = _uiState.value.selectedId
        private set(value) { _uiState.update { it.copy(selectedId = value) } }
    var draft: Entry
        get() = _uiState.value.draft
        private set(value) { _uiState.update { it.copy(draft = value) } }
    var draftChanged: Boolean
        get() = _uiState.value.draftChanged
        private set(value) { _uiState.update { it.copy(draftChanged = value) } }
    var aiEdited: Boolean
        get() = _uiState.value.aiEdited
        private set(value) { _uiState.update { it.copy(aiEdited = value) } }
    var generating: Boolean
        get() = _uiState.value.generating
        private set(value) { _uiState.update { it.copy(generating = value) } }
    var progress: String
        get() = _uiState.value.progress
        private set(value) { _uiState.update { it.copy(progress = value) } }
    var pendingShare: SharePayload?
        get() = _uiState.value.pendingShare
        private set(value) { _uiState.update { it.copy(pendingShare = value) } }
    var appearance: AppearanceSettings
        get() = _uiState.value.appearance
        private set(value) { _uiState.update { it.copy(appearance = value) } }
    private var retryAction: (() -> Unit)? = null
        set(value) { field = value; _uiState.update { it.copy(canRetry = value != null) } }
    private var aiJob: Job? = null
    private val appearanceMutex = Mutex()
    private val libraryWrites = Mutex()
    private var previousPage = Page.HOME
    private val notices = Channel<String>(Channel.BUFFERED)
    private val homeWrites = Channel<HomePreferences>(Channel.CONFLATED)
    val messages = notices.receiveAsFlow()

    init {
        reload()
        viewModelScope.launch {
            SyncSignals.status.collect { value ->
                if (value != SyncStatus()) _uiState.update { it.copy(syncStatus = value) }
            }
        }
        viewModelScope.launch {
            var previous = SyncSignals.remote.value
            SyncSignals.remote.collect { value ->
                if (previous != value) {
                    previous = value
                    libraryWrites.withLock {
                        library = store.load()
                        refreshTrash()
                        val valid = library.tags.map { it.id }.toSet()
                        if (!valid.containsAll(home.tagIds)) updateHome(home.copy(tagIds = home.tagIds.intersect(valid)))
                    }
                }
            }
        }
        viewModelScope.launch {
            TrashCleanupWorker.changes.collect { libraryWrites.withLock { refreshTrash() } }
        }
        viewModelScope.launch {
            for (value in homeWrites) {
                try { withContext(Dispatchers.IO) { preferencesStore.writeHome(value) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { notices.send("首页设置保存失败，下次启动可能需要重新选择") }
            }
        }
    }
    fun reload() {
        loading = true; loadError = null
        viewModelScope.launch {
            try {
                store.purgeExpiredTrash()
                library = store.load()
                refreshTrash()
                withContext(Dispatchers.IO) {
                    home = preferencesStore.readHome(); config = preferencesStore.readAi(); appearance = preferencesStore.readAppearance()
                    LauncherIconMode.apply(getApplication(), appearance.dynamicIcon)
                    _uiState.update { it.copy(syncConfig = syncSettings.read(),
                        syncStatus = if (SyncSignals.status.value.running) SyncSignals.status.value else syncSettings.status()) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { loadError = e.message ?: "资料读取失败" }
            finally { loading = false }
        }
    }
    private suspend fun refreshTrash() {
        val items = store.loadTrash()
        _uiState.update { it.copy(trash = items) }
    }
    private fun work(action: suspend () -> Unit, retry: (() -> Unit)? = null) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { libraryWrites.withLock { action() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { operationError = e.message ?: "操作失败，请重试"; retryAction = retry }
            finally { busy = false }
        }
    }
    /** Small tag changes queue in order and never open a blocking progress/error dialog. */
    private fun tagWork(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { libraryWrites.withLock { action() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notices.send(e.message ?: "标签操作失败，请重试") }
        }
    }
    fun clearError() { operationError = null; retryAction = null }
    val canRetry get() = _uiState.value.canRetry
    fun retry() { val action = retryAction; clearError(); action?.invoke() }
    fun updateHome(value: HomePreferences) {
        home = value
        homeWrites.trySend(value)
    }
    fun updateQuery(value: String) { query = value }
    private fun requestNavigation(target: Page) {
        val id = when (target) {
            Page.DETAIL -> selectedId
            Page.EDITOR -> if (previousPage == Page.DETAIL) draft.id else null
            else -> null
        }
        val finishing = page == Page.EDITOR && target != Page.EDITOR
        page = target
        navigationChannel.trySend(NavigationCommand(target, id, finishing))
    }
    fun onDestinationChanged(target: Page, entryId: String? = null) {
        if (target == Page.DETAIL) selectedId = entryId
        page = target
    }
    fun restoreEditor(entryId: String?) {
        if (entryId != null && draft.id != entryId) {
            library.entries.firstOrNull { it.id == entryId }?.let { draft = it; aiEdited = it.title.isNotBlank() }
            previousPage = Page.DETAIL
        }
        if (entryId != null) selectedId = entryId
        page = Page.EDITOR
    }
    fun openPage(value: Page) {
        requestNavigation(value)
        if (value == Page.TRASH) tagWork { store.purgeExpiredTrash(); refreshTrash() }
    }
    fun openEntry(entry: Entry) { selectedId = entry.id; requestNavigation(Page.DETAIL) }
    fun newEntry() {
        previousPage = Page.HOME; draft = Entry(); draftChanged = false; aiEdited = false; requestNavigation(Page.EDITOR)
    }
    fun editEntry(entry: Entry) {
        previousPage = Page.DETAIL; draft = entry; draftChanged = false; aiEdited = entry.title.isNotBlank(); requestNavigation(Page.EDITOR)
    }
    fun changeDraft(value: Entry, manualAiEdit: Boolean = false) {
        draft = if (value.body != draft.body) value.copy(bodyTitle = "") else value; draftChanged = true
        if (manualAiEdit) aiEdited = true
    }
    fun cancelEditor() {
        aiJob?.cancel(); generating = false
        requestNavigation(previousPage); draftChanged = false; draft = Entry()
        work({ store.cleanupUnused() })
    }
    fun removeAttachment(id: String) { changeDraft(draft.copy(attachments = draft.attachments.filterNot { it.id == id })) }
    fun updateStoredEntry(value: Entry) {
        if (generating) return
        work({
            val saved = store.saveEntry(value.copy(title = value.title.trim().ifBlank { "未命名条目" }), preserveConcurrent = true)
            library = store.load()
            runCatching { store.cleanupUnused() }
            if (saved.id != value.id) notices.send("条目在编辑期间已更新，已保留你的修改为冲突副本")
        }, { updateStoredEntry(value) })
    }
    fun importToEntry(entryId: String, uris: List<Uri>) {
        if (generating) return
        work({
            val current = library.entries.firstOrNull { it.id == entryId } ?: error("条目已不存在")
            val additions = mutableListOf<Attachment>()
            val failures = mutableListOf<Uri>()
            for (uri in uris.distinct()) {
                try { additions += store.importAttachment(uri) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failures += uri }
            }
            if (additions.isNotEmpty()) {
                store.saveEntry(current.copy(attachments = current.attachments + additions))
                library = store.load()
            }
            if (failures.isNotEmpty()) {
                operationError = "${failures.size} 个文件添加失败，成功添加的原件已保存"
                retryAction = { importToEntry(entryId, failures) }
            }
        }, { importToEntry(entryId, uris) })
    }
    fun toggleEntryTag(entryId: String, tagId: String): Unit = tagWork {
        if (generating) return@tagWork
        val current = library.entries.firstOrNull { it.id == entryId } ?: return@tagWork
        if (library.tags.none { it.id == tagId }) return@tagWork
        store.saveEntry(current.copy(tagIds = if (tagId in current.tagIds) current.tagIds - tagId else current.tagIds + tagId))
        library = store.load()
    }
    fun addTagToEntry(entryId: String, name: String): Unit = tagWork {
        val current = library.entries.firstOrNull { it.id == entryId } ?: error("条目已不存在")
        val tag = store.addTag(name)
        store.saveEntry(current.copy(tagIds = current.tagIds + tag.id)); library = store.load()
    }

    fun generateStoredTitle(entryId: String, attachmentId: String? = null, wholeEntry: Boolean = false) {
        if (busy || generating) return
        val captured = library.entries.firstOrNull { it.id == entryId } ?: return
        val requestConfig = config
        generating = true; progress = "正在读取所选资料…"
        aiJob = viewModelScope.launch {
            try {
                val result = if (wholeEntry) ai.generate(captured.body, captured.attachments, requestConfig) { progress = it }
                else com.collection.ai.generateSourceTitle(captured, attachmentId,
                    generate = { text, files, callback -> ai.generate(text, files, requestConfig, callback) },
                    onProgress = { progress = it })
                val latest = library.entries.firstOrNull { it.id == entryId } ?: return@launch
                val updated = when {
                    wholeEntry -> latest.copy(title = result.title)
                    attachmentId == null -> {
                        require(latest.body == captured.body) { "文字已变更，请重新生成标题" }
                        latest.copy(bodyTitle = result.title)
                    }
                    else -> {
                        val original = captured.attachments.first { it.id == attachmentId }
                        require(latest.attachments.any { it.id == attachmentId && it.path == original.path }) { "原件已变更，请重新生成标题" }
                        latest.copy(attachments = latest.attachments.map { if (it.id == attachmentId) it.copy(title = result.title) else it })
                    }
                }
                store.saveEntry(updated.copy(parseWarnings = com.collection.ai.mergeWebReadWarnings(latest.parseWarnings, result.warnings, result.webSources)))
                library = store.load()
                notices.send(if (result.warnings.isEmpty()) "标题已更新" else "标题已更新，部分资料读取提示请查看条目")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                operationError = e.message ?: "标题生成失败，原内容已保留"
                retryAction = { generateStoredTitle(entryId, attachmentId, wholeEntry) }
            } finally { generating = false }
        }
    }
    fun importToDraft(uris: List<Uri>): Unit = work({
        val failures = mutableListOf<Pair<Uri, String>>()
        val additions = mutableListOf<Attachment>()
        for (uri in uris.distinct()) {
            try { additions += store.importAttachment(uri) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += uri to (e.message ?: "无法导入文件") }
        }
        changeDraft(draft.copy(attachments = draft.attachments + additions))
        if (failures.isNotEmpty()) {
            operationError = "${failures.size} 个文件导入失败：${failures.first().second}。已成功添加的文件会保留。"
            retryAction = { importToDraft(failures.map { it.first }) }
        }
    }, { importToDraft(uris) })
    fun saveEntry() {
        if (generating) return
        val captured = draft
        if (captured.title.isBlank() && captured.body.isBlank() && captured.summary.isBlank() && captured.attachments.isEmpty()) {
            operationError = "请添加文字、文件或标题后再保存"; return
        }
        work({
            val title = captured.title.trim().ifBlank {
                captured.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(30)
                    ?: captured.attachments.firstOrNull()?.name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }?.take(30) ?: "未命名条目"
            }
            store.saveEntry(captured.copy(title = title, updatedAt = System.currentTimeMillis()))
            library = store.load(); selectedId = captured.id; requestNavigation(Page.DETAIL)
            draftChanged = false; draft = Entry()
            runCatching { store.cleanupUnused() }.onFailure { notices.trySend("条目已保存，部分暂存文件稍后可清理") }
            notices.send("已保存，原始资料已保留")
        }, { saveEntry() })
    }
    fun deleteEntry(entry: Entry): Unit = work({
        store.deleteEntry(entry.id); library = store.load(); requestNavigation(Page.HOME); selectedId = null
        refreshTrash()
        notices.send("已移到回收站")
    }, { deleteEntry(entry) })
    fun deleteEntries(ids: Set<String>): Unit = work({
        val moved = store.deleteEntries(ids)
        library = store.load()
        refreshTrash()
        if (moved > 0) notices.send("已移到回收站 · $moved 个条目")
    }, { deleteEntries(ids) })
    fun restoreTrash(ids: Set<String>): Unit = work({
        val restored = store.restoreEntries(ids)
        library = store.load()
        refreshTrash()
        if (restored > 0) notices.send("已恢复 $restored 个条目")
    }, { restoreTrash(ids) })

    fun purgeTrash(ids: Set<String>): Unit = work({
        val removed = store.purgeEntries(ids, draft.attachments)
        refreshTrash()
        if (removed > 0) {
            val config = _uiState.value.syncConfig
            if (config.configured) SyncScheduler.request(getApplication(), manual = true, config = config)
            notices.send(if (config.configured) "已永久清除 $removed 个条目，等待云端同步" else "已永久清除 $removed 个条目")
        }
    }, { purgeTrash(ids) })
    fun addTag(name: String, attachToDraft: Boolean = false): Unit = tagWork {
        val tag = store.addTag(name); library = store.load()
        if (attachToDraft) changeDraft(draft.copy(tagIds = draft.tagIds + tag.id))
    }
    fun renameTag(tag: Tag, name: String): Unit = work({ store.renameTag(tag.id, name); library = store.load() }, { renameTag(tag, name) })
    fun reorderTags(ids: List<String>): Unit = tagWork {
        val existing = library.tags.map { it.id }.toSet()
        val order = ids.distinct().filter { it in existing } + library.tags.map { it.id }.filterNot { it in ids }
        store.reorderTags(order); library = store.load()
    }
    fun deleteTag(tag: Tag): Unit = tagWork {
        store.deleteTag(tag.id); library = store.load()
        updateHome(home.copy(tagIds = home.tagIds - tag.id))
        draft = draft.copy(tagIds = draft.tagIds - tag.id)
        notices.send("标签已删除，条目和原始资料保留")
    }
    fun generate() {
        if (generating || busy) return
        val source = draft
        generating = true; progress = "正在读取原始资料…"
        aiJob = viewModelScope.launch {
            try {
                val result = com.collection.ai.generateOriginalTitles(source,
                    generate = { text, files, callback -> ai.generate(text, files, config, callback) },
                    onProgress = { progress = it },
                    combineTopics = { text, callback -> ai.generateTopics(text, config, callback) })
                draft = draft.copy(title = result.title, bodyTitle = result.bodyTitle,
                    attachments = result.attachments, parseWarnings = result.warnings)
                draftChanged = true; aiEdited = false
                if (result.warnings.isNotEmpty()) notices.send("部分原件标题生成失败，成功结果已保留")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { operationError = e.message ?: "AI 生成失败，可手动保存"; retryAction = { generate() } }
            finally { generating = false }
        }
    }
    fun cancelGeneration() { aiJob?.cancel() }
    fun saveSyncConfig(value: SyncConfig): Unit = work({
        val saved = withContext(Dispatchers.IO) { syncSettings.write(value); syncSettings.read() }
        _uiState.update { it.copy(syncConfig = saved, syncStatus = syncSettings.status()) }
        SyncScheduler.configure(getApplication(), saved)
        SyncScheduler.request(getApplication(), config = saved)
        notices.send("同步设置已保存")
    }, { saveSyncConfig(value) })
    fun testSync(value: SyncConfig) {
        if (_uiState.value.syncTesting || _uiState.value.syncStatus.running) return
        _uiState.update { it.copy(syncTesting = true) }
        viewModelScope.launch {
            try {
                SyncCoordinator.test(getApplication(), value.validated())
                notices.send("连接成功，已验证云端读写与提交")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notices.send(if (e is SyncFailure || e is IllegalArgumentException) e.message ?: "连接失败" else "连接失败，请检查网络和 WebDAV 地址") }
            finally { _uiState.update { it.copy(syncTesting = false) } }
        }
    }
    fun syncNow() {
        val current = _uiState.value
        if (current.syncStatus.running || !current.syncConfig.configured) return
        _uiState.update { it.copy(syncStatus = it.syncStatus.copy(message = "已安排同步，等待网络连接…")) }
        SyncScheduler.request(getApplication(), manual = true, config = current.syncConfig)
    }
    fun saveConfig(value: AiConfig): Unit = work({
        withContext(Dispatchers.IO) { preferencesStore.writeAi(value) }
        config = value; notices.send("AI 设置已保存")
    }, { saveConfig(value) })
    fun updateAppearance(value: AppearanceSettings) {
        appearance = value
        viewModelScope.launch {
            try {
                appearanceMutex.withLock {
                    if (appearance == value) withContext(Dispatchers.IO) {
                        LauncherIconMode.apply(getApplication(), value.dynamicIcon)
                        preferencesStore.writeAppearance(value)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notices.send("主题设置保存失败，下次启动可能需要重新调整")
            }
        }
    }
    fun receiveShare(payload: SharePayload) { pendingShare = payload }
    fun dismissShare() { pendingShare = null }
    fun acceptShare(separate: Boolean) {
        val share = pendingShare ?: return
        pendingShare = null
        if (!separate) {
            newEntry(); changeDraft(draft.copy(body = share.text)); importToDraft(share.uris)
        } else importSeparateShare(share)
    }
    private fun importSeparateShare(share: SharePayload): Unit = work({
            var successes = 0; val errors = mutableListOf<Pair<Uri, String>>()
            share.uris.distinct().forEach { uri ->
                try {
                    val attachment = store.importAttachment(uri)
                    store.saveEntry(Entry(title = attachment.name.substringBeforeLast('.').ifBlank { attachment.name },
                        body = share.text, attachments = listOf(attachment)))
                    successes++
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors += uri to (e.message ?: "导入失败") }
            }
            library = store.load(); requestNavigation(Page.HOME)
            notices.send("已创建 $successes 个条目，可在编辑页生成 AI 标题")
            if (errors.isNotEmpty()) {
                operationError = "${errors.size} 个文件导入失败：${errors.first().second}。已成功创建的条目会保留。"
                retryAction = { importSeparateShare(share.copy(uris = errors.map { it.first })) }
            }
        })
    fun exportBackup(uri: Uri): Unit = work({ store.exportBackup(uri); notices.send("备份已导出，包含条目、标签和原件，不含 API Key") }, { exportBackup(uri) })
    fun restoreBackup(uri: Uri): Unit = work({ store.restoreBackup(uri); library = store.load(); notices.send("备份已合并导入，现有条目已保留") }, { restoreBackup(uri) })
}

