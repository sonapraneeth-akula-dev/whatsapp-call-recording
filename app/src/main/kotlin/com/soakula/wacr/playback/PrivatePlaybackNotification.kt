package com.soakula.wacr.playback

import android.app.Notification
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.soakula.wacr.R

@UnstableApi
internal class PrivatePlaybackNotification(
    private val context: Context,
    private val delegate: MediaNotification.Provider = DefaultMediaNotificationProvider.Builder(context)
        .setNotificationId(2005).setChannelId(CHANNEL).setChannelName(R.string.playback_channel).build()
        .apply { setSmallIcon(android.R.drawable.ic_media_play) }
) : MediaNotification.Provider by delegate {
    override fun createNotification(
        mediaSession: MediaSession, mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory, onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification = shield(delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory) {
        onNotificationChangedCallback.onNotificationChanged(shield(it))
    })

    private fun shield(value: MediaNotification): MediaNotification {
        value.notification.visibility = Notification.VISIBILITY_PRIVATE
        value.notification.flags = value.notification.flags or Notification.FLAG_LOCAL_ONLY
        value.notification.publicVersion = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play).setLocalOnly(true)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.playback_private_title)).build()
        return value
    }

    companion object { const val CHANNEL = "private_playback" }
}
