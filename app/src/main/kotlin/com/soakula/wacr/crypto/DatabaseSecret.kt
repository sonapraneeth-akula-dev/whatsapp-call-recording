package com.soakula.wacr.crypto

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom

private val Context.secrets by preferencesDataStore(name = "secrets")

class DatabaseSecret(
    private val store: DataStore<Preferences>,
    private val databaseFile: File,
    private val encryption: Aead
) {
    suspend fun load(): ByteArray {
        val values = store.edit { preferences ->
            if (preferences[KEY] == null) {
                if (listOf("", "-wal", "-shm", "-journal").any { File(databaseFile.path + it).exists() }) {
                    throw GeneralSecurityException("Database secret missing; existing database must not be replaced")
                }
                val password = ByteArray(32).also(SecureRandom()::nextBytes)
                try {
                    preferences[KEY] = encryption.encrypt(password, associatedData)
                } finally {
                    password.fill(0)
                }
            }
        }
        val password = encryption.decrypt(checkNotNull(values[KEY]), associatedData)
        if (password.size != 32) {
            password.fill(0)
            throw GeneralSecurityException("Invalid database passphrase length")
        }
        return password
    }

    companion object {
        internal val KEY = byteArrayPreferencesKey("database_passphrase_v1")
        private val associatedData = "v1|database|wacr.db".toByteArray(Charsets.UTF_8)

        fun open(context: Context, databaseFile: File, encryption: Aead): DatabaseSecret =
            DatabaseSecret(context.applicationContext.secrets, databaseFile, encryption)
    }
}
