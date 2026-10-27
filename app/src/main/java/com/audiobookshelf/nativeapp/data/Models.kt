package com.audiobookshelf.nativeapp.data

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class UserDto(
  val id: String = "",
  val username: String = "",
  val token: String = "",
  val type: String? = null,
  val bookmarks: List<BookmarkDto> = emptyList()
)

@Serializable
data class BookmarkDto(val libraryItemId: String = "", val title: String = "", val time: Double = 0.0)

@Serializable
data class LoginResponse(val user: UserDto = UserDto(), val userDefaultLibraryId: String? = null)

@Serializable
data class LibrariesResponse(val libraries: List<LibraryDto> = emptyList())

@Serializable
data class LibraryDto(
  val id: String = "",
  val name: String = "",
  val mediaType: String? = null,
  val icon: String? = null,
  val displayOrder: Int = 0
)

@Serializable
data class ItemsResponse(
  val results: List<LibraryItemDto> = emptyList(),
  val total: Int = 0,
  val page: Int = 0,
  val limit: Int = 0
)

@Serializable
data class LibraryItemDto(
  val id: String = "",
  val libraryId: String? = null,
  val addedAt: Long = 0,
  val mediaType: String? = null,
  val media: MediaDto = MediaDto(),
  val userMediaProgress: ProgressDto? = null,
  /** Set by the server when the library is requested with collapseseries=1. */
  val collapsedSeries: CollapsedSeriesDto? = null
) {
  /**
   * The server only sets [MediaDto.coverPath] when the item has artwork. This is the same check
   * the original client uses (`!!media.coverPath` in LazyBookCard.vue / BookCover.vue): it lets
   * the UI decide about the placeholder before any network request is made.
   */
  fun hasCover(): Boolean = !media.coverPath.isNullOrBlank()
}

/**
 * Server side series folding: the item that carries this stands in for the whole series, so the
 * list has one slot per series instead of one per book.
 */
@Serializable
data class CollapsedSeriesDto(
  val id: String = "",
  val name: String = "",
  val nameIgnorePrefix: String = "",
  val sequence: String? = null,
  val numBooks: Int = 0,
  val libraryItemIds: List<String> = emptyList(),
  val seriesSequenceList: List<String> = emptyList()
)

@Serializable
data class MediaDto(
  val metadata: MetadataDto = MetadataDto(),
  /** Present (non-blank) only when the server has a cover for this item. */
  val coverPath: String? = null,
  val duration: Double = 0.0,
  val numChapters: Int = 0,
  val numAudioFiles: Int = 0,
  val chapters: List<ChapterDto> = emptyList(),
  val tracks: List<TrackDto> = emptyList(),
  val ebookFile: EbookFileDto? = null
)

@Serializable
data class AuthorRefDto(val id: String = "", val name: String = "")

@Serializable
data class MetadataDto(
  val title: String? = null,
  val subtitle: String? = null,
  val authorName: String? = null,
  val author: String? = null,
  val authors: List<AuthorRefDto> = emptyList(),
  val narratorName: String? = null,
  val seriesName: String? = null,
  val description: String? = null,
  val genres: List<String> = emptyList(),
  val publishedYear: String? = null,
  val language: String? = null
) {
  /** authorName is not always present: podcasts use `author`, newer servers also send `authors`. */
  fun displayAuthor(): String? =
    authorName?.takeIf { it.isNotBlank() }
      ?: author?.takeIf { it.isNotBlank() }
      ?: authors.firstOrNull { it.name.isNotBlank() }?.name
}

@Serializable
data class ChapterDto(val id: Int = 0, val start: Double = 0.0, val end: Double = 0.0, val title: String = "")

@Serializable
data class TrackDto(
  val index: Int = 0,
  val startOffset: Double = 0.0,
  val duration: Double = 0.0,
  val contentUrl: String? = null,
  val mimeType: String? = null,
  val title: String? = null
)

@Serializable
data class EbookFileDto(val ebookFormat: String? = null, val ebookLocation: String? = null)

@Serializable
data class ProgressDto(
  val libraryItemId: String? = null,
  val episodeId: String? = null,
  val currentTime: Double = 0.0,
  val duration: Double = 0.0,
  val progress: Double = 0.0,
  val isFinished: Boolean = false,
  val lastUpdate: Long = 0
)

@Serializable
data class DeviceInfoDto(
  val deviceId: String,
  val clientName: String = "ABS Native",
  val clientVersion: String = "0.1.0"
)

@Serializable
data class PlayRequest(
  val deviceInfo: DeviceInfoDto,
  val mediaPlayer: String = "exo-player",
  val forceDirectPlay: Boolean = true
)

@Serializable
data class PlaybackSessionDto(
  val id: String = "",
  val libraryItemId: String? = null,
  val episodeId: String? = null,
  val displayTitle: String = "",
  val displayAuthor: String = "",
  val duration: Double = 0.0,
  val currentTime: Double = 0.0,
  val chapters: List<ChapterDto> = emptyList(),
  val audioTracks: List<AudioTrackDto> = emptyList(),
  val mediaMetadata: MetadataDto = MetadataDto()
)

@Serializable
data class AudioTrackDto(
  val index: Int = 0,
  val startOffset: Double = 0.0,
  val duration: Double = 0.0,
  val contentUrl: String = "",
  val mimeType: String? = null,
  val title: String? = null
)

@Serializable
data class ProgressUpdate(
  val currentTime: Double,
  val duration: Double,
  val progress: Double,
  /**
   * Only sent when the book is actually finished. Audiobookshelf treats an explicit `false` as
   * "un-finish" and resets currentTime/progress to 0, so a plain progress patch must omit it.
   * Nulls are dropped by the Json config (explicitNulls = false).
   */
  val isFinished: Boolean? = null
)

@Serializable
data class SearchBookDto(val libraryItem: LibraryItemDto = LibraryItemDto())

@Serializable
data class SearchResponse(
  val book: List<SearchBookDto> = emptyList(),
  val podcast: List<SearchBookDto> = emptyList()
)

@Serializable
data class ShelfDto(
  val id: String = "",
  val label: String = "",
  /** Servers localise shelf titles through this key, e.g. "LabelContinueListening". */
  val labelStringKey: String? = null,
  val type: String = "",
  val entities: List<LibraryItemDto> = emptyList()
)

data class Session(
  val serverUrl: String,
  val token: String,
  val userId: String,
  val username: String,
  val defaultLibraryId: String?,
  /** Optional LAN address of the same server, preferred while on Wi-Fi. */
  val localServerUrl: String? = null
)

@Serializable
data class MeResponse(
  val id: String = "",
  val username: String = "",
  val mediaProgress: List<ProgressDto> = emptyList()
)
