package com.audiobookshelf.nativeapp

import com.audiobookshelf.nativeapp.playback.shouldHoldPlaybackForeground
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down when this app considers playback still intended during a stall. On media3 <= 1.4.1
 * the session was taken out of the foreground as soon as playWhenReady turned false, which is what
 * let the system stop playback between two episodes until the app was opened again. Since 1.6.0
 * media3 owns that decision with its own 10-minute user-engaged window; this policy now governs
 * our wake lock and bookkeeping only.
 */
class ForegroundHoldTest {

  @Test
  fun `active playback always stays in the foreground`() {
    assertTrue(
      shouldHoldPlaybackForeground(
        hasMedia = true, playWhenReady = true, resumeIntent = false, holdUntil = 0L, now = 1_000L
      )
    )
  }

  @Test
  fun `an interruption waiting to be undone stays in the foreground`() {
    assertTrue(
      shouldHoldPlaybackForeground(
        hasMedia = true, playWhenReady = false, resumeIntent = true, holdUntil = 0L, now = 1_000L
      )
    )
  }

  @Test
  fun `a pause is covered by the grace window`() {
    assertTrue(
      shouldHoldPlaybackForeground(
        hasMedia = true, playWhenReady = false, resumeIntent = false,
        holdUntil = 10_000L, now = 9_999L
      )
    )
  }

  @Test
  fun `the grace window expires`() {
    assertFalse(
      shouldHoldPlaybackForeground(
        hasMedia = true, playWhenReady = false, resumeIntent = false,
        holdUntil = 10_000L, now = 10_000L
      )
    )
  }

  @Test
  fun `a cleared playlist never holds the foreground`() {
    assertFalse(
      shouldHoldPlaybackForeground(
        hasMedia = false, playWhenReady = true, resumeIntent = true,
        holdUntil = Long.MAX_VALUE, now = 1_000L
      )
    )
  }
}
