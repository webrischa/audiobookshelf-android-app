package com.audiobookshelf.nativeapp.ui

import com.audiobookshelf.nativeapp.util.AppLog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Chrome bars are rounded only at the bottom, like a sheet hanging from the top edge. */
val GlassBarShape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)

/** Corner radius shared by every bottom sheet. */
internal val SheetShape = RoundedCornerShape(26.dp)

/**
 * Glass material for chrome that sits directly on the page background.
 *
 * The backdrop library is used for floating overlays (mini player, sheets, shelf cards) where the
 * surface really samples content behind it. Top bars sit on a flat page colour, so the same look is
 * produced with a translucent gradient plus a specular rim: identical pixels, no extra GPU layers.
 */
internal val GlassFill = Brush.verticalGradient(
  listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.05f))
)

internal val GlassRim = Brush.verticalGradient(
  listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.06f))
)

/** Floating glass top bar. The wrapped content keeps its own status bar padding. */
@Composable
fun GlassBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .background(GlassFill, GlassBarShape)
      .border(0.5.dp, GlassRim, GlassBarShape)
  ) {
    content()
  }
}

/** Rounded glass card for grouped content (forms, panels). */
@Composable
fun GlassCard(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(26.dp),
  content: @Composable ColumnScope.() -> Unit
) {
  Column(
    modifier = modifier
      .background(GlassFill, shape)
      .border(0.5.dp, GlassRim, shape)
  ) {
    content()
  }
}

private val ItemCardShape = RoundedCornerShape(22.dp)
private val ItemCoverShape = RoundedCornerShape(14.dp)

/**
 * Applies the static glass material to any existing container, so a screen can be glassed without
 * restructuring its layout.
 */
fun Modifier.glassSurface(shape: Shape = RoundedCornerShape(26.dp)): Modifier =
  this.background(GlassFill, shape).border(0.5.dp, GlassRim, shape)

/**
 * Liquid glass switch, ported from the catalog's `LiquidToggle`
 * (github.com/Kyant0/AndroidLiquidGlass, kmp branch).
 *
 * The track is a 64x28 capsule that lerps from neutral grey to the theme accent, and the thumb is
 * a 40x24 glass bead that samples that track through [drawBackdrop]. Pressing stretches the bead
 * (1.5x) and swaps the blur for a lens, so it reads as sinking into the surface instead of sitting
 * on top of it; releasing adds a velocity based squash along the travel direction.
 *
 * Only the track is sampled, never the page backdrop: the switch lives inside the content the
 * navigation backdrop records, so sampling that would make the layer reference itself.
 */
@Composable
fun GlassSwitch(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true
) {
  val accent = MaterialTheme.colorScheme.primary
  val density = LocalDensity.current
  val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

  val thumbWidth = 40.dp
  val thumbHeight = 24.dp
  val trackWidth = 64.dp
  val trackHeight = 28.dp
  val edge = 2.dp
  val travelPx = with(density) { (trackWidth - thumbWidth - edge * 2).toPx() }

  val scope = rememberCoroutineScope()
  val fraction = remember { Animatable(if (checked) 1f else 0f, 0.001f) }
  val pressProgress = remember { Animatable(0f, 0.001f) }
  val pressScaleX = remember { Animatable(1f, 0.001f) }
  val pressScaleY = remember { Animatable(1f, 0.001f) }
  val releaseVelocity = remember { Animatable(0f, 0.001f) }

  // Drag drives a plain state so the bead follows the finger without a coroutine per event
  var dragging by remember { mutableStateOf(false) }
  var dragFraction by remember { mutableFloatStateOf(fraction.value) }
  val tracker = remember { VelocityTracker() }

  // Press state is owned by the pointer itself, not by toggleable: once a drag consumes the
  // events, toggleable cancels its press interaction and the glass state would collapse mid-drag.
  var pressed by remember { mutableStateOf(false) }
  // The gesture block is keyed on the layout only, so it must read the latest state
  val currentChecked by rememberUpdatedState(checked)
  val currentOnChange by rememberUpdatedState(onCheckedChange)

  LaunchedEffect(pressed, enabled) {
    val down = pressed && enabled
    launch { pressProgress.animateTo(if (down) 1f else 0f, TogglePressSpec) }
    launch { pressScaleX.animateTo(if (down) TogglePressedScale else 1f, ToggleScaleXSpec) }
    launch { pressScaleY.animateTo(if (down) TogglePressedScale else 1f, ToggleScaleYSpec) }
  }

  // The single animation driver for taps and for programmatic changes
  LaunchedEffect(checked) {
    val target = if (checked) 1f else 0f
    if (!dragging && fraction.value != target) fraction.animateTo(target, ToggleValueSpec)
  }

  val shownFraction = if (dragging) dragFraction else fraction.value
  val trackBackdrop = rememberLayerBackdrop()
  // Pressing shrinks the sampled track so the enlarged bead still shows what is behind it
  val trackSampler = rememberBackdrop(trackBackdrop) { drawBackdrop ->
    val progress = pressProgress.value
    scale(lerpFloat(2f / 3f, 0.75f, progress), lerpFloat(0f, 0.75f, progress)) {
      drawBackdrop()
    }
  }

  Box(
    modifier = modifier
      .toggleable(
        value = checked,
        enabled = enabled,
        role = Role.Switch,
        onValueChange = onCheckedChange
      )
      // Holds the glass state for as long as the finger is down, drag or tap alike
      .pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          pressed = true
          try {
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break
            }
          } finally {
            pressed = false
          }
        }
      }
      .pointerInput(enabled, travelPx) {
        if (!enabled || travelPx <= 0f) return@pointerInput
        detectHorizontalDragGestures(
          onDragStart = {
            tracker.resetTracking()
            dragFraction = fraction.value
            dragging = true
          },
          onDragCancel = {
            dragging = false
            scope.launch { fraction.snapTo(dragFraction) }
          },
          onDragEnd = {
            val dropped = dragFraction
            val target = if (dropped >= 0.5f) 1f else 0f
            val velocity = tracker.calculateVelocity().x
            scope.launch {
              fraction.snapTo(dropped)
              dragging = false
              if ((target == 1f) != currentChecked) currentOnChange(target == 1f)
              launch { releaseVelocity.snapTo(velocity) }
              launch { releaseVelocity.animateTo(0f, ToggleVelocitySpec) }
              fraction.animateTo(target, ToggleValueSpec)
            }
          }
        ) { change, dragAmount ->
          change.consume()
          val delta = if (isLtr) dragAmount / travelPx else -dragAmount / travelPx
          dragFraction = (dragFraction + delta).coerceIn(0f, 1f)
          tracker.addPosition(change.uptimeMillis, Offset(dragFraction, 0f))
        }
      }
      .size(trackWidth, trackHeight)
      .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
    contentAlignment = Alignment.CenterStart
  ) {
    Box(
      Modifier
        .layerBackdrop(trackBackdrop)
        .clip(ToggleCapsule)
        .drawBehind { drawRect(lerp(ToggleTrackOff, accent, shownFraction)) }
        .fillMaxSize()
    )

    Box(
      Modifier
        .graphicsLayer {
          val inset = edge.toPx()
          translationX = if (isLtr) {
            lerpFloat(inset, inset + travelPx, shownFraction)
          } else {
            lerpFloat(-inset, -(inset + travelPx), shownFraction)
          }
        }
        .drawBackdrop(
          backdrop = trackSampler,
          shape = { ToggleCapsule },
          effects = {
            val progress = pressProgress.value
            blur(8.dp.toPx() * (1f - progress))
            lens(
              refractionHeight = 5.dp.toPx() * progress,
              refractionAmount = 10.dp.toPx() * progress,
              chromaticAberration = true
            )
          },
          highlight = {
            val progress = pressProgress.value
            Highlight.Ambient.copy(
              width = Highlight.Ambient.width / 1.5f,
              blurRadius = Highlight.Ambient.blurRadius / 1.5f,
              alpha = progress
            )
          },
          shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)) },
          innerShadow = {
            val progress = pressProgress.value
            InnerShadow(radius = 4.dp * progress, alpha = progress)
          },
          layerBlock = {
            val stretch = (releaseVelocity.value / 50f).coerceIn(-0.2f, 0.2f)
            scaleX = pressScaleX.value / (1f - stretch * 0.75f)
            scaleY = pressScaleY.value * (1f - stretch * 0.25f)
          },
          onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - pressProgress.value)) }
        )
        .size(thumbWidth, thumbHeight)
    )
  }
}

/** Stadium shape: a true capsule at any aspect ratio. */
private val ToggleCapsule = RoundedCornerShape(percent = 50)
private val ToggleTrackOff = Color(0xFF787880).copy(alpha = 0.36f)
private const val TogglePressedScale = 1.5f

private val ToggleValueSpec = spring<Float>(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.001f)
private val TogglePressSpec = spring<Float>(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.001f)
private val ToggleScaleXSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 250f, visibilityThreshold = 0.001f)
private val ToggleScaleYSpec = spring<Float>(dampingRatio = 0.7f, stiffness = 250f, visibilityThreshold = 0.001f)
private val ToggleVelocitySpec = spring<Float>(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.01f)

/**
 * Glass card holding a cover, title and optional progress. Used by the home shelves and the
 * bookshelf grid so both read as the same material.
 *
 * The surface is the static glass material rather than a sampled backdrop: a per-card backdrop
 * draw is wasteful inside long lazy rows, and over a flat page colour the two are pixel-identical.
 */
@Composable
fun GlassItemCard(
  coverUrl: String,
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  progress: Double = 0.0,
  hasCover: Boolean? = null
) {
  // Static glass: a per-card backdrop draw is wasteful inside long lazy rows and can
  // fail to render when many cards share one full-screen layer.
  val surface = modifier
    .background(GlassFill, ItemCardShape)
    .border(0.5.dp, GlassRim, ItemCardShape)

  Column(
    modifier = surface
      .clip(ItemCardShape)
      .clickable(onClick = {
        AppLog.tapTrace("CARD_CLICK", title)
        onClick()
      })
      .padding(8.dp)
  ) {
    // Progress is drawn on the bottom edge of the cover, like the original app
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .clip(ItemCoverShape)
    ) {
      BookCover(url = coverUrl, hasCover = hasCover, modifier = Modifier.fillMaxSize())
      if (progress > 0) {
        Box(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth(progress.toFloat().coerceIn(0f, 1f))
            .height(4.dp)
            .background(MaterialTheme.colorScheme.primary)
        )
      }
    }
    Spacer(Modifier.height(8.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.bodySmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    if (subtitle != null) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

private val SliderTrackColor = Color(0xFF787880).copy(alpha = 0.36f)

private val SliderPressSpec =
  spring<Float>(dampingRatio = 0.7f, stiffness = 400f, visibilityThreshold = 0.001f)

/**
 * Liquid glass slider, ported from the catalog's `LiquidSlider`
 * (github.com/Kyant0/AndroidLiquidGlass, kmp branch).
 *
 * The track is a 6dp capsule that fills with [accent]; the thumb is a 40x24 glass bead that samples
 * the track through [drawBackdrop]. At rest the bead is an opaque white pill, so the only thing that
 * moves is the fill; pressing fades the surface out and swaps the blur for a lens, which makes the
 * track colour read through the bead.
 *
 * Only the track is sampled, never the page backdrop: the slider lives inside content the navigation
 * backdrop records, so sampling that would make the layer reference itself.
 *
 * Dragging is deliberately not snapped here — callers round the value themselves, which keeps a
 * 1 second step from fighting the finger while it is still moving.
 */
@Composable
fun GlassSlider(
  value: Float,
  onValueChange: (Float) -> Unit,
  valueRange: ClosedFloatingPointRange<Float>,
  modifier: Modifier = Modifier,
  accent: Color = MaterialTheme.colorScheme.primary,
  enabled: Boolean = true,
  label: String? = null
) {
  val density = LocalDensity.current
  val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
  val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
  val progressOf: (Float) -> Float = { v -> ((v - valueRange.start) / span).coerceIn(0f, 1f) }

  val trackBackdrop = rememberLayerBackdrop()
  val pressProgress = remember { Animatable(0f, 0.001f) }

  var trackWidthPx by remember { mutableFloatStateOf(0f) }
  var dragging by remember { mutableStateOf(false) }
  var dragProgress by remember { mutableFloatStateOf(progressOf(value)) }
  var pressed by remember { mutableStateOf(false) }

  val currentOnChange by rememberUpdatedState(onValueChange)
  val targetProgress = progressOf(value)
  // A drag must start from the committed value. An earlier version eased the bead to the new value
  // after release and started the next drag from that animation's current frame, so a quick second
  // drag resumed from near the old position and read as "it jumped back to zero".
  val latestTarget = rememberUpdatedState(targetProgress)

  LaunchedEffect(pressed, enabled) {
    pressProgress.animateTo(if (pressed && enabled) 1f else 0f, SliderPressSpec)
  }

  // The bead follows the finger while dragging and sits exactly on the committed value otherwise.
  // No easing on release: the drag has already placed it, and any animation here would only make
  // the next touch start from a position the user never chose.
  val shownProgress = if (dragging) dragProgress else targetProgress
  // Pressing shrinks the sampled track so the enlarged bead still shows what is behind it
  val trackSampler = rememberBackdrop(trackBackdrop) { drawBackdrop ->
    val progress = pressProgress.value
    scale(lerpFloat(2f / 3f, 1f, progress), lerpFloat(0f, 1f, progress)) { drawBackdrop() }
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .onSizeChanged { trackWidthPx = it.width.toFloat() }
      // A drag surface built from raw pointer events carries no semantics at all: TalkBack read it
      // as an unlabelled blank, and there was no way to change the value without dragging.
      .semantics {
        label?.let { contentDescription = it }
        progressBarRangeInfo = ProgressBarRangeInfo(current = progressOf(value), range = 0f..1f)
        setProgress { target ->
          val fraction = target.coerceIn(0f, 1f)
          currentOnChange(valueRange.start + fraction * span)
          true
        }
      }
      // Holds the glass state for as long as the finger is down, drag or tap alike
      .pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          pressed = true
          try {
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break
            }
          } finally {
            pressed = false
          }
        }
      }
      .pointerInput(enabled, trackWidthPx, isLtr, valueRange) {
        if (!enabled || trackWidthPx <= 0f) return@pointerInput
        detectHorizontalDragGestures(
          onDragStart = {
            dragProgress = latestTarget.value
            dragging = true
          },
          onDragEnd = { dragging = false },
          onDragCancel = { dragging = false }
        ) { change, dragAmount ->
          change.consume()
          val delta = if (isLtr) dragAmount / trackWidthPx else -dragAmount / trackWidthPx
          dragProgress = (dragProgress + delta).coerceIn(0f, 1f)
          currentOnChange(valueRange.start + dragProgress * span)
        }
      }
      .pointerInput(enabled, trackWidthPx, isLtr, valueRange) {
        if (!enabled || trackWidthPx <= 0f) return@pointerInput
        detectTapGestures { position ->
          val fraction = (position.x / trackWidthPx).coerceIn(0f, 1f)
          val target = if (isLtr) fraction else 1f - fraction
          currentOnChange(valueRange.start + target * span)
        }
      }
      .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
    contentAlignment = Alignment.CenterStart
  ) {
    Box(Modifier.layerBackdrop(trackBackdrop)) {
      Box(
        Modifier
          .fillMaxWidth()
          .height(6.dp)
          .clip(ToggleCapsule)
          .background(SliderTrackColor)
      )
      Box(
        Modifier
          .fillMaxWidth(shownProgress)
          .height(6.dp)
          .clip(ToggleCapsule)
          .background(accent)
      )
    }

    Box(
      Modifier
        .graphicsLayer {
          // The bead overhangs the track by a quarter of its width at either end, like the catalog
          translationX = if (trackWidthPx > 0f) {
            (-size.width / 2f + trackWidthPx * shownProgress).coerceIn(
              -size.width / 4f,
              trackWidthPx - size.width * 3f / 4f
            )
          } else {
            0f
          }
        }
        .drawBackdrop(
          backdrop = trackSampler,
          shape = { ToggleCapsule },
          effects = {
            val progress = pressProgress.value
            blur(8.dp.toPx() * (1f - progress))
            lens(
              refractionHeight = 10.dp.toPx() * progress,
              refractionAmount = 14.dp.toPx() * progress,
              chromaticAberration = true
            )
          },
          highlight = {
            val progress = pressProgress.value
            Highlight.Ambient.copy(
              width = Highlight.Ambient.width / 1.5f,
              blurRadius = Highlight.Ambient.blurRadius / 1.5f,
              alpha = progress
            )
          },
          shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)) },
          innerShadow = {
            val progress = pressProgress.value
            InnerShadow(radius = 4.dp * progress, alpha = progress)
          },
          layerBlock = {
            val progress = pressProgress.value
            scaleX = lerpFloat(1f, TogglePressedScale, progress)
            scaleY = lerpFloat(1f, TogglePressedScale, progress)
          },
          onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - pressProgress.value)) }
        )
        .size(40.dp, 24.dp)
    )
  }
}

private val LiquidPressSpec =
  spring<Float>(dampingRatio = 1f, stiffness = 900f, visibilityThreshold = 0.001f)
private val LiquidReleaseSpec =
  spring<Offset>(dampingRatio = 0.55f, stiffness = 320f, visibilityThreshold = Offset(0.5f, 0.5f))

/**
 * Liquid glass button, following the catalog's `LiquidButton`
 * (github.com/Kyant0/AndroidLiquidGlass, kmp branch).
 *
 * The glass samples a canvas plate that the button draws itself: a clear middle with a tinted ring
 * near the edge plus a specular blob. The page level backdrop cannot be sampled from here — the
 * whole screen is already recorded into the navigation backdrop, and a descendant that samples its
 * own ancestor layer makes the render node reference itself. The ring matters because the lens only
 * bends what has an edge: a flat fill would be refracted as a flat fill and read as a plain circle.
 *
 * Pressing grows the surface; while the finger is down the surface is pulled towards it with a tanh
 * falloff and squashed along the drag axis, which is what makes the material read as liquid rather
 * than as a scaling image.
 */
@Composable
fun LiquidGlassButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  tint: Color = MaterialTheme.colorScheme.primary,
  content: @Composable BoxScope.() -> Unit
) {
  val scope = rememberCoroutineScope()
  var pressed by remember { mutableStateOf(false) }
  // Plain state, not an Animatable: the gesture loop runs in a restricted suspend scope and cannot
  // call Animatable.snapTo. The release animation below drives this state instead.
  var dragOffset by remember { mutableStateOf(Offset.Zero) }
  var releaseJob by remember { mutableStateOf<Job?>(null) }
  val release = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
  val press = remember { Animatable(0f, 0.001f) }

  LaunchedEffect(pressed) {
    press.animateTo(if (pressed) 1f else 0f, LiquidPressSpec)
  }

  // Clear centre, colour only in a ring near the edge. A flat filled disc would be refracted as a
  // flat disc — the lens only bends what has an edge — so the ring is what makes the rim read as
  // glass while the middle stays see-through. The white blob is the specular highlight.
  val plate = rememberCanvasBackdrop {
    val radius = size.minDimension / 2f
    drawCircle(
      brush = Brush.radialGradient(
        colorStops = arrayOf(
          0f to Color.Transparent,
          0.58f to tint.copy(alpha = 0.04f),
          0.86f to tint.copy(alpha = 0.20f),
          1f to tint.copy(alpha = 0.12f)
        ),
        center = Offset(size.width / 2f, size.height / 2f),
        radius = radius
      )
    )
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.34f), Color.Transparent),
        center = Offset(size.width * 0.34f, size.height * 0.24f),
        radius = size.minDimension * 0.55f
      )
    )
  }

  Box(
    modifier = modifier
      .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
      .drawBackdrop(
        backdrop = plate,
        shape = { CircleShape },
        effects = {
          vibrancy()
          blur(2.dp.toPx())
          lens(12.dp.toPx(), 24.dp.toPx())
        },
        layerBlock = if (enabled) {
          {
            val width = size.width
            val height = size.height
            val grow = lerpFloat(1f, 1f + 4.dp.toPx() / height, press.value)
            val reach = size.minDimension
            val offset = dragOffset
            translationX = reach * tanh(0.05f * offset.x / reach)
            translationY = reach * tanh(0.05f * offset.y / reach)
            val stretch = 4.dp.toPx() / height
            val angle = atan2(offset.y, offset.x)
            scaleX = grow +
              stretch * abs(cos(angle) * offset.x / size.maxDimension) * (width / height).coerceAtMost(1f)
            scaleY = grow +
              stretch * abs(sin(angle) * offset.y / size.maxDimension) * (height / width).coerceAtMost(1f)
          }
        } else {
          null
        },
        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.05f)) }
      )
      .clickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick
      )
      .pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          releaseJob?.cancel()
          val start = down.position
          pressed = true
          try {
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break
              dragOffset = change.position - start
            }
          } finally {
            pressed = false
            releaseJob = scope.launch {
              release.snapTo(dragOffset)
              release.animateTo(Offset.Zero, LiquidReleaseSpec) { dragOffset = value }
            }
          }
        }
      },
    contentAlignment = Alignment.Center,
    content = content
  )
}

/** Round quick pick, shared by the speed, sleep timer and skip dialogs. */
@Composable
fun GlassPresetCircle(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  accent: Color = MaterialTheme.colorScheme.primary,
  modifier: Modifier = Modifier,
  size: Dp = 52.dp
) {
  Box(
    modifier = modifier
      .size(size)
      .clip(CircleShape)
      .border(
        width = 1.dp,
        color = if (selected) accent else Color.White.copy(alpha = 0.18f),
        shape = CircleShape
      )
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = label,
      color = if (selected) accent else Color.White,
      style = MaterialTheme.typography.bodyMedium
    )
  }
}
