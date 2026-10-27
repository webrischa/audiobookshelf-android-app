package com.audiobookshelf.nativeapp.ui

import com.audiobookshelf.nativeapp.util.AppLog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiobookshelf.nativeapp.AbsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemScreen(
  vm: AbsViewModel,
  itemId: String,
  onBack: () -> Unit,
  onOpenPlayer: () -> Unit
) {
  // A popped destination stays composed while the crossfade runs, so its rows are still in the
  // hit-test tree and can receive the next tap even though the user already sees the previous
  // screen. Guard both playback entry points with the destination lifecycle: a tap that lands on
  // a leaving page must not start a book.
  val lifecycleOwner = LocalLifecycleOwner.current
  var pageActive by remember { mutableStateOf(true) }
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      pageActive = event.targetState == Lifecycle.State.RESUMED
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(itemId) {
    AppLog.tapTrace("DETAIL_ENTER", itemId)
    vm.loadItem(itemId)
  }
  val playback by vm.player.state.collectAsStateWithLifecycle()

  if (vm.itemMissing) {
    // The server no longer knows this item: local data (if any) still renders behind the
    // dialog, and confirming sends the user back to wherever the book was opened from.
    AlertDialog(
      onDismissRequest = { vm.dismissMissingItem() },
      title = { Text("书本已被删除") },
      text = { Text("服务器上已找不到这本书，返回上一页。") },
      confirmButton = {
        TextButton(onClick = { vm.dismissMissingItem(); onBack() }) { Text("知道了") }
      }
    )
  }

  Scaffold(
    containerColor = Color.Transparent,
      contentColor = MaterialTheme.colorScheme.onBackground,
    topBar = {
      GlassBar {
        ScreenHeader(
          title = vm.detailItem?.media?.metadata?.title ?: "详情",
          // A spinner while the refresh runs, a quiet 无法连接 badge when it failed over
          // already-rendered local data; neither shows for a plain page.
          titleTrailing = when {
            vm.itemSyncing -> {
              {
                CircularProgressIndicator(
                  color = Color.White,
                  modifier = Modifier.size(16.dp),
                  strokeWidth = 2.dp
                )
              }
            }
            vm.itemSyncFailed -> {
              {
                Text(
                  text = "无法连接",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1
                )
              }
            }
            else -> null
          },
          onBack = onBack
        )
      }
    }
  ) { padding ->
    val libraryItem = vm.detailItem
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      ErrorBanner(vm.error) { vm.clearError() }

      if (libraryItem == null) {
        // No local copy to render: block until the load finishes, then offer a retry when it
        // failed. A 404 opens the "book was deleted" dialog instead and sends the user back.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          if (vm.loading) {
            CircularProgressIndicator()
          } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                text = vm.error ?: "无法连接服务器",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
              )
              Spacer(Modifier.height(12.dp))
              Button(onClick = { vm.loadItem(itemId) }) { Text("重试") }
            }
          }
        }
        return@Column
      }

      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalMiniPlayerBottomInset.current)
      ) {
        item {
          val userProgress = vm.progressFor(libraryItem.id) ?: libraryItem.userMediaProgress
          val progress = userProgress?.progress ?: 0.0
          val isCurrent = vm.playingItemId == libraryItem.id
          val isPlaying = isCurrent && playback.isPlayingOrStarting
          val metadata = libraryItem.media.metadata
          val authorName = metadata.displayAuthor() ?: "未知作者"
          // media.duration is often 0 and tracks may be missing: try every source we have
          val progressDuration = userProgress?.duration ?: 0.0
          val totalSeconds = when {
            libraryItem.media.duration > 0 -> libraryItem.media.duration
            progressDuration > 0 -> progressDuration
            libraryItem.media.tracks.isNotEmpty() -> libraryItem.media.tracks.sumOf { it.duration }
            else -> libraryItem.media.chapters.sumOf { it.end - it.start }
          }

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 6.dp)
              .glassSurface()
              .padding(16.dp)
          ) {
            BookCover(
              url = vm.coverUrl(libraryItem.id, width = 600),
              hasCover = libraryItem.hasCover(),
              modifier = Modifier.size(120.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(metadata.title ?: "未命名", style = MaterialTheme.typography.titleLarge)

              Spacer(Modifier.height(8.dp))
              // Play button sits with the author / chapter lines, below the title
              Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    authorName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                  )
                  metadata.narratorName?.takeIf { it.isNotBlank() }?.let {
                    Text("朗读：$it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                  }
                  Spacer(Modifier.height(6.dp))
                  Text(
                    "共 ${vm.detailChapters.size} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  if (totalSeconds > 0) {
                    Text(
                      "时长 ${formatDurationShort(totalSeconds)}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }
                Spacer(Modifier.width(8.dp))
                // Liquid glass play/pause: same material language as the catalog's LiquidButton
                LiquidGlassButton(
                  onClick = {
                    AppLog.tapTrace("DETAIL_PLAY_CLICK", "id=${libraryItem.id} isCurrent=$isCurrent isPlaying=$isPlaying active=$pageActive")
                    if (pageActive) {
                      if (isPlaying) {
                        // Already running: the button is a pause control, so it only pauses and the
                        // detail page stays where it is
                        vm.togglePlayPause()
                      } else {
                        if (isCurrent) vm.togglePlayPause() else vm.playItem(libraryItem.id)
                        // Starting playback opens the player page, the way tapping the mini bar does
                        onOpenPlayer()
                      }
                    }
                  },
                  enabled = !vm.loading,
                  modifier = Modifier.size(52.dp)
                ) {
                  Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(28.dp),
                    // Clear glass carries no fill, so the icon has to carry the contrast itself
                    tint = Color.White
                  )
                }
              }

              if (progress > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                  progress = { progress.toFloat().coerceIn(0f, 1f) },
                  modifier = Modifier.fillMaxWidth().height(4.dp),
                  // No stop dot and no gap: a single continuous bar
                  gapSize = 0.dp,
                  drawStopIndicator = {}
                )
                Spacer(Modifier.height(6.dp))
                Text(
                  "已听 ${(progress * 100).toInt()}% · ${formatTime(((userProgress?.currentTime ?: 0.0) * 1000).toLong())}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        }

        val description = libraryItem.media.metadata.description
        if (!description.isNullOrBlank()) {
          item {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .glassSurface()
                .padding(16.dp)
            ) {
              Text("简介", style = MaterialTheme.typography.titleSmall)
              Spacer(Modifier.height(6.dp))
              Text(
                description.replace(Regex("<[^>]+>"), ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
        }

        item {
          Text(
            "章节",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
          )
        }

        itemsIndexed(vm.detailChapters) { index, chapter ->
          // The book may already be the one loaded in the player: jumping to a chapter is then a
          // seek, not a reload. Going through playItem rebuilt the session and the playlist, which
          // discarded the buffer and re-requested the stream for a chapter that was already here.
          val loadedInPlayer = vm.playingItemId == libraryItem.id && playback.hasMedia
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                AppLog.tapTrace("CHAPTER_CLICK", "index=$index title=${chapter.title} loaded=$loadedInPlayer active=$pageActive")
                if (pageActive) {
                  if (loadedInPlayer) {
                    vm.playChapter(chapter)
                  } else {
                    vm.playItem(libraryItem.id, startAtMs = (chapter.start * 1000).toLong())
                  }
                  // Jumping to a chapter is a "start listening here" action, so it opens the player
                  onOpenPlayer()
                }
              }
              .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                chapter.title.ifBlank { "第 ${index + 1} 章" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                formatTime((chapter.start * 1000).toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Text(
              formatDurationShort(chapter.end - chapter.start),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
      }
    }
  }
}
