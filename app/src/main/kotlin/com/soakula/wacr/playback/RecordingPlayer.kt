package com.soakula.wacr.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.util.AppLog

@UnstableApi
fun createRecordingPlayer(context: Context, store: CryptoStore, log: AppLog): ExoPlayer {
    val sources = ProgressiveMediaSource.Factory(
        DataSource.Factory { DecryptingDataSource(store) },
        DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    )
    return ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(sources)
        .setAudioAttributes(
            AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { player ->
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    log.log("playback", "failed", mapOf("code" to error.errorCodeName))
                }
            })
        }
}
