package com.audiobookshelf.nativeapp

import com.audiobookshelf.nativeapp.data.ChapterDto
import com.audiobookshelf.nativeapp.playback.PlayerConnection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chapter lookup drives three separate behaviours — which chapter the progress bar follows, which
 * one the media card labels, and where the intro/outro skips fire — so an off-by-one here shows up
 * as a skipped chapter or a card naming the previous one.
 */
class ChapterLookupTest {

  private val chapters = listOf(
    ChapterDto(id = 1, start = 0.0, end = 600.0, title = "第一章"),
    ChapterDto(id = 2, start = 600.0, end = 1_200.0, title = "第二章"),
    ChapterDto(id = 3, start = 1_200.0, end = 1_800.0, title = "第三章")
  )

  @Test
  fun `index follows the chapter that contains the position`() {
    assertEquals(0, PlayerConnection.chapterIndexAt(chapters, 0))
    assertEquals(0, PlayerConnection.chapterIndexAt(chapters, 599_999))
    assertEquals(1, PlayerConnection.chapterIndexAt(chapters, 600_000))
    assertEquals(2, PlayerConnection.chapterIndexAt(chapters, 1_700_000))
  }

  @Test
  fun `positions past the last chapter stay on the last chapter`() {
    assertEquals(2, PlayerConnection.chapterIndexAt(chapters, 9_999_999))
  }

  @Test
  fun `a book without chapters has no index`() {
    assertEquals(-1, PlayerConnection.chapterIndexAt(emptyList(), 12_000))
  }

  @Test
  fun `media card names the chapter and the book`() {
    val (title, subtitle) = PlayerConnection.mediaCardText(
      chapters = chapters,
      absoluteMs = 700_000,
      bookTitle = "银河系漫游指南",
      bookAuthor = "道格拉斯·亚当斯"
    )
    assertEquals("第二章", title)
    assertEquals("银河系漫游指南 · 道格拉斯·亚当斯", subtitle)
  }

  @Test
  fun `media card falls back to the book when there are no chapters`() {
    val (title, subtitle) = PlayerConnection.mediaCardText(
      chapters = emptyList(),
      absoluteMs = 0,
      bookTitle = "银河系漫游指南",
      bookAuthor = "道格拉斯·亚当斯"
    )
    assertEquals("银河系漫游指南", title)
    assertEquals("道格拉斯·亚当斯", subtitle)
  }

  @Test
  fun `a chapter without a title gets a numbered one`() {
    val untitled = listOf(ChapterDto(id = 1, start = 0.0, end = 600.0, title = ""))
    val (title, _) = PlayerConnection.mediaCardText(untitled, 0, "书", "作者")
    assertEquals("第 1 章", title)
  }
}
