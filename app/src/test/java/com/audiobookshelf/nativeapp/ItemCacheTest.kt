package com.audiobookshelf.nativeapp.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The detail screen renders this mirror before the network answers, so the round trip
 * persist -> (re)read has to be lossless, a broken file must never crash the screen, and no
 * account may ever see another account's copy.
 */
class ItemCacheTest {

  private val tempDirs = mutableListOf<File>()

  private fun newDir(): File =
    Files.createTempDirectory("itemcache").toFile().also { tempDirs += it }

  /** The first account directory created under [root]; lets a test plant a broken file. */
  private fun accountDir(root: File): File =
    root.listFiles()!!.first { it.isDirectory && it.name.startsWith("u_") }

  private fun cache(
    root: File,
    userId: () -> String? = { "u1" },
    maxEntries: Int = 32,
    maxDiskEntries: Int = 64
  ) = ItemCache(root, userId, maxEntries, maxDiskEntries)

  @After
  fun cleanUp() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  @Test
  fun `a persisted copy reads back unchanged`() = runBlocking {
    val store = cache(newDir())
    store.persist(LibraryItemDto(id = "li_a"))
    assertEquals("li_a", store.readMemory("li_a")?.id)
    assertEquals("li_a", store.readDisk("li_a")?.id)
  }

  @Test
  fun `a restarted process loses memory but still reads the disk copy`() = runBlocking {
    val dir = newDir()
    cache(dir).persist(LibraryItemDto(id = "li_a"))
    val fresh = cache(dir)
    assertNull(fresh.readMemory("li_a"))
    assertEquals("li_a", fresh.readDisk("li_a")?.id)
  }

  @Test
  fun `a broken cache file reads back as null instead of crashing`() = runBlocking {
    val dir = newDir()
    cache(dir).persist(LibraryItemDto(id = "seed"))
    File(accountDir(dir), "li_broken.json").writeText("{ not json")
    assertNull(cache(dir).readDisk("li_broken"))
    assertNull(cache(dir).readDisk("li_missing"))
  }

  @Test
  fun `the memory side keeps only the newest entries`() = runBlocking {
    val store = cache(newDir(), maxEntries = 2)
    store.persist(LibraryItemDto(id = "a"))
    store.persist(LibraryItemDto(id = "b"))
    store.persist(LibraryItemDto(id = "c"))
    assertNull(store.readMemory("a"))
    assertEquals("b", store.readMemory("b")?.id)
    assertEquals("c", store.readMemory("c")?.id)
  }

  @Test
  fun `clear drops both memory and disk copies`() = runBlocking {
    val store = cache(newDir())
    store.persist(LibraryItemDto(id = "li_a"))
    store.clear()
    assertNull(store.readMemory("li_a"))
    assertNull(store.readDisk("li_a"))
  }

  @Test
  fun `an item id cannot write outside the cache directory`() = runBlocking {
    // A private parent so `../escaped` resolves into an empty, test-owned directory rather than
    // the shared system temp root (where a previous run could have left a file behind).
    val parent = Files.createTempDirectory("itemcache-parent").toFile().also { tempDirs += it }
    val dir = File(parent, "items")
    cache(dir).persist(LibraryItemDto(id = "../escaped"))
    assertNull(cache(dir).readDisk("../escaped"))
    assertFalse(File(dir, "escaped.json").exists())
    // The tmp file must be checked too: validate before writing, not only before renaming.
    assertFalse(File(dir, "escaped.json.tmp").exists())
    assertFalse(File(parent, "escaped.json").exists())
    assertFalse(File(parent, "escaped.json.tmp").exists())
  }

  @Test
  fun `a different account never reads the previous account's mirror`() = runBlocking {
    val dir = newDir()
    var current: String? = "u1"
    val store = cache(dir, userId = { current })
    store.persist(LibraryItemDto(id = "li_a"))

    current = "u2"
    assertNull(store.readMemory("li_a"))
    assertNull(store.readDisk("li_a"))

    current = "u1"
    assertNull(store.readMemory("li_a")) // memory was handed over on the switch
    assertEquals("li_a", store.readDisk("li_a")?.id) // but the same account keeps its copy
  }

  @Test
  fun `a 401 with no session neither reads nor writes a mirror`() = runBlocking {
    val dir = newDir()
    var current: String? = "u1"
    val store = cache(dir, userId = { current })
    store.persist(LibraryItemDto(id = "li_a"))
    current = null
    assertNull(store.readMemory("li_a"))
    assertNull(store.readDisk("li_a"))
    store.persist(LibraryItemDto(id = "li_b"))
    assertNull(store.readMemory("li_b"))
    // Re-authenticating the same account still opens instantly: that is why 401 does not clear.
    current = "u1"
    assertEquals("li_a", store.readDisk("li_a")?.id)
    assertNull(store.readDisk("li_b"))
  }

  @Test
  fun `concurrent persists for one id never truncate the file`() = runBlocking {
    val store = cache(newDir())
    coroutineScope {
      (0 until 24).map { index ->
        async {
          store.persist(LibraryItemDto(id = "li_shared", libraryId = "payload-$index"))
        }
      }.awaitAll()
    }
    // A shared tmp file used to let one writer rename another's half-written payload into place.
    val written = store.readDisk("li_shared")
    assertEquals("li_shared", written?.id)
    assertFalse(written?.libraryId.isNullOrEmpty())
  }

  @Test
  fun `the disk tier stays bounded`() = runBlocking {
    val store = cache(newDir(), maxDiskEntries = 2)
    store.persist(LibraryItemDto(id = "a"))
    store.persist(LibraryItemDto(id = "b"))
    store.persist(LibraryItemDto(id = "c"))
    assertNull(store.readDisk("a"))
    assertEquals("b", store.readDisk("b")?.id)
    assertEquals("c", store.readDisk("c")?.id)
  }

  @Test
  fun `a one-entry cap still keeps the book just written`() = runBlocking {
    val store = cache(newDir(), maxDiskEntries = 1)
    store.persist(LibraryItemDto(id = "a"))
    store.persist(LibraryItemDto(id = "b"))
    assertNull(store.readDisk("a"))
    assertEquals("b", store.readDisk("b")?.id)
  }

  @Test
  fun `evict drops only the deleted item`() = runBlocking {
    val store = cache(newDir())
    store.persist(LibraryItemDto(id = "gone"))
    store.persist(LibraryItemDto(id = "kept"))
    store.evict("gone")
    assertNull(store.readMemory("gone"))
    assertNull(store.readDisk("gone"))
    assertEquals("kept", store.readDisk("kept")?.id)
  }
}
