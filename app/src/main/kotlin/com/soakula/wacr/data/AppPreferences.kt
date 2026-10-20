package com.soakula.wacr.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore(name = "settings")

enum class ThemeChoice { SYSTEM, LIGHT, DARK }
data class Appearance(val theme: ThemeChoice = ThemeChoice.SYSTEM, val dynamicColor: Boolean = true)

class AppPreferences internal constructor(private val store: DataStore<Preferences>) {
    val appearance = store.data.map { values ->
        Appearance(values[THEME]?.let(ThemeChoice::valueOf) ?: ThemeChoice.SYSTEM, values[DYNAMIC_COLOR] ?: true)
    }

    suspend fun setTheme(theme: ThemeChoice) { store.edit { it[THEME] = theme.name } }
    suspend fun setDynamicColor(enabled: Boolean) { store.edit { it[DYNAMIC_COLOR] = enabled } }

    companion object {
        internal val THEME = stringPreferencesKey("theme")
        internal val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        fun open(context: Context) = AppPreferences(context.applicationContext.settings)
    }
}
