package com.audiobookshelf.nativeapp.data

import java.io.IOException

/**
 * Typed API failures.
 *
 * The previous single `ApiException(code, body)` put the raw response body into its message. The
 * UI then displayed that message verbatim ("HTTP 401: {...}"), and no caller could distinguish an
 * expired token from a dead network without parsing a string. Both problems are fixed here: the
 * failure is a sealed type, and the user-facing text is derived from the type/code, never from
 * [Throwable.message], so server payloads can never leak into a toast or banner.
 */
sealed class ApiException(message: String) : Exception(message) {
  abstract val code: Int

  /** Token expired or revoked. The only HTTP failure that must end the session. */
  class Unauthorized internal constructor(val body: String) : ApiException("unauthorized") {
    override val code: Int = 401
  }

  /** The server answered, but this account may not read the resource. */
  class Forbidden internal constructor(val body: String) : ApiException("forbidden") {
    override val code: Int = 403
  }

  /** Any other non-2xx response. [body] is kept for logging only. */
  class Http internal constructor(override val code: Int, val body: String) :
    ApiException("HTTP $code")
}

internal fun httpException(code: Int, body: String): ApiException = when (code) {
  401 -> ApiException.Unauthorized(body)
  403 -> ApiException.Forbidden(body)
  else -> ApiException.Http(code, body)
}

/**
 * A locally raised failure whose message is already safe, user-facing Chinese text (e.g. a
 * rejected password). Unlike [ApiException], its message may be displayed directly.
 */
class UserFacingError(message: String) : Exception(message)

fun Throwable.isUnauthorized(): Boolean = this is ApiException.Unauthorized

/**
 * Maps a failure to a stable UI string. Deliberately never returns [Throwable.message] for
 * network/HTTP errors: the response body is server data, not a message for the listener.
 */
fun Throwable.userMessage(): String = when (this) {
  is ApiException.Unauthorized -> "登录已过期，请重新登录"
  is ApiException.Forbidden -> "没有访问权限"
  is ApiException.Http -> when {
    code == 404 -> "请求的内容不存在"
    code >= 500 -> "服务器暂时不可用，请稍后重试"
    else -> "请求失败（$code），请稍后重试"
  }
  is UserFacingError -> message ?: "操作失败，请稍后重试"
  is IOException -> "网络连接失败，请检查网络"
  else -> "操作失败，请稍后重试"
}
