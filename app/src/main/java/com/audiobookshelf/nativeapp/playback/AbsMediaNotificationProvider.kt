package com.audiobookshelf.nativeapp.playback

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.audiobookshelf.nativeapp.R
import com.google.common.collect.ImmutableList

/**
 * Media notification with five actions: -15s, previous, play/pause, next, +15s.
 *
 * The default media3 provider only renders previous/play/next and ignores the session's custom
 * layout, so the notification is built here instead.
 */
class AbsMediaNotificationProvider(
  private val context: Context,
  private val contentIntent: () -> PendingIntent?
) : MediaNotification.Provider {

  override fun createNotification(
    mediaSession: MediaSession,
    customLayout: ImmutableList<CommandButton>,
    actionFactory: MediaNotification.ActionFactory,
    onNotificationChangedCallback: MediaNotification.Provider.Callback
  ): MediaNotification {
    val player = mediaSession.player
    // playWhenReady stays true while PlaybackService retries a dropped stream; without the error
    // check the media card would keep showing a pause button with no audio behind it.
    val playing = player.playWhenReady && player.playerError == null

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(player.mediaMetadata.title ?: "正在播放")
      .setContentText(player.mediaMetadata.artist)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setSilent(true)
      .setOngoing(playing)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setStyle(
        MediaStyleNotificationHelper.MediaStyle(mediaSession)
          .setShowActionsInCompactView(0, 1, 2)
      )
      // Order matters: MIUI/HyperOS only render the first three actions
      .addAction(
        actionFactory.createMediaAction(
          mediaSession,
          IconCompat.createWithResource(context, R.drawable.ic_replay_15),
          "后退 15 秒",
          Player.COMMAND_SEEK_BACK
        )
      )
      .addAction(
        actionFactory.createMediaAction(
          mediaSession,
          IconCompat.createWithResource(context, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
          if (playing) "暂停" else "播放",
          Player.COMMAND_PLAY_PAUSE
        )
      )
      .addAction(
        actionFactory.createMediaAction(
          mediaSession,
          IconCompat.createWithResource(context, R.drawable.ic_forward_15),
          "前进 15 秒",
          Player.COMMAND_SEEK_FORWARD
        )
      )
      .addAction(
        actionFactory.createMediaAction(
          mediaSession,
          IconCompat.createWithResource(context, R.drawable.ic_prev),
          "上一章",
          Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        )
      )
      .addAction(
        actionFactory.createMediaAction(
          mediaSession,
          IconCompat.createWithResource(context, R.drawable.ic_next),
          "下一章",
          Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        )
      )

    contentIntent()?.let { builder.setContentIntent(it) }

    return MediaNotification(NOTIFICATION_ID, builder.build())
  }

  override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
    false

  // media3 1.11 asks the provider which channel the notification belongs to so it can keep the
  // channel in sync while the session is idle. It must match the channel PlaybackService creates.
  override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
    MediaNotification.Provider.NotificationChannelInfo(CHANNEL_ID, CHANNEL_NAME)

  companion object {
    const val CHANNEL_ID = "abs_playback"
    const val CHANNEL_NAME = "播放控制"
    const val NOTIFICATION_ID = 1001
  }
}
