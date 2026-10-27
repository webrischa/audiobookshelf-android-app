package com.audiobookshelf.nativeapp.playback

import android.media.AudioAttributes
import androidx.media3.common.Player

/**
 * Pure decision tables for the playback service: interruption policy, transient-source
 * classification, auto-resume policy and stream-retry backoff.
 *
 * Nothing here touches a Context, a Player instance or a coroutine, which is why the entire matrix
 * is covered by JVM tests (VoiceInterruptTest / FocusResumeTest / StreamRetryTest). The service
 * only gathers inputs and applies the returned action.
 */
/**
 * Whether this app still considers playback "intended" while audio is not actually running.
 * Used for our wake-lock and foreground bookkeeping, not to override media3: since 1.6.0
 * `MediaNotificationManager.shouldRunInForeground` decides the real foreground state itself with
 * a 10-minute user-engaged window that survives pauses, stops and errors. Kept pure so the
 * boundary is covered by a JVM test instead of a device-only repro.
 */
internal fun shouldHoldPlaybackForeground(
  hasMedia: Boolean,
  playWhenReady: Boolean,
  resumeIntent: Boolean,
  holdUntil: Long,
  now: Long
): Boolean = hasMedia && (playWhenReady || resumeIntent || now < holdUntil)

/** Focus events this service reacts to, mapped off AudioManager ints so the whole matrix is a
 * pure JVM-testable decision table. */
internal enum class FocusEvent { LOSS, TRANSIENT, GAIN, OTHER }

internal enum class FocusAction { INTERRUPT_NOW, DEFER, IGNORE, RESUME_IF_INTERRUPTED }

/**
 * The complete interruption policy. Product rules with "ignore notification sounds" ON:
 *  - Short pings (WeChat / DingTalk / SMS) must neither duck nor pause. They go through a grace
 *    window: AUDIOFOCUS_GAIN inside it means "ping", so nothing ever happens to the player and
 *    its volume stays at 1f.
 *  - A focus that stays away is a voice message or an alarm: pause after the window, then resume
 *    on GAIN (a transient loss keeps our request on the focus stack).
 *  - A permanent loss (alarm clock / another app taking over the output) interrupts immediately.
 *  - A call always interrupts immediately; turning the setting OFF makes every short sound
 *    interrupt immediately as well.
 */
internal fun decideFocusAction(
  event: FocusEvent,
  ignoreNotificationSounds: Boolean,
  isCallMode: Boolean,
  decisionPending: Boolean
): FocusAction = when (event) {
  FocusEvent.LOSS -> FocusAction.INTERRUPT_NOW
  FocusEvent.TRANSIENT -> when {
    isCallMode || !ignoreNotificationSounds -> FocusAction.INTERRUPT_NOW
    decisionPending -> FocusAction.IGNORE
    else -> FocusAction.DEFER
  }
  FocusEvent.GAIN -> FocusAction.RESUME_IF_INTERRUPTED
  FocusEvent.OTHER -> FocusAction.IGNORE
}

/**
 * What a transient focus loss actually turned out to be, sampled from the usages playing.
 *
 * On MIUI, DingTalk / WeChat / SMS pings are played by SystemUI's RingtonePlayer with
 * USAGE_NOTIFICATION + AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK. Measured on device: 1.541 s and
 * 1.581 s of focus hold - just over the old 1.5 s grace window, so the timer fired before
 * AUDIOFOCUS_GAIN arrived and the ping was mistaken for a voice message / alarm. Time alone is
 * a poor discriminator; the usage is authoritative when the OEM reports it.
 */
internal enum class TransientSource { PING, LONG_AUDIO, UNKNOWN }

/** Short pings: never a reason to pause, and never a reason to delay an auto resume. */
private val PING_AUDIO_USAGES = setOf(
  AudioAttributes.USAGE_NOTIFICATION,
  AudioAttributes.USAGE_NOTIFICATION_EVENT
)

internal fun classifyTransientSource(activeUsages: Set<Int>): TransientSource = when {
  activeUsages.any { it in PRIORITY_AUDIO_USAGES } -> TransientSource.LONG_AUDIO
  activeUsages.any { it in PING_AUDIO_USAGES } -> TransientSource.PING
  else -> TransientSource.UNKNOWN
}

/** A long source wins over a ping observed in the same window (alarm over a queued notification). */
internal fun mergeTransientObservation(
  current: TransientSource,
  sample: TransientSource
): TransientSource = when {
  current == TransientSource.LONG_AUDIO || sample == TransientSource.LONG_AUDIO ->
    TransientSource.LONG_AUDIO
  current == TransientSource.PING || sample == TransientSource.PING -> TransientSource.PING
  else -> TransientSource.UNKNOWN
}

/**
 * At the end of the grace window, a still-absent focus is normally a voice message or an alarm.
 * A confirmed notification ping is let through even when it outlasts the window; an OEM that
 * reports no active playback configuration falls back to the time-based rule. A book the user
 * paused, or a call that already paused it, must not be touched again.
 */
internal fun shouldInterruptAfterGrace(
  playWhenReady: Boolean,
  isPlaying: Boolean,
  isCallMode: Boolean,
  source: TransientSource
): Boolean =
  (playWhenReady || isPlaying) && !isCallMode && source != TransientSource.PING

/**
 * Fallback window for a transient loss that the usage probe could not identify.
 *
 * Widening every window to 2.5 s would make a WeChat voice message overlap the book for its
 * first two seconds - the listener cannot hear either. So the standard window stays at the
 * original 1.5 s. Only AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK gets 2 s: the requester explicitly
 * allowed other apps to keep playing, which is the signature of a short ping (DingTalk measured
 * 1.541 s / 1.581 s on this device). Neither value adds any delay once the usage probe identifies
 * the source: a confirmed ping is released immediately and an alarm / voice source interrupts
 * immediately, faster than the old fixed timer.
 */
internal fun transientGraceWindowMs(duckable: Boolean): Long = if (duckable) 2_000L else 1_500L

/**
 * What is keeping the output busy while playback is interrupted.
 *
 * A focus callback carries no source information, and `requestAudioFocus(AUDIOFOCUS_GAIN)` can
 * still return GRANTED while an alarm is sounding: alarms / voice messages / navigation use
 * usages other than USAGE_MEDIA, the framework does not stop them when we ask for focus again,
 * and `AudioManager.isMusicActive()` only observes the MEDIA stream. "We got the focus back" is
 * therefore NOT evidence that the interruption ended. Resuming on that assumption made the retry
 * loop steal focus from a ringing alarm, which then re-asserted focus and paused us again - the
 * reported play / pause / play oscillation. Recovery now requires a read-only probe instead.
 */
internal enum class InterruptionState { NONE, MEDIA, PRIORITY }

/**
 * Audio that must keep the book paused even after it has released its focus request: alarms,
 * voice messages / calls, navigation and assistant. Short notification pings deliberately are
 * NOT here - they live in [PING_AUDIO_USAGES]. Treating a ping as a blocking source made a
 * DingTalk notification delay an auto resume by several seconds, which contradicts the
 * "ignore notification sounds" setting and feels like yet another interruption.
 */
private val PRIORITY_AUDIO_USAGES = setOf(
  AudioAttributes.USAGE_ALARM,
  AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
  AudioAttributes.USAGE_VOICE_COMMUNICATION,
  AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
  AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
  AudioAttributes.USAGE_ASSISTANT
)

/** Also guards the AUDIOFOCUS_GAIN fast path: some OEMs deliver GAIN repeatedly during an alarm. */
internal fun hasPriorityAudioActive(activeUsages: Set<Int>, callMode: Boolean): Boolean =
  callMode || activeUsages.any { it in PRIORITY_AUDIO_USAGES }

internal fun classifyInterruption(
  activeUsages: Set<Int>,
  musicActive: Boolean,
  callMode: Boolean
): InterruptionState = when {
  hasPriorityAudioActive(activeUsages, callMode) -> InterruptionState.PRIORITY
  // Weak evidence only: isMusicActive is sticky for a while after our own pause() on some OEMs,
  // so this branch stays subject to RESUME_WINDOW_MS instead of waiting forever.
  musicActive -> InterruptionState.MEDIA
  else -> InterruptionState.NONE
}

internal enum class ResumeAction { RESUME, WAIT, WAIT_OPEN_ENDED, GIVE_UP }

/** Consecutive quiet probes before the fallback resume: alarms can have silent gaps between rings. */
private const val RESUME_QUIET_PROBES = 2

/** The whole retry policy at one decision point. Kept pure so the matrix is covered by JVM tests. */
internal fun decideResumeAction(
  interruption: InterruptionState,
  callMode: Boolean,
  waitingForHeadset: Boolean,
  quietProbes: Int,
  blockedMs: Long,
  resumeWindowMs: Long,
  priorityHeldMs: Long,
  priorityMaxMs: Long
): ResumeAction = when {
  // A call or unplugged headphones lasts as long as the user wants: no give-up deadline.
  callMode || waitingForHeadset -> ResumeAction.WAIT_OPEN_ENDED
  // An alarm is confirmed to still be sounding. Do not request focus (that is what makes both
  // apps fight over the output), and do not spend the give-up budget on it. A stale OEM playback
  // configuration must still be bounded so resumeIntent cannot hold the wake lock forever.
  interruption == InterruptionState.PRIORITY ->
    if (priorityHeldMs >= priorityMaxMs) ResumeAction.GIVE_UP else ResumeAction.WAIT_OPEN_ENDED
  blockedMs > resumeWindowMs -> ResumeAction.GIVE_UP
  interruption == InterruptionState.MEDIA -> ResumeAction.WAIT
  quietProbes >= RESUME_QUIET_PROBES -> ResumeAction.RESUME
  else -> ResumeAction.WAIT
}

/**
 * How much a resume-loop wait really spent on the give-up budget.
 *
 * The loop used to add the *planned* delay to `blockedMs`. Doze or an OEM freezer can stretch a
 * requested 2 s delay into minutes, so the 120 s budget (and with it the wake lock) could last far
 * longer than intended. The planned value stays as a floor for clock skew, never as the measure.
 */
internal fun resumeElapsedMs(plannedMs: Long, actualMs: Long): Long =
  maxOf(plannedMs, actualMs.coerceAtLeast(0L))

/** What the stream-retry loop should do at a decision point. Kept pure for the JVM tests. */
internal enum class StreamRetryAction { RETRY, WAIT, STOP }

/**
 * Decides whether a failed stream may be retried right now.
 *
 * A late network callback must never resurrect a book the user paused or that was cleared, and
 * must never re-`prepare()` a player that is already buffering or playing. Timed retries spend
 * [retriesLeft]; once it is exhausted only a genuine network recovery may wake the stream, and
 * only within [totalBudgetMs] — otherwise a permanently dead link would keep the CPU awake
 * forever.
 */
internal fun decideStreamRetry(
  playWhenReady: Boolean,
  hasMedia: Boolean,
  playbackState: Int,
  retriesLeft: Int,
  networkSignal: Boolean,
  elapsedMs: Long,
  totalBudgetMs: Long
): StreamRetryAction {
  if (!playWhenReady || !hasMedia) return StreamRetryAction.STOP
  if (playbackState != Player.STATE_IDLE) return StreamRetryAction.STOP
  if (elapsedMs > totalBudgetMs) return StreamRetryAction.STOP
  if (retriesLeft > 0 || networkSignal) return StreamRetryAction.RETRY
  return StreamRetryAction.WAIT
}

/** Exponential backoff for timed retries, capped so a long outage stays low-frequency. */
internal fun nextStreamRetryDelayMs(currentMs: Long, maxMs: Long): Long =
  (currentMs * 2).coerceAtMost(maxMs)

/** Debug-only observation window for a backward position jump inside one track. */
private const val FALLBACK_LOG_MIN_MS = 400L
private const val FALLBACK_LOG_MAX_MS = 8_000L

/**
 * Returns the size of a backward position jump worth logging, or null for everything else.
 *
 * This is deliberately **observation only**: the service must not seek in response to it. The
 * earlier "heal" used a threshold plus a cooldown to guess at a cache seam over a transcoded
 * stream; that guess was never confirmed on a device, and `DISCONTINUITY_REASON_INTERNAL` also
 * covers ExoPlayer's own position corrections and playlist rebuilds, so an automatic seek can
 * work against the player. The pure shape keeps the filter testable without a Player instance.
 */
internal fun positionFallbackToLog(
  reason: Int,
  oldTrack: Int,
  newTrack: Int,
  oldPositionMs: Long,
  newPositionMs: Long
): Long? {
  if (reason != Player.DISCONTINUITY_REASON_INTERNAL &&
    reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION
  ) {
    return null
  }
  if (oldTrack != newTrack) return null
  val fallbackMs = oldPositionMs - newPositionMs
  return fallbackMs.takeIf { it in FALLBACK_LOG_MIN_MS..FALLBACK_LOG_MAX_MS }
}
