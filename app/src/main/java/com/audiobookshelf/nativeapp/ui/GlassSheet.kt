package com.audiobookshelf.nativeapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Bottom sheet material and its content helpers. Split out of Glass.kt: these are self-contained
 * composables, while the shared SheetShape / GlassFill / GlassRim brushes stay there as internal
 * values so both files draw the same surface.
 */
/**
 * Keeps taps inside an overlay panel from falling through to the scrim behind it.
 *
 * Compose hit-tests siblings independently: a panel without any pointer-input node is transparent
 * to hit testing, so a tap on its empty space (title, hint text, card padding) is delivered to the
 * full-screen clickable scrim underneath and closes the sheet. Children are hit-tested before this
 * node, so buttons, rulers and drags inside the panel keep working unchanged.
 *
 * Consuming DOWN is the part that matters — the scrim's [dismissOnScrimTap] waits for an
 * unconsumed down. Only DOWN and UP are consumed; MOVE is left alone so nested drag gestures
 * still see it.
 */
internal fun Modifier.consumeOverlayTaps(): Modifier = pointerInput(Unit) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    down.consume()
    while (true) {
      val event = awaitPointerEvent()
      val change = event.changes.firstOrNull { it.id == down.id } ?: break
      if (!change.pressed) {
        change.consume()
        break
      }
    }
  }
}

/**
 * Dismisses a sheet only on a genuine tap that starts on the scrim.
 *
 * Compose hit-tests pointer input once, on DOWN, and then keeps dispatching that pointer's MOVE/UP
 * to the same nodes for the rest of the gesture — it never re-tests when the finger travels over
 * the panel. [androidx.compose.foundation.clickable] gives up only when the pointer leaves its own
 * bounds, and a fillMaxSize() scrim has no "outside": pressing it and dragging into the sheet still
 * counted as a tap and closed the panel. Tracking the travel distance restores the platform
 * convention that press-drag-release is a drag, not a click.
 *
 * `awaitFirstDown` still requires an unconsumed down, so taps that start on the panel (consumed by
 * [consumeOverlayTaps] or by one of its buttons) never reach the scrim.
 */
internal fun Modifier.dismissOnScrimTap(onDismiss: () -> Unit): Modifier = pointerInput(Unit) {
  val slop = viewConfiguration.touchSlop
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = true)
    down.consume()
    val origin = down.position
    var dragged = false
    while (true) {
      val event = awaitPointerEvent()
      val change = event.changes.firstOrNull { it.id == down.id } ?: break
      if ((change.position - origin).getDistance() > slop) dragged = true
      if (!change.pressed) {
        if (!dragged && !change.isConsumed) {
          change.consume()
          onDismiss()
        }
        break
      }
    }
  }
}

/**
 * Liquid glass bottom sheet — the panel that slides up from the bottom edge.
 *
 * [backdrop] is the layer the page was recorded into, so the panel can sample it for the blur and
 * lens. Pass null when the sheet cannot reach that layer (e.g. a screen that itself lives inside the
 * recorded subtree): the same material is then built from a near-opaque page-coloured base plus the
 * shared highlight gradient and rim.
 *
 * When the backdrop is supplied the sheet must stay a sibling of the recorded content, never inside
 * it, or the layer ends up referencing the panel that draws it.
 *
 * [bottomInset] is the space taken by floating chrome at the bottom edge (the mini player). When it
 * is zero the sheet sits directly on the bottom edge; [bottomGap] is the breathing room kept above
 * that edge or above the bar, so a screen can tighten it when nothing floats underneath.
 */
@Composable
fun GlassSheet(
  backdrop: Backdrop?,
  visible: Boolean,
  onDismiss: () -> Unit,
  bottomInset: Dp = 0.dp,
  bottomGap: Dp = 10.dp,
  content: @Composable ColumnScope.() -> Unit
) {
  val sheetBackdrop = backdrop
  Box(Modifier.fillMaxSize()) {
    // Scrim fades, the panel itself slides up from the bottom edge and back down
    AnimatedVisibility(
      visible = visible,
      enter = fadeIn(tween(200)),
      exit = fadeOut(tween(200))
    ) {
      Box(
        Modifier
          .fillMaxSize()
          // The scrim stops above whatever floats at the bottom of the page (the mini player), so
          // that strip keeps its normal brightness and stays tappable instead of looking dimmed
          .padding(bottom = bottomInset)
          .background(Color.Black.copy(alpha = 0.42f))
          .dismissOnScrimTap(onDismiss)
          // clickable used to expose an onClick action to accessibility services; keep it now that
          // the scrim uses a raw pointerInput node
          .semantics { onClick { onDismiss(); true } }
      )
    }
    AnimatedVisibility(
      visible = visible,
      enter = slideInVertically(tween(320)) { it } + fadeIn(tween(220)),
      exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(180)),
      modifier = Modifier.align(Alignment.BottomCenter)
    ) {
      Box(
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 10.dp)
          // Lifts the panel clear of the floating playback bar instead of sliding underneath it;
          // with no bar the panel drops to the bottom edge instead of hanging in mid air
          .padding(bottom = bottomInset + bottomGap)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .then(
              if (sheetBackdrop != null) {
                Modifier.drawBackdrop(
                  backdrop = sheetBackdrop,
                  shape = { SheetShape },
                  effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                    lens(18.dp.toPx(), 30.dp.toPx())
                  },
                  onDrawSurface = {
                    drawRect(Color.Black.copy(alpha = 0.32f))
                    drawRect(Color.White.copy(alpha = 0.06f))
                  }
                )
              } else {
                Modifier
                  .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f), SheetShape)
                  .background(GlassFill, SheetShape)
                  .border(0.5.dp, GlassRim, SheetShape)
              }
            )
            .clip(SheetShape)
            .padding(top = 12.dp, bottom = 16.dp)
            // Taps anywhere in the panel belong to the panel, never to the dismiss scrim
            .consumeOverlayTaps()
        ) {
          content()
        }
      }
    }
  }
}

@Composable
fun SheetHeader(
  title: String,
  subtitle: (@Composable () -> Unit)? = null,
  onClose: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White
      )
      // The close button is 48dp tall, so a second line still fits inside the same header height
      subtitle?.invoke()
    }
    IconButton(onClick = onClose) {
      Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White.copy(alpha = 0.7f))
    }
  }
}

/** Card that holds the big value, the wheel and the quick picks. */
@Composable
fun SheetCard(
  verticalPadding: Dp = 22.dp,
  content: @Composable ColumnScope.() -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp)
      .clip(RoundedCornerShape(18.dp))
      .background(Color.White.copy(alpha = 0.05f))
      .padding(vertical = verticalPadding),
    content = content
  )
}

@Composable
fun SheetHint(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = Color.White.copy(alpha = 0.5f),
    modifier = Modifier.padding(horizontal = 20.dp)
  )
}
