package com.audiobookshelf.nativeapp.data

/**
 * Sort options for the library shelf, mirroring the field names the server accepts.
 */
enum class ShelfSort(val apiValue: String, val label: String) {
  ADDED("addedAt", "最近添加"),
  TITLE("media.metadata.title", "标题"),
  AUTHOR("media.metadata.authorName", "作者"),
  YEAR("media.metadata.publishedYear", "出版年份"),
  DURATION("media.duration", "时长"),
  RANDOM("random", "随机");

  companion object {
    /** Title ascending is the default order of a shelf. */
    fun fromApiValue(value: String?): ShelfSort =
      entries.firstOrNull { it.apiValue == value } ?: TITLE
  }
}
