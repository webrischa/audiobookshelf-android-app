package com.audiobookshelf.nativeapp.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiobookshelf.nativeapp.playback.PlaybackUiState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/** Extra space scrollable screens must reserve at the bottom so content is not hidden by the bar. */
val LocalMiniPlayerBottomInset = compositionLocalOf { 0.dp }

private val BarShape = CircleShape

/**
 * Floating liquid glass playback bar.
 *
 * The glass is drawn by sampling the [backdrop] that the screen content is recorded into, so the
 * bar must stay a sibling of that content (never inside the recorded subtree).
 *
 * @param preparing a playback session is being created for a book that is not in the player yet
 *   (the cold-start case: the book is staged, the first tap has to ask the server for a session).
 *   The button turns into a spinner for the duration — without it the tap looked like it had been
 *   ignored for the second or two the round trip takes.
 */
@Composable
fun MiniPlayerBar(
  backdrop: Backdrop,
  state: PlaybackUiState,
  title: String,
  subtitle: String,
  coverUrl: String,
  progress: Float,
  sleepTimerEndsAt: Long,
  sleepTimerMinutes: Int,
  onOpen: () -> Unit,
  onTogglePlayPause: () -> Unit,
  modifier: Modifier = Modifier,
  preparing: Boolean = false,
  hasCover: Boolean? = null
) {
  // Same countdown the player page draws, so the timer stays visible from any screen
  val sleepRemaining = rememberSleepTimerRemaining(sleepTimerEndsAt)
  Box(
    modifier = modifier
      .padding(horizontal = 12.dp)
      .padding(bottom = 10.dp)
      .fillMaxWidth()
      .height(68.dp)
      .drawBackdrop(
        backdrop = backdrop,
        shape = { BarShape },
        effects = {
          vibrancy()
          blur(6.dp.toPx())
          lens(14.dp.toPx(), 26.dp.toPx(), depthEffect = true)
        },
        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.08f)) }
      )
      .clip(BarShape)
      .clickable(onClick = onOpen)
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        // Symmetric inset: the button's right gap matches the cover's left gap
        .padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      BookCover(
        url = coverUrl,
        hasCover = hasCover,
        modifier = Modifier.size(48.dp),
        shape = CircleShape
      )
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      // Same height as the cover, with the glyph centred inside: the two ends of the bar then
      // share one optical height instead of the button standing taller than the artwork.
      Box(
        modifier = Modifier
          .size(48.dp)
          // The button carries no fill of its own, so the ring gets a faint track to sit on;
          // without it a nearly finished countdown would look like a stray arc.
          .sleepTimerRing(
            remainingMs = sleepRemaining,
            totalMinutes = sleepTimerMinutes,
            strokeWidth = 2.dp,
            inset = 1.dp,
            trackColor = Color.White.copy(alpha = 0.16f)
          )
          .sleepTimerStateDescription(sleepRemaining)
          .clip(CircleShape)
          // Not tappable while a session is being created: a second tap would cancel the request
          // that is already in flight and start over, making the wait longer.
          .clickable(enabled = !preparing, onClick = onTogglePlayPause),
        contentAlignment = Alignment.Center
      ) {
        if (preparing) {
          CircularProgressIndicator(
            modifier = Modifier.size(22.dp).semantics { contentDescription = "正在准备播放" },
            strokeWidth = 2.dp,
            color = Color.White
          )
        } else {
          Icon(
            imageVector = if (state.isPlayingOrStarting) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (state.isPlayingOrStarting) "暂停" else "播放",
            tint = Color.White,
            modifier = Modifier.size(32.dp)
          )
        }
      }
    }

    LinearProgressIndicator(
      progress = { progress.coerceIn(0f, 1f) },
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(horizontal = 18.dp)
        .fillMaxWidth()
        .height(2.dp),
      color = MaterialTheme.colorScheme.primary,
      trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
      // No stop dot and no gap: a single continuous bar
      gapSize = 0.dp,
      drawStopIndicator = {}
    )
  }
}
