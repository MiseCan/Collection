package com.collection.sync

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.collection.data.LibraryStore
import com.collection.domain.Attachment
import com.collection.domain.Entry
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.net.URI
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Two real isolated SQLite libraries exchange original bytes through a local HTTPS WebDAV server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SyncRuntimeTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var root: File
    private lateinit var aContext: DeviceContext
    private lateinit var bContext: DeviceContext
    private lateinit var a: LibraryStore
    private lateinit var b: LibraryStore
    private lateinit var dav: DavFixture
    private val receiptsA = mutableSetOf<String>()
    private val receiptsB = mutableSetOf<String>()
    private val config get() = SyncConfig(server.url("/dav/").toString(), "account@example.com", "test-application-password")
    private val nutstoreConfig get() = config.copy(endpoint = server.url("/dav/").newBuilder().host("dav.jianguoyun.com").build().toString())
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = File(app.cacheDir, "sync-test-${UUID.randomUUID()}").apply { mkdirs() }
        aContext = DeviceContext(app, File(root, "a")); bContext = DeviceContext(app, File(root, "b"))
        a = LibraryStore(aContext, A); b = LibraryStore(bContext, B)
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("dav.jianguoyun.com").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); start() }
        http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == "dav.jianguoyun.com") listOf(InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(hostname)
            })
            .followRedirects(false).followSslRedirects(false).build()
        dav = DavFixture(); server.dispatcher = dav
    }
    @After fun teardown() {
        if (::server.isInitialized) server.shutdown()
        if (::http.isInitialized) { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
        aContext.close(); bContext.close()
        if (!root.deleteRecursively()) root.walkTopDown().forEach { it.deleteOnExit() }
    }
    private fun engine(store: SyncLocalStore, actor: String, receipts: MutableSet<String>, endpoint: SyncConfig = config) =
        SyncEngine(store, WebDavClient(endpoint, http), actor, File(root, "cache-$actor"), { receipts }, { receipts += it })
    private suspend fun syncA() = engine(a, A, receiptsA).run()
    private suspend fun syncB() = engine(b, B, receiptsB).run()
    private suspend fun seed(): Entry {
        val file = File(aContext.filesDir, "originals/${UUID.randomUUID()}.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        val tag = a.addTag("资料")
        val entry = Entry(title = "第一份资料", body = "需要同步的原文", tagIds = setOf(tag.id),
            attachments = listOf(Attachment(name = "原始资料.pdf", title = "资料标题", mime = "application/pdf", size = file.length(), path = file.path)))
        a.saveEntry(entry); return a.load().entries.single()
    }
    @Test fun twoDevicesExchangeOriginalsAndRepeatedSyncDoesNotDuplicateOrReupload() = runBlocking {
        val original = seed()
        assertEquals(1, syncA().uploaded)
        assertEquals(1, syncB().downloaded)
        val received = b.load().entries.single()
        assertEquals(original.id, received.id); assertEquals(original.tagIds, received.tagIds)
        assertEquals("资料标题", received.attachments.single().title)
        assertArrayEquals(File(original.attachments.single().path).readBytes(), File(received.attachments.single().path).readBytes())
        val before = dav.blobPuts
        a.saveEntry(a.load().entries.single().copy(title = "只修改标题"))
        syncA(); syncB(); syncA(); syncB()
        assertEquals(before, dav.blobPuts)
        assertEquals("只修改标题", b.load().entries.single().title)
        assertEquals(1, a.load().entries.size); assertEquals(1, b.load().entries.size)
        assertFalse(dav.files.values.filter { it.size < 100_000 }.any { it.toString(Charsets.UTF_8).contains(aContext.filesDir.path) })
    }
    @Test fun concurrentOfflineEditsConvergeAndConflictCopiesAreNotRepeated() = runBlocking {
        seed(); syncA(); syncB(); syncA()
        a.saveEntry(a.load().entries.single().copy(title = "手机修改"))
        b.saveEntry(b.load().entries.single().copy(body = "另一台设备离线编辑的全文"))
        syncA(); syncB(); syncA(); syncB()
        assertEquals(2, a.load().entries.size)
        assertEquals(a.load().entries.map { it.id }.toSet(), b.load().entries.map { it.id }.toSet())
        assertTrue(a.load().entries.any { it.title.startsWith("手机修改") })
        assertTrue(a.load().entries.any { it.body == "另一台设备离线编辑的全文" })
        val ids = a.load().entries.map { it.id }.toSet()
        syncA(); syncB()
        assertEquals(ids, b.load().entries.map { it.id }.toSet())
    }
    @Test fun offlineStaleDeviceCannotRestoreADeletedEntry() = runBlocking {
        val original = seed(); syncA(); syncB(); syncA()
        a.deleteEntry(original.id); a.cleanupUnused(); syncA(); syncB(); syncA()
        assertTrue(a.load().entries.isEmpty()); assertTrue(b.load().entries.isEmpty())
        assertNotNull(a.captureSync().document.records["entry:${original.id}"])
        assertNull(b.captureSync().document.records.getValue("entry:${original.id}").payload)
    }

    @Test fun remoteDeletionMovesLocalCopyToTrashAndRestorationPropagates() = runBlocking {
        val original = seed()
        syncA(); syncB()
        val receivedPath = b.load().entries.single().attachments.single().path
        a.deleteEntry(original.id)
        syncA(); syncB()
        b.cleanupUnused()
        assertTrue(File(receivedPath).isFile)
        assertEquals(original.id, b.loadTrash().single().id)
        assertTrue(b.load().entries.isEmpty())
        b.restoreEntries(setOf(original.id))
        syncB(); syncA()
        assertTrue(a.loadTrash().isEmpty())
        assertEquals(original.tagIds, a.load().entries.single().tagIds)
        assertEquals(original.id, a.load().entries.single().id)
    }

    @Test fun permanentPurgeClearsBothDevicesAndCloudButPreservesSharedOriginalUntilLastUse() = runBlocking {
        val first = seed()
        val second = a.saveEntry(first.copy(id = UUID.randomUUID().toString(), title = "另一条收录"))
        syncA(); syncB()
        val objectPath = dav.files.keys.single { it.endsWith(".bin") }
        a.deleteEntry(first.id)
        syncA(); syncB()
        assertEquals(first.id, b.loadTrash().single().id)
        a.purgeEntries(setOf(first.id))
        syncA()
        assertTrue(dav.files.containsKey(objectPath))
        assertTrue(dav.files.filterKeys { it.endsWith(".json") }.values.none {
            "entry:${first.id}" in SyncDocument.decode(it.toString(Charsets.UTF_8)).records
        })
        b.restoreEntries(setOf(first.id))
        syncB()
        assertEquals(second.id, b.load().entries.single().id)
        assertTrue(b.loadTrash().isEmpty())
        a.deleteEntry(second.id)
        a.purgeEntries(setOf(second.id))
        syncA(); syncB()
        assertFalse(dav.files.containsKey(objectPath))
        assertTrue(b.load().entries.isEmpty())
        assertTrue(b.loadTrash().isEmpty())
    }

    @Test fun aReaddedOriginalIsUploadedEvenAfterItsPreviousCloudCopyWasPurged() = runBlocking {
        val first = seed()
        val bytes = File(first.attachments.single().path).readBytes()
        syncA()
        a.deleteEntry(first.id)
        a.purgeEntries(setOf(first.id))
        syncA()
        assertTrue(dav.files.keys.none { it.endsWith(".bin") })
        val file = File(aContext.filesDir, "originals/${UUID.randomUUID()}.bin").apply { writeBytes(bytes) }
        val fresh = first.copy(id = UUID.randomUUID().toString(), attachments = listOf(first.attachments.single().copy(path = file.path)))
        a.saveEntry(fresh)
        assertEquals(1, syncA().uploaded)
        syncB()
        assertEquals(fresh.id, b.load().entries.single().id)
    }

    @Test fun cloudBytesWaitForLegacyDevicesToReportTheirTrashReferences() = runBlocking {
        val first = seed()
        syncA(); syncB()
        val objectPath = dav.files.keys.single { it.endsWith(".bin") }
        val oldHeadPath = dav.files.keys.single { it.endsWith("$B.json") }
        val oldHead = SyncDocument.decode(dav.files.getValue(oldHeadPath).toString(Charsets.UTF_8))
        dav.files[oldHeadPath] = oldHead.copy(retained = null).encode().toByteArray()
        a.deleteEntry(first.id)
        a.purgeEntries(setOf(first.id))
        assertTrue(syncA().cleanupPending)
        assertTrue(dav.files.containsKey(objectPath))
        assertFalse(syncB().cleanupPending)
        assertFalse(dav.files.containsKey(objectPath))
        assertTrue(b.load().entries.isEmpty())
    }

    @Test fun anActiveCloudWriterPreventsAnotherDeviceFromEntering() = runBlocking {
        val first = WebDavClient(config, http)
        val second = WebDavClient(config, http)
        first.initialize()
        first.withWriteLock(A) {
            try {
                second.withWriteLock(B) { fail("第二台设备不能同时写入云端") }
                fail("应要求第二台设备稍后重试")
            } catch (e: SyncFailure) { assertTrue(e.retryable) }
        }
        assertEquals("released", second.withWriteLock(B) { "released" })
    }

    @Test fun missingEtagsDoNotMakeTheCloudDirectoryCacheStale() = runBlocking {
        dav.omitEtags = true
        seed(); syncA(); syncB()
        a.saveEntry(a.load().entries.single().copy(title = "服务端未提供版本标记时仍能更新"))
        syncA(); syncB()
        assertEquals("服务端未提供版本标记时仍能更新", b.load().entries.single().title)
    }
    @Test fun interruptedUploadResumesUsingCompletedOriginalsWithoutLosingLocalData() = runBlocking {
        seed(); dav.failPublication = true
        try { syncA(); fail("目录提交应失败") } catch (_: SyncFailure) { }
        val puts = dav.blobPuts
        assertEquals(1, a.load().entries.size)
        dav.failPublication = false
        syncA(); syncB()
        assertEquals(puts, dav.blobPuts); assertEquals(1, b.load().entries.size)
    }
    @Test fun corruptedDownloadDoesNotCommitPartialRemoteChanges() = runBlocking {
        seed(); syncA(); dav.corruptDownloads = true
        try { syncB(); fail("应拒绝损坏的原件") } catch (_: IllegalArgumentException) { }
        assertTrue(b.load().entries.isEmpty())
        dav.corruptDownloads = false; syncB(); assertEquals(1, b.load().entries.size)
    }
    @Test fun localWriteDuringNetworkTransferIsMergedInsteadOfOverwritten() = runBlocking {
        val original = seed(); syncA(); syncB(); syncA()
        a.saveEntry(a.load().entries.single().copy(title = "云端已修改")); syncA()
        var injected = false
        val intercept = object : SyncLocalStore {
            override suspend fun captureSync() = b.captureSync()
            override suspend fun applySync(remote: SyncDocument, downloaded: Map<String, File>): SyncSnapshot {
                if (!injected) { injected = true; b.saveEntry(b.load().entries.single().copy(body = "传输过程中刚输入的内容")) }
                return b.applySync(remote, downloaded)
            }
        }
        engine(intercept, B, receiptsB).run(); syncA()
        assertEquals(2, b.load().entries.size)
        assertTrue(a.load().entries.any { it.title.startsWith("云端已修改") })
        assertTrue(a.load().entries.any { it.body == "传输过程中刚输入的内容" })
        assertTrue(a.load().entries.any { it.id == original.id })
    }
    @Test fun credentialFailureDoesNotMutateLocalLibraryOrUploadAnything() = runBlocking {
        seed(); dav.rejectAuthorization = true
        try { syncA(); fail("应提示鉴权失败") } catch (e: SyncFailure) { assertTrue(e.message!!.contains("应用密码")) }
        assertEquals(1, a.load().entries.size); assertEquals(0, dav.blobPuts)
    }

    @Test fun savingAnOpenEditorAfterAutomaticSyncPreservesBothVersions() = runBlocking {
        seed(); syncA(); syncB()
        val opened = b.load().entries.single()
        a.saveEntry(a.load().entries.single().copy(title = "其他设备已保存的标题"))
        syncA(); syncB()
        val saved = b.saveEntry(opened.copy(body = "同步期间仍在编辑的内容"), preserveConcurrent = true)
        assertNotEquals(opened.id, saved.id)
        assertEquals("其他设备已保存的标题", b.load().entries.single { it.id == opened.id }.title)
        assertEquals("同步期间仍在编辑的内容", b.load().entries.single { it.id == saved.id }.body)
    }
    @Test fun connectionProbeUsesPropfindAndTestsMoveAndOnlyDeletesItsOwnFiles() = runBlocking {
        dav.files["/dav/keep.txt"] = "保留".toByteArray()
        WebDavClient(config, http).test()
        assertArrayEquals("保留".toByteArray(), dav.files.getValue("/dav/keep.txt"))
        assertFalse(dav.files.keys.any { it.contains("connection-") })
        assertEquals(2, dav.moveRequests)
    }

    @Test fun nutstoreConnectionAndRepeatedSyncSucceedWhenServerRejectsMove() = runBlocking {
        dav.rejectMove = true
        WebDavClient(nutstoreConfig, http).test()
        assertFalse(dav.files.keys.any { it.contains("connection-") })
        seed()
        val first = engine(a, A, receiptsA, nutstoreConfig)
        val second = engine(b, B, receiptsB, nutstoreConfig)
        assertEquals(1, first.run().uploaded)
        assertEquals(1, second.run().downloaded)
        a.saveEntry(a.load().entries.single().copy(title = "坚果云直接更新目录"))
        assertEquals(0, first.run().uploaded)
        second.run()
        assertEquals("坚果云直接更新目录", b.load().entries.single().title)
        assertEquals(0, dav.moveRequests)
        assertFalse(dav.files.keys.any { it.endsWith(".tmp") })
    }

    @Test fun nutstoreDoesNotReportSuccessWhenPublishedDirectoryReadbackDisagrees() = runBlocking {
        seed()
        val first = engine(a, A, receiptsA, nutstoreConfig)
        first.run()
        a.saveEntry(a.load().entries.single().copy(title = "等待云端确认"))
        dav.ignoreHeadUpdates = true
        try {
            first.run()
            fail("云端返回旧目录时不能报告成功")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("写入校验失败"))
        }
        assertEquals("等待云端确认", a.load().entries.single().title)
    }

    @Test fun directorySetupHandlesBothTrailingSlashRejectionAndExistingDirectoryConflict() = runBlocking {
        dav.rejectMkcolTrailingSlash = true
        dav.existingDirectoryStatus = 409
        WebDavClient(config, http).test()
        seed()
        assertEquals(1, syncA().uploaded)
        assertEquals(1, syncB().downloaded)
        assertEquals(1, b.load().entries.size)
    }

    @Test fun missingParentStopsBeforeUploadingWithTheAffectedPath() = runBlocking {
        val missing = config.copy(endpoint = server.url("/dav/missing/").toString())
        try {
            WebDavClient(missing, http).initialize()
            fail("缺失的父目录应阻止同步")
        } catch (e: SyncFailure) {
            assertTrue(e.message.orEmpty().contains("/dav/missing/Collection"))
        }
        assertTrue(dav.files.isEmpty())
    }
    @Test fun changingConfigurationBeforeCommitStopsWithoutPublishing() = runBlocking {
        seed()
        val engine = SyncEngine(a, WebDavClient(config, http), A, File(root, "changed"), ensureCurrent = { error("配置已变化") })
        try { engine.run(); fail("应停止") } catch (_: IllegalStateException) { }
        assertEquals(1, a.load().entries.size); assertEquals(0, dav.blobPuts)
    }

    private class DeviceContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val databases = mutableListOf<SQLiteDatabase>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, "db/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory).also { databases += it }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler).also { databases += it }
        fun close() { databases.filter { it.isOpen }.forEach { it.close() } }
    }

    private class DavFixture : okhttp3.mockwebserver.Dispatcher() {
        val files = ConcurrentHashMap<String, ByteArray>()
        private val folders = ConcurrentHashMap.newKeySet<String>().apply { add("/dav") }
        var blobPuts = 0
        var failPublication = false
        var corruptDownloads = false
        var rejectAuthorization = false
        var omitEtags = false
        var rejectMkcolTrailingSlash = false
        var existingDirectoryStatus = 405
        var rejectMove = false
        var moveRequests = 0
        var ignoreHeadUpdates = false
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (rejectAuthorization || request.getHeader("Authorization") != Credentials.basic("account@example.com", "test-application-password", Charsets.UTF_8))
                return MockResponse().setResponseCode(401)
            val path = request.requestUrl!!.encodedPath
            val directory = path.trimEnd('/')
            val parent = directory.substringBeforeLast('/')
            return when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(when {
                    rejectMkcolTrailingSlash && path.endsWith('/') -> 409
                    parent !in folders -> 409
                    folders.add(directory) -> 201
                    else -> existingDirectoryStatus
                })
                "PROPFIND" -> {
                    if (directory !in folders) return MockResponse().setResponseCode(if (parent in folders) 404 else 409)
                    val entries = mutableListOf("<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                    if (request.getHeader("Depth") == "1") files.entries.filter { it.key.startsWith(path) && '/' !in it.key.removePrefix(path) }.forEach { (name, bytes) ->
                        val etag = if (omitEtags) "" else digest(bytes)
                        entries += "<d:response><d:href>$name</d:href><d:propstat><d:prop><d:resourcetype/><d:getetag>$etag</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                    }
                    MockResponse().setResponseCode(207).setBody("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">${entries.joinToString("")}</d:multistatus>")
                }
                "PUT" -> {
                    if (parent !in folders) return MockResponse().setResponseCode(409)
                    if (ignoreHeadUpdates && path.endsWith(".json") && files.containsKey(path)) return MockResponse().setResponseCode(204)
                    files[path] = request.body.readByteArray()
                    if (path.endsWith(".bin")) blobPuts++
                    MockResponse().setResponseCode(201)
                }
                "GET" -> {
                    val bytes = files[path]
                    if (bytes == null) MockResponse().setResponseCode(404)
                    else MockResponse().setBody(Buffer().write(if (corruptDownloads && path.endsWith(".bin")) ByteArray(bytes.size) else bytes))
                }
                "HEAD" -> MockResponse().setResponseCode(if (files.containsKey(path)) 200 else 404)
                "MOVE" -> {
                    moveRequests++
                    if (rejectMove) return MockResponse().setResponseCode(409)
                    if (failPublication && path.contains("devices/")) MockResponse().setResponseCode(403)
                    else { val bytes = files.remove(path); if (bytes == null) MockResponse().setResponseCode(404)
                        else { files[URI(request.getHeader("Destination")!!).rawPath] = bytes; MockResponse().setResponseCode(201) } }
                }
                "DELETE" -> MockResponse().setResponseCode(when {
                    files.remove(path) != null -> 204
                    directory !in folders -> 404
                    files.keys.any { it.startsWith("$directory/") } -> 409
                    else -> { folders.remove(directory); 204 }
                })
                else -> MockResponse().setResponseCode(405)
            }
        }
    }
}
