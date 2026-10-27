package com.audiobookshelf.nativeapp.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "abs_session")

/**
 * The two addresses a connection consists of, without any user identity.
 *
 * Produced by [SessionStore.clearUser] so the login screen can be prefilled after a user switch.
 */
data class SavedConnection(
  val serverUrl: String?,
  val localServerUrl: String?
)

/**
 * Persists the server connection. The token is also cached in memory because the playback
 * service needs it synchronously when reporting progress.
 */
class SessionStore(private val context: Context) {
  private object Keys {
    val serverUrl = stringPreferencesKey("serverUrl")
    val localServerUrl = stringPreferencesKey("localServerUrl")
    val token = stringPreferencesKey("token")
    val userId = stringPreferencesKey("userId")
    val username = stringPreferencesKey("username")
    val defaultLibraryId = stringPreferencesKey("defaultLibraryId")
    val lastLibraryId = stringPreferencesKey("lastLibraryId")
  }

  @Volatile
  var current: Session? = null
    private set

  suspend fun load(): Session? {
    val prefs = context.dataStore.data.first()
    val serverUrl = prefs[Keys.serverUrl] ?: return null
    val token = prefs[Keys.token] ?: return null
    val session = Session(
      serverUrl = serverUrl,
      token = token,
      userId = prefs[Keys.userId].orEmpty(),
      username = prefs[Keys.username].orEmpty(),
      defaultLibraryId = prefs[Keys.defaultLibraryId],
      localServerUrl = prefs[Keys.localServerUrl]
    )
    current = session
    return session
  }

  suspend fun save(session: Session) {
    context.dataStore.edit { prefs ->
      prefs[Keys.serverUrl] = session.serverUrl
      prefs[Keys.token] = session.token
      prefs[Keys.userId] = session.userId
      prefs[Keys.username] = session.username
      session.defaultLibraryId?.let { prefs[Keys.defaultLibraryId] = it }
      val local = session.localServerUrl
      if (local.isNullOrBlank()) prefs.remove(Keys.localServerUrl) else prefs[Keys.localServerUrl] = local
    }
    current = session
  }

  suspend fun clear() {
    context.dataStore.edit { it.clear() }
    current = null
  }

  /** Addresses kept in the store, used to prefill the login form. */
  suspend fun savedConnection(): SavedConnection {
    val prefs = context.dataStore.data.first()
    return SavedConnection(
      serverUrl = prefs[Keys.serverUrl],
      localServerUrl = prefs[Keys.localServerUrl]
    )
  }

  /**
   * Drops the signed-in user but keeps the addresses, so "switch user" only has to ask for the
   * credentials of the next account.
   *
   * The token is what [load] requires, so removing it is enough to keep the session from being
   * restored on the next launch while the addresses survive.
   */
  suspend fun clearUser(): SavedConnection {
    val kept = savedConnection()
    context.dataStore.edit { prefs ->
      prefs.remove(Keys.token)
      prefs.remove(Keys.userId)
      prefs.remove(Keys.username)
      prefs.remove(Keys.defaultLibraryId)
      prefs.remove(Keys.lastLibraryId)
    }
    current = null
    return kept
  }

  /** Last library the user opened, used to jump straight back into it on the next launch. */
  suspend fun getLastLibraryId(): String? {
    return context.dataStore.data.first()[Keys.lastLibraryId]
  }

  suspend fun setLastLibraryId(libraryId: String) {
    context.dataStore.edit { it[Keys.lastLibraryId] = libraryId }
  }

  suspend fun clearLastLibraryId() {
    context.dataStore.edit { it.remove(Keys.lastLibraryId) }
  }

  val usernameFlow = context.dataStore.data.map { it[Keys.username] }
}
