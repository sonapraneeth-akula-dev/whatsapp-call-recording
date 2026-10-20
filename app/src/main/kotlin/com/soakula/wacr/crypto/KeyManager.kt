package com.soakula.wacr.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.Parameters
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeystore
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.GeneralSecurityException

class KeyManager(
    private val directory: File,
    private val masterKey: Aead,
    private val protectedKeysets: Set<String> = emptySet()
) {
    init {
        AeadConfig.register()
        StreamingAeadConfig.register()
    }

    val streamingAead: StreamingAead by lazy {
        load("streaming", PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }
    val aead: Aead by lazy {
        load("metadata", PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun load(name: String, parameters: Parameters): KeysetHandle = synchronized(lock) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create keyset directory")
        val file = File(directory, "$name.keyset")
        require(file.canonicalFile == file.absoluteFile) { "Keyset paths must not contain symlinks" }
        val associatedData = "v1|keyset|$name".toByteArray(Charsets.UTF_8)
        if (file.exists()) {
            TinkProtoKeysetFormat.parseEncryptedKeyset(file.readBytes(), masterKey, associatedData)
        } else {
            if (name in protectedKeysets) {
                throw GeneralSecurityException("Keyset missing; existing encrypted data must not be reset")
            }
            val handle = KeysetHandle.generateNew(parameters)
            val encrypted = TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, masterKey, associatedData)
            val temporary = File.createTempFile("keyset-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(encrypted)
                    output.fd.sync()
                }
                Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary.toPath())
            }
            handle
        }
    }

    companion object {
        private const val ALIAS = "wacr-master-v1"
        // ponytail: one process-wide keyset lock; app components run in the default process.
        private val lock = Any()

        fun open(context: Context): KeyManager = synchronized(lock) {
            val directory = File(context.noBackupFilesDir.canonicalFile, "keys")
            val protected = buildSet {
                if (File(context.filesDir, "rec").exists()) add("streaming")
                val database = context.getDatabasePath("wacr.db")
                if (listOf("", "-wal", "-shm", "-journal").any { File(database.path + it).exists() } ||
                    File(File(context.filesDir, "datastore"), "secrets.preferences_pb").exists()) {
                    add("metadata")
                }
            }
            if (!AndroidKeystore.hasKey(ALIAS)) {
                if (protected.isNotEmpty() || File(directory, "streaming.keyset").exists() ||
                    File(directory, "metadata.keyset").exists()) {
                    throw GeneralSecurityException("Keystore key missing; existing keysets must not be replaced")
                }
                AndroidKeystore.generateNewAes256GcmKey(ALIAS)
            }
            KeyManager(directory, AndroidKeystore.getAead(ALIAS), protected)
        }
    }
}
