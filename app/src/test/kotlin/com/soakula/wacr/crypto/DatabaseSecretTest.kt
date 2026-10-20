package com.soakula.wacr.crypto

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException

class DatabaseSecretTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun encryption(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    @Test fun persistsOnlyCiphertextAndReopensIdenticallyAcrossConcurrentLoads() = runBlocking {
        val file = File(temporary.newFolder(), "secrets.preferences_pb")
        val database = File(file.parentFile, "wacr.db")
        val encryption = encryption()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val secret = DatabaseSecret(store, database, encryption)
        val values = coroutineScope { List(8) { async(Dispatchers.IO) { secret.load() } }.awaitAll() }
        values.forEach { assertArrayEquals(values.first(), it) }
        assertEquals(32, values.first().size)
        val ciphertext = file.readBytes()
        assertFalse(ciphertext.toList().windowed(32).any { it == values.first().toList() })
        scope.coroutineContext.job.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            assertArrayEquals(values.first(), DatabaseSecret(store, database, encryption).load())
            assertThrows(GeneralSecurityException::class.java) {
                runBlocking { DatabaseSecret(store, database, encryption()).load() }
            }
            assertArrayEquals(ciphertext, file.readBytes())
        } finally {
            values.forEach { it.fill(0) }
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test fun missingAndCorruptSecretsNeverResetExistingDatabase() = runBlocking {
        val file = File(temporary.newFolder(), "secrets.preferences_pb")
        val database = File(file.parentFile, "wacr.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val secret = DatabaseSecret(store, database, encryption())
            File(database.path + "-wal").writeBytes(byteArrayOf(1, 2, 3))
            assertThrows(GeneralSecurityException::class.java) { runBlocking { secret.load() } }
            assertFalse(database.exists())
            store.edit { it[DatabaseSecret.KEY] = byteArrayOf(1, 2, 3) }
            val before = file.readBytes()
            assertThrows(GeneralSecurityException::class.java) { runBlocking { secret.load() } }
            assertArrayEquals(before, file.readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), File(database.path + "-wal").readBytes())
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
