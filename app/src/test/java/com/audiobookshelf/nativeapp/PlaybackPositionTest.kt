package com.audiobookshelf.nativeapp

import androidx.media3.common.Player
import com.audiobookshelf.nativeapp.playback.positionFallbackToLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The service only logs a backward position jump; it must never seek in response to one.
 * These cases pin down what counts as a candidate so the observation window cannot silently
 * grow back into a guess-and-correct heuristic.
 */
class PlaybackPositionTest {

  private fun fallback(
    reason: Int = Player.DISCONTINUITY_REASON_INTERNAL,
    oldTrack: Int = 3,
    newTrack: Int = 3,
    oldMs: Long = 12_000L,
    newMs: Long = 11_000L
  ) = positionFallbackToLog(reason, oldTrack, newTrack, oldMs, newMs)

  @Test
  fun `an internal backward jump inside one track is observable`() {
    assertEquals(1_000L, fallback()!!)
  }

  @Test
  fun `an auto transition inside one track is observable`() {
    assertEquals(1_000L, fallback(reason = Player.DISCONTINUITY_REASON_AUTO_TRANSITION)!!)
  }

  @Test
  fun `a user seek is never observable`() {
    assertNull(fallback(reason = Player.DISCONTINUITY_REASON_SEEK))
  }

  @Test
  fun `a chapter change belongs to the transition machinery`() {
    assertNull(fallback(newTrack = 4))
  }

  @Test
  fun `a forward jump and sub-second noise are ignored`() {
    assertNull(fallback(oldMs = 10_000L, newMs = 11_000L))
    assertNull(fallback(newMs = 11_700L))
  }

  @Test
  fun `a large jump is a real seek, not a duplicated seam`() {
    assertNull(fallback(newMs = 3_000L))
  }

  @Test
  fun `the observation window boundaries are inclusive`() {
    assertEquals(400L, fallback(oldMs = 10_000L, newMs = 9_600L)!!)
    assertEquals(8_000L, fallback(oldMs = 10_000L, newMs = 2_000L)!!)
    assertNull(fallback(oldMs = 10_000L, newMs = 1_999L))
  }
}
