package com.audiobookshelf.nativeapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Connection settings as one immutable snapshot. The URL and the token must come from the same
 * value: [AbsApi.configure] used to write two independent volatile fields, so a request racing an
 * address retry could build a URL for the new server with the old token — or with a blank base.
 */
private data class ApiConfig(val baseUrl: String = "", val token: String? = null)

/**
 * Thin Audiobookshelf REST client. The server is self hosted, so the base URL is user supplied
 * and may be plain http (cleartext is enabled in the manifest for that reason).
 */
class AbsApi {
  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }

  private val jsonMedia = "application/json; charset=utf-8".toMediaType()

  @Volatile
  private var config = ApiConfig()

  fun configure(serverUrl: String, authToken: String?) {
    config = ApiConfig(serverUrl.trimEnd('/'), authToken)
  }

  private val probeClient = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(2, TimeUnit.SECONDS)
    .callTimeout(3, TimeUnit.SECONDS)
    .build()

  /**
   * Cheap reachability check used to choose between the LAN and the remote address.
   * Returns false for any failure instead of throwing.
   */
  suspend fun ping(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      val request = Request.Builder().url(serverUrl.trimEnd('/') + "/ping").build()
      probeClient.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)
  }

  suspend fun login(serverUrl: String, username: String, password: String): LoginResponse = withContext(Dispatchers.IO) {
    val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password))
    val text = execute("/login", "POST", body, auth = false, base = serverUrl.trimEnd('/'))
    json.decodeFromString(LoginResponse.serializer(), text)
  }

  suspend fun me(): MeResponse = withContext(Dispatchers.IO) {
    val text = execute("/api/me", "GET")
    json.decodeFromString(MeResponse.serializer(), text)
  }

  suspend fun libraries(): List<LibraryDto> = withContext(Dispatchers.IO) {
    val text = execute("/api/libraries", "GET")
    json.decodeFromString(LibrariesResponse.serializer(), text).libraries
  }

  suspend fun items(
    libraryId: String,
    page: Int,
    limit: Int = 40,
    collapseSeries: Boolean = false,
    sort: String = "media.metadata.title",
    desc: Boolean = false
  ): ItemsResponse = withContext(Dispatchers.IO) {
    // collapseseries is handled by the server: one entry per series instead of one per book
    val collapse = if (collapseSeries) "&collapseseries=1" else ""
    val order = "&desc=${if (desc) 1 else 0}"
    val path = "/api/libraries/$libraryId/items?limit=$limit&page=$page&sort=$sort$order&minified=1$collapse"
    val text = execute(path, "GET")
    json.decodeFromString(ItemsResponse.serializer(), text)
  }

  suspend fun item(itemId: String): LibraryItemDto = withContext(Dispatchers.IO) {
    val text = execute("/api/items/$itemId", "GET")
    json.decodeFromString(LibraryItemDto.serializer(), text)
  }

  suspend fun search(libraryId: String, query: String): SearchResponse = withContext(Dispatchers.IO) {
    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
    val text = execute("/api/libraries/$libraryId/search?q=$encoded", "GET")
    json.decodeFromString(SearchResponse.serializer(), text)
  }

  /** Personalized home shelves: continue listening, recently added, discover, ... */
  suspend fun personalized(libraryId: String): List<ShelfDto> = withContext(Dispatchers.IO) {
    val path = "/api/libraries/$libraryId/personalized?minified=1&include=rssfeed,numEpisodesIncomplete"
    val text = execute(path, "GET")
    json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ShelfDto.serializer()), text)
  }

  suspend fun startPlayback(itemId: String, deviceId: String): PlaybackSessionDto = withContext(Dispatchers.IO) {
    val body = json.encodeToString(PlayRequest.serializer(), PlayRequest(DeviceInfoDto(deviceId)))
    val text = execute("/api/items/$itemId/play", "POST", body)
    json.decodeFromString(PlaybackSessionDto.serializer(), text)
  }

  suspend fun updateProgress(itemId: String, update: ProgressUpdate) = withContext(Dispatchers.IO) {
    val body = json.encodeToString(ProgressUpdate.serializer(), update)
    execute("/api/me/progress/$itemId", "PATCH", body)
    Unit
  }

  /**
   * Progress stored on the server for one item, null when this account never played it.
   *
   * Only a 404 means "no progress yet". Everything else - in particular a 401 - has to reach the
   * caller: swallowing it here used to hide an expired token while the app kept showing stale
   * shelves and the listener kept listening with no idea why nothing updated.
   */
  suspend fun remoteProgress(itemId: String): ProgressDto? = withContext(Dispatchers.IO) {
    try {
      val text = execute("/api/me/progress/$itemId", "GET")
      json.decodeFromString(ProgressDto.serializer(), text)
    } catch (error: ApiException) {
      if (error.code == 404) null else throw error
    }
  }

  /**
   * [path] is resolved against one [config] snapshot, and the Authorization header comes from
   * that same snapshot. [base] overrides it only for the unauthenticated login probe, which is
   * aimed at an address that has not been adopted yet.
   */
  private fun execute(
    path: String,
    method: String,
    body: String? = null,
    auth: Boolean = true,
    base: String? = null
  ): String {
    val cfg = config
    val builder = Request.Builder().url((base ?: cfg.baseUrl) + path)
    if (auth) cfg.token?.let { builder.header("Authorization", "Bearer $it") }
    builder.method(method, body?.toRequestBody(jsonMedia))
    client.newCall(builder.build()).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) throw httpException(response.code, text)
      return text
    }
  }
}
