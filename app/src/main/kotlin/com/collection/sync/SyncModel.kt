package com.collection.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal const val MAX_SYNC_BYTES = 32 * 1024 * 1024
internal const val MAX_ORIGINAL_BYTES = 100L * 1024 * 1024
internal val HASH = Regex("[0-9a-f]{64}")
internal val ACTOR = Regex("[0-9a-f-]{36}")

internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun fileHash(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun canonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

data class SyncRecord(val kind: String, val id: String, val clock: Map<String, Long>, val payload: String?) {
    val key get() = "$kind:$id"
    fun dominates(other: SyncRecord): Boolean = other.clock.all { (actor, count) -> (clock[actor] ?: 0) >= count }
}

data class SyncDocument(
    val records: Map<String, SyncRecord> = emptyMap(),
    val purged: Map<String, Set<String>> = emptyMap(),
    val retained: Map<String, Set<String>>? = null,
) {
    fun encode(): String = canonical(JSONObject().put("format", if (retained == null && purged.isEmpty()) "collection-sync-v1" else "collection-sync-v2").put("records", JSONArray().apply {
        records.toSortedMap().values.forEach { r -> put(JSONObject().put("kind", r.kind).put("id", r.id)
            .put("clock", JSONObject(r.clock)).put("data", r.payload?.let(::JSONObject) ?: JSONObject.NULL)) }
    }).apply {
        if (purged.isNotEmpty()) put("purged", hashIndex(purged))
        retained?.let { put("retained", hashIndex(it)) }
    })

    fun withoutPurged(markers: Map<String, Set<String>>): SyncDocument = copy(
        records = records.filterValues { it.kind != "entry" || it.id !in markers },
        purged = markers,
        retained = retained?.filterKeys { it !in markers },
    )
    fun blobs(): Map<String, Long> = buildMap {
        records.values.filter { it.kind == "entry" && it.payload != null }.forEach { r ->
            val files = JSONObject(r.payload!!).getJSONArray("attachments")
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i); val hash = file.getString("hash"); val size = file.getLong("size")
                require(get(hash)?.let { it == size } != false) { "云端原件长度声明不一致" }; put(hash, size)
            }
        }
    }
    companion object {
        fun decode(text: String): SyncDocument {
            require(text.toByteArray().size <= MAX_SYNC_BYTES) { "同步目录过大" }
            val json = JSONObject(text)
            require(json.getString("format") in setOf("collection-sync-v1", "collection-sync-v2")) { "云端同步格式不兼容，请更新应用" }
            val array = json.getJSONArray("records")
            require(array.length() <= 100_000) { "同步记录过多" }
            val records = linkedMapOf<String, SyncRecord>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i); val kind = item.getString("kind"); val id = item.getString("id")
                require(kind in listOf("entry", "tag") && id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl)) { "同步记录标识无效" }
                val clockJson = item.getJSONObject("clock")
                require(clockJson.length() in 1..128) { "同步版本信息无效" }
                val clock = clockJson.keys().asSequence().associateWith { actor ->
                    require(ACTOR.matches(actor)) { "设备标识无效" }
                    clockJson.getLong(actor).also { require(it in 1 until Long.MAX_VALUE) { "同步版本超限" } }
                }
                val data = if (item.isNull("data")) null else item.getJSONObject("data")
                if (data != null) validatePayload(kind, id, data)
                val record = SyncRecord(kind, id, clock, data?.let(::canonical))
                require(records.put(record.key, record) == null) { "同步记录重复" }
            }
            require(records.values.count { it.kind == "entry" && it.payload != null } <= 10_000 &&
                records.values.count { it.kind == "tag" && it.payload != null } <= 5_000) { "云端条目或标签数量超限" }
            val purged = json.optJSONObject("purged")?.let(::readHashIndex).orEmpty()
            val retained = json.optJSONObject("retained")?.let(::readHashIndex)
            return SyncDocument(records, purged, retained).also { require(it.blobs().size <= 20_000) { "云端原件数量超限" } }
        }

        private fun hashIndex(index: Map<String, Set<String>>) = JSONObject().apply {
            index.forEach { (id, hashes) -> put(id, JSONArray(hashes.sorted())) }
        }

        private fun readHashIndex(index: JSONObject): Map<String, Set<String>> {
            require(index.length() <= 100_000) { "回收站同步记录过多" }
            return index.keys().asSequence().associateWith { id ->
                require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl)) { "回收站同步标识无效" }
                val hashes = index.getJSONArray(id)
                require(hashes.length() <= 20_000) { "回收站原件数量超限" }
                (0 until hashes.length()).map { hashes.getString(it).also { hash -> require(HASH.matches(hash)) } }.toSet()
            }
        }

        private fun validatePayload(kind: String, id: String, data: JSONObject) {
            require(data.getString("id") == id) { "同步记录身份不一致" }
            if (kind == "tag") {
                val name = data.getString("name")
                require(name.isNotBlank() && name.length <= 100 && name.none(Char::isISOControl)) { "云端标签无效" }
                require(data.getInt("position") >= 0)
            } else {
                require(data.getString("title").length <= 100_000 && data.getString("body").length <= 2_000_000)
                data.getString("summary"); data.getString("bodyTitle"); data.getLong("createdAt"); data.getLong("updatedAt")
                data.getJSONArray("tagIds"); data.getJSONArray("parseWarnings")
                val files = data.getJSONArray("attachments")
                require(files.length() <= 20_000) { "单条原件数量超限" }
                val ids = mutableSetOf<String>()
                for (j in 0 until files.length()) {
                    val f = files.getJSONObject(j)
                    require(ids.add(f.getString("id")) && f.getString("id").length in 1..128)
                    require(HASH.matches(f.getString("hash")) && f.getLong("size") in 0..MAX_ORIGINAL_BYTES) { "原件校验信息无效" }
                    require(f.getString("name").length in 1..512 && f.getString("mime").length <= 255)
                    f.getString("title")
                    require(!f.has("path")) { "云端目录不应包含本机路径" }
                }
            }
        }
    }
}

/** Causal versions, rather than wall clocks, distinguish an edit from a concurrent edit. */
internal object SyncMerge {
    fun merge(vararg docs: SyncDocument): SyncDocument {
        val purged = linkedMapOf<String, Set<String>>()
        docs.forEach { doc -> doc.purged.forEach { (id, hashes) -> purged[id] = purged[id].orEmpty() + hashes } }
        docs.forEach { doc ->
            doc.retained.orEmpty().forEach { (id, hashes) ->
                if (id in purged) purged[id] = purged.getValue(id) + hashes
            }
            doc.records.values.filter { it.kind == "entry" && it.id in purged && it.payload != null }.forEach { record ->
                val files = JSONObject(record.payload!!).getJSONArray("attachments")
                purged[record.id] = purged.getValue(record.id) + (0 until files.length()).map { files.getJSONObject(it).getString("hash") }
            }
        }
        val out = linkedMapOf<String, SyncRecord>()
        val pending = ArrayDeque(docs.flatMap { it.records.values }.sortedWith(compareBy({ it.key }, { it.payload.orEmpty() })))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst(); val old = out[next.key]
            if (next.kind == "entry" && next.id in purged) continue
            if (old == null) { out[next.key] = next; continue }
            if (old == next) continue
            val clock = (old.clock.keys + next.clock.keys).associateWith { maxOf(old.clock[it] ?: 0, next.clock[it] ?: 0) }
            val winner: SyncRecord
            if (old.payload == next.payload) winner = old
            else if (old.dominates(next) && !next.dominates(old)) winner = old
            else if (next.dominates(old) && !old.dominates(next)) winner = next
            else {
                winner = if (old.payload == null) old else if (next.payload == null) next
                    else listOf(old, next).maxBy { digest(it.payload!!.toByteArray()) }
                val loser = if (winner === old) next else old
                if (loser.payload != null && old.clock != next.clock && (old.kind == "entry" || winner.payload == null ||
                        JSONObject(loser.payload).getString("name") != JSONObject(winner.payload).getString("name"))) {
                    val id = UUID.nameUUIDFromBytes((loser.key + ":" + loser.payload).toByteArray()).toString()
                    val data = JSONObject(loser.payload).put("id", id)
                    if (old.kind == "entry") data.put("title", data.getString("title") + "（冲突副本）")
                    // The derived UUID is also the creation event. Its clock is independent of merge order;
                    // subsequent edits/deletions of the copy dominate recreating the same conflict later.
                    pending.add(SyncRecord(loser.kind, id, mapOf(id to 1L), canonical(data)))
                }
            }
            out[next.key] = winner.copy(clock = clock)
        }
        // Independent devices may create the same label; preserve both identities without violating SQLite's unique name index.
        val used = mutableSetOf<String>()
        out.values.filter { it.kind == "tag" && it.payload != null }.sortedBy { it.id }.forEach { r ->
            val data = JSONObject(r.payload!!); val name = data.getString("name")
            if (!used.add(name.lowercase(java.util.Locale.ROOT))) {
                var n = 1
                var replacement: String
                do { replacement = name.take(55) + " · " + r.id.take(8) + if (n++ == 1) "" else "-$n" }
                while (!used.add(replacement.lowercase(java.util.Locale.ROOT)))
                out[r.key] = r.copy(payload = canonical(data.put("name", replacement)))
            }
        }
        return SyncDocument(out, purged, docs.firstOrNull()?.retained?.filterKeys { it !in purged })
    }
}
