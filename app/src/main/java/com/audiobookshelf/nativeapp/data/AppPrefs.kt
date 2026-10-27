package com.audiobookshelf.nativeapp.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appDataStore by preferencesDataStore(name = "abs_app_prefs")

/**
 * Sleep timer as it is stored on disk.
 *
 * The deadline is an absolute wall clock timestamp rather than a remaining duration: it survives
 * the process being frozen (Doze) or killed, and a timer set for 30 minutes still fires 30 minutes
 * later instead of restarting whenever something re-reads the value.
 */
data class SleepTimerState(val endsAtMs: Long = 0L, val minutes: Int = 0) {
  val active: Boolean get() = endsAtMs > 0L
}

/** App-wide appearance settings (not tied to a single book). */
class AppPrefs(private val context: Context) {

  private object Keys {
    val backgroundImagePath = stringPreferencesKey("backgroundImagePath")
    val headsetControl = booleanPreferencesKey("headsetControl")
    val autoResume = booleanPreferencesKey("autoResume")
    val ignoreNotificationSounds = booleanPreferencesKey("ignoreNotificationSounds")
    val backgroundCustom = booleanPreferencesKey("backgroundCustom")
  }

  /** Pause when headphones are unplugged, resume when they come back. */
  suspend fun isHeadsetControlEnabled(): Boolean =
    context.appDataStore.data.first()[Keys.headsetControl] ?: true

  suspend fun setHeadsetControlEnabled(enabled: Boolean) {
    context.appDataStore.edit { it[Keys.headsetControl] = enabled }
  }

  /** True once the user picked their own background; false = bundled default. */
  suspend fun isBackgroundCustom(): Boolean =
    context.appDataStore.data.first()[Keys.backgroundCustom] ?: false

  suspend fun setBackgroundCustom(value: Boolean) {
    context.appDataStore.edit { it[Keys.backgroundCustom] = value }
  }

  /** Keep playing when apps like DingTalk/WeChat play a short notification sound. */
  suspend fun isIgnoreNotificationSoundsEnabled(): Boolean =
    context.appDataStore.data.first()[Keys.ignoreNotificationSounds] ?: true

  suspend fun setIgnoreNotificationSounds(enabled: Boolean) {
    context.appDataStore.edit { it[Keys.ignoreNotificationSounds] = enabled }
  }

  /** Resume playback after an interruption that was not caused by the user. */
  suspend fun isAutoResumeEnabled(): Boolean =
    context.appDataStore.data.first()[Keys.autoResume] ?: true

  suspend fun setAutoResumeEnabled(enabled: Boolean) {
    context.appDataStore.edit { it[Keys.autoResume] = enabled }
  }

  fun observeIgnoreNotificationSounds(): Flow<Boolean> =
    context.appDataStore.data
      .catch { emit(emptyPreferences()) }
      .map { it[Keys.ignoreNotificationSounds] ?: true }
      .distinctUntilChanged()

  fun observeAutoResume(): Flow<Boolean> =
    context.appDataStore.data
      .catch { emit(emptyPreferences()) }
      .map { it[Keys.autoResume] ?: true }
      .distinctUntilChanged()

  suspend fun backgroundImagePath(): String? =
    context.appDataStore.data.first()[Keys.backgroundImagePath]

  suspend fun setBackgroundImagePath(path: String?) {
    context.appDataStore.edit { prefs ->
      if (path.isNullOrBlank()) prefs.remove(Keys.backgroundImagePath) else prefs[Keys.backgroundImagePath] = path
    }
  }

  // Shelf presentation is remembered per library: each library can have its own view mode and
  // series folding state.
  private fun shelfListViewKey(libraryId: String) = booleanPreferencesKey("shelfListView_$libraryId")

  private fun shelfCollapseSeriesKey(libraryId: String) = booleanPreferencesKey("shelfCollapseSeries_$libraryId")

  /** Grid (false) or list (true) view for this library's shelf. */
  suspend fun shelfListView(libraryId: String): Boolean =
    context.appDataStore.data.first()[shelfListViewKey(libraryId)] ?: false

  suspend fun setShelfListView(libraryId: String, listView: Boolean) {
    context.appDataStore.edit { it[shelfListViewKey(libraryId)] = listView }
  }

  /** Whether series are folded into one entry on this library's shelf. */
  suspend fun shelfCollapseSeries(libraryId: String): Boolean =
    context.appDataStore.data.first()[shelfCollapseSeriesKey(libraryId)] ?: false

  suspend fun setShelfCollapseSeries(libraryId: String, collapsed: Boolean) {
    context.appDataStore.edit { it[shelfCollapseSeriesKey(libraryId)] = collapsed }
  }

  // v2 keys: the default order changed to title ascending, so previously stored choices are
  // intentionally ignored instead of pinning an old field.
  private fun shelfSortKey(libraryId: String) = stringPreferencesKey("shelfSortV2_$libraryId")

  private fun shelfSortDescKey(libraryId: String) = booleanPreferencesKey("shelfSortDescV2_$libraryId")

  /** Server sort field for this library's shelf, null when it was never changed. */
  suspend fun shelfSort(libraryId: String): String? =
    context.appDataStore.data.first()[shelfSortKey(libraryId)]

  suspend fun setShelfSort(libraryId: String, sort: String) {
    context.appDataStore.edit { it[shelfSortKey(libraryId)] = sort }
  }

  suspend fun shelfSortDesc(libraryId: String): Boolean =
    context.appDataStore.data.first()[shelfSortDescKey(libraryId)] ?: false

  suspend fun setShelfSortDesc(libraryId: String, descending: Boolean) {
    context.appDataStore.edit { it[shelfSortDescKey(libraryId)] = descending }
  }

  private val skipSecondsKey = intPreferencesKey("skipSeconds")

  /** How far the rewind/forward buttons jump, in seconds. */
  suspend fun skipSeconds(): Int =
    context.appDataStore.data.first()[skipSecondsKey] ?: 15

  suspend fun setSkipSeconds(seconds: Int) {
    context.appDataStore.edit { it[skipSecondsKey] = seconds }
  }

  private val sleepTimerEndsAtKey = longPreferencesKey("sleepTimerEndsAt")
  private val sleepTimerMinutesKey = intPreferencesKey("sleepTimerMinutes")

  /**
   * The sleep timer lives in the same store the playback service reads, not in the view model.
   *
   * A timer owned by the view model dies with it: swiping the task away (or the OEM killing the
   * activity) left the countdown running in the ui while nothing was left to pause playback, and
   * the "timer fired" moment was silently lost. Both the ui and the service observe this value.
   */
  fun observeSleepTimer(): Flow<SleepTimerState> =
    context.appDataStore.data
      .catch { emit(emptyPreferences()) }
      .map { prefs ->
        SleepTimerState(
          endsAtMs = prefs[sleepTimerEndsAtKey] ?: 0L,
          minutes = prefs[sleepTimerMinutesKey] ?: 0
        )
      }
      .distinctUntilChanged()

  suspend fun sleepTimer(): SleepTimerState =
    context.appDataStore.data.first().let { prefs ->
      SleepTimerState(
        endsAtMs = prefs[sleepTimerEndsAtKey] ?: 0L,
        minutes = prefs[sleepTimerMinutesKey] ?: 0
      )
    }

  /** Stores a running timer; [endsAtMs] is an absolute epoch timestamp. */
  suspend fun setSleepTimer(endsAtMs: Long, minutes: Int) {
    context.appDataStore.edit {
      it[sleepTimerEndsAtKey] = endsAtMs
      it[sleepTimerMinutesKey] = minutes
    }
  }

  suspend fun clearSleepTimer() {
    context.appDataStore.edit {
      it.remove(sleepTimerEndsAtKey)
      it.remove(sleepTimerMinutesKey)
    }
  }
}
