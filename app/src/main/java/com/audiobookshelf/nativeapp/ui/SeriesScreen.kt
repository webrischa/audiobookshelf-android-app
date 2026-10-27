package com.audiobookshelf.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.audiobookshelf.nativeapp.AbsViewModel

/**
 * Books of one series, opened from a collapsed series card.
 *
 * The server has no batch endpoint for "items by series" on this version, so the ids reported in
 * the collapsed entry are fetched one by one in the view model.
 */
@Composable
fun SeriesScreen(
  vm: AbsViewModel,
  onOpenItem: (String) -> Unit,
  onBack: () -> Unit
) {
  val series = vm.currentSeries

  Scaffold(
    containerColor = Color.Transparent,
    contentColor = MaterialTheme.colorScheme.onBackground,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      GlassBar {
        ScreenHeader(
          title = series?.name?.takeIf { it.isNotBlank() } ?: "系列",
          onBack = onBack
        )
      }
    }
  ) { padding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .navigationBarsPadding()
    ) {
      when {
        vm.seriesLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
        vm.seriesItems.isEmpty() -> EmptyState("这个系列还没有书")
        else -> LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 132.dp),
          contentPadding = PaddingValues(
            start = 12.dp,
            top = 12.dp,
            end = 12.dp,
            bottom = 12.dp + LocalMiniPlayerBottomInset.current
          ),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier = Modifier.fillMaxSize()
        ) {
          items(vm.seriesItems, key = { it.id }) { item ->
            GlassItemCard(
              coverUrl = vm.coverUrl(item.id),
              hasCover = item.hasCover(),
              title = item.media.metadata.title ?: "未命名",
              subtitle = item.media.metadata.authorName ?: "",
              progress = vm.progressFor(item.id)?.progress ?: 0.0,
              onClick = { onOpenItem(item.id) },
              modifier = Modifier.fillMaxWidth()
            )
          }
        }
      }
    }
  }
}
