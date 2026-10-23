package com.mobileailocal.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mobileailocal.domain.GenerationParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val theme: String = "system", // system / light / dark
    val language: String = "system", // system / ja / en / zh-CN / zh-TW
    val defaultModelId: String? = null,
    val params: GenerationParams = GenerationParams(),
    /** true なら従量制回線(モバイル通信など)ではダウンロードしない。 */
    val wifiOnly: Boolean = true,
    /** JavaScript 実行(拡張機能)を使うか。既定は無効。 */
    val jsEnabled: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val model = stringPreferencesKey("default_model")
        val temperature = floatPreferencesKey("temperature")
        val topP = floatPreferencesKey("top_p")
        val maxTokens = intPreferencesKey("max_tokens")
        val contextLength = intPreferencesKey("context_length")
        val threads = intPreferencesKey("threads")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val jsEnabled = booleanPreferencesKey("js_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = GenerationParams()
        AppSettings(
            theme = p[K.theme] ?: "system",
            language = p[K.language] ?: "system",
            defaultModelId = p[K.model],
            wifiOnly = p[K.wifiOnly] ?: true,
            jsEnabled = p[K.jsEnabled] ?: false,
            params = GenerationParams(
                temperature = p[K.temperature] ?: d.temperature,
                topP = p[K.topP] ?: d.topP,
                maxTokens = p[K.maxTokens] ?: d.maxTokens,
                contextLength = p[K.contextLength] ?: d.contextLength,
                threads = p[K.threads] ?: d.threads,
            ),
        )
    }

    suspend fun setTheme(v: String) = context.dataStore.edit { it[K.theme] = v }
    suspend fun setLanguage(v: String) = context.dataStore.edit { it[K.language] = v }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[K.wifiOnly] = v }
    suspend fun setJsEnabled(v: Boolean) = context.dataStore.edit { it[K.jsEnabled] = v }
    suspend fun setDefaultModel(v: String?) = context.dataStore.edit { p -> set(p, v) }
    suspend fun setParams(v: GenerationParams) = context.dataStore.edit {
        it[K.temperature] = v.temperature
        it[K.topP] = v.topP
        it[K.maxTokens] = v.maxTokens
        it[K.contextLength] = v.contextLength
        it[K.threads] = v.threads
    }

    private fun set(p: MutablePreferences, v: String?) {
        if (v == null) p.remove(K.model) else p[K.model] = v
    }
}
