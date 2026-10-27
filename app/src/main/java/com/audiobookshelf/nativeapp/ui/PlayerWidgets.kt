package com.audiobookshelf.nativeapp.ui
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay


/**
 * Stateless widgets for the player page: value rulers, speed/sleep wheels and the hold-to-seek
 * control. Split out of PlayerScreen.kt; the page keeps the state and passes simple callbacks.
 */
/**
 * Wheel style ruler: the red marker stays in the middle and the ticks scroll underneath it.
 * Dragging snaps to the nearest tick, one haptic beat per tick.
 */
@Composable
internal fun ValueRuler(
  currentIndex: Float,
  tickCount: Int,
  stepPx: Float,
  majorEvery: Int,
  labelEvery: Int,
  labelOf: (Int) -> String,
  onChange: (Int) -> Unit
) {
  val latestIndex by rememberUpdatedState(currentIndex)
  val latestChange by rememberUpdatedState(onChange)
  val textMeasurer = rememberTextMeasurer()
  val haptics = LocalHapticFeedback.current

  var dragging by remember { mutableStateOf(false) }
  var dragStartIndex by remember { mutableFloatStateOf(0f) }
  var dragPx by remember { mutableFloatStateOf(0f) }
  var lastTickIndex by remember { mutableIntStateOf(-1) }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(56.dp)
      .pointerInput(tickCount, stepPx) {
        detectDragGestures(
          onDragStart = {
            dragStartIndex = latestIndex
            lastTickIndex = latestIndex.roundToInt()
            dragPx = 0f
            dragging = true
          },
          onDragCancel = {
            dragging = false
            dragPx = 0f
          },
          onDragEnd = {
            dragging = false
            dragPx = 0f
          },
          onDrag = { change, amount ->
            change.consume()
            dragPx += amount.x
            // Dragging the ruler to the right brings earlier ticks under the marker
            val preview = (dragStartIndex - dragPx / stepPx).roundToInt().coerceIn(0, tickCount - 1)
            if (preview != lastTickIndex) {
              lastTickIndex = preview
              haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              latestChange(preview)
            }
          }
        )
      }
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val centerX = size.width / 2f
      val visualIndex = if (dragging) dragStartIndex - dragPx / stepPx else currentIndex

      for (i in 0 until tickCount) {
        val x = centerX + (i - visualIndex) * stepPx
        if (x < -stepPx || x > size.width + stepPx) continue
        val major = i % majorEvery == 0
        drawLine(
          color = Color.White.copy(alpha = if (major) 0.45f else 0.18f),
          start = Offset(x, size.height * if (major) 0.16f else 0.28f),
          end = Offset(x, size.height * 0.66f),
          strokeWidth = if (major) 2f else 1.2f
        )
      }

      // Value labels ride along with their tick
      for (i in 0 until tickCount step labelEvery) {
        val x = centerX + (i - visualIndex) * stepPx
        if (x < 0f || x > size.width) continue
        val layout = textMeasurer.measure(
          text = labelOf(i),
          style = TextStyle(color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
        )
        drawText(layout, topLeft = Offset(x - layout.size.width / 2f, size.height * 0.68f))
      }

      // Marker is pinned to the centre
      drawLine(
        color = SpeedAccent,
        start = Offset(centerX, 4.dp.toPx()),
        end = Offset(centerX, size.height * 0.7f),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round
      )
      drawCircle(color = SpeedAccent, radius = 4.dp.toPx(), center = Offset(centerX, 4.dp.toPx()))
    }
  }
}

/** Speed wheel: one tick per 0.1x. */
@Composable
internal fun SpeedRuler(current: Float, onChange: (Float) -> Unit) {
  val stepPx = with(LocalDensity.current) { 30.dp.toPx() }
  ValueRuler(
    currentIndex = speedIndexOf(current).toFloat(),
    tickCount = SpeedTickCount,
    stepPx = stepPx,
    majorEvery = 5,
    labelEvery = 5,
    labelOf = { speedLabel(speedOf(it)) }
  ) { index -> onChange(speedOf(index)) }
}

/** Sleep wheel: one tick per 15 minutes, the top of the range is 4 hours. */
@Composable
internal fun SleepRuler(current: Int, onChange: (Int) -> Unit) {
  val stepPx = with(LocalDensity.current) { 22.dp.toPx() }
  ValueRuler(
    currentIndex = (current / SleepStep).coerceIn(0, SleepTickCount - 1).toFloat(),
    tickCount = SleepTickCount,
    stepPx = stepPx,
    majorEvery = 4,
    labelEvery = 4,
    labelOf = { "${it * SleepStep}" }
  ) { index -> onChange(index * SleepStep) }
}

internal fun speedIndexOf(speed: Float): Int =
  ((speed - MinSpeed) / 0.1f).roundToInt().coerceIn(0, SpeedTickCount - 1)

internal fun speedOf(index: Int): Float =
  (MinSpeed + index * 0.1f).coerceIn(MinSpeed, MaxSpeed)

internal fun sleepLabel(minutes: Int): String = when {
  minutes <= 0 -> "关闭"
  minutes % 60 == 0 -> "${minutes / 60} 小时"
  else -> "$minutes 分钟"
}

/**
 * Circular arrow with the seconds count in the middle. Drawn by hand because the material icon
 * set only ships 5/10/30 second variants, and the plain Forward icon is a straight arrow.
 */
@Composable
internal fun SeekControl(forward: Boolean, seconds: Int, onSeek: () -> Unit) {
  // Holding the button keeps jumping; a short tap jumps exactly once. The repeat loop has to live
  // in a LaunchedEffect because pointerInput's gesture scope is a restricted coroutine scope.
  var holding by remember { mutableStateOf(false) }
  var longPressFired by remember { mutableStateOf(false) }
  // The tap is handled by pointerInput so that holding can repeat the seek, which also means
  // there is no clickable in the chain to emit press interactions or draw an indication. Feed
  // the system ripple by hand, otherwise these two controls are the only buttons on the player
  // screen with no pressed feedback at all.
  val interactionSource = remember { MutableInteractionSource() }
  LaunchedEffect(holding) {
    if (!holding) return@LaunchedEffect
    delay(400)
    longPressFired = true
    while (true) {
      onSeek()
      delay(160)
    }
  }
  // The gesture handling below never produces a click event, so without this the button is
  // completely silent to TalkBack and to switch access: it has no role, no label and no action.
  val label = if (forward) "快进 $seconds 秒" else "快退 $seconds 秒"
  val latestSeek by rememberUpdatedState(onSeek)
  Box(
    modifier = Modifier
      .size(46.dp)
      .clip(CircleShape)
      // 40dp round pressed layer: the same size as the surrounding IconButtons' state layer and
      // clearly larger than the 22dp glyph. The default indication would spread over the whole
      // 46dp node (too big); a 30dp layer reads too tight around the arrow.
      .indication(interactionSource, ripple(bounded = true, radius = 20.dp))
      .pointerInput(seconds) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          longPressFired = false
          holding = true
          val press = PressInteraction.Press(down.position)
          interactionSource.tryEmit(press)
          val up = waitForUpOrCancellation()
          interactionSource.tryEmit(
            if (up != null) PressInteraction.Release(press) else PressInteraction.Cancel(press)
          )
          holding = false
          if (!longPressFired) onSeek()
        }
      }
      .semantics {
        role = Role.Button
        contentDescription = label
        onClick(label = label) {
          latestSeek()
          true
        }
      },
    contentAlignment = Alignment.Center
  ) {
    Canvas(Modifier.size(22.dp)) {
      // Stroke and arrow head scale with the ring: at 22dp the previous absolute values made the
      // head look oversized against a thinner arc.
      val stroke = 1.3.dp.toPx()
      val radius = (size.minDimension - stroke) / 2f
      val endAngle = if (forward) 290f else 250f
      val sweep = if (forward) 290f else -290f
      drawArc(
        color = Color.White,
        startAngle = endAngle - sweep,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = stroke, cap = StrokeCap.Round)
      )
      // Arrow head at the open end of the arc
      val rad = Math.toRadians(endAngle.toDouble())
      val tipX = center.x + radius * cos(rad).toFloat()
      val tipY = center.y + radius * sin(rad).toFloat()
      val dirSign = if (forward) 1f else -1f
      val tangentX = -sin(rad).toFloat() * dirSign
      val tangentY = cos(rad).toFloat() * dirSign
      val headLength = 3.9.dp.toPx()
      val headHalfWidth = 2.3.dp.toPx()
      val baseX = tipX - tangentX * headLength
      val baseY = tipY - tangentY * headLength
      val normalX = -tangentY
      val normalY = tangentX
      val head = Path().apply {
        moveTo(tipX, tipY)
        lineTo(baseX + normalX * headHalfWidth, baseY + normalY * headHalfWidth)
        lineTo(baseX - normalX * headHalfWidth, baseY - normalY * headHalfWidth)
        close()
      }
      drawPath(head, Color.White)
    }
    Text(
      text = "$seconds",
      color = Color.White,
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold
    )
  }
}

internal fun speedLabel(speed: Float): String = "%.1fx".format(Locale.US, speed)

/**
 * One row of the overflow menu.
 *
 * The label deliberately avoids the word "跳过": automatic ad-skipping accessibility services
 * (e.g. 李跳跳) watch TYPE_WINDOWS_CHANGED and fire ACTION_CLICK on clickable nodes whose text
 * contains that keyword. Returning to the foreground used to open this row's sheet without a user
 * tap. With a neutral label the row can stay a plain [clickable] — no gesture filters, timing
 * cooldowns or custom semantics are needed.
 */
@Composable
internal fun GlassMenuRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  tint: Color = Color.White,
  trailing: (@Composable () -> Unit)? = null,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(12.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = tint,
      maxLines = 1,
      modifier = Modifier.weight(1f)
    )
    if (trailing != null) {
      Spacer(Modifier.width(8.dp))
      trailing()
    }
  }
}

/**
 * Time actually left on the sleep timer, ticking with the same clock as the ring. The row used to
 * echo the picked preset, which said nothing about how much of it had already run down.
 */
@Composable
internal fun SleepMenuTrailing(endsAtMs: Long, color: Color) {
  val remaining = rememberSleepTimerRemaining(endsAtMs)
  Text(
    text = remaining?.let(::sleepRemainingClock).orEmpty(),
    style = MaterialTheme.typography.labelSmall,
    color = color,
    maxLines = 1
  )
}

/**
 * "24:31 后停止播放" under the sleep panel's title.
 *
 * Rendered even without a timer — as an empty line of the same style — so that setting one from
 * inside the panel does not push the card below it down by a line.
 */
@Composable
internal fun SleepSheetCountdown(endsAtMs: Long) {
  val remaining = rememberSleepTimerRemaining(endsAtMs)
  Text(
    text = if (remaining != null) "${sleepRemainingClock(remaining)} 后停止播放" else "",
    style = MaterialTheme.typography.bodySmall,
    color = Color.White.copy(alpha = 0.7f),
    maxLines = 1
  )
}

/**
 * Per-book intro/outro skip editor. Both values are seconds inside a chapter: playback starts
 * [intro] seconds after a chapter begins and jumps to the next chapter [outro] seconds before it
 * ends.
 */
@Composable
internal fun SkipIntroOutroSheet(
  backdrop: Backdrop,
  visible: Boolean,
  intro: Int,
  outro: Int,
  onDismiss: () -> Unit,
  onSave: (Int, Int) -> Unit
) {
  GlassSheet(backdrop = backdrop, visible = visible, onDismiss = onDismiss) {
    // Drafts live inside the sheet content, so the panel's animated subtree takes them with it when
    // it slides out. Closing without saving therefore discards the edit; the next open always
    // starts from the stored values.
    var introValue by remember(intro) { mutableIntStateOf(intro) }
    var outroValue by remember(outro) { mutableIntStateOf(outro) }

    SheetHeader("片头片尾设置") { onDismiss() }
    SheetCard {
      SkipSlider("片头", introValue) { introValue = it }
      Spacer(Modifier.height(20.dp))
      SkipSlider("片尾", outroValue) { outroValue = it }
    }
    Spacer(Modifier.height(16.dp))
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Box(
        modifier = Modifier
          .weight(1f)
          .height(50.dp)
          .clip(RoundedCornerShape(50))
          .background(Color.White.copy(alpha = 0.10f))
          .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
      ) {
        Text("取消", color = Color.White, fontSize = 16.sp)
      }
      Box(
        modifier = Modifier
          .weight(1f)
          .height(50.dp)
          .clip(RoundedCornerShape(50))
          .background(MaterialTheme.colorScheme.primary)
          .clickable { onSave(introValue, outroValue) },
        contentAlignment = Alignment.Center
      ) {
        Text("保存", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }
    }
    Spacer(Modifier.height(10.dp))
    SheetHint("仅对当前书籍生效，每本书单独记忆")
  }
}

/** One labelled slider with the 0s / 120s scale underneath, 1 second steps. */
@Composable
internal fun SkipSlider(label: String, value: Int, onChange: (Int) -> Unit) {
  // The whole block is inset from the card edge; the track inherits that inset instead of adding
  // its own, so the label, the bead and the scale all line up on the same left edge.
  Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.85f),
        modifier = Modifier.weight(1f)
      )
      Text(
        text = "${value}s",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary
      )
    }
    Spacer(Modifier.height(4.dp))
    // The bead overhangs the track by a quarter of its width, so the track is inset to keep it
    // inside the card
    GlassSlider(
      value = value.toFloat(),
      onValueChange = { onChange(it.roundToInt()) },
      valueRange = 0f..120f,
      label = label
    )
    Row(Modifier.fillMaxWidth()) {
      Text("0s", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.45f))
      Spacer(Modifier.weight(1f))
      Text("120s", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.45f))
    }
  }
}
