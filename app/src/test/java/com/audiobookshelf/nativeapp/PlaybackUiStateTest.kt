package com.audiobookshelf.nativeapp

import androidx.media3.common.Player
import com.audiobookshelf.nativeapp.playback.PlaybackUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The play/pause icon must follow "audio is coming out, or is about to", not the raw
 * `playWhenReady` flag. A dropped stream keeps `playWhenReady=true` for the whole retry window,
 * which used to leave a pause button on screen with nothing playing (2026-11-12).
 */
class PlaybackUiStateTest {

  @Test
  fun `audio flowing shows pause`() {
    assertTrue(
      PlaybackUiState(isPlaying = true, playWhenReady = true, playbackState = Player.STATE_READY)
        .isPlayingOrStarting
    )
  }

  @Test
  fun `buffering still shows pause`() {
    assertTrue(
      PlaybackUiState(isPlaying = false, playWhenReady = true, playbackState = Player.STATE_BUFFERING)
        .isPlayingOrStarting
    )
  }

  @Test
  fun `brief idle during a playlist rebuild still shows pause`() {
    assertTrue(
      PlaybackUiState(isPlaying = false, playWhenReady = true, playbackState = Player.STATE_IDLE)
        .isPlayingOrStarting
    )
  }

  @Test
  fun `user pause shows play`() {
    assertFalse(
      PlaybackUiState(isPlaying = false, playWhenReady = false, playbackState = Player.STATE_READY)
        .isPlayingOrStarting
    )
  }

  @Test
  fun `ended book shows play`() {
    assertFalse(
      PlaybackUiState(isPlaying = false, playWhenReady = true, playbackState = Player.STATE_ENDED)
        .isPlayingOrStarting
    )
  }

  @Test
  fun `stream error shows play even though playWhenReady is still true`() {
    assertFalse(
      PlaybackUiState(
        isPlaying = false,
        playWhenReady = true,
        playbackState = Player.STATE_IDLE,
        hasPlaybackError = true
      ).isPlayingOrStarting
    )
  }
}
