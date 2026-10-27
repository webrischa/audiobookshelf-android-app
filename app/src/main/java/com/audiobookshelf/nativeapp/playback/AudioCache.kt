package com.audiobookshelf.nativeapp.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.audiobookshelf.nativeapp.util.AppLog
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Chapter audio on disk, shared by the player and the preloader.
 *
 * The cache exists for one reason: ExoPlayer only starts loading the next playlist item when the
 * current one is about to end, and it never loads anything ahead of a chapter the user jumps to.
 * Every chapter switch therefore pays for a connection, a container parse and a decoder flush
 * before the first sample is audible. Reading the head of the next track into this cache while the
 * current one still plays turns that into a local read.
 *
 * Keys come from the media item (`MediaItem.customCacheKey`), never from the url. Every playback
 * session gets a fresh `/public/session/<id>/track/<n>` url, so a url-keyed cache would miss on
 * every book open and grow with a duplicate of the same file per session.
 */
class AudioCache(context: Context) {
  /**
   * Null when the folder cannot be used (another instance holds the lock, storage is gone). The
   * player then streams straight from the network: no preload, no fast switch, but nothing broken.
   */
  private val cache: SimpleCache? = runCatching {
    SimpleCache(
      File(context.cacheDir, DIRECTORY),
      LeastRecentlyUsedCacheEvictor(MAX_BYTES),
      databaseProvider(context)
    )
  }.onFailure { AppLog.w(TAG, "audio cache unavailable, streaming without it", it) }.getOrNull()

  private val upstream = DefaultHttpDataSource.Factory()
    // Same user agent the player used to send through DefaultMediaSourceFactory: a self-hosted
    // server can sit behind a proxy that looks at it, and the cache must not change the request
    // the server sees.
    .setUserAgent(Util.getUserAgent(context, context.packageName))

  /**
   * What the player reads through. Writes as it plays as well, so a chapter that was listened to
   * once is served from disk when it is replayed, or reached again through a chapter skip.
   */
  val dataSourceFactory: DataSource.Factory = cache?.let { disk ->
    CacheDataSource.Factory()
      .setCache(disk)
      .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(disk))
      .setUpstreamDataSourceFactory(upstream)
      // A cache that cannot be written (storage full, span corrupted by the system clearing the
      // cache dir) must degrade to a plain stream, never fail the load.
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
  } ?: upstream

  /**
   * Reads the head of [uri] into the cache, best effort.
   *
   * Deliberately not a `CacheWriter`/`DownloadManager`: those fetch the whole track (tens of MB for
   * a long chapter) and report progress for something the user never asked to download. A bounded
   * read of the first [PRELOAD_BYTES] is enough to cover the container header plus the intro skip,
   * which is all a chapter switch needs before the rest streams in normally.
   *
   * @return true when the head is in the cache afterwards
   */
  suspend fun preload(uri: Uri, key: String): Boolean = withContext(Dispatchers.IO) {
    val disk = cache ?: return@withContext false
    if (runCatching { disk.isCached(key, 0, PRELOAD_BYTES) }.getOrDefault(false)) {
      return@withContext true
    }
    val source = dataSourceFactory.createDataSource()
    val startedAt = SystemClock.elapsedRealtime()
    try {
      source.open(DataSpec.Builder().setUri(uri).setKey(key).build())
      val buffer = ByteArray(READ_CHUNK_BYTES)
      var read = 0L
      // Stops on the byte budget, on the time budget and on the end of the file. The time budget
      // matters: the read holds the span lock for this key, and the player blocks on that lock if
      // it reaches the same track while a slow connection is still filling it.
      while (isActive && read < PRELOAD_BYTES &&
        SystemClock.elapsedRealtime() - startedAt < TIME_BUDGET_MS
      ) {
        val count = source.read(buffer, 0, buffer.size)
        if (count == C.RESULT_END_OF_INPUT) break
        read += count
      }
      AppLog.i(TAG, "Preloaded ${read / 1024} KB of $key")
      read > 0
    } catch (error: Exception) {
      // Cancellation (book change, service teardown) lands here as an InterruptedIOException on
      // some devices; either way the only loss is the fast start.
      AppLog.i(TAG, "Preload of $key stopped: ${error.message}")
      false
    } finally {
      runCatching { source.close() }
    }
  }

  fun release() {
    runCatching { cache?.release() }
  }

  companion object {
    private const val TAG = "AudioCache"
    private const val DIRECTORY = "chapter-audio"

    /**
     * Process wide on purpose: [SimpleCache] keeps the index database open for as long as it
     * lives, and the playback service is created again on every playback start. One helper per
     * service instance would leave a database connection behind each time.
     */
    @Volatile
    private var database: StandaloneDatabaseProvider? = null

    private fun databaseProvider(context: Context): StandaloneDatabaseProvider =
      database ?: synchronized(this) {
        database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }
      }

    /**
     * Disk budget. Old chapters are evicted first and a preloaded head can always be fetched
     * again, so this only caps how much re-listening stays free.
     */
    private const val MAX_BYTES = 512L * 1024 * 1024

    /** Minutes of speech at any sane audiobook bitrate; covers the container header and the intro skip. */
    private const val PRELOAD_BYTES = 4L * 1024 * 1024

    private const val READ_CHUNK_BYTES = 64 * 1024

    /** Gives up on a slow connection instead of holding the write lock on a key indefinitely. */
    private const val TIME_BUDGET_MS = 30_000L
  }
}
