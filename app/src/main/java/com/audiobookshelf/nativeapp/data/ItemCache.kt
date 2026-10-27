package com.audiobookshelf.nativeapp.data

import com.audiobookshelf.nativeapp.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val TAG = "AbsItemCache"

/** Orphaned tmp files from a process killed mid-write are cleaned up after this long. */
private const val TMP_TTL_MS = 24L * 60 * 60 * 1000

/**
 * Local mirror of the item detail payload (`/api/items/<id>`).
 *
 * The detail screen renders this copy immediately on open — a memory hit synchronously, the disk
 * file inside the load coroutine — and flags the header with a "syncing" badge while the network
 * refresh runs. A successful refresh overwrites the copy; a failure leaves it untouched.
 *
 * Both tiers are scoped to the signed-in user. A 401 deliberately leaves the cache in place so
 * re-authenticating the *same* account still opens instantly, but signing in as a different
 * account must never render the previous account's detail (which carries `userMediaProgress`).
 * Scoping by user id rather than clearing on login keeps that behaviour without a race.
 *
 * Item ids are opaque server tokens (never user input), so they are safe file names. The memory
 * side is a small insertion-ordered map; the disk side is bounded too, otherwise every book ever
 * opened stays in the cache directory forever.
 */
internal class ItemCache(
  private val root: File,
  /** Current account. Null means no signed-in session: never read or write a mirror. */
  private val userId: () -> String?,
  private val maxEntries: Int = 32,
  private val maxDiskEntries: Int = 64
) {

  // Same options as the api client: server payloads carry extra fields across releases.
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }

  private val memory = LinkedHashMap<String, LibraryItemDto>()
  private var memoryOwner: String? = null

  /**
   * Serialises disk access. The tmp file is derived from the target path, so two concurrent
   * persists for the same id would otherwise overwrite each other's tmp and rename a truncated
   * payload into place. Detail writes are rare (one per opened book), so one gate is enough.
   */
  private val diskLock = Mutex()

  /** Keeps the copy in memory and writes it to disk. Disk failures are logged, never thrown. */
  suspend fun persist(item: LibraryItemDto) {
    val owner = userId() ?: return
    synchronized(memory) {
      adoptOwner(owner)
      memory[item.id] = item
      while (memory.size > maxEntries) memory.remove(memory.keys.first())
    }
    withContext(Dispatchers.IO) {
      diskLock.withLock {
        runCatching {
          val base = userDir(owner)
          base.mkdirs()
          // Validate the id *before* creating anything: the tmp file must be derived from the
          // checked target, otherwise an unsafe id could still write outside the cache dir.
          val target = file(base, item.id)
          val tmp = File(target.path + ".tmp")
          tmp.writeText(json.encodeToString(LibraryItemDto.serializer(), item))
          try {
            // REPLACE_EXISTING keeps the previous copy intact if the move itself fails. The old
            // implementation deleted the target before a second rename attempt and lost both.
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
          } catch (error: Exception) {
            tmp.delete()
            throw error
          }
          trim(base, target)
        }.onFailure { AppLog.w(TAG, "item cache write failed", it) }
      }
    }
  }

  /**
   * The memory copy, readable on the main thread without suspending. A changed account drops the
   * old tier right here, so switching users never leaves the previous account's details resident
   * in memory until the next successful fetch.
   */
  fun readMemory(itemId: String): LibraryItemDto? = synchronized(memory) {
    val owner = userId()
    adoptOwner(owner)
    if (owner == null) null else memory[itemId]
  }

  /** Caller must hold the [memory] monitor. */
  private fun adoptOwner(owner: String?) {
    if (memoryOwner != owner) {
      memory.clear()
      memoryOwner = owner
    }
  }

  /** The disk copy, used when the process was restarted since the item was last opened. */
  suspend fun readDisk(itemId: String): LibraryItemDto? = withContext(Dispatchers.IO) {
    val owner = userId() ?: return@withContext null
    diskLock.withLock {
      runCatching {
        val source = file(userDir(owner), itemId)
        if (source.exists()) {
          json.decodeFromString(LibraryItemDto.serializer(), source.readText())
        } else null
      }.onFailure { AppLog.w(TAG, "item cache read failed", it) }.getOrNull()
    }
  }

  /** Drops one item after the server reports it deleted (404), so it cannot be reopened offline. */
  suspend fun evict(itemId: String) {
    val owner = userId() ?: return
    synchronized(memory) { if (memoryOwner == owner) memory.remove(itemId) }
    withContext(Dispatchers.IO) {
      diskLock.withLock {
        runCatching { file(userDir(owner), itemId).delete() }
          .onFailure { AppLog.w(TAG, "item cache evict failed", it) }
      }
    }
  }

  /** Drops every account's memory and disk copies; called on logout / user switch. */
  suspend fun clear() {
    synchronized(memory) {
      memory.clear()
      memoryOwner = null
    }
    withContext(Dispatchers.IO) {
      diskLock.withLock {
        runCatching { root.deleteRecursively() }
          .onFailure { AppLog.w(TAG, "item cache clear failed", it) }
      }
    }
  }

  /**
   * Keeps the disk tier bounded and sweeps tmp files a killed process may have left behind.
   * The file just written is never a candidate: several files can share a lastModified value on
   * coarse-grained filesystems, and an unstable sort must not evict the book on screen.
   */
  private fun trim(base: File, justWritten: File) {
    val entries = base.listFiles { candidate -> candidate.isFile } ?: return
    val now = System.currentTimeMillis()
    entries.filter { it.name.endsWith(".tmp") && now - it.lastModified() > TMP_TTL_MS }
      .forEach { it.delete() }
    val others = entries
      .filter { it.name.endsWith(".json") && it.path != justWritten.path }
      .sortedBy { it.lastModified() }
    val excess = others.size - (maxDiskEntries - 1)
    if (excess > 0) others.take(excess).forEach { it.delete() }
  }

  /** Hashed so an opaque server token can never become a path component. */
  private fun userDir(owner: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
    val key = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }.take(16)
    return File(root, "u_$key")
  }

  /**
   * Item ids are opaque server tokens, but they still arrive over the network: never let one
   * escape the account directory (e.g. `../` or an absolute path). Both callers wrap this in
   * runCatching, so an unsafe id simply skips the disk copy instead of writing elsewhere.
   */
  private fun file(base: File, itemId: String): File {
    val target = File(base, "$itemId.json")
    val allowed = base.canonicalPath
    check(target.canonicalPath.startsWith(allowed + File.separator)) { "unsafe cache id" }
    return target
  }
}
