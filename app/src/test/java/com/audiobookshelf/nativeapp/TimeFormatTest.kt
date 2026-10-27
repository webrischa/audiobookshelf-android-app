package com.audiobookshelf.nativeapp

import com.audiobookshelf.nativeapp.ui.formatDurationShort
import com.audiobookshelf.nativeapp.ui.formatTime
import com.audiobookshelf.nativeapp.ui.sleepRemainingClock
import com.audiobookshelf.nativeapp.ui.sleepRemainingLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Time labels are read by every screen and were easy to get subtly wrong: a countdown that rounds
 * down reads "0 分钟" while the timer is still running, and a remaining time that wraps at an hour
 * hides how long is actually left.
 */
class TimeFormatTest {

  @Test
  fun `position under an hour has no hour field`() {
    assertEquals("0:00", formatTime(0))
    assertEquals("0:07", formatTime(7_000))
    assertEquals("12:34", formatTime(754_000))
  }

  @Test
  fun `position over an hour carries hours`() {
    assertEquals("1:00:00", formatTime(3_600_000))
    assertEquals("2:03:04", formatTime(7_384_000))
  }

  @Test
  fun `negative positions clamp to zero`() {
    assertEquals("0:00", formatTime(-5_000))
  }

  @Test
  fun `short duration shows hours and minutes`() {
    assertEquals("0m", formatDurationShort(0.0))
    assertEquals("5m", formatDurationShort(300.0))
    assertEquals("1h 30m", formatDurationShort(5_400.0))
  }

  @Test
  fun `countdown rounds up so a running timer never reads zero`() {
    assertEquals("1 秒", sleepRemainingLabel(1L))
    assertEquals("1 秒", sleepRemainingLabel(999L))
    assertEquals("2 秒", sleepRemainingLabel(1_001L))
    assertEquals("30 秒", sleepRemainingLabel(30_000L))
    assertEquals("1 分钟", sleepRemainingLabel(60_000L))
    assertEquals("1 小时", sleepRemainingLabel(3_600_000L))
    assertEquals("1 小时 5 分", sleepRemainingLabel(3_900_000L))
  }

  @Test
  fun `clock form is not capped at two digit minutes`() {
    assertEquals("00:30", sleepRemainingClock(30_000L))
    assertEquals("04:00", sleepRemainingClock(240_000L))
    // The picker goes up to four hours; wrapping to "00:00" would hide the remaining time
    assertEquals("240:00", sleepRemainingClock(240 * 60_000L))
  }
}
