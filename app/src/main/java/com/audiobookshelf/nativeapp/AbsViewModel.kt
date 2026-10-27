package com.audiobookshelf.nativeapp

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.audiobookshelf.nativeapp.util.AppLog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.audiobookshelf.nativeapp.data.ApiException
import com.audiobookshelf.nativeapp.data.ChapterDto
import com.audiobookshelf.nativeapp.data.CollapsedSeriesDto
import com.audiobookshelf.nativeapp.data.LibraryDto
import com.audiobookshelf.nativeapp.data.LibraryItemDto
import com.audiobookshelf.nativeapp.data.ProgressDto
import com.audiobookshelf.nativeapp.data.ProgressSyncWorker
import com.audiobookshelf.nativeapp.data.Session
import com.audiobookshelf.nativeapp.data.ShelfSort
import com.audiobookshelf.nativeapp.data.ShelfDto
import com.audiobookshelf.nativeapp.data.userMessage
import com.audiobookshelf.nativeapp.playback.PlaybackUiState
import com.audiobookshelf.nativeapp.playback.PlayerConnection
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking

/**
 * Snapshot of the book that is actually loaded in the player.
 *
 * The player page must not read the detail page state: opening another book only loads it for
 * display, and doing so used to replace the cover, chapters and duration of the book that was
 * still playing. Everything the player renders comes from this snapshot instead.
 */
data class PlayingBook(
  val itemId: String,
  val title: String,
  val author: String,
  val description: String,
  val chapters: List<ChapterDto>,
  val durationMs: Long,
  /** From the loaded item's media.coverPath; null when that field was unavailable. */
  val hasCover: Boolean? = null
)

/**
 * Book that [AbsViewModel.playItem] is currently fetching.
 *
 * [title] is filled in when the caller already knows the book (it is opened from its detail page),
 * so the player page can label the loading state with the incoming book instead of the outgoing one.
 */
data class PendingPlayback(
  val itemId: String,
  val title: String
)

class AbsViewModel(application: Application) : AndroidViewModel(application) {
  private val container = (application as AbsApp).container
  internal val repository = container.repository

  val player = PlayerConnection(application)

  var session by mutableStateOf<Session?>(null)
    private set

  /**
   * Addresses left behind by "switch user", used to prefill the login form.
   *
   * They live outside [session] because that one is null once the user has been signed out.
   */
  var loginServerUrl by mutableStateOf<String?>(null)
    private set
  var loginLocalServerUrl by mutableStateOf<String?>(null)
    private set
  var initializing by mutableStateOf(true)
    private set
  var libraries by mutableStateOf<List<LibraryDto>>(emptyList())
    private set
  var items by mutableStateOf<List<LibraryItemDto>>(emptyList())
    private set
  var itemsTotal by mutableStateOf(0)
    private set
  /**
   * Per-view bookshelf scroll mirrors, see [ShelfScrollMirror]. Plain objects, not snapshot
   * state: only a lazy state initialiser and effects read them, so writing during composition
   * cannot invalidate a recomposition. Returning from a detail page restores both views; a fresh
   * entry into the shelf starts at the top.
   */
  internal val shelfScroll = ShelfScrollMirror()

  /** Starts (or resumes) a bookshelf session and returns the seeds for both lazy states. */
  internal fun startShelfSession(entryId: String): ShelfScrollMirror.Seed =
    shelfScroll.startSession(entryId)
  /** Fold series into one entry; the server performs the folding (collapseseries=1). */
  var collapseSeries by mutableStateOf(false)
    private set
  /** Grid (false) or list (true) for the shelf; remembered per library. */
  var shelfListView by mutableStateOf(false)
    private set
  /** Server sort field for the shelf; remembered per library. Defaults to title, ascending. */
  var shelfSort by mutableStateOf(ShelfSort.TITLE)
    private set
  var shelfSortDesc by mutableStateOf(false)
    private set
  /** Series opened from a collapsed card. */
  var currentSeries by mutableStateOf<CollapsedSeriesDto?>(null)
    private set
  var seriesItems by mutableStateOf<List<LibraryItemDto>>(emptyList())
    private set
  var seriesLoading by mutableStateOf(false)
    private set
  /**
   * Book currently shown on the detail page. Display only: it is never what the player renders,
   * so browsing an unplayed book cannot hijack the player page.
   */
  var detailItem by mutableStateOf<LibraryItemDto?>(null)
    private set
  var detailChapters by mutableStateOf<List<ChapterDto>>(emptyList())
    private set
  /** True while the detail screen re-syncs over already-rendered local data. */
  var itemSyncing by mutableStateOf(false)
    private set
  /** The sync failed but local data is on screen: the header badge shows 无法连接 instead. */
  var itemSyncFailed by mutableStateOf(false)
    private set
  /** The server no longer knows this item (404): the detail screen shows a confirm dialog. */
  var itemMissing by mutableStateOf(false)
    private set

  fun dismissMissingItem() {
    itemMissing = false
  }
  /** Book loaded in the player; the only source the player page and the mini bar read from. */
  var playingBook by mutableStateOf<PlayingBook?>(null)
    private set
  /**
   * Book being prepared by [playItem], cleared as soon as the session has been handed to the player.
   *
   * The player keeps the outgoing media until then, so without this the player page would show the
   * previous book's cover, chapters and position while the new one is still loading.
   */
  var pendingPlayback by mutableStateOf<PendingPlayback?>(null)
    private set
  var loading by mutableStateOf(false)
    private set
  var loadingMore by mutableStateOf(false)
    private set
  var error by mutableStateOf<String?>(null)
    private set

  /**
   * Failure of the last manual address switch, shown as a dialog on the settings page.
   *
   * Deliberately separate from [error]: the shared banner is only cleared when some operation
   * resets it or the user dismisses it, so a switch refusal parked itself on the settings page
   * indefinitely. A dialog is dismissed with a tap and owns nothing afterwards.
   */
  var switchError by mutableStateOf<String?>(null)
    private set
  var lastLibraryId by mutableStateOf<String?>(null)
    private set
  var searchQuery by mutableStateOf("")
    private set
  var searchResults by mutableStateOf<List<LibraryItemDto>>(emptyList())
    private set
  var searching by mutableStateOf(false)
    private set
  var shelves by mutableStateOf<List<ShelfDto>>(emptyList())
    internal set
  var homeLoading by mutableStateOf(false)
    private set
  /**
   * Library whose shelves are actually on screen; null until a load for one succeeded.
   *
   * An empty [shelves] list means "not loaded yet" just as often as it means "the library is
   * empty". Screens use this to tell the two apart, instead of showing the empty hint for the one
   * frame between navigation and the load that the screen itself starts.
   */
  var homeLoadedLibraryId by mutableStateOf<String?>(null)
    private set
  /** Library whose item list is on screen, same purpose as [homeLoadedLibraryId]. */
  var itemsLoadedLibraryId by mutableStateOf<String?>(null)
    private set
  /** Address currently in use, either the LAN or the remote one. */
  var activeServerUrl by mutableStateOf<String?>(null)
    private set
  /** Playback speed of the item currently loaded, remembered per library item. */
  var playbackSpeed by mutableStateOf(1f)
    private set
  /** Seconds the rewind / forward buttons jump. */
  var skipSeconds by mutableStateOf(15)
    private set
  /** Per-book: seconds skipped at the start of every chapter. */
  var skipIntroSeconds by mutableStateOf(0)
    private set
  /** Per-book: seconds before the chapter end that jump to the next chapter. */
  var skipOutroSeconds by mutableStateOf(0)
    private set
  /** Epoch millis when the sleep timer fires, 0 when it is off. */
  var sleepTimerEndsAt by mutableStateOf(0L)
    private set

  /** Duration picked in the sleep timer sheet in minutes, 0 when it is off. */
  var sleepTimerMinutes by mutableIntStateOf(0)
    private set

  /**
   * Where a book that is staged but not loaded yet would resume from.
   *
   * A cold start only stages the last unfinished book (see [resumeLastBookIfAny]); the player has
   * no media until the user presses play, so the position has to come from here.
   */
  var resumePositionMs by mutableStateOf(0L)
    private set

  /**
   * The item read while staging, kept so the first play does not fetch it a second time.
   *
   * Pressing play on a staged book is already the slowest tap in the app — it has to create a
   * playback session — and re-requesting the item that staging had just read added a second round
   * trip in front of it for nothing.
   */
  private var stagedItem: LibraryItemDto? = null
  /** Library item currently loaded in the player (may be paused). */
  var playingItemId by mutableStateOf<String?>(null)
    private set
  /** Pause on headphone unplug and resume when they come back. */
  var headsetControlEnabled by mutableStateOf(true)
    private set
  /** Resume playback after an interruption the user did not cause. */
  var autoResumeEnabled by mutableStateOf(true)
    private set
  /** Keep playing when apps like DingTalk/WeChat play a notification sound. */
  var ignoreNotificationSounds by mutableStateOf(true)
    private set

  private val audioManager = application.getSystemService(AudioManager::class.java)
  private val runtime = container.runtime

  private val deviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
      if (!headsetControlEnabled || !runtime.waitingForHeadset) return
      // addedDevices only lists what just appeared, and a Bluetooth headset often re-appears in
      // several steps (A2DP first, SCO later). Checking the delta therefore missed the moment the
      // headphones were actually back, leaving waitingForHeadset stuck at true — which made every
      // auto resume path return early and playback never came back. Ask for the full output set.
      val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return
      if (outputs.none { it.isHeadphone() }) return
      // Headphones are back: continue where we stopped
      runtime.waitingForHeadset = false
      player.play()
    }

    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
      if (!headsetControlEnabled) return
      if (removedDevices.none { it.isHeadphone() }) return
      // Unplugging one headset while another is still connected must not pause playback.
      val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return
      if (outputs.any { it.isHeadphone() }) return
      if (!player.state.value.isPlayingOrStarting) return
      // Do not let the speaker take over; wait for the headphones
      runtime.waitingForHeadset = true
      player.pause()
    }
  }

  /** The background image currently displayed; defaults to the bundled one. */
  var backgroundImagePath by mutableStateOf<String?>(
    // Read up front (bounded): the first frame must not wait behind the network probe
    runBlocking {
      val stored = container.appPrefs.backgroundImagePath()
      val resolved = if (container.appPrefs.isBackgroundCustom() && stored != null) {
        stored
      } else {
        DEFAULT_BACKGROUND_URI
      }
      AppLog.i("AbsNetwork", "bg init: custom=${container.appPrefs.isBackgroundCustom()} stored=$stored resolved=$resolved")
      resolved
    }
  )
    private set

  private val connectivity = application.getSystemService(ConnectivityManager::class.java)

  /** Last NET_CAPABILITY_VALIDATED value, see [onCapabilitiesChanged]. */
  private var lastNetworkValidated = false

  /** Identity of the network in use, see [currentNetworkSignature] and the watchdog in [init]. */
  private var networkSignature: NetworkSignature? = null

  /** Set when the address changed while the player was paused, see [reloadPendingAddress]. */
  private var pendingAddressReload = false

  /** Uptime of the last address resolution, used to skip redundant ones, see [onAppForeground]. */
  private var lastResolveAt = 0L

  private data class NetworkSignature(val handle: Long, val wifi: Boolean)

  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      AppLog.i("AbsNetwork", "onAvailable: $network")
      onNetworkEvent("available")
    }

    // A lost network is not a reason to re-resolve: its replacement arrives through onAvailable a
    // moment later. Reacting to the loss alone resolved the address while no network was up yet,
    // which fell back to the remote server and rebuilt a playlist that was still playing fine.
    override fun onLost(network: Network) {
      AppLog.i("AbsNetwork", "onLost: $network")
    }

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
      // Fires continuously while the signal fluctuates. Every call cancelled the pending switch
      // and restarted its timer, so on a weak link the resolution either never ran or ran on every
      // quality sample. Only a change in "this network is actually usable" is meaningful.
      val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      if (validated == lastNetworkValidated) return
      lastNetworkValidated = validated
      AppLog.i("AbsNetwork", "validated=$validated on $network")
      onNetworkEvent("validated=$validated")
    }
  }

  /** Coalesces the burst of callbacks a single toggle produces. */
  private var networkEventJob: Job? = null
  private fun onNetworkEvent(reason: String, networkChanged: Boolean = true) {
    if (networkChanged) repository.noteNetworkChanged()
    networkEventJob?.cancel()
    networkEventJob = viewModelScope.launch {
      // Longer than the old 600 ms: a weak Wi-Fi link flaps for a second or two, and every extra
      // resolution costs a probe timeout plus, potentially, a playlist rebuild.
      delay(1_500)
      AppLog.i("AbsNetwork", "resolving after $reason")
      switchServerIfNeeded()
    }
  }

  /**
   * Re-evaluates the address when the ui comes back to the foreground.
   *
   * A switch that happened while nothing was listening (activity stopped, callback coalesced by the
   * system, process frozen by the OEM power manager) is not replayed on resume, and the app would
   * keep talking to an address it can no longer reach. Cheap: one probe, and only when a LAN
   * address is configured while on Wi-Fi.
   */
  fun onAppForeground() {
    if (initializing || session == null) return
    // Playback keeps reporting progress while this activity is stopped. Reconcile the shelves
    // with the server on the way back; the home screen's own refresh shares the same budget.
    refreshHomeSilently()
    // The picker and other external activities also stop and start this one: a resolution that ran
    // a moment ago is still valid, and repeating it would cost a probe for nothing.
    if (SystemClock.elapsedRealtime() - lastResolveAt < FOREGROUND_RESOLVE_MIN_GAP_MS) return
    onNetworkEvent("foreground", networkChanged = false)
  }

  private fun currentNetworkSignature(): NetworkSignature? {
    val manager = connectivity ?: return null
    val network = manager.activeNetwork ?: return null
    val capabilities = manager.getNetworkCapabilities(network) ?: return null
    return NetworkSignature(
      handle = network.networkHandle,
      wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    )
  }

  private var searchJob: Job? = null
  /** Bumped on every query change, so a superseded search cannot publish anything. */
  private var searchGeneration = 0
  private var homeJob: Job? = null
  internal var homeLibraryId: String? = null
  /** In-flight silent shelf re-read, see [refreshHomeSilently]. */
  private var homeRefreshJob: Job? = null
  private var lastHomeRefreshAt = 0L

  /**
   * Books that just started playing but whose progress the server has not confirmed yet: progress
   * is only reported every 15 s, so a re-fetch alone would leave the shelf frozen for a whole
   * interval (and used to leave it frozen until the next cold start). Insertion order is the
   * playback order, newest first. A pin is released once the server's progress timestamp is at
   * least as new as the moment playback started.
   */
  internal var pinnedRecent = LinkedHashMap<String, PinnedBook>()

  private var itemsPage = 0
  private var itemsLibraryId: String? = null
  /** Bumped on every reset so responses of an outdated sort/library cannot overwrite the list. */
  private var itemsGeneration = 0

  /**
   * Bumped on every [playItem] so a slow request cannot overwrite the book a newer tap chose.
   *
   * Two taps in quick succession (a mis-tap on the wrong book, then the right one) used to end
   * with both loads running; whichever finished last won, which was often the first one.
   */
  private var playGeneration = 0
  private var playJob: Job? = null

  /** Same idea for [openSeries]: only the series opened last may publish its books. */
  private var seriesGeneration = 0

  /** Same idea for [loadItem]: only the detail page opened last may fill itself in. */
  private var detailGeneration = 0

  init {
    player.connect()
    runCatching {
      // Not a NetworkRequest for "internet over wifi or cellular": such a callback only reports
      // networks that *become* available, and the cellular network is already available while
      // Wi-Fi is up. Leaving Wi-Fi therefore produced onLost for the Wi-Fi network (deliberately
      // ignored, see above) and no onAvailable at all, so the address was never re-resolved — the
      // reported "the network change is sometimes not noticed". The default-network callback
      // reports the switch itself, whatever the previous network was.
      connectivity?.registerDefaultNetworkCallback(networkCallback)
    }.onFailure { AppLog.w("AbsNetwork", "register callback failed", it) }
    networkSignature = currentNetworkSignature()
    // Seed the flag with the state at registration: otherwise the first capabilities callback
    // looks like a transition and re-resolves an address that was just resolved by restore().
    lastNetworkValidated = connectivity?.activeNetwork?.let { network ->
      connectivity.getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true
    viewModelScope.launch {
      // Local prefs first: they must not wait behind the (potentially slow) network probe
      headsetControlEnabled = container.appPrefs.isHeadsetControlEnabled()
      skipSeconds = container.appPrefs.skipSeconds()
      autoResumeEnabled = container.appPrefs.isAutoResumeEnabled()
      ignoreNotificationSounds = container.appPrefs.isIgnoreNotificationSoundsEnabled()
      val stored = container.appPrefs.backgroundImagePath()
      if (container.appPrefs.isBackgroundCustom() && stored != null) {
        backgroundImagePath = stored
      } else if (backgroundImagePath != DEFAULT_BACKGROUND_URI) {
        backgroundImagePath = DEFAULT_BACKGROUND_URI
      }
      AppLog.i("AbsNetwork", "bg refresh: custom=${container.appPrefs.isBackgroundCustom()} stored=$stored")
      session = repository.restore()
      lastResolveAt = SystemClock.elapsedRealtime()
      activeServerUrl = repository.activeServerUrl
      if (session != null) {
        // A previous run may have left undelivered progress on disk (offline, killed process,
        // expired token). Drain it as soon as there is a valid session again.
        ProgressSyncWorker.enqueue(container.context)
        bootstrapLibraries()
        resumeLastBookIfAny()
      } else {
        // Nothing signed in: a previous "switch user" may have left the addresses behind
        val kept = container.sessionStore.savedConnection()
        loginServerUrl = kept.serverUrl
        loginLocalServerUrl = kept.localServerUrl
      }
      initializing = false
    }
    viewModelScope.launch {
      // The sleep timer is owned by the service (it is what pauses playback), so this only mirrors
      // the stored deadline: setting one from here, letting it run out there, or coming back to a
      // timer that was set before the app was closed all end up in the same state.
      container.appPrefs.observeSleepTimer().collect { timer ->
        sleepTimerEndsAt = timer.endsAtMs
        sleepTimerMinutes = timer.minutes
        // A deadline that ran out while nothing was alive to enforce it (process killed, device
        // off) is stale: left in place, the service would see it as "already expired" and pause
        // playback the moment the next session starts.
        if (timer.endsAtMs > 0L && timer.endsAtMs <= System.currentTimeMillis()) {
          container.appPrefs.clearSleepTimer()
        }
      }
    }
    viewModelScope.launch {
      // A progress write is already in the local cache, and that cache is what the shelf order is
      // derived from. Reordering locally is enough: the server that just accepted the write has no
      // newer information than we do, so re-fetching /personalized here would be a round trip for
      // identical data every 15 s. Changes made elsewhere are picked up on navigation/foreground.
      repository.progressUpdates.collect { itemId ->
        if (repository.progressFor(itemId)?.isFinished == true) pinnedRecent.remove(itemId)
        publishShelves(reorderContinueListening(shelves))
      }
    }
    viewModelScope.launch {
      // A 401 from anywhere (a shelf refresh, a progress write in the playback service, ...)
      // has already cleared the stored session in the repository. Mirror it here, prefill the
      // login form from the kept addresses, and let AbsNav route to the login page. Without this
      // the listener stayed on a frozen home screen with no way back to the credentials.
      repository.authEvents.collect {
        session = null
        activeServerUrl = null
        val kept = container.sessionStore.savedConnection()
        loginServerUrl = kept.serverUrl
        loginLocalServerUrl = kept.localServerUrl
        error = "登录已过期，请重新登录"
      }
    }
    viewModelScope.launch {
      // Safety net for a switch no callback reported: the callbacks are registered per process and
      // can be coalesced, delayed or missed entirely while the device dozes. Comparing the identity
      // of the default network costs nothing (no I/O) and catches whatever slipped through.
      while (true) {
        delay(NETWORK_WATCHDOG_MS)
        if (repository.session == null) continue
        val signature = currentNetworkSignature() ?: continue
        val previous = networkSignature
        // Track every sample, not just the ones that trigger a resolution: a change the callback
        // already handled must not be reported a second time here.
        networkSignature = signature
        if (previous == null || previous == signature) continue
        AppLog.i("AbsNetwork", "watchdog: network is now $signature (was $previous)")
        onNetworkEvent("watchdog")
      }
    }
    runCatching {
      audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }
    player.playWhenReadyListener = { playWhenReady, reason ->
      handlePlayWhenReadyChanged(playWhenReady, reason)
    }
  }

  /**
   * The reason comes from the session player, so notification and headset button pauses are
   * USER_REQUEST (intentional) while focus loss and noisy events are real interruptions.
   */
  private fun handlePlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    if (playWhenReady) {
      runtime.waitingForHeadset = false
      // Playback can be resumed on a playlist built for an address the app is no longer on (a
      // paused book carried across a network switch: reloadAt refuses to rebuild while paused, so
      // the switch only recorded that it is needed).
      reloadPendingAddress()
      return
    }
    // Focus loss is handled by the service (it owns the AudioFocusRequest now).
    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
      runtime.waitingForHeadset = true
    }
  }

  fun updateHeadsetControl(enabled: Boolean) {
    headsetControlEnabled = enabled
    viewModelScope.launch { container.appPrefs.setHeadsetControlEnabled(enabled) }
  }

  fun updateIgnoreNotificationSounds(enabled: Boolean) {
    ignoreNotificationSounds = enabled
    viewModelScope.launch { container.appPrefs.setIgnoreNotificationSounds(enabled) }
  }

  fun updateAutoResume(enabled: Boolean) {
    autoResumeEnabled = enabled
    viewModelScope.launch { container.appPrefs.setAutoResumeEnabled(enabled) }
  }

  /** Play/pause from the ui: marks the change as intentional so it is not auto resumed. */
  fun togglePlayPause() {
    // A book that is only staged has no media in the player yet (see [resumeLastBookIfAny]): the
    // first tap is what creates the session, so it must not be swallowed by a controller that has
    // nothing to play.
    val staged = playingBook
    if (staged != null && !player.state.value.hasMedia) {
      // Ignore a tap while that session is already being created. The button is disabled for the
      // duration, but a tap can still land on the frame before the request is registered, and
      // restarting it would only make the wait longer.
      if (pendingPlayback == null) {
        playItem(staged.itemId, startAtMs = resumePositionMs.takeIf { it > 0L })
      }
      return
    }
    player.togglePlayPause()
  }

  /**
   * Selects a chapter and starts playing it.
   *
   * A chapter tap — in the player's chapter sheet, on the previous/next buttons, or on a detail
   * page whose book is already loaded — means "listen from here". It must start audio even when
   * the book was paused; seeking alone preserved `playWhenReady` and left the book silent. A cold
   * start only stages the last book without loading media (see [resumeLastBookIfAny]), so the same
   * tap has to create the session at that chapter instead of seeking an empty playlist.
   */
  fun playChapter(chapter: ChapterDto) {
    AppLog.tapTrace("PLAY_CHAPTER", "title=${chapter.title} start=${chapter.start}")
    when (chapterJumpFor(
      hasMedia = player.state.value.hasMedia,
      hasStagedBook = playingBook != null,
      loadingSession = pendingPlayback != null
    )) {
      ChapterJump.SeekAndPlay -> player.seekToAbsoluteAndPlay(chapterStartWithIntro(chapter))
      ChapterJump.LoadSession -> playingBook?.let {
        playItem(it.itemId, startAtMs = chapterStartWithIntro(chapter))
      }
      ChapterJump.Ignore -> Unit
    }
  }

  /**
   * Re-evaluates the server address when the network changes (wifi <-> cellular).
   * When the address actually changes while media is loaded, the playlist is rebuilt against the
   * new address and resumes at the current position, so playback keeps going.
   */
  private suspend fun switchServerIfNeeded() {
    AppLog.i("AbsNetwork", "switchServerIfNeeded: session=${repository.session != null}")
    if (repository.session == null) return
    val before = repository.activeServerUrl
    val after = repository.resolveActiveServer(true) ?: return
    lastResolveAt = SystemClock.elapsedRealtime()
    activeServerUrl = after
    val playback = player.state.value
    // The api can already be on the new address while the loaded playlist still holds urls of the
    // old one — that is what a cold start with an existing session looks like, and no network
    // event fires for it. Comparing the two is the only way to notice.
    val staleMedia = player.isLoadedFrom(after) == false
    AppLog.i(
      "AbsNetwork",
      "switchServerIfNeeded: $before -> $after staleMedia=$staleMedia playing=${playback.isPlaying} " +
        "pwr=${playback.playWhenReady} state=${playback.playbackState}"
    )

    if (after == before && !staleMedia) {
      // Same address. A network bounce can still kill the in-flight stream, but only rebuild when
      // playback was intended and has actually stopped: touching a running stream discarded the
      // buffered audio and was the start of the "playback closed itself" chain.
      reloadIfStalled(playback)
      return
    }

    // The old address is unreachable now (left the Wi-Fi), so a stream on it dies anyway: rebuild
    // against the new one. reloadAt refuses to run while paused, see PlayerConnection.
    if (playback.hasMedia) {
      val positionMs = player.currentAbsolutePositionMs()
      AppLog.i("AbsNetwork", "address changed, rebuilding at $positionMs")
      if (!player.reloadAt(positionMs)) {
        // Paused: rebuilding would tear the session down, so the rebuild waits for the next play
        pendingAddressReload = true
      }
    }
  }

  /**
   * Rebuilds the loaded playlist on the current address the next time playback starts.
   *
   * Rebuilding while paused resets the player to STATE_IDLE, and Media3 treats "idle and not
   * playing" as nothing worth keeping the session alive for — the notification would disappear.
   */
  private fun reloadPendingAddress() {
    if (!pendingAddressReload) return
    pendingAddressReload = false
    val active = repository.activeServerUrl ?: return
    if (player.isLoadedFrom(active) != false) return
    val playback = player.state.value
    if (!playback.hasMedia || !playback.playWhenReady) return
    val positionMs = player.currentAbsolutePositionMs()
    AppLog.i("AbsNetwork", "resuming on a stale address, rebuilding at $positionMs")
    player.reloadAt(positionMs)
  }

  /** Rebuilds the stream only when playback was intended and audio has stopped coming out. */
  private fun reloadIfStalled(playback: PlaybackUiState) {
    if (!playback.hasMedia || !playback.playWhenReady || playback.isPlaying) return
    if (playback.playbackState != Player.STATE_BUFFERING && playback.playbackState != Player.STATE_IDLE) return
    val positionMs = player.currentAbsolutePositionMs()
    AppLog.i("AbsNetwork", "stream stalled at $positionMs, rebuilding")
    player.reloadAt(positionMs)
  }

  /**
   * Loads the library list before the first screen is chosen so the remembered library can be
   * validated against the server: a library deleted on the server must not be auto-entered, and a
   * server with a single library skips the picker entirely.
   */
  private suspend fun bootstrapLibraries() {
    val remembered = container.sessionStore.getLastLibraryId()
    // Never block the splash for the full connect timeout when the server is unreachable
    val loaded = try {
      withTimeoutOrNull(6000) { repository.libraries() }
    } catch (e: Exception) {
      null
    }
    if (loaded == null) {
      error = "连接服务器失败，请检查网络"
      lastLibraryId = null
      return
    }
    libraries = loaded

    val resolved = when {
      libraries.size == 1 -> libraries.first().id
      remembered != null && libraries.any { it.id == remembered } -> remembered
      else -> null
    }

    lastLibraryId = resolved
    if (resolved != null) {
      container.sessionStore.setLastLibraryId(resolved)
    } else if (remembered != null) {
      // The remembered library no longer exists on the server
      container.sessionStore.clearLastLibraryId()
    }
  }

  /**
   * Switches the active library.
   *
   * Selecting the library that is already open is a no-op: the screen keeps its content and no
   * request is made. A real switch drops the previous library's shelves and books synchronously
   * and starts loading immediately, so the next screen shows a loading state instead of the old
   * library's content until the request returns.
   */
  fun rememberLibrary(libraryId: String) {
    if (libraryId.isBlank() || lastLibraryId == libraryId) return
    lastLibraryId = libraryId
    clearLibraryContent()
    loadHome(libraryId)
    viewModelScope.launch { container.sessionStore.setLastLibraryId(libraryId) }
  }

  /** Forgets everything that belongs to the previously selected library. */
  private fun clearLibraryContent() {
    homeJob?.cancel()
    homeJob = null
    homeRefreshJob?.cancel()
    homeRefreshJob = null
    lastHomeRefreshAt = 0L
    pinnedRecent.clear()
    homeLibraryId = null
    homeLoadedLibraryId = null
    shelves = emptyList()
    items = emptyList()
    itemsTotal = 0
    itemsPage = 0
    itemsLibraryId = null
    itemsLoadedLibraryId = null
    itemsGeneration++
    loadingMore = false
    // The in-flight request drops its result, so it will not reset these itself
    loading = false
    homeLoading = false
  }

  fun onSearchQueryChanged(libraryId: String, query: String) {
    searchQuery = query
    searchJob?.cancel()
    val generation = ++searchGeneration
    if (query.isBlank()) {
      searchResults = emptyList()
      searching = false
      return
    }
    // Set before the debounce rather than after it: the 350 ms wait already is a wait for the
    // user, and with the flag still false the screen reported "nothing found" for a request that
    // had not even been sent yet.
    searching = true
    error = null
    searchJob = viewModelScope.launch {
      try {
        delay(350)
        val response = repository.search(libraryId, query)
        if (generation != searchGeneration) return@launch
        searchResults = (response.book + response.podcast).map { it.libraryItem }.filter { it.id.isNotBlank() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == searchGeneration) error = e.userMessage()
      } finally {
        // Only the newest search owns the flag; a cancelled one clearing it would hide the
        // spinner of the search that is still running
        if (generation == searchGeneration) searching = false
      }
    }
  }

  fun clearSearch() {
    searchJob?.cancel()
    searchGeneration++
    searchQuery = ""
    searchResults = emptyList()
    searching = false
  }

  /** Personalized home shelves (continue listening, recently added, discover, ...). */
  fun loadHome(libraryId: String, force: Boolean = false) {
    if (libraryId.isBlank()) return
    val sameLibrary = homeLibraryId == libraryId
    // Same library with content on screen (or a load in flight): keep the list, refresh in place
    if (!force && sameLibrary && (shelves.isNotEmpty() || homeJob?.isActive == true)) {
      // A book that started playing on another screen is already pinned locally, so refreshing
      // here only has to reconcile server-side changes (another device, a book finished). Like the
      // original client this happens on every mount of the home screen, but throttled: navigating
      // back and forth must not fire the same two requests on every tap.
      publishShelves(reorderContinueListening(shelves))
      refreshHomeSilently()
      return
    }
    if (!sameLibrary) {
      // Another library's shelves must not stay on screen while this one loads
      shelves = emptyList()
    }
    homeJob?.cancel()
    homeLibraryId = libraryId
    // 同步置位，避免首帧出现"媒体库还是空的"空态闪烁
    homeLoading = true
    homeJob = viewModelScope.launch {
      error = null
      try {
        // Progress is not part of the shelf payload, refresh it so the cards show bars
        repository.refreshProgress()
        val loaded = repository.personalized(libraryId)
        // A newer switch may have landed while this request was in flight
        if (homeLibraryId == libraryId) {
          publishShelves(mergeServerShelves(loaded))
          homeLoadedLibraryId = libraryId
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (homeLibraryId == libraryId) error = e.userMessage()
      } finally {
        if (homeLibraryId == libraryId) {
          homeLoading = false
          // A full load just fetched exactly what a silent refresh would. Without this the first
          // navigation back to the home screen duplicated the same two requests immediately.
          lastHomeRefreshAt = SystemClock.elapsedRealtime()
        }
      }
    }
  }

  fun clearError() {
    error = null
  }

  fun clearSwitchError() {
    switchError = null
  }

  /**
   * Re-reads /api/me and the personalized shelves without clearing what is already on screen.
   *
   * Called only on explicit boundaries — entering the home screen and returning to the foreground
   * — and throttled, because those two often fire within milliseconds of each other. Progress
   * writes during playback do NOT come here: they are already mirrored into the local cache and
   * only need a local reorder, not a network round trip.
   */
  private fun refreshHomeSilently() {
    val libraryId = homeLibraryId ?: return
    if (homeLoadedLibraryId != libraryId || homeJob?.isActive == true) return
    if (homeRefreshJob?.isActive == true) return
    val now = SystemClock.elapsedRealtime()
    if (!shouldRefreshHome(lastHomeRefreshAt, now, HOME_REFRESH_MIN_GAP_MS)) return
    lastHomeRefreshAt = now
    AppLog.i("AbsHome", "refresh shelves for $libraryId")
    homeRefreshJob = viewModelScope.launch {
      try {
        repository.refreshProgress()
        val loaded = repository.personalized(libraryId)
        // A library switch (or logout) may have landed while the request was in flight
        if (homeLibraryId == libraryId) publishShelves(mergeServerShelves(loaded))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // A silent refresh must not put an error banner over a perfectly usable shelf.
        AppLog.w("AbsHome", "silent home refresh failed", e)
      }
    }
  }


  /** True once the shelves of [libraryId] have been loaded at least once, empty or not. */
  fun isHomeLoaded(libraryId: String): Boolean = homeLoadedLibraryId == libraryId

  /** True once the item list of [libraryId] has been loaded at least once, empty or not. */
  fun isShelfLoaded(libraryId: String): Boolean = itemsLoadedLibraryId == libraryId

  fun login(server: String, localServer: String?, username: String, password: String, onSuccess: () -> Unit) {
    viewModelScope.launch {
      loading = true
      error = null
      try {
        session = repository.login(server, localServer, username, password)
        activeServerUrl = repository.activeServerUrl
        // The form is behind us now, so the switch-user prefill has served its purpose
        loginServerUrl = null
        loginLocalServerUrl = null
        // A server with a single library skips the picker
        libraries = try {
          repository.libraries()
        } catch (e: Exception) {
          emptyList()
        }
        val single = libraries.singleOrNull()
        lastLibraryId = single?.id
        if (single != null) container.sessionStore.setLastLibraryId(single.id)
        // Re-login after a 401: deliver the writes that were queued while the token was dead.
        ProgressSyncWorker.enqueue(container.context)
        onSuccess()
      } catch (e: Exception) {
        error = e.userMessage()
      } finally {
        loading = false
      }
    }
  }

  /** Saves the optional LAN address from the settings screen and switches to it when reachable. */
  fun updateLocalServerUrl(url: String?) {
    viewModelScope.launch {
      repository.updateLocalServerUrl(url)
      session = repository.session
      activeServerUrl = repository.activeServerUrl
    }
  }

  /**
   * Switches to [url] because the user picked it in the settings screen.
   *
   * The switch is gated on a reachability probe: an address that does not answer is refused with
   * a message where the user tapped, instead of being adopted and only failing later through
   * shelf requests. The automatic choice is only as good as its probe, and when it picks wrong
   * there was no way out from inside the app — this is that way out, but only for an address
   * that actually answers.
   */
  fun switchToServer(url: String) {
    if (url.isBlank()) return
    viewModelScope.launch {
      val before = repository.activeServerUrl
      if (!repository.probeServer(url)) {
        AppLog.i("AbsNetwork", "manual switch to $url refused: probe failed")
        switchError = "无法连接到 $url，请确认地址正确且设备在同一网络"
        return@launch
      }
      if (!repository.forceActiveServer(url)) return@launch
      activeServerUrl = repository.activeServerUrl
      AppLog.i("AbsNetwork", "manual switch: $before -> $activeServerUrl")
      // A paused player is rebuilt on the next play instead: rebuilding it now would reset it to
      // STATE_IDLE, and Media3 tears the session and its notification down for an idle player.
      val playback = player.state.value
      if (playback.hasMedia && !player.reloadAt(player.currentAbsolutePositionMs())) {
        pendingAddressReload = true
      }
      // Everything on screen was fetched from the previous address. Reloading it is also the only
      // way to find out whether the address the user picked actually answers: the switch itself
      // does not probe, on purpose.
      val libraryId = lastLibraryId
      if (libraryId != null) {
        loadHome(libraryId, force = true)
        if (itemsLibraryId == libraryId) loadItems(libraryId, reset = true)
      } else {
        loadLibraries()
      }
    }
  }

  /** Applies a speed to the book in the player and remembers it for that book only. */
  fun setSpeed(speed: Float) {
    val clamped = (kotlin.math.round(speed * 10f) / 10f).coerceIn(0.5f, 3.0f)
    playbackSpeed = clamped
    player.setSpeed(clamped)
    val itemId = playingBook?.itemId ?: return
    viewModelScope.launch { container.playbackPrefs.setSpeed(itemId, clamped) }
  }

  /** Copies a picked image into app storage so it survives restarts and permission loss. */
  fun setBackgroundImageFromUri(uri: Uri) {
    viewModelScope.launch {
      val old = backgroundImagePath
      val path = withContext(Dispatchers.IO) {
        runCatching {
          // Unique name per pick: reusing one file kept the old image in Coil's cache, so
          // selecting a new picture appeared to do nothing until "clear" was pressed first.
          val file = File(container.context.filesDir, "global_background_${System.currentTimeMillis()}.img")
          container.context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
          }
          old?.takeIf { it.startsWith("/") }?.let { File(it).delete() }
          container.appPrefs.setBackgroundCustom(true)
          file.absolutePath
        }.getOrNull()
      }
      if (path != null) {
        backgroundImagePath = path
        container.appPrefs.setBackgroundImagePath(path)
      }
    }
  }

  /** Resets the background to the bundled image. */
  fun restoreDefaultBackground() {
    viewModelScope.launch {
      val old = backgroundImagePath
      container.appPrefs.setBackgroundCustom(false)
      container.appPrefs.setBackgroundImagePath(DEFAULT_BACKGROUND_URI)
      old?.takeIf { it.startsWith("/") }?.let { File(it).delete() }
      backgroundImagePath = DEFAULT_BACKGROUND_URI
    }
  }

  /** Starts a sleep timer; 0 minutes cancels it. */
  fun setSleepTimer(minutes: Int) {
    val clamped = minutes.coerceIn(0, SLEEP_TIMER_MAX_MINUTES)
    val endsAt = if (clamped <= 0) 0L else System.currentTimeMillis() + clamped * 60_000L
    // Local echo first: the ring and the panel label must follow the tap, not the disk write.
    sleepTimerEndsAt = endsAt
    sleepTimerMinutes = clamped
    viewModelScope.launch {
      if (clamped <= 0) {
        container.appPrefs.clearSleepTimer()
      } else {
        container.appPrefs.setSleepTimer(endsAt, clamped)
      }
    }
  }

  fun logout(onDone: () -> Unit) = endSession(onDone, keepConnection = false)

  /**
   * Same as [logout], except the server and LAN addresses survive and are offered again on the
   * login form, so switching accounts does not mean retyping the connection.
   */
  fun switchUser(onDone: () -> Unit) = endSession(onDone, keepConnection = true)

  private fun endSession(onDone: () -> Unit, keepConnection: Boolean) {
    // Invalidate in-flight detail work before the first suspend point. logout()/switchUser() are
    // suspending, so a refresh completing during them used to publish the outgoing account's
    // detail (and its "syncing"/"deleted" flags) onto the view model that now backs the login
    // screen. Bumping the generation makes loadItem's guard drop that result.
    detailGeneration++
    itemSyncing = false
    itemSyncFailed = false
    itemMissing = false
    viewModelScope.launch {
      player.clear()
      if (keepConnection) {
        val kept = repository.switchUser()
        loginServerUrl = kept.serverUrl
        loginLocalServerUrl = kept.localServerUrl
      } else {
        repository.logout()
        loginServerUrl = null
        loginLocalServerUrl = null
      }
      session = null
      libraries = emptyList()
      clearLibraryContent()
      detailItem = null
      detailChapters = emptyList()
      playingBook = null
      runtime.chapters = emptyList()
      runtime.chaptersMediaId = null
      runtime.skipIntroSeconds = 0
      runtime.skipOutroSeconds = 0
      pendingPlayback = null
      pendingAddressReload = false
      lastLibraryId = null
      playingItemId = null
      resumePositionMs = 0L
      stagedItem = null
      currentSeries = null
      seriesItems = emptyList()
      // Nothing is playing any more, so a countdown would have nothing to pause
      setSleepTimer(0)
      onDone()
    }
  }

  fun loadLibraries(onLoaded: (() -> Unit)? = null) {
    // 同步置位，避免"没有可用媒体库"空态闪现（与 loadItems/loadHome 同款）
    loading = true
    viewModelScope.launch {
      loading = true
      error = null
      try {
        libraries = repository.libraries()
        onLoaded?.invoke()
      } catch (e: Exception) {
        error = e.userMessage()
      } finally {
        loading = false
      }
    }
  }

  fun loadItems(libraryId: String, reset: Boolean = true) {
    if (reset) {
      // Mark synchronously: clearing the list before the flag would flash the empty state
      loading = true
      itemsPage = 0
      itemsLibraryId = libraryId
      items = emptyList()
      itemsGeneration++
    }
    if (itemsLibraryId != libraryId) return
    val generation = itemsGeneration
    viewModelScope.launch {
      if (reset) loading = true else loadingMore = true
      error = null
      try {
        val response = repository.items(
          libraryId = libraryId,
          page = itemsPage,
          collapseSeries = collapseSeries,
          sort = shelfSort.apiValue,
          desc = shelfSortDesc
        )
        // The library, the sort or the folding changed while this request was in flight
        if (itemsLibraryId != libraryId || itemsGeneration != generation) return@launch
        val page = response.results
        // The server pages by offset over a list that can change between requests (a book being
        // added, series folding re-evaluating), so the same item can arrive on two pages. Lazy
        // grids key on the id: a duplicate crashes the list with "key was already used".
        items = (if (reset) page else items + page).distinctBy { it.id }
        // A page that brought nothing new while the server still claims more entries would keep
        // loadMoreItems asking for the same page forever: the loaded count never reaches the total.
        itemsTotal = if (!reset && page.isEmpty()) items.size else response.total
        itemsLoadedLibraryId = libraryId
        itemsPage += 1
      } catch (e: Exception) {
        if (itemsLibraryId == libraryId && itemsGeneration == generation) {
          error = e.userMessage()
        }
      } finally {
        if (itemsLibraryId == libraryId && itemsGeneration == generation) {
          loading = false
          loadingMore = false
        }
      }
    }
  }

  fun loadMoreItems() {
    val libraryId = itemsLibraryId ?: return
    if (loading || loadingMore || items.size >= itemsTotal) return
    loadItems(libraryId, reset = false)
  }

  /**
   * Opens a library's shelf, restoring the view mode and folding state that were saved for it.
   * The list is only fetched again when the library changed, it is still empty, or the folding
   * state differs from what is on screen.
   */
  fun openShelf(libraryId: String) {
    if (libraryId.isBlank()) return
    viewModelScope.launch {
      val listView = container.appPrefs.shelfListView(libraryId)
      val collapsed = container.appPrefs.shelfCollapseSeries(libraryId)
      val sort = ShelfSort.fromApiValue(container.appPrefs.shelfSort(libraryId))
      val sortDesc = container.appPrefs.shelfSortDesc(libraryId)
      val needsReload = itemsLibraryId != libraryId || items.isEmpty() ||
        collapseSeries != collapsed || shelfSort != sort || shelfSortDesc != sortDesc
      shelfListView = listView
      collapseSeries = collapsed
      shelfSort = sort
      shelfSortDesc = sortDesc
      if (needsReload) loadItems(libraryId, reset = true)
    }
  }

  /** Sort field and direction; stored per library and refetched from the first page. */
  fun updateShelfSort(sort: ShelfSort, descending: Boolean) {
    if (shelfSort == sort && shelfSortDesc == descending) return
    shelfSort = sort
    shelfSortDesc = descending
    val libraryId = itemsLibraryId ?: lastLibraryId ?: return
    viewModelScope.launch {
      container.appPrefs.setShelfSort(libraryId, sort.apiValue)
      container.appPrefs.setShelfSortDesc(libraryId, descending)
    }
    loadItems(libraryId, reset = true)
  }

  /** Grid or list view; stored per library so each shelf remembers its own. */
  fun updateSkipSeconds(seconds: Int) {
    val clamped = seconds.coerceIn(5, 120)
    if (skipSeconds == clamped) return
    skipSeconds = clamped
    viewModelScope.launch { container.appPrefs.setSkipSeconds(clamped) }
  }

  /** Stores the intro/outro skip of the book currently loaded; both are per library item. */
  fun saveSkipIntroOutro(introSeconds: Int, outroSeconds: Int) {
    val intro = introSeconds.coerceIn(0, 120)
    val outro = outroSeconds.coerceIn(0, 120)
    skipIntroSeconds = intro
    skipOutroSeconds = outro
    // The service reads these on every tick, so the new values apply without reloading the book
    runtime.skipIntroSeconds = intro
    runtime.skipOutroSeconds = outro
    val itemId = playingItemId ?: return
    viewModelScope.launch {
      container.playbackPrefs.setSkipIntro(itemId, intro)
      container.playbackPrefs.setSkipOutro(itemId, outro)
    }
  }

  /** Chapter start plus the configured intro skip, clamped inside the chapter. */
  fun chapterStartWithIntro(chapter: ChapterDto): Long {
    val startMs = (chapter.start * 1000).toLong()
    val endMs = (chapter.end * 1000).toLong()
    return (startMs + skipIntroSeconds * 1000L).coerceAtMost(endMs)
  }

  fun updateShelfListView(listView: Boolean) {
    if (shelfListView == listView) return
    shelfListView = listView
    val libraryId = itemsLibraryId ?: return
    viewModelScope.launch { container.appPrefs.setShelfListView(libraryId, listView) }
  }

  /**
   * Turns series folding on or off. The server does the folding, so the list has to be fetched
   * again from the first page: the previous page numbering no longer matches.
   */
  fun updateCollapseSeries(enabled: Boolean) {
    if (collapseSeries == enabled) return
    collapseSeries = enabled
    val libraryId = itemsLibraryId ?: lastLibraryId ?: return
    viewModelScope.launch { container.appPrefs.setShelfCollapseSeries(libraryId, enabled) }
    loadItems(libraryId, reset = true)
  }

  /** Opens a collapsed series: its books are fetched individually (there is no batch endpoint). */
  fun openSeries(series: CollapsedSeriesDto) {
    val generation = ++seriesGeneration
    currentSeries = series
    seriesItems = emptyList()
    seriesLoading = true
    viewModelScope.launch {
      val loaded = coroutineScope {
        series.libraryItemIds.map { id ->
          async { runCatching { repository.item(id) }.getOrNull() }
        }.awaitAll().filterNotNull()
      }
      // Another series was opened while these requests were in flight: its books are the ones
      // that belong on screen, not the ones that happened to answer last.
      if (generation != seriesGeneration) return@launch
      // Keep the order the server reported for the series
      seriesItems = loaded
      seriesLoading = false
    }
  }

  fun closeSeries() {
    seriesGeneration++
    currentSeries = null
    seriesItems = emptyList()
    seriesLoading = false
  }

  fun loadItem(itemId: String) {
    AppLog.tapTrace("LOAD_ITEM", itemId)
    val generation = ++detailGeneration
    itemSyncing = false
    itemSyncFailed = false
    itemMissing = false
    // A previous book may have left an error banner behind. Clear it on every entry, including
    // the cached path, otherwise a stale message sits above perfectly good local data.
    error = null
    // Render the local mirror first so the page opens instantly: a memory hit is synchronous,
    // the disk copy is read inside the coroutine below. The blocking loader only applies while
    // there is nothing to show; as soon as local data is on screen, `loading` is released so the
    // play button stays enabled while the network refresh runs.
    val cached = repository.cachedItem(itemId)
    if (cached != null) {
      detailItem = cached
      detailChapters = cached.media.chapters
      itemSyncing = true
      loading = false
    } else {
      loading = true
      detailItem = null
      detailChapters = emptyList()
    }
    viewModelScope.launch {
      try {
        if (cached == null) {
          val local = repository.cachedItemFromDisk(itemId)
          if (generation == detailGeneration && local != null) {
            detailItem = local
            detailChapters = local.media.chapters
            itemSyncing = true
            loading = false
          }
        }
        val item = repository.item(itemId)
        if (generation != detailGeneration) return@launch
        detailItem = item
        detailChapters = item.media.chapters
        itemSyncing = false
        itemSyncFailed = false
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == detailGeneration) {
          when {
            // The book was deleted on the server: the dialog offers the way back.
            e is ApiException.Http && e.code == 404 -> {
              itemSyncing = false
              itemMissing = true
              // Keep the mirror from resurrecting a deleted book on every later open.
              repository.evictCachedItem(itemId)
            }
            // Local data is on screen: keep it rendered and only flag the sync as failed.
            detailItem?.id == itemId -> {
              itemSyncing = false
              itemSyncFailed = true
            }
            else -> error = e.userMessage()
          }
        }
      } finally {
        if (generation == detailGeneration) loading = false
      }
    }
  }

  /**
   * Stages the last unfinished book so the mini bar appears on a cold start and one tap on play
   * continues it.
   *
   * This reads the item and its progress but deliberately does not create a playback session
   * (POST /api/play) nor build a playlist. Doing that at launch opened a stream that immediately
   * buffered, told the server a playback had started that might never happen, and pushed the first
   * screen behind two round trips. The session is created when the user actually presses play.
   */
  private suspend fun resumeLastBookIfAny() {
    if (player.state.value.hasMedia) return
    val last = repository.lastUnfinishedProgress() ?: return
    val itemId = last.libraryItemId ?: return
    val item = runCatching { repository.item(itemId) }.getOrNull() ?: return
    stageBook(item, (last.currentTime * 1000).toLong())
  }

  /** Publishes a book as "ready to play" without loading anything into the player. */
  private suspend fun stageBook(item: LibraryItemDto, startAtMs: Long) {
    val metadata = item.media.metadata
    val chapters = item.media.chapters
    val durationSeconds = when {
      item.media.duration > 0 -> item.media.duration
      chapters.isNotEmpty() -> chapters.last().end
      else -> 0.0
    }
    playingBook = PlayingBook(
      itemId = item.id,
      title = metadata.title?.takeIf { it.isNotBlank() } ?: "未命名",
      author = metadata.displayAuthor().orEmpty(),
      description = metadata.description?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty(),
      chapters = chapters,
      durationMs = (durationSeconds * 1000).toLong(),
      hasCover = item.hasCover()
    )
    playingItemId = item.id
    resumePositionMs = startAtMs.coerceAtLeast(0L)
    stagedItem = item
    playbackSpeed = container.playbackPrefs.getSpeed(item.id) ?: 1f
    skipIntroSeconds = container.playbackPrefs.getSkipIntro(item.id)
    skipOutroSeconds = container.playbackPrefs.getSkipOutro(item.id)
    runtime.chaptersMediaId = item.id
    runtime.chapters = chapters
    runtime.skipIntroSeconds = skipIntroSeconds
    runtime.skipOutroSeconds = skipOutroSeconds
  }

  fun playItem(itemId: String, startAtMs: Long? = null, autoPlay: Boolean = true) {
    AppLog.tapTrace("PLAY_ITEM", "id=$itemId startAt=$startAtMs autoPlay=$autoPlay")
    val generation = ++playGeneration
    // A newer tap wins outright: the older request would otherwise finish later and load the book
    // the user has already moved on from.
    playJob?.cancel()
    playJob = viewModelScope.launch {
      loading = true
      error = null
      pendingPlayback = PendingPlayback(
        itemId = itemId,
        title = detailItem?.takeIf { it.id == itemId }?.media?.metadata?.title.orEmpty()
      )
      try {
        // Reuses what staging already read, so a cold-start play costs one request (the session)
        // instead of two. The session response overrides the chapters and the duration anyway.
        val item = detailItem?.takeIf { it.id == itemId }
          ?: stagedItem?.takeIf { it.id == itemId }
          ?: repository.item(itemId)
        val playbackSession = repository.playbackSession(itemId)
        if (generation != playGeneration) return@launch
        val itemChapters = playbackSession.chapters.ifEmpty { item.media.chapters }
        val durationSeconds = if (playbackSession.duration > 0) playbackSession.duration else item.media.duration
        val startSeconds = when {
          startAtMs != null -> startAtMs / 1000.0
          playbackSession.currentTime > 0 -> playbackSession.currentTime
          else -> item.userMediaProgress?.currentTime ?: 0.0
        }
        val bookDurationMs = (durationSeconds * 1000).toLong()
        // The player page and the lock screen artwork show this large; the 400px shelf size is
        // visibly soft there.
        val coverUrl = repository.coverUrl(itemId, width = PLAYER_COVER_WIDTH)

        // The player owns this snapshot; the detail page state is left untouched so that
        // starting a book never rewrites what another screen is browsing.
        val metadata = item.media.metadata
        playingBook = PlayingBook(
          itemId = itemId,
          title = metadata.title?.takeIf { it.isNotBlank() } ?: playbackSession.displayTitle,
          author = metadata.displayAuthor()?.takeIf { it.isNotBlank() } ?: playbackSession.displayAuthor,
          description = metadata.description?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty(),
          chapters = itemChapters,
          durationMs = bookDurationMs,
          hasCover = item.hasCover()
        )

        // Every book keeps its own speed
        val storedSpeed = container.playbackPrefs.getSpeed(itemId) ?: 1f
        playbackSpeed = storedSpeed
        skipIntroSeconds = container.playbackPrefs.getSkipIntro(itemId)
        skipOutroSeconds = container.playbackPrefs.getSkipOutro(itemId)
        // The service applies the chapter skips on its own thread, so it needs the same data
        runtime.chaptersMediaId = itemId
        runtime.chapters = itemChapters
        runtime.skipIntroSeconds = skipIntroSeconds
        runtime.skipOutroSeconds = skipOutroSeconds

        player.loadSession(
          session = playbackSession,
          startTimeSeconds = startSeconds,
          coverUrl = coverUrl,
          bookDurationMs = bookDurationMs,
          chapters = itemChapters,
          playWhenReady = autoPlay,
          trackUrlBuilder = { sessionId, trackIndex -> repository.trackUrl(sessionId, trackIndex) }
        )
        player.setSpeed(storedSpeed)
        // A fresh playlist is already built on the active address
        pendingAddressReload = false
        playingItemId = itemId
        // Make the home shelf change on this tap. The server only learns the new position on the
        // first progress report, so waiting for a re-fetch would leave "最近播放" stale for 15 s
        // (and, before this, until the app was restarted).
        promoteToContinueListening(item)
        // The book is in the player now; the staged snapshot and its resume point have served
        // their purpose.
        resumePositionMs = 0L
        stagedItem = null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Logged as well as shown: on the player page there is no banner to put it in, so a failed
        // session used to leave no trace at all — the tap simply did nothing.
        AppLog.w("AbsPlayback", "cannot start $itemId", e)
        if (generation == playGeneration) error = e.userMessage()
      } finally {
        // Only the call that owns the slot may clear it: a second tap can start a newer load
        if (generation == playGeneration) {
          if (pendingPlayback?.itemId == itemId) pendingPlayback = null
          loading = false
        }
      }
    }
  }

  /** Chapter of the book in the player (never of the book being browsed). */
  fun chapterAt(positionMs: Long): ChapterDto? {
    val chapters = playingBook?.chapters.orEmpty()
    if (chapters.isEmpty()) return null
    return chapters.find { positionMs >= (it.start * 1000).toLong() && positionMs < (it.end * 1000).toLong() }
      ?: chapters.lastOrNull { (it.start * 1000).toLong() <= positionMs }
  }

  fun coverUrl(itemId: String, width: Int = 400): String = repository.coverUrl(itemId, width)

  /** Progress comes from /api/me: item and shelf payloads do not include it. */
  fun progressFor(itemId: String): ProgressDto? = repository.progressFor(itemId)

  override fun onCleared() {
    super.onCleared()
    runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
    runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
    player.release()
  }
}
