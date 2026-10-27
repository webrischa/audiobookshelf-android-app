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
 * Pure decisions and tuning constants used by AbsViewModel: refresh throttles, chapter-jump
 * routing, "continue listening" shelf matching and ordering. Kept free of Android/Compose state so
 * the rules stay covered by JVM tests. Same package, so internal is enough for the view model.
 */
/** Wired, USB and Bluetooth audio outputs all count as "headphones". */
internal fun AudioDeviceInfo.isHeadphone(): Boolean = when (type) {
  AudioDeviceInfo.TYPE_WIRED_HEADSET,
  AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
  AudioDeviceInfo.TYPE_USB_HEADSET,
  AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
  AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
  else -> android.os.Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET
}

internal const val DEFAULT_BACKGROUND_URI = "file:///android_asset/default_background.jpg"

/** Upper bound of the sleep timer, matching the last preset in the picker. */
internal const val SLEEP_TIMER_MAX_MINUTES = 240

/**
 * Cover width requested for the player page and the lock-screen artwork.
 *
 * The shelf grids ask for 400px, which is right for a ~130dp card; the same url blown up to a
 * full-width player cover was visibly soft, and the media notification had nothing better either.
 */
internal const val PLAYER_COVER_WIDTH = 900

/**
 * How often the identity of the default network is compared against the last one seen.
 *
 * Only a change triggers anything, and reading the capabilities is local (no traffic), so this is
 * a cheap way to catch a switch that no callback reported.
 */
internal const val NETWORK_WATCHDOG_MS = 20_000L

/** Minimum gap between two foreground-triggered address resolutions. */
internal const val FOREGROUND_RESOLVE_MIN_GAP_MS = 10_000L

/**
 * Minimum gap between two shelf re-reads. Entering the home screen and returning to the foreground
 * share this budget; playback progress updates never spend it.
 */
internal const val HOME_REFRESH_MIN_GAP_MS = 20_000L

/** Throttle policy kept pure so the refresh cadence is pinned down by a unit test. */
internal fun shouldRefreshHome(lastAt: Long, now: Long, minGapMs: Long): Boolean =
  lastAt <= 0L || now - lastAt >= minGapMs

/**
 * Identity of one shelf row's current batch, used as the key of that row's reset effect.
 *
 * Comparing ids rather than DTO instances means a refresh that returned the same books in the
 * same order does not move the row, while a re-randomised "discover" batch does. Every row has
 * its own key, so a change in one row never resets another: coming back from a detail page keeps
 * the position the user scrolled to in the other shelves. A plain String (item ids are opaque
 * tokens without the separator) so it can be stored by `rememberSaveable` across a NavHost
 * recomposition — the row must remember the *previous* batch, not just the current one.
 */
internal fun shelfBatchKey(entities: List<LibraryItemDto>): String =
  entities.joinToString(separator = "|") { it.id }

/**
 * Whether a saved list identity differs from the one observed now.
 *
 * HomeScreen's shelf rows and BookshelfScreen's grid/list both use this to decide if a scroll
 * reset is warranted. On the first composition — and when NavHost recomposes a screen after a
 * detail or player page — the saved value equals the current one, so the restored scroll position
 * survives; only a real content change resets it. Kept pure so the boundary is unit tested.
 */
internal fun batchChanged(previous: String, current: String): Boolean = previous != current

/** How a chapter tap reaches the player, see [AbsViewModel.playChapter]. */
internal enum class ChapterJump { SeekAndPlay, LoadSession, Ignore }

/**
 * A loaded player only needs a seek plus a play command. With nothing loaded, the jump has to
 * start a session at that chapter (only a staged book can supply one); while a session is already
 * being created, or with no book at all, the tap has nothing to act on. Kept pure so the three
 * states are covered by a unit test rather than by a device-only interaction.
 */
internal fun chapterJumpFor(
  hasMedia: Boolean,
  hasStagedBook: Boolean,
  loadingSession: Boolean
): ChapterJump = when {
  hasMedia -> ChapterJump.SeekAndPlay
  !hasStagedBook || loadingSession -> ChapterJump.Ignore
  else -> ChapterJump.LoadSession
}

/** Tolerance for a server clock lagging behind the device when releasing a local pin. */
internal const val SERVER_CLOCK_SKEW_MS = 60_000L

/** Shelf synthesised locally when the server has no "continue listening" category yet. */
internal const val LOCAL_CONTINUE_SHELF_ID = "local-continue-listening"
/** Kept in the server's wording so HomeScreen's label mapping renders it as 最近播放. */
internal const val CONTINUE_SHELF_LABEL = "Continue Listening"

/** A pin is a stopgap, not a list: the server takes over after the first progress report. */
internal const val MAX_PINNED_RECENT = 3

/**
 * Only the real "continue listening" shelf qualifies. Matching on a loose `contains("continue")`
 * would also reorder "continue-series" and "continue-reading", which the server returns as
 * separate categories. The id is the stable identifier; the label/key are the fallbacks.
 */
internal fun isContinueListeningShelf(shelf: ShelfDto): Boolean =
  shelf.id.equals("continue-listening", ignoreCase = true) ||
    shelf.id.equals(LOCAL_CONTINUE_SHELF_ID, ignoreCase = true) ||
    shelf.labelStringKey.equals("LabelContinueListening", ignoreCase = true) ||
    shelf.label.trim().equals("continue listening", ignoreCase = true) ||
    shelf.label.trim().equals("最近播放", ignoreCase = true)

/**
 * Order of the "continue listening" shelf: locally pinned books first (newest playback first),
 * then everything else by the progress' lastUpdate. Finished books are dropped.
 *
 * Kept free of Android/Compose state so the rule can be unit tested. [pinned] is already in the
 * order the books were played, and duplicates of a pinned id in [entities] are removed.
 */
internal fun orderContinueEntities(
  entities: List<LibraryItemDto>,
  pinned: List<LibraryItemDto>,
  lastUpdate: (String) -> Long,
  isFinished: (String) -> Boolean
): List<LibraryItemDto> {
  val pinnedIds = pinned.map { it.id }.toSet()
  val rest = entities
    .filterNot { it.id in pinnedIds || isFinished(it.id) }
    .distinctBy { it.id }
    .sortedByDescending { lastUpdate(it.id) }
  return pinned + rest
}
