package com.audiobookshelf.nativeapp

import com.audiobookshelf.nativeapp.playback.resumeElapsedMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-resume give-up window is a wall-clock budget. Adding the *planned* delay used to keep
 * the retry (and its wake lock) alive indefinitely on a device frozen in Doze.
 */
class ResumeBudgetTest {

  @Test
  fun `an ordinary wait spends its measured duration`() {
    assertEquals(2_040L, resumeElapsedMs(2_000L, 2_040L))
  }

  @Test
  fun `a doze-stretched wait spends the stretched duration`() {
    assertEquals(120_000L, resumeElapsedMs(2_000L, 120_000L))
  }

  @Test
  fun `the planned delay stays as a floor when the clock moves backwards`() {
    assertEquals(2_000L, resumeElapsedMs(2_000L, 10L))
    assertEquals(2_000L, resumeElapsedMs(2_000L, -5L))
  }

  @Test
  fun `a stretched wait crosses the give-up window on real time`() {
    val windowMs = 120_000L
    val blockedMs = 118_000L + resumeElapsedMs(2_000L, 90_000L)
    assertTrue(blockedMs > windowMs)
  }
}
