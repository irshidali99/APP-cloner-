package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.SettingsData
import com.example.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_cloner_settings")

class PreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_SUFFIX = stringPreferencesKey("default_suffix")
        val AUTO_NUMBER = booleanPreferencesKey("auto_number")
        val CONFIRM_DELETE = booleanPreferencesKey("confirm_delete")
        val MAX_CLONES = intPreferencesKey("max_clones")
    }

    val settingsData: Flow<SettingsData> = context.dataStore.data.map { prefs ->
        val themeString = prefs[PreferencesKeys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        val themeMode = try {
            ThemeMode.valueOf(themeString)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }

        SettingsData(
            themeMode = themeMode,
            defaultSuffix = prefs[PreferencesKeys.DEFAULT_SUFFIX] ?: "Clone",
            autoNumber = prefs[PreferencesKeys.AUTO_NUMBER] ?: true,
            confirmDelete = prefs[PreferencesKeys.CONFIRM_DELETE] ?: true,
            maxClones = prefs[PreferencesKeys.MAX_CLONES] ?: 0
        )
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.THEME_MODE] = themeMode.name
        }
    }

    suspend fun setDefaultSuffix(suffix: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.DEFAULT_SUFFIX] = suffix
        }
    }

    suspend fun setAutoNumber(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.AUTO_NUMBER] = enabled
        }
    }

    suspend fun setMaxClones(max: Int) {
        context.dataStore.edit { prefs -> prefs[PreferencesKeys.MAX_CLONES] = max }
    }

    suspend fun setConfirmDelete(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.CONFIRM_DELETE] = enabled
        }
    }
}
