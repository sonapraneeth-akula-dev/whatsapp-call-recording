package com.soakula.wacr.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.RecordingFiles
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class DecryptingDataSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var files: RecordingFiles
    private lateinit var store: CryptoStore
    private val id = UUID.fromString("abcdef01-2345-4678-89ab-cdef01234567")
    private val plain = ByteArray(25_000) { (it % 251).toByte() }

    @Before fun prepareCiphertext() {
        StreamingAeadConfig.register()
        val encryption = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        files = RecordingFiles(temporary.newFolder())
        store = CryptoStore(files, encryption)
        store.openWriter(id, "audio.aac.enc").use { writer ->
            val buffer = ByteBuffer.wrap(plain)
            while (buffer.hasRemaining()) writer.write(buffer)
        }
    }

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(DecryptingDataSource.uri(id)).setPosition(position).setLength(length).build()

    private fun consume(source: DecryptingDataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(997)
        while (true) {
            val count = source.read(buffer, 3, buffer.size - 3)
            if (count == C.RESULT_END_OF_INPUT) break
            assertTrue(count > 0)
            output.write(buffer, 3, count)
        }
        return output.toByteArray()
    }

    @Test fun readsAndSeeksAcrossCiphertextBlocksWithoutWritingPlaintext() {
        val file = files.file(id, "audio.aac.enc")
        val before = file.readBytes()
        val source = DecryptingDataSource(store)
        try {
            assertEquals(plain.size.toLong(), source.open(spec()))
            assertEquals(DecryptingDataSource.uri(id), source.uri)
            assertArrayEquals(plain, consume(source))
            source.close()
            assertNull(source.uri)
            assertEquals(7000L, source.open(spec(12_500, 7000)))
            assertArrayEquals(plain.copyOfRange(12_500, 19_500), consume(source))
            source.close()
            assertEquals(500L, source.open(spec(100, 500)))
            assertArrayEquals(plain.copyOfRange(100, 600), consume(source))
        } finally {
            source.close()
        }
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(file), temporary.root.walkTopDown().filter { it.isFile }.toList())
    }

    @Test fun followsMedia3EndOfFileAndOversizedRangeContracts() {
        val source = DecryptingDataSource(store)
        try {
            assertEquals(10L, source.open(spec(plain.size - 3L, 10)))
            assertArrayEquals(plain.takeLast(3).toByteArray(), consume(source))
            source.close()
            assertEquals(0L, source.open(spec(plain.size.toLong())))
            assertEquals(0, source.read(ByteArray(1), 0, 0))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
            source.close()
            val error = assertThrows(IOException::class.java) { source.open(spec(plain.size + 1L)) }
            assertTrue(DataSourceException.isCausedByPositionOutOfRange(error))
            assertNull(source.uri)
            source.close()
            assertEquals(plain.size.toLong(), source.open(spec()))
        } finally {
            source.close()
        }
    }

    @Test fun rejectsExternalNoncanonicalAndUnfinalizedArtifactUris() {
        val source = DecryptingDataSource(store)
        for (uri in listOf(
            "https://example.invalid/$id/audio.aac.enc", "file:///private/$id/audio.aac.enc",
            "content://recording/$id/audio.aac.enc", "wacr://recording/not-a-uuid/audio.aac.enc",
            "wacr://recording/$id/video.mp4.enc", "wacr://recording/$id/seg_00001.enc",
            "wacr://recording/$id/audio.pending.enc", "wacr://recording/$id/audio.aac.enc?x=1",
            "wacr://recording/$id/audio.aac.enc#fragment", "wacr://recording/$id/../audio.aac.enc",
            "wacr://recording/${id.toString().uppercase()}/audio.aac.enc"
        )) {
            assertThrows(IOException::class.java) { source.open(DataSpec(Uri.parse(uri))) }
            assertNull(source.uri)
            source.close()
        }
        val post = DataSpec.Builder().setUri(DecryptingDataSource.uri(id))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(byteArrayOf(1)).build()
        assertThrows(IOException::class.java) { source.open(post) }
        source.close()
    }

    @Test fun reportsAuthenticationFailuresAndRequiresCloseBeforeRetryingReads() {
        val file = files.file(id, "audio.aac.enc")
        val original = file.readBytes()
        file.writeBytes(original.copyOf().also { it[it.lastIndex - 3] = (it[it.lastIndex - 3].toInt() xor 1).toByte() })
        val source = DecryptingDataSource(store)
        try {
            source.open(spec(20_000))
            assertThrows(IOException::class.java) { consume(source) }
            assertThrows(IllegalStateException::class.java) { source.read(ByteArray(1), 0, 1) }
        } finally {
            source.close()
        }
        file.writeBytes(original.copyOf().also { it[0] = 0 })
        assertThrows(IOException::class.java) { source.open(spec()) }
        assertNull(source.uri)
        source.close()
        val other = UUID.randomUUID()
        files.file(other, "audio.aac.enc").parentFile!!.mkdirs()
        files.file(other, "audio.aac.enc").writeBytes(original)
        assertThrows(IOException::class.java) { source.open(DataSpec(DecryptingDataSource.uri(other))) }
        source.close()
    }

    @Test fun boundsBuffersAndBalancesLocalTransferCallbacks() {
        val events = mutableListOf<String>()
        val source = DecryptingDataSource(store)
        source.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                assertFalse(isNetwork)
                events += "initializing"
            }
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { events += "start" }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                assertEquals(8, bytesTransferred)
                events += "bytes"
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { events += "end" }
        })
        source.open(spec(0, 8))
        assertThrows(IllegalStateException::class.java) { source.open(spec()) }
        assertThrows(IllegalArgumentException::class.java) { source.read(ByteArray(8), -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { source.read(ByteArray(8), 0, 9) }
        assertThrows(IllegalArgumentException::class.java) { source.read(ByteArray(8), 0, -1) }
        assertEquals(8, source.read(ByteArray(8), 0, 8))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        source.close()
        source.close()
        assertNull(source.uri)
        assertEquals(listOf("initializing", "start", "bytes", "end"), events)
        assertThrows(IllegalStateException::class.java) { source.read(ByteArray(1), 0, 1) }
    }
}
