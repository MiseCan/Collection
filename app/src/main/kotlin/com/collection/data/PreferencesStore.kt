package com.collection.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.collection.domain.AiConfig
import com.collection.domain.AppearanceSettings
import com.collection.domain.HomePreferences
import com.collection.domain.PageTransitionStyle
import com.collection.domain.SortOrder
import com.collection.domain.ThemeMode
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Settings contain no plaintext credentials; the AES key cannot be exported from Android Keystore. */
class PreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("collfresh_settings", Context.MODE_PRIVATE)

    fun readAppearance(): AppearanceSettings = synchronized(PREFERENCES_LOCK) {
        // This redesign opts existing installs into wallpaper colors once; later user choices persist.
        if (!prefs.getBoolean("appearance_dynamic_design_v2", false)) {
            check(prefs.edit().putBoolean("appearance_dynamic_design_v2", true)
                .putBoolean("appearance_dynamic_color", true).commit()) { "动态配色设置保存失败" }
        }
        val default = AppearanceSettings()
        AppearanceSettings(
            themeMode = runCatching {
                ThemeMode.valueOf(prefs.getString("appearance_theme_mode", null).orEmpty())
            }.getOrDefault(default.themeMode),
            seedColor = prefs.getLong("appearance_seed_color", default.seedColor),
            paletteStyle = prefs.getString("appearance_palette_style", null) ?: default.paletteStyle,
            colorSpec = prefs.getString("appearance_color_spec", null) ?: default.colorSpec,
            dynamicColor = prefs.getBoolean("appearance_dynamic_color", default.dynamicColor),
            dynamicIcon = prefs.getBoolean("appearance_dynamic_icon", default.dynamicIcon),
            predictiveBack = prefs.getBoolean("appearance_predictive_back", default.predictiveBack),
            edgeAwareBack = prefs.getBoolean("appearance_edge_aware_back", default.edgeAwareBack),
            pageTransition = runCatching {
                PageTransitionStyle.valueOf(prefs.getString("appearance_page_transition", null).orEmpty())
            }.getOrDefault(default.pageTransition),
            uiScale = prefs.getFloat("appearance_ui_scale", default.uiScale).coerceIn(0.85f, 1.15f),
        )
    }

    fun writeAppearance(value: AppearanceSettings) = synchronized(PREFERENCES_LOCK) {
        check(
            prefs.edit()
                .putString("appearance_theme_mode", value.themeMode.name)
                .putLong("appearance_seed_color", value.seedColor)
                .putString("appearance_palette_style", value.paletteStyle)
                .putString("appearance_color_spec", value.colorSpec)
                .putBoolean("appearance_dynamic_color", value.dynamicColor)
                .putBoolean("appearance_dynamic_icon", value.dynamicIcon)
                .putBoolean("appearance_dynamic_design_v2", true)
                .putBoolean("appearance_predictive_back", value.predictiveBack)
                .putBoolean("appearance_edge_aware_back", value.edgeAwareBack)
                .putString("appearance_page_transition", value.pageTransition.name)
                .putFloat("appearance_ui_scale", value.uiScale.coerceIn(0.85f, 1.15f))
                .commit()
        ) { "主题设置保存失败，请重试" }
    }

    fun readHome(): HomePreferences = synchronized(PREFERENCES_LOCK) { HomePreferences(
        cards = prefs.getBoolean("home_cards", true),
        sort = runCatching { SortOrder.valueOf(prefs.getString("home_sort", null).orEmpty()) }
            .getOrDefault(SortOrder.UPDATED_DESC),
        tagIds = prefs.getStringSet("home_tags", emptySet()).orEmpty().toSet(),
    ) }

    fun writeHome(home: HomePreferences) = synchronized(PREFERENCES_LOCK) {
        check(prefs.edit().putBoolean("home_cards", home.cards)
            .putString("home_sort", home.sort.name)
            .putStringSet("home_tags", home.tagIds.toSet()).commit()) { "首页设置保存失败，请重试" }
    }

    internal fun removeTagFilter(id: String) = synchronized(PREFERENCES_LOCK) {
        readHome().let { writeHome(it.copy(tagIds = it.tagIds - id)) }
    }

    fun readAi(): AiConfig = synchronized(PREFERENCES_LOCK) { AiConfig(
        // A restored preference file cannot decrypt on another device. Ask for the key again.
        apiKey = runCatching { decrypt(prefs.getString("ai_key_encrypted", null)) }.getOrDefault(""),
        endpoint = prefs.getString("ai_endpoint", null) ?: AiConfig().endpoint,
        model = prefs.getString("ai_model", null) ?: AiConfig().model,
        proxyMode = prefs.getBoolean("ai_proxy", false),
    ) }

    fun writeAi(config: AiConfig) = synchronized(PREFERENCES_LOCK) {
        val endpoint = config.endpoint.trim()
        val uri = runCatching { URI(endpoint) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null) { "AI 服务地址必须是有效的 HTTPS 地址" }
        require(config.model.trim().isNotEmpty()) { "请填写模型名称" }
        val encrypted = config.apiKey.trim().takeIf { it.isNotEmpty() }?.let(::encrypt)
        check(prefs.edit().putString("ai_key_encrypted", encrypted)
            .putString("ai_endpoint", endpoint).putString("ai_model", config.model.trim())
            .putBoolean("ai_proxy", config.proxyMode).commit()) { "AI 设置保存失败，请重试" }
    }

    private fun secretKey(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    internal fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    internal fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val pieces = value.split(':', limit = 2)
        require(pieces.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_ALIAS = "collfresh.ai.credentials.v1"
        val KEY_LOCK = Any()
        val PREFERENCES_LOCK = Any()
    }
}
