@file:Suppress("NOTHING_TO_INLINE")

package com.audiobookshelf.nativeapp.util

import android.util.Log
import com.audiobookshelf.nativeapp.BuildConfig

/**
 * The only logging entry point for app code.
 *
 * `BuildConfig.DEBUG` is a compile-time constant: in release builds the `if` block is dead code,
 * so the compiler/R8 removes both the `Log` call and the message built at the call site. Debug
 * builds keep the original tags, levels and throwable stack traces untouched.
 *
 * Do not call `android.util.Log` directly - the R8 rule in proguard-rules.pro strips those calls
 * in release, but a direct call would still build its message before being removed.
 */
object AppLog {
  /**
   * Temporary click diagnostics under the single tag `TapTrace`. The compact call stack is the
   * point: a real pointer tap arrives through the clickable/pointerInput nodes, while an
   * accessibility service's ACTION_CLICK arrives through ComposeAccessibilityNodeProvider.
   * Debug-only; BuildConfig.DEBUG is a compile-time false in release, so all of this is removed.
   */
  inline fun tapTrace(event: String, detail: String = "") {
    if (BuildConfig.DEBUG) {
      val frames = Throwable().stackTrace
        .dropWhile { it.className.contains("AppLog") }
        .take(16)
        .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }
      Log.d("TapTrace", "$event $detail :: $frames")
    }
  }

  inline fun v(tag: String, msg: String) {
    if (BuildConfig.DEBUG) Log.v(tag, msg)
  }

  inline fun v(tag: String, msg: String, tr: Throwable) {
    if (BuildConfig.DEBUG) Log.v(tag, msg, tr)
  }

  inline fun d(tag: String, msg: String) {
    if (BuildConfig.DEBUG) Log.d(tag, msg)
  }

  inline fun d(tag: String, msg: String, tr: Throwable) {
    if (BuildConfig.DEBUG) Log.d(tag, msg, tr)
  }

  inline fun i(tag: String, msg: String) {
    if (BuildConfig.DEBUG) Log.i(tag, msg)
  }

  inline fun i(tag: String, msg: String, tr: Throwable) {
    if (BuildConfig.DEBUG) Log.i(tag, msg, tr)
  }

  inline fun w(tag: String, msg: String) {
    if (BuildConfig.DEBUG) Log.w(tag, msg)
  }

  inline fun w(tag: String, msg: String, tr: Throwable) {
    if (BuildConfig.DEBUG) Log.w(tag, msg, tr)
  }

  inline fun e(tag: String, msg: String) {
    if (BuildConfig.DEBUG) Log.e(tag, msg)
  }

  inline fun e(tag: String, msg: String, tr: Throwable) {
    if (BuildConfig.DEBUG) Log.e(tag, msg, tr)
  }

  inline fun wtf(tag: String, msg: String) {
    if (BuildConfig.DEBUG) Log.wtf(tag, msg)
  }

  inline fun wtf(tag: String, msg: String, tr: Throwable) {
    if (BuildConfig.DEBUG) Log.wtf(tag, msg, tr)
  }
}
