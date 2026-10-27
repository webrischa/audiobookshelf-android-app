package com.audiobookshelf.nativeapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.audiobookshelf.nativeapp.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Prepares the app background as a small, already blurred image.
 *
 * The background used to be the full-size picture with `Modifier.blur(48.dp)` on top of it, which
 * has two problems:
 *
 *  - `Modifier.blur` only renders a real blur on API 31+. Below that it is silently ignored, so on
 *    Android 8–11 the background was simply the sharp photo, with all its detail competing with
 *    the text on top of it.
 *  - On API 31+ it costs a full-screen offscreen render pass, and it has to be redone whenever the
 *    layer is invalidated (rotation, a resize, a recomposition that recreates the graphics layer).
 *
 * Blurring a 120 px wide copy once and stretching that over the screen is both cheaper and
 * indistinguishable at 48dp of blur radius. The result is cached on disk, keyed by the source file
 * and its timestamp, so re-picking an image produces a new file instead of a stale one.
 */
object BackgroundBlur {
  private const val TAG = "BgBlur"

  /** Width the source is reduced to before blurring. Stretched, this reads as a very soft focus. */
  private const val TARGET_WIDTH = 120

  /** Box blur passes; three passes approximate a gaussian closely enough at this size. */
  private const val BLUR_PASSES = 3

  private const val CACHE_DIR = "background"
  private const val JPEG_QUALITY = 85

  /**
   * Cache lookup without decoding anything, for the first composition.
   *
   * One `stat` on the main thread instead of a frame with no background at all: the blurred copy
   * exists on every launch but the first, and waiting for a coroutine to report that back would
   * flash an empty background every time the app starts.
   */
  fun cachedPath(context: Context, source: String): String? {
    val target = File(File(context.cacheDir, CACHE_DIR), "${cacheKey(source)}.jpg")
    return target.takeIf { it.isFile && it.length() > 0L }?.absolutePath
  }

  /**
   * @return absolute path of the blurred copy, or null when the source cannot be decoded (a
   *   permission that was revoked, a file that was deleted)
   */
  suspend fun prepare(context: Context, source: String): String? = withContext(Dispatchers.IO) {
    runCatching {
      cachedPath(context, source)?.let { return@runCatching it }
      val dir = File(context.cacheDir, CACHE_DIR)
      if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
      val target = File(dir, "${cacheKey(source)}.jpg")

      val decoded = decodeSmall(context, source) ?: run {
        AppLog.w(TAG, "cannot decode $source")
        return@runCatching null
      }
      val blurred = boxBlur(decoded, BLUR_PASSES)
      FileOutputStream(target).use { out ->
        blurred.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
      }
      if (blurred !== decoded) blurred.recycle()
      decoded.recycle()
      // Drop older copies: re-picking the background leaves one file per pick behind otherwise.
      dir.listFiles()
        ?.filter { it.isFile && it.name != target.name }
        ?.forEach { it.delete() }
      AppLog.i(TAG, "prepared $source -> ${target.absolutePath}")
      target.absolutePath
    }.onFailure { AppLog.w(TAG, "cannot prepare background from $source", it) }.getOrNull()
  }

  /** Decodes the source at roughly [TARGET_WIDTH] px wide, whatever its original size is. */
  private fun decodeSmall(context: Context, source: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // Statement form on purpose: `openStream(...)?.use { decodeStream(...) }` evaluates to the
    // decode result, which is null for a bounds-only decode, so a `?: return null` on it would
    // reject every image — including the ones that decoded fine.
    val boundsStream = openStream(context, source) ?: return null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
    val options = BitmapFactory.Options().apply {
      inSampleSize = sample
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = openStream(context, source)?.use {
      BitmapFactory.decodeStream(it, null, options)
    } ?: return null
    if (decoded.width <= 0 || decoded.height <= 0) return null

    val height = (TARGET_WIDTH.toFloat() * decoded.height / decoded.width)
      .roundToInt()
      .coerceAtLeast(1)
    return Bitmap.createScaledBitmap(decoded, TARGET_WIDTH, height, true)
  }

  private fun openStream(context: Context, source: String): InputStream? = runCatching {
    when {
      source.startsWith(ASSET_PREFIX) ->
        context.assets.open(source.removePrefix(ASSET_PREFIX))
      source.startsWith("content://") ->
        context.contentResolver.openInputStream(Uri.parse(source))
      source.startsWith("file://") ->
        Uri.parse(source).path?.let { File(it).inputStream() }
      else -> File(source).inputStream()
    }
  }.getOrNull()

  /**
   * Identity of the source: the path plus a stamp that changes when the file does, so a new pick
   * never reuses the previous blur.
   */
  private fun cacheKey(source: String): String {
    val stamp = when {
      source.startsWith(ASSET_PREFIX) -> "asset"
      source.startsWith("content://") -> source
      else -> {
        val file = File(source.removePrefix("file://"))
        "${file.length()}_${file.lastModified()}"
      }
    }
    val digest = MessageDigest.getInstance("SHA-1").digest("$source|$stamp".toByteArray())
    return digest.take(8).joinToString("") { "%02x".format(it) }
  }

  /** Separable box blur; [radius] scales with the image so the result is resolution independent. */
  private fun boxBlur(source: Bitmap, passes: Int): Bitmap {
    val width = source.width
    val height = source.height
    if (width <= 0 || height <= 0) return source
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val radius = (minOf(width, height) / 6).coerceIn(2, 16)
    var current = pixels
    repeat(passes) {
      current = blurPass(current, width, height, radius, horizontal = true)
      current = blurPass(current, width, height, radius, horizontal = false)
    }
    return Bitmap.createBitmap(current, width, height, Bitmap.Config.ARGB_8888)
  }

  private fun blurPass(
    src: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean
  ): IntArray {
    val out = IntArray(src.size)
    val length = if (horizontal) width else height
    val lines = if (horizontal) height else width
    val window = radius * 2 + 1

    fun at(line: Int, index: Int): Int =
      if (horizontal) src[line * width + index] else src[index * width + line]

    for (line in 0 until lines) {
      var a = 0
      var r = 0
      var g = 0
      var b = 0
      // Prime the sliding window, clamping at both edges so the border does not darken.
      for (offset in -radius..radius) {
        val pixel = at(line, offset.coerceIn(0, length - 1))
        a += (pixel ushr 24) and 0xFF
        r += (pixel shr 16) and 0xFF
        g += (pixel shr 8) and 0xFF
        b += pixel and 0xFF
      }
      for (index in 0 until length) {
        val packed = ((a / window) shl 24) or ((r / window) shl 16) or
          ((g / window) shl 8) or (b / window)
        if (horizontal) out[line * width + index] = packed else out[index * width + line] = packed

        val leaving = at(line, (index - radius).coerceIn(0, length - 1))
        val entering = at(line, (index + radius + 1).coerceIn(0, length - 1))
        a += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
        r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
        g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
        b += (entering and 0xFF) - (leaving and 0xFF)
      }
    }
    return out
  }

  private const val ASSET_PREFIX = "file:///android_asset/"
}
