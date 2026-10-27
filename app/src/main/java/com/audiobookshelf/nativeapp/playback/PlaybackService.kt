package com.audiobookshelf.nativeapp.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import com.audiobookshelf.nativeapp.util.AppLog
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.audiobookshelf.nativeapp.AbsApp
import com.audiobookshelf.nativeapp.R
import com.audiobookshelf.nativeapp.data.PendingProgress
import com.audiobookshelf.nativeapp.data.ProgressSyncWorker
import com.audiobookshelf.nativeapp.data.isUnauthorized
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Foreground media service. Media3 owns the notification, the lock screen controls and the
 * media session; the UI talks to it through a [androidx.media3.session.MediaController].
 *
 * Progress is reported from here (not from the UI) so it keeps working while the app is in the
 * background with only the notification alive.
 */
private const val ACTION_REWIND_15 = "com.audiobookshelf.nativeapp.REWIND_15"
private const val ACTION_FORWARD_15 = "com.audiobookshelf.nativeapp.FORWARD_15"

class PlaybackService : MediaSessionService() {
  private val progressReporter by lazy { ProgressReporter(this) }

  private var mediaSession: MediaSession? = null
  private var focusRequest: AudioFocusRequest? = null
  private var modeListener: AudioManager.OnModeChangedListener? = null

  /**
   * True while playback is suspended by an interruption and should come back once the
   * interruption is over.
   *
   * Every interruption source (focus loss, phone call, unplugged headphones) feeds this single
   * flag. The previous implementation kept one boolean per source and they overwrote each other:
   * a call pause followed by a focus loss left `pausedForTransient` set forever (resuming later
   * during an unrelated event), while the opposite order left no flag set at all and playback
   * never came back. Whether audio resumed therefore depended on callback order, which is exactly
   * why the failure looked intermittent.
   */
  private var resumeIntent = false

  /**
   * Elapsed-realtime deadline for keeping the service in the foreground even though media3 sees
   * no active playback. Once the foreground state is dropped the process is demoted to an ordinary
   * background service, the media card disappears, and getting playback back requires a foreground
   * start from the background — which Android 12+ (and the OEM freezer on top of it) restricts.
   * That is the reported "playback stopped at the next episode and only came back when I opened
   * the app". Ten minutes is the timeout media3 itself added in 1.7 for the same reason.
   */
  private var foregroundHoldUntil = 0L

  /** True while we are overriding media3's decision to withdraw the foreground state. */
  private var foregroundHeld = false

  /**
   * Own partial wake lock. ExoPlayer's WAKE_MODE_NETWORK is released the moment playWhenReady
   * turns false, so an interruption pause leaves the resume retry loop (1-8 s delays) with
   * nothing keeping the CPU up. With the screen off, the loop only runs again when something else
   * wakes the device — exactly why playback appeared to resume only after the app was opened.
   */
  private var playbackWakeLock: PowerManager.WakeLock? = null

  /** Retry loop that wins the focus back when the system never sends AUDIOFOCUS_GAIN. */
  private var resumeJob: Job? = null

  /**
   * Pending "notification ping or voice message?" decision. A focus loss carries no source
   * information, so a short grace window separates the two: a ping returns the focus before the
   * window elapses and AUDIOFOCUS_GAIN cancels this job, a voice message does not. Kept as a
   * field so GAIN, a permanent loss and teardown can all cancel it cleanly.
   */
  private var transientPauseJob: Job? = null

  /**
   * The fade-out currently leading to an interruption pause, see [fadeOutAndPause]. A resume
   * landing while it still runs cancels it: the ramp must not pull the gain back down underneath
   * a stream that is playing again.
   */
  private var interruptFadeJob: Job? = null

  /**
   * Uptime of the last pause this service issued itself. `Player.pause()` is reported with
   * PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, exactly like a tap on the pause button, so a short
   * time window is the only way to tell our own interruptions apart from a deliberate stop.
   */
  private var selfPauseAt = 0L

  /** Media id the chapter skip state belongs to; a book change resets the per-chapter guard. */
  private var skipStateMediaId: String? = null

  /** Chapter index whose intro was already skipped, -1 when none. */
  private var introSkippedChapter = -1

  /** Chapter index whose outro was already skipped, -1 when none. */
  private var outroSkippedChapter = -1

  /** Retry budget for stream errors, reset as soon as audio is actually playing again. */
  private var errorRetriesLeft = ERROR_RETRY_ATTEMPTS
  private var errorRetryDelayMs = ERROR_RETRY_START_MS
  private var errorRetryJob: Job? = null
  /** Start of the current error streak; bounds the whole retry window, not just one attempt. */
  private var errorRetryStartedAt = 0L
  /** Completed early when the default network becomes usable again. */
  private var networkRetrySignal: CompletableDeferred<Unit>? = null
  /** Coalesces the burst of callbacks a single VPN reconnect produces. */
  private var networkSignalDebounce: Job? = null
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var lastNetworkValidated = false
  /** Log "waiting for network" once per streak instead of once per loop. */
  private var loggedWaitingForNetwork = false

  /** On-disk chapter cache shared by the player and the preloader. */
  private var audioCache: AudioCache? = null

  /** Background read of the next track's head, see [maybePreloadNextTrack]. */
  private var preloadJob: Job? = null

  /** Key the in-flight [preloadJob] is reading; a different track may cancel and replace it. */
  private var preloadingKey: String? = null

  /** Cache key whose head is fully on disk; the preload for it never runs twice. */
  private var preloadedKey: String? = null

  /** Last key a preload was started for, and when, so a failure is not retried every tick. */
  private var preloadAttemptKey: String? = null
  private var preloadAttemptAt = 0L

  /**
   * The fade-and-seek currently moving the playhead across a chapter boundary. Non-null while it
   * runs: the skip loop stands back meanwhile, otherwise the next 500 ms tick would measure the
   * position in the middle of the cut and start a second one.
   */
  private var chapterTransition: Job? = null

  /**
   * Ownership token for the player gain. Every path that ramps or restores the volume claims a
   * new epoch; a stale chapter fade must not write its captured value back over a later resume
   * (that race used to leave playback silent at 0f). All of these run on the main thread.
   */
  private var volumeEpoch = 0L
  private fun claimVolume(): Long = ++volumeEpoch

  @Volatile
  private var ignoreNotificationSounds = true

  @Volatile
  private var autoResume = true

  /**
   * Deadline of the sleep timer, epoch millis, 0 when it is off.
   *
   * Mirrored from the persisted value rather than owned here: the countdown has to survive the
   * activity being swiped away, and the ui has to be able to show it after a cold start.
   */
  @Volatile
  private var sleepTimerEndsAt = 0L

  // ExoPlayer must only be touched on the thread it was created on (main), so the ticker runs on
  // the main dispatcher and only the network call is pushed to IO.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var progressListener: Player.Listener? = null

  override fun onCreate() {
    super.onCreate()
    playbackWakeLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
      ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "abs:playback-intent")
      ?.apply { setReferenceCounted(false) }
    runCatching {
      val manager = getSystemService(NotificationManager::class.java)
      if (manager.getNotificationChannel(AbsMediaNotificationProvider.CHANNEL_ID) == null) {
        manager.createNotificationChannel(
          NotificationChannel(
            AbsMediaNotificationProvider.CHANNEL_ID,
            AbsMediaNotificationProvider.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
          ).apply {
            setShowBadge(false)
            description = "播放控制与进度"
          }
        )
      }
    }
    val cache = AudioCache(this)
    audioCache = cache
    val player = ExoPlayer.Builder(this)
      // Chapters are read through the disk cache so a track that was preloaded while the previous
      // one was still playing starts from local storage instead of a fresh connection.
      .setMediaSourceFactory(DefaultMediaSourceFactory(cache.dataSourceFactory))
      // Android 13+ derives its own rewind/forward buttons from these increments
      .setSeekBackIncrementMs(15_000)
      .setSeekForwardIncrementMs(15_000)
      .build()

    // Hold a partial wake lock (plus a high-performance Wi-Fi lock) for as long as playback is
    // intended. Without it the CPU is only kept awake by the active AudioTrack, so the moment a
    // track ends there is nothing holding the device up: the loader thread is suspended before the
    // next track has buffered, and playback sits there until the screen comes back on. That is
    // exactly the "stops at the end of an episode while the screen is off, and continues as soon as
    // the app is opened" report. Media3 keeps the locks while playWhenReady and the state is
    // BUFFERING or READY, which covers the gap between two tracks; they are released as soon as
    // playback is paused or stopped.
    player.setWakeMode(C.WAKE_MODE_NETWORK)

    // Without handleAudioFocus the player keeps the audio output while a call is ringing, so the
    // system tears the stream away mid-buffer and the speaker clicks before the ringtone starts.
    // With focus enabled ExoPlayer ducks/pauses on AUDIOFOCUS_LOSS_TRANSIENT (incoming call) and
    // resumes afterwards.
    val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
      .setUsage(androidx.media3.common.C.USAGE_MEDIA)
      // media3 renamed the C.CONTENT_TYPE_* aliases to AUDIO_CONTENT_TYPE_*; the old ones are
      // deprecated. Same int value as android.media.AudioAttributes.CONTENT_TYPE_SPEECH below.
      .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
      .build()
    // Focus is managed here, not by ExoPlayer: transient losses (notification sounds from apps
    // like DingTalk/WeChat that do not request ducking) must not pause playback.
    player.setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false)
    player.setHandleAudioBecomingNoisy(true)

    val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
      handleAudioFocusChange(change)
    }
    val platformAttributes = android.media.AudioAttributes.Builder()
      .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
      .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()
    focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(platformAttributes)
      // Speech must not be ducked down to a whisper: ask the framework to send
      // AUDIOFOCUS_LOSS_TRANSIENT instead of AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK. This is also the
      // "no volume change for short pings" guarantee: the framework never lowers our gain, and
      // the DEFER branch below never touches player.volume.
      .setWillPauseWhenDucked(true)
      .setOnAudioFocusChangeListener(focusListener)
      .build()

    // Calls (gsm and voip) switch the audio mode; notification sounds do not. This is how a
    // phone call can still pause playback while notification sounds do not.
    // OnModeChangedListener is API 31: on older releases the focus retry loop alone detects the
    // end of a call, so this listener is an accelerator rather than a requirement.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      runCatching {
        val am = getSystemService(AudioManager::class.java)
        val listener = AudioManager.OnModeChangedListener { mode ->
          handleAudioModeChanged(mode)
        }
        modeListener = listener
        am?.addOnModeChangedListener(ContextCompat.getMainExecutor(this), listener)
      }
    }

    // The UI's AbsViewModel owns a network callback too, but it is an AndroidViewModel: while
    // only the notification is alive there is nobody to notice the network coming back. A stalled
    // stream then had to wait for the next exponential-backoff tick, and after six failures it
    // stopped for good ("tap play to continue"). Register here so recovery wakes the retry loop
    // immediately, with timed retries as the fallback rather than the only trigger.
    runCatching {
      val connectivity = getSystemService(ConnectivityManager::class.java)
      // Seed with the state at registration: otherwise the first capabilities callback looks like
      // a transition and wakes a retry that has not even started yet.
      lastNetworkValidated = connectivity?.activeNetwork?.let {
        connectivity.getNetworkCapabilities(it)
          ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      } == true
      val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          signalNetworkRecovery("available")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
          // Fires continuously while the signal fluctuates: only a usability transition matters.
          val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
          if (validated == lastNetworkValidated) return
          lastNetworkValidated = validated
          if (validated) signalNetworkRecovery("validated")
        }
      }
      networkCallback = callback
      connectivity?.registerDefaultNetworkCallback(callback)
    }.onFailure { AppLog.w("PlaybackService", "network callback registration failed", it) }

    val session = MediaSession.Builder(this, player)
      .setCallback(seekCommandCallback)
      .build()
    // Extra buttons for the system media card / lock screen. Since 1.11 the no-arg Builder() and
    // setIconResId() are deprecated; Builder(ICON_UNDEFINED) + setCustomIconResId() is the exact
    // equivalent (verified in 1.11.1 bytecode: Builder() delegates to Builder(0), setIconResId()
    // delegates to setCustomIconResId()).
    session.setCustomLayout(
      listOf(
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
          .setSessionCommand(SessionCommand(ACTION_REWIND_15, Bundle.EMPTY))
          .setCustomIconResId(R.drawable.ic_replay_15)
          .setDisplayName("后退 15 秒")
          .build(),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
          .setSessionCommand(SessionCommand(ACTION_FORWARD_15, Bundle.EMPTY))
          .setCustomIconResId(R.drawable.ic_forward_15)
          .setDisplayName("前进 15 秒")
          .build()
      )
    )
    mediaSession = session

    setMediaNotificationProvider(
      AbsMediaNotificationProvider(this) {
        PendingIntent.getActivity(
          this,
          0,
          Intent(this, com.audiobookshelf.nativeapp.MainActivity::class.java),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
      }
    )

    val listener = object : Player.Listener {
      override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
          // Audio is intended again: the grace window is no longer needed.
          foregroundHoldUntil = 0L
          // Playback can be restarted (user tap, new book, resume) while an interruption fade
          // still owns the gain. Cancel it first and restore the gain on this very frame, so a
          // book never comes back silent and the fade's delayed restore cannot fight the resume.
          interruptFadeJob?.cancel()
          chapterTransition?.cancel()
          claimVolume()
          player.volume = FULL_VOLUME
          // Hold focus while we play so the system knows who owns the output. ExoPlayer runs with
          // handleAudioFocus=false, so nothing else requests it on our behalf.
          if (!requestFocus()) {
            // Another app owns the audio output right now: never play over it. Remember that the
            // user wanted playback and retry until the focus is free again.
            resumeIntent = true
            pauseSelf(player)
            startResumeRetry()
          } else {
            // Playback runs again, whatever interruption was pending is over.
            resumeIntent = false
            resumeJob?.cancel()
          }
          updatePlaybackWakeLock()
          return
        }
        // Any pause — an interruption, a playlist rebuild, a deliberate stop — opens a grace
        // window in which the media card and the foreground state must survive.
        notePlaybackStalled()
        val ours = SystemClock.elapsedRealtime() - selfPauseAt < SELF_PAUSE_WINDOW_MS
        // USER_REQUEST: the user pressed pause. AUDIO_BECOMING_NOISY: headphones were unplugged,
        // handled by the view model through RuntimeState.waitingForHeadset.
        val deliberate = !ours && (
          reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
          )
        if (deliberate) {
          if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            // The user stopped playback on purpose: never resurrect it.
            resumeIntent = false
            resumeJob?.cancel()
          }
          // Release the focus so whoever asks for it next does not have to fight us.
          abandonAudioFocus()
        }
      }

      override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
          // Audio is flowing again: stop the retry loop and refill its budget for the next drop.
          cancelStreamRetry()
        } else {
          scope.launch { progressReporter.report(player) }
        }
      }

      override fun onPlayerError(error: PlaybackException) {
        AppLog.w("PlaybackService", "playback error ${error.errorCodeName}", error)
        scheduleErrorRetry(player, error)
      }

      override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
      ) {
        // Observation only. A short backward jump inside one track used to be "healed" by an
        // automatic seek with a 30 s cooldown, based on an unverified cache-seam hypothesis.
        // That intervention is gone: INTERNAL also covers ExoPlayer's own position corrections
        // and playlist rebuilds, so seeking on top of them can fight the player. Keeping the
        // signal in debug logcat lets a real reproduction be traced to the stream layer instead
        // of being papered over with a threshold. See positionFallbackToLog().
        val fallbackMs = positionFallbackToLog(
          reason = reason,
          oldTrack = oldPosition.mediaItemIndex,
          newTrack = newPosition.mediaItemIndex,
          oldPositionMs = oldPosition.positionMs,
          newPositionMs = newPosition.positionMs
        ) ?: return
        AppLog.w(
          "PlaybackService",
          "position fell back ${fallbackMs}ms (observation only, reason=$reason)"
        )
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE) {
          // A playlist rebuild (new book, next episode, error retry) passes through STATE_IDLE.
          // media3 reads that as "not engaged" and drops the foreground state for a frame, so the
          // grace window has to be open before its notification update runs.
          if (player.mediaItemCount > 0) notePlaybackStalled()
          // Keep the focus while playback is still intended: an idle player that is about to be
          // retried must not hand the output over and then resume without it.
          if (!player.playWhenReady) abandonAudioFocus()
          // Media was dropped (logout, failed load): there is nothing left to resume.
          if (player.mediaItemCount == 0) {
            resumeIntent = false
            resumeJob?.cancel()
            cancelStreamRetry()
            updatePlaybackWakeLock()
          }
        }
        // isPlaying turns false before ENDED in some cases; report here as well so the finished
        // flag is never lost when the last track runs out.
        if (playbackState == Player.STATE_ENDED) scope.launch { progressReporter.report(player) }
      }
    }
    progressListener = listener
    player.addListener(listener)

    scope.launch {
      (application as AbsApp).container.appPrefs.let { prefs ->
        launch { prefs.observeIgnoreNotificationSounds().collect { ignoreNotificationSounds = it } }
        launch { prefs.observeAutoResume().collect { autoResume = it } }
        launch { prefs.observeSleepTimer().collect { sleepTimerEndsAt = it.endsAtMs } }
      }
    }

    scope.launch {
      while (isActive) {
        delay(PROGRESS_INTERVAL_MS)
        if (player.isPlaying) progressReporter.report(player)
      }
    }

    // Sleep timer. It used to be a `delay` in the view model, which meant it died together with
    // the activity: swiping the task away left the countdown running on screen while nothing was
    // left to pause playback, and a process the OEM froze pushed the deadline back by however long
    // it slept. The deadline now lives on disk and is compared against the wall clock, so this
    // loop can be late without being wrong — and it is the service, which outlives the ui, that
    // does the pausing.
    scope.launch {
      while (isActive) {
        // A running timer needs second resolution; without one this is just a long read.
        delay(if (sleepTimerEndsAt > 0L) SLEEP_TIMER_TICK_MS else SLEEP_TIMER_IDLE_TICK_MS)
        val endsAt = sleepTimerEndsAt
        if (endsAt <= 0L || System.currentTimeMillis() < endsAt) continue
        fireSleepTimer(player)
      }
    }

    // Chapter skips live here rather than in the player screen: they have to keep working while
    // the user browses another screen, listens from the mini bar or has the app in the background.
    scope.launch {
      while (isActive) {
        // Nothing loaded: back off instead of waking up twice a second for an empty list.
        val idle = (application as AbsApp).container.runtime.chapters.isEmpty()
        delay(if (idle) IDLE_CHAPTER_TICK_MS else CHAPTER_TICK_MS)
        // Re-evaluate the foreground/wake-lock holds even when no player event fires: a paused
        // book produces no further callbacks, so the grace window needs a clock of its own.
        enforceForegroundHold()
        updatePlaybackWakeLock()
        publishChapterMetadata(player)
        applyChapterSkips(player)
        maybePreloadNextTrack(player)
      }
    }
  }

  /** Handles the ±15s buttons that the system media card shows. */
  private val seekCommandCallback = object : MediaSession.Callback {
    override fun onCustomCommand(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      customCommand: SessionCommand,
      args: Bundle
    ): ListenableFuture<SessionResult> {
      val deltaMs = when (customCommand.customAction) {
        ACTION_REWIND_15 -> -15_000L
        ACTION_FORWARD_15 -> 15_000L
        else -> return Futures.immediateFuture(
          SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
        )
      }
      val player = session.player
      val upper = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
      player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, upper))
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
  }

  /**
   * A call is reported through the audio mode rather than through focus on many devices, so this
   * feeds the same [resumeIntent] state as a focus loss instead of keeping a flag of its own.
   */
  private fun handleAudioModeChanged(mode: Int) {
    val player = mediaSession?.player ?: return
    AppLog.i("PlaybackService", "audio mode=$mode playing=${player.isPlaying} vol=${player.volume}")
    when (mode) {
      AudioManager.MODE_RINGTONE,
      AudioManager.MODE_IN_CALL,
      AudioManager.MODE_IN_COMMUNICATION -> {
        if (player.playWhenReady || player.isPlaying) {
          resumeIntent = true
          fadeOutAndPause(player)
          startResumeRetry()
        }
      }
      // Only an interrupted playback is resumed: resumeNow() is a no-op when the user paused.
      AudioManager.MODE_NORMAL -> resumeNow()
    }
  }

  private fun isCallMode(): Boolean = when (getSystemService(AudioManager::class.java)?.mode) {
    AudioManager.MODE_RINGTONE,
    AudioManager.MODE_IN_CALL,
    AudioManager.MODE_IN_COMMUNICATION -> true
    else -> false
  }

  /**
   * Read-only probe of the usages that are actually producing sound right now.
   *
   * `getActivePlaybackConfigurations()` is API 26 (exactly our minSdk), needs no permission and,
   * unlike requestAudioFocus(), does not change who owns the output. It is the only signal that
   * can see a USAGE_ALARM player; isMusicActive() cannot. Only non-MEDIA usages are consumed by
   * the policy, so our own paused USAGE_MEDIA track can never be mistaken for another app.
   */
  private fun activeAudioUsages(): Set<Int> = runCatching {
    getSystemService(AudioManager::class.java)
      ?.activePlaybackConfigurations
      ?.mapNotNull { it.audioAttributes?.usage }
      ?.toSet() ?: emptySet()
  }.getOrDefault(emptySet())

  private fun isMusicActive(): Boolean =
    getSystemService(AudioManager::class.java)?.isMusicActive == true

  /** Pauses without being mistaken for a user-initiated stop, see [selfPauseAt]. */
  private fun pauseSelf(player: Player) {
    selfPauseAt = SystemClock.elapsedRealtime()
    player.pause()
  }

  /** @return true when this app owns the audio output */
  private fun requestFocus(): Boolean {
    val am = getSystemService(AudioManager::class.java) ?: return false
    val request = focusRequest ?: return false
    return am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  private fun abandonAudioFocus() {
    val am = getSystemService(AudioManager::class.java) ?: return
    focusRequest?.let { am.abandonAudioFocusRequest(it) }
  }

  /**
   * Focus is owned by this service, not by ExoPlayer, so this is the single place deciding whether
   * an interruption may pause an audiobook.
   */
  private fun handleAudioFocusChange(change: Int) {
    val player = mediaSession?.player ?: return
    AppLog.i(
      "PlaybackService",
      "focus change=$change playing=${player.isPlaying} vol=${player.volume} " +
        "callMode=${isCallMode()} usages=${activeAudioUsages()}"
    )
    // setWillPauseWhenDucked(true) normally turns CAN_DUCK into LOSS_TRANSIENT; some OEM
    // implementations (MIUI included) still deliver CAN_DUCK. Both defer the decision, but
    // CAN_DUCK means the requester explicitly allowed others to keep playing - a short ping -
    // so only that case gets the slightly longer unidentified-source fallback window.
    val duckable = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
    val event = when (change) {
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusEvent.TRANSIENT
      AudioManager.AUDIOFOCUS_LOSS -> FocusEvent.LOSS
      AudioManager.AUDIOFOCUS_GAIN -> FocusEvent.GAIN
      else -> FocusEvent.OTHER
    }
    when (
      decideFocusAction(
        event,
        ignoreNotificationSounds,
        isCallMode(),
        transientPauseJob?.isActive == true
      )
    ) {
      FocusAction.INTERRUPT_NOW -> {
        transientPauseJob?.cancel()
        interruptPlayback(player)
      }
      FocusAction.DEFER -> {
        // A short ping returns the focus inside the window and its GAIN cancels this job: the
        // volume is never touched and the book keeps playing. Identifying the source ends the
        // window early in BOTH directions: a USAGE_NOTIFICATION ping is released immediately
        // (no waiting for GAIN), an alarm / voice message interrupts immediately instead of after
        // 1.5 s. Only an OEM that reports no playback config rides out the fallback timer.
        val graceMs = transientGraceWindowMs(duckable)
        transientPauseJob = scope.launch {
          val deadline = SystemClock.elapsedRealtime() + graceMs
          var observed = TransientSource.UNKNOWN
          while (isActive && SystemClock.elapsedRealtime() < deadline) {
            observed = mergeTransientObservation(
              observed,
              classifyTransientSource(activeAudioUsages())
            )
            if (observed != TransientSource.UNKNOWN) break
            delay(TRANSIENT_PROBE_MS)
          }
          // AUDIOFOCUS_GAIN cancelled the window: this was a short ping, nothing to decide.
          if (!isActive) return@launch
          val current = mediaSession?.player ?: return@launch
          if (!shouldInterruptAfterGrace(
              current.playWhenReady, current.isPlaying, isCallMode(), observed
            )
          ) {
            AppLog.i(
              "PlaybackService",
              "transient source=$observed window=${graceMs}ms, letting the book play"
            )
            return@launch
          }
          AppLog.i(
            "PlaybackService",
            "transient source=$observed window=${graceMs}ms, pausing"
          )
          interruptPlayback(current)
        }
      }
      FocusAction.RESUME_IF_INTERRUPTED -> {
        // Cancelling here is what makes a short ping a no-op: no pause, no volume change at all.
        // The focus is deliberately not abandoned on a loss - keeping the request registered is
        // what makes the system deliver this GAIN when the other app is done. resumeNow() still
        // verifies that no alarm / voice audio is sounding: an OEM can send GAIN early, and an
        // alarm can release its focus request while continuing to ring.
        transientPauseJob?.cancel()
        if (resumeIntent) resumeNow()
      }
      FocusAction.IGNORE -> Unit
    }
  }

  private fun interruptPlayback(player: Player) {
    if (player.playWhenReady || player.isPlaying) {
      resumeIntent = true
      // The fade pauses the player ~80 ms later. Claim the CPU lock now, before that pause
      // releases ExoPlayer's own wake lock, so the retry loop below can run with the screen off.
      updatePlaybackWakeLock()
      fadeOutAndPause(player)
    }
    if (resumeIntent) startResumeRetry()
  }

  /**
   * Pauses behind a very short fade-out instead of an instant cut.
   *
   * A bare `pause()` stops the AudioTrack at whatever sample amplitude happens to be current:
   * full scale to silence in one sample, the exact step that is heard as a click. An incoming
   * call lands that step on top of the first ring — the audio mode is switching and the ringtone
   * starts at the same moment — so the cut stands out as a squeak precisely when the ring begins.
   * Ramping the gain to zero first (same mechanism and length as the chapter transition fade,
   * which proved the approach on this device) turns the step into a dip below what the ear
   * resolves.
   *
   * The pause is skipped when the interruption is lifted while the ramp is still running:
   * resumeNow() then owns the player and playback must survive the fade. Otherwise the gain stays
   * at zero until the output has actually gone quiet, and only then returns to 1 — restoring it
   * immediately after pause() put a 0 -> 1 step across the last buffered samples, which is just
   * as audible as the original cut and lands exactly on the first ring.
   */
  private fun fadeOutAndPause(player: Player) {
    // A single call fires both a focus loss and an audio-mode change. Restarting the ramp on the
    // second event kept the gain above zero for another whole ramp while the ringtone was already
    // starting; the earliest fade is the one that has to finish.
    if (interruptFadeJob?.isActive == true) return
    val epoch = claimVolume()
    AppLog.i("PlaybackService", "interrupt fade start: vol=${player.volume} playing=${player.isPlaying}")
    interruptFadeJob = scope.launch {
      try {
        rampVolume(player, 0f, INTERRUPT_FADE_MS)
        if (resumeIntent && (player.playWhenReady || player.isPlaying)) {
          pauseSelf(player)
          // AudioTrack still holds buffered samples right after pause(). Restoring the gain at
          // once jumps 0 -> 1 across those samples: a step as audible as the original cut, and
          // it lands exactly on the first ring. Wait for the output to go quiet first.
          delay(INTERRUPT_SETTLE_MS)
        }
        // Only restore while the output is stopped. A resume that arrived during the settle delay
        // cancels this job and restores the gain itself.
        if (volumeEpoch == epoch && !player.playWhenReady && !player.isPlaying) {
          player.volume = FULL_VOLUME
        }
        AppLog.i("PlaybackService", "interrupt fade done: vol=${player.volume} playing=${player.isPlaying}")
      } catch (error: CancellationException) {
        // A resume or teardown cancelled the ramp; both restore the gain on their own terms.
        throw error
      }
    }
  }

  /** Resumes now when everything allows it; otherwise leaves the retry loop in charge. */
  private fun resumeNow() {
    val player = mediaSession?.player ?: return
    if (!resumeIntent) return
    val runtime = (application as AbsApp).container.runtime
    if (runtime.waitingForHeadset || isCallMode()) {
      startResumeRetry()
      return
    }
    // AUDIOFOCUS_GAIN is not proof that the output is free: an alarm can release its focus request
    // and keep ringing, and some OEMs deliver GAIN repeatedly while it rings. Keep resumeIntent
    // set in that case and let the retry loop wait until the alarm audio has actually disappeared.
    if (hasPriorityAudioActive(activeAudioUsages(), false)) {
      AppLog.i("PlaybackService", "focus gain ignored: alarm/voice audio is still active")
      startResumeRetry()
      return
    }
    if (!requestFocus()) {
      startResumeRetry()
      return
    }
    resumeIntent = false
    resumeJob?.cancel()
    // The interruption fade may still be running (call ended within ~120ms): it must not pull the
    // gain back down now that playback is coming back.
    interruptFadeJob?.cancel()
    chapterTransition?.cancel()
    claimVolume()
    player.volume = FULL_VOLUME
    player.play()
  }

  /**
   * Waits for the interruption to be genuinely over before asking for the focus back.
   *
   * A granted focus request used to be treated as proof that the output was free. It is not: an
   * alarm (USAGE_ALARM) can keep sounding after releasing its focus request, and our own GAIN
   * request may still succeed because the framework does not stop alarm audio. The loop then
   * resumed into a ringing alarm, the alarm re-asserted focus, we paused, and the loop resumed
   * again - the reported "pause, play, pause, play" cycle. Taking the focus back also pauses
   * whoever is playing, which is why a video in the browser used to stop by itself.
   *
   * The loop now probes active playback configurations (read-only) and only asks for focus once
   * no priority audio has been seen for two consecutive probes. AUDIOFOCUS_GAIN remains the
   * instant fast path, but it too is gated by the same probe in resumeNow().
   */
  private fun startResumeRetry() {
    if (!autoResume) {
      // Auto resume is off: give the focus back instead of holding it while paused.
      resumeIntent = false
      abandonAudioFocus()
      updatePlaybackWakeLock()
      return
    }
    if (resumeJob?.isActive == true) return
    resumeJob = scope.launch {
      var waitMs = RESUME_RETRY_START_MS
      var blockedMs = 0L
      var quietProbes = 0
      var prioritySince = 0L
      while (isActive) {
        val waitStartedAt = SystemClock.elapsedRealtime()
        delay(waitMs)
        val player = mediaSession?.player ?: return@launch
        if (!resumeIntent) return@launch
        // Already back (the AUDIOFOCUS_GAIN fast path ran in the meantime)
        if (player.playWhenReady) {
          resumeIntent = false
          return@launch
        }
        val app = application as AbsApp
        // Logged out or media cleared meanwhile: do not resurrect playback
        if (app.container.repository.session == null || player.mediaItemCount == 0) {
          resumeIntent = false
          return@launch
        }
        val callMode = isCallMode()
        val waitingForHeadset = app.container.runtime.waitingForHeadset
        val state = classifyInterruption(activeAudioUsages(), isMusicActive(), callMode)
        val now = SystemClock.elapsedRealtime()
        val priorityHeldMs = if (prioritySince == 0L) 0L else now - prioritySince
        // A frozen device can stretch a 2 s delay into minutes. Spend the real elapsed time, not
        // the planned one, or the 120 s give-up budget (and the wake lock) never expires.
        val nextBlockedMs = blockedMs + resumeElapsedMs(waitMs, now - waitStartedAt)
        when (
          decideResumeAction(
            interruption = state,
            callMode = callMode,
            waitingForHeadset = waitingForHeadset,
            quietProbes = quietProbes,
            blockedMs = nextBlockedMs,
            resumeWindowMs = RESUME_WINDOW_MS,
            priorityHeldMs = priorityHeldMs,
            priorityMaxMs = RESUME_PRIORITY_MAX_MS
          )
        ) {
          ResumeAction.GIVE_UP -> {
            AppLog.i(
              "PlaybackService",
              "Auto resume gave up after ${nextBlockedMs / 1000}s of focus contention"
            )
            resumeIntent = false
            // Stop sitting on a focus request we are not using.
            abandonAudioFocus()
            return@launch
          }
          ResumeAction.WAIT_OPEN_ENDED -> {
            // An alarm / voice message is still sounding: never touch the focus, and do not spend
            // the give-up budget on a source whose duration is chosen by the user. Calls and
            // unplugged headphones have no hard deadline; a stale OEM playback config does.
            val newlyBlocked = state == InterruptionState.PRIORITY && prioritySince == 0L
            prioritySince = when {
              callMode || waitingForHeadset -> 0L
              prioritySince == 0L -> now
              else -> prioritySince
            }
            if (newlyBlocked) {
              AppLog.i(
                "PlaybackService",
                "auto resume deferred: priority audio still active usages=${activeAudioUsages()}"
              )
            }
            quietProbes = 0
            waitMs = RESUME_PRIORITY_POLL_MS
            continue
          }
          ResumeAction.WAIT -> {
            prioritySince = 0L
            quietProbes = if (state == InterruptionState.NONE) quietProbes + 1 else 0
            blockedMs = nextBlockedMs
            // Quiet probes run at a short fixed cadence; media contention backs off instead.
            waitMs = if (state == InterruptionState.NONE) {
              RESUME_RETRY_START_MS
            } else {
              (waitMs * 3 / 2).coerceAtMost(RESUME_RETRY_MAX_MS)
            }
            continue
          }
          ResumeAction.RESUME -> {
            prioritySince = 0L
            blockedMs = nextBlockedMs
            // Close the race where an alarm starts between the last quiet probe and play().
            if (hasPriorityAudioActive(activeAudioUsages(), callMode)) {
              quietProbes = 0
              waitMs = RESUME_RETRY_START_MS
              continue
            }
            if (requestFocus()) {
              AppLog.i("PlaybackService", "Auto resume: focus granted after ${blockedMs / 1000}s")
              resumeIntent = false
              interruptFadeJob?.cancel()
              chapterTransition?.cancel()
              claimVolume()
              player.volume = FULL_VOLUME
              player.play()
              return@launch
            }
            quietProbes = 0
            waitMs = (waitMs * 3 / 2).coerceAtMost(RESUME_RETRY_MAX_MS)
          }
        }
      }
    }
    // Every exit path (focus recovered, budget exhausted, cancelled) must re-evaluate the wake
    // lock: a retry that gave up would otherwise keep the CPU up for the whole timeout.
    resumeJob?.invokeOnCompletion {
      if (scope.isActive) scope.launch { updatePlaybackWakeLock() }
    }
  }

  /**
   * Keeps the system media card on the chapter being played.
   *
   * The notification, the lock screen and the Android 13 output card all take the two text lines
   * from the *current media item's* metadata, so the chapter has to be written back into the
   * playlist item instead of being formatted in the notification builder (which only affects the
   * notification). `replaceMediaItem` swaps the metadata in place: the local configuration (uri,
   * mime type) is unchanged, so Media3 keeps the existing MediaSource and nothing is re-prepared,
   * re-buffered or interrupted.
   */
  private fun publishChapterMetadata(player: Player) {
    if (player.mediaItemCount == 0) return
    val item = player.currentMediaItem ?: return
    val runtime = (application as AbsApp).container.runtime
    val chapters = runtime.chapters
    if (chapters.isEmpty()) return
    val mediaId = item.mediaId
    // Same guard as the chapter skips: the view model can publish the next book before its media
    // items reach the session, and labelling the card from the new chapter list while the old
    // track is still loaded would show a chapter the listener is not in.
    if (!mediaId.isNullOrBlank() && mediaId != runtime.chaptersMediaId) return
    val extras = item.mediaMetadata.extras ?: return
    val absoluteMs = extras.getLong(PlayerConnection.KEY_TRACK_START_MS) + player.currentPosition
    val (title, subtitle) = PlayerConnection.mediaCardText(
      chapters = chapters,
      absoluteMs = absoluteMs,
      bookTitle = extras.getString(PlayerConnection.KEY_BOOK_TITLE).orEmpty(),
      bookAuthor = extras.getString(PlayerConnection.KEY_BOOK_AUTHOR).orEmpty()
    )
    if (item.mediaMetadata.title?.toString() == title &&
      item.mediaMetadata.artist?.toString() == subtitle
    ) {
      return
    }
    val updated = item.buildUpon()
      .setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(title).setArtist(subtitle).build())
      .build()
    AppLog.i("PlaybackService", "Media card: $title / $subtitle")
    player.replaceMediaItem(player.currentMediaItemIndex, updated)
  }

  /**
   * Applies the per-book intro/outro skips with 500 ms resolution.
   *
   * This is the only place the skips run. They used to be split: the intro was applied by the
   * "next/previous chapter" buttons and by the outro jump, so a natural chapter transition never
   * skipped it, and the outro lived in a `LaunchedEffect` on the player screen, so it stopped
   * working the moment that screen left the composition.
   *
   * Intro is skipped once per chapter entry. Without that guard a manual seek back to the start of
   * a chapter would be pushed forward again on the next tick and the position would fight the
   * user. Outro is capped at half the chapter so a very short chapter cannot place the trigger
   * before its own start and cascade through the whole book.
   *
   * Both jumps go through [startChapterTransition] rather than seeking straight away: a hard cut
   * between two decoded positions is what is heard as a click at the chapter boundary.
   */
  private fun applyChapterSkips(player: Player) {
    val runtime = (application as AbsApp).container.runtime
    val chapters = runtime.chapters
    if (chapters.isEmpty()) return
    // Paused by the user: leave the position alone, a paused seek must stay where it was dropped.
    if (!player.playWhenReady) return
    // A jump of our own is already running. The position it reports is the one from before the
    // fade, so measuring it here would decide on a chapter that is about to be left.
    if (chapterTransition?.isActive == true) return

    val mediaId = player.currentMediaItem?.mediaId
    // The view model can publish the next book before its media items reach the session, because
    // MediaController commands are asynchronous. Acting on that mismatch would seek the old book
    // to a position taken from the new chapter list. A blank id means the server did not send a
    // libraryItemId, in which case there is nothing to compare and the skip still has to work.
    if (!mediaId.isNullOrBlank() && mediaId != runtime.chaptersMediaId) return

    if (mediaId != skipStateMediaId) {
      skipStateMediaId = mediaId
      introSkippedChapter = -1
      outroSkippedChapter = -1
    }

    val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return
    val absoluteMs = extras.getLong(PlayerConnection.KEY_TRACK_START_MS) + player.currentPosition

    var index = chapters.indexOfLast { absoluteMs >= (it.start * 1000).toLong() }
    if (index < 0) index = 0
    val chapter = chapters[index]
    val startMs = (chapter.start * 1000).toLong()
    val endMs = (chapter.end * 1000).toLong()
    if (endMs <= startMs) return

    val introMs = (runtime.skipIntroSeconds * 1000L).coerceAtMost(endMs - startMs)
    if (introMs > 0 && index != introSkippedChapter && absoluteMs < startMs + introMs) {
      introSkippedChapter = index
      AppLog.i("PlaybackService", "Skip intro: chapter $index, +${introMs / 1000}s")
      startChapterTransition(player, startMs + introMs, "skip intro of chapter $index")
      return
    }

    val outroMs = (runtime.skipOutroSeconds * 1000L).coerceAtMost((endMs - startMs) / 2)
    if (outroMs > 0 && index != outroSkippedChapter && index < chapters.lastIndex &&
      absoluteMs >= endMs - outroMs &&
      // Less than a fade plus a seek left before the end: the natural transition is already on
      // its way and a second jump landing inside it is precisely the seam that clicks. Playing
      // the last second of the outro is the cheaper trade.
      absoluteMs <= endMs - TRANSITION_LEAD_MS
    ) {
      outroSkippedChapter = index
      val next = chapters[index + 1]
      val nextStartMs = (next.start * 1000).toLong()
      val nextEndMs = (next.end * 1000).toLong()
      val target = (nextStartMs + runtime.skipIntroSeconds * 1000L).coerceAtMost(nextEndMs)
      // The next chapter counts as entered, otherwise its intro skip would fire right after.
      introSkippedChapter = index + 1
      AppLog.i("PlaybackService", "Skip outro: chapter $index -> ${target / 1000}s")
      startChapterTransition(player, target, "skip outro of chapter $index")
    }
  }

  /**
   * Moves the playhead to [targetMs] behind a short volume fade.
   *
   * A chapter skip is a hard cut in the waveform: ExoPlayer flushes the audio sink, which truncates
   * the outgoing stream mid-sample, and decoding restarts at a sample with no relation to the one
   * that was playing, so the incoming stream begins at whatever amplitude that sample happens to
   * have. Both ends are a step, and a step is heard as a click. Ramping the gain down before the
   * seek and back up after it turns the two steps into a dip short enough to read as part of the
   * cut.
   *
   * The fade-in waits for the player to be playing again instead of running right after the seek:
   * the new position has to be opened, decoded and buffered first, and fading up while the output
   * is still silent would only restore full gain in time for the very sample that clicks.
   *
   * The gain is restored in `finally` only while this fade still owns the volume epoch. A resume
   * or an interruption can claim it half way through (book change, call, focus gain); writing the
   * captured ramp value back afterwards would overwrite their 1f and leave playback silent.
   */
  private fun startChapterTransition(player: Player, targetMs: Long, label: String) {
    if (chapterTransition?.isActive == true) return
    val epoch = claimVolume()
    chapterTransition = scope.launch {
      try {
        rampVolume(player, 0f, FADE_OUT_MS)
        AppLog.i("PlaybackService", "Chapter transition ($label) -> ${targetMs / 1000}s")
        PlayerConnection.seekToBookPosition(player, targetMs)
        // `seekTo` is asynchronous and `isPlaying` keeps reporting the old position for a few
        // frames after it. Fading in on that reading would raise the gain while the outgoing
        // stream is still playing, and the cut would land at full scale again.
        delay(TRANSITION_SETTLE_MS)
        val deadline = SystemClock.elapsedRealtime() + TRANSITION_READY_TIMEOUT_MS
        while (isActive && !player.isPlaying && SystemClock.elapsedRealtime() < deadline) {
          delay(TRANSITION_POLL_MS)
        }
        rampVolume(player, FULL_VOLUME, FADE_IN_MS)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        // The player is torn down underneath a transition on shutdown; the fade is cosmetic, the
        // chapter change itself has already been requested.
        AppLog.w("PlaybackService", "Chapter transition failed", error)
      } finally {
        if (volumeEpoch == epoch) runCatching { player.volume = FULL_VOLUME }
      }
    }
  }

  /**
   * Linear gain ramp in a handful of steps.
   *
   * A single jump from 1 to 0 would be the same step this is meant to avoid; a per-sample ramp is
   * not reachable through [Player.setVolume], so the steps are made small enough (≈20 ms each) to
   * stay below what the ear resolves as a click.
   */
  private suspend fun rampVolume(player: Player, target: Float, durationMs: Long) {
    val start = player.volume
    if (start == target) return
    val stepMs = (durationMs / VOLUME_RAMP_STEPS).coerceAtLeast(1L)
    for (step in 1..VOLUME_RAMP_STEPS) {
      // Apply the step before waiting. An incoming call leaves only a few milliseconds before the
      // ringtone starts, so the first reduction has to land on the same frame as the event —
      // waiting one step first kept full-scale audio playing through the audio-mode switch.
      player.volume = start + (target - start) * step / VOLUME_RAMP_STEPS
      if (step < VOLUME_RAMP_STEPS) delay(stepMs)
    }
  }

  /**
   * Fills the cache with the head of the next track while the current one is still playing.
   *
   * ExoPlayer loads the next playlist item only once the current one is nearly over, and never
   * loads anything ahead of a chapter the user jumps to — so a chapter switch always begins with a
   * connection, a container parse and a decoder flush. Reading the head of the next track into
   * [AudioCache] ahead of time turns that into a local read, and the preloaded entry is reused both
   * by a natural transition, by the outro skip and by a manual "next chapter".
   *
   * Runs once the current track has been playing for [PRELOAD_SETTLE_MS]: earlier would put a
   * second download next to the player's own first buffer, which on a weak link is exactly the
   * moment that must not be starved. Every track gets its successor preloaded, so the read is not
   * tied to how much of the current chapter is left.
   */
  private fun maybePreloadNextTrack(player: Player) {
    val cache = audioCache ?: return
    // Audio has to be flowing: while the player is still buffering its own track, a competing read
    // only makes the wait longer.
    if (!player.isPlaying || player.mediaItemCount == 0) return
    if (player.currentPosition < PRELOAD_SETTLE_MS) return
    val nextIndex = player.currentMediaItemIndex + 1
    if (nextIndex >= player.mediaItemCount) return
    val next = player.getMediaItemAt(nextIndex)
    val key = next.mediaMetadata.extras?.getString(PlayerConnection.KEY_CACHE_KEY) ?: return
    if (key == preloadedKey) return
    // Only a read of this same key may keep running. A chapter or book change used to be blocked
    // by the previous key's in-flight read for up to its 30 s time budget.
    val inFlight = preloadJob
    if (inFlight?.isActive == true) {
      if (preloadingKey == key) return
      inFlight.cancel()
    }
    // A failed attempt is not retried on every tick: a dead connection would otherwise start a
    // new read twice a second for as long as the chapter lasts.
    val now = SystemClock.elapsedRealtime()
    if (key == preloadAttemptKey && now - preloadAttemptAt < PRELOAD_RETRY_MS) return
    val uri = next.localConfiguration?.uri ?: return
    preloadAttemptKey = key
    preloadAttemptAt = now
    preloadingKey = key
    preloadJob = scope.launch {
      try {
        if (cache.preload(uri, key)) preloadedKey = key
      } finally {
        if (preloadingKey == key) preloadingKey = null
      }
    }
  }

  /**
   * Pauses playback because the sleep timer ran out, and clears the stored deadline.
   *
   * `pause()` is not routed through [pauseSelf]: as far as the focus state machine is concerned
   * this is a deliberate stop (PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST), so the listener above
   * clears [resumeIntent] and releases the focus instead of treating the pause as an interruption
   * that has to be undone the moment the system says the output is free again.
   */
  private fun fireSleepTimer(player: Player) {
    AppLog.i("PlaybackService", "Sleep timer fired, pausing playback")
    sleepTimerEndsAt = 0L
    player.pause()
    scope.launch {
      runCatching { (application as AbsApp).container.appPrefs.clearSleepTimer() }
    }
  }

  /**
   * Retries a dropped stream instead of leaving the player idle.
   *
   * On a weak network ExoPlayer exhausts its own retries and stops at STATE_IDLE while
   * playWhenReady is still true. Nothing resumes it from there, and Media3 tears the session and
   * its notification down as soon as the player is idle — the reported "playback closed itself and
   * the media card disappeared". Only I/O and timeout errors are retried: a parse error or a 404
   * will not heal by itself.
   *
   * Each wait is a [CompletableDeferred] that the network callback completes early, so a network
   * recovery retries immediately instead of waiting for the next backoff tick. Once the timed
   * budget is spent, the loop keeps listening for a network signal at low frequency (up to
   * [ERROR_RETRY_TOTAL_MS]) rather than giving up permanently — the old "six failures, then tap
   * play" behaviour. [decideStreamRetry] keeps every exit condition in one testable place.
   */
  private fun scheduleErrorRetry(player: Player, error: PlaybackException) {
    if (error.errorCode !in RETRYABLE_ERROR_CODES) {
      AppLog.w("PlaybackService", "not retrying ${error.errorCodeName}")
      return
    }
    if (!player.playWhenReady || player.mediaItemCount == 0) return
    // A second error while the loop is still waiting simply continues the same streak. Starting a
    // second loop would double-prepare the player and restart the total-window clock.
    if (errorRetryJob?.isActive == true) return
    if (errorRetryStartedAt == 0L) errorRetryStartedAt = SystemClock.elapsedRealtime()
    loggedWaitingForNetwork = false
    errorRetryJob = scope.launch {
      while (isActive) {
        val elapsed = SystemClock.elapsedRealtime() - errorRetryStartedAt
        val remaining = ERROR_RETRY_TOTAL_MS - elapsed
        if (remaining <= 0) {
          AppLog.i("PlaybackService", "stream retry gave up after ${ERROR_RETRY_TOTAL_MS / 1000}s")
          resetStreamRetryState()
          return@launch
        }
        val timed = errorRetriesLeft > 0
        val waitMs = if (timed) {
          errorRetryDelayMs.coerceAtMost(remaining)
        } else {
          if (!loggedWaitingForNetwork) {
            loggedWaitingForNetwork = true
            AppLog.i("PlaybackService", "retry budget exhausted, waiting for network recovery")
          }
          remaining.coerceAtMost(ERROR_RETRY_WAIT_MS)
        }
        val signal = CompletableDeferred<Unit>()
        networkRetrySignal = signal
        val wokeByNetwork = try {
          withTimeout(waitMs) { signal.await() }
          true
        } catch (_: TimeoutCancellationException) {
          false
        } finally {
          if (networkRetrySignal === signal) networkRetrySignal = null
        }
        val current = mediaSession?.player ?: run {
          resetStreamRetryState()
          return@launch
        }
        // In the wait-for-network state a missed onAvailable is still worth a low-frequency
        // check: a VPN link can report VALIDATED while its TLS handshakes keep getting dropped.
        val networkReady = wokeByNetwork || (!timed && isNetworkValidated())
        val action = decideStreamRetry(
          playWhenReady = current.playWhenReady,
          hasMedia = current.mediaItemCount > 0,
          playbackState = current.playbackState,
          retriesLeft = errorRetriesLeft,
          networkSignal = networkReady,
          elapsedMs = SystemClock.elapsedRealtime() - errorRetryStartedAt,
          totalBudgetMs = ERROR_RETRY_TOTAL_MS
        )
        when (action) {
          StreamRetryAction.STOP -> {
            AppLog.i(
              "PlaybackService",
              "stream retry stopped: playWhenReady=${current.playWhenReady} " +
                "state=${current.playbackState} media=${current.mediaItemCount}"
            )
            resetStreamRetryState()
            return@launch
          }
          StreamRetryAction.WAIT -> continue
          StreamRetryAction.RETRY -> {
            if (errorRetriesLeft > 0) {
              errorRetriesLeft--
              errorRetryDelayMs = nextStreamRetryDelayMs(errorRetryDelayMs, ERROR_RETRY_MAX_MS)
              // The network signal can arrive mid-window: report the real trigger, not the
              // planned backoff, so the next logcat investigation is not misled.
              if (wokeByNetwork) {
                AppLog.i("PlaybackService", "retry triggered by network, $errorRetriesLeft attempts left")
              } else {
                AppLog.i("PlaybackService", "retry after ${waitMs}ms, $errorRetriesLeft attempts left")
              }
            } else {
              AppLog.i("PlaybackService", "retry triggered by network recovery")
            }
            current.prepare()
          }
        }
      }
    }
  }

  /** Wakes a pending stream retry; a network event must never touch a stream that is playing fine. */
  private fun signalNetworkRecovery(reason: String) {
    if (networkRetrySignal == null) return
    networkSignalDebounce?.cancel()
    networkSignalDebounce = scope.launch {
      delay(NETWORK_SIGNAL_DEBOUNCE_MS)
      val signal = networkRetrySignal ?: return@launch
      AppLog.i("PlaybackService", "network $reason, waking stream retry")
      signal.complete(Unit)
    }
  }

  /** Fallback for OEMs that restore the link without dispatching a usable network callback. */
  private fun isNetworkValidated(): Boolean = runCatching {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val network = connectivity?.activeNetwork
    val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
  }.getOrDefault(false)

  private fun resetStreamRetryState() {
    errorRetriesLeft = ERROR_RETRY_ATTEMPTS
    errorRetryDelayMs = ERROR_RETRY_START_MS
    errorRetryStartedAt = 0L
    loggedWaitingForNetwork = false
    networkRetrySignal = null
    networkSignalDebounce?.cancel()
    networkSignalDebounce = null
    errorRetryJob = null
  }

  private fun cancelStreamRetry() {
    errorRetryJob?.cancel()
    resetStreamRetryState()
  }


  /** True while audio is expected to come back without the user starting a new book. */
  private fun hasPlaybackIntent(): Boolean {
    val player = mediaSession?.player ?: return false
    return shouldHoldPlaybackForeground(
      hasMedia = player.mediaItemCount > 0,
      playWhenReady = player.playWhenReady,
      resumeIntent = resumeIntent,
      holdUntil = foregroundHoldUntil,
      now = SystemClock.elapsedRealtime()
    )
  }

  /** Opens (or refreshes) the grace window after playback stalls for a moment. */
  private fun notePlaybackStalled() {
    foregroundHoldUntil = SystemClock.elapsedRealtime() + FOREGROUND_HOLD_MS
    updatePlaybackWakeLock()
  }

  /**
   * Holds the CPU only while audio is running or an interruption is waiting to be undone. A
   * deliberate pause releases it: there is no retry loop to keep alive at that point.
   */
  private fun updatePlaybackWakeLock() {
    val lock = playbackWakeLock ?: return
    val player = mediaSession?.player
    val hold = player != null && player.mediaItemCount > 0 &&
      (player.playWhenReady || resumeIntent)
    if (hold && !lock.isHeld) {
      lock.acquire(WAKE_LOCK_TIMEOUT_MS)
      AppLog.i("PlaybackService", "playback wake lock acquired")
    } else if (!hold && lock.isHeld) {
      lock.release()
      AppLog.i("PlaybackService", "playback wake lock released")
    }
  }

  /**
   * Clears our own "still in foreground" bookkeeping once the grace window expires. On media3
   * 1.11.1 a direct onUpdateNotification() call does not run
   * MediaNotificationManager.updateNotification(), so media3's own 10-minute user-engaged timer —
   * not this method — is what actually withdraws the foreground state.
   */
  private fun enforceForegroundHold() {
    if (!foregroundHeld || hasPlaybackIntent()) return
    AppLog.i("PlaybackService", "foreground hold expired")
    foregroundHeld = false
    // Service.stopForeground() is final; let media3 keep its startedInForeground bookkeeping in
    // sync for the next resume.
    mediaSession?.let { onUpdateNotification(it, false) }
    updatePlaybackWakeLock()
  }

  /**
   * Kept after the media3 1.11.1 upgrade for two reasons: it records [foregroundHeld] for the
   * wake-lock bookkeeping, and calling super is part of media3's notification handshake —
   * `onUpdateNotificationAsync` only runs `MediaNotificationManager.updateNotification` after the
   * super implementation has flipped `defaultMethodCalled`; keeping it without `super` would break
   * that handshake. It no longer decides the foreground state, so the override and the
   * [foregroundHeld] bookkeeping it feeds are legacy. Removing it would also drop one wake-lock
   * refresh path, so that cleanup is deferred until a device run can confirm background behaviour.
   *
   * This no longer *decides* the foreground state. Since 1.6.0 media3 keeps the service in the
   * foreground for a 10-minute "user engaged" window after a pause, stop or error, and keeps the
   * notification visible in stopped/error states by default. In 1.11.1 the boolean arriving here
   * is `shouldRunInForeground(...)`'s result and the super two-arg method ignores it (it delegates
   * to the one-arg method that only flips the flag). That built-in window is what fixes the
   * "playback stopped at the next episode and only came back when I opened the app" report; on
   * 1.4.1 this override could not, because media3's hard-coded second `stopForeground()` decisions
   * bypassed the callback on STATE_IDLE/ERROR.
   */
  override fun onUpdateNotification(session: MediaSession, startInForegroundWhenPaused: Boolean) {
    val keep = hasPlaybackIntent()
    val held = keep && !startInForegroundWhenPaused
    if (held && !foregroundHeld) {
      AppLog.i("PlaybackService", "keep foreground across playback stall")
    }
    foregroundHeld = held
    super.onUpdateNotification(session, keep || startInForegroundWhenPaused)
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = mediaSession?.player
    if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    resumeIntent = false
    // A service that is going away must not keep the CPU locked.
    foregroundHoldUntil = 0L
    playbackWakeLock?.let { if (it.isHeld) it.release() }
    playbackWakeLock = null
    resumeJob?.cancel()
    transientPauseJob?.cancel()
    interruptFadeJob?.cancel()
    cancelStreamRetry()
    networkCallback?.let { callback ->
      runCatching {
        getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
      }
    }
    networkCallback = null
    preloadJob?.cancel()
    chapterTransition?.cancel()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      modeListener?.let { getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(it) }
    }
    abandonAudioFocus()
    progressListener?.let { mediaSession?.player?.removeListener(it) }
    scope.cancel()
    mediaSession?.run {
      player.release()
      release()
    }
    mediaSession = null
    // Only after the player is gone: it holds data sources into this cache.
    audioCache?.release()
    audioCache = null
    super.onDestroy()
  }

  companion object {
    private const val PROGRESS_INTERVAL_MS = 15_000L
    /**
     * How long our own bookkeeping considers playback "stalled but intended". Equals media3's
     * DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS (600_000L, added in 1.6.0) so the app's wake-lock
     * hold and media3's built-in foreground window expire together.
     */
    private const val FOREGROUND_HOLD_MS = 600_000L
    /** Safety timeout for our own partial wake lock; state changes keep it refreshed. */
    private const val WAKE_LOCK_TIMEOUT_MS = 600_000L
    /** Resolution of the sleep timer while one is running. */
    private const val SLEEP_TIMER_TICK_MS = 1_000L
    /** Same loop with no timer set: it only re-reads a deadline that is zero. */
    private const val SLEEP_TIMER_IDLE_TICK_MS = 5_000L
    /** Resolution of the intro/outro skip checks. */
    private const val CHAPTER_TICK_MS = 500L
    /** Same loop while no chapters are loaded. */
    private const val IDLE_CHAPTER_TICK_MS = 2_000L
    /** How long the current track plays before its successor is read into the cache. */
    private const val PRELOAD_SETTLE_MS = 10_000L
    /** Delay before a failed preload may be tried again for the same track. */
    private const val PRELOAD_RETRY_MS = 30_000L
    /** Gain ramp around a chapter jump: out, then back in once the new position is playing. */
    private const val FADE_OUT_MS = 120L
    private const val FADE_IN_MS = 180L
    private const val VOLUME_RAMP_STEPS = 8
    /**
     * Fade-out before an interruption pause, see [fadeOutAndPause]. Shorter than the chapter
     * fade-out: the ringtone can start within a few milliseconds of the audio-mode switch, so the
     * gain has to be down before that. Eight ≈10 ms steps are still a ramp, not a click.
     */
    private const val INTERRUPT_FADE_MS = 80L
    /**
     * Time to let AudioTrack drain after pause() before the gain is restored. Restoring it earlier
     * puts a 0 -> 1 step across the last buffered samples, which is the squeak on the first ring.
     */
    private const val INTERRUPT_SETTLE_MS = 120L
    /** Lets the asynchronous seek land before the fade-in starts looking at the player state. */
    private const val TRANSITION_SETTLE_MS = 80L
    /** How long the fade-in waits for the new position to produce audio before giving up. */
    private const val TRANSITION_READY_TIMEOUT_MS = 3_000L
    private const val TRANSITION_POLL_MS = 20L
    /** Normal gain. Any fade that still owns the volume epoch restores exactly this value. */
    private const val FULL_VOLUME = 1f
    /** Minimum distance to the chapter end for a skip to be worth a seek of its own. */
    private const val TRANSITION_LEAD_MS = 700L
    /** How far another device must be ahead before this one stops writing its own position. */
    /** Window in which a pause of our own is still recognised as such. */
    private const val SELF_PAUSE_WINDOW_MS = 500L
    /**
     * How often the transient decision window samples the usages that are actually playing.
     * The window lengths live in [transientGraceWindowMs] so that the trade-off is JVM-testable.
     */
    private const val TRANSIENT_PROBE_MS = 150L
    private const val RESUME_RETRY_START_MS = 1_000L
    private const val RESUME_RETRY_MAX_MS = 8_000L
    /**
     * Total time spent waiting for other apps to release the focus before auto resume gives up.
     * Time spent in a call or waiting for headphones is not counted.
     */
    private const val RESUME_WINDOW_MS = 120_000L
    /**
     * Polling cadence and hard cap while a confirmed alarm / voice message is still sounding.
     * That wait deliberately does not spend RESUME_WINDOW_MS - an alarm can ring for several
     * minutes - but a playback configuration that an OEM never clears must still be bounded so
     * resumeIntent cannot hold the wake lock indefinitely. Long calls and unplugged headphones
     * are handled separately and are not subject to this cap.
     */
    private const val RESUME_PRIORITY_POLL_MS = 2_000L
    private const val RESUME_PRIORITY_MAX_MS = 15 * 60_000L
    /** Timed stream errors retried before switching to the event-driven wait, with backoff. */
    private const val ERROR_RETRY_ATTEMPTS = 6
    private const val ERROR_RETRY_START_MS = 2_000L
    private const val ERROR_RETRY_MAX_MS = 30_000L
    /** Whole error streak: after the timed budget, keep listening this long before giving up. */
    private const val ERROR_RETRY_TOTAL_MS = 600_000L
    /** Low-frequency fallback check while waiting for a network callback that may never arrive. */
    private const val ERROR_RETRY_WAIT_MS = 30_000L
    /** Coalesces onAvailable + onCapabilitiesChanged from a single VPN reconnect. */
    private const val NETWORK_SIGNAL_DEBOUNCE_MS = 300L

    /** Only failures that can heal when the network recovers. */
    private val RETRYABLE_ERROR_CODES = setOf(
      PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      PlaybackException.ERROR_CODE_TIMEOUT
    )
  }
}
