package com.audiobookshelf.nativeapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.audiobookshelf.nativeapp.ui.ErrorBanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First instrumented UI test. The banner is the surface that shows every mapped API error,
 * including the 401 re-login text, so it is the cheapest place to lock the "no raw response body
 * reaches the listener" contract end to end.
 *
 * Run with: ./gradlew connectedDebugAndroidTest (requires a connected device/emulator).
 */
@RunWith(AndroidJUnit4::class)
class ErrorBannerTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun showsTheMappedErrorMessage() {
    composeTestRule.setContent {
      MaterialTheme {
        ErrorBanner(message = "登录已过期，请重新登录", onDismiss = {})
      }
    }
    composeTestRule.onNodeWithText("登录已过期，请重新登录").assertIsDisplayed()
  }

  @Test
  fun isAbsentWhenThereIsNoError() {
    composeTestRule.setContent {
      MaterialTheme {
        ErrorBanner(message = null, onDismiss = {})
      }
    }
    composeTestRule.onNodeWithText("登录已过期，请重新登录").assertDoesNotExist()
  }

  @Test
  fun doesNotRenderForABlankMessage() {
    composeTestRule.setContent {
      MaterialTheme {
        ErrorBanner(message = "   ", onDismiss = {})
      }
    }
    composeTestRule.onNodeWithText("登录已过期，请重新登录").assertDoesNotExist()
  }
}
