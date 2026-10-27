package com.audiobookshelf.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * How long a page stays recorded after the last glass panel began closing.
 *
 * `layerBackdrop` copies the whole page into an offscreen graphics layer on every frame. Detaching
 * it on the same frame a panel starts its exit animation leaves that panel sampling a layer that
 * no longer exists. 400 ms covers the longest exit animation in use (320 ms) plus a frame or two.
 */
const val BackdropExitGraceMs = 400L

/**
 * Single source of truth for "should this page currently be recorded for a glass panel".
 *
 * The condition itself differs per screen (a speed sheet, a sort menu, a settings dialog), but
 * the behaviour is identical: attach immediately while a reader exists, keep the layer alive for
 * the exit animation, then unmount it so an idle page stops paying for an offscreen copy every
 * frame. This used to be written out three times, and one of the three had no exit grace at all.
 */
@Composable
fun rememberBackdropRecording(active: Boolean): Boolean {
  var recording by remember { mutableStateOf(active) }
  LaunchedEffect(active) {
    if (active) {
      recording = true
    } else {
      delay(BackdropExitGraceMs)
      recording = false
    }
  }
  return recording
}
