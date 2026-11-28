package com.securetalk.core.database.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "securetalk_settings")

/** App settings via Jetpack DataStore (Preferences). */
class SettingsRepository(private val context: Context) {

    data class Settings(
        val displayName: String = "",
        val apiBaseUrl: String = DEFAULT_API_BASE_URL,
        val defaultSpeaker: Boolean = false,
        val diagnosticsEnabled: Boolean = true,
        val onboarded: Boolean = false,
    ) {
        companion object {
            /** Server origin; REST/WS paths are appended by the transports. */
            const val DEFAULT_API_BASE_URL = "https://wakitaki.sadsirco.ir/"
        }
    }

    val settings: Flow<Settings> = context.settingsDataStore.data.map { prefs ->
        Settings(
            displayName = prefs[KEY_DISPLAY_NAME] ?: "",
            apiBaseUrl = prefs[KEY_API_BASE_URL] ?: Settings.DEFAULT_API_BASE_URL,
            defaultSpeaker = prefs[KEY_DEFAULT_SPEAKER] ?: false,
            diagnosticsEnabled = prefs[KEY_DIAGNOSTICS] ?: true,
            onboarded = prefs[KEY_ONBOARDED] ?: false,
        )
    }

    suspend fun setDisplayName(name: String) = context.settingsDataStore.edit { it[KEY_DISPLAY_NAME] = name }
    suspend fun setApiBaseUrl(url: String) = context.settingsDataStore.edit { it[KEY_API_BASE_URL] = url }
    suspend fun setDefaultSpeaker(value: Boolean) = context.settingsDataStore.edit { it[KEY_DEFAULT_SPEAKER] = value }
    suspend fun setDiagnosticsEnabled(value: Boolean) = context.settingsDataStore.edit { it[KEY_DIAGNOSTICS] = value }
    suspend fun setOnboarded(value: Boolean) = context.settingsDataStore.edit { it[KEY_ONBOARDED] = value }

    suspend fun clear() = context.settingsDataStore.edit { it.clear() }

    private companion object {
        val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")
        val KEY_DEFAULT_SPEAKER = booleanPreferencesKey("default_speaker")
        val KEY_DIAGNOSTICS = booleanPreferencesKey("diagnostics_enabled")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
    }
}
