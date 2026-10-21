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
data class UserPreferences(
    val appearance: Appearance = Appearance(),
    val onboardingDone: Boolean = false,
    val appLock: Boolean = false
)

class AppPreferences internal constructor(private val store: DataStore<Preferences>) {
    val values = store.data.map { values ->
        UserPreferences(
            Appearance(values[THEME]?.let(ThemeChoice::valueOf) ?: ThemeChoice.SYSTEM, values[DYNAMIC_COLOR] ?: true),
            values[ONBOARDING_DONE] ?: false,
            values[APP_LOCK] ?: false
        )
    }
    val appearance = values.map { it.appearance }
    val captureProfileJson = store.data.map { it[CAPTURE_PROFILE] }

    suspend fun setTheme(theme: ThemeChoice) { store.edit { it[THEME] = theme.name } }
    suspend fun setDynamicColor(enabled: Boolean) { store.edit { it[DYNAMIC_COLOR] = enabled } }
    suspend fun completeOnboarding() { store.edit { it[ONBOARDING_DONE] = true } }
    internal suspend fun setAppLock(enabled: Boolean) { store.edit { it[APP_LOCK] = enabled } }
    internal suspend fun cacheCaptureProfile(value: String) {
        require(value.length <= 16_384) { "Capture profile cache is too large" }
        store.edit { it[CAPTURE_PROFILE] = value }
    }

    companion object {
        internal val THEME = stringPreferencesKey("theme")
        internal val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        internal val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        internal val APP_LOCK = booleanPreferencesKey("app_lock")
        internal val CAPTURE_PROFILE = stringPreferencesKey("capture_profile_json")
        fun open(context: Context) = AppPreferences(context.applicationContext.settings)
    }
}
