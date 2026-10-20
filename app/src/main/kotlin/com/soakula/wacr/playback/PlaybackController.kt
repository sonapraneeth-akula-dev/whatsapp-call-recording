package com.soakula.wacr.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

@UnstableApi
suspend fun createPlaybackController(context: Context): Player = suspendCancellableCoroutine { continuation ->
    val future = MediaController.Builder(context.applicationContext,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)))
        .buildAsync()
    continuation.invokeOnCancellation { future.cancel(true) }
    future.addListener({
        try {
            val controller = future.get()
            val player = object : ForwardingPlayer(controller) {
                override fun getPlayerError(): PlaybackException? = if (!controller.isConnected)
                    PlaybackException("Private playback disconnected", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
                else controller.playerError
            }
            continuation.resume(player) { _, value, _ ->
                ContextCompat.getMainExecutor(context).execute { value.release() }
            }
        } catch (error: CancellationException) {
            continuation.cancel(error)
        } catch (error: ExecutionException) {
            if (continuation.isActive)
                continuation.resumeWithException(IOException("Private playback connection failed", error.cause))
        }
    }, ContextCompat.getMainExecutor(context))
}
