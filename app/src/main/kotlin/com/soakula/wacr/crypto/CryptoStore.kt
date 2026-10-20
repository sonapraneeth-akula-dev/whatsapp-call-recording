package com.soakula.wacr.crypto

import com.google.crypto.tink.StreamingAead
import com.soakula.wacr.data.RecordingFiles
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.util.UUID

class CryptoStore(private val files: RecordingFiles, private val encryption: StreamingAead) {
    fun openWriter(recordingId: UUID, kind: String): WritableByteChannel =
        openWriter(recordingId, kind, kind)

    private fun openWriter(recordingId: UUID, fileName: String, kind: String): WritableByteChannel {
        val file = files.file(recordingId, fileName)
        val parent = file.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create recording directory")
        // ponytail: sync ciphertext at speech bitrates; batch on segment close if profiling requires it.
        val output = FileChannel.open(file.toPath(), CREATE_NEW, WRITE, SYNC)
        var handedOff = false
        try {
            val encrypted = encryption.newEncryptingChannel(output, associatedData(recordingId, kind))
            handedOff = true
            return encrypted
        } finally {
            if (!handedOff) {
                output.close()
                Files.deleteIfExists(file.toPath())
            }
        }
    }

    fun openReader(recordingId: UUID, kind: String): SeekableByteChannel =
        openReader(recordingId, kind, kind)

    private fun openReader(recordingId: UUID, fileName: String, kind: String): SeekableByteChannel {
        val input = FileChannel.open(files.file(recordingId, fileName).toPath(), READ)
        var handedOff = false
        try {
            val decrypted = encryption.newSeekableDecryptingChannel(input, associatedData(recordingId, kind))
            // Tink selects the key on first read; size() is unavailable before that.
            val probe = ByteBuffer.allocate(1)
            var previousPosition = input.position()
            while (decrypted.read(probe) == 0) {
                val position = input.position()
                if (position == previousPosition) throw IOException("Encrypted recording made no decryption progress")
                previousPosition = position
            }
            decrypted.position(0)
            handedOff = true
            return decrypted
        } finally {
            if (!handedOff) input.close()
        }
    }

    fun delete(recordingId: UUID, kind: String): Boolean =
        Files.deleteIfExists(files.file(recordingId, kind).toPath())

    internal fun openPendingAudioWriter(recordingId: UUID): WritableByteChannel =
        openWriter(recordingId, "audio.pending.enc", "audio.aac.enc")

    internal fun openPendingAudioReader(recordingId: UUID): SeekableByteChannel =
        openReader(recordingId, "audio.pending.enc", "audio.aac.enc")

    internal fun publishAudio(recordingId: UUID) {
        val destination = files.file(recordingId, "audio.aac.enc")
        if (destination.exists()) throw FileAlreadyExistsException(destination.path)
        Files.move(files.file(recordingId, "audio.pending.enc").toPath(), destination.toPath(), ATOMIC_MOVE)
    }

    private fun associatedData(recordingId: UUID, kind: String) =
        "v1|$recordingId|$kind".toByteArray(Charsets.UTF_8)
}
