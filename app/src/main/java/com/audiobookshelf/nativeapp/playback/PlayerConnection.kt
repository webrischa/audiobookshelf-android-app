package com.audiobookshelf.nativeapp.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import com.audiobookshelf.nativeapp.util.AppLog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.audiobookshelf.nativeapp.data.ChapterDto
import com.audiobookshelf.nativeapp.data.PlaybackSessionDto
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the ui needs about playback except the playhead.
 *
 * Position is deliberately not part of this type: it changes twice per second, and every consumer
 * of a single state object recomposes on every field of it. The player page and the mini bar used
 * to read the whole snapshot at their top level, so a 2 Hz position tick re-ran the entire page —
 * cover, description, chapter list, control row — 120 times a minute. Position now lives in
 * [PlaybackPosition] and is only read by the composables that actually draw it.
 */
data class PlaybackUiState(
  val isConnected: Boolean = false,
  val hasMedia: Boolean = false,
  val isPlaying: Boolean = false,
  val playWhenReady: Boolean = false,
  val playbackState: Int = Player.STATE_IDLE,
  /** Non-null `Player.playerError`: the stream failed and the service is waiting to retry. */
  val hasPlaybackError: Boolean = false,
  val durationMs: Long = 0,
  val speed: Float = 1f,
  val mediaId: String? = null,
  /**
   * Current item title. With chapters available this is the chapter being played, because the
   * system media card reads both of its lines from the current item's metadata.
   */
  val title: String = "",
  /** Second line of the media card: "book · author". */
  val artist: String = "",
  /** Book the player is on, independent of the chapter title above. */
  val bookTitle: String = "",
  val bookAuthor: String = ""
) {
  /**
   * True while playing *or* about to play: ExoPlayer reports isPlaying=false during buffering,
   * which made the play button flip back to "play" for a moment after starting.
   *
   * A failed stream keeps `playWhenReady=true` for the whole retry window (the service's
   * `scheduleErrorRetry` needs it), so the "about to play" branch would otherwise show a pause
   * button indefinitely while nothing is audible. An error is never "starting": the button has to
   * offer `play()` so the user can force a retry, and the icon must match the silence.
   */
  val isPlayingOrStarting: Boolean
    get() = !hasPlaybackError &&
      (isPlaying || (playWhenReady && playbackState != Player.STATE_ENDED))
}

/** The playhead, split from [PlaybackUiState] because it ticks while nothing else changes. */
data class PlaybackPosition(
  val positionMs: Long = 0,
  val trackStartMs: Long = 0,
  val bufferedMs: Long = 0
) {
  /** Position inside the whole book: the tracks are separate media items. */
  val absolutePositionMs: Long get() = (trackStartMs + positionMs).coerceAtLeast(0)
}

/**
 * Single connection to the [PlaybackService] session. Owned by the activity level view model so
 * navigation between screens never tears the player down.
 */
class PlayerConnection(context: Context) {
  private val appContext = context.applicationContext
  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var controller: MediaController? = null
  private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
  private var reconnectAttempts = 0

  /**
   * Set by [release], when the owning view model is cleared for good. A queued [onDisconnected]
   * reconnect used to outlive the view model and build a fresh MediaController for a dead screen.
   */
  @Volatile
  private var released = false

  /**
   * Fired whenever playWhenReady changes. The reason (from the session player) tells user
   * pauses apart from interruptions: USER_REQUEST / REMOTE = intentional, AUDIO_FOCUS_LOSS =
   * call, alarm or another app, AUDIO_BECOMING_NOISY = headphones unplugged.
   */
  var playWhenReadyListener: ((playWhenReady: Boolean, reason: Int) -> Unit)? = null

  private val _state = MutableStateFlow(PlaybackUiState())
  val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

  /**
   * The playhead, updated on every player event (2 Hz while the ui polls).
   *
   * Separate from [state] so that polling the position does not invalidate every reader of the
   * playback snapshot. `MutableStateFlow` drops equal values, so [state] only emits when one of
   * its own fields actually changed.
   */
  private val _position = MutableStateFlow(PlaybackPosition())
  val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

  /** Absolute book position right now, for callers that need one sample rather than a stream. */
  fun currentAbsolutePositionMs(): Long = _position.value.absolutePositionMs

  // Kept so the playlist can be rebuilt against another server address without losing the position
  private var lastSession: PlaybackSessionDto? = null
  private var lastTrackUrlBuilder: ((sessionId: String, trackIndex: Int) -> String)? = null
  private var lastCoverUrl: String = ""
  private var lastBookDurationMs: Long = 0L
  private var lastChapters: List<ChapterDto> = emptyList()

  private val listener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
      publish(player)
    }
  }

  private val sessionListener = object : MediaController.Listener {
    override fun onDisconnected(deadController: MediaController) {
      if (released) return
      // The service was destroyed and came back with a fresh session; without reconnecting the
      // ui would keep showing "no media" while audio actually plays in the new session.
      AppLog.w("PlayerConnection", "media controller disconnected, reconnecting")
      controller = null
      controllerFuture = null
      reconnectAttempts++
      if (reconnectAttempts > 5) {
        AppLog.e("PlayerConnection", "reconnect gave up after $reconnectAttempts attempts")
        return
      }
      // The callback can still be in flight when release() runs; check again when it fires.
      mainHandler.postDelayed({ if (!released) connect() }, 1_000L * reconnectAttempts)
    }
  }

  fun connect() {
    // An explicit connect is a deliberate new owner, so it lifts a previous release() and keeps
    // normal reconnects working. A queued reconnect never calls this once released is set.
    released = false
    if (controller != null || controllerFuture != null) return
    val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
    val future = MediaController.Builder(appContext, token)
      .setListener(sessionListener)
      .buildAsync()
    controllerFuture = future
    future.addListener({
      if (released) return@addListener
      try {
        val connected = future.get()
        if (released) {
          // The future resolved after release(): cancelling it cannot free the controller, so
          // release it here instead of handing it to a view model that is already gone.
          connected.release()
          return@addListener
        }
        controller = connected
        reconnectAttempts = 0
        connected.addListener(listener)
        connected.addListener(object : Player.Listener {
          override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playWhenReadyListener?.invoke(playWhenReady, reason)
          }
        })
        publish(connected)
      } catch (error: Exception) {
        AppLog.e("PlayerConnection", "Failed to connect media controller", error)
      }
    }, ContextCompat.getMainExecutor(appContext))
  }

  fun release() {
    // Terminal: discard queued reconnects before tearing the controller down, otherwise the
    // onDisconnected callback rebuilds one for a view model that has already been cleared.
    released = true
    mainHandler.removeCallbacksAndMessages(null)
    controller?.removeListener(listener)
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controller = null
    controllerFuture = null
  }

  fun play() { controller?.play() }

  fun pause() { controller?.pause() }

  /** Stops playback and drops the loaded media (used on logout). */
  fun clear() {
    val current = controller ?: return
    current.pause()
    current.clearMediaItems()
  }

  /** Re-reads the controller state; used by a UI ticker because the player emits no per-second event. */
  fun refresh() {
    controller?.let { publish(it) }
  }

  fun togglePlayPause() {
    val current = controller ?: return
    if (current.isPlaying) current.pause() else current.play()
  }

  fun seekToAbsolute(positionMs: Long) {
    val current = controller ?: return
    seekToBookPosition(current, positionMs)
  }

  /**
   * Jumps to a book position and starts playing it, regardless of the previous play/pause state.
   *
   * Selecting a chapter means "listen from here", not "move the playhead and wait". A plain
   * [seekToAbsolute] leaves `playWhenReady` untouched, so a chapter picked while paused changed
   * the position but never produced any audio. Seek first, then play: the reverse order would let
   * the old position be audible for the frames between the two commands.
   */
  fun seekToAbsoluteAndPlay(positionMs: Long) {
    val current = controller ?: return
    seekToBookPosition(current, positionMs)
    current.play()
  }

  fun seekBy(deltaMs: Long) {
    seekToAbsolute(_position.value.absolutePositionMs + deltaMs)
  }

  fun setSpeed(speed: Float) {
    controller?.setPlaybackSpeed(speed)
  }

  fun loadSession(
    session: PlaybackSessionDto,
    startTimeSeconds: Double,
    coverUrl: String,
    bookDurationMs: Long,
    chapters: List<ChapterDto>,
    trackUrlBuilder: (sessionId: String, trackIndex: Int) -> String,
    playWhenReady: Boolean = true
  ) {
    lastSession = session
    lastTrackUrlBuilder = trackUrlBuilder
    lastCoverUrl = coverUrl
    lastBookDurationMs = bookDurationMs
    lastChapters = chapters
    applySession(session, (startTimeSeconds * 1000).toLong(), coverUrl, bookDurationMs, chapters, trackUrlBuilder, playWhenReady = playWhenReady)
  }

  /**
   * Rebuilds the playlist with fresh track urls (the builder reads the currently active server
   * address) and resumes at [absolutePositionMs]. Used when the active address changes between the
   * LAN and the remote one while media is loaded, so playback continues on the new address.
   *
   * Refuses to run while paused. Rebuilding resets the player to STATE_IDLE, and Media3 treats
   * "idle and not playing" as nothing worth keeping the service alive for: it stops the session and
   * removes the media notification. A paused player also has no stream to rescue — the next play
   * builds its urls from the new address anyway.
   *
   * @return false when there is nothing to reload
   */
  fun reloadAt(absolutePositionMs: Long): Boolean {
    val session = lastSession ?: return false
    val builder = lastTrackUrlBuilder ?: return false
    val current = controller ?: return false
    if (!current.playWhenReady) return false
    applySession(session, absolutePositionMs, lastCoverUrl, lastBookDurationMs, lastChapters, builder, playWhenReady = true)
    return true
  }

  /**
   * Whether the loaded stream urls were built for [serverUrl], or null when nothing is loaded.
   *
   * The api address can move to the other server while a playlist built for the previous one is
   * still loaded — after a cold start with an existing session, for instance. Comparing the two
   * tells the view model that the media has to be rebuilt, which no network callback would report.
   * A prefix comparison (rather than host equality) also covers servers mounted under a path.
   */
  fun isLoadedFrom(serverUrl: String): Boolean? {
    val uri = controller?.currentMediaItem?.localConfiguration?.uri?.toString() ?: return null
    return uri.startsWith(serverUrl.trimEnd('/') + "/")
  }

  private fun applySession(
    session: PlaybackSessionDto,
    startMs: Long,
    coverUrl: String,
    bookDurationMs: Long,
    chapters: List<ChapterDto>,
    trackUrlBuilder: (sessionId: String, trackIndex: Int) -> String,
    playWhenReady: Boolean
  ) {
    val current = controller ?: return
    if (session.audioTracks.isEmpty()) return

    var index = 0
    var offset = startMs
    session.audioTracks.forEachIndexed { i, track ->
      val trackStart = (track.startOffset * 1000).toLong()
      val trackEnd = trackStart + (track.duration * 1000).toLong()
      if (startMs in trackStart until trackEnd) {
        index = i
        offset = startMs - trackStart
      }
    }

    val items = session.audioTracks.mapIndexed { i, track ->
      val trackStart = (track.startOffset * 1000).toLong()
      val trackDurationMs = (track.duration * 1000).toLong()
      // The cache key has to outlive the playback session: /play hands out a new session id (and
      // therefore new track urls) on every book open, so keying the cache by url would miss every
      // time and store another copy of the same file. The duration is part of the key so a track
      // that was re-encoded or replaced on the server is not served from a stale entry.
      val cacheKey = session.libraryItemId
        ?.takeIf { it.isNotBlank() }
        ?.let { "$it/${track.index}/$trackDurationMs" }
      val extras = bundleOf(
        KEY_TRACK_START_MS to trackStart,
        KEY_TRACK_DURATION_MS to trackDurationMs,
        KEY_BOOK_DURATION_MS to bookDurationMs,
        KEY_BOOK_TITLE to session.displayTitle,
        KEY_BOOK_AUTHOR to session.displayAuthor
      )
      if (cacheKey != null) extras.putString(KEY_CACHE_KEY, cacheKey)
      // The media card (notification, lock screen, Android 13 output switcher) takes its two lines
      // from the current item's metadata, so the chapter is written in here rather than only in the
      // notification builder. PlaybackService rewrites it as chapters advance.
      val (cardTitle, cardSubtitle) = mediaCardText(
        chapters = chapters,
        absoluteMs = if (i == index) startMs else trackStart,
        bookTitle = session.displayTitle,
        bookAuthor = session.displayAuthor
      )
      val metadata = MediaMetadata.Builder()
        .setTitle(cardTitle)
        .setArtist(cardSubtitle)
        .setAlbumTitle(session.displayTitle)
        .setArtworkUri(coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
        .setExtras(extras)
        .build()
      val itemBuilder = MediaItem.Builder()
        .setUri(trackUrlBuilder(session.id, track.index))
        .setMediaId(session.libraryItemId ?: "")
        .setMediaMetadata(metadata)
      // Tells the cache data source which entry this track belongs to; the playback service reads
      // the same key back from the extras when it preloads the next track.
      if (cacheKey != null) itemBuilder.setCustomCacheKey(cacheKey)
      itemBuilder.build()
    }

    current.setMediaItems(items, index, offset.coerceAtLeast(0L))
    current.prepare()
    current.playWhenReady = playWhenReady
  }

  private fun publish(player: Player) {
    val extras = player.currentMediaItem?.mediaMetadata?.extras
    val metadata = player.mediaMetadata
    // The playhead first: it is the field that changes most often and the one nobody else needs
    _position.value = PlaybackPosition(
      positionMs = player.currentPosition.coerceAtLeast(0L),
      trackStartMs = extras?.getLong(KEY_TRACK_START_MS) ?: 0L,
      bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
    )
    _state.update {
      it.copy(
        isConnected = true,
        hasMedia = player.mediaItemCount > 0,
        isPlaying = player.isPlaying,
        playWhenReady = player.playWhenReady,
        playbackState = player.playbackState,
        hasPlaybackError = player.playerError != null,
        durationMs = player.duration.coerceAtLeast(0L),
        speed = player.playbackParameters.speed,
        mediaId = player.currentMediaItem?.mediaId,
        title = metadata.title?.toString().orEmpty(),
        artist = metadata.artist?.toString().orEmpty(),
        bookTitle = extras?.getString(KEY_BOOK_TITLE).orEmpty(),
        bookAuthor = extras?.getString(KEY_BOOK_AUTHOR).orEmpty()
      )
    }
  }

  companion object {
    const val KEY_TRACK_START_MS = "trackStartMs"
    const val KEY_TRACK_DURATION_MS = "trackDurationMs"
    const val KEY_BOOK_DURATION_MS = "bookDurationMs"
    const val KEY_BOOK_TITLE = "bookTitle"
    const val KEY_BOOK_AUTHOR = "bookAuthor"
    /** Cache entry of the track, stable across playback sessions; see [AudioCache]. */
    const val KEY_CACHE_KEY = "cacheKey"

    /** Index of the chapter that contains [absoluteMs], -1 when the book has no chapters. */
    fun chapterIndexAt(chapters: List<ChapterDto>, absoluteMs: Long): Int {
      if (chapters.isEmpty()) return -1
      val index = chapters.indexOfLast { absoluteMs >= (it.start * 1000).toLong() }
      return if (index < 0) 0 else index
    }

    /**
     * The two lines the system media card shows for a position inside the book.
     *
     * Title is the chapter being played, subtitle is "book · author". Without chapters there is
     * nothing more specific than the book itself, and repeating it in both lines would only waste
     * the second one — the author goes there alone instead.
     */
    fun mediaCardText(
      chapters: List<ChapterDto>,
      absoluteMs: Long,
      bookTitle: String,
      bookAuthor: String
    ): Pair<String, String> {
      val index = chapterIndexAt(chapters, absoluteMs)
      val subtitle = listOfNotNull(
        bookTitle.takeIf { it.isNotBlank() },
        bookAuthor.takeIf { it.isNotBlank() }
      ).joinToString(" · ")
      if (index < 0) {
        return (bookTitle.takeIf { it.isNotBlank() } ?: "正在播放") to bookAuthor
      }
      val chapterTitle = chapters[index].title.takeIf { it.isNotBlank() } ?: "第 ${index + 1} 章"
      return chapterTitle to subtitle
    }

    /**
     * Seeks to an absolute book position, switching to the media item that contains it.
     *
     * Shared with [PlaybackService], which applies the chapter skips without a controller.
     * Works on [MediaController] and on the session player alike, both are [Player].
     */
    fun seekToBookPosition(player: Player, positionMs: Long) {
      val count = player.mediaItemCount
      if (count == 0) return
      val target = positionMs.coerceAtLeast(0L)

      // Tracks are separate media items, so the target chapter may live in a different item
      for (index in 0 until count) {
        val extras = player.getMediaItemAt(index).mediaMetadata.extras
        val trackStart = extras?.getLong(KEY_TRACK_START_MS) ?: 0L
        val trackDuration = extras?.getLong(KEY_TRACK_DURATION_MS) ?: 0L
        val trackEnd = if (trackDuration > 0) trackStart + trackDuration else Long.MAX_VALUE
        if (target in trackStart until trackEnd) {
          player.seekTo(index, (target - trackStart).coerceAtLeast(0L))
          return
        }
      }

      // Past the end of the last track: clamp to its final position
      val lastIndex = count - 1
      val lastStart = player.getMediaItemAt(lastIndex).mediaMetadata.extras?.getLong(KEY_TRACK_START_MS) ?: 0L
      player.seekTo(lastIndex, (target - lastStart).coerceAtLeast(0L))
    }
  }
}
