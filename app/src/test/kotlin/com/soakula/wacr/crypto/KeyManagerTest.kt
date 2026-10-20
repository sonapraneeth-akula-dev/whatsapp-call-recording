package com.soakula.wacr.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class KeyManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun master(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    @Test fun encryptedKeysetsSurviveReopeningForBothPrimitives() {
        val directory = temporary.newFolder()
        val master = master()
        val first = KeyManager(directory, master)
        val cleartext = "private call content".toByteArray()
        val associatedData = "recording".toByteArray()
        val encrypted = ByteArrayOutputStream()
        first.streamingAead.newEncryptingStream(encrypted, associatedData).use { it.write(cleartext) }
        val metadata = first.aead.encrypt(cleartext, associatedData)
        val second = KeyManager(directory, master)
        second.streamingAead.newDecryptingStream(ByteArrayInputStream(encrypted.toByteArray()), associatedData).use {
            assertArrayEquals(cleartext, it.readBytes())
        }
        assertArrayEquals(cleartext, second.aead.decrypt(metadata, associatedData))
        assertEquals(setOf("streaming.keyset", "metadata.keyset"), directory.list()!!.toSet())
    }

    @Test fun wrongMasterAndCorruptionNeverRegenerateKeys() {
        val directory = temporary.newFolder()
        val master = master()
        KeyManager(directory, master).streamingAead
        val file = File(directory, "streaming.keyset")
        val original = file.readBytes()
        assertThrows(GeneralSecurityException::class.java) { KeyManager(directory, master()).streamingAead }
        assertArrayEquals(original, file.readBytes())
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(GeneralSecurityException::class.java) { KeyManager(directory, master).streamingAead }
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }

    @Test fun swappingKeysetKindsFailsAuthentication() {
        val directory = temporary.newFolder()
        val master = master()
        KeyManager(directory, master).aead
        File(directory, "metadata.keyset").copyTo(File(directory, "streaming.keyset"))
        assertThrows(GeneralSecurityException::class.java) { KeyManager(directory, master).streamingAead }
    }

    @Test fun missingKeysetsNeverReplaceKeysForExistingEncryptedArtifacts() {
        val directory = temporary.newFolder()
        val manager = KeyManager(directory, master(), setOf("streaming", "metadata"))
        assertThrows(GeneralSecurityException::class.java) { manager.streamingAead }
        assertThrows(GeneralSecurityException::class.java) { manager.aead }
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test fun concurrentManagersShareOnePersistentKeyset() {
        val directory = temporary.newFolder()
        val master = master()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val encrypted = executor.invokeAll(List(8) {
                Callable { KeyManager(directory, master).aead.encrypt("content".toByteArray(), byteArrayOf()) }
            }).map { it.get() }
            val reader = KeyManager(directory, master).aead
            encrypted.forEach { assertArrayEquals("content".toByteArray(), reader.decrypt(it, byteArrayOf())) }
        } finally {
            executor.shutdownNow()
        }
    }
}
