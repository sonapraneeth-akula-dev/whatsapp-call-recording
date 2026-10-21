package com.soakula.wacr.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun cleanup() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }

    @Test fun choicesSurviveFileReopenAndIndependentEditsPreserveEachOther() = runBlocking {
        val file = temporary.newFile("settings.preferences_pb")
        val first = AppPreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        assertEquals(Appearance(), first.appearance.first())
        assertFalse(first.values.first().appLock)
        first.setAppLock(true)
        first.setTheme(ThemeChoice.DARK)
        first.setDynamicColor(false)
        first.completeOnboarding()
        assertEquals(Appearance(ThemeChoice.DARK, false), first.appearance.first())
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reopened = AppPreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        assertEquals(Appearance(ThemeChoice.DARK, false), reopened.appearance.first())
        assertTrue(reopened.values.first().onboardingDone)
        assertTrue(reopened.values.first().appLock)
        reopened.setTheme(ThemeChoice.LIGHT)
        assertEquals(Appearance(ThemeChoice.LIGHT, false), reopened.appearance.first())
        reopened.setTheme(ThemeChoice.SYSTEM)
        reopened.setDynamicColor(true)
        assertEquals(Appearance(), reopened.appearance.first())
        assertTrue(reopened.values.first().onboardingDone)
        reopened.setAppLock(false)
        assertFalse(reopened.values.first().appLock)
        assertEquals(Appearance(), reopened.appearance.first())
        assertTrue(reopened.values.first().onboardingDone)
    }

    @Test fun unknownStoredChoiceFailsExplicitlyWithoutResettingOriginalValue() = runBlocking {
        val file = temporary.newFile("unknown.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        store.edit { it[AppPreferences.THEME] = "UNSUPPORTED" }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { AppPreferences(store).appearance.first() }
        }
        assertEquals("UNSUPPORTED", store.data.first()[AppPreferences.THEME])
    }
}
