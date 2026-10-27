package com.audiobookshelf.nativeapp

/**
 * Mirrors the bookshelf scroll position across navigation, kept free of Android/Compose types so
 * the session semantics can be covered by JVM tests.
 *
 * Grid and list view are different coordinate systems: a grid index counts rows of several books,
 * a list index counts single books. One shared mirror used to seed the inactive view with the
 * active view's index, so switching view modes jumped to unrelated books.
 *
 * One browsing session is one NavBackStackEntry: returning from a detail page keeps both
 * positions, while a fresh entry into the shelf starts both at the top.
 */
internal class ShelfScrollMirror {
  private var gridIndex = 0
  private var gridOffset = 0
  private var listIndex = 0
  private var listOffset = 0
  private var entryId: String? = null

  data class Seed(
    val gridIndex: Int,
    val gridOffset: Int,
    val listIndex: Int,
    val listOffset: Int
  )

  /** Starts or resumes a browsing session and returns the seeds for both lazy states. */
  fun startSession(id: String): Seed {
    if (entryId != id) {
      entryId = id
      reset()
    }
    return Seed(gridIndex, gridOffset, listIndex, listOffset)
  }

  fun save(listView: Boolean, index: Int, offset: Int) {
    if (listView) {
      listIndex = index
      listOffset = offset
    } else {
      gridIndex = index
      gridOffset = offset
    }
  }

  fun index(listView: Boolean): Int = if (listView) listIndex else gridIndex
  fun offset(listView: Boolean): Int = if (listView) listOffset else gridOffset

  /** Sorting, folding or switching library replaces the content: both coordinates are stale. */
  fun reset() {
    gridIndex = 0
    gridOffset = 0
    listIndex = 0
    listOffset = 0
  }
}
