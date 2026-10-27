package com.audiobookshelf.nativeapp

import android.content.Context
import com.audiobookshelf.nativeapp.data.AbsApi
import com.audiobookshelf.nativeapp.data.AppPrefs
import com.audiobookshelf.nativeapp.data.ChapterDto
import com.audiobookshelf.nativeapp.data.PlaybackPrefs
import com.audiobookshelf.nativeapp.data.ProgressQueue
import com.audiobookshelf.nativeapp.data.Repository
import com.audiobookshelf.nativeapp.data.SessionStore

/** Mutable playback state shared between the ui (view model) and the playback service. */
class RuntimeState {
  /** Set while headphones are away and playback is waiting for them to come back. */
  @Volatile
  var waitingForHeadset: Boolean = false

  /**
   * Chapters of the book currently loaded in the player, in absolute seconds.
   *
   * The view model publishes them here so the playback service can apply the intro/outro skips on
   * its own. Skipping used to live in the player screen, which meant it only worked while that
   * screen was composed: natural chapter transitions never skipped the intro, and the outro skip
   * stopped entirely as soon as the user went back to the shelf or listened from the lock screen.
   */
  @Volatile
  var chapters: List<ChapterDto> = emptyList()

  /**
   * Library item the [chapters] belong to. MediaController commands are asynchronous, so the view
   * model can publish the next book before the session holds its media items; the service compares
   * this against the loaded media id and ignores a mismatch instead of seeking into a stale book.
   */
  @Volatile
  var chaptersMediaId: String? = null

  @Volatile
  var skipIntroSeconds: Int = 0

  @Volatile
  var skipOutroSeconds: Int = 0
}

/**
 * Manual dependency container. The app is small enough that Hilt would add more
 * ceremony than value.
 */
class AppContainer(val context: Context) {
  val runtime = RuntimeState()
  val sessionStore by lazy { SessionStore(context) }
  val playbackPrefs by lazy { PlaybackPrefs(context) }
  val appPrefs by lazy { AppPrefs(context) }
  val api by lazy { AbsApi() }
  val repository by lazy { Repository(api, sessionStore, context) }
  /** Durable outbox for progress writes; shared by the playback service and the sync worker. */
  val progressQueue by lazy { ProgressQueue(context) }
}
