package com.audiobookshelf.nativeapp

import com.audiobookshelf.nativeapp.playback.StreamRetryAction
import com.audiobookshelf.nativeapp.playback.decideStreamRetry
import com.audiobookshelf.nativeapp.playback.nextStreamRetryDelayMs
import org.junit.Assert.assertEquals
import org.junit.Test

// Player.STATE_* are compile-time constants; keep the tests free of an android.jar stub.
private const val IDLE = 1
private const val BUFFERING = 2
private const val READY = 3
private const val TOTAL_WINDOW_MS = 600_000L
private const val BACKOFF_CAP_MS = 30_000L

/**
 * Pins down when a stalled stream may be retried. A late network callback must not resurrect a
 * book the user paused, nor re-prepare a player that is already buffering; and once the timed
 * budget is spent the stream must keep waiting for recovery instead of stopping for good.
 */
class StreamRetryTest {

  private fun decide(
    playWhenReady: Boolean = true,
    hasMedia: Boolean = true,
    playbackState: Int = IDLE,
    retriesLeft: Int = 6,
    networkSignal: Boolean = false,
    elapsedMs: Long = 0L,
    totalBudgetMs: Long = TOTAL_WINDOW_MS
  ) = decideStreamRetry(
    playWhenReady, hasMedia, playbackState, retriesLeft, networkSignal, elapsedMs, totalBudgetMs
  )

  @Test
  fun `timed retries fire while the budget lasts`() {
    assertEquals(StreamRetryAction.RETRY, decide())
    assertEquals(StreamRetryAction.RETRY, decide(retriesLeft = 1, elapsedMs = 90_000L))
  }

  @Test
  fun `a deliberate pause is never resurrected by a network callback`() {
    assertEquals(
      StreamRetryAction.STOP,
      decide(playWhenReady = false, networkSignal = true, retriesLeft = 6)
    )
  }

  @Test
  fun `a cleared playlist is never resurrected`() {
    assertEquals(StreamRetryAction.STOP, decide(hasMedia = false, networkSignal = true))
  }

  @Test
  fun `a buffering or ready player is not prepared again`() {
    assertEquals(StreamRetryAction.STOP, decide(playbackState = BUFFERING, networkSignal = true))
    assertEquals(StreamRetryAction.STOP, decide(playbackState = READY, networkSignal = true))
  }

  @Test
  fun `an exhausted timed budget waits instead of stopping`() {
    assertEquals(StreamRetryAction.WAIT, decide(retriesLeft = 0))
  }

  @Test
  fun `network recovery wakes an exhausted budget`() {
    assertEquals(StreamRetryAction.RETRY, decide(retriesLeft = 0, networkSignal = true))
  }

  @Test
  fun `the total window wins over any retry trigger`() {
    assertEquals(
      StreamRetryAction.STOP,
      decide(retriesLeft = 6, networkSignal = true, elapsedMs = TOTAL_WINDOW_MS + 1)
    )
  }

  @Test
  fun `the deadline itself is still inside the window`() {
    assertEquals(
      StreamRetryAction.RETRY,
      decide(retriesLeft = 0, networkSignal = true, elapsedMs = TOTAL_WINDOW_MS)
    )
  }

  @Test
  fun `backoff doubles and then stays capped`() {
    assertEquals(4_000L, nextStreamRetryDelayMs(2_000L, BACKOFF_CAP_MS))
    assertEquals(8_000L, nextStreamRetryDelayMs(4_000L, BACKOFF_CAP_MS))
    assertEquals(16_000L, nextStreamRetryDelayMs(8_000L, BACKOFF_CAP_MS))
    assertEquals(BACKOFF_CAP_MS, nextStreamRetryDelayMs(16_000L, BACKOFF_CAP_MS))
    assertEquals(BACKOFF_CAP_MS, nextStreamRetryDelayMs(BACKOFF_CAP_MS, BACKOFF_CAP_MS))
  }
}
