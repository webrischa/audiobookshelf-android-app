package com.audiobookshelf.nativeapp.playback

import android.content.Context
import androidx.media3.common.Player
import com.audiobookshelf.nativeapp.AbsApp
import com.audiobookshelf.nativeapp.data.PendingProgress
import com.audiobookshelf.nativeapp.data.ProgressSyncWorker
import com.audiobookshelf.nativeapp.data.isUnauthorized
import com.audiobookshelf.nativeapp.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Progress reporting, split out of PlaybackService because it needs only a Context and the Player
 * snapshot - none of the service's media-session/focus state. Failed writes go to the durable
 * outbox; the WorkManager retry drains them once the server confirms each one.
 */
internal class ProgressReporter(private val context: Context) {
  /**
   * Three callers can report at the same instant (isPlaying change, STATE_ENDED and the 15 s
   * tick). Serialising the whole report — including the position sample — keeps their writes in
   * order, so an older sample cannot land on the server after a newer one.
   */
  private val reportGate = Mutex()

  suspend fun report(player: Player) = reportGate.withLock { reportNow(player) }

  private suspend fun reportNow(player: Player) {
    val item = player.currentMediaItem ?: return
    val libraryItemId = item.mediaId.takeIf { it.isNotBlank() } ?: return
    val extras = item.mediaMetadata.extras
    val bookDurationMs = extras?.getLong(PlayerConnection.KEY_BOOK_DURATION_MS) ?: 0L
    if (bookDurationMs <= 0) return
    val trackStartMs = extras?.getLong(PlayerConnection.KEY_TRACK_START_MS) ?: 0L
    val absoluteMs = (trackStartMs + player.currentPosition).coerceAtLeast(0L)
    // Media3 only allows touching the player on the thread that created it. Reading isPlaying
    // inside the IO block below threw "Player is accessed on the wrong thread", which aborted
    // every single report before the network call: the server never received any progress.
    // Sample the flag here (caller is the main dispatcher) and carry it into the IO block.
    val playing = player.isPlaying
    // The playlist reached its end: the book is done. Without an explicit isFinished the server
    // keeps it in "continue listening" at ~100% forever, because a plain progress patch never
    // flips the finished flag.
    val finished = player.playbackState == Player.STATE_ENDED
    val repository = (context.applicationContext as AbsApp).container.repository
    // Capture the owner before the request: a 401 or a concurrent logout clears the session, and
    // the catch block below would otherwise enqueue a write with no attributable account.
    val ownerId = repository.session?.userId.orEmpty()
    // What the server actually accepted, mirrored into the local cache once back on the main
    // thread. Null means nothing was written: the report was skipped as stale or it failed.
    var accepted: Pair<Double, Boolean>? = null
    withContext(Dispatchers.IO) {
      try {
        if (finished) {
          // Final state: the "another device is ahead" guard must not swallow it.
          repository.updateProgress(libraryItemId, bookDurationMs / 1000.0, bookDurationMs / 1000.0, isFinished = true)
          AppLog.i("PlaybackService", "Reported finished: $libraryItemId")
          accepted = (bookDurationMs / 1000.0) to true
          return@withContext
        }
        // Do not let a paused device drag the server backwards: if another device has already
        // advanced well past this position, keep the server value. Playback start already reads
        // the server position, so the next resume lands on the newer spot.
        if (!playing) {
          val remoteMs = ((repository.remoteProgress(libraryItemId)?.currentTime ?: 0.0) * 1000).toLong()
          if (remoteMs > absoluteMs + REMOTE_PROGRESS_LEAD_MS) {
            AppLog.i("PlaybackService", "Skip report: server at ${remoteMs / 1000}s, local at ${absoluteMs / 1000}s")
            return@withContext
          }
        }
        repository.updateProgress(libraryItemId, absoluteMs / 1000.0, bookDurationMs / 1000.0)
        accepted = (absoluteMs / 1000.0) to false
      } catch (error: Exception) {
        AppLog.w("PlaybackService", "Failed to report progress, queueing for retry", error)
        // A 401 is not a delivery failure: Repository.withAuthGuard has already ended the
        // session and the outbox is discarded when the next account signs in. Every other
        // failure is durable - in particular the single isFinished write at STATE_ENDED, which
        // used to be lost forever when the network was down at that exact moment.
        if (!error.isUnauthorized()) {
          val pendingSeconds = if (finished) bookDurationMs / 1000.0 else absoluteMs / 1000.0
          // NonCancellable: the service may be torn down right after ENDED. The queue write must
          // finish even then, otherwise the last chapter of every offline listen is lost.
          withContext(NonCancellable) {
            runCatching {
              (context.applicationContext as AbsApp).container.progressQueue.enqueue(
                PendingProgress(
                  itemId = libraryItemId,
                  currentTime = pendingSeconds,
                  duration = bookDurationMs / 1000.0,
                  isFinished = finished,
                  createdAtMs = System.currentTimeMillis(),
                  userId = ownerId
                )
              )
              ProgressSyncWorker.enqueue(context.applicationContext)
            }.onFailure {
              AppLog.w("PlaybackService", "Could not persist pending progress", it)
            }
          }
        }
      }
    }
    // The shelf reads the local cache, not the server: without this the bars kept showing the
    // position from the last /api/me fetch and the book being listened to looked untouched.
    accepted?.let { (seconds, isFinished) ->
      repository.noteProgress(libraryItemId, seconds, bookDurationMs / 1000.0, isFinished)
    }
  }

  private companion object {
    /** A server more than two minutes ahead is another device; a paused phone must not rewind it. */
    private const val REMOTE_PROGRESS_LEAD_MS = 120_000L
  }
}
