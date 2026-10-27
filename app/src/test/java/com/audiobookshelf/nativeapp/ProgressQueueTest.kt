package com.audiobookshelf.nativeapp

import com.audiobookshelf.nativeapp.data.MAX_PENDING_PROGRESS
import com.audiobookshelf.nativeapp.data.PendingProgress
import com.audiobookshelf.nativeapp.data.belongsToSession
import com.audiobookshelf.nativeapp.data.mergePending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outbox is a durable queue, not an append-only log: the service reports a position every
 * 15 s, so one offline hour must not produce 240 writes for the same book, and the single
 * isFinished write at STATE_ENDED must never be overwritten by a later position tick.
 */
class ProgressQueueTest {

  private fun entry(
    id: String,
    seconds: Double = 10.0,
    finished: Boolean = false,
    at: Long = 1L
  ) = PendingProgress(
    itemId = id,
    currentTime = seconds,
    duration = 100.0,
    isFinished = finished,
    createdAtMs = at,
    userId = "u1"
  )

  @Test
  fun `a newer position replaces the older one for the same book`() {
    val merged = mergePending(listOf(entry("book", 10.0)), entry("book", 25.0, at = 2L))
    assertEquals(1, merged.size)
    assertEquals(25.0, merged.single().currentTime, 0.0)
  }

  @Test
  fun `a finished book is never un-finished by a later position`() {
    val merged = mergePending(
      listOf(entry("book", 100.0, finished = true, at = 1L)),
      entry("book", 40.0, finished = false, at = 2L)
    )
    assertEquals(1, merged.size)
    assertTrue(merged.single().isFinished)
    assertEquals(100.0, merged.single().currentTime, 0.0)
  }

  @Test
  fun `a finished write can replace an unfinished one`() {
    val merged = mergePending(
      listOf(entry("book", 40.0, at = 1L)),
      entry("book", 100.0, finished = true, at = 2L)
    )
    assertTrue(merged.single().isFinished)
    assertEquals(100.0, merged.single().currentTime, 0.0)
  }

  @Test
  fun `different books each keep their own pending write`() {
    val merged = mergePending(listOf(entry("a"), entry("b")), entry("a", 55.0, at = 3L))
    assertEquals(2, merged.size)
    assertEquals(setOf("a", "b"), merged.map { it.itemId }.toSet())
    assertEquals(55.0, merged.first { it.itemId == "a" }.currentTime, 0.0)
  }

  @Test
  fun `the cap drops the oldest entries and keeps the newest`() {
    var queue = emptyList<PendingProgress>()
    repeat(MAX_PENDING_PROGRESS + 25) { index ->
      queue = mergePending(queue, entry("book-$index", at = index.toLong()))
    }
    assertEquals(MAX_PENDING_PROGRESS, queue.size)
    assertFalse(queue.any { it.itemId == "book-0" })
    assertEquals("book-${MAX_PENDING_PROGRESS + 24}", queue.last().itemId)
  }

  @Test
  fun `an empty queue simply stores the first failure`() {
    val merged = mergePending(emptyList(), entry("book", finished = true))
    assertEquals(1, merged.size)
    assertTrue(merged.single().isFinished)
  }

  @Test
  fun `a write is replayed only for the account that produced it`() {
    assertTrue(belongsToSession("u1", "u1"))
    assertFalse(belongsToSession("u2", "u1"))
  }

  @Test
  fun `an unattributed write is never replayed under the next account`() {
    // The session was gone when the report failed (401 / logout while playing). Guessing the
    // current owner here would push this position onto a different user's server-side book.
    assertFalse(belongsToSession("", "u1"))
    assertFalse(belongsToSession("   ", "u1"))
  }

  @Test
  fun `ownership is exact and does not guess from whitespace`() {
    assertFalse(belongsToSession(" u1", "u1"))
    assertFalse(belongsToSession("u1 ", "u1"))
    assertFalse(belongsToSession("U1", "u1"))
  }
}
