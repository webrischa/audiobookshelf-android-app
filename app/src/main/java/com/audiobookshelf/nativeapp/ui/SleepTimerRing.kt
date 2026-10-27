package com.audiobookshelf.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Milliseconds left on the sleep timer, re-read from the wall clock once per second.
 *
 * Returns null when no timer is set or when it has just run out, so callers can simply skip
 * drawing anything. The countdown itself lives in the view model and pauses playback at
 * [endsAtMs]; this only mirrors it, and reading the clock instead of counting ticks means the
 * value is still right after the app sat in the background with a stopped frame clock.
 */
@Composable
fun rememberSleepTimerRemaining(endsAtMs: Long): Long? {
  var remainingMs by remember(endsAtMs) { mutableLongStateOf(leftUntil(endsAtMs)) }
  LaunchedEffect(endsAtMs) {
    if (endsAtMs <= 0L) return@LaunchedEffect
    while (true) {
      val left = leftUntil(endsAtMs)
      remainingMs = left
      if (left <= 0L) break
      // Wake on the next whole second so the label flips exactly when the second does
      delay(1_000L - System.currentTimeMillis() % 1_000L)
    }
  }
  return remainingMs.takeIf { endsAtMs > 0L && it > 0L }
}

private fun leftUntil(endsAtMs: Long): Long =
  (endsAtMs - System.currentTimeMillis()).coerceAtLeast(0L)

/**
 * Draws the sleep-timer countdown as a ring hugging the bounds of this element.
 *
 * The remaining arc is anchored at 12 o'clock and sweeps clockwise, so its tail retreats
 * counter-clockwise as time is used up — the ring drains in the opposite direction to a clock.
 * Nothing is drawn when [remainingMs] is null, which keeps the play buttons untouched while the
 * timer is off.
 *
 * @param totalMinutes the duration that was picked; together with [remainingMs] it gives the
 *   fraction still to go. It is not the same as the remaining time after the user extends the
 *   timer, which is why the caller passes both.
 * @param inset gap between the ring and the element's edge
 * @param trackColor faint full circle behind the arc; pass transparent on elements that already
 *   have a circular fill, where it would just read as a second ring
 */
fun Modifier.sleepTimerRing(
  remainingMs: Long?,
  totalMinutes: Int,
  strokeWidth: Dp,
  inset: Dp = 1.dp,
  color: Color = Color.White,
  trackColor: Color = Color.Transparent
): Modifier {
  if (remainingMs == null || totalMinutes <= 0) return this
  val fraction = (remainingMs / (totalMinutes * 60_000f)).coerceIn(0f, 1f)
  return drawBehind {
    val stroke = strokeWidth.toPx()
    val gap = inset.toPx()
    val diameter = size.minDimension - stroke - gap * 2f
    if (diameter <= 0f) return@drawBehind
    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
    val arcSize = Size(diameter, diameter)

    if (trackColor.alpha > 0f) {
      // Butt caps: a closed circle drawn with round caps leaves a visible bump where they meet
      drawArc(
        color = trackColor,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Butt)
      )
    }
    drawArc(
      color = color,
      startAngle = -90f,
      sweepAngle = 360f * fraction,
      useCenter = false,
      topLeft = topLeft,
      size = arcSize,
      // A full ring has both ends meeting at 12 o'clock, where two round caps would stack into
      // a nub; below that the round ends are what make the shrinking arc readable.
      style = Stroke(
        width = stroke,
        cap = if (fraction > 0.995f) StrokeCap.Butt else StrokeCap.Round
      )
    )
  }
}

/** Announces the countdown on the play button, which is otherwise silent about the timer. */
fun Modifier.sleepTimerStateDescription(remainingMs: Long?): Modifier =
  if (remainingMs == null) this
  else semantics { stateDescription = "定时关闭还剩 ${sleepRemainingLabel(remainingMs)}" }

/** "42 分钟" / "1 小时 5 分" / "38 秒", rounded up so a running timer never reads zero. */
fun sleepRemainingLabel(remainingMs: Long): String {
  val seconds = (remainingMs + 999L) / 1_000L
  return when {
    seconds >= 3_600L -> {
      val hours = seconds / 3_600L
      val minutes = seconds % 3_600L / 60L
      if (minutes == 0L) "$hours 小时" else "$hours 小时 $minutes 分"
    }
    seconds >= 60L -> "${(seconds + 59L) / 60L} 分钟"
    else -> "$seconds 秒"
  }
}

/**
 * "24:31" — minutes and seconds left, the form the overflow menu shows.
 *
 * Minutes are not capped at two digits: the picker goes up to four hours, so a fresh timer reads
 * "240:00" rather than wrapping to a shorter unit that would hide how long is actually left.
 */
fun sleepRemainingClock(remainingMs: Long): String {
  val seconds = (remainingMs + 999L) / 1_000L
  return "%02d:%02d".format(Locale.US, seconds / 60L, seconds % 60L)
}
