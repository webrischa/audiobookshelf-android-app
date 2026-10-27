package com.audiobookshelf.nativeapp.ui

import com.audiobookshelf.nativeapp.util.AppLog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiobookshelf.nativeapp.AbsViewModel
import com.audiobookshelf.nativeapp.batchChanged
import com.audiobookshelf.nativeapp.shelfBatchKey

@Composable
fun HomeScreen(
  vm: AbsViewModel,
  libraryId: String,
  rowStateFor: (String) -> LazyListState,
  onOpenItem: (String) -> Unit,
  onOpenBookshelf: () -> Unit,
  onOpenLibraries: () -> Unit,
  onSearch: () -> Unit,
  onSettings: () -> Unit
) {
  LaunchedEffect(libraryId) { vm.loadHome(libraryId) }

  val library = vm.libraries.find { it.id == libraryId }
  val mediaType = library?.mediaType
  // A single library has nothing to pick from, so the header stays a plain, inert label
  val canSwitchLibrary = vm.libraries.size != 1
  // Observed so cover urls are recomputed after a LAN/remote address switch
  vm.activeServerUrl

  Box(Modifier.fillMaxSize()) {
    Scaffold(
      containerColor = Color.Transparent,
      contentColor = MaterialTheme.colorScheme.onBackground,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      topBar = {
        GlassBar {
          LibraryHeader(
            libraryName = library?.name ?: "媒体库",
            mediaType = mediaType,
            canSwitchLibrary = canSwitchLibrary,
            onOpenLibraries = onOpenLibraries,
            onSearch = onSearch,
            onSettings = onSettings
          )
        }
      }
    ) { padding ->
      val hasShelves = vm.shelves.any { it.entities.isNotEmpty() }
      // An empty list is also what the very first frame looks like, before this screen's
      // LaunchedEffect has started the load: showing the empty hint there made it flash for a
      // frame on every entry to the home screen (login, library switch, returning from settings).
      val loaded = libraryId.isBlank() || vm.isHomeLoaded(libraryId)
      when {
        vm.homeLoading && !hasShelves -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        // A failed switch must not look like an empty library
        vm.error != null && !hasShelves -> Column(
          modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = vm.error ?: "加载失败",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
          )
          Spacer(Modifier.height(12.dp))
          Button(onClick = { vm.loadHome(libraryId, force = true) }) { Text("重试") }
        }
        !hasShelves && !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        !hasShelves -> EmptyState("媒体库还是空的")
        else -> LazyColumn(
          modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
          contentPadding = PaddingValues(bottom = LocalMiniPlayerBottomInset.current)
        ) {
          // The error branch above only covers "nothing on screen yet". Everything that fails
          // while the shelves are already up — starting a book, refreshing progress — used to be
          // completely silent here, so a failed tap on play looked like a tap that never landed.
          item(key = "error") {
            ErrorBanner(vm.error) { vm.clearError() }
          }
          item {
            // The full bookshelf grid is a secondary entry now.
            // A bare clickable draws its bounded ripple as a square-cornered rectangle, which
            // overshoots the card's rounded rim. Clipping to the very same shape keeps the pressed
            // layer exactly coextensive with the card: no bigger, no smaller.
            val shelfEntryShape = RoundedCornerShape(26.dp)
            GlassCard(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(shelfEntryShape)
                .clickable(onClick = onOpenBookshelf),
              shape = shelfEntryShape
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  Icons.Default.Book,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("书架", style = MaterialTheme.typography.titleSmall)
                  Text(
                    "查看全部书籍",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                Icon(
                  Icons.AutoMirrored.Filled.ArrowForwardIos,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }

          vm.shelves.filter { it.entities.isNotEmpty() }.forEach { shelf ->
            val shelfKey = shelf.id.ifBlank { shelf.label }
            item(key = shelfKey) {
              Text(
                text = shelfLabel(shelf.label, shelf.labelStringKey),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp)
              )
            }
            item(key = "$shelfKey-items") {
              // Hoisted to AbsNav: NavHost disposes HOME on a detail/player round trip, but the
              // same state instance is handed back here, so the horizontal offset survives.
              val rowState = remember(shelfKey) { rowStateFor(shelfKey) }
              val batch = shelfBatchKey(shelf.entities)
              // NavHost only composes the top destination: opening a detail page or the player
              // disposes HOME while it stays on the back stack. rememberSaveable restores both
              // the row's scroll position and the last batch observed, so a plain back navigation
              // (or the first composition) leaves the restored position untouched. Only a real
              // batch change — e.g. the server re-randomising "discover" — resets this row, and
              // only this row.
              var lastBatch by rememberSaveable { mutableStateOf(batch) }
              LaunchedEffect(batch) {
                val changed = batchChanged(lastBatch, batch)
                AppLog.tapTrace("SHELF_BATCH", "shelf=$shelfKey old=$lastBatch new=$batch changed=$changed")
                if (changed) rowState.scrollToItem(0)
                lastBatch = batch
              }
              LaunchedEffect(shelfKey) {
                snapshotFlow { rowState.firstVisibleItemIndex to rowState.firstVisibleItemScrollOffset }
                  .collect { (index, offset) ->
                    AppLog.tapTrace("SHELF_SCROLL", "shelf=$shelfKey index=$index offset=$offset")
                  }
              }
              LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                items(shelf.entities) { item ->
                  GlassItemCard(
                    coverUrl = vm.coverUrl(item.id),
                    hasCover = item.hasCover(),
                    title = item.media.metadata.title ?: "未命名",
                    subtitle = item.media.metadata.displayAuthor() ?: "",
                    progress = vm.progressFor(item.id)?.progress ?: 0.0,
                    modifier = Modifier.width(152.dp),
                    onClick = {
                      AppLog.tapTrace("HOME_ITEM_CLICK", "id=${item.id} shelf=$shelfKey")
                      onOpenItem(item.id)
                    }
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * The server returns english shelf labels; newer versions also send a localization key
 * (e.g. "LabelContinueListening"), which is the more stable thing to match on.
 */
private fun shelfLabel(label: String, labelStringKey: String?): String {
  val key = (labelStringKey?.takeIf { it.isNotBlank() } ?: label).trim().lowercase()
  return when (key) {
    "labelcontinuelistening", "continue listening" -> "最近播放"
    "labelcontinueseries", "continue series" -> "继续系列"
    "labelrecentlyadded", "recently added" -> "最近添加"
    "labellistenagain", "listen again" -> "再次收听"
    "labeldiscover", "discover" -> "发现"
    else -> label
  }
}

/**
 * Library home header. The left slot is the library entry (icon, name, chevron) that opens the
 * library picker; with a single library there is nothing to pick from, so the chevron disappears
 * and the slot becomes a plain label. Height, paddings, title style and action slots come from
 * [ScreenHeader] so it lines up with every other screen.
 */
@Composable
private fun LibraryHeader(
  libraryName: String,
  mediaType: String?,
  canSwitchLibrary: Boolean,
  onOpenLibraries: () -> Unit,
  onSearch: () -> Unit,
  onSettings: () -> Unit
) {
  ScreenHeader(
    title = null,
    leading = {
      Row(
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          // Nothing to open: skip clickable entirely, so the row carries no tap target or ripple
          .then(if (canSwitchLibrary) Modifier.clickable(onClick = onOpenLibraries) else Modifier)
          .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = if (mediaType == "podcast") Icons.Default.Podcasts else Icons.Default.LibraryMusic,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = libraryName,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        if (canSwitchLibrary) {
          Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = "切换媒体库",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
      Spacer(Modifier.weight(1f))
    },
    actions = {
      IconButton(onClick = onSearch) {
        Icon(Icons.Default.Search, contentDescription = "搜索", tint = MaterialTheme.colorScheme.onSurface)
      }
      IconButton(onClick = onSettings) {
        Icon(Icons.Default.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurface)
      }
    }
  )
}
