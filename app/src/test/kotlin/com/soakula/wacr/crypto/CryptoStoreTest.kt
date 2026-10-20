package com.soakula.wacr.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.soakula.wacr.data.RecordingFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.FileAlreadyExistsException
import java.util.UUID

class CryptoStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun encryption(): StreamingAead {
        StreamingAeadConfig.register()
        return KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    private fun write(store: CryptoStore, id: UUID, content: ByteArray) {
        store.openWriter(id, "audio.aac.enc").use { output ->
            val buffer = ByteBuffer.wrap(content)
            while (buffer.hasRemaining()) output.write(buffer)
        }
    }

    private fun read(store: CryptoStore, id: UUID, kind: String = "audio.aac.enc") =
        Channels.newInputStream(store.openReader(id, kind)).use { it.readBytes() }

    @Test fun roundTripAndSeekingNeverWritePlaintextFiles() {
        val root = temporary.newFolder()
        val files = RecordingFiles(root)
        val store = CryptoStore(files, encryption())
        val id = UUID.randomUUID()
        val cleartext = ByteArray(25000) { (it % 251).toByte() }
        write(store, id, cleartext)
        assertArrayEquals(cleartext, read(store, id))
        store.openReader(id, "audio.aac.enc").use { reader ->
            reader.position(12500)
            val slice = ByteBuffer.allocate(7000)
            while (slice.hasRemaining()) assertTrue(reader.read(slice) > 0)
            assertArrayEquals(cleartext.copyOfRange(12500, 19500), slice.array())
        }
        val diskFile = files.file(id, "audio.aac.enc")
        assertFalse(cleartext.contentEquals(diskFile.readBytes()))
        assertEquals(listOf(diskFile), root.walkTopDown().filter { it.isFile }.toList())
    }

    @Test fun tamperedTruncatedAndReboundFilesFailAuthentication() {
        val files = RecordingFiles(temporary.newFolder())
        val store = CryptoStore(files, encryption())
        val id = UUID.randomUUID()
        write(store, id, ByteArray(20000) { it.toByte() })
        val file = files.file(id, "audio.aac.enc")
        val original = file.readBytes()
        val corrupt = original.copyOf().also { it[it.lastIndex - 3] = (it[it.lastIndex - 3].toInt() xor 1).toByte() }
        file.writeBytes(corrupt)
        assertThrows(IOException::class.java) { read(store, id) }
        file.writeBytes(original.copyOf(original.size - 1))
        assertThrows(IOException::class.java) { read(store, id) }
        file.writeBytes(original)
        file.copyTo(files.file(id, "video.mp4.enc"))
        assertThrows(IOException::class.java) { read(store, id, "video.mp4.enc") }
        val other = UUID.randomUUID()
        files.file(other, "audio.aac.enc").parentFile!!.mkdirs()
        file.copyTo(files.file(other, "audio.aac.enc"))
        assertThrows(IOException::class.java) { read(store, other) }
    }

    @Test(timeout = 5000) fun existingRecordingsCannotBeOverwrittenAndEmptyAudioFailsExplicitly() {
        val files = RecordingFiles(temporary.newFolder())
        val store = CryptoStore(files, encryption())
        val id = UUID.randomUUID()
        write(store, id, byteArrayOf())
        val before = files.file(id, "audio.aac.enc").readBytes()
        assertThrows(IOException::class.java) { read(store, id) }
        assertThrows(FileAlreadyExistsException::class.java) { store.openWriter(id, "audio.aac.enc") }
        assertArrayEquals(before, files.file(id, "audio.aac.enc").readBytes())
    }
}
