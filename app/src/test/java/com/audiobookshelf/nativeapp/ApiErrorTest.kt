package com.audiobookshelf.nativeapp

import com.audiobookshelf.nativeapp.data.ApiException
import com.audiobookshelf.nativeapp.data.UserFacingError
import com.audiobookshelf.nativeapp.data.httpException
import com.audiobookshelf.nativeapp.data.isUnauthorized
import com.audiobookshelf.nativeapp.data.userMessage
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 401 regression: a single ApiException used to carry the raw response body in its message,
 * which the UI displayed verbatim, while Repository.refreshProgress() swallowed the failure with
 * runCatching{}.getOrNull() so no caller could tell an expired token from a dead network.
 */
class ApiErrorTest {

  private val BODY = "{\"error\":\"token expired\",\"trace\":\"secret\"}"

  @Test
  fun `401 is dispatched to Unauthorized`() {
    val error = httpException(401, BODY)
    assertTrue(error is ApiException.Unauthorized)
    assertEquals(401, error.code)
    assertTrue(error.isUnauthorized())
  }

  @Test
  fun `403 is dispatched to Forbidden and is not a logout`() {
    val error = httpException(403, BODY)
    assertTrue(error is ApiException.Forbidden)
    assertFalse(error.isUnauthorized())
  }

  @Test
  fun `other statuses stay typed with their code`() {
    val error = httpException(503, BODY)
    assertTrue(error is ApiException.Http)
    assertEquals(503, error.code)
    assertFalse(error.isUnauthorized())
  }

  @Test
  fun `401 maps to a re-login message and never exposes the response body`() {
    val message = httpException(401, BODY).userMessage()
    assertEquals("登录已过期，请重新登录", message)
    assertFalse(message.contains("HTTP"))
    assertFalse(message.contains("token"))
    assertFalse(message.contains("secret"))
  }

  @Test
  fun `errors are mapped by code not by message`() {
    assertEquals("没有访问权限", httpException(403, BODY).userMessage())
    assertEquals("请求的内容不存在", httpException(404, BODY).userMessage())
    assertEquals("服务器暂时不可用，请稍后重试", httpException(500, BODY).userMessage())
    assertEquals("服务器暂时不可用，请稍后重试", httpException(503, BODY).userMessage())
    assertEquals("请求失败（429），请稍后重试", httpException(429, BODY).userMessage())
  }

  @Test
  fun `network failures never leak the exception message`() {
    val message = IOException("connection reset by 192.168.1.1").userMessage()
    assertEquals("网络连接失败，请检查网络", message)
    assertFalse(message.contains("192.168"))
  }

  @Test
  fun `locally raised user text is kept`() {
    assertEquals("用户名或密码错误", UserFacingError("用户名或密码错误").userMessage())
  }

  @Test
  fun `unknown exceptions fall back to a generic message`() {
    val message = Exception("raw server payload").userMessage()
    assertEquals("操作失败，请稍后重试", message)
    assertFalse(message.contains("raw server payload"))
  }
}
