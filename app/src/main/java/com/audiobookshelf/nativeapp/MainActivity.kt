package com.audiobookshelf.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiobookshelf.nativeapp.ui.AbsNav
import com.audiobookshelf.nativeapp.util.AppLog
import com.audiobookshelf.nativeapp.ui.AbsTheme

class MainActivity : ComponentActivity() {
  /**
   * Held only to forward [onStart]: the view model is created by composition, and a network switch
   * that happened while the activity was stopped has to be re-checked as soon as it comes back.
   */
  private var hostViewModel: AbsViewModel? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    // Draw behind the status/navigation bars so the player background reaches the screen edges
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    // No runtime POST_NOTIFICATIONS request. The permission stays declared in the manifest so it
    // can still be toggled in system settings on ROMs that need it, but the app never prompts:
    // playback, background resume and audio focus do not depend on it. Media cards and lock-screen
    // controls are rendered by SystemUI from the MediaSession, not from this permission - verified
    // on MIUI: granted=false still shows both. OEM behaviour varies, hence the declaration stays.
    setContent {
      AbsTheme {
        val vm: AbsViewModel = viewModel()
        hostViewModel = vm
        AbsNav(vm)
      }
    }
  }

  override fun onStart() {
    super.onStart()
    AppLog.tapTrace("ACTIVITY_START", "")
    hostViewModel?.onAppForeground()
  }

  override fun onResume() {
    super.onResume()
    AppLog.tapTrace("ACTIVITY_RESUME", "")
  }

  override fun onPause() {
    AppLog.tapTrace("ACTIVITY_PAUSE", "")
    super.onPause()
  }

  override fun onStop() {
    AppLog.tapTrace("ACTIVITY_STOP", "")
    super.onStop()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    AppLog.tapTrace("WINDOW_FOCUS", "hasFocus=$hasFocus")
  }
}
