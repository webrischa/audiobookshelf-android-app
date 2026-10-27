package com.audiobookshelf.nativeapp.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import com.audiobookshelf.nativeapp.util.AppLog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AbsNetwork"

class Repository(private val api: AbsApi, private val store: SessionStore, private val appContext: Context) {

  private val deviceId: String = deviceId(appContext)

  /**
   * Local mirror of item details, see [ItemCache]; lives in the app cache directory. Scoped to the
   * current account, so a 401 can leave the mirror in place for a same-account re-login without
   * ever handing one account's details (and its media progress) to the next sign-in.
   */
  private val itemCache = ItemCache(
    root = File(appContext.cacheDir, "items"),
    userId = { session?.userId }
  )

  @Volatile
  var session: Session? = null
    private set

  /** Address currently used for requests: either the LAN or the remote one. */
  @Volatile
  var activeServerUrl: String? = null
    private set

  /** Serialises address resolution: network callbacks and request retries can race for it. */
  private val resolveMutex = Mutex()

  /** Uptime of the last successful LAN probe, 0 when the LAN address was never confirmed. */
  @Volatile
  private var lanVerifiedAt = 0L

  /**
   * Tells the repository that the device moved to another network.
   *
   * The LAN address confirmed on the previous network proves nothing about the new one: the same
   * 192.168.x.x is a different machine at the office, or nothing at all. Dropping the trust makes
   * the next resolution require a fresh successful probe instead of falling back to "the ping
   * failed but the LAN address is already in use, keep it", which used to pin the app to an address
   * that no longer existed until it was restarted.
   */
  fun noteNetworkChanged() {
    lanVerifiedAt = 0L
  }

  suspend fun restore(): Session? {
    val saved = store.load() ?: return null
    session = saved
    applyActiveAddress(saved, force = true)
    // A 401 during this refresh clears the session (see withAuthGuard). Returning the stale
    // `saved` here would put the UI back on the home screen with a token the server rejects.
    refreshProgress()
    return if (session == null) null else saved
  }

  /**
   * Signs in, trying the LAN address first when the device is on Wi-Fi.
   *
   * At home the LAN address is both the faster one and the one that is actually reachable: the
   * public address often cannot be reached from inside the network (no hairpin NAT, a tunnel that
   * is down, DNS pointing at a stale IP), and login used to fail on exactly that while the server
   * was answering a metre away.
   *
   * The probe before the LAN attempt is what keeps the fallback cheap: the request client waits 15
   * seconds to connect, and paying that on a Wi-Fi network the server is not on would feel like a
   * hang. A rejected password (401/403) is final and never retried on the other address — the
   * server answered, so the credentials are what is wrong, and repeating it would only double the
   * wait and burn a failed attempt.
   */
  suspend fun login(serverUrl: String, localServerUrl: String?, username: String, password: String): Session {
    val normalized = normalizeServer(serverUrl)
    val normalizedLocal = localServerUrl?.takeIf { it.isNotBlank() }?.let { normalizeServer(it) }
    val candidates = buildList {
      if (normalizedLocal != null && normalizedLocal != normalized && isOnWifi() &&
        api.ping(normalizedLocal)
      ) {
        AppLog.i(TAG, "login: LAN $normalizedLocal answers, trying it first")
        add(normalizedLocal)
      }
      add(normalized)
    }

    var response: LoginResponse? = null
    var usedBase: String? = null
    var lastError: Exception? = null
    for (base in candidates) {
      try {
        AppLog.i(TAG, "login: POST $base/login")
        response = api.login(base, username, password)
        usedBase = base
        break
      } catch (error: ApiException) {
        if (error.code == 401 || error.code == 403) {
          // The server answered: the credentials are what is wrong. Never retried on the other
          // address, and reported in words the login form can show as is.
          throw UserFacingError("用户名或密码错误")
        }
        AppLog.w(TAG, "login on $base failed with HTTP ${error.code}")
        lastError = error
      } catch (error: IOException) {
        AppLog.w(TAG, "login on $base unreachable: ${error.message}")
        lastError = error
      }
    }
    val success = response
      ?: throw UserFacingError(
        if (lastError == null) "无法连接服务器" else "无法连接服务器，请检查地址与网络"
      )
    if (success.user.token.isBlank()) throw UserFacingError("服务器未返回登录凭据")
    val newSession = Session(
      serverUrl = normalized,
      token = success.user.token,
      userId = success.user.id,
      username = success.user.username.ifBlank { username },
      defaultLibraryId = success.userDefaultLibraryId,
      localServerUrl = normalizedLocal
    )
    store.save(newSession)
    session = newSession
    // The address that answered is the one to use: the LAN was either confirmed by that very
    // response or already ruled out by the probe above, so probing a third time would only add
    // another wait before the library screen appears. The public address stays the canonical one
    // in the session either way.
    adoptAddress(usedBase ?: normalized, newSession.token, lanVerified = usedBase == normalizedLocal)
    refreshProgress()
    return newSession
  }

  /**
   * Makes the address that just answered a real request the active one.
   *
   * [lanVerified] feeds the LAN trust window: a login through the LAN is better proof than a
   * probe, while a login through the public address says nothing about whether the LAN is up.
   */
  private suspend fun adoptAddress(address: String, token: String, lanVerified: Boolean) {
    resolveMutex.withLock {
      api.configure(address, token)
      activeServerUrl = address
      lanVerifiedAt = if (lanVerified) SystemClock.elapsedRealtime() else 0L
    }
  }

  /** Stores the optional LAN address and immediately re-evaluates which address to use. */
  suspend fun updateLocalServerUrl(url: String?) {
    val current = session ?: return
    val normalized = url?.takeIf { it.isNotBlank() }?.let { normalizeServer(it) }
    val updated = current.copy(localServerUrl = normalized)
    store.save(updated)
    session = updated
    applyActiveAddress(updated, force = true)
  }

  /** Re-evaluates the address after a network change. Returns the address now in use. */
  suspend fun resolveActiveServer(force: Boolean = false): String? {
    val current = session ?: return null
    return applyActiveAddress(current, force)
  }

  /**
   * Cheap reachability check for an address the user is about to switch to.
   *
   * [forceActiveServer] deliberately does not probe (the user's word outranks the probe), so the
   * caller that needs a refused switch on an unreachable address runs this gate in front of it.
   */
  suspend fun probeServer(url: String): Boolean {
    val normalized = normalizeServer(url)
    if (normalized.isBlank()) return false
    return api.ping(normalized)
  }

  /**
   * Switches to [url] because the user asked for it, without probing it first.
   *
   * The automatic choice can only ever be as good as its probe: a LAN address that timed out on a
   * congested link, or a remote address the current network refuses to reach, leaves the app on an
   * address that does not work with no way out. This is that way out — the user is standing in
   * front of the device and knows which network they are on.
   *
   * @return true when the active address actually changed
   */
  suspend fun forceActiveServer(url: String): Boolean {
    val current = session ?: return false
    val normalized = normalizeServer(url)
    if (normalized.isBlank()) return false
    var changed = false
    resolveMutex.withLock {
      changed = normalized != activeServerUrl
      api.configure(normalized, current.token)
      activeServerUrl = normalized
      // A manual choice is a statement about the network in use, so it is trusted for the window
      // in which the automatic logic tolerates a failed probe instead of bouncing straight back.
      lanVerifiedAt =
        if (normalized == current.localServerUrl) SystemClock.elapsedRealtime() else 0L
    }
    AppLog.i(TAG, "forceActiveServer: $normalized (changed=$changed)")
    return changed
  }

  fun isOnWifi(): Boolean {
    val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
  }

  /**
   * The LAN address is only useful on Wi-Fi, and only when the server actually answers there.
   * Everything else falls back to the remote address.
   */
  private suspend fun pickActiveAddress(session: Session): String {
    val local = session.localServerUrl
    if (local.isNullOrBlank()) return session.serverUrl
    val onWifi = isOnWifi()
    if (!onWifi) {
      AppLog.i(TAG, "pickActiveAddress: not on wifi -> remote")
      return session.serverUrl
    }
    val reachable = api.ping(local)
    AppLog.i(TAG, "pickActiveAddress: ping $local -> $reachable")
    if (reachable) {
      lanVerifiedAt = SystemClock.elapsedRealtime()
      return local
    }
    // A failed ping does not prove the LAN server is gone: on a congested link the 3 second probe
    // times out while the server is perfectly reachable. Switching to the remote address in that
    // case rebuilt the whole playlist, threw the buffered audio away and, when the fresh load also
    // failed, left the player idle with no notification. Keep the address already in use — but
    // only while it was confirmed recently, otherwise a Wi-Fi network the server is not on would
    // keep the app pinned to an unreachable address forever.
    val age = SystemClock.elapsedRealtime() - lanVerifiedAt
    if (activeServerUrl == local && lanVerifiedAt > 0 && age < LAN_TRUST_WINDOW_MS) {
      AppLog.i(TAG, "pickActiveAddress: ping failed, LAN verified ${age / 1000}s ago, keeping it")
      return local
    }
    AppLog.i(TAG, "pickActiveAddress: ping failed and LAN not recently verified -> remote")
    return session.serverUrl
  }

  private suspend fun applyActiveAddress(session: Session, force: Boolean = false): String =
    resolveMutex.withLock {
      val active = pickActiveAddress(session)
      if (force || active != activeServerUrl) {
        api.configure(active, session.token)
        activeServerUrl = active
      }
      active
    }

  /**
   * Runs [block]; when it fails at the connection level, re-resolves the server address once and
   * retries.
   *
   * Network callbacks are not always delivered (a process that was not listening, a callback the
   * system coalesced, a Wi-Fi network that is still "connected" while its server is unreachable),
   * and a request against a dead address is the first visible symptom. Retrying once after a fresh
   * resolution turns that from "nothing loads until the app is restarted" into a slower request.
   */
  private suspend fun <T> withAddressRetry(block: suspend () -> T): T {
    return try {
      block().also { markLanVerifiedIfActive() }
    } catch (error: IOException) {
      val before = activeServerUrl
      AppLog.w(TAG, "request failed on $before, re-resolving", error)
      val after = runCatching { resolveActiveServer(force = true) }.getOrNull()
      if (after == null || after == before) throw error
      block()
    }
  }

  /**
   * Fired once per expired-token response, after the local session has been cleared. The UI
   * collects this to open the login page. When only the playback service is alive there is no
   * collector, but clearing the stored token is enough: the next launch lands on login too.
   */
  private val _authEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val authEvents: SharedFlow<Unit> = _authEvents.asSharedFlow()

  /**
   * The single place where "401" becomes an actual logout. It used to be swallowed by
   * `runCatching { api.me() }.getOrNull() ?: return`, which left the app signed in on paper with
   * a dead token: no re-login entry, a frozen progress mirror and a raw "HTTP 401: {...}" body
   * on screen.
   *
   * Addresses are kept (clearUser, not clear) so the login form opens prefilled, exactly like a
   * manual user switch. The error is rethrown so callers can still map it to a short UI message.
   */
  private suspend fun <T> withAuthGuard(block: suspend () -> T): T = try {
    block()
  } catch (error: ApiException.Unauthorized) {
    AppLog.w(TAG, "401: token expired, clearing session")
    store.clearUser()
    resetConnection()
    _authEvents.tryEmit(Unit)
    throw error
  }

  /** Every authenticated API call gets both the address retry and the expired-token handling. */
  private suspend fun <T> guarded(block: suspend () -> T): T =
    withAuthGuard { withAddressRetry(block) }

  /**
   * A request that went through is better proof than a probe: it keeps the "recently verified"
   * window meaningful for a session that has been streaming from the LAN for a long time, so a
   * single slow probe cannot push it onto the remote address.
   */
  private fun markLanVerifiedIfActive() {
    val local = session?.localServerUrl ?: return
    if (activeServerUrl == local) lanVerifiedAt = SystemClock.elapsedRealtime()
  }

  suspend fun logout() {
    itemCache.clear()
    store.clear()
    resetConnection()
  }

  /**
   * Signs the current user out but keeps the server addresses, so the login form opens prefilled
   * for the next account. Returns the addresses that were kept.
   */
  suspend fun switchUser(): SavedConnection {
    itemCache.clear()
    val kept = store.clearUser()
    resetConnection()
    return kept
  }

  /** Everything that belongs to the connection in use, dropped on both logout and user switch. */
  private fun resetConnection() {
    api.configure("", null)
    session = null
    activeServerUrl = null
    progressByItem = emptyMap()
  }

  /**
   * libraryItemId -> progress, from /api/me (list and item responses do not carry it).
   *
   * Compose state, not a plain map: every screen reads it during composition to draw the progress
   * bars, and a `@Volatile` field left them showing whatever was true when they were first drawn.
   * A refresh finished after the list was already on screen therefore changed nothing until an
   * unrelated state change happened to recompose the row — which is why progress looked "stuck"
   * until the app was restarted.
   */
  private var progressByItem by mutableStateOf<Map<String, ProgressDto>>(emptyMap())

  /**
   * Emits the item id whenever the playback service has written a progress update that the server
   * accepted.
   *
   * Progress is reported by [com.audiobookshelf.nativeapp.playback.PlaybackService], which keeps
   * running while the UI is gone. This flow is the only way the home screen can learn that the
   * members or the order of "continue listening" changed while it was not on screen (or while the
   * app was in the background).
   */
  private val _progressUpdates = MutableSharedFlow<String>(extraBufferCapacity = 16)
  val progressUpdates: SharedFlow<String> = _progressUpdates.asSharedFlow()

  fun progressFor(libraryItemId: String): ProgressDto? = progressByItem[libraryItemId]

  /** The entry with the most recent update that has not been finished. */
  fun lastUnfinishedProgress(): ProgressDto? =
    progressByItem.values
      .filter { !it.isFinished && it.progress > 0.0 && it.currentTime > 0.0 }
      .maxByOrNull { it.lastUpdate }

  suspend fun refreshProgress() {
    val entries = try {
      guarded { api.me().mediaProgress }
    } catch (error: Exception) {
      // A 401 has already cleared the session and emitted an auth event. A dead network or a
      // transient 5xx just leaves the cached mirror alone: a silent refresh must not raise an
      // error banner over a usable shelf, but it must never hide an expired token either.
      AppLog.w(TAG, "refreshProgress failed", error)
      return
    }
    val next = entries
      .mapNotNull { progress -> progress.libraryItemId?.let { it to progress } }
      .toMap()
    // This map backs Compose state. Replacing it with equal contents invalidates every card that
    // drew a progress bar, which on an unchanged shelf is a full recomposition for nothing.
    if (next != progressByItem) progressByItem = next
  }

  /**
   * Mirrors a position that was just written to the server into the local cache.
   *
   * Without this the bars kept showing the position from the last /api/me fetch: the player had
   * already moved on, so a book being listened to showed up as "not started" on the shelf right
   * next to it. Called on the main thread, which is where the reporter returns to.
   */
  fun noteProgress(
    libraryItemId: String,
    currentTime: Double,
    duration: Double,
    isFinished: Boolean = false
  ) {
    if (libraryItemId.isBlank() || duration <= 0.0) return
    val progress = (currentTime / duration).coerceIn(0.0, 1.0)
    val existing = progressByItem[libraryItemId]
    if (existing != null && !isFinished &&
      kotlin.math.abs(existing.currentTime - currentTime) < 1.0
    ) {
      return
    }
    progressByItem = progressByItem + (
      libraryItemId to ProgressDto(
        libraryItemId = libraryItemId,
        episodeId = existing?.episodeId,
        currentTime = currentTime,
        duration = duration,
        progress = progress,
        isFinished = isFinished,
        lastUpdate = System.currentTimeMillis()
      )
      )
    _progressUpdates.tryEmit(libraryItemId)
  }

  suspend fun libraries(): List<LibraryDto> = guarded { api.libraries() }

  suspend fun items(
    libraryId: String,
    page: Int,
    limit: Int = 40,
    collapseSeries: Boolean = false,
    sort: String = "media.metadata.title",
    desc: Boolean = false
  ): ItemsResponse = guarded {
    api.items(libraryId, page, limit, collapseSeries, sort, desc)
  }

  /**
   * Fetches the item detail and refreshes the local mirror on success. Failures leave the mirror
   * untouched: the detail screen keeps rendering the cached copy.
   */
  suspend fun item(itemId: String): LibraryItemDto {
    val item = guarded { api.item(itemId) }
    itemCache.persist(item)
    return item
  }

  /** The cached detail payload, if this item was opened before (memory only, synchronous). */
  fun cachedItem(itemId: String): LibraryItemDto? = itemCache.readMemory(itemId)

  /** The disk copy, used when the process was restarted since the item was last opened. */
  suspend fun cachedItemFromDisk(itemId: String): LibraryItemDto? = itemCache.readDisk(itemId)

  /** The server reports the book deleted: stop offering its mirror on the next open. */
  suspend fun evictCachedItem(itemId: String) = itemCache.evict(itemId)

  suspend fun search(libraryId: String, query: String): SearchResponse = guarded {
    api.search(libraryId, query)
  }

  suspend fun personalized(libraryId: String): List<ShelfDto> = guarded {
    api.personalized(libraryId)
  }

  suspend fun playbackSession(itemId: String): PlaybackSessionDto = guarded {
    api.startPlayback(itemId, deviceId)
  }

  suspend fun updateProgress(itemId: String, currentTime: Double, duration: Double, isFinished: Boolean? = null) {
    val progress = if (duration > 0) (currentTime / duration).coerceIn(0.0, 1.0) else 0.0
    // Reads already used withAddressRetry; the progress PATCH did not, so a LAN/remote switch
    // silently dropped the write. The auth guard also makes a 401 here end the session instead
    // of being logged and forgotten by the playback service.
    guarded { api.updateProgress(itemId, ProgressUpdate(currentTime, duration, progress, isFinished)) }
  }

  suspend fun remoteProgress(itemId: String): ProgressDto? = guarded { api.remoteProgress(itemId) }

  fun coverUrl(itemId: String, width: Int = 400): String {
    val current = session ?: return ""
    val base = activeServerUrl ?: current.serverUrl
    return "$base/api/items/$itemId/cover?token=${current.token}&width=$width"
  }

  fun trackUrl(playbackSessionId: String, index: Int): String {
    val current = session ?: return ""
    // Streams must follow the address that is currently reachable (LAN while at home)
    val base = activeServerUrl ?: current.serverUrl
    return "$base/public/session/$playbackSessionId/track/$index"
  }

  companion object {
    /**
     * How long a confirmed LAN address survives a failed probe.
     *
     * Long enough to ride out a congested link (the probe gives up after 3 s), short enough that
     * moving to another network cannot leave the app talking to an address that is not there.
     */
    private const val LAN_TRUST_WINDOW_MS = 90_000L

    fun normalizeServer(input: String): String {
      var value = input.trim()
      if (value.isEmpty()) return value
      if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://$value"
      return value.trimEnd('/')
    }

    @SuppressLint("HardwareIds")
    private fun deviceId(context: Context): String {
      val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
      return androidId ?: "abs-native-device"
    }
  }
}
