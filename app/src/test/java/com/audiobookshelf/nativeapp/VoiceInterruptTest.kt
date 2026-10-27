package com.audiobookshelf.nativeapp

import android.media.AudioAttributes
import com.audiobookshelf.nativeapp.playback.FocusAction
import com.audiobookshelf.nativeapp.playback.FocusEvent
import com.audiobookshelf.nativeapp.playback.InterruptionState
import com.audiobookshelf.nativeapp.playback.ResumeAction
import com.audiobookshelf.nativeapp.playback.TransientSource
import com.audiobookshelf.nativeapp.playback.classifyInterruption
import com.audiobookshelf.nativeapp.playback.classifyTransientSource
import com.audiobookshelf.nativeapp.playback.decideFocusAction
import com.audiobookshelf.nativeapp.playback.decideResumeAction
import com.audiobookshelf.nativeapp.playback.hasPriorityAudioActive
import com.audiobookshelf.nativeapp.playback.mergeTransientObservation
import com.audiobookshelf.nativeapp.playback.shouldInterruptAfterGrace
import com.audiobookshelf.nativeapp.playback.transientGraceWindowMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full regression matrix for the "ignore notification sounds" policy:
 *  - short pings (WeChat / DingTalk / SMS): no duck, no pause, released as soon as
 *    USAGE_NOTIFICATION is observed - not after a blanket 2.5 s timer;
 *  - voice messages and alarms: interrupt as soon as they are identified (faster than the
 *    old fixed 1.5 s timer), then auto-resume on AUDIOFOCUS_GAIN;
 *  - calls, a disabled setting and permanent losses: interrupt immediately.
 *
 * The service keeps player.volume at 1f in every DEFER / IGNORE branch; the only volume ramp is
 * the fade-out inside interruptPlayback(), which a short ping never reaches.
 */
class VoiceInterruptTest {

  private fun action(
    event: FocusEvent,
    settingOn: Boolean = true,
    call: Boolean = false,
    pending: Boolean = false
  ): FocusAction = decideFocusAction(event, settingOn, call, pending)

  // --- short notification pings: neither duck nor pause ---

  @Test
  fun `a WeChat ping only opens the grace window`() {
    assertEquals(FocusAction.DEFER, action(FocusEvent.TRANSIENT))
  }

  @Test
  fun `a DingTalk or SMS event inside the same window does not restart it`() {
    assertEquals(FocusAction.IGNORE, action(FocusEvent.TRANSIENT, pending = true))
  }

  @Test
  fun `a ping returning focus is a no-op unless playback was interrupted`() {
    assertEquals(FocusAction.RESUME_IF_INTERRUPTED, action(FocusEvent.GAIN))
  }

  @Test
  fun `a longer ping still only defers while the setting stays on`() {
    assertEquals(FocusAction.DEFER, action(FocusEvent.TRANSIENT, pending = false))
  }

  // --- voice messages / alarms: interrupt, then resume ---

  @Test
  fun `an alarm taking permanent focus interrupts immediately`() {
    assertEquals(FocusAction.INTERRUPT_NOW, action(FocusEvent.LOSS))
  }

  @Test
  fun `a permanent loss interrupts even with a call active and a pending decision`() {
    assertEquals(FocusAction.INTERRUPT_NOW, action(FocusEvent.LOSS, call = true, pending = true))
  }

  @Test
  fun `a voice message holding focus is interrupted when the window elapses`() {
    assertTrue(
      shouldInterruptAfterGrace(
        playWhenReady = true, isPlaying = true, isCallMode = false,
        source = TransientSource.LONG_AUDIO
      )
    )
  }

  @Test
  fun `a voice message is interrupted even while the player is only buffering`() {
    assertTrue(
      shouldInterruptAfterGrace(
        playWhenReady = true, isPlaying = false, isCallMode = false,
        source = TransientSource.LONG_AUDIO
      )
    )
  }

  @Test
  fun `an OEM reporting no playback config falls back to the time rule`() {
    assertTrue(
      shouldInterruptAfterGrace(
        playWhenReady = true, isPlaying = true, isCallMode = false,
        source = TransientSource.UNKNOWN
      )
    )
  }

  // --- DingTalk / WeChat / SMS pings: the MIUI 1.54 s regression ---

  @Test
  fun `a DingTalk ping outlasting the grace window is still not interrupted`() {
    assertFalse(
      shouldInterruptAfterGrace(
        playWhenReady = true, isPlaying = true, isCallMode = false,
        source = TransientSource.PING
      )
    )
  }

  @Test
  fun `a DingTalk ping is classified from USAGE_NOTIFICATION`() {
    assertEquals(
      TransientSource.PING,
      classifyTransientSource(setOf(AudioAttributes.USAGE_NOTIFICATION))
    )
    assertEquals(
      TransientSource.PING,
      classifyTransientSource(setOf(AudioAttributes.USAGE_NOTIFICATION_EVENT))
    )
  }

  @Test
  fun `an alarm or voice message is classified as long audio`() {
    listOf(
      AudioAttributes.USAGE_ALARM,
      AudioAttributes.USAGE_VOICE_COMMUNICATION,
      AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
      AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    ).forEach { usage ->
      assertEquals(TransientSource.LONG_AUDIO, classifyTransientSource(setOf(usage)))
    }
  }

  @Test
  fun `our own media track alone leaves the source unknown`() {
    assertEquals(
      TransientSource.UNKNOWN,
      classifyTransientSource(setOf(AudioAttributes.USAGE_MEDIA))
    )
    assertEquals(TransientSource.UNKNOWN, classifyTransientSource(emptySet()))
  }

  // --- the fallback window must not delay voice messages ---

  @Test
  fun `a normal transient loss keeps the original 1_5s window`() {
    assertEquals(1_500L, transientGraceWindowMs(duckable = false))
  }

  @Test
  fun `only a duckable transient loss gets a 2s fallback for long pings`() {
    assertEquals(2_000L, transientGraceWindowMs(duckable = true))
  }

  @Test
  fun `no event gets the discarded 2_5s blanket window`() {
    assertTrue(transientGraceWindowMs(duckable = false) < 2_500L)
    assertTrue(transientGraceWindowMs(duckable = true) < 2_500L)
  }

  @Test
  fun `a long source wins over a ping sampled in the same window`() {
    assertEquals(
      TransientSource.LONG_AUDIO,
      mergeTransientObservation(TransientSource.PING, TransientSource.LONG_AUDIO)
    )
    assertEquals(
      TransientSource.LONG_AUDIO,
      mergeTransientObservation(TransientSource.LONG_AUDIO, TransientSource.PING)
    )
    assertEquals(
      TransientSource.PING,
      mergeTransientObservation(TransientSource.PING, TransientSource.UNKNOWN)
    )
    assertEquals(
      TransientSource.UNKNOWN,
      mergeTransientObservation(TransientSource.UNKNOWN, TransientSource.UNKNOWN)
    )
  }

  // --- calls ---

  @Test
  fun `an incoming call interrupts immediately`() {
    assertEquals(FocusAction.INTERRUPT_NOW, action(FocusEvent.TRANSIENT, call = true))
  }

  @Test
  fun `a call wins over an already pending grace window`() {
    assertEquals(FocusAction.INTERRUPT_NOW, action(FocusEvent.TRANSIENT, call = true, pending = true))
  }

  @Test
  fun `a call that already paused the book does not trigger a second interrupt`() {
    assertFalse(
      shouldInterruptAfterGrace(
        playWhenReady = true, isPlaying = true, isCallMode = true,
        source = TransientSource.LONG_AUDIO
      )
    )
  }

  // --- setting OFF: every short sound may interrupt ---

  @Test
  fun `turning the setting off makes every transient loss interrupt`() {
    assertEquals(FocusAction.INTERRUPT_NOW, action(FocusEvent.TRANSIENT, settingOn = false))
  }

  @Test
  fun `setting off wins over a pending decision`() {
    assertEquals(FocusAction.INTERRUPT_NOW, action(FocusEvent.TRANSIENT, settingOn = false, pending = true))
  }

  @Test
  fun `setting off still interrupts during a call`() {
    assertEquals(FocusAction.INTERRUPT_NOW, action(FocusEvent.TRANSIENT, settingOn = false, call = true))
  }

  // --- boundaries ---

  @Test
  fun `a book the user paused is left alone at the end of the window`() {
    assertFalse(
      shouldInterruptAfterGrace(
        playWhenReady = false, isPlaying = false, isCallMode = false,
        source = TransientSource.UNKNOWN
      )
    )
  }

  @Test
  fun `unknown focus events are ignored`() {
    assertEquals(FocusAction.IGNORE, action(FocusEvent.OTHER))
  }
}

/**
 * Regression matrix for the "play, pause, play, pause" oscillation during an alarm.
 *
 * The old loop treated a granted focus request as proof that the interruption was over. An alarm
 * can release its focus request and keep ringing (USAGE_ALARM is invisible to isMusicActive), so
 * the loop stole focus, resumed, the alarm re-asserted focus, and the cycle repeated. Recovery
 * now waits for read-only evidence that no priority audio is sounding.
 */
class FocusResumeTest {

  private fun state(
    vararg usages: Int,
    musicActive: Boolean = false,
    call: Boolean = false
  ): InterruptionState = classifyInterruption(usages.toSet(), musicActive, call)

  private fun action(
    state: InterruptionState = InterruptionState.NONE,
    call: Boolean = false,
    headset: Boolean = false,
    quietProbes: Int = 2,
    blockedMs: Long = 1_000L,
    windowMs: Long = 120_000L,
    priorityHeldMs: Long = 0L,
    priorityMaxMs: Long = 900_000L
  ): ResumeAction = decideResumeAction(
    state, call, headset, quietProbes, blockedMs, windowMs, priorityHeldMs, priorityMaxMs
  )

  // --- reading the interruption source ---

  @Test
  fun `an alarm that is still ringing is a priority source`() {
    assertEquals(InterruptionState.PRIORITY, state(AudioAttributes.USAGE_ALARM))
    assertTrue(hasPriorityAudioActive(setOf(AudioAttributes.USAGE_ALARM), callMode = false))
  }

  @Test
  fun `voice messages ringtones and navigation are priority sources`() {
    listOf(
      AudioAttributes.USAGE_VOICE_COMMUNICATION,
      AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
      AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    ).forEach { usage ->
      assertEquals(InterruptionState.PRIORITY, state(usage))
    }
  }

  @Test
  fun `a DingTalk ping never delays an auto resume`() {
    assertEquals(
      InterruptionState.NONE,
      state(AudioAttributes.USAGE_NOTIFICATION)
    )
    assertEquals(
      InterruptionState.NONE,
      state(AudioAttributes.USAGE_NOTIFICATION_EVENT)
    )
    assertFalse(
      hasPriorityAudioActive(setOf(AudioAttributes.USAGE_NOTIFICATION), callMode = false)
    )
  }

  @Test
  fun `an early focus gain during an alarm is not allowed to resume`() {
    assertTrue(hasPriorityAudioActive(setOf(AudioAttributes.USAGE_ALARM), callMode = false))
    assertTrue(hasPriorityAudioActive(emptySet(), callMode = true))
  }

  @Test
  fun `our own media usage is never mistaken for another app`() {
    assertEquals(InterruptionState.NONE, state(AudioAttributes.USAGE_MEDIA))
  }

  @Test
  fun `sticky isMusicActive is only a soft media block`() {
    assertEquals(InterruptionState.MEDIA, state(musicActive = true))
  }

  @Test
  fun `a call is a priority interruption`() {
    assertEquals(InterruptionState.PRIORITY, state(call = true))
  }

  @Test
  fun `no active audio is classified as quiet`() {
    assertEquals(InterruptionState.NONE, state())
  }

  // --- the retry loop never fights a sounding alarm ---

  @Test
  fun `a ringing alarm keeps the retry loop waiting instead of requesting focus`() {
    assertEquals(
      ResumeAction.WAIT_OPEN_ENDED,
      action(state = InterruptionState.PRIORITY, quietProbes = 2, blockedMs = 300_000L)
    )
  }

  @Test
  fun `an alarm still sounding overrides an accumulated quiet streak`() {
    assertEquals(
      ResumeAction.WAIT_OPEN_ENDED,
      action(state = InterruptionState.PRIORITY, quietProbes = 5)
    )
  }

  @Test
  fun `a ringing alarm does not spend the give-up budget`() {
    assertEquals(
      ResumeAction.WAIT_OPEN_ENDED,
      action(state = InterruptionState.PRIORITY, blockedMs = 999_999L, windowMs = 120_000L)
    )
  }

  @Test
  fun `only consecutive quiet probes allow the fallback resume`() {
    assertEquals(ResumeAction.WAIT, action(quietProbes = 0))
    assertEquals(ResumeAction.WAIT, action(quietProbes = 1))
    assertEquals(ResumeAction.RESUME, action(quietProbes = 2))
  }

  @Test
  fun `another media app playing never has its output stolen`() {
    assertEquals(
      ResumeAction.WAIT,
      action(state = InterruptionState.MEDIA, quietProbes = 2)
    )
  }

  @Test
  fun `sticky isMusicActive eventually gives up instead of waiting forever`() {
    assertEquals(
      ResumeAction.GIVE_UP,
      action(state = InterruptionState.MEDIA, blockedMs = 121_000L, windowMs = 120_000L)
    )
  }

  @Test
  fun `a priority configuration that never clears is bounded`() {
    assertEquals(
      ResumeAction.GIVE_UP,
      action(
        state = InterruptionState.PRIORITY,
        priorityHeldMs = 900_000L,
        priorityMaxMs = 900_000L
      )
    )
  }

  // --- open-ended waits keep their original semantics ---

  @Test
  fun `a long call never times out`() {
    assertEquals(
      ResumeAction.WAIT_OPEN_ENDED,
      action(call = true, blockedMs = 999_999L, windowMs = 120_000L)
    )
  }

  @Test
  fun `waiting for headphones never times out`() {
    assertEquals(
      ResumeAction.WAIT_OPEN_ENDED,
      action(headset = true, blockedMs = 999_999L, windowMs = 120_000L)
    )
  }

  @Test
  fun `quiet contention past the window still gives up`() {
    assertEquals(
      ResumeAction.GIVE_UP,
      action(blockedMs = 121_000L, windowMs = 120_000L)
    )
  }
}
