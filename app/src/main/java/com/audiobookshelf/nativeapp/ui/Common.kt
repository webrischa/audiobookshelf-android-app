package com.audiobookshelf.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter

/**
 * The single app bar every screen uses: 60dp tall, 6dp side padding, a 48dp icon slot on the left
 * (back arrow when [onBack] is set), a title that takes the remaining width, actions on the right.
 *
 * Screens used to hand roll this Row with slightly different paddings and heights, which made the
 * back arrow and the title jump when navigating. One implementation removes that class of drift.
 */
@Composable
fun ScreenHeader(
  title: String?,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  titleTrailing: (@Composable () -> Unit)? = null,
  leading: (@Composable RowScope.() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {}
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .height(60.dp)
      .padding(horizontal = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (leading != null) {
      // Screens whose left slot is an entry point instead of a back arrow (library picker)
      leading()
    } else {
      if (onBack != null) {
        IconButton(onClick = onBack) {
          Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = MaterialTheme.colorScheme.onSurface
          )
        }
      }
      if (title != null) {
        // A trailing slot (e.g. the detail screen's sync spinner / 无法连接 badge) sits right
        // after the title and must stay readable even when the title is long, hence the title
        // keeps the flexible slot.
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
          if (titleTrailing != null) {
            Spacer(Modifier.width(8.dp))
            titleTrailing()
          }
        }
      } else {
        Spacer(Modifier.weight(1f))
      }
    }
    actions()
  }
}

/**
 * Cover with a placeholder that appears only when there is genuinely no artwork.
 *
 * [hasCover] comes from the server's `media.coverPath`, the same check the original client uses.
 * `false` means the book has no cover: the glyph is drawn immediately and no image request is
 * made at all (the original points at a bundled placeholder). `true` means it does: loading keeps
 * a plain tinted box and the artwork replaces it without a book glyph flashing first. `null`
 * means the caller had no item data (e.g. a collapsed series that only carries ids), so the
 * Coil load result is used as the fallback. [AsyncImage] is kept rather than a raw painter so
 * Coil still sizes the request to the composable.
 */
@Composable
fun BookCover(
  url: String,
  hasCover: Boolean? = null,
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(6.dp)
) {
  Box(
    modifier = modifier.clip(shape).background(Color.White.copy(alpha = 0.10f)),
    contentAlignment = Alignment.Center
  ) {
    // Reset on a new url: a failure for the previous book must not paint a placeholder over the
    // next one while its cover loads.
    var loadFailed by remember(url) { mutableStateOf(false) }
    val showPlaceholder = when {
      url.isBlank() -> true
      hasCover == false -> true
      else -> loadFailed
    }
    if (showPlaceholder) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.MenuBook,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.28f),
        modifier = Modifier.fillMaxSize(0.3f)
      )
    }
    // A book the server says has no artwork never needs a round trip for a 404.
    if (url.isNotBlank() && hasCover != false) {
      AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onState = { state -> loadFailed = state is AsyncImagePainter.State.Error },
        modifier = Modifier.fillMaxSize()
      )
    }
  }
}

@Composable
fun ErrorBanner(message: String?, onDismiss: () -> Unit) {
  if (message.isNullOrBlank()) return
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
      .padding(12.dp)
  ) {
    Text(
      text = message,
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.fillMaxWidth()
    )
  }
}

/**
 * Placeholder for a screen without content.
 *
 * [alignment] is exposed because a search page reads better with its hints directly under the
 * query field than with a message floating in the middle of an otherwise empty page.
 */
@Composable
fun EmptyState(
  text: String,
  alignment: Alignment = Alignment.Center,
  modifier: Modifier = Modifier
) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = alignment) {
    Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
  }
}

fun formatTime(ms: Long): String {
  val totalSeconds = (ms / 1000).coerceAtLeast(0)
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) {
    "%d:%02d:%02d".format(hours, minutes, seconds)
  } else {
    "%d:%02d".format(minutes, seconds)
  }
}

fun formatDurationShort(seconds: Double): String {
  val totalMinutes = (seconds / 60).toLong()
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
