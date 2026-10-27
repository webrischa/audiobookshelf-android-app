package com.audiobookshelf.nativeapp

import com.audiobookshelf.nativeapp.data.LibraryItemDto
import com.audiobookshelf.nativeapp.data.MediaDto
import com.audiobookshelf.nativeapp.data.ShelfDto
import com.audiobookshelf.nativeapp.data.ShelfSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sort field is stored per library as the server's api name and read back on every shelf open.
 * A value the server no longer accepts (or a renamed field) has to degrade to the default order
 * instead of sending an empty sort to the api.
 */
class ShelfSortTest {

  @Test
  fun `every option round-trips through its api value`() {
    ShelfSort.entries.forEach { sort ->
      assertEquals(sort, ShelfSort.fromApiValue(sort.apiValue))
    }
  }

  @Test
  fun `an unknown or missing value falls back to title`() {
    assertEquals(ShelfSort.TITLE, ShelfSort.fromApiValue(null))
    assertEquals(ShelfSort.TITLE, ShelfSort.fromApiValue(""))
    assertEquals(ShelfSort.TITLE, ShelfSort.fromApiValue("media.metadata.removedField"))
  }

  private fun item(id: String) = LibraryItemDto(id = id)

  @Test
  fun `a book that just started is pinned first and never duplicated`() {
    val fresh = item("fresh")
    val result = orderContinueEntities(
      entities = listOf(item("old"), fresh),
      pinned = listOf(fresh),
      lastUpdate = { 10L },
      isFinished = { false }
    )
    assertEquals(listOf("fresh", "old"), result.map { it.id })
  }

  @Test
  fun `continue listening is ordered by the local progress timestamp`() {
    val stamps = mapOf("a" to 100L, "b" to 900L, "c" to 500L)
    val result = orderContinueEntities(
      entities = listOf(item("a"), item("b"), item("c")),
      pinned = emptyList(),
      lastUpdate = { stamps[it] ?: 0L },
      isFinished = { false }
    )
    assertEquals(listOf("b", "c", "a"), result.map { it.id })
  }

  @Test
  fun `a finished book leaves the continue shelf`() {
    val result = orderContinueEntities(
      entities = listOf(item("a"), item("b")),
      pinned = emptyList(),
      lastUpdate = { 1L },
      isFinished = { it == "a" }
    )
    assertEquals(listOf("b"), result.map { it.id })
  }

  @Test
  fun `the continue shelf is recognised by its id, key or label`() {
    assertTrue(isContinueListeningShelf(ShelfDto(id = "continue-listening", label = "Continue Listening")))
    assertTrue(isContinueListeningShelf(ShelfDto(id = "x", label = "最近播放")))
    assertTrue(isContinueListeningShelf(ShelfDto(id = "x", label = "", labelStringKey = "LabelContinueListening")))
    assertFalse(isContinueListeningShelf(ShelfDto(id = "recently-added", label = "Recently Added")))
    // Other "continue ..." categories are separate shelves and must not be reordered
    assertFalse(isContinueListeningShelf(ShelfDto(id = "continue-series", label = "Continue Series")))
    assertFalse(isContinueListeningShelf(ShelfDto(id = "continue-reading", label = "Continue Reading")))
  }

  @Test
  fun `the first shelf refresh is never throttled`() {
    assertTrue(shouldRefreshHome(lastAt = 0L, now = 1_000L, minGapMs = 20_000L))
  }

  @Test
  fun `navigation and foreground share one refresh budget`() {
    assertFalse(shouldRefreshHome(lastAt = 10_000L, now = 25_000L, minGapMs = 20_000L))
    assertTrue(shouldRefreshHome(lastAt = 10_000L, now = 30_000L, minGapMs = 20_000L))
  }

  @Test
  fun `a shelf row only resets when its own batch changed`() {
    val original = shelfBatchKey(listOf(item("a"), item("b")))
    // Same books in the same order, even from fresh DTOs after a silent refresh: no reset.
    assertEquals(original, shelfBatchKey(listOf(item("a"), item("b"))))
    // A re-randomised batch, a reorder, or a new/removed book: this row resets.
    assertNotEquals(original, shelfBatchKey(listOf(item("b"), item("a"))))
    assertNotEquals(original, shelfBatchKey(listOf(item("a"), item("c"))))
    assertNotEquals(original, shelfBatchKey(listOf(item("a"))))
    // Each row has its own key, so one row's change cannot reset another row.
    val other = shelfBatchKey(listOf(item("x"), item("y")))
    assertEquals(other, shelfBatchKey(listOf(item("x"), item("y"))))
  }

  @Test
  fun `a recomposed screen keeps the restored scroll position`() {
    val before = shelfBatchKey(listOf(item("a"), item("b")))
    // First composition, or returning from a detail/player page: the saved identity is unchanged,
    // so NavHost's restored LazyListState must not be pulled back to the first item.
    assertFalse(batchChanged(before, before))
    assertTrue(batchChanged(before, shelfBatchKey(listOf(item("b"), item("a")))))
    // BookshelfScreen identifies its list by the first item id, with the same rule.
    assertFalse(batchChanged("li_a", "li_a"))
    assertTrue(batchChanged("", "li_a")) // a reload cleared the list, then repopulated it
  }

  @Test
  fun `coverPath decides whether a book has artwork`() {
    // Missing or blank means the server has no cover: the placeholder shows immediately and no
    // image request is made (the original client's !!media.coverPath check).
    assertFalse(LibraryItemDto(id = "a").hasCover())
    assertFalse(LibraryItemDto(id = "a", media = MediaDto(coverPath = "  ")).hasCover())
    assertTrue(LibraryItemDto(id = "a", media = MediaDto(coverPath = "/covers/a.jpg")).hasCover())
  }
}
