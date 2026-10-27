package com.audiobookshelf.nativeapp.ui
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiobookshelf.nativeapp.AbsViewModel
import com.audiobookshelf.nativeapp.PLAYER_COVER_WIDTH
import com.audiobookshelf.nativeapp.R
import com.audiobookshelf.nativeapp.data.ChapterDto
import com.audiobookshelf.nativeapp.playback.PlayerConnection
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.delay


private val SpeedPresets = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f)
// 0 cancels the timer; the rest are the quick picks shown as circles
private val SleepPresets = listOf(0, 30, 60, 120, 240)
internal val SpeedAccent = Color(0xFFE0483C)
internal const val MinSpeed = 0.5f
internal const val MaxSpeed = 2.0f
internal const val SpeedTickCount = 16
internal const val SleepStep = 15
private const val SleepMaxMinutes = 240
internal const val SleepTickCount = SleepMaxMinutes / SleepStep + 1
private val Tabs = listOf("封面", "简介")

/**
 * The overlays of the player page. A single value replaces five independent booleans: a menu and
 * a sheet can no longer be "open" in the same frame, and the hand-off from one to the next is an
 * explicit state transition rather than two unrelated flags racing each other.
 */
private enum class PlayerOverlay { None, Menu, Speed, Sleep, Skip, Chapter }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: AbsViewModel, onBack: () -> Unit) {
  // Only the stable half of the playback snapshot is read here. The playhead ticks twice a second
  // and is collected inside the two composables that draw it (progress block, control row), so a
  // position update no longer re-runs the cover, the description and everything around them.
  val state by vm.player.state.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    // Poll faster while playing; idle pages do not need 2 updates per second
    while (true) {
      vm.player.refresh()
      delay(if (vm.player.state.value.isPlayingOrStarting) 500 else 1_500)
    }
  }

  // Exactly one overlay at a time. The first menu row avoids the word "跳过": automatic
  // ad-skipping accessibility services (e.g. 李跳跳) watch TYPE_WINDOWS_CHANGED and fire
  // ACTION_CLICK on clickable nodes whose text contains that keyword — that, not a replayed
  // touch, was opening the skip sheet after returning from the background. No gesture filters,
  // timing cooldowns or custom semantics are needed once the label is neutral.
  var overlay by remember { mutableStateOf(PlayerOverlay.None) }

  // The overlay is deliberately kept across a background trip. The system captures the Recents
  // thumbnail before any lifecycle callback, so it always shows whatever was on screen; clearing
  // the state afterwards only makes the menu vanish during the return animation. Keeping it means
  // the thumbnail and the live UI agree. Dismiss with the scrim, the ⋮ button or Back.
  var tab by rememberSaveable { mutableIntStateOf(0) }

  // Everything on this page describes the book loaded in the player, never the book that is only
  // open on a detail page
  val book = vm.playingBook
  val chapters = book?.chapters.orEmpty()
  val bookDuration = book?.durationMs?.takeIf { it > 0 } ?: state.durationMs
  // The player page and the lock screen artwork show the cover large; 400px is the shelf size
  val coverUrl = book?.let { vm.coverUrl(it.itemId, width = PLAYER_COVER_WIDTH) }.orEmpty()
  // A book switch keeps the outgoing media in the player until the new session arrives, so the
  // page must not keep showing that outgoing book while the incoming one loads.
  val pendingPlayback = vm.pendingPlayback
  // Comparing pendingPlayback.itemId with state.mediaId was wrong: a cold start stages the last
  // book without loading media, so mediaId is null and the first play of that same book looked
  // like a switch. Compare against the book the page is actually showing instead.
  val shownBookId = book?.itemId ?: state.mediaId
  val switchingBook = pendingPlayback != null && pendingPlayback.itemId != shownBookId
  // Playing the book already on the page needs no page-level loading: cover, description, progress
  // and chapters are all valid, so only the play button carries the wait.
  val preparingPlayback = pendingPlayback != null && !state.hasMedia
  val title = when {
    switchingBook && pendingPlayback.title.isNotBlank() -> pendingPlayback.title
    else -> state.bookTitle.ifBlank { book?.title?.takeIf { it.isNotBlank() } ?: "正在播放" }
  }
  val description = book?.description.orEmpty()

  // The slide-down exit is provided by the navigation transition (see AbsNav)
  val requestClose: () -> Unit = onBack

  // Intro/outro skipping is applied by PlaybackService (see applyChapterSkips) so it keeps
  // working outside this screen. Doing it here as well would seek twice per boundary.

  // System back follows the same slide-down exit as the arrow
  BackHandler(enabled = true) {
    if (overlay != PlayerOverlay.None) {
      overlay = PlayerOverlay.None
    } else {
      requestClose()
    }
  }

  // Page content is recorded here so the glass sheets can sample what is behind them
  val backdrop = rememberLayerBackdrop {
    drawContent()
  }

  // The page is only recorded while a glass panel is on screen: layerBackdrop copies the whole
  // page into an offscreen graphics layer on every frame, and with nothing open nobody reads it.
  // The unmount is held back for the length of the exit animation, otherwise a closing panel would
  // be left sampling a layer that no longer exists.
  val overlayVisible = overlay != PlayerOverlay.None
  val recording = rememberBackdropRecording(overlayVisible)

  // Chapter list highlights the chapter under the playhead when it opens; a transient panel does
  // not need to follow the position while it is open.
  val sheetChapterIndex = PlayerConnection.chapterIndexAt(
    chapters = chapters,
    absoluteMs = if (state.hasMedia) vm.player.currentAbsolutePositionMs() else vm.resumePositionMs
  )

  Box(Modifier.fillMaxSize()) {
    Box(
      Modifier
        .fillMaxSize()
        .then(if (recording) Modifier.layerBackdrop(backdrop) else Modifier)
    ) {
      GlobalBackground(LocalGlobalBackground.current)

      Column(
        modifier = Modifier
          .fillMaxSize()
          .statusBarsPadding()
          .navigationBarsPadding()
      ) {
        // Top bar: collapse arrow + title + overflow menu
        Row(
          modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(onClick = requestClose) {
            Icon(
              Icons.Default.KeyboardArrowDown,
              contentDescription = "收起",
              tint = Color.White,
              modifier = Modifier.size(30.dp)
            )
          }
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )
          IconButton(onClick = {
            overlay = if (overlay == PlayerOverlay.Menu) {
              PlayerOverlay.None
            } else {
              PlayerOverlay.Menu
            }
          }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Color.White)
          }
        }

        if (!state.hasMedia && book == null) {
          Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (vm.loading) {
              CircularProgressIndicator(color = Color.White)
            } else {
              Text("没有正在播放的内容", color = Color.White.copy(alpha = 0.7f))
            }
          }
          return@Column
        }

        // Opening a different book: everything below still describes the outgoing one, so it is
        // replaced by a full-page spinner until the new session is in the player. Playing the book
        // already shown is not a switch — its page content stays and only the play button waits.
        if (switchingBook) {
          Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
          }
          return@Column
        }

        // Landscape splits the page in two — cover / description on the left, everything that
        // controls playback on the right — so neither half has to squeeze vertically. Portrait
        // keeps the single stacked column.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
          if (maxWidth > maxHeight) {
            Row(
              modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              PlayerPane(
                tab = tab,
                onSelectTab = { tab = it },
                coverUrl = coverUrl,
                hasCover = book?.hasCover,
                description = description,
                modifier = Modifier.weight(1f).fillMaxHeight()
              )
              Spacer(Modifier.width(28.dp))
              Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center
              ) {
                PlayerProgressBlock(
                  vm = vm,
                  chapters = chapters,
                  bookDurationMs = bookDuration,
                  hasMedia = state.hasMedia,
                  onOpenSkipSettings = { overlay = PlayerOverlay.Skip },
                  onOpenChapters = { overlay = PlayerOverlay.Chapter }
                )
                Spacer(Modifier.height(18.dp))
                PlayerControls(
                  vm = vm,
                  chapters = chapters,
                  bookDurationMs = bookDuration,
                  hasMedia = state.hasMedia,
                  preparing = preparingPlayback,
                  speed = vm.playbackSpeed,
                  isPlaying = state.isPlayingOrStarting,
                  sleepTimerEndsAt = vm.sleepTimerEndsAt,
                  sleepTimerMinutes = vm.sleepTimerMinutes,
                  onSpeed = { overlay = PlayerOverlay.Speed },
                  onChapters = { overlay = PlayerOverlay.Chapter }
                )
              }
            }
          } else {
            Column(Modifier.fillMaxSize()) {
            PlayerPane(
              tab = tab,
              onSelectTab = { tab = it },
              coverUrl = coverUrl,
              hasCover = book?.hasCover,
              description = description,
              modifier = Modifier.weight(1f)
            )
              PlayerProgressBlock(
                vm = vm,
                chapters = chapters,
                bookDurationMs = bookDuration,
                hasMedia = state.hasMedia,
                onOpenSkipSettings = { overlay = PlayerOverlay.Skip },
                onOpenChapters = { overlay = PlayerOverlay.Chapter }
              )
              // The controls stay anchored to the bottom, so this gap is what lifts the progress
              // row off them
              Spacer(Modifier.height(20.dp))
              PlayerControls(
                vm = vm,
                chapters = chapters,
                bookDurationMs = bookDuration,
                hasMedia = state.hasMedia,
                preparing = preparingPlayback,
                speed = vm.playbackSpeed,
                isPlaying = state.isPlayingOrStarting,
                sleepTimerEndsAt = vm.sleepTimerEndsAt,
                sleepTimerMinutes = vm.sleepTimerMinutes,
                onSpeed = { overlay = PlayerOverlay.Speed },
                onChapters = { overlay = PlayerOverlay.Chapter }
              )
              // Lift the progress + control block a little off the navigation bar
              Spacer(Modifier.height(30.dp))
            }
          }
        }
      }
    }

      // Overflow menu in the page's own view tree, using the same glass material as the sheets.
      // It starts below the ⋮ button so it never overlaps the button that opens it.
      //
      // A plain conditional, not AnimatedVisibility: the frame clock stops while the app is in the
      // background, so an exit animation started by ON_STOP would freeze and only finish once the
      // app is visible again, making the menu look like it is dismissed on return.
      if (overlay == PlayerOverlay.Menu) {
        Box(Modifier.fillMaxSize()) {
          Box(
            Modifier
              .fillMaxSize()
              .background(Color.Black.copy(alpha = 0.22f))
              .dismissOnScrimTap { overlay = PlayerOverlay.None }
              .semantics { onClick { overlay = PlayerOverlay.None; true } }
          )
          Column(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .statusBarsPadding()
              // 64dp leaves 12dp of air under the 52dp-tall ⋮ hit area
              .padding(top = 64.dp, end = 12.dp)
              .width(190.dp)
              .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(18.dp) },
                effects = {
                  vibrancy()
                  blur(10.dp.toPx())
                  lens(10.dp.toPx(), 24.dp.toPx())
                },
                onDrawSurface = {
                  drawRect(Color.Black.copy(alpha = 0.30f))
                  drawRect(Color.White.copy(alpha = 0.06f))
                }
              )
              .clip(RoundedCornerShape(18.dp))
              .padding(vertical = 6.dp)
              .consumeOverlayTaps()
          ) {
            GlassMenuRow(Icons.Default.KeyboardDoubleArrowRight, "片头片尾设置") {
              overlay = PlayerOverlay.Skip
            }
            // Sleep timer moved here from the bottom bar; the active countdown shows on the row
            GlassMenuRow(
              icon = Icons.Default.Timer,
              label = "定时",
              tint = if (vm.sleepTimerEndsAt > 0L) {
                MaterialTheme.colorScheme.primary
              } else {
                Color.White
              },
              trailing = if (vm.sleepTimerEndsAt > 0L) {
                { SleepMenuTrailing(vm.sleepTimerEndsAt, MaterialTheme.colorScheme.primary) }
              } else null
            ) {
              overlay = PlayerOverlay.Sleep
            }
          }
        }
      }

        GlassSheet(
          backdrop = backdrop,
          visible = overlay == PlayerOverlay.Speed,
          onDismiss = { overlay = PlayerOverlay.None }
        ) {
          SheetHeader("播放速度") { overlay = PlayerOverlay.None }
          SheetCard {
            Text(
              text = speedLabel(vm.playbackSpeed),
              color = Color.White,
              fontSize = 40.sp,
              fontWeight = FontWeight.Medium,
              modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(20.dp))
            SpeedRuler(current = vm.playbackSpeed) { vm.setSpeed(it) }
            Spacer(Modifier.height(18.dp))
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
              horizontalArrangement = Arrangement.SpaceEvenly
            ) {
              SpeedPresets.forEach { preset ->
                val selected = kotlin.math.abs(vm.playbackSpeed - preset) < 0.05f
                GlassPresetCircle(
                  label = speedLabel(preset).removeSuffix("x"),
                  selected = selected,
                  onClick = { vm.setSpeed(preset) },
                  accent = SpeedAccent
                )
              }
            }
          }
          Spacer(Modifier.height(14.dp))
          SheetHint("每本书单独记忆，下次打开这本书仍使用该速度")
        }

        GlassSheet(backdrop = backdrop, visible = overlay == PlayerOverlay.Sleep, onDismiss = { overlay = PlayerOverlay.None }) {
          SheetHeader(
            title = "定时",
            // Reads under the title only while a timer runs; the line keeps its height otherwise so
            // setting one from inside this panel does not shift the card down.
            subtitle = { SleepSheetCountdown(vm.sleepTimerEndsAt) },
            onClose = { overlay = PlayerOverlay.None }
          )
          SheetCard {
            Text(
              text = sleepLabel(vm.sleepTimerMinutes),
              color = Color.White,
              fontSize = 40.sp,
              fontWeight = FontWeight.Medium,
              modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(20.dp))
            SleepRuler(current = vm.sleepTimerMinutes) { vm.setSleepTimer(it) }
            Spacer(Modifier.height(18.dp))
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
              horizontalArrangement = Arrangement.SpaceEvenly
            ) {
              SleepPresets.forEach { minutes ->
                val selected = vm.sleepTimerMinutes == minutes
                GlassPresetCircle(
                  label = if (minutes == 0) "关闭" else "$minutes",
                  selected = selected,
                  onClick = { vm.setSleepTimer(minutes) },
                  accent = SpeedAccent
                )
              }
            }
          }
          Spacer(Modifier.height(14.dp))
          SheetHint("倒计时结束后自动暂停播放")
        }

        // Chapter list, now reached from the bottom-right button instead of a tab. It sits in the
        // same white glass card as the speed/sleep panels, and the list is capped in height so a
        // long book scrolls inside the card rather than growing it.
        GlassSheet(backdrop = backdrop, visible = overlay == PlayerOverlay.Chapter, onDismiss = { overlay = PlayerOverlay.None }) {
          SheetHeader(if (chapters.isEmpty()) "章节" else "章节 · ${chapters.size}") {
            overlay = PlayerOverlay.None
          }
          SheetCard(verticalPadding = 8.dp) {
            ChapterList(
              chapters = chapters,
              currentIndex = sheetChapterIndex,
              onSelect = { chapter ->
                vm.playChapter(chapter)
                overlay = PlayerOverlay.None
              },
              modifier = Modifier.heightIn(max = 380.dp)
            )
          }
          Spacer(Modifier.height(6.dp))
        }

        SkipIntroOutroSheet(
          backdrop = backdrop,
          visible = overlay == PlayerOverlay.Skip,
          intro = vm.skipIntroSeconds,
          outro = vm.skipOutroSeconds,
          onDismiss = { overlay = PlayerOverlay.None },
          onSave = { intro, outro ->
            vm.saveSkipIntroOutro(intro, outro)
            overlay = PlayerOverlay.None
          }
        )
  }
}

/**
 * Cover / description pane: the segmented tabs plus the pages behind them. The cover is sized from
 * whichever side is tighter, so the same code fills a portrait column and the narrower landscape
 * pane without clipping.
 *
 * The pages sit in a horizontal pager, so the two views can also be switched by swiping instead of
 * only by tapping a tab. Tabs and pager share one selection: a tap animates the pager, a settled
 * swipe moves the selection, and neither direction can loop back into the other.
 */
@Composable
private fun PlayerPane(
  tab: Int,
  onSelectTab: (Int) -> Unit,
  coverUrl: String,
  description: String,
  modifier: Modifier = Modifier,
  hasCover: Boolean? = null
) {
  val pagerState = rememberPagerState(initialPage = tab) { Tabs.size }

  LaunchedEffect(tab) {
    if (pagerState.currentPage != tab) pagerState.animateScrollToPage(tab)
  }
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.currentPage }.collect { page -> onSelectTab(page) }
  }

  Column(modifier.fillMaxWidth()) {
    PlayerTabs(selected = tab, onSelect = onSelectTab)
    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxWidth().weight(1f)
    ) { page ->
      when (page) {
        0 -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          val side = minOf(maxWidth * 0.78f, maxHeight * 0.94f)
          BookCover(
            url = coverUrl,
            hasCover = hasCover,
            modifier = Modifier.size(side),
            shape = RoundedCornerShape(16.dp)
          )
        }
        else -> DescriptionTab(description)
      }
    }
  }
}

/**
 * Seek bar with the ±N second buttons and the time row underneath.
 *
 * The scrub position lives here rather than in the page: while dragging, the thumb and the labels
 * follow the finger, while the page keeps tracking real playback — scrubbing towards the end can no
 * longer trip the outro skip, which used to fire on the dragged position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerProgressBlock(
  vm: AbsViewModel,
  chapters: List<ChapterDto>,
  bookDurationMs: Long,
  hasMedia: Boolean,
  onOpenSkipSettings: () -> Unit,
  onOpenChapters: () -> Unit,
  modifier: Modifier = Modifier
) {
  // The playhead is collected here, in the only composable that draws it: keeping it in the page
  // scope is what made a 2 Hz position tick re-run the whole player page.
  val playbackPosition by vm.player.position.collectAsStateWithLifecycle()
  val position = (if (hasMedia) playbackPosition.absolutePositionMs else vm.resumePositionMs)
    .coerceIn(0L, bookDurationMs.coerceAtLeast(0L))

  // The progress track follows the chapter being played, not the whole book
  val chapter = vm.chapterAt(position)
  val chapterIndex = chapter?.let { chapters.indexOf(it) } ?: -1
  val chapterStartMs = chapter?.let { (it.start * 1000).toLong() } ?: 0L
  val chapterEndMs = chapter?.let { (it.end * 1000).toLong() } ?: bookDurationMs
  val chapterDurationMs = (chapterEndMs - chapterStartMs).coerceAtLeast(1L)
  val chapterPositionMs = (position - chapterStartMs).coerceIn(0L, chapterDurationMs)
  val chapterTitle = chapter?.title?.takeIf { it.isNotBlank() } ?: "第 ${chapterIndex + 1} 章"

  val skipSeconds = vm.skipSeconds
  val skipIntroSeconds = vm.skipIntroSeconds
  val skipOutroSeconds = vm.skipOutroSeconds
  val seekTo: (Long) -> Unit = { vm.player.seekToAbsolute(it) }
  val seekBy: (Long) -> Unit = { vm.player.seekBy(it) }

  var dragMs by remember { mutableStateOf<Long?>(null) }
  val shownMs = (dragMs ?: chapterPositionMs).coerceIn(0L, chapterDurationMs)

  Column(modifier.fillMaxWidth()) {
    // Progress with the ±N s buttons on the sides; the intro/outro skip badges sit on the line
    // above the track, one over each end of it
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      SeekControl(forward = false, seconds = skipSeconds) { seekBy(-skipSeconds * 1000L) }
      Column(modifier = Modifier.weight(1f)) {
        // Height reserved even at 0s, so switching intro/outro skipping on does not grow the block
        // and shift the page while the user is still in the settings sheet
        Row(
          modifier = Modifier
            .fillMaxWidth()
            // The slider insets its track by half the thumb on each side; matching that keeps the
            // badges sitting exactly over the ends of the bar instead of floating past them
            .padding(horizontal = SliderThumbSize / 2)
            .height(SkipBadgeRowHeight)
            // The slider is lifted by half this strip, so its touch area overlaps the badges.
            // Keeping them on top preserves their own taps.
            .zIndex(1f),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Tapping a badge opens the skip settings: it is the natural place to change a value the
          // user is already looking at.
          Box(
            Modifier
              .weight(1f)
              .clickable(enabled = skipIntroSeconds > 0) { onOpenSkipSettings() },
            contentAlignment = Alignment.CenterStart
          ) {
            if (skipIntroSeconds > 0) {
              SkipBadge(seconds = skipIntroSeconds, alignEnd = false)
            }
          }
          Box(
            Modifier
              .weight(1f)
              .clickable(enabled = skipOutroSeconds > 0) { onOpenSkipSettings() },
            contentAlignment = Alignment.CenterEnd
          ) {
            if (skipOutroSeconds > 0) {
              SkipBadge(seconds = skipOutroSeconds, alignEnd = true)
            }
          }
        }
        Slider(
          value = shownMs.toFloat(),
          onValueChange = { dragMs = it.toLong() },
          onValueChangeFinished = {
            dragMs?.let { seekTo(chapterStartMs + it) }
            dragMs = null
          },
          valueRange = 0f..chapterDurationMs.toFloat(),
          thumb = {
            Box(Modifier.size(SliderThumbSize).background(Color.White, CircleShape))
          },
          track = { sliderState ->
            val span = sliderState.valueRange.endInclusive - sliderState.valueRange.start
            val fraction = if (span > 0f) {
              ((sliderState.value - sliderState.valueRange.start) / span).coerceIn(0f, 1f)
            } else {
              0f
            }
            Box(
              Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f))
            ) {
              Box(
                Modifier
                  .fillMaxWidth(fraction)
                  .fillMaxHeight()
                  .clip(CircleShape)
                  .background(Color.White)
              )
            }
          },
          // Lifted by half the badge strip so the track sits on the same horizontal centre line as
          // the ±N s buttons beside it. The badges themselves stay where they are.
          modifier = Modifier
            .fillMaxWidth()
            .offset(y = -(SkipBadgeRowHeight / 2))
        )
      }
      SeekControl(forward = true, seconds = skipSeconds) { seekBy(skipSeconds * 1000L) }
    }

    // Time on the left, remaining on the right.
    //
    // Both time slots have a fixed width, measured once from the longest reading they can ever
    // hold. A ticking second therefore never re-lays out the row: the digits stay put and the
    // chapter title keeps exactly the same space, so it neither shifts sideways nor loses its
    // optical centre.
    val timeStyle = MaterialTheme.typography.labelSmall
    val slotMeasurer = rememberTextMeasurer()
    val slotDensity = LocalDensity.current
    // "99:59:59" is the widest reading the formatter can produce; the minus prefix of the
    // remaining time is measured too so the right column never clips it
    val timeSlotWidth = with(slotDensity) {
      slotMeasurer.measure("-99:59:59", timeStyle).size.width.toDp() + 1.dp
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Played time — fixed slot, flush left so the digits read as a column
      Box(Modifier.width(timeSlotWidth), contentAlignment = Alignment.CenterStart) {
        Text(
          text = formatTime(shownMs),
          style = timeStyle,
          color = Color.White.copy(alpha = 0.6f),
          maxLines = 1,
          softWrap = false,
          // Unbounded so a rare wider reading spills instead of being clipped by the slot;
          // the reported width stays fixed either way
          modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
        )
      }
      // Tapping the chapter name opens the chapter list — the same action as the bottom-right
      // button, kept here because this is where the user reads which chapter is playing.
      Text(
        text = chapterTitle,
        style = timeStyle,
        fontSize = 13.sp,
        color = Color.White.copy(alpha = 0.75f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = 8.dp)
          .clickable(onClick = onOpenChapters)
      )
      // Remaining time — identical slot width, flush right so the two ends mirror each other
      Box(Modifier.width(timeSlotWidth), contentAlignment = Alignment.CenterEnd) {
        Text(
          text = "-${formatTime((chapterDurationMs - shownMs).coerceAtLeast(0L))}",
          style = timeStyle,
          color = Color.White.copy(alpha = 0.6f),
          maxLines = 1,
          softWrap = false,
          modifier = Modifier.wrapContentWidth(Alignment.End, unbounded = true)
        )
      }
    }
  }
}

/** Icon size and leading gap of the intro/outro skip badge. */
private val SkipBadgeIconSize = 12.dp
private val SkipBadgeIconGap = 1.dp

/** Diameter of the seek bar thumb; the badge row above the track is inset by half of it. */
private val SliderThumbSize = 14.dp

/**
 * Height of the strip above the seek bar that holds the skip badges.
 *
 * Reserved whether or not a badge is shown: switching intro/outro skipping on must not grow the
 * block, otherwise everything above it shifts while the user is in the settings sheet.
 */
private val SkipBadgeRowHeight = 14.dp

/**
 * "»30s" marker for an active intro/outro skip.
 *
 * The double chevron replaces the minus sign the badge used to carry: a hyphen read as a negative
 * offset, while the arrows say "this much is skipped" in the same glyph the skip menu uses.
 */
@Composable
private fun SkipBadge(seconds: Int, alignEnd: Boolean) {
  Row(
    // Unbounded like the plain text badge was, so a wider reading spills instead of being clipped
    // by the fixed column; the reported width stays fixed either way
    modifier = Modifier.wrapContentWidth(
      if (alignEnd) Alignment.End else Alignment.Start,
      unbounded = true
    ),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = Icons.Default.KeyboardDoubleArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(SkipBadgeIconSize)
    )
    Spacer(Modifier.width(SkipBadgeIconGap))
    Text(
      text = "${seconds}s",
      // Compact line height: the default body line box would be twice the height of the reserved
      // strip and get clipped by it
      style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
      color = MaterialTheme.colorScheme.primary,
      maxLines = 1,
      softWrap = false
    )
  }
}

/** Speed, chapter step, play/pause and the chapter list, in one row. */
@Composable
private fun PlayerControls(
  vm: AbsViewModel,
  chapters: List<ChapterDto>,
  bookDurationMs: Long,
  hasMedia: Boolean,
  preparing: Boolean,
  speed: Float,
  isPlaying: Boolean,
  sleepTimerEndsAt: Long,
  sleepTimerMinutes: Int,
  onSpeed: () -> Unit,
  onChapters: () -> Unit,
  modifier: Modifier = Modifier
) {
  // Which chapter the arrows step to follows the playhead, so it is read here as well instead of
  // being hoisted into the page (that would drag the whole page along at 2 Hz).
  val playbackPosition by vm.player.position.collectAsStateWithLifecycle()
  val position = (if (hasMedia) playbackPosition.absolutePositionMs else vm.resumePositionMs)
    .coerceIn(0L, bookDurationMs.coerceAtLeast(0L))
  val chapterIndex = PlayerConnection.chapterIndexAt(chapters, position)
  val previousChapter = chapters.getOrNull(chapterIndex - 1)
  val nextChapter = chapters.getOrNull(chapterIndex + 1)
  // Stepping a chapter is a "start listening here" action: it starts playback even when paused,
  // and creates the session from that chapter when a cold start has only staged the book.
  val onPrevious: () -> Unit = {
    previousChapter?.let { vm.playChapter(it) }
  }
  val onNext: () -> Unit = {
    nextChapter?.let { vm.playChapter(it) }
  }
  val onTogglePlay: () -> Unit = { vm.togglePlayPause() }

  // The ring is the only place the countdown is visible without opening a sheet
  val sleepRemaining = rememberSleepTimerRemaining(sleepTimerEndsAt)
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    IconButton(onClick = onSpeed) {
      // Icon frame + live value in the middle, so the number follows the speed.
      // 26dp puts the drawn brackets at ~20dp, a touch above the ~16dp glyphs of the 32dp
      // material icons on either side: the control carries a number, so it is allowed to read
      // slightly heavier without dominating the row.
      Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        Icon(
          painter = painterResource(R.drawable.ic_speed_frame),
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.fillMaxSize()
        )
        Text(
          text = speedLabel(speed),
          color = Color.White,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.offset(y = 1.dp)
        )
      }
    }
    IconButton(onClick = onPrevious, enabled = previousChapter != null) {
      Icon(
        Icons.Default.SkipPrevious,
        contentDescription = "上一章",
        modifier = Modifier.size(32.dp),
        tint = if (previousChapter != null) Color.White else Color.White.copy(alpha = 0.3f)
      )
    }
    // The pressed layer is a 90dp round state layer around the 74dp circle, so a visible 8dp
    // halo shows outside the fill. The 74dp outer box keeps the row layout unchanged; the inner
    // box has to use requiredSize, not size — size() clamps to the parent's 74dp max constraint
    // and the ripple silently collapses back to the circle's diameter. requiredSize lets the
    // child overflow (Box does not clip children) while the outer box still measures 74dp.
    // A bounded ripple with an explicit radius stays centred on the button instead of following
    // the touch point, which is what makes it read like the other controls' state layers.
    Box(
      modifier = Modifier.size(74.dp),
      contentAlignment = Alignment.Center
    ) {
      Box(
        modifier = Modifier
          .requiredSize(90.dp)
          .clip(CircleShape)
          .sleepTimerStateDescription(sleepRemaining)
          // A second tap while the session is being created would cancel the request in flight and
          // start over, making the wait longer.
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = true, radius = 45.dp),
            enabled = !preparing,
            onClick = onTogglePlay
          ),
        contentAlignment = Alignment.Center
      ) {
        Box(
          modifier = Modifier
            .size(74.dp)
            .background(Color.White.copy(alpha = 0.16f), CircleShape)
            // Drawn on the rim of the existing circle, so switching the timer on does not resize
            // the button or shift the row: only the ring appears.
            .sleepTimerRing(
              remainingMs = sleepRemaining,
              totalMinutes = sleepTimerMinutes,
              strokeWidth = 3.dp,
              inset = 1.dp
            ),
          contentAlignment = Alignment.Center
        ) {
          if (preparing) {
            CircularProgressIndicator(
              modifier = Modifier.size(36.dp).semantics { contentDescription = "正在准备播放" },
              strokeWidth = 3.dp,
              color = Color.White
            )
          } else {
            Icon(
              imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
              contentDescription = if (isPlaying) "暂停" else "播放",
              modifier = Modifier.size(40.dp),
              tint = Color.White
            )
          }
        }
      }
    }
    IconButton(onClick = onNext, enabled = nextChapter != null) {
      Icon(
        Icons.Default.SkipNext,
        contentDescription = "下一章",
        modifier = Modifier.size(32.dp),
        tint = if (nextChapter != null) Color.White else Color.White.copy(alpha = 0.3f)
      )
    }
    IconButton(onClick = onChapters) {
      ChapterListIcon(color = Color.White)
    }
  }
}

/** Pill segmented control: 封面 / 简介. */
@Composable
private fun PlayerTabs(selected: Int, onSelect: (Int) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Tabs.forEachIndexed { index, label ->
      val active = index == selected
      Box(
        modifier = Modifier
          .clip(CircleShape)
          .background(if (active) Color.White.copy(alpha = 0.18f) else Color.Transparent)
          .clickable { onSelect(index) }
          .padding(horizontal = 18.dp, vertical = 7.dp)
      ) {
        Text(
          text = label,
          style = MaterialTheme.typography.bodyMedium,
          color = if (active) Color.White else Color.White.copy(alpha = 0.6f)
        )
      }
    }
  }
}

@Composable
private fun DescriptionTab(description: String) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 24.dp, vertical = 8.dp)
  ) {
    Text(
      text = description.ifBlank { "这本书没有简介" },
      style = MaterialTheme.typography.bodyMedium,
      color = Color.White.copy(alpha = if (description.isBlank()) 0.5f else 0.82f),
      lineHeight = 22.sp
    )
  }
}

/**
 * Three bars for the chapter-list button, drawn by hand instead of using the Material "toc" glyph.
 *
 * At 24dp the stock icon packs its lines only 4dp apart (2dp line + 2dp gap), which reads as one
 * solid block next to the heavier round icons. These bars sit 6.5dp apart with rounded ends, so the
 * three lines stay legible at icon size. Centres are 5.5 / 12 / 18.5 in a 24 unit box, scaled to
 * whatever size the caller gives.
 */
@Composable
private fun ChapterListIcon(
  color: Color = Color.White,
  // 22dp puts the drawn bars at ~16.5dp, matching the ~16dp glyphs of the chapter arrows beside
  // it. Measured from a screenshot: 24dp read 90px against their 60px.
  iconSize: Dp = 22.dp,
  modifier: Modifier = Modifier
) {
  Canvas(
    modifier
      .size(iconSize)
      .semantics { contentDescription = "章节列表" }
  ) {
    val unit = size.height / 24f
    val barHeight = 2.6f * unit
    val inset = 3f * unit
    val corner = CornerRadius(barHeight / 2f, barHeight / 2f)
    // Three bars spanning 4..20 of the 24 unit design grid instead of 5..19: at a 32dp canvas that
    // makes the drawn glyph ~24x24dp, matching the height of the material icons beside it. The
    // previous spread left it visibly shorter than its neighbours.
    listOf(4f, 12f, 20f).forEach { centerY ->
      drawRoundRect(
        color = color,
        topLeft = Offset(inset, centerY * unit - barHeight / 2f),
        size = Size(size.width - inset * 2f, barHeight),
        cornerRadius = corner
      )
    }
  }
}

@Composable
private fun ChapterList(
  chapters: List<ChapterDto>,
  currentIndex: Int,
  onSelect: (ChapterDto) -> Unit,
  modifier: Modifier = Modifier
) {
  if (chapters.isEmpty()) {
    Box(modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
      Text("这本书没有章节信息", color = Color.White.copy(alpha = 0.6f))
    }
    return
  }
  val listState = rememberLazyListState()
  // The panel is composed fresh every time it is opened, so this jumps to the current chapter once
  LaunchedEffect(Unit) {
    if (currentIndex >= 0) {
      listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
    }
  }
  LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
    itemsIndexed(chapters) { index, item ->
      val selected = index == currentIndex
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 1.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
          .clickable { onSelect(item) }
          .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = item.title.ifBlank { "第 ${index + 1} 章" },
          style = MaterialTheme.typography.bodyMedium,
          color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        Text(
          text = formatTime((item.start * 1000).toLong()),
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.55f)
        )
      }
    }
  }
}
