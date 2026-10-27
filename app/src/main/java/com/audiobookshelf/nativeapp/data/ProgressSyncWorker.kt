package com.audiobookshelf.nativeapp.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.audiobookshelf.nativeapp.AbsApp
import com.audiobookshelf.nativeapp.util.AppLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ProgressSync"

/**
 * Drains the failed-progress outbox. A write leaves the queue only after the server accepted it;
 * a connection failure or a 5xx returns [Result.retry] and WorkManager backs off and waits for a
 * network. Process death is irrelevant: [ProgressQueue] is on disk and WorkManager reschedules.
 */
class ProgressSyncWorker(
  appContext: Context,
  params: WorkerParameters
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val container = (applicationContext as? AbsApp)?.container ?: return Result.retry()
    val repository = container.repository
    val queue = container.progressQueue
    val pending = queue.all()
    if (pending.isEmpty()) return Result.success()

    // No session (a 401 cleared it, or the user logged out). Keep the outbox: the next sign-in
    // re-enqueues this worker. Retrying now would spin forever with no token.
    val session = repository.session ?: return Result.success()

    val delivered = mutableSetOf<String>()
    for (entry in pending) {
      // Only a write whose owner is known and matches may be replayed. A blank userId means the
      // session was already gone when it failed; guessing the current account here would move a
      // different user's server-side position.
      if (!belongsToSession(entry.userId, session.userId)) {
        delivered += entry.itemId
        AppLog.i(TAG, "discarding ${entry.itemId}: unattributed or another account's write")
        continue
      }
      try {
        repository.updateProgress(
          entry.itemId,
          entry.currentTime,
          entry.duration,
          entry.isFinished.takeIf { it }
        )
        // noteProgress touches Compose snapshot state, which the reporter normally calls on the
        // main thread. Keep that contract when the write comes from a background worker.
        withContext(Dispatchers.Main) {
          repository.noteProgress(entry.itemId, entry.currentTime, entry.duration, entry.isFinished)
        }
        delivered += entry.itemId
        AppLog.i(TAG, "delivered ${entry.itemId} finished=${entry.isFinished}")
      } catch (error: Exception) {
        when {
          error.isUnauthorized() -> {
            // Repository already cleared the session and emitted the re-login event. Keep the
            // remaining entries; the next sign-in drains them (subject to the account check).
            queue.remove(delivered)
            return Result.success()
          }
          error is IOException -> {
            queue.remove(delivered)
            return Result.retry()
          }
          error is ApiException.Http && error.code >= 500 -> {
            queue.remove(delivered)
            return Result.retry()
          }
          else -> {
            // A permanent 4xx will never succeed; dropping just this entry prevents an endless
            // retry loop while keeping the remaining queue intact.
            delivered += entry.itemId
            AppLog.w(TAG, "dropping permanent failure for ${entry.itemId}", error)
          }
        }
      }
    }
    queue.remove(delivered)
    return Result.success()
  }

  companion object {
    const val UNIQUE_WORK = "abs_progress_sync"

    /** One chain of retries no matter how many reports failed in the same offline stretch. */
    fun enqueue(context: Context) {
      val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
      val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
      // KEEP, not APPEND: during one offline stretch the service fails a report every 15 s, and
      // appending would build a long chain of identical workers that all drain the same outbox.
      // An already pending/running worker (including a backoff retry) is enough.
      WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        UNIQUE_WORK,
        ExistingWorkPolicy.KEEP,
        request
      )
    }
  }
}
