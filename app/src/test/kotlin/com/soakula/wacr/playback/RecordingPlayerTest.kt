package com.soakula.wacr.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.RecordingFiles
import com.soakula.wacr.util.AppLog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class RecordingPlayerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var files: RecordingFiles
    private lateinit var store: CryptoStore
    private lateinit var log: AppLog
    private val messages = mutableListOf<String>()

    @Before fun prepareStore() {
        StreamingAeadConfig.register()
        val encryption = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        files = RecordingFiles(temporary.newFolder("audio"))
        store = CryptoStore(files, encryption)
        log = AppLog(temporary.newFolder("logs"), redact = true, mirror = messages::add, reportFailure = { throw it })
    }

    @Test fun exposesNativePlayPauseSeekAndReleaseWithoutStartingPlaybackOnCreation() {
        val player = createRecordingPlayer(RuntimeEnvironment.getApplication(), store, log)
        try {
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertFalse(player.playWhenReady)
            val uri = DecryptingDataSource.uri(UUID.randomUUID())
            player.setMediaItem(MediaItem.fromUri(uri))
            assertEquals(uri, player.currentMediaItem?.localConfiguration?.uri)
            player.play()
            assertTrue(player.playWhenReady)
            player.pause()
            assertFalse(player.playWhenReady)
            player.seekTo(5000)
            assertEquals(5000L, player.currentPosition)
        } finally {
            player.release()
        }
        assertTrue(player.isReleased)
    }

    @Test fun actualPlayerRejectsExternalUrisAndReportsRedactedErrors() {
        val player = createRecordingPlayer(RuntimeEnvironment.getApplication(), store, log)
        try {
            player.setMediaItem(MediaItem.fromUri("https://invalid.example/SyntheticContact.aac"))
            player.prepare()
            val error = awaitError(player)
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any {
                it.message == "Invalid private recording URI" ||
                    it.message == "Only canonical private audio recording URIs are supported"
            })
            assertTrue(messages.any { it.contains("playback") && it.contains("failed") })
            assertTrue(messages.none { it.contains("SyntheticContact") || it.contains("invalid.example") })
            assertTrue(temporary.root.resolve("audio").walkTopDown().none { it.isFile })
        } finally {
            player.release()
        }
    }

    @Test fun actualPlayerPropagatesAuthenticationFailureAndRetainsOnlyCiphertext() {
        val id = UUID.randomUUID()
        store.openWriter(id, "audio.aac.enc").use { writer ->
            val input = ByteBuffer.wrap(ByteArray(12_000))
            while (input.hasRemaining()) writer.write(input)
        }
        val ciphertext = files.file(id, "audio.aac.enc")
        val damaged = ciphertext.readBytes().also { it[100] = (it[100].toInt() xor 1).toByte() }
        ciphertext.writeBytes(damaged)
        val player = createRecordingPlayer(RuntimeEnvironment.getApplication(), store, log)
        try {
            player.setMediaItem(MediaItem.fromUri(DecryptingDataSource.uri(id)))
            player.prepare()
            val error = awaitError(player)
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { cause ->
                cause.stackTrace.any { it.className.startsWith("com.google.crypto.tink.streamingaead.") }
            })
            assertArrayEquals(damaged, ciphertext.readBytes())
            assertEquals(listOf(ciphertext), temporary.root.resolve("audio").walkTopDown().filter { it.isFile }.toList())
        } finally {
            player.release()
        }
    }

    private fun awaitError(player: ExoPlayer): PlaybackException {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            player.playerError?.let { return it }
            Thread.sleep(10)
        }
        error("Player did not report its source failure")
    }
}
