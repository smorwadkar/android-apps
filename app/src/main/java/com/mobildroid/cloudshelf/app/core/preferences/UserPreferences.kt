package com.mobildroid.cloudshelf.app.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK, VIBE }

private val Context.dataStore by preferencesDataStore(name = "cloudshelf_prefs")

/**
 * Non-secret app preferences (theme, future region override, etc.) backed by
 * Jetpack DataStore. Secret values like IAM keys live in EncryptedSharedPreferences
 * via [com.mobildroid.cloudshelf.app.auth.IamKeyStore].
 */
@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val themeMode: Flow<ThemeMode> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
