package com.collection.sync

import android.content.Context
import android.util.AtomicFile
import com.collection.data.PreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

data class SyncConfig(
    val endpoint: String = "https://dav.jianguoyun.com/dav/",
    val username: String = "",
    val password: String = "",
    val automatic: Boolean = false,
    val wifiOnly: Boolean = true,
) {
    val configured get() = username.isNotBlank() && password.isNotEmpty()
    fun validated(): SyncConfig {
        val url = endpoint.trim().toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "请填写有效的 HTTPS WebDAV 地址" }
        require(username.isNotBlank() && username.none { it == '\n' || it == '\r' || it == ':' } && password.isNotEmpty()) { "请填写账号和应用密码" }
        return copy(endpoint = url.toString().trimEnd('/') + "/", username = username.trim())
    }
    internal val identity get() = digest((endpoint.trimEnd('/') + "\n" + username.trim()).toByteArray())
    override fun toString() = "SyncConfig(endpoint=$endpoint, username=$username, password=<redacted>, automatic=$automatic, wifiOnly=$wifiOnly)"
}

data class SyncStatus(val running: Boolean = false, val message: String = "尚未同步", val lastSuccess: Long = 0)

internal object SyncIdentity {
    private val lock = Any()
    fun actor(context: Context): String = synchronized(lock) {
        val file = File(context.noBackupFilesDir, "collection-sync-device")
        val saved = runCatching { file.readText().trim() }.getOrNull()?.takeIf(ACTOR::matches)
        saved ?: UUID.randomUUID().toString().also { value ->
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(value.toByteArray()); atomic.finishWrite(stream) }
            catch (error: Throwable) { atomic.failWrite(stream); throw error }
        }
    }
}

internal object SyncSignals {
    val local = MutableStateFlow(0L)
    val remote = MutableStateFlow(0L)
    val status = MutableStateFlow(SyncStatus())
    fun localChanged() { local.update { it + 1 } }
    fun remoteChanged() { remote.update { it + 1 } }
}

internal class SyncSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("webdav_sync", Context.MODE_PRIVATE)
    private val crypto = PreferencesStore(app)
    fun read(): SyncConfig = synchronized(LOCK) {
        SyncConfig(endpoint = prefs.getString("endpoint", null) ?: SyncConfig().endpoint,
            username = prefs.getString("username", "").orEmpty(),
            password = runCatching { crypto.decrypt(prefs.getString("credential", null)) }.getOrDefault(""),
            automatic = prefs.getBoolean("automatic", false), wifiOnly = prefs.getBoolean("wifi_only", true))
    }
    fun write(value: SyncConfig) = synchronized(LOCK) {
        val config = value.validated()
        val previous = read()
        val changedAccount = previous.identity != config.identity
        check(prefs.edit().putString("endpoint", config.endpoint).putString("username", config.username)
            .putString("credential", crypto.encrypt(config.password)).putBoolean("automatic", config.automatic)
            .putBoolean("wifi_only", config.wifiOnly).apply { if (changedAccount) remove("success").remove("message") }
            .commit()) { "同步设置保存失败" }
    }
    fun status(): SyncStatus = synchronized(LOCK) {
        SyncStatus(message = prefs.getString("message", "尚未同步").orEmpty(), lastSuccess = prefs.getLong("success", 0))
    }
    fun status(value: SyncStatus) = synchronized(LOCK) {
        check(prefs.edit().putString("message", value.message).putLong("success", value.lastSuccess).commit())
        SyncSignals.status.value = value
    }
    /** Persist a rolling request window, including retries; other WebDAV apps may consume additional quota. */
    fun reserveRequest(identity: String, now: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        val key = "requests_$identity"
        val old = JSONArray(prefs.getString(key, "[]"))
        val times = (0 until old.length()).map { old.getLong(it) }.filter { it > now - 30 * 60_000L }
        if (times.size >= 500) throw SyncFailure("请求较多，已暂停同步，稍后会自动重试", retryable = true)
        check(prefs.edit().putString(key, JSONArray(times + now).toString()).commit())
    }
    fun uploaded(identity: String): Set<String> = synchronized(LOCK) {
        prefs.getStringSet("uploaded_$identity", emptySet()).orEmpty().toSet()
    }
    fun markUploaded(identity: String, hash: String) = synchronized(LOCK) {
        check(prefs.edit().putStringSet("uploaded_$identity", uploaded(identity) + hash).commit())
    }
    private companion object { val LOCK = Any() }
}

internal class SyncFailure(message: String, val retryable: Boolean = false, cause: Throwable? = null) : IOException(message, cause)
