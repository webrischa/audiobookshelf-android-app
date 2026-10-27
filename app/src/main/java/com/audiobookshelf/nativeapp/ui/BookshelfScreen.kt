package com.audiobookshelf.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiobookshelf.nativeapp.AbsViewModel
import com.audiobookshelf.nativeapp.batchChanged
import com.audiobookshelf.nativeapp.data.CollapsedSeriesDto
import com.audiobookshelf.nativeapp.data.LibraryItemDto
import com.audiobookshelf.nativeapp.data.ShelfSort
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

@Composable
fun BookshelfScreen(
  vm: AbsViewModel,
  libraryId: String,
  entryId: String,
  onOpenItem: (String) -> Unit,
  onOpenSeries: (CollapsedSeriesDto) -> Unit,
  onBack: () -> Unit,
  onSearch: () -> Unit,
  onSettings: () -> Unit
) {
  // Guard against an empty route argument: fall back to the remembered library instead of
  // requesting /api/libraries//items
  val effectiveLibraryId = libraryId.ifBlank {
    vm.lastLibraryId ?: vm.libraries.firstOrNull()?.id.orEmpty()
  }
  LaunchedEffect(effectiveLibraryId) {
    if (effectiveLibraryId.isNotBlank()) vm.openShelf(effectiveLibraryId)
  }

  // View mode, folding and sort come from the per-library preferences held by the view model
  val listView = vm.shelfListView

  var sortMenuOpen by remember { mutableStateOf(false) }

  // One browsing session per NavBackStackEntry. The reset has to be synchronous (the states
  // below are seeded from it), so it lives in a remember initialiser rather than an effect;
  // it only touches plain ViewModel fields, never snapshot state. Returning from a detail page
  // recomposes the same entry and keeps the mirrored position; a fresh entry starts at the top.
  val seed = remember(entryId) { vm.startShelfSession(entryId) }
  val gridState = remember(entryId) { LazyGridState(seed.gridIndex, seed.gridOffset) }
  val listState = remember(entryId) { LazyListState(seed.listIndex, seed.listOffset) }

  // Mirror the live position into the view model: it survives a reload that briefly empties the
  // list (the grid clamps its state to 0 while empty) and any navigation round trip. Paging
  // updates it as the user scrolls, so a restore is always to the latest position.
  LaunchedEffect(listView) {
    if (listView) {
      snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
        .collect { (index, offset) ->
          vm.shelfScroll.save(listView = true, index = index, offset = offset)
        }
    } else {
      snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
        .collect { (index, offset) ->
          vm.shelfScroll.save(listView = false, index = index, offset = offset)
        }
    }
  }

  // States are per session now; pagination reads them directly. loadItems(reset = false) only
  // appends to the end, so the first item id below stays put and paging never resets the scroll.
  val shouldLoadMoreGrid by remember {
    derivedStateOf {
      val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      vm.items.isNotEmpty() && lastVisible >= vm.items.size - 8
    }
  }
  val shouldLoadMoreList by remember {
    derivedStateOf { shouldLoadMore(listState, vm.items.size) }
  }
  LaunchedEffect(shouldLoadMoreGrid, listView) {
    if (!listView && shouldLoadMoreGrid) vm.loadMoreItems()
  }
  LaunchedEffect(shouldLoadMoreList, listView) {
    if (listView && shouldLoadMoreList) vm.loadMoreItems()
  }

  // Sorting, folding or switching library replaces the whole list; without this the grid would
  // keep its old scroll offset and appear to start in the middle of the new order. Returning
  // from a detail page leaves the first item id untouched, so the restored offset survives.
  val firstItemId = vm.items.firstOrNull()?.id.orEmpty()
  var lastFirstItemId by remember { mutableStateOf(firstItemId) }
  LaunchedEffect(firstItemId) {
    if (vm.items.isNotEmpty() && batchChanged(lastFirstItemId, firstItemId)) {
      gridState.scrollToItem(0)
      listState.scrollToItem(0)
      vm.shelfScroll.reset()
    }
    lastFirstItemId = firstItemId
  }

  // Safety net for everything that clamps the state to 0 behind our back (a reload emptying the
  // list, a lost state): when the list changed but the visible index fell behind the mirror,
  // bring it back up. Identical positions are a no-op, so paging appends never move the view.
  LaunchedEffect(vm.items, listView) {
    val target = vm.shelfScroll.index(listView)
    val offset = vm.shelfScroll.offset(listView)
    if (target > 0 && vm.items.size > target) {
      if (listView) {
        if (listState.firstVisibleItemIndex < target) listState.scrollToItem(target, offset)
      } else if (gridState.firstVisibleItemIndex < target) {
        gridState.scrollToItem(target, offset)
      }
    }
  }

  Box(Modifier.fillMaxSize()) {
    // Page content is recorded so the sort menu can sample it like real glass. Only while that
    // menu is open (plus its exit animation): layerBackdrop copies the whole page into an
    // offscreen layer on every frame, and the sort menu is the only reader here. This screen used
    // to detach on the very frame the menu closed, which left the closing menu sampling a dead
    // layer - the shared helper makes all three pages behave the same way.
    val pageBackdrop = rememberLayerBackdrop()
    val pageRecording = rememberBackdropRecording(sortMenuOpen)
    Box(
      Modifier
        .fillMaxSize()
        .then(if (pageRecording) Modifier.layerBackdrop(pageBackdrop) else Modifier)
    ) {
      Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
      GlassBar {
        ScreenHeader(
          title = "书架",
          onBack = onBack,
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
        }
      ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
          ErrorBanner(vm.error) { vm.clearError() }

          ShelfToolbar(
            itemCount = vm.itemsTotal,
            listView = listView,
            collapseSeries = vm.collapseSeries,
            sort = vm.shelfSort,
            descending = vm.shelfSortDesc,
            onToggleListView = { vm.updateShelfListView(!vm.shelfListView) },
            onToggleSeries = { vm.updateCollapseSeries(!vm.collapseSeries) },
            onOpenSort = { sortMenuOpen = true }
          )

          when {
            vm.loading && vm.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
            // Nothing loaded yet: the list is empty for the one frame before this screen's
            // LaunchedEffect starts the request, which is not the same as an empty shelf. Skipped
            // on failure so a broken address shows the error banner instead of spinning forever.
            vm.items.isEmpty() && vm.error == null && !vm.isShelfLoaded(effectiveLibraryId) ->
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
              }
            vm.items.isEmpty() -> EmptyState("这个媒体库还没有条目")
            listView -> LazyColumn(
              state = listState,
              contentPadding = PaddingValues(
                start = 12.dp,
                top = 4.dp,
                end = 12.dp,
                bottom = 12.dp + LocalMiniPlayerBottomInset.current
              ),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxSize()
            ) {
              listItems(vm.items, key = { it.id }) { item ->
                val series = item.collapsedSeries
                if (series != null) {
                  SeriesListRow(
                    name = series.name,
                    author = item.media.metadata.displayAuthor().orEmpty(),
                    count = series.numBooks,
                    covers = series.libraryItemIds.take(3).map { vm.coverUrl(it) },
                    progress = seriesProgress(vm, series.libraryItemIds),
                    onClick = { onOpenSeries(series) }
                  )
                } else {
                  BookListRow(
                    item = item,
                    coverUrl = vm.coverUrl(item.id),
                    progress = vm.progressFor(item.id)?.progress ?: 0.0,
                    onClick = { onOpenItem(item.id) }
                  )
                }
              }
              if (vm.loadingMore) {
                item(key = "loading-more") {
                  LoadingMoreRow()
                }
              }
            }
            else -> LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 132.dp),
              state = gridState,
              contentPadding = PaddingValues(
                start = 12.dp,
                top = 4.dp,
                end = 12.dp,
                bottom = 12.dp + LocalMiniPlayerBottomInset.current
              ),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp),
              modifier = Modifier.fillMaxSize()
            ) {
              gridItems(vm.items, key = { it.id }) { item ->
                val series = item.collapsedSeries
                if (series != null) {
                  GlassSeriesCard(
                    name = series.name,
                    author = item.media.metadata.displayAuthor().orEmpty(),
                    count = series.numBooks,
                    covers = series.libraryItemIds.take(3).map { vm.coverUrl(it) },
                    progress = seriesProgress(vm, series.libraryItemIds),
                    onClick = { onOpenSeries(series) }
                  )
                } else {
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
              if (vm.loadingMore) {
                item(key = "loading-more", span = { GridItemSpan(maxLineSpan) }) {
                  LoadingMoreRow()
                }
              }
            }
          }
        }
      }
    }

    if (sortMenuOpen) {
      // Scrim closes the menu; it sits outside the recorded layer so the menu can sample the page
      Box(
        Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.30f))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
          ) { sortMenuOpen = false }
      )
      SortMenu(
        backdrop = pageBackdrop,
        current = vm.shelfSort,
        descending = vm.shelfSortDesc,
        onPick = { sort, descending ->
          vm.updateShelfSort(sort, descending)
          sortMenuOpen = false
        },
        modifier = Modifier
          .align(Alignment.TopEnd)
          .statusBarsPadding()
          .padding(top = 104.dp, end = 12.dp)
          .width(190.dp)
      )
    }
  }
}

/** Spinner shown at the end of the grid/list while the next page is loading. */
@Composable
private fun LoadingMoreRow() {
  Box(
    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
    contentAlignment = Alignment.Center
  ) {
    CircularProgressIndicator(
      modifier = Modifier.size(22.dp),
      strokeWidth = 2.dp,
      color = MaterialTheme.colorScheme.primary
    )
  }
}

/** Liquid glass dropdown with the shelf sort options; tapping the active one flips the direction. */
@Composable
private fun SortMenu(
  backdrop: Backdrop,
  current: ShelfSort,
  descending: Boolean,
  onPick: (ShelfSort, Boolean) -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .drawBackdrop(
        backdrop = backdrop,
        shape = { RoundedCornerShape(20.dp) },
        effects = {
          vibrancy()
          blur(10.dp.toPx())
          lens(10.dp.toPx(), 24.dp.toPx())
        },
        onDrawSurface = {
          drawRect(Color.Black.copy(alpha = 0.34f))
          drawRect(Color.White.copy(alpha = 0.06f))
        }
      )
      .clip(RoundedCornerShape(20.dp))
      .padding(vertical = 6.dp)
      .consumeOverlayTaps()
  ) {
    ShelfSort.entries.forEach { option ->
      val selected = option == current
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable {
            if (selected) {
              onPick(option, !descending)
            } else {
              // Same as the original picker: only "recently added" forces descending, every other
              // field keeps the direction that is already active
              onPick(option, if (option == ShelfSort.ADDED) true else descending)
            }
          }
          .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = option.label,
          style = MaterialTheme.typography.bodyMedium,
          color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
          modifier = Modifier.weight(1f)
        )
        if (selected) {
          Icon(
            imageVector = if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
            contentDescription = if (descending) "降序" else "升序",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
          )
        }
      }
    }
  }
}

/** Item count on the left, sort / folding / view switch on the right. */
@Composable
private fun ShelfToolbar(
  itemCount: Int,
  listView: Boolean,
  collapseSeries: Boolean,
  sort: ShelfSort,
  descending: Boolean,
  onToggleListView: () -> Unit,
  onToggleSeries: () -> Unit,
  onOpenSort: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(44.dp)
      .padding(start = 20.dp, end = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = if (itemCount > 0) "$itemCount 项" else "",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.weight(1f))
    IconButton(onClick = onOpenSort) {
      // Sort glyph plus a small arrow showing the active direction
      Box(
        modifier = Modifier.size(24.dp).semantics {
          contentDescription = "排序：${sort.label}，${if (descending) "降序" else "升序"}"
        },
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.Sort,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(22.dp)
        )
        Icon(
          imageVector = if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 1.dp, y = 1.dp)
            .size(9.dp)
        )
      }
    }
    IconButton(onClick = onToggleSeries) {
      Icon(
        imageVector = if (collapseSeries) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
        contentDescription = if (collapseSeries) "取消系列折叠" else "折叠系列",
        tint = if (collapseSeries) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        }
      )
    }
    IconButton(onClick = onToggleListView) {
      // Shows the mode currently in use, not the one a tap would switch to
      Icon(
        imageVector = if (listView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
        contentDescription = if (listView) "当前为列表视图，点击切换为网格" else "当前为网格视图，点击切换为列表",
        tint = MaterialTheme.colorScheme.onSurface
      )
    }
  }
}

/**
 * Collapsed series in the grid: one card whose cover is the series' books stacked on top of each
 * other, with the book count as a badge on the top right corner.
 */
@Composable
private fun GlassSeriesCard(
  name: String,
  author: String,
  count: Int,
  covers: List<String>,
  progress: Double,
  onClick: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .glassSurface(RoundedCornerShape(22.dp))
      .clip(RoundedCornerShape(22.dp))
      .clickable(onClick = onClick)
      .padding(8.dp)
  ) {
    // Clip to the same radius as the cover so the progress bar cannot spill out of the corners
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))) {
      // The cover is recorded so the badge can sample it and refract like real glass
      val coverBackdrop = rememberLayerBackdrop()
      Box(Modifier.fillMaxSize().layerBackdrop(coverBackdrop)) {
        StackedCovers(covers = covers, modifier = Modifier.fillMaxSize())
      }
      SeriesCountBadge(
        count = count,
        backdrop = coverBackdrop,
        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
      )
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
      text = name,
      style = MaterialTheme.typography.bodySmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    if (author.isNotBlank()) {
      Text(
        text = author,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/** List row: cover on the left, title + author on the right. */
@Composable
private fun BookListRow(
  item: LibraryItemDto,
  coverUrl: String,
  progress: Double,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .glassSurface(RoundedCornerShape(16.dp))
      .clip(RoundedCornerShape(16.dp))
      .clickable(onClick = onClick)
      .padding(10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    // Progress rides on the bottom edge of the cover, same as the grid cards
    Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))) {
      BookCover(
        url = coverUrl,
        hasCover = item.hasCover(),
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(10.dp)
      )
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
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = item.media.metadata.title ?: "未命名",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      val author = item.media.metadata.displayAuthor()
      if (!author.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
          text = author,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

/** Collapsed series in the list view: stacked cover, series name and author. */
@Composable
private fun SeriesListRow(
  name: String,
  author: String,
  count: Int,
  covers: List<String>,
  progress: Double,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .glassSurface(RoundedCornerShape(16.dp))
      .clip(RoundedCornerShape(16.dp))
      .clickable(onClick = onClick)
      .padding(10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) {
      val coverBackdrop = rememberLayerBackdrop()
      Box(Modifier.fillMaxSize().layerBackdrop(coverBackdrop)) {
        StackedCovers(
          covers = covers,
          modifier = Modifier.fillMaxSize(),
          shape = RoundedCornerShape(8.dp)
        )
      }
      SeriesCountBadge(
        count = count,
        backdrop = coverBackdrop,
        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
        compact = true
      )
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
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = name,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      if (author.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
          text = author,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

/** Round liquid glass badge on the cover corner showing how many books the series holds. */
@Composable
private fun SeriesCountBadge(
  count: Int,
  backdrop: Backdrop,
  modifier: Modifier = Modifier,
  compact: Boolean = false
) {
  if (count <= 0) return
  Box(
    modifier = modifier
      .size(if (compact) 18.dp else 24.dp)
      .drawBackdrop(
        backdrop = backdrop,
        shape = { CircleShape },
        effects = {
          blur(6.dp.toPx())
          lens(
            refractionHeight = 4.dp.toPx(),
            refractionAmount = 8.dp.toPx(),
            chromaticAberration = true
          )
        },
        highlight = { Highlight.Ambient.copy(alpha = 0.85f) },
        shadow = { Shadow(radius = 3.dp, color = Color.Black.copy(alpha = 0.22f)) },
        onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.30f)) }
      )
      .semantics { contentDescription = "$count 本" },
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = "$count",
      color = Color.White,
      fontSize = if (compact) 9.sp else 10.sp,
      fontWeight = FontWeight.Bold
    )
  }
}

/**
 * Draws up to three covers offset behind each other so the card reads as a stack of books, the
 * same idea as the original app's group cover.
 */
@Composable
private fun StackedCovers(
  covers: List<String>,
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(14.dp)
) {
  Box(modifier = modifier) {
    if (covers.size >= 3) {
      BookCover(
        url = covers[2],
        modifier = Modifier.fillMaxSize().padding(start = 14.dp, top = 14.dp),
        shape = shape
      )
    }
    if (covers.size >= 2) {
      BookCover(
        url = covers[1],
        modifier = Modifier.fillMaxSize().padding(start = 7.dp, top = 7.dp),
        shape = shape
      )
    }
    BookCover(
      url = covers.firstOrNull().orEmpty(),
      modifier = Modifier.fillMaxSize(),
      shape = shape
    )
  }
}

/**
 * Overall listening progress of the series: every book contributes its own share, so a book that
 * is half listened moves the bar by half a book instead of snapping to "started" or "finished".
 */
private fun seriesProgress(vm: AbsViewModel, itemIds: List<String>): Double {
  if (itemIds.isEmpty()) return 0.0
  val listened = itemIds.sumOf { id ->
    (vm.progressFor(id)?.progress ?: 0.0).coerceIn(0.0, 1.0)
  }
  return (listened / itemIds.size).coerceIn(0.0, 1.0)
}

/** True when the list is scrolled close enough to the end to request the next page. */
private fun shouldLoadMore(state: LazyListState, itemCount: Int): Boolean {
  if (itemCount == 0) return false
  val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
  return lastVisible >= itemCount - 8
}
