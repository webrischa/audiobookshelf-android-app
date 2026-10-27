package com.audiobookshelf.nativeapp.ui

import com.audiobookshelf.nativeapp.util.AppLog

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.audiobookshelf.nativeapp.AbsViewModel
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.io.File
import kotlinx.coroutines.delay

object Routes {
  const val LOGIN = "login"
  const val LIBRARIES = "libraries"

  /**
   * Home of a library: shelves (continue listening, discover, ...) with the full bookshelf grid
   * one level deeper. No path argument is used for the start destination because Navigation
   * Compose does not populate entry arguments for it; the library id comes from the view model.
   */
  const val HOME = "home"
  const val BOOKSHELF = "bookshelf/{libraryId}"
  const val ITEM = "item/{itemId}"
  const val SERIES = "series"
  const val SEARCH = "search/{libraryId}"
  const val PLAYER = "player"
  const val SETTINGS = "settings"

  fun bookshelf(libraryId: String) = "bookshelf/$libraryId"
  fun item(itemId: String) = "item/$itemId"
  fun search(libraryId: String) = "search/$libraryId"
}

/** Background image shared with screens that draw their own full-screen layout. */
val LocalGlobalBackground = compositionLocalOf<Any?> { null }

/**
 * Full-bleed blurred background used by the app shell and the player page.
 *
 * The image is pre-blurred on a background thread (see [BackgroundBlur]) instead of being drawn
 * with `Modifier.blur`, which is a no-op below API 31 and costs a full-screen offscreen pass above
 * it. Nothing is drawn until the blurred copy is ready; the scrim underneath keeps that moment
 * from flashing.
 */
@Composable
fun GlobalBackground(model: Any?) {
  val source = model as? String
  if (source.isNullOrBlank()) return
  val context = LocalContext.current
  // Checked synchronously so an already-prepared background is there on the very first frame
  val cached = remember(source) { BackgroundBlur.cachedPath(context, source) }
  val blurred by produceState(initialValue = cached, source) {
    // Unconditional on purpose: produceState keeps the previous source's value across a key
    // change (only the producer restarts), so the old `if (value == null)` guard read the stale
    // path of the outgoing image, skipped the work, and a freshly picked background only showed
    // up after an app restart. prepare() checks the disk cache first, so for a source that is
    // already prepared this is one stat; while a new one decodes, the old image stays on screen.
    value = BackgroundBlur.prepare(context, source) ?: value
  }
  blurred?.let { path ->
    AsyncImage(
      model = File(path),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize()
    )
  }
  Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
}

/** Height the glass bar occupies, used by screens to reserve space at the end of their lists. */
private val MiniPlayerInset = 88.dp

@Composable
fun AbsNav(vm: AbsViewModel) {
  if (vm.initializing) {
    // The splash must carry the global background too, or it flashes in late
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      GlobalBackground(vm.backgroundImagePath?.takeIf { it.isNotBlank() })
      CircularProgressIndicator()
    }
    return
  }

  val navController = rememberNavController()
  // Jump straight into the remembered library home: the picker is never composed in that case
  val startDestination = when {
    vm.session == null -> Routes.LOGIN
    !vm.lastLibraryId.isNullOrBlank() -> Routes.HOME
    else -> Routes.LIBRARIES
  }

  val playback by vm.player.state.collectAsStateWithLifecycle()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route

  // startDestination is computed once, so a session that is cleared later by a 401 would leave
  // the listener on a home screen whose every request fails. Route back to the credentials as
  // soon as the view model mirrors the repository's logout. currentRoute == null means the
  // NavHost has not produced its first entry yet - do not fight it while it is being created.
  LaunchedEffect(vm.session) {
    if (vm.session == null && currentRoute != null && currentRoute != Routes.LOGIN) {
      navController.navigate(Routes.LOGIN) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
      }
    }
  }

  // The full player screen has its own controls, the login screen has no session.
  // `playingBook` covers the staged case: on a cold start the last unfinished book is published
  // before any playback session exists (see AbsViewModel.resumeLastBookIfAny), so the bar has to
  // show up for it too — that bar is the entry point that starts playback.
  val showMiniPlayer = vm.session != null &&
    (playback.hasMedia || vm.playingBook != null) &&
    currentRoute != Routes.PLAYER && currentRoute != Routes.LOGIN

  // Everything behind the glass bar is recorded here; the bar samples it to draw the blur/lens.
  val backgroundColor = MaterialTheme.colorScheme.background
  val onDraw: ContentDrawScope.() -> Unit = remember(backgroundColor) {
    {
      drawRect(backgroundColor)
      drawContent()
    }
  }
  val backdrop = rememberLayerBackdrop(onDraw = onDraw)

  // Global background: only a user picked image, never the playing cover
  val backgroundModel: Any? = vm.backgroundImagePath?.takeIf { it.isNotBlank() }

  // Hoisted above NavHost: navigation only composes the top destination, so HOME's horizontal
  // shelf rows are disposed on a detail/player round trip. The bookshelf session states live in
  // BookshelfScreen itself, keyed by its NavBackStackEntry: returning keeps the offset,
  // re-entering from home is a new session that starts at the top.
  // The map is a plain holder, never snapshot state; HomeScreen only sees a lookup lambda, so
  // it cannot mutate the map during composition. A handful of row states is kept for the
  // lifetime of the nav graph rather than evicted on every library switch.
  val homeRowStates = remember { mutableMapOf<String, LazyListState>() }
  val rowStateFor: (String) -> LazyListState = remember {
    { key -> homeRowStates.getOrPut(key) { LazyListState() } }
  }

  Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(
      LocalMiniPlayerBottomInset provides if (showMiniPlayer) MiniPlayerInset else 0.dp,
      LocalGlobalBackground provides backgroundModel
    ) {
      // The recording layer is only attached while something actually samples it: layerBackdrop
      // records its content into an offscreen graphics layer on every frame, and the mini bar is
      // the only thing in this shell that reads it — keeping it mounted permanently paid that cost
      // for every screen, all the time.
      Box(
        Modifier
          .fillMaxSize()
          .then(if (showMiniPlayer) Modifier.layerBackdrop(backdrop) else Modifier)
      ) {
        GlobalBackground(LocalGlobalBackground.current)

        // Keep a symmetric crossfade: removing the outgoing half made the old page vanish on the
        // same frame and the incoming page fade in over the bare background, which reads as a
        // flicker. Both halves use a short 220 ms tween. The click fix lives in ItemScreen's
        // lifecycle guard, not in the transition.
        NavHost(
          navController = navController,
          startDestination = startDestination,
          enterTransition = { fadeIn(animationSpec = tween(220)) },
          exitTransition = { fadeOut(animationSpec = tween(220)) },
          popEnterTransition = { fadeIn(animationSpec = tween(220)) },
          popExitTransition = { fadeOut(animationSpec = tween(220)) }
        ) {
          composable(Routes.LOGIN) {
            LoginScreen(vm) {
              val target = if (vm.lastLibraryId != null) Routes.HOME else Routes.LIBRARIES
              navController.navigate(target) {
                popUpTo(Routes.LOGIN) { inclusive = true }
              }
            }
          }

          composable(Routes.LIBRARIES) {
            // Frozen on first composition. Recomputing on every back stack change made the arrow
            // disappear the moment popBackStack ran, i.e. before the screen actually left, which
            // read as "the arrow vanishes, then the page switches".
            val canGoBack = remember {
              val previous = navController.previousBackStackEntry?.destination?.route
              previous != null && previous != Routes.LOGIN
            }
            // Back with nowhere to pop backgrounds the task instead of finishing the activity
            BackgroundOnBack(enabled = !canGoBack)
            LibrariesScreen(
              vm = vm,
              onOpenLibrary = { library ->
                vm.rememberLibrary(library.id)
                // Switching libraries replaces the previous library home instead of stacking a
                // second one, so back does not land on a home showing the same library again
                navController.navigate(Routes.HOME) {
                  popUpTo(Routes.HOME) { inclusive = true }
                  launchSingleTop = true
                }
              },
              onBack = if (canGoBack) {
                { navController.popBackStack() }
              } else {
                null
              },
              onSettings = { navController.navigate(Routes.SETTINGS) }
            )
          }

          composable(Routes.HOME) {
            BackgroundOnBack()
            val libraryId = vm.lastLibraryId.orEmpty()
            HomeScreen(
              vm = vm,
              libraryId = libraryId,
              rowStateFor = rowStateFor,
              onOpenItem = { itemId ->
                AppLog.tapTrace("NAV_ITEM", itemId)
                navController.navigate(Routes.item(itemId))
              },
              onOpenBookshelf = { navController.navigate(Routes.bookshelf(libraryId)) },
              onOpenLibraries = { navController.navigate(Routes.LIBRARIES) },
              onSearch = { navController.navigate(Routes.search(libraryId)) },
              onSettings = { navController.navigate(Routes.SETTINGS) }
            )
          }

          composable(Routes.BOOKSHELF) { entry ->
            val libraryId = entry.arguments?.getString("libraryId").orEmpty()
            BookshelfScreen(
              vm = vm,
              libraryId = libraryId,
              entryId = entry.id,
              onOpenItem = { itemId ->
                AppLog.tapTrace("NAV_ITEM", itemId)
                navController.navigate(Routes.item(itemId))
              },
              onOpenSeries = { series ->
                vm.openSeries(series)
                navController.navigate(Routes.SERIES) { launchSingleTop = true }
              },
              onBack = { navController.popBackStack() },
              onSearch = { navController.navigate(Routes.search(libraryId)) },
              onSettings = { navController.navigate(Routes.SETTINGS) }
            )
          }

          composable(Routes.SERIES) {
            SeriesScreen(
              vm = vm,
              onOpenItem = { itemId ->
                AppLog.tapTrace("NAV_ITEM", itemId)
                navController.navigate(Routes.item(itemId))
              },
              onBack = {
                vm.closeSeries()
                navController.popBackStack()
              }
            )
          }

          composable(Routes.ITEM) { entry ->
            val itemId = entry.arguments?.getString("itemId").orEmpty()
            ItemScreen(
              vm = vm,
              itemId = itemId,
              onBack = { navController.popBackStack() },
              // Same entry as the mini bar: single top, so tapping play twice cannot stack players
              onOpenPlayer = {
                AppLog.tapTrace("NAV_PLAYER", "from=detail")
                navController.navigate(Routes.PLAYER) { launchSingleTop = true }
              }
            )
          }

          composable(Routes.SEARCH) { entry ->
            val libraryId = entry.arguments?.getString("libraryId").orEmpty()
            SearchScreen(
              vm = vm,
              libraryId = libraryId,
              onOpenItem = { itemId ->
                AppLog.tapTrace("NAV_ITEM", itemId)
                navController.navigate(Routes.item(itemId))
              },
              onBack = { navController.popBackStack() }
            )
          }

          composable(
            route = Routes.PLAYER,
            // The whole destination (background included) slides down, revealing the page below
            exitTransition = { slideOutVertically(targetOffsetY = { it }, animationSpec = tween(260)) },
            popExitTransition = { slideOutVertically(targetOffsetY = { it }, animationSpec = tween(260)) }
          ) {
            PlayerScreen(vm = vm, onBack = { navController.popBackStack() })
          }

          composable(Routes.SETTINGS) {
            SettingsScreen(
              vm = vm,
              onBack = { navController.popBackStack() },
              onLoggedOut = {
                navController.navigate(Routes.LOGIN) {
                  popUpTo(0) { inclusive = true }
                }
              }
            )
          }
        }
      }
    }

    if (showMiniPlayer) {
      MiniPlayerHost(
        vm = vm,
        backdrop = backdrop,
        hasMedia = playback.hasMedia,
        onOpen = {
          AppLog.tapTrace("NAV_PLAYER", "from=miniBar")
          navController.navigate(Routes.PLAYER) { launchSingleTop = true }
        },
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
      )
    }
  }
}

/**
 * The floating bar, in its own composable so the playhead is read here and nowhere else.
 *
 * Collecting the position in [AbsNav] re-ran the whole navigation shell twice a second, because
 * the value was part of the snapshot the shell subscribed to. The bar is the only consumer.
 */
@Composable
private fun MiniPlayerHost(
  vm: AbsViewModel,
  backdrop: Backdrop,
  hasMedia: Boolean,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier
) {
  val position by vm.player.position.collectAsStateWithLifecycle()
  val playback by vm.player.state.collectAsStateWithLifecycle()

  // MediaController only emits on events, so poll while the bar is on screen
  LaunchedEffect(Unit) {
    while (true) {
      vm.player.refresh()
      delay(1_000)
    }
  }

  // Mini bar mirrors the player, not the detail page currently on screen
  val book = vm.playingBook
  // A staged book (cold start) has no playhead yet: the stored resume point is what it would start
  // from, so the bar shows that instead of 0:00.
  val absoluteMs = if (hasMedia) position.absolutePositionMs else vm.resumePositionMs
  val chapter = vm.chapterAt(absoluteMs)
  // The card title is the chapter, so the book identity comes from its own fields
  val bookTitle = book?.title?.takeIf { it.isNotBlank() } ?: "正在播放"
  val author = book?.author.orEmpty()
  // First line is the chapter, second line identifies the book
  val title = chapter?.title?.takeIf { it.isNotBlank() } ?: bookTitle
  val subtitle = listOfNotNull(bookTitle, author.takeIf { it.isNotBlank() }).joinToString(" · ")
  val coverUrl = book?.let { vm.coverUrl(it.itemId) }.orEmpty()

  // Progress follows the chapter being played, the same basis the player page uses:
  // a book-wide bar reads as "almost done" while the current episode has just started.
  val chapterStartMs = chapter?.let { (it.start * 1000).toLong() } ?: 0L
  val chapterEndMs = chapter?.let { (it.end * 1000).toLong() }
    ?: book?.durationMs?.takeIf { it > 0 }
    ?: 0L
  val chapterSpanMs = (chapterEndMs - chapterStartMs).coerceAtLeast(1L)
  val chapterProgress = ((absoluteMs - chapterStartMs).toFloat() / chapterSpanMs).coerceIn(0f, 1f)

  MiniPlayerBar(
    modifier = modifier,
    backdrop = backdrop,
    state = playback,
    title = title,
    subtitle = subtitle,
    coverUrl = coverUrl,
    hasCover = book?.hasCover,
    progress = chapterProgress,
    sleepTimerEndsAt = vm.sleepTimerEndsAt,
    sleepTimerMinutes = vm.sleepTimerMinutes,
    // Only for a book that is not in the player yet: while one is already loaded, starting
    // another one keeps the current audio running and the button stays meaningful.
    preparing = vm.pendingPlayback != null && !hasMedia,
    onOpen = onOpen,
    onTogglePlayPause = { vm.togglePlayPause() }
  )
}

/**
 * Back on a root destination sends the whole task to the background instead of finishing the
 * activity.
 *
 * Playback runs in PlaybackService, a foreground service, so it survives either way — but finishing
 * the activity throws away the navigation stack and the view model, and the next launch rebuilds
 * everything from the start destination. That read as "the page reloaded while the audio kept
 * playing".
 *
 * The handler is registered from inside a destination, i.e. after the NavController's own back
 * callback, so it only wins where the nav stack has nothing left to pop.
 */
@Composable
private fun BackgroundOnBack(enabled: Boolean = true) {
  val context = LocalContext.current
  BackHandler(enabled = enabled) {
    val activity = context.findActivity() ?: return@BackHandler
    // moveTaskToBack returns false when the activity is not the task root; finishing is the only
    // way out then, otherwise back would look like it did nothing
    if (!activity.moveTaskToBack(true)) activity.finish()
  }
}

/** Walks the context chain up to the hosting activity. */
private fun Context.findActivity(): Activity? {
  var current: Context? = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return null
}
