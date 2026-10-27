package com.audiobookshelf.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shelf has two coordinate systems (grid rows vs single list items). These cases lock the
 * "return keeps the position / re-enter starts at the top" semantics without a Compose runtime.
 */
class ShelfScrollMirrorTest {

  @Test
  fun `grid and list positions never seed each other`() {
    val mirror = ShelfScrollMirror()
    mirror.startSession("entry-1")
    mirror.save(listView = false, index = 12, offset = 40)
    mirror.save(listView = true, index = 90, offset = 8)

    val seed = mirror.startSession("entry-1")
    assertEquals(12, seed.gridIndex)
    assertEquals(40, seed.gridOffset)
    assertEquals(90, seed.listIndex)
    assertEquals(8, seed.listOffset)
    assertEquals(12, mirror.index(listView = false))
    assertEquals(90, mirror.index(listView = true))
  }

  @Test
  fun `a fresh entry starts both views at the top`() {
    val mirror = ShelfScrollMirror()
    mirror.startSession("a")
    mirror.save(listView = false, index = 12, offset = 40)
    mirror.save(listView = true, index = 90, offset = 8)

    assertEquals(ShelfScrollMirror.Seed(0, 0, 0, 0), mirror.startSession("b"))
    assertEquals(0, mirror.index(listView = false))
    assertEquals(0, mirror.index(listView = true))
  }

  @Test
  fun `reset clears both coordinates without starting a new session`() {
    val mirror = ShelfScrollMirror()
    mirror.startSession("a")
    mirror.save(listView = false, index = 12, offset = 40)
    mirror.save(listView = true, index = 90, offset = 8)
    mirror.reset()

    assertEquals(ShelfScrollMirror.Seed(0, 0, 0, 0), mirror.startSession("a"))
    assertEquals(0, mirror.offset(listView = false))
    assertEquals(0, mirror.offset(listView = true))
  }
}
