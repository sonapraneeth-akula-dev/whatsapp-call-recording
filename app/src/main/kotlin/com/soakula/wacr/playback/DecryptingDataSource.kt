package com.soakula.wacr.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.soakula.wacr.crypto.CryptoStore
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID

@UnstableApi
class DecryptingDataSource(private val store: () -> CryptoStore) : BaseDataSource(false) {
    constructor(store: CryptoStore) : this({ store })
    private var input: SeekableByteChannel? = null
    private var openedUri: Uri? = null
    private var remaining = 0L
    private var failed = false

    override fun open(dataSpec: DataSpec): Long {
        check(input == null) { "Playback source is already open" }
        transferInitializing(dataSpec)
        val id = recordingId(dataSpec.uri)
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null) {
            throw IOException("Only local recording reads are supported")
        }
        val reader = try { store().openReader(id, "audio.aac.enc") }
        catch (error: GeneralSecurityException) { throw IOException("Private playback key unavailable", error) }
        catch (error: ProviderException) { throw IOException("Private playback provider unavailable", error) }
        var handedOff = false
        try {
            val size = reader.size()
            if (dataSpec.position > size) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            reader.position(dataSpec.position)
            val available = size - dataSpec.position
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
            openedUri = dataSpec.uri
            failed = false
            input = reader
            handedOff = true
        } finally {
            if (!handedOff) reader.close()
        }
        transferStarted(dataSpec)
        return if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining else dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "Invalid playback buffer range" }
        val reader = checkNotNull(input) { "Playback source is not open" }
        check(!failed) { "Playback source failed; close before retrying" }
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = try {
            reader.read(ByteBuffer.wrap(buffer, offset, minOf(remaining, length.toLong()).toInt()))
        } catch (error: IOException) {
            failed = true
            throw error
        }
        if (count <= 0) {
            failed = true
            throw EOFException("Encrypted recording ended before its authenticated size")
        }
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        val reader = input
        input = null
        openedUri = null
        remaining = 0
        failed = false
        try {
            reader?.close()
        } finally {
            if (reader != null) transferEnded()
        }
    }

    private fun recordingId(value: Uri): UUID {
        val path = value.pathSegments
        if (path.size != 2) throw IOException("Invalid private recording URI")
        val id = try {
            UUID.fromString(path.first())
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid recording identity", error)
        }
        if (value != uri(id)) throw IOException("Only canonical private audio recording URIs are supported")
        return id
    }

    companion object {
        fun uri(id: UUID): Uri = Uri.parse("wacr://recording/$id/audio.aac.enc")
    }
}
