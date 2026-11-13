package io.github.jnesew.paudiobooks.library

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class PlayableTrack(val id: String, val assetId: String, val relativePath: String, val durationMs: Long?, val chapters: List<Chapter>, val documentSource: DocumentSource? = null)
data class PlayableBook(val id: String, val title: String, val author: String?, val tracks: List<PlayableTrack>, val progress: ReadingState?, val coverPath: String? = null)
data class TrackDetails(val track: BookTrack, val asset: AudioAsset, val documentSource: DocumentSource? = null)
data class BookDetails(val book: Book, val tracks: List<TrackDetails>, val chapters: List<Chapter>, val progress: ReadingState?, val bookmarks: List<Bookmark>, val linkedRoot: LinkedBookRoot? = null)
data class BookSummary(val book: Book, val durationMs: Long?, val progress: ReadingState?, val trackCount: Int, val positionMs: Long?)

class LibraryRepository(private val database: LibraryDatabase) {
    companion object {
        const val MAX_BOOKMARK_NOTE_LENGTH = 4096
    }
    private val progressMutex = Mutex()
    val books = database.books().observeBooks()
    val summaries: Flow<List<BookSummary>> = combine(books, database.library().observeAllTracks(), database.library().observeAssets(), database.library().observeAllProgress()) { books, tracks, assets, states ->
        val assetsById = assets.associateBy { it.id }
        books.map { book ->
            val ownTracks = tracks.filter { it.bookId == book.id }
            val durations = ownTracks.map { assetsById[it.assetId]?.durationMs }
            fun sumKnown(values: List<Long?>): Long? {
                var sum = 0L
                for (value in values) {
                    val n = value ?: return null
                    if (n < 0 || sum > Long.MAX_VALUE - n) return null
                    sum += n
                }
                return sum
            }
            val duration = if (durations.isEmpty()) null else sumKnown(durations)
            val state = states.firstOrNull { it.bookId == book.id }
            val index = ownTracks.indexOfFirst { it.id == state?.trackId }
            val preceding = if (index >= 0) sumKnown(durations.take(index)) else null
            val position = if (preceding != null && state != null && state.offsetMs >= 0 && preceding <= Long.MAX_VALUE - state.offsetMs &&
                (durations[index] == null || state.offsetMs <= durations[index]!!)) preceding + state.offsetMs else null
            BookSummary(book, duration, state, ownTracks.size, position)
        }
    }
    fun observeBookDetails(bookId: String): Flow<BookDetails?> {
        val base = combine(database.books().observe(bookId), database.library().observeAllTracks(), database.library().observeAssets(), database.library().observeAllProgress(), database.sources().observeSources()) { book, tracks, assets, states, sources ->
            if (book == null) null else {
                val byId = assets.associateBy { it.id }
                val ownTracks = tracks.filter { it.bookId == bookId }.mapNotNull { track -> byId[track.assetId]?.let { TrackDetails(track, it, sources.firstOrNull { source -> source.assetId == it.id }) } }
                Triple(book, ownTracks, states.firstOrNull { it.bookId == bookId })
            }
        }
        return combine(base, database.library().observeChapters(bookId), database.library().observeBookmarks(bookId),database.sources().observeRoot(bookId)) { data, chapters, bookmarks, root ->
            data?.let { tuple ->
                val byTrack=chapters.groupBy { it.trackId }
                val navigation=tuple.second.flatMap { item -> fileBoundaryFallback(item.track.id,item.asset.durationMs,byTrack[item.track.id].orEmpty()) }
                BookDetails(tuple.first, tuple.second, navigation, tuple.third, bookmarks, root)
            }
        }
    }
    /** Audio bytes owned by the library, counted once per asset. Excludes art and staging. */
    suspend fun audioStorageBytes(): Long = database.library().assetSizes().fold(0L) { total, size ->
        require(size >= 0 && total <= Long.MAX_VALUE - size)
        total + size
    }
    suspend fun bookExists(bookId: String): Boolean = database.books().get(bookId) != null
    suspend fun playableBook(bookId: String): PlayableBook? = database.withTransaction {
        val book = database.books().get(bookId) ?: return@withTransaction null
        val tracks = database.library().tracks(bookId).map { track ->
            val asset = database.library().asset(track.assetId) ?: return@withTransaction null
            PlayableTrack(track.id, asset.id, asset.relativePath, asset.durationMs, fileBoundaryFallback(track.id,asset.durationMs,database.library().chapters(track.id)), database.sources().source(asset.id))
        }
        if (tracks.isEmpty()) return@withTransaction null
        PlayableBook(book.id, book.titleOverride ?: book.title, book.authorOverride ?: book.importedAuthor, tracks, database.library().progress(bookId), book.coverOverridePath ?: book.coverPath)
    }
    suspend fun updateOverrides(bookId: String, title: String?, author: String?, narrator: String?, series: String?, translator: String? = null, seriesNumber: String? = null): Boolean {
        listOf(title, author, narrator, series, translator, seriesNumber).forEach { require(it == null || it.length <= 512) }
        require(title == null || title.isNotBlank())
        return database.books().setOverrides(bookId, title, author, narrator, series, translator, seriesNumber) == 1
    }
    suspend fun addBookmark(bookId: String, trackId: String, offsetMs: Long, note: String?,
                            bookmarkId: String = UUID.randomUUID().toString()): String? {
        require(offsetMs >= 0 && (note == null || note.length <= 4096))
        require(runCatching { UUID.fromString(bookmarkId).toString() == bookmarkId }.getOrDefault(false))
        return database.withTransaction {
            if (database.library().tracks(bookId).none { it.id == trackId }) return@withTransaction null
            // A recreated editor may retry after the transaction committed but its
            // original UI coroutine was cancelled. Preserve that exact operation.
            database.library().bookmark(bookmarkId)?.let { existing ->
                return@withTransaction bookmarkId.takeIf {
                    existing.bookId == bookId && existing.trackId == trackId &&
                        existing.offsetMs == offsetMs && existing.note == note
                }
            }
            database.library().insertBookmark(Bookmark(bookmarkId, bookId, trackId, offsetMs, note, System.currentTimeMillis()))
            bookmarkId
        }
    }
    suspend fun updateBookmarkNote(bookId: String, bookmarkId: String, note: String?): Boolean {
        require(note == null || note.length <= MAX_BOOKMARK_NOTE_LENGTH)
        val cleanedNote = if (note == null || note.isBlank()) null else note.trim()
        return database.library().updateBookmarkNote(bookId, bookmarkId, cleanedNote) == 1
    }
    suspend fun deleteBookmark(bookId: String, bookmarkId: String): Boolean = database.library().deleteBookmark(bookId, bookmarkId) == 1
    /** For a book not currently controlled by the playback service. */
    suspend fun setFinished(bookId: String, finished: Boolean): Boolean = progressMutex.withLock {
        database.withTransaction {
            if (database.books().get(bookId) == null) return@withTransaction false
            val tracks = database.library().tracks(bookId)
            if (tracks.isEmpty()) return@withTransaction false
            val track = if (finished) tracks.last() else tracks.first()
            val offset = if (finished) database.library().asset(track.assetId)?.durationMs?.coerceAtLeast(0) ?: 0 else 0
            val speed = database.library().progress(bookId)?.speed?.takeIf { it.isFinite() && it in 0.5f..3f } ?: 1f
            database.library().saveProgress(ReadingState(bookId, track.id, offset, speed, finished, System.currentTimeMillis()))
            true
        }
    }
    suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long, speed: Float, finished: Boolean = false): Boolean {
        require(positionMs >= 0 && speed.isFinite() && speed > 0)
        return progressMutex.withLock {
            database.withTransaction {
                if (database.books().get(bookId) == null || database.library().tracks(bookId).none { it.id == trackId }) return@withTransaction false
                database.library().saveProgress(ReadingState(bookId, trackId, positionMs, speed, finished, System.currentTimeMillis()))
                true
            }
        }
    }
}

/** Derived navigation for legacy/no-chapter assets. Never writes or replaces imported rows. */
private fun fileBoundaryFallback(trackId: String, durationMs: Long?, chapters: List<Chapter>): List<Chapter> {
    if (chapters.isNotEmpty()) return chapters.sortedBy { it.position }
    if (durationMs==0L) return emptyList()
    val chapterId=UUID.nameUUIDFromBytes(("paudiobooks:file-boundary:"+trackId).toByteArray(Charsets.UTF_8)).toString()
    return listOf(Chapter(chapterId,trackId,0,0,durationMs?.takeIf { it>0 },null,"file"))
}
