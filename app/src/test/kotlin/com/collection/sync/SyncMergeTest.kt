package com.collection.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal const val A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
internal const val B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
internal const val C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
internal fun entryData(id: String = "item", title: String = "资料", body: String = "原文"): String = canonical(JSONObject()
    .put("id", id).put("title", title).put("summary", "").put("body", body).put("bodyTitle", "文字")
    .put("createdAt", 1).put("updatedAt", 2).put("tagIds", JSONArray()).put("parseWarnings", JSONArray()).put("attachments", JSONArray()))
internal fun record(title: String = "资料", clock: Map<String, Long> = mapOf(A to 1), deleted: Boolean = false) =
    SyncRecord("entry", "item", clock, if (deleted) null else entryData(title = title))
internal fun doc(vararg records: SyncRecord) = SyncDocument(records.associateBy { it.key })

class SyncMergeTest {
    @Test fun causalEditReplacesAnOlderValueWithoutConflict() {
        val merged = SyncMerge.merge(doc(record()), doc(record("修改", mapOf(A to 2))))
        assertEquals(1, merged.records.size)
        assertEquals("修改", JSONObject(merged.records.getValue("entry:item").payload!!).getString("title"))
    }
    @Test fun concurrentEditsPreserveBothTitlesAndHaveDeterministicIdentity() {
        val a = doc(record("设备甲", mapOf(A to 1))); val b = doc(record("设备乙", mapOf(B to 1)))
        val merged = SyncMerge.merge(a, b)
        assertEquals(2, merged.records.size)
        val titles = merged.records.values.map { JSONObject(it.payload!!).getString("title").removeSuffix("（冲突副本）") }.toSet()
        assertEquals(setOf("设备甲", "设备乙"), titles)
        assertEquals(merged.encode(), SyncMerge.merge(b, a).encode())
        assertEquals(merged.encode(), SyncMerge.merge(merged, a, b, merged).encode())
    }
    @Test fun threeConcurrentDevicesConvergeRegardlessOfDocumentOrder() {
        val a = doc(record("甲", mapOf(A to 1))); val b = doc(record("乙", mapOf(B to 1))); val c = doc(record("丙", mapOf(C to 1)))
        val expected = SyncMerge.merge(a, b, c)
        assertEquals(3, expected.records.size)
        for (input in listOf(arrayOf(c, b, a), arrayOf(b, a, c), arrayOf(a, c, b))) {
            assertEquals(expected.encode(), SyncMerge.merge(*input).encode())
        }
        assertEquals(expected.encode(), SyncMerge.merge(SyncMerge.merge(a, b), c).encode())
    }
    @Test fun deletionPreventsAnOfflineDeviceFromResurrectingAnOldEntry() {
        val deleted = doc(record(clock = mapOf(A to 2), deleted = true))
        val result = SyncMerge.merge(deleted, doc(record()))
        assertEquals(1, result.records.size); assertNull(result.records.getValue("entry:item").payload)
    }
    @Test fun concurrentDeleteAndEditKeepDeletionAndRescueTheEditedContent() {
        val merged = SyncMerge.merge(doc(record(deleted = true)), doc(record("未同步的修改", mapOf(B to 1))))
        assertNull(merged.records.getValue("entry:item").payload)
        assertEquals(1, merged.records.values.count { it.payload != null })
        assertTrue(merged.records.values.any { it.payload?.contains("未同步的修改") == true })
    }
    @Test fun identicalConcurrentContentJoinsVersionsWithoutDuplicatingIt() {
        val merged = SyncMerge.merge(doc(record()), doc(record(clock = mapOf(B to 1))))
        assertEquals(1, merged.records.size)
        assertEquals(mapOf(A to 1L, B to 1L), merged.records.getValue("entry:item").clock)
    }
    @Test fun causalityWinsEvenWhenDeviceTimestampsAreIncorrect() {
        val old = record().copy(payload = canonical(JSONObject(entryData()).put("updatedAt", Long.MAX_VALUE)))
        val newest = record("新内容", mapOf(A to 2))
        assertEquals(newest.payload, SyncMerge.merge(doc(old), doc(newest)).records.getValue(old.key).payload)
    }
    @Test fun sameNameConcurrentTagReorderDoesNotCreateExtraTags() {
        fun tag(position: Int, actor: String) = SyncRecord("tag", "tag", mapOf(actor to 1),
            canonical(JSONObject().put("id", "tag").put("name", "工作").put("position", position)))
        val merged = SyncMerge.merge(doc(tag(0, A)), doc(tag(2, B)))
        assertEquals(1, merged.records.size)
    }
    @Test fun independentSameNameTagsRemainStableAcrossRepeatedMerges() {
        fun tag(id: String, actor: String) = SyncRecord("tag", id, mapOf(actor to 1),
            canonical(JSONObject().put("id", id).put("name", "工作").put("position", 0)))
        val a = doc(tag("ta", A)); val b = doc(tag("tb", B))
        val merged = SyncMerge.merge(a, b)
        assertEquals(2, merged.records.size)
        assertEquals(2, merged.records.values.map { JSONObject(it.payload!!).getString("name") }.distinct().size)
        assertEquals(merged.encode(), SyncMerge.merge(a, b, merged).encode())
    }
    @Test fun encodedDocumentRoundTripsAndExcludesLocalPathsAndCredentials() {
        val original = doc(record())
        assertEquals(original.encode(), SyncDocument.decode(original.encode()).encode())
        assertFalse(original.encode().contains("password")); assertFalse(original.encode().contains("path"))
    }
    @Test fun wrongProtocolIsRejected() { rejects { SyncDocument.decode(doc(record()).encode().replace("collection-sync-v1", "foreign-format")) } }
    @Test fun duplicateRecordsAreRejected() {
        val json = JSONObject(doc(record()).encode()); val records = json.getJSONArray("records")
        records.put(records.getJSONObject(0)); rejects { SyncDocument.decode(json.toString()) }
    }
    @Test fun negativeVersionIsRejected() { rejects { SyncDocument.decode(doc(record(clock = mapOf(A to -1))).encode()) } }
    @Test fun unknownDeviceIdentifierIsRejected() { rejects { SyncDocument.decode(doc(record(clock = mapOf("../other" to 1))).encode()) } }
    @Test fun mismatchedEntryIdentityIsRejected() { rejects { SyncDocument.decode(doc(record().copy(payload = entryData("different"))).encode()) } }
    @Test fun unsafeOriginalHashIsRejected() {
        val json = JSONObject(entryData()).put("attachments", JSONArray().put(JSONObject()
            .put("id", "file").put("name", "文档.pdf").put("title", "文档").put("mime", "application/pdf")
            .put("size", 10).put("hash", "../../outside")))
        rejects { SyncDocument.decode(doc(record().copy(payload = canonical(json))).encode()) }
    }
    @Test fun httpsEndpointRejectsEmbeddedPasswordsAndQueryTokens() {
        for (endpoint in listOf("http://example.com/dav/", "https://name:secret@example.com/", "https://example.com/?token=secret"))
            rejects { SyncConfig(endpoint, "account", "app-password").validated() }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("应拒绝无效同步数据") } catch (_: IllegalArgumentException) { }
    }
}
