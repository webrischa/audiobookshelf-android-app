package com.audiobookshelf.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chapter selection has to start audio in every state it can be tapped from. The decision is pure
 * (see [chapterJumpFor]) so the paused / cold-start / loading cases are pinned down here instead
 * of depending on a device-only interaction.
 */
class ChapterJumpTest {

  @Test
  fun `a loaded player always seeks and plays, even while a book is staged or loading`() {
    assertEquals(
      ChapterJump.SeekAndPlay,
      chapterJumpFor(hasMedia = true, hasStagedBook = false, loadingSession = false)
    )
    assertEquals(
      ChapterJump.SeekAndPlay,
      chapterJumpFor(hasMedia = true, hasStagedBook = true, loadingSession = true)
    )
  }

  @Test
  fun `a cold start with only a staged book creates the session at the chapter`() {
    assertEquals(
      ChapterJump.LoadSession,
      chapterJumpFor(hasMedia = false, hasStagedBook = true, loadingSession = false)
    )
  }

  @Test
  fun `a second tap while the session is being created is ignored`() {
    assertEquals(
      ChapterJump.Ignore,
      chapterJumpFor(hasMedia = false, hasStagedBook = true, loadingSession = true)
    )
  }

  @Test
  fun `with no media and no book there is nothing to play`() {
    assertEquals(
      ChapterJump.Ignore,
      chapterJumpFor(hasMedia = false, hasStagedBook = false, loadingSession = false)
    )
  }
}
