package com.audiobookshelf.nativeapp.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.playbackDataStore by preferencesDataStore(name = "abs_playback_prefs")

/**
 * Per-library-item playback preferences. Only the speed is stored for now, keyed by the library
 * item id so every book keeps its own value.
 */
class PlaybackPrefs(private val context: Context) {

  suspend fun getSpeed(libraryItemId: String): Float? {
    if (libraryItemId.isBlank()) return null
    val prefs = context.playbackDataStore.data.first()
    return prefs[speedKey(libraryItemId)]
  }

  suspend fun setSpeed(libraryItemId: String, speed: Float) {
    if (libraryItemId.isBlank()) return
    context.playbackDataStore.edit { it[speedKey(libraryItemId)] = speed }
  }

  private fun speedKey(libraryItemId: String) = floatPreferencesKey("speed_$libraryItemId")

  /** Seconds skipped at the start of every chapter of this book. */
  suspend fun getSkipIntro(libraryItemId: String): Int {
    if (libraryItemId.isBlank()) return 0
    return context.playbackDataStore.data.first()[skipIntroKey(libraryItemId)] ?: 0
  }

  suspend fun setSkipIntro(libraryItemId: String, seconds: Int) {
    if (libraryItemId.isBlank()) return
    context.playbackDataStore.edit { it[skipIntroKey(libraryItemId)] = seconds }
  }

  /** Seconds before the end of a chapter at which playback jumps to the next one. */
  suspend fun getSkipOutro(libraryItemId: String): Int {
    if (libraryItemId.isBlank()) return 0
    return context.playbackDataStore.data.first()[skipOutroKey(libraryItemId)] ?: 0
  }

  suspend fun setSkipOutro(libraryItemId: String, seconds: Int) {
    if (libraryItemId.isBlank()) return
    context.playbackDataStore.edit { it[skipOutroKey(libraryItemId)] = seconds }
  }

  private fun skipIntroKey(libraryItemId: String) = intPreferencesKey("skipIntro_$libraryItemId")

  private fun skipOutroKey(libraryItemId: String) = intPreferencesKey("skipOutro_$libraryItemId")
}
