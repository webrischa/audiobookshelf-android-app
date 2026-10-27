package com.audiobookshelf.nativeapp.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.progressQueueStore by preferencesDataStore(name = "abs_progress_queue")

/**
 * A progress write that the server did not accept. Kept on disk so a dead network at the exact
 * moment a book finishes cannot lose the "finished" state forever.
 */
@Serializable
data class PendingProgress(
  val itemId: String,
  val currentTime: Double,
  val duration: Double,
  val isFinished: Boolean = false,
  val createdAtMs: Long = 0L,
  /** Account the write belongs to: never replay one user's position under another account. */
  val userId: String = ""
)

internal const val MAX_PENDING_PROGRESS = 200

/**
 * One failed write replaces any earlier one for the same book: only the newest position matters,
 * and an unbounded list of 15-second ticks would outlive the outage it was created for.
 *
 * "Finished" is terminal - a late ordinary position report must not un-finish the book. The cap
 * drops the oldest entries, so a permanently offline device cannot grow the store forever.
 */
internal fun mergePending(
  existing: List<PendingProgress>,
  incoming: PendingProgress,
  maxEntries: Int = MAX_PENDING_PROGRESS
): List<PendingProgress> {
  val old = existing.firstOrNull { it.itemId == incoming.itemId }
  val chosen = if (old?.isFinished == true && !incoming.isFinished) old else incoming
  val next = existing.filter { it.itemId != incoming.itemId } + chosen
  return if (next.size > maxEntries) next.takeLast(maxEntries) else next
}

/**
 * True only when the queued write provably belongs to the signed-in account.
 *
 * A blank userId means the session was already gone when the write failed (a 401 clears it, or
 * the user signed out while audio kept playing). Replaying such an entry under the next account
 * moves another user's server-side position, which is worse than dropping one local sample:
 * playback itself still reports the current position on the next tick.
 */
internal fun belongsToSession(entryUserId: String, sessionUserId: String): Boolean =
  entryUserId.isNotBlank() && entryUserId == sessionUserId

/**
 * Durable outbox for progress writes. DataStore edits are atomic and survive process death,
 * which is the whole point: [com.audiobookshelf.nativeapp.playback.PlaybackService] can be killed
 * seconds after the report failed.
 */
class ProgressQueue(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = ListSerializer(PendingProgress.serializer())
  private val key = stringPreferencesKey("pending")

  private fun decode(raw: String?): List<PendingProgress> =
    if (raw.isNullOrBlank()) {
      emptyList()
    } else {
      runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

  suspend fun all(): List<PendingProgress> = runCatching {
    decode(context.progressQueueStore.data.first()[key])
  }.getOrDefault(emptyList())

  suspend fun enqueue(entry: PendingProgress) {
    context.progressQueueStore.edit { prefs ->
      // Read from `prefs` (the snapshot being edited), not from a cached value: DataStore may
      // replay this lambda after a conflicting write.
      val next = mergePending(decode(prefs[key]), entry)
      prefs[key] = json.encodeToString(serializer, next)
    }
  }

  /** Called only after the server confirmed the write. */
  suspend fun remove(itemIds: Set<String>) {
    if (itemIds.isEmpty()) return
    context.progressQueueStore.edit { prefs ->
      val next = decode(prefs[key]).filter { it.itemId !in itemIds }
      if (next.isEmpty()) prefs.remove(key) else prefs[key] = json.encodeToString(serializer, next)
    }
  }

  suspend fun clear() {
    context.progressQueueStore.edit { it.remove(key) }
  }
}
