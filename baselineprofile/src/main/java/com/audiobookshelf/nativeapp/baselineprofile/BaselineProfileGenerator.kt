package com.audiobookshelf.nativeapp.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a method-granularity baseline profile, replacing the hand-written
 * `app/src/main/baseline-prof.txt` (which only lists classes on the startup path).
 *
 * Run on a physical device or an emulator with a debuggable/profileable :app build:
 *
 *   ./gradlew :app:generateBaselineProfile
 *
 * The rule writes to `app/src/main/generated/baselineProfiles/baseline-prof.txt`. Journey steps
 * beyond cold start are intentionally left out: without a signed-in session the app lands on the
 * login screen, and driving a real login here would bake credentials into a checked-in file.
 * Add steps after login (home -> bookshelf -> player) once a test account is provisioned.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

  @get:Rule
  val baselineProfileRule = BaselineProfileRule()

  @Test
  fun generate() {
    baselineProfileRule.collect(
      packageName = TARGET_PACKAGE,
      maxIterations = 10,
      stableIterations = 3,
      includeInStartupProfile = true
    ) {
      pressHome()
      startActivityAndWait()
    }
  }

  private companion object {
    const val TARGET_PACKAGE = "com.audiobookshelf.nativeapp"
  }
}
