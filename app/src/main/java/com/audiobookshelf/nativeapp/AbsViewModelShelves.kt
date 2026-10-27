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
 * Shelf composition for the home screen: publishing server shelves, local "continue listening"
 * pins and their ordering. Kept as extensions on AbsViewModel (same package) so the state stays
 * in one class while these rules live in their own file.
 */
internal data class PinnedBook(val item: LibraryItemDto, val since: Long)

  /**
   * `shelves` backs a LazyColumn: assigning an equal list still recomposes every card (and every
   * cover request). Only publish when the members or their order actually changed.
   */
internal fun AbsViewModel.publishShelves(next: List<ShelfDto>) {
    if (next != shelves) shelves = next
  }

  /**
   * Publishes server shelves, releasing local pins only once the server has caught up.
   *
   * Listing a book in "continue listening" is not enough: for a book that was played before, the
   * server still has the *old* position and the old lastUpdate. Dropping the pin on that response
   * would push the book back to its old rank for the 15 s until the first progress report. The
   * timestamp comparison is what tells us the new playback actually reached the server.
   */
internal fun AbsViewModel.mergeServerShelves(loaded: List<ShelfDto>): List<ShelfDto> {
    val serverIds = loaded.asSequence()
      .filter(::isContinueListeningShelf)
      .flatMap { it.entities.asSequence() }
      .map { it.id }
      .toSet()
    val confirmed = serverIds.filter { id ->
      val pin = pinnedRecent[id] ?: return@filter false
      // A server clock a few seconds behind the device must not keep a pin forever.
      (repository.progressFor(id)?.lastUpdate ?: 0L) >= pin.since - SERVER_CLOCK_SKEW_MS
    }.toSet()
    if (confirmed.isNotEmpty()) {
      pinnedRecent = LinkedHashMap(pinnedRecent.filterKeys { it !in confirmed })
    }
    return reorderContinueListening(loaded)
  }

  /**
   * Puts the book that just started playing at the head of "continue listening" on the same tap.
   *
   * The server cannot do this yet: the first progress report is 15 s away (see PlaybackService).
   * A pin survives re-fetches until the server confirms a progress timestamp from this playback.
   */
internal fun AbsViewModel.promoteToContinueListening(item: LibraryItemDto) {
    val libraryId = homeLibraryId ?: return
    if (item.id.isBlank()) return
    // A book from another library must not show up in this library's home. Minified payloads
    // sometimes omit the field altogether, in which case the check cannot be made.
    val itemLibrary = item.libraryId
    if (!itemLibrary.isNullOrBlank() && itemLibrary != libraryId) return
    val next = LinkedHashMap<String, PinnedBook>()
    next[item.id] = PinnedBook(item, System.currentTimeMillis())
    pinnedRecent.filterKeys { it != item.id }.forEach { (id, value) -> next[id] = value }
    while (next.size > MAX_PINNED_RECENT) next.remove(next.keys.last())
    pinnedRecent = next
    publishShelves(reorderContinueListening(shelves))
  }

  /** Rebuilds the continue shelf from local progress while keeping pinned books at its head. */
internal fun AbsViewModel.reorderContinueListening(base: List<ShelfDto>): List<ShelfDto> {
    val pinned = pinnedRecent.values.map { it.item }
    if (base.isEmpty()) {
      return if (pinned.isEmpty()) base else listOf(localContinueShelf(pinned))
    }
    val result = base.map { shelf ->
      if (!isContinueListeningShelf(shelf)) return@map shelf
      val ordered = orderContinueEntities(
        entities = shelf.entities,
        pinned = pinned,
        lastUpdate = { repository.progressFor(it)?.lastUpdate ?: 0L },
        isFinished = { repository.progressFor(it)?.isFinished == true }
      )
      if (ordered == shelf.entities) shelf else shelf.copy(entities = ordered)
    }.toMutableList()
    if (pinned.isNotEmpty() && result.none(::isContinueListeningShelf)) {
      result.add(0, localContinueShelf(pinned))
    }
    return result
  }

internal fun AbsViewModel.localContinueShelf(entities: List<LibraryItemDto>) = ShelfDto(
    id = LOCAL_CONTINUE_SHELF_ID,
    label = CONTINUE_SHELF_LABEL,
    type = "book",
    entities = entities
  )
